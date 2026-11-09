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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.VerifiedKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Signed verification tokens: issue, verify, expire, rotate. */
@DisplayName("VerificationTokens")
class VerificationTokensTest {

  private static final Duration LIFETIME = Duration.ofMinutes(10);
  private static final VerifiedKey STEVE =
      new VerifiedKey(0x1234_5678_9ABC_DEF0L, 0x0FED_CBA9_8765_4321L);
  private static final VerifiedKey ALEX = new VerifiedKey(1, 2);

  @TempDir private Path directory;

  private final AtomicLong millis = new AtomicLong(1_800_000_000_000L);

  private VerificationTokens inMemory() throws IOException {
    return new VerificationTokens(LIFETIME, Duration.ZERO, null, null, millis::get);
  }

  private VerificationTokens onDisk(Duration rotateAfter) throws IOException {
    return new VerificationTokens(
        LIFETIME, rotateAfter, directory.resolve("token.key"), null, millis::get);
  }

  // ---------------------------------------------------------------------------
  // Sign and verify
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("sign and verify")
  class SignAndVerify {

    @Test
    @DisplayName("should accept its own token for the identity it was issued to, with its flag")
    void roundTrip() throws IOException {
      VerificationTokens tokens = inMemory();

      byte[] token = tokens.issue(STEVE, true);
      VerificationTokens.Claim claim = tokens.verify(token, STEVE);

      assertEquals(VerificationTokens.LENGTH, token.length);
      assertNotNull(claim);
      assertTrue(claim.trusted());
      assertEquals(Instant.ofEpochMilli(millis.get()), claim.issuedAt());
      assertEquals(1, tokens.issued());
      assertEquals(1, tokens.accepted());
      VerificationTokens.Claim untrusted = tokens.verify(tokens.issue(ALEX, false), ALEX);
      assertNotNull(untrusted);
      assertFalse(untrusted.trusted());
    }

    @Test
    @DisplayName("should refuse a token for another identity: it cannot be lent")
    void otherIdentity() throws IOException {
      VerificationTokens tokens = inMemory();

      assertNull(tokens.verify(tokens.issue(STEVE, true), ALEX));
      assertEquals(1, tokens.rejected());
    }

    @Test
    @DisplayName("should refuse a token with any bit changed")
    void tampered() throws IOException {
      VerificationTokens tokens = inMemory();
      byte[] token = tokens.issue(STEVE, true);

      for (int i = 0; i < token.length; i++) {
        byte[] changed = token.clone();
        changed[i] ^= 1;
        assertNull(tokens.verify(changed, STEVE), "byte " + i + " changed");
      }
    }

    @Test
    @DisplayName("should refuse a token of another length, version or key")
    void malformed() throws IOException {
      VerificationTokens tokens = inMemory();
      VerificationTokens others = inMemory();

      assertNull(tokens.verify(new byte[0], STEVE));
      assertNull(tokens.verify(Arrays.copyOf(tokens.issue(STEVE, true), 10), STEVE));
      assertNull(tokens.verify(others.issue(STEVE, true), STEVE), "another proxy's key");
    }

    @Test
    @DisplayName("should refuse a token past its lifetime")
    void expiry() throws IOException {
      VerificationTokens tokens = inMemory();
      byte[] token = tokens.issue(STEVE, true);

      millis.addAndGet(LIFETIME.toMillis());
      assertNotNull(tokens.verify(token, STEVE), "at the lifetime: still valid");
      millis.addAndGet(1);
      assertNull(tokens.verify(token, STEVE), "past it");
    }

    @Test
    @DisplayName(
        "should accept a token issued by a fleet member whose clock is slightly ahead, not one"
            + " from further in the future")
    void clockSkew() throws IOException {
      VerificationTokens tokens = inMemory();
      byte[] token = tokens.issue(STEVE, true);

      millis.addAndGet(-VerificationTokens.MAX_CLOCK_SKEW.toMillis());
      assertNotNull(tokens.verify(token, STEVE), "within the skew tolerated between proxies");
      millis.addAndGet(-1);
      assertNull(tokens.verify(token, STEVE), "beyond it: a forgery, or clocks badly adrift");
    }
  }

