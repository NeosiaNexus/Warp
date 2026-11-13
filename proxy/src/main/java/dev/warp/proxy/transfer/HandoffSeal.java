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
package dev.warp.proxy.transfer;

import dev.warp.proxy.config.HandoffConfig;
import dev.warp.proxy.config.SecretFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * Seals handoff tickets with AES-256-GCM under the key the proxies of a network share: what a
 * ticket says stays between them, and a ticket changed in transit does not open.
 *
 * <p><b>Format</b>: a version byte, the first {@value #KEY_ID_LENGTH} bytes of the SHA-256 of the
 * key (which key sealed it, so that a key mismatch between proxies reads as such and not as a
 * forgery), a random {@value #IV_LENGTH}-byte IV, then the encrypted ticket and its 16-byte tag.
 * The version, key id and IV are authenticated as associated data.
 *
 * <p><b>Key</b>: {@value HandoffConfig#ENV_VARIABLE} if set (random text of 16 characters or more,
 * the same on every proxy), else the file {@code transfers.handoff.secret-file}, generated at first
 * start with 32 random bytes and readable by its owner only. The AES key is derived from either
 * with HMAC-SHA256, so that the secret is never used as a key for two purposes; it is a secret of
 * its own, never the forwarding secret backends hold.
 *
 * <p>Thread-safe: each call builds its own {@link Cipher}.
 */
final class HandoffSeal {

  /** The format of tickets this proxy writes and reads. */
  static final byte VERSION = 1;

  static final int KEY_ID_LENGTH = 4;
  static final int IV_LENGTH = 12;
  static final int TAG_BITS = 128;

  /** The header: version, key id, IV. */
  static final int HEADER_LENGTH = 1 + KEY_ID_LENGTH + IV_LENGTH;

  /** The shortest secret accepted, from the environment or the file: 128 bits if random. */
  static final int MIN_SECRET_LENGTH = 16;

  private static final int SECRET_LENGTH = 32;
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final byte[] DERIVATION_LABEL =
      "warp:handoff/aes-256-gcm/v1".getBytes(StandardCharsets.US_ASCII);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;
  private final byte[] keyId;
  private final boolean generated;
  private final boolean shared;

  private HandoffSeal(byte[] secret, boolean generated, boolean shared) {
    byte[] derived = hmac(secret, DERIVATION_LABEL);
    this.key = new SecretKeySpec(derived, "AES");
    this.keyId = Arrays.copyOf(sha256(derived), KEY_ID_LENGTH);
    this.generated = generated;
    this.shared = shared;
  }

  /**
   * Loads the key: from the environment variable if set, else from the file, generated when absent.
   *
   * @param file the key file
   * @param fromEnvironment the value of {@value HandoffConfig#ENV_VARIABLE}, or {@code null}
   * @return the seal
   * @throws IOException if the file cannot be read, holds no key, or cannot be created
   */
  static HandoffSeal load(Path file, @Nullable String fromEnvironment) throws IOException {
    if (fromEnvironment != null && !fromEnvironment.isBlank()) {
      byte[] text = fromEnvironment.strip().getBytes(StandardCharsets.UTF_8);
      if (text.length < MIN_SECRET_LENGTH) {
        // Never the value itself: a secret does not belong in a log.
        throw new IOException(
            HandoffConfig.ENV_VARIABLE
                + " is too short to seal handoff tickets with: "
                + MIN_SECRET_LENGTH
                + " characters or more, random (openssl rand -base64 32)");
      }
      return new HandoffSeal(text, false, true);
    }
    byte[] secret = read(file);
    if (secret != null) {
      return new HandoffSeal(secret, false, false);
    }
    byte[] fresh = new byte[SECRET_LENGTH];
    RANDOM.nextBytes(fresh);
    write(file, fresh);
    return new HandoffSeal(fresh, true, false);
  }

  /**
   * A seal under {@code secret}, for tests and for a proxy that keeps nothing on disk.
   *
   * @param secret the shared secret
   * @return the seal
   */
  static HandoffSeal of(byte[] secret) {
    return new HandoffSeal(secret.clone(), false, true);
  }

  /**
   * Returns whether the key file was absent and generated at this start.
   *
   * @return {@code true} if generated
   */
  boolean generated() {
    return generated;
  }

  /**
   * Returns whether the key comes from the environment, which suggests the proxies share it.
   *
   * @return {@code true} for {@value HandoffConfig#ENV_VARIABLE}
   */
  boolean fromEnvironment() {
    return shared;
  }

  /**
   * Returns whether {@code secret} is the one this seal's key derives from, in constant time.
   *
   * @param secret another secret of the proxy, which must not seal tickets
   * @return {@code true} if tickets are sealed under it
   */
  boolean sealsUnder(byte[] secret) {
    return MessageDigest.isEqual(hmac(secret, DERIVATION_LABEL), key.getEncoded());
  }

  // ---------------------------------------------------------------------------
  // Sealing
  // ---------------------------------------------------------------------------

  /**
   * Seals a ticket's content.
   *
   * @param body the plaintext
   * @return the cookie's payload
   */
  byte[] seal(byte[] body) {
    byte[] sealed = new byte[HEADER_LENGTH + body.length + TAG_BITS / 8];
    sealed[0] = VERSION;
    System.arraycopy(keyId, 0, sealed, 1, KEY_ID_LENGTH);
    byte[] iv = new byte[IV_LENGTH];
    RANDOM.nextBytes(iv);
    System.arraycopy(iv, 0, sealed, 1 + KEY_ID_LENGTH, IV_LENGTH);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(sealed, 0, HEADER_LENGTH);
      int written = cipher.doFinal(body, 0, body.length, sealed, HEADER_LENGTH);
      if (written != body.length + TAG_BITS / 8) {
        throw new IllegalStateException("AES-GCM wrote " + written + " bytes");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is a mandatory JCA algorithm", e);
    }
    return sealed;
  }

  /** What opening a payload gave: the plaintext, or why there is none. */
  sealed interface Opened {
    /** The payload opened. */
    record Body(byte[] plaintext) implements Opened {}

    /** It did not. */
    record Refused(HandoffRejection reason) implements Opened {}
  }

  /**
   * Opens a cookie's payload.
   *
   * @param sealed the payload
   * @return the plaintext, or the reason it does not open
   */
  Opened open(byte[] sealed) {
    if (sealed.length < HEADER_LENGTH + TAG_BITS / 8) {
      return new Opened.Refused(HandoffRejection.MALFORMED);
    }
    if (sealed[0] != VERSION) {
      return new Opened.Refused(HandoffRejection.UNKNOWN_VERSION);
    }
    if (!Arrays.equals(sealed, 1, 1 + KEY_ID_LENGTH, keyId, 0, KEY_ID_LENGTH)) {
      return new Opened.Refused(HandoffRejection.FOREIGN_KEY);
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(TAG_BITS, sealed, 1 + KEY_ID_LENGTH, IV_LENGTH));
      cipher.updateAAD(sealed, 0, HEADER_LENGTH);
      return new Opened.Body(cipher.doFinal(sealed, HEADER_LENGTH, sealed.length - HEADER_LENGTH));
    } catch (AEADBadTagException forged) {
      return new Opened.Refused(HandoffRejection.TAMPERED);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is a mandatory JCA algorithm", e);
    }
  }

  // ---------------------------------------------------------------------------
  // The key file
  // ---------------------------------------------------------------------------

  /** Reads the key file: one line of Base64, after comment lines; {@code null} if absent. */
  private static byte @Nullable [] read(Path file) throws IOException {
    if (!Files.exists(file)) {
      return null;
    }
    for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
      String text = line.strip();
      if (text.isEmpty() || text.startsWith("#")) {
        continue;
      }
      try {
        byte[] secret = Base64.getDecoder().decode(text);
        if (secret.length >= MIN_SECRET_LENGTH) {
          return secret;
        }
      } catch (IllegalArgumentException malformed) {
        // reported below
      }
      break;
    }
    throw new IOException(
        "The transfer handoff key file "
            + file
            + " does not hold a Base64 key of 16 bytes or more: delete it to generate a new one,"
            + " or set "
            + HandoffConfig.ENV_VARIABLE);
  }

  /** Writes a new key file, readable by its owner only; never over an existing one. */
  private static void write(Path file, byte[] secret) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    String text =
        "# The key Warp seals transfer handoff tickets with. Generated by Warp: give every proxy of\n"
            + "# the network the same file (or set "
            + HandoffConfig.ENV_VARIABLE
            + " on each), and keep it secret.\n"
            + Base64.getEncoder().encodeToString(secret)
            + "\n";
    // Created readable by its owner only: the secret is never on disk with wider permissions.
    SecretFiles.create(file, text);
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is a mandatory JCA algorithm", e);
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is a mandatory JCA algorithm", e);
    }
  }
}
