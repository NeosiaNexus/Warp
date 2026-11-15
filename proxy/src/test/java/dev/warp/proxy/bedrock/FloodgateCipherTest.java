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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FloodgateCipher")
class FloodgateCipherTest {

  /** Gate's test key: 16 bytes of 0x42. */
  static final SecureRandom RANDOM = new SecureRandom();

  static final byte[] KEY = repeat(0x42, 16);

  static final byte[] OTHER_KEY = repeat(0x13, 16);

  static final String PLAINTEXT =
      String.join(
          "\0",
          "1",
          "Steve",
          "281474976710655",
          "2",
          "en_US",
          "1",
          "1",
          "127.0.0.1",
          "",
          "0",
          "sub",
          "code");

  static byte[] repeat(int value, int length) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  /** A source of IVs that always gives the same bytes, so that two encryptions can be compared. */
  static SecureRandom fixedIv(byte[] iv) {
    return new SecureRandom() {
      @Override
      public void nextBytes(byte[] bytes) {
        System.arraycopy(iv, 0, bytes, 0, bytes.length);
      }
    };
  }

  /**
   * Floodgate's {@code AesCipher.encrypt} as written upstream (Base64 topping), with the IV given:
   * the reference the proxy's output is compared with byte for byte.
   */
  static byte[] floodgateEncrypt(byte[] key, byte[] iv, String plaintext) throws Exception {
    byte[] header =
        (FloodgateCipher.IDENTIFIER + (char) (FloodgateCipher.VERSION + 0x3E))
            .getBytes(StandardCharsets.UTF_8);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] encodedIv = Base64.getEncoder().encode(iv);
    byte[] encodedText = Base64.getEncoder().encode(cipherText);
    return ByteBuffer.allocate(header.length + encodedIv.length + encodedText.length + 1)
        .put(header)
        .put(encodedIv)
        .put((byte) 0x21)
        .put(encodedText)
        .array();
  }

  @Test
  @DisplayName("should expose Floodgate's constants")
  void constants() {
    assertEquals("^Floodgate^>", FloodgateCipher.HEADER);
    assertEquals(12, FloodgateCipher.IV_LENGTH);
    assertEquals(16, FloodgateCipher.TAG_LENGTH);
  }

  @Nested
  @DisplayName("wire format")
  class WireFormat {

    @Test
    @DisplayName("should write exactly what Floodgate's AesCipher writes for the same IV")
    void matchesFloodgate() throws Exception {
      byte[] iv = repeat(0x07, FloodgateCipher.IV_LENGTH);
      FloodgateCipher cipher = new FloodgateCipher(KEY, fixedIv(iv));

      String blob = cipher.encrypt(PLAINTEXT);

      assertArrayEquals(
          floodgateEncrypt(KEY, iv, PLAINTEXT), blob.getBytes(StandardCharsets.UTF_8));
      assertTrue(blob.startsWith("^Floodgate^>"), blob);
    }

    @Test
    @DisplayName("should decrypt what Floodgate's AesCipher writes")
    void readsFloodgate() throws Exception {
      byte[] iv = new byte[FloodgateCipher.IV_LENGTH];
      RANDOM.nextBytes(iv);
      String blob = new String(floodgateEncrypt(KEY, iv, PLAINTEXT), StandardCharsets.UTF_8);

      FloodgateCipher.Decrypted decrypted = new FloodgateCipher(KEY).decrypt(blob);

      assertEquals(PLAINTEXT, decrypted.plaintext());
      assertEquals(FloodgateCipher.TAG_LENGTH, decrypted.tag().length);
    }

    @Test
    @DisplayName("should recognize the header and read the version byte")
    void header() {
      assertTrue(FloodgateCipher.hasHeader("^Floodgate^>abc"));
      assertEquals(0, FloodgateCipher.version("^Floodgate^>abc"));
      assertEquals(1, FloodgateCipher.version("^Floodgate^?abc"));
      assertFalse(FloodgateCipher.hasHeader("^Floodgate^"));
      assertFalse(FloodgateCipher.hasHeader("FML2"));
      assertFalse(FloodgateCipher.hasHeader(""));
    }

    @Test
    @DisplayName("should use a fresh IV for every encryption")
    void freshIv() {
      FloodgateCipher cipher = new FloodgateCipher(KEY);

      assertNotEquals(cipher.encrypt(PLAINTEXT), cipher.encrypt(PLAINTEXT));
    }
  }

  @Nested
  @DisplayName("refusals")
  class Refusals {

    @Test
    @DisplayName("should refuse a blob encrypted with another key")
    void otherKey() {
      String blob = new FloodgateCipher(OTHER_KEY).encrypt(PLAINTEXT);

      assertThrows(GeneralSecurityException.class, () -> new FloodgateCipher(KEY).decrypt(blob));
    }

    @Test
    @DisplayName("should refuse a blob whose ciphertext was altered")
    void tampered() {
      String blob = new FloodgateCipher(KEY).encrypt(PLAINTEXT);
      int last = blob.length() - 5;
      char altered = blob.charAt(last) == 'A' ? 'B' : 'A';
      String tamperedBlob = blob.substring(0, last) + altered + blob.substring(last + 1);

      assertThrows(
          GeneralSecurityException.class, () -> new FloodgateCipher(KEY).decrypt(tamperedBlob));
    }

    @Test
    @DisplayName("should refuse a blob without a splitter, a bad IV, or no header")
    void malformed() {
      FloodgateCipher cipher = new FloodgateCipher(KEY);

      assertThrows(GeneralSecurityException.class, () -> cipher.decrypt("^Floodgate^>AAAA"));
      assertThrows(GeneralSecurityException.class, () -> cipher.decrypt("^Floodgate^>AAAA!AAAA"));
      assertThrows(GeneralSecurityException.class, () -> cipher.decrypt("^Floodgate^>!!AAAA"));
      assertThrows(GeneralSecurityException.class, () -> cipher.decrypt("not floodgate"));
    }

    @Test
    @DisplayName("should accept only 16, 24 and 32 byte keys")
    void keyLength() {
      assertThrows(IllegalArgumentException.class, () -> new FloodgateCipher(repeat(1, 15)));
      assertThrows(IllegalArgumentException.class, () -> new FloodgateCipher(new byte[0]));
      new FloodgateCipher(repeat(1, 24));
      new FloodgateCipher(repeat(1, 32));
    }
  }
}
