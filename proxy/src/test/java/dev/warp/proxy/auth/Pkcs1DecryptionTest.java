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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import javax.crypto.Cipher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Pkcs1Decryption")
class Pkcs1DecryptionTest {

  /** The proxy's key size, as the protocol fixes it. */
  private static final KeyPair KEY = generate(1024);

  private static final RSAPublicKey PUBLIC = (RSAPublicKey) KEY.getPublic();

  private static final int BLOCK_SIZE = 128;

  /** Builds the padding: seeded, so that a failure replays with the same blocks. */
  private final SplittableRandom random = new SplittableRandom(0x504B_4353L);

  private final byte[] secret = randomBytes(16);

  @Nested
  @DisplayName("a valid ciphertext")
  class Valid {

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {4, 16})
    @DisplayName("should decrypt what the JDK's PKCS#1 v1.5 cipher encrypts, as clients do")
    void decryptsWhatTheJdkEncrypts(int length) throws GeneralSecurityException {
      byte[] value = randomBytes(length);
      Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      cipher.init(Cipher.ENCRYPT_MODE, KEY.getPublic());

      byte[] decrypted = Pkcs1Decryption.decrypt(KEY.getPrivate(), cipher.doFinal(value), length);

      assertArrayEquals(value, decrypted);
    }

    @Test
    @DisplayName("should decrypt a value with a 2048-bit key too")
    void decryptsWithALargerKey() throws GeneralSecurityException {
      KeyPair larger = generate(2048);
      Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      cipher.init(Cipher.ENCRYPT_MODE, larger.getPublic());

      byte[] decrypted = Pkcs1Decryption.decrypt(larger.getPrivate(), cipher.doFinal(secret), 16);

      assertArrayEquals(secret, decrypted);
    }

    @Test
    @DisplayName(
        "should decrypt a block built by hand, the ciphertext stripped of its leading zero")
    void decryptsAShortCiphertext() {
      // A ciphertext below 2^1016 has a leading zero byte, which a client may leave out.
      byte[] ciphertext;
      do {
        ciphertext = unsigned(rsa(pkcs1(secret)));
      } while (ciphertext.length == BLOCK_SIZE);

      assertArrayEquals(secret, Pkcs1Decryption.decrypt(KEY.getPrivate(), ciphertext, 16));
    }

    @Test
    @DisplayName("should tell the expected value from another of the same length")
    void checksTheValue() {
      byte[] token = randomBytes(4);
      byte[] other = token.clone();
      other[3] ^= (byte) 0x80;
      byte[] ciphertext = encrypt(pkcs1(token));

      assertTrue(Pkcs1Decryption.decryptsTo(KEY.getPrivate(), ciphertext, token));
      assertFalse(Pkcs1Decryption.decryptsTo(KEY.getPrivate(), ciphertext, other));
    }
  }

  @Nested
  @DisplayName("an invalid ciphertext")
  class Invalid {

    static Stream<Arguments> invalidBlocks() {
      return Stream.of(
          Arguments.of("first byte not zero", (UnaryOperator<byte[]>) b -> set(b, 0, 1)),
          Arguments.of("block type 1", (UnaryOperator<byte[]>) b -> set(b, 1, 1)),
          Arguments.of("block type 0", (UnaryOperator<byte[]>) b -> set(b, 1, 0)),
          Arguments.of("zero in the padding", (UnaryOperator<byte[]>) b -> set(b, 9, 0)),
          Arguments.of("zero padding byte last", (UnaryOperator<byte[]>) b -> set(b, 110, 0)),
          Arguments.of("no separator", (UnaryOperator<byte[]>) b -> set(b, 111, 0x5A)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBlocks")
    @DisplayName("should refuse a block that is not PKCS#1 v1.5 encryption padding")
    void refusesBadPadding(String what, UnaryOperator<byte[]> damage) {
      byte[] ciphertext = encrypt(damage.apply(pkcs1(secret)));

      assertNull(Pkcs1Decryption.decrypt(KEY.getPrivate(), ciphertext, 16));
      assertFalse(Pkcs1Decryption.decryptsTo(KEY.getPrivate(), ciphertext, secret));
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 4, 15, 17, 32})
    @DisplayName("should refuse a well padded value of another length")
    void refusesAnotherLength(int length) {
      byte[] ciphertext = encrypt(pkcs1(Arrays.copyOf(secret, length)));

      assertNull(Pkcs1Decryption.decrypt(KEY.getPrivate(), ciphertext, 16));
      assertFalse(Pkcs1Decryption.decryptsTo(KEY.getPrivate(), ciphertext, secret));
    }

    @Test
    @DisplayName("should refuse a value that leaves fewer than 8 padding bytes")
    void refusesShortPadding() {
      byte[] value = randomBytes(BLOCK_SIZE - 10);
      byte[] ciphertext = encrypt(pkcs1(value));

      assertNull(Pkcs1Decryption.decrypt(KEY.getPrivate(), ciphertext, value.length));
      assertFalse(Pkcs1Decryption.decryptsTo(KEY.getPrivate(), ciphertext, value));
    }

    @Test
    @DisplayName("should refuse a number at least as large as the modulus")
    void refusesTooLargeANumber() {
      byte[] ciphertext = unsigned(PUBLIC.getModulus());

      assertNull(Pkcs1Decryption.decrypt(KEY.getPrivate(), ciphertext, 16));
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, BLOCK_SIZE + 1})
    @DisplayName("should refuse an empty ciphertext and one longer than the modulus")
    void refusesWrongSizes(int size) {
      byte[] ciphertext = new byte[size];

      assertNull(Pkcs1Decryption.decrypt(KEY.getPrivate(), ciphertext, 16));
      assertFalse(Pkcs1Decryption.decryptsTo(KEY.getPrivate(), ciphertext, secret));
    }
  }

  // ---------------------------------------------------------------------------
  // Client side, written out by hand
  // ---------------------------------------------------------------------------

  /**
   * A PKCS#1 v1.5 encryption block (RFC 8017 §7.2.1) carrying {@code message}: {@code 00 02},
   * nonzero random padding, {@code 00}, then the message.
   */
  private byte[] pkcs1(byte[] message) {
    byte[] block = new byte[BLOCK_SIZE];
    int separator = block.length - message.length - 1;
    block[1] = 2;
    for (int i = 2; i < separator; i++) {
      block[i] = (byte) random.nextInt(1, 256);
    }
    System.arraycopy(message, 0, block, separator + 1, message.length);
    return block;
  }

  /** Raw RSA with the public key: the ciphertext of {@code block}, padded to the block size. */
  private static byte[] encrypt(byte[] block) {
    byte[] value = unsigned(rsa(block));
    byte[] ciphertext = new byte[BLOCK_SIZE];
    System.arraycopy(value, 0, ciphertext, BLOCK_SIZE - value.length, value.length);
    return ciphertext;
  }

  private static BigInteger rsa(byte[] block) {
    return new BigInteger(1, block).modPow(PUBLIC.getPublicExponent(), PUBLIC.getModulus());
  }

  /** The big-endian bytes of a nonnegative number, without the sign byte. */
  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
  }

  private static byte[] set(byte[] block, int index, int value) {
    block[index] = (byte) value;
    return block;
  }

  private byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }

  private static KeyPair generate(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new AssertionError("RSA is a mandatory JCA algorithm", e);
    }
  }
}
