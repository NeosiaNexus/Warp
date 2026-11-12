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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("HandoffSeal")
class HandoffSealTest {

  private static final byte[] SECRET =
      "a-shared-secret-of-the-network".getBytes(StandardCharsets.UTF_8);
  private static final byte[] BODY = "what a ticket says".getBytes(StandardCharsets.UTF_8);

  private final HandoffSeal seal = HandoffSeal.of(SECRET);

  private static HandoffRejection refusal(HandoffSeal.Opened opened) {
    return assertInstanceOf(HandoffSeal.Opened.Refused.class, opened).reason();
  }

  private static byte[] plaintext(HandoffSeal.Opened opened) {
    return assertInstanceOf(HandoffSeal.Opened.Body.class, opened).plaintext();
  }

  @Nested
  @DisplayName("sealing and opening")
  class RoundTrip {

    @Test
    @DisplayName("should open what it sealed")
    void roundTrip() {
      assertArrayEquals(BODY, plaintext(seal.open(seal.seal(BODY))));
    }

    @Test
    @DisplayName("should open what another proxy sealed under the same secret")
    void sharedSecret() {
      assertArrayEquals(BODY, plaintext(HandoffSeal.of(SECRET.clone()).open(seal.seal(BODY))));
    }

    @Test
    @DisplayName("should seal an empty body, and open it")
    void emptyBody() {
      byte[] sealed = seal.seal(new byte[0]);

      assertEquals(HandoffSeal.HEADER_LENGTH + 16, sealed.length);
      assertEquals(0, plaintext(seal.open(sealed)).length);
    }

    @Test
    @DisplayName("should add its header and tag to the body, and nothing else")
    void overhead() {
      byte[] sealed = seal.seal(BODY);

      assertEquals(HandoffSeal.HEADER_LENGTH + BODY.length + 16, sealed.length);
      assertEquals(HandoffSeal.VERSION, sealed[0]);
    }

    @Test
    @DisplayName("should never seal the same body the same way twice, nor leave it readable")
    void randomized() {
      byte[] first = seal.seal(BODY);
      byte[] second = seal.seal(BODY);

      assertFalse(Arrays.equals(first, second));
      assertFalse(
          new String(first, StandardCharsets.ISO_8859_1)
              .contains(new String(BODY, StandardCharsets.ISO_8859_1)));
      // Same key id, another IV.
      assertArrayEquals(Arrays.copyOfRange(first, 1, 5), Arrays.copyOfRange(second, 1, 5));
      assertFalse(
          Arrays.equals(
              Arrays.copyOfRange(first, 5, HandoffSeal.HEADER_LENGTH),
              Arrays.copyOfRange(second, 5, HandoffSeal.HEADER_LENGTH)));
    }
  }

  @Nested
  @DisplayName("refusing")
  class Refusing {

    @Test
    @DisplayName("should refuse a payload with any single bit changed, wherever it is")
    void everyBit() {
      byte[] sealed = seal.seal(BODY);

      for (int i = 0; i < sealed.length; i++) {
        for (int bit = 0; bit < 8; bit++) {
          byte[] changed = sealed.clone();
          changed[i] ^= (byte) (1 << bit);
          HandoffRejection reason = refusal(seal.open(changed));
          HandoffRejection expected =
              i == 0
                  ? HandoffRejection.UNKNOWN_VERSION
                  : i <= HandoffSeal.KEY_ID_LENGTH
                      ? HandoffRejection.FOREIGN_KEY
                      : HandoffRejection.TAMPERED;
          assertEquals(expected, reason, "byte " + i + " bit " + bit);
        }
      }
    }

    @Test
    @DisplayName("should refuse a payload sealed under another secret, naming the key")
    void wrongKey() {
      byte[] sealed =
          HandoffSeal.of("another-secret-entirely".getBytes(StandardCharsets.UTF_8)).seal(BODY);

      assertEquals(HandoffRejection.FOREIGN_KEY, refusal(seal.open(sealed)));
    }