  // ---------------------------------------------------------------------------
  // Keys
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("keys")
  class Keys {

    @Test
    @DisplayName("should generate a key file at first start, and load it again")
    void generatesAndReloads() throws IOException {
      VerificationTokens first = onDisk(Duration.ZERO);
      byte[] token = first.issue(STEVE, true);
      Path file = directory.resolve("token.key");
      assertTrue(Files.exists(file));

      VerificationTokens second = onDisk(Duration.ZERO);

      assertNotNull(second.verify(token, STEVE), "the same key after a restart");
      assertEquals(first.keySince(), second.keySince());
    }

    @Test
    @DisplayName("should share a key set in the environment between proxies, and not rotate it")
    void fromEnvironment() throws IOException {
      VerificationTokens one =
          new VerificationTokens(LIFETIME, Duration.ofDays(1), null, "fleet-secret", millis::get);
      VerificationTokens two =
          new VerificationTokens(LIFETIME, Duration.ofDays(1), null, "fleet-secret", millis::get);

      assertNotNull(two.verify(one.issue(STEVE, true), STEVE));
      assertFalse(one.rotatable());
      assertThrows(IllegalStateException.class, () -> one.rotate("test"));
      millis.addAndGet(Duration.ofDays(2).toMillis());
      one.tick();
      assertNotNull(two.verify(one.issue(STEVE, true), STEVE), "not rotated by the proxy");
    }

    @Test
    @DisplayName("should keep accepting the previous key after a rotation, and no older one")
    void rotation() throws IOException {
      VerificationTokens tokens = onDisk(Duration.ZERO);
      byte[] first = tokens.issue(STEVE, true);

      tokens.rotate("test");
      byte[] second = tokens.issue(STEVE, true);
      assertNotNull(tokens.verify(first, STEVE), "the previous key still signs valid tokens");
      assertNotNull(tokens.verify(second, STEVE));
      assertFalse(Arrays.equals(first, second));

      tokens.rotate("test");
      assertNull(tokens.verify(first, STEVE), "two rotations back: gone");
      assertNotNull(tokens.verify(second, STEVE));

      // Both keys are on disk, the current one first: a restart changes nothing.
      String written = Files.readString(directory.resolve("token.key"), StandardCharsets.US_ASCII);
      assertEquals(2, written.lines().filter(line -> !line.startsWith("#")).count());
      VerificationTokens reloaded = onDisk(Duration.ZERO);
      assertNotNull(reloaded.verify(second, STEVE));
      assertNotNull(reloaded.verify(reloaded.issue(ALEX, true), ALEX));
    }

    @Test
    @DisplayName("should rotate its own key once it is older than rotate-after")
    void scheduledRotation() throws Exception {
      VerificationTokens tokens = onDisk(Duration.ofHours(1));
      Instant since = tokens.keySince();

      millis.addAndGet(Duration.ofMinutes(59).toMillis());
      tokens.tick();
      Thread.sleep(50);
      assertEquals(since, tokens.keySince(), "not yet");

      millis.addAndGet(Duration.ofMinutes(2).toMillis());
      tokens.tick();
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (tokens.keySince().equals(since) && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(Instant.ofEpochMilli(millis.get()), tokens.keySince());
    }

    @Test
    @DisplayName("should refuse a key file it cannot read, naming the way out")
    void corruptFile() throws IOException {
      Files.writeString(directory.resolve("token.key"), "not base64!\n", StandardCharsets.US_ASCII);

      IOException refused = assertThrows(IOException.class, () -> onDisk(Duration.ZERO));

      assertTrue(
          String.valueOf(refused.getMessage()).contains("WARP_ANTIBOT_TOKEN_SECRET"),
          refused.getMessage());
    }
  }
}
