/*
 * Copyright (C) 2026 Warp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.warp.proxy.antibot;

import dev.warp.api.antibot.VerifiedKey;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.logging.LogCode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The signed tokens that carry a verification across a transfer (section 9.5 of the anti-bot
 * research, "Fleets"). A player verified before it authenticated, during an attack, is sent back to
 * the proxy with a Transfer packet; its client comes back with the token in the {@value
 * #COOKIE_KEY} cookie, and whichever proxy of the fleet receives it lets it authenticate without
 * verifying it again. No state is shared: the token is self-contained and signed.
 *
 * <p><b>Format</b> ({@value #LENGTH} bytes): a version byte, the first 4 bytes of the SHA-256 of
 * the signing key (which key to check with), the time of issue in milliseconds, the 16 bytes of the
 * identity's name-and-network key, a flags byte, and the first 16 bytes of the HMAC-SHA256 of all
 * of that under the signing key. A token is valid while it is younger than its lifetime, its tag
 * checks in constant time, and its identity is the one logging in: it cannot be lent to another
 * name or network, nor replayed after the attack it was issued in.
 *
 * <p><b>Keys.</b> Random, generated at first start into {@code verification.token.secret-file} next
 * to the verified store, or set for a whole fleet in {@code WARP_ANTIBOT_TOKEN_SECRET}. The current
 * key signs; the previous one is still accepted, so that a rotation never strands a player
 * mid-transfer. The proxy rotates its own file's key every {@code rotate-after} (and on {@code
 * /warp antibot token rotate}); a key from the environment is the operator's to rotate.
 *
 * <p>Thread-safe: tokens are issued and checked on event loops, rotation happens on its own.
 */
public final class VerificationTokens {

  private static final Logger logger = LoggerFactory.getLogger(VerificationTokens.class);

  /** The cookie the token travels in. */
  public static final String COOKIE_KEY = "warp:verified";

  static final int KEY_ID_LENGTH = 4;
  static final int TAG_LENGTH = 16;

  /** The length of a token. */
  public static final int LENGTH = 1 + KEY_ID_LENGTH + 8 + 16 + 1 + TAG_LENGTH;

  static final int KEY_LENGTH = 32;
  static final byte VERSION = 1;
  static final int FLAG_TRUSTED = 1;

  private static final String ALGORITHM = "HmacSHA256";
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * What a valid token says.
   *
   * @param issuedAt when it was issued
   * @param trusted whether the verification it carries trusts the identity (no flags raised)
   */
  public record Claim(Instant issuedAt, boolean trusted) {}

  /** A key, its id and when it started signing. */
  private record Key(byte[] secret, byte[] id, long sinceMillis) {
    static Key of(byte[] secret, long sinceMillis) {
      return new Key(secret, Arrays.copyOf(sha256(secret), KEY_ID_LENGTH), sinceMillis);
    }
  }

  private final Duration lifetime;
  private final Duration rotateAfter;
  private final @Nullable Path file;
  private final LongSupplier millisClock;
  private volatile Key current;
  private volatile @Nullable Key previous;
  private volatile boolean rotating;
  private final LongAdder issued = new LongAdder();
  private final LongAdder accepted = new LongAdder();
  private final LongAdder rejected = new LongAdder();

  /**
   * Loads the keys: from the environment variable if set, else from the file (generated when
   * absent), else a random key for a proxy that keeps nothing on disk.
   *
   * @param lifetime how long a token stays valid
   * @param rotateAfter how often the file's key is replaced; zero never
   * @param file the key file, or {@code null} for a key in memory only
   * @param fromEnvironment the value of {@code WARP_ANTIBOT_TOKEN_SECRET}, or {@code null}
   * @param millisClock the wall clock
   * @throws IOException if the file cannot be read or written
   */
  public VerificationTokens(
      Duration lifetime,
      Duration rotateAfter,
      @Nullable Path file,
      @Nullable String fromEnvironment,
      LongSupplier millisClock)
      throws IOException {
    this.lifetime = lifetime;
    this.millisClock = millisClock;
    long now = millisClock.getAsLong();
    if (fromEnvironment != null && !fromEnvironment.isBlank()) {
      this.file = null;
      this.rotateAfter = Duration.ZERO;
      this.current = Key.of(sha256(fromEnvironment.strip().getBytes(StandardCharsets.UTF_8)), now);
      return;
    }
    this.rotateAfter = rotateAfter;
    this.file = file;
    if (file == null) {
      this.current = Key.of(randomKey(), now);
      return;
    }
    List<Key> loaded = load(file);
    if (loaded.isEmpty()) {
      this.current = Key.of(randomKey(), now);
      save(file, current, null);
      logger.info("Anti-bot: generated the key verification tokens are signed under in {}", file);
    } else {
      this.current = loaded.getFirst();
      this.previous = loaded.size() > 1 ? loaded.get(1) : null;
    }
  }

