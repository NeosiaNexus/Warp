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
package dev.warp.proxy.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.Arrays;

import javax.crypto.Cipher;

import org.jspecify.annotations.Nullable;

/**
 * Decrypts the values a client encrypts with the proxy's RSA key during login, the verify token and
 * the shared secret, without telling anyone whether a value was well padded.
 *
 * <p>The protocol fixes RSA with PKCS#1 v1.5 padding (RFC 8017 §7.2), which falls to
 * Bleichenbacher's attack wherever a client can tell a badly padded ciphertext from a well padded
 * one: by the reply, or by the time the reply takes. The JDK's {@code RSA/ECB/PKCS1Padding} throws
 * for a bad padding and returns otherwise, and building the exception takes time a well padded
 * value does not. So the value is decrypted without padding, and the padding is checked here in
 * time that does not depend on it. Since the length of each value is known, a valid block has
 * exactly one layout: {@code 00 02}, nonzero padding bytes, {@code 00}, then the value. Anything
 * else is invalid, including a well padded block that carries a value of another length, so that
 * case cannot be told apart either.
 *
 * <p>Each value gets its own {@link Cipher}: one is cheap next to the RSA operation, and a cipher
 * is not safe to share between the event loops.
 */
public final class Pkcs1Decryption {

  /** The RSA transformation: raw RSA, the padding is checked by {@link #paddingError}. */
  private static final String RAW_RSA = "RSA/ECB/NoPadding";

  /** The fewest padding bytes a PKCS#1 v1.5 encryption block has (RFC 8017 §7.2.1). */
  private static final int MIN_PADDING = 8;

  private Pkcs1Decryption() {}

  /**
   * Decrypts a value of {@code length} bytes.
   *
   * @param key the proxy's private key
   * @param ciphertext the value as the client encrypted it with the proxy's public key
   * @param length the length of the value
   * @return the value, or {@code null} if {@code ciphertext} is not the encryption of a value of
   *     {@code length} bytes, whatever the reason
   */
  public static byte @Nullable [] decrypt(PrivateKey key, byte[] ciphertext, int length) {
    byte[] block = rawDecrypt(key, ciphertext);
    if (block == null || paddingError(block, length) != 0) {
      return null;
    }
    return Arrays.copyOfRange(block, block.length - length, block.length);
  }

  /**
   * Checks that {@code ciphertext} is the encryption of {@code expected}, in time that depends on
   * neither its padding nor its value.
   *
   * @param key the proxy's private key
   * @param ciphertext the value as the client encrypted it with the proxy's public key
   * @param expected the value it must carry
   * @return whether it carries {@code expected}
   */
  public static boolean decryptsTo(PrivateKey key, byte[] ciphertext, byte[] expected) {
    byte[] block = rawDecrypt(key, ciphertext);
    if (block == null || block.length < expected.length) {
      return false;
    }
    byte[] value = Arrays.copyOfRange(block, block.length - expected.length, block.length);
    // Both checks run before either result is looked at: no branch on the padding before the
    // value is compared.
    int paddingError = paddingError(block, expected.length);
    boolean equal = MessageDigest.isEqual(expected, value);
    return paddingError == 0 && equal;
  }

  /**
   * Returns the RSA block of {@code ciphertext}, padding included, or {@code null} if it is not an
   * RSA ciphertext under {@code key} at all: longer than the modulus, or a number at least as
   * large. Both depend on the public key and the ciphertext only, which the client knows already.
   */
  private static byte @Nullable [] rawDecrypt(PrivateKey key, byte[] ciphertext) {
    Cipher cipher;
    try {
      cipher = Cipher.getInstance(RAW_RSA);
      cipher.init(Cipher.DECRYPT_MODE, key);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Raw RSA is not available for " + key.getAlgorithm(), e);
    }
    try {
      return cipher.doFinal(ciphertext);
    } catch (GeneralSecurityException e) {
      return null;
    }
  }

  /**
   * Returns {@code 0} if {@code block} is a PKCS#1 v1.5 encryption block carrying {@code length}
   * bytes, nonzero otherwise. Reads every byte of the header and padding and never branches on
   * them, so its time depends on the lengths only.
   */
  private static int paddingError(byte[] block, int length) {
    int separator = block.length - length - 1;
    if (separator < 2 + MIN_PADDING) {
      return 1; // No room for the padding: depends on the key size and the length only.
    }
    int error = block[0] | (block[1] ^ 2) | block[separator];
    for (int i = 2; i < separator; i++) {
      // (b - 1) is negative only for b = 0: its sign bit flags a zero padding byte.
      error |= ((block[i] & 0xFF) - 1) >>> 31;
    }
    return error;
  }
}