    @Test
    @DisplayName("should refuse a payload of another key that claims this key's id")
    void forgedKeyId() {
      byte[] sealed =
          HandoffSeal.of("another-secret-entirely".getBytes(StandardCharsets.UTF_8)).seal(BODY);
      System.arraycopy(seal.seal(BODY), 1, sealed, 1, HandoffSeal.KEY_ID_LENGTH);

      assertEquals(HandoffRejection.TAMPERED, refusal(seal.open(sealed)));
    }

    @Test
    @DisplayName("should refuse every truncation of a payload")
    void truncated() {
      byte[] sealed = seal.seal(BODY);

      for (int length = 0; length < sealed.length; length++) {
        HandoffRejection reason = refusal(seal.open(Arrays.copyOf(sealed, length)));
        assertEquals(
            length < HandoffSeal.HEADER_LENGTH + 16
                ? HandoffRejection.MALFORMED
                : HandoffRejection.TAMPERED,
            reason,
            "length " + length);
      }
    }

    @Test
    @DisplayName("should take the shortest payload that can hold a tag for one, and refuse it")
    void shortestPayload() {
      byte[] sealed = Arrays.copyOf(seal.seal(BODY), HandoffSeal.HEADER_LENGTH + 16);

      assertEquals(HandoffRejection.TAMPERED, refusal(seal.open(sealed)));
    }

    @Test
    @DisplayName("should refuse a payload with bytes added")
    void extended() {
      byte[] sealed = seal.seal(BODY);

      assertEquals(
          HandoffRejection.TAMPERED, refusal(seal.open(Arrays.copyOf(sealed, sealed.length + 1))));
    }

    @Test
    @DisplayName("should refuse a newer format before looking at its key")
    void newerVersion() {
      byte[] sealed = seal.seal(BODY);
      sealed[0] = 2;
      sealed[1] ^= 1;

      assertEquals(HandoffRejection.UNKNOWN_VERSION, refusal(seal.open(sealed)));
    }

    @Test
    @DisplayName("should refuse an oversized payload of garbage without failing")
    void oversized() {
      byte[] garbage = new byte[64 * 1024];
      garbage[0] = HandoffSeal.VERSION;

      assertEquals(HandoffRejection.FOREIGN_KEY, refusal(seal.open(garbage)));
      System.arraycopy(seal.seal(BODY), 0, garbage, 0, HandoffSeal.HEADER_LENGTH);
      assertEquals(HandoffRejection.TAMPERED, refusal(seal.open(garbage)));
    }
  }

  @Nested
  @DisplayName("loading the key")
  class Loading {

    @TempDir private Path directory;

