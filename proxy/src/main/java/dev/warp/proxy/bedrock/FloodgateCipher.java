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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Floodgate's cipher, as Geyser writes the data and Floodgate reads it: AES-GCM with the raw bytes
 * of {@code key.pem} as the key, in Floodgate's own framing.
 *
 * <p>The wire form, verified against Floodgate's {@code FloodgateCipher} and {@code AesCipher}
 * (GeyserMC/Geyser, {@code common/src/main/java/org/geysermc/floodgate/crypto}):
 *
 * <pre>
 * ^Floodgate^ &lt;version byte: 0x3E + version&gt; base64(iv) ! base64(ciphertext + 128-bit tag)
 * </pre>
 *
 * <p>The IV is 12 random bytes, the tag 128 bits, the Base64 the standard alphabet with padding,
 * the splitter the byte {@code 0x21}. Floodgate has one version, 0. The key is 16, 24 or 32 bytes,
 * read from the file as they are: Floodgate's {@code AesKeyProducer} wraps the file's bytes in a
 * {@code SecretKeySpec} without decoding them, and generates 128-bit keys.
 *
 * <p>Thread-safe: a {@link Cipher} is created per call, which the handshake, not the hot path, pays
 * for once per Bedrock login.
 */
public final class FloodgateCipher {

  /** The text every blob starts with. */
  public static final String IDENTIFIER = "^Floodgate^";

  /** The only version Floodgate has written so far. */
  public static final int VERSION = 0;

  /** Added to the version to make the byte after the identifier printable. */
  static final int VERSION_BASE = 0x3E;

  /** The byte between the Base64 of the IV and the Base64 of the ciphertext. */
  static final char SPLITTER = '!';

  /** The header of a version 0 blob: the identifier and the version byte. */
  public static final String HEADER = IDENTIFIER + (char) (VERSION_BASE + VERSION);

  /** The IV's length in bytes. */
  public static final int IV_LENGTH = 12;

  /** The GCM tag's length in bytes. */
  public static final int TAG_LENGTH = 16;

  private static final int TAG_BITS = TAG_LENGTH * 8;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private final SecretKeySpec key;
  private final SecureRandom random;

  /**
   * Creates the cipher of a key.
   *
   * @param key the raw key, 16, 24 or 32 bytes: the bytes of Floodgate's {@code key.pem}
   * @throws IllegalArgumentException if the key has another length
   */
  public FloodgateCipher(byte[] key) {
    this(key, new SecureRandom());
  }

  /**
   * Creates the cipher of a key, with the source of its IVs.
   *
   * @param key the raw key, 16, 24 or 32 bytes
   * @param random where the IVs come from
   * @throws IllegalArgumentException if the key has another length
   */
  public FloodgateCipher(byte[] key, SecureRandom random) {
    if (!isValidKeyLength(key.length)) {
      throw new IllegalArgumentException(
          "a Floodgate key is 16, 24 or 32 bytes, this one is " + key.length);
    }
    this.key = new SecretKeySpec(key, "AES");
    this.random = random;
  }

  /**
   * Returns whether a key of this length is one AES accepts.
   *
   * @param length the key's length in bytes
   * @return {@code true} for 16, 24 and 32
   */
  public static boolean isValidKeyLength(int length) {
    return length == 16 || length == 24 || length == 32;
  }

  /**
   * Returns whether a part of a handshake's host name is a Floodgate blob of any version: it starts
   * with the identifier and has a version byte.
   *
   * @param part the part, between two NULs
   * @return {@code true} if it claims to be Floodgate data
   */
  public static boolean hasHeader(String part) {
    return part.length() > IDENTIFIER.length() && part.startsWith(IDENTIFIER);
  }

  /**
   * Returns the version a blob declares.
   *
   * @param part a part that {@link #hasHeader} accepted
   * @return the version, {@link #VERSION} for every blob Floodgate writes today
   */
  public static int version(String part) {
    return part.charAt(IDENTIFIER.length()) - VERSION_BASE;
  }

  /**
   * Decrypts a blob.
   *
   * @param blob the blob, header included, as it came in the host name
   * @return the plaintext
   * @throws GeneralSecurityException if the blob is not in Floodgate's form, was not encrypted with
   *     this key, or was altered: the tag does not check
   */
  public Decrypted decrypt(String blob) throws GeneralSecurityException {
    if (!blob.startsWith(HEADER)) {
      throw new GeneralSecurityException("not a Floodgate version 0 blob");
    }
    int splitter = blob.indexOf(SPLITTER, HEADER.length());
    if (splitter < 0) {
      throw new GeneralSecurityException("no splitter between the IV and the ciphertext");
    }
    byte[] iv;
    byte[] ciphertext;
    try {
      iv = Base64.getDecoder().decode(blob.substring(HEADER.length(), splitter));
      ciphertext = Base64.getDecoder().decode(blob.substring(splitter + 1));
    } catch (IllegalArgumentException notBase64) {
      throw new GeneralSecurityException("the IV or the ciphertext is not Base64", notBase64);
    }
    if (iv.length != IV_LENGTH) {
      throw new GeneralSecurityException("the IV is " + iv.length + " bytes, not " + IV_LENGTH);
    }
    if (ciphertext.length < TAG_LENGTH) {
      throw new GeneralSecurityException("the ciphertext is shorter than its tag");
    }
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
    byte[] plaintext = cipher.doFinal(ciphertext);
    byte[] tag = Arrays.copyOfRange(ciphertext, ciphertext.length - TAG_LENGTH, ciphertext.length);
    return new Decrypted(new String(plaintext, StandardCharsets.UTF_8), tag);
  }

  /**
   * Encrypts a plaintext into a blob Floodgate reads, under a fresh IV.
   *
   * @param plaintext the text, such as {@link FloodgateData#encode()}
   * @return the blob, header included, ready to append to a host name after a NUL
   */
  public String encrypt(String plaintext) {
    byte[] iv = new byte[IV_LENGTH];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return HEADER
          + Base64.getEncoder().encodeToString(iv)
          + SPLITTER
          + Base64.getEncoder().encodeToString(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is not available", e);
    }
  }

  /**
   * A decrypted blob.
   *
   * @param plaintext the text Geyser encrypted
   * @param tag the blob's authentication tag, unique to this encryption: what a replay repeats
   */
  public record Decrypted(String plaintext, byte[] tag) {}
}