  // ---------------------------------------------------------------------------
  // Tokens
  // ---------------------------------------------------------------------------

  /**
   * Issues a token for an identity verified now.
   *
   * @param identity the name-and-network key of the login verified
   * @param trusted whether the verification trusts it (fewer than two flags)
   * @return the token, to store in the client's {@value #COOKIE_KEY} cookie
   */
  public byte[] issue(VerifiedKey identity, boolean trusted) {
    Key key = current;
    ByteBuffer buf = ByteBuffer.allocate(LENGTH);
    buf.put(VERSION);
    buf.put(key.id());
    buf.putLong(millisClock.getAsLong());
    buf.putLong(identity.high());
    buf.putLong(identity.low());
    buf.put((byte) (trusted ? FLAG_TRUSTED : 0));
    byte[] tag = tag(key, buf.array(), buf.position());
    buf.put(tag, 0, TAG_LENGTH);
    issued.increment();
    return buf.array();
  }

  /**
   * Checks a token a client returned.
   *
   * @param token the cookie's payload
   * @param identity the name-and-network key of the login that returned it
   * @return what the token says, or {@code null} if it is malformed, forged, expired, signed under
   *     a key this proxy no longer accepts, or issued to another identity
   */
  public @Nullable Claim verify(byte[] token, VerifiedKey identity) {
    Claim claim = check(token, identity);
    if (claim == null) {
      rejected.increment();
    } else {
      accepted.increment();
    }
    return claim;
  }

  private @Nullable Claim check(byte[] token, VerifiedKey identity) {
    if (token.length != LENGTH || token[0] != VERSION) {
      return null;
    }
    Key key = keyFor(Arrays.copyOfRange(token, 1, 1 + KEY_ID_LENGTH));
    if (key == null) {
      return null;
    }
    int body = LENGTH - TAG_LENGTH;
    byte[] expected = tag(key, token, body);
    if (!MessageDigest.isEqual(
        Arrays.copyOf(expected, TAG_LENGTH), Arrays.copyOfRange(token, body, LENGTH))) {
      return null;
    }
    ByteBuffer buf = ByteBuffer.wrap(token, 1 + KEY_ID_LENGTH, body - 1 - KEY_ID_LENGTH);
    long issuedAt = buf.getLong();
    long high = buf.getLong();
    long low = buf.getLong();
    byte flags = buf.get();
    if (high != identity.high() || low != identity.low()) {
      return null;
    }
    long age = millisClock.getAsLong() - issuedAt;
    if (age < 0 || age > lifetime.toMillis()) {
      return null;
    }
    return new Claim(Instant.ofEpochMilli(issuedAt), (flags & FLAG_TRUSTED) != 0);
  }

  private @Nullable Key keyFor(byte[] id) {
    Key key = current;
    if (Arrays.equals(key.id(), id)) {
      return key;
    }
    Key old = previous;
    return old != null && Arrays.equals(old.id(), id) ? old : null;
  }

