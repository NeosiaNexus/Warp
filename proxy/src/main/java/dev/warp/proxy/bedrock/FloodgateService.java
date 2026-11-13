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
package dev.warp.proxy.bedrock;

import dev.warp.api.player.ClientPlatform.Bedrock;
import dev.warp.api.player.ClientPlatform.DeviceOs;
import dev.warp.api.player.ClientPlatform.InputMode;
import dev.warp.api.player.ClientPlatform.LinkedAccount;
import dev.warp.api.player.ClientPlatform.UiProfile;
import dev.warp.proxy.config.BedrockConfig;
import dev.warp.proxy.connection.LogThrottle;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Reads, verifies and re-emits Floodgate's data: what makes a connection from Geyser a Bedrock
 * player the proxy can vouch for.
 *
 * <p>At the handshake, {@link #read} looks for the one NUL-separated part of the host name that
 * carries Floodgate's header, decrypts it with the shared key, and checks what Floodgate itself
 * does not: that the blob was not seen before (a replay), that the address it names is the
 * connection's unless the Geyser host is trusted to say otherwise, and that the identity it carries
 * can log in. A blob that fails is a refusal with a reason, never a fallback to a Java player, so
 * that nobody logs in under a Bedrock name without the key.
 *
 * <p>The identity follows Floodgate: UUID {@code new UUID(0, xuid)} ({@code Utils.getJavaUuid}),
 * name {@code <prefix><gamertag>} with spaces replaced by underscores and cut to 16 characters
 * ({@code replace-spaces: true}, Floodgate's default), the linked Java account taking over both
 * when present. For backends that run Floodgate for its API, {@link #encodeForBackend} writes the
 * same data under a fresh IV with the from-proxy flag set, as Floodgate on a proxy does.
 */
public final class FloodgateService {

  /** The most a Java name has: Floodgate cuts the prefixed gamertag to it. */
  static final int MAX_NAME_LENGTH = 16;

  /** What a linked Java account's name has to be: a Java name. */
  private static final Pattern JAVA_NAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

  /** How often a refused blob is logged: the first at once, then one per interval. */
  private static final Duration WARNING_INTERVAL = Duration.ofMinutes(5);

  /**
   * No Floodgate: every handshake is a Java client's, whatever its host name carries. Declared
   * after the constants its constructor reads.
   */
  public static final FloodgateService DISABLED = new FloodgateService(BedrockConfig.DISABLED);

  private final @Nullable FloodgateCipher cipher;
  private final BedrockConfig config;
  private final FloodgateReplayGuard replays;
  private final LogThrottle warnings;

  /**
   * Creates the service of a configuration, on the system clock.
   *
   * @param config the settings
   */
  public FloodgateService(BedrockConfig config) {
    this(config, System::nanoTime);
  }

  /**
   * Creates the service of a configuration.
   *
   * @param config the settings
   * @param nanoClock the clock of the replay guard and the log throttle
   */
  public FloodgateService(BedrockConfig config, LongSupplier nanoClock) {
    this.config = config;
    this.cipher =
        config.floodgate().enabled() ? new FloodgateCipher(config.floodgate().key()) : null;
    this.replays =
        new FloodgateReplayGuard(
            FloodgateReplayGuard.DEFAULT_WINDOW, FloodgateReplayGuard.DEFAULT_CAPACITY, nanoClock);
    this.warnings = new LogThrottle(WARNING_INTERVAL, nanoClock);
  }

  /**
   * Returns whether Floodgate data is read.
   *
   * @return {@code true} when a key is configured
   */
  public boolean enabled() {
    return cipher != null;
  }

  /**
   * Returns the configured prefix of Bedrock names.
   *
   * @return the prefix, possibly empty
   */
  public String usernamePrefix() {
    return config.floodgate().usernamePrefix();
  }

  /**
   * Returns whether a name is one only a Bedrock player may use: it starts with the prefix. A Java
   * client using one claims to be Bedrock without proof.
   *
   * @param name the name a client logs in with
   * @return {@code true} if the prefix is not empty and the name starts with it
   */
  public boolean reservesName(String name) {
    String prefix = usernamePrefix();
    return enabled() && !prefix.isEmpty() && name.startsWith(prefix);
  }

  /**
   * Returns whether a backend gets the Floodgate data appended to its handshake.
   *
   * @param server the server's name
   * @return {@code true} to re-emit the data to it
   */
  public boolean forwardsTo(String server) {
    return enabled() && config.forwardsTo(server.toLowerCase(Locale.ROOT));
  }

  /**
   * Reads the Floodgate data of a handshake, if it carries one.
   *
   * @param serverAddress the host name field of the handshake, as sent
   * @param peer the address the TCP connection comes from: the Geyser host, or a load balancer
   * @param remote the address the connection is attributed to: the peer, or what a trusted PROXY
   *     protocol header said
   * @return what the data said, {@link Result.None} without data or with Floodgate off
   */
  public Result read(String serverAddress, InetSocketAddress peer, InetSocketAddress remote) {
    FloodgateCipher decrypting = cipher;
    if (decrypting == null) {
      return Result.NONE;
    }
    String blob = findBlob(serverAddress);
    if (blob == null) {
      return Result.NONE;
    }
    int version = FloodgateCipher.version(blob);
    if (version != FloodgateCipher.VERSION) {
      return new Result.Rejected(Reason.UNKNOWN_VERSION, "Floodgate data version " + version);
    }
    FloodgateCipher.Decrypted decrypted;
    try {
      decrypted = decrypting.decrypt(blob);
    } catch (GeneralSecurityException e) {
      return new Result.Rejected(Reason.NOT_VERIFIED, message(e));
    }
    FloodgateData data;
    try {
      data = FloodgateData.parse(decrypted.plaintext());
    } catch (IllegalArgumentException e) {
      return new Result.Rejected(Reason.MALFORMED, message(e));
    }
    if (!replays.accept(decrypted.tag())) {
      return new Result.Rejected(Reason.REPLAYED, "the same Floodgate data was already used");
    }
    return identify(data, peer, remote);
  }

  private static String message(Exception e) {
    String message = e.getMessage();
    return message != null ? message : e.getClass().getSimpleName();
  }

  private static @Nullable String findBlob(String serverAddress) {
    int nul = serverAddress.indexOf('\0');
    if (nul < 0) {
      return null;
    }
    for (String part : serverAddress.substring(nul + 1).split("\0", -1)) {
      if (FloodgateCipher.hasHeader(part)) {
        return part;
      }
    }
    return null;
  }

  private Result identify(FloodgateData data, InetSocketAddress peer, InetSocketAddress remote) {
    long xuid;
    try {
      xuid = data.xuidValue();
    } catch (IllegalArgumentException e) {
      return new Result.Rejected(Reason.INVALID_IDENTITY, message(e));
    }
    InetAddress address;
    try {
      address = InetAddress.ofLiteral(data.ip());
    } catch (IllegalArgumentException e) {
      return new Result.Rejected(
          Reason.INVALID_IDENTITY, "the address '" + data.ip() + "' is not an IP address");
    }
    InetSocketAddress realAddress = null;
    if (!address.equals(remote.getAddress())) {
      CidrRanges trusted = config.trustedGeyser();
      if (!trusted.contains(peer.getAddress())) {
        return new Result.Rejected(
            Reason.ADDRESS_MISMATCH,
            "the data names "
                + address.getHostAddress()
                + " but the connection comes from "
                + remote.getAddress().getHostAddress()
                + ", which is not in bedrock.geyser.trusted");
      }
      realAddress = new InetSocketAddress(address, remote.getPort());
    }

    UUID javaId;
    String javaName;
    Optional<LinkedAccount> linked = Optional.empty();
    FloodgateData.LinkedPlayer link = data.linkedPlayer();
    if (link != null) {
      if (!JAVA_NAME.matcher(link.javaUsername()).matches()) {
        return new Result.Rejected(
            Reason.INVALID_IDENTITY,
            "the linked Java name '" + link.javaUsername() + "' is not a Java name");
      }
      javaId = link.javaId();
      javaName = link.javaUsername();
      linked = Optional.of(new LinkedAccount(link.javaId(), link.javaUsername()));
    } else {
      javaId = new UUID(0, xuid);
      javaName = javaName(data.username());
      if (javaName == null) {
        return new Result.Rejected(
            Reason.INVALID_IDENTITY, "the gamertag '" + data.username() + "' cannot be a name");
      }
    }
    Bedrock platform =
        new Bedrock(
            xuid,
            data.username(),
            DeviceOs.fromId(data.deviceOs()),
            InputMode.fromId(data.inputMode()),
            UiProfile.fromId(data.uiProfile()),
            data.language(),
            linked,
            address);
    return new Result.Verified(new BedrockIdentity(data, platform, javaId, javaName, realAddress));
  }

  /**
   * Derives the Java name of a gamertag, as Floodgate does with {@code replace-spaces} on: the
   * prefix, the gamertag with its spaces as underscores, cut to 16 characters.
   *
   * @param gamertag the Bedrock name
   * @return the name, or {@code null} if the gamertag is empty or has a character no name can carry
   */
  @Nullable String javaName(String gamertag) {
    if (gamertag.isEmpty()) {
      return null;
    }
    String name = usernamePrefix() + gamertag.replace(' ', '_');
    if (name.length() > MAX_NAME_LENGTH) {
      name = name.substring(0, MAX_NAME_LENGTH);
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isISOControl(c) || Character.isWhitespace(c) || c == ';') {
        return null;
      }
    }
    return name;
  }

  /**
   * Writes the data for a backend running Floodgate: the same fields under a fresh IV, with the
   * from-proxy flag set as Floodgate on a proxy sets it.
   *
   * @param data the data read at the handshake
   * @return the blob to append to the backend's host name after a NUL
   * @throws IllegalStateException if Floodgate is off
   */
  public String encodeForBackend(FloodgateData data) {
    FloodgateCipher encrypting = cipher;
    if (encrypting == null) {
      throw new IllegalStateException("Floodgate is off");
    }
    return encrypting.encrypt(data.fromProxy(true).encode());
  }

  /**
   * Records a refusal for the log and says whether to log it, throttled so that a flood of bad
   * blobs cannot fill the logs while the first, which tells the operator the key is wrong, is
   * logged at once.
   *
   * @return {@code -1} to keep quiet, else how many refusals were held back since the last line
   */
  public long logRefusal() {
    return warnings.tryAcquire();
  }

  /** Why Floodgate data was refused. */
  public enum Reason {
    /** A version byte the proxy does not know; Floodgate has only written version 0. */
    UNKNOWN_VERSION,
    /** The blob is not in Floodgate's form, or was not encrypted with the proxy's key. */
    NOT_VERIFIED,
    /** The plaintext does not have Floodgate's twelve fields, or a number is not one. */
    MALFORMED,
    /** The same blob was already used to log in. */
    REPLAYED,
    /** The data names another address than the connection's, from an untrusted peer. */
    ADDRESS_MISMATCH,
    /** The XUID, address, gamertag or linked account cannot be a player's. */
    INVALID_IDENTITY;

    /**
     * Returns the reason as a metric or log label: {@code address_mismatch}.
     *
     * @return the label
     */
    public String label() {
      return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns what an operator whose own Geyser was refused should check, for the log line.
     *
     * @return the hint, empty when the refusal points at no setting
     */
    public String hint() {
      return switch (this) {
        case NOT_VERIFIED ->
            ". If this is your Geyser, its key.pem differs from bedrock.floodgate.key-file";
        case ADDRESS_MISMATCH ->
            ". If this is your Geyser, list the address it connects from in bedrock.geyser.trusted";
        case UNKNOWN_VERSION, MALFORMED ->
            ". If this is your Geyser, it writes a newer Floodgate format than this Warp reads";
        case REPLAYED, INVALID_IDENTITY -> "";
      };
    }
  }

  /** What a handshake's Floodgate data said. */
  public sealed interface Result {

    /** No data, or Floodgate off: a Java client. */
    Result NONE = new None();

    /**
     * Returns whether the handshake carried verified data.
     *
     * @return {@code true} for a {@link Verified} result
     */
    default boolean isVerified() {
      return this instanceof Verified;
    }

    /** No data, or Floodgate off: a Java client. */
    record None() implements Result {}

    /**
     * Verified data.
     *
     * @param identity who the player is
     */
    record Verified(BedrockIdentity identity) implements Result {}

    /**
     * Data that was refused.
     *
     * @param reason why
     * @param detail the technical cause, for the log
     */
    record Rejected(Reason reason, String detail) implements Result {}
  }
}