    @Test
    @DisplayName("should generate a key file readable by its owner only, and read it back")
    void generates() throws Exception {
      Path file = directory.resolve("keys").resolve("transfer.secret");

      HandoffSeal first = HandoffSeal.load(file, null);
      HandoffSeal second = HandoffSeal.load(file, "  ");

      assertTrue(first.generated());
      assertFalse(first.fromEnvironment());
      assertFalse(second.generated());
      assertFalse(second.fromEnvironment());
      assertArrayEquals(BODY, plaintext(second.open(first.seal(BODY))));
      assertFalse(Files.exists(file.resolveSibling("transfer.secret.tmp")));
      if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
        assertEquals(
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file));
      }
      String key =
          Files.readAllLines(file, StandardCharsets.US_ASCII).stream()
              .filter(line -> !line.startsWith("#"))
              .findFirst()
              .orElseThrow();
      assertEquals(32, Base64.getDecoder().decode(key).length);
    }

    @Test
    @DisplayName("should generate another key for each proxy: one does not open the other's")
    void generatedKeysDiffer() throws Exception {
      HandoffSeal first = HandoffSeal.load(directory.resolve("a.secret"), null);
      HandoffSeal second = HandoffSeal.load(directory.resolve("b.secret"), null);

      assertEquals(HandoffRejection.FOREIGN_KEY, refusal(second.open(first.seal(BODY))));
    }

    @Test
    @DisplayName("should replace a temporary file left by an interrupted start")
    void staleTemporary() throws Exception {
      Path file = directory.resolve("transfer.secret");
      Files.writeString(file.resolveSibling("transfer.secret.tmp"), "left over");

      assertTrue(HandoffSeal.load(file, null).generated());
      assertFalse(Files.exists(file.resolveSibling("transfer.secret.tmp")));
    }

    @Test
    @DisplayName("should share a copied key file: both proxies open each other's tickets")
    void copiedFile() throws Exception {
      Path file = directory.resolve("transfer.secret");
      HandoffSeal first = HandoffSeal.load(file, null);
      Path copy = directory.resolve("copy.secret");
      Files.copy(file, copy);

      assertArrayEquals(BODY, plaintext(HandoffSeal.load(copy, null).open(first.seal(BODY))));
    }

    @Test
    @DisplayName("should prefer the environment to the file, without creating the file")
    void environment() throws Exception {
      Path file = directory.resolve("transfer.secret");

      HandoffSeal fromEnvironment = HandoffSeal.load(file, "  a-shared-secret-of-the-network \n");

      assertTrue(fromEnvironment.fromEnvironment());
      assertFalse(fromEnvironment.generated());
      assertFalse(Files.exists(file));
      assertArrayEquals(BODY, plaintext(seal.open(fromEnvironment.seal(BODY))));
    }

    @Test
    @DisplayName(
        "should refuse a secret under 16 characters in the environment, without quoting it")
    void shortEnvironment() {
      IOException refused =
          assertThrows(
              IOException.class,
              () -> HandoffSeal.load(directory.resolve("transfer.secret"), "hunter2hunter2!"));

      assertTrue(
          String.valueOf(refused.getMessage()).contains("WARP_TRANSFER_SECRET"),
          String.valueOf(refused.getMessage()));
      assertFalse(
          String.valueOf(refused.getMessage()).contains("hunter2"),
          String.valueOf(refused.getMessage()));
      assertFalse(Files.exists(directory.resolve("transfer.secret")));
    }

    @Test
    @DisplayName("should accept a secret of exactly 16 characters in the environment")
    void shortestEnvironment() throws Exception {
      HandoffSeal loaded =
          HandoffSeal.load(directory.resolve("transfer.secret"), "hunter2hunter2!!");

      assertArrayEquals(BODY, plaintext(loaded.open(loaded.seal(BODY))));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "",
          "# only a comment\n",
          "not base64 !\n",
          "c2hvcnQ=\n",
          "AAAAAAAAAAAAAAAAAAAA\n"
        })
    @DisplayName(
        "should refuse a key file without a Base64 key of 16 bytes, rather than replace it")
    void badFile(String content) throws Exception {
      Path file = directory.resolve("transfer.secret");
      Files.writeString(file, content, StandardCharsets.US_ASCII);

      IOException refused = assertThrows(IOException.class, () -> HandoffSeal.load(file, null));

      assertTrue(
          String.valueOf(refused.getMessage()).contains("transfer.secret"),
          String.valueOf(refused.getMessage()));
      assertEquals(content, Files.readString(file, StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("should read a key of exactly 16 bytes after comments and blank lines")
    void shortestFile() throws Exception {
      Path file = directory.resolve("transfer.secret");
      byte[] key = new byte[16];
      Arrays.fill(key, (byte) 7);
      Files.writeString(
          file, "# a comment\n\n  " + Base64.getEncoder().encodeToString(key) + "  \nignored\n");

      HandoffSeal loaded = HandoffSeal.load(file, null);

      assertFalse(loaded.generated());
      assertArrayEquals(BODY, plaintext(HandoffSeal.of(key).open(loaded.seal(BODY))));
    }

    @Test
    @DisplayName("should derive the key: the secret itself, used as an AES key, opens nothing")
    void derived() throws Exception {
      byte[] key = new byte[32];
      Arrays.fill(key, (byte) 9);
      byte[] sealed = HandoffSeal.of(key).seal(BODY);
      javax.crypto.Cipher raw = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      raw.init(
          javax.crypto.Cipher.DECRYPT_MODE,
          new javax.crypto.spec.SecretKeySpec(key, "AES"),
          new javax.crypto.spec.GCMParameterSpec(128, sealed, 5, 12));
      raw.updateAAD(sealed, 0, HandoffSeal.HEADER_LENGTH);

      assertThrows(
          javax.crypto.AEADBadTagException.class,
          () ->
              raw.doFinal(
                  sealed, HandoffSeal.HEADER_LENGTH, sealed.length - HandoffSeal.HEADER_LENGTH));
    }
  }
}