  private static byte[] tag(Key key, byte[] data, int length) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key.secret(), ALGORITHM));
      mac.update(data, 0, length);
      return mac.doFinal();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is a mandatory JCA algorithm", e);
    }
  }

  // ---------------------------------------------------------------------------
  // Rotation
  // ---------------------------------------------------------------------------

  /**
   * Returns whether the proxy may rotate the key: it owns a file, and no rotation is under way.
   *
   * @return {@code true} unless the key comes from the environment
   */
  public boolean rotatable() {
    return file != null;
  }

  /**
   * Returns the lifetime of a token.
   *
   * @return the lifetime
   */
  public Duration lifetime() {
    return lifetime;
  }

  /**
   * Returns when the current key started signing.
   *
   * @return the instant
   */
  public Instant keySince() {
    return Instant.ofEpochMilli(current.sinceMillis());
  }

  /**
   * Replaces the signing key with a new random one, keeping the current one as the previous key,
   * and writes both to the file. Called by staff, and by {@link #tick} when the key is old.
   *
   * @param source who asks, for the log
   * @throws IOException if the file cannot be written: the keys in memory are unchanged
   * @throws IllegalStateException if the key is not the proxy's to rotate
   */
  public synchronized void rotate(String source) throws IOException {
    Path target = file;
    if (target == null) {
      throw new IllegalStateException(
          "the token key comes from the environment: rotate it there, on every proxy");
    }
    Key next = Key.of(randomKey(), millisClock.getAsLong());
    Key old = current;
    save(target, next, old);
    previous = old;
    current = next;
    logger.info(
        "Anti-bot: rotated the key verification tokens are signed under ({}); tokens under the"
            + " previous key stay valid for {}",
        source,
        AntiBotService.format(lifetime));
  }

  /** Rotates the key once it is older than {@code rotate-after}; called once a second. */
  public void tick() {
    if (file == null || rotateAfter.isZero() || rotating) {
      return;
    }
    if (millisClock.getAsLong() - current.sinceMillis() < rotateAfter.toMillis()) {
      return;
    }
    rotating = true;
    // Off the event loop: a file write.
    Thread.ofVirtual()
        .name("warp-antibot-token-rotation")
        .start(
            () -> {
              try {
                rotate("scheduled");
              } catch (IOException | RuntimeException e) {
                logger.warn(
                    LogCode.ANTIBOT_TOKEN_KEY_UNWRITABLE.marker(),
                    "Anti-bot: cannot rotate the token key in {}: {}",
                    file,
                    e.getMessage());
              } finally {
                rotating = false;
              }
            });
  }

  // ---------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------

  /**
   * Returns how many tokens were issued since the start.
   *
   * @return the count
   */
  public long issued() {
    return issued.sum();
  }

  /**
   * Returns how many returned tokens were valid.
   *
   * @return the count
   */
  public long accepted() {
    return accepted.sum();
  }

  /**
   * Returns how many returned tokens were refused.
   *
   * @return the count
   */
  public long rejected() {
    return rejected.sum();
  }

  // ---------------------------------------------------------------------------
  // The key file
  // ---------------------------------------------------------------------------

  /**
   * Reads the key file: one key per line, Base64 then the time it started signing in epoch
   * milliseconds, the current key first.
   */
  private static List<Key> load(Path file) throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<Key> keys = new ArrayList<>(2);
    for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
      String text = line.strip();
      if (text.isEmpty() || text.startsWith("#")) {
        continue;
      }
      String[] fields = text.split("\\s+");
      try {
        byte[] secret = Base64.getDecoder().decode(fields[0]);
        long since = fields.length > 1 ? Long.parseLong(fields[1]) : 0;
        if (secret.length >= 16 && keys.size() < 2) {
          keys.add(Key.of(secret, since));
          continue;
        }
      } catch (IllegalArgumentException malformed) {
        // reported below
      }
      throw new IOException(
          "The anti-bot token key file "
              + file
              + " is not one or two lines of a Base64 key of 16 bytes or more: delete it to"
              + " generate a new one, or set "
              + AntiBotConfig.ENV_TOKEN_SECRET);
    }
    return keys;
  }

  /** Writes the keys, current first, to a temporary file renamed over the key file. */
  private static void save(Path file, Key current, @Nullable Key previous) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    StringBuilder text = new StringBuilder();
    text.append("# The keys anti-bot verification tokens are signed under: the current one, then")
        .append(" the previous one still accepted. Generated by Warp.\n");
    append(text, current);
    if (previous != null) {
      append(text, previous);
    }
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(
        temporary,
        text.toString(),
        StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    restrict(temporary);
    Files.move(
        temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void append(StringBuilder text, Key key) {
    text.append(Base64.getEncoder().encodeToString(key.secret()))
        .append(' ')
        .append(key.sinceMillis())
        .append('\n');
  }

  private static void restrict(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException e) {
      // Not a POSIX file system, or no permission to change them: the platform's defaults apply.
    }
  }

  private static byte[] randomKey() {
    byte[] key = new byte[KEY_LENGTH];
    RANDOM.nextBytes(key);
    return key;
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is a mandatory JCA algorithm", e);
    }
  }
}
