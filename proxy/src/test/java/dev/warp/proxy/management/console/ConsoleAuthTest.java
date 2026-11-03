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
package dev.warp.proxy.management.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.RemoteConsoleConfig;
import dev.warp.proxy.config.RemoteConsoleConfig.Scope;
import dev.warp.proxy.config.RemoteConsoleConfig.Token;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConsoleAuth")
class ConsoleAuthTest {

  private static final String OPS_SECRET = "o".repeat(40);
  private static final String DASH_SECRET = "d".repeat(40);
  private static final Token OPS =
      new Token(
          "ops",
          Set.of(Scope.LOGS, Scope.COMMANDS),
          RemoteConsoleConfig.sha256(OPS_SECRET),
          OPS_SECRET);
  private static final Token DASH =
      new Token("dash", Set.of(Scope.LOGS), RemoteConsoleConfig.sha256(DASH_SECRET), DASH_SECRET);

  private final AtomicLong now = new AtomicLong(1_000_000_000L);
  private final ConsoleAuth auth =
      new ConsoleAuth(
          new RemoteConsoleConfig(
              true, List.of(OPS, DASH), CidrRanges.none(), 100, 3, Duration.ofMinutes(10)),
          now::get);

  private static InetAddress address(String text) throws Exception {
    return InetAddress.getByName(text);
  }

  @Nested
  @DisplayName("tokens")
  class Tokens {

    @Test
    @DisplayName("should grant each token its own identity")
    void granted() throws Exception {
      ConsoleAuth.Result ops = auth.authenticate(address("10.0.0.1"), "Bearer " + OPS_SECRET);
      ConsoleAuth.Result dash = auth.authenticate(address("10.0.0.1"), "bearer  " + DASH_SECRET);

      assertEquals("ops", assertInstanceOf(ConsoleAuth.Result.Granted.class, ops).token().name());
      assertEquals("dash", assertInstanceOf(ConsoleAuth.Result.Granted.class, dash).token().name());
    }

    @Test
    @DisplayName("should refuse a missing token without counting it")
    void missing() throws Exception {
      for (int i = 0; i < 10; i++) {
        assertInstanceOf(
            ConsoleAuth.Result.Missing.class, auth.authenticate(address("10.0.0.1"), null));
      }
      assertInstanceOf(
          ConsoleAuth.Result.Missing.class, auth.authenticate(address("10.0.0.1"), "Basic abc"));

      assertInstanceOf(
          ConsoleAuth.Result.Granted.class,
          auth.authenticate(address("10.0.0.1"), "Bearer " + OPS_SECRET));
    }

    @Test
    @DisplayName("should never accept the drain token or a prefix of a token")
    void prefix() throws Exception {
      ConsoleAuth.Result result =
          auth.authenticate(address("10.0.0.1"), "Bearer " + OPS_SECRET.substring(1));

      assertInstanceOf(ConsoleAuth.Result.Wrong.class, result);
    }
  }

  @Nested
  @DisplayName("lockout")
  class Lockout {

    @Test
    @DisplayName("should lock an address out after its wrong tokens, even with the right one")
    void locksOut() throws Exception {
      InetAddress attacker = address("203.0.113.9");

      ConsoleAuth.Result first = auth.authenticate(attacker, "Bearer guess-1");
      ConsoleAuth.Result second = auth.authenticate(attacker, "Bearer guess-2");
      ConsoleAuth.Result third = auth.authenticate(attacker, "Bearer guess-3");
      ConsoleAuth.Result right = auth.authenticate(attacker, "Bearer " + OPS_SECRET);

      assertEquals(new ConsoleAuth.Result.Wrong(1, 3, false), first);
      assertEquals(new ConsoleAuth.Result.Wrong(2, 3, false), second);
      assertEquals(new ConsoleAuth.Result.Wrong(3, 3, true), third);
      ConsoleAuth.Result.LockedOut locked =
          assertInstanceOf(ConsoleAuth.Result.LockedOut.class, right);
      assertEquals(Duration.ofMinutes(10), locked.retryAfter());
    }

    @Test
    @DisplayName("should leave the other addresses alone")
    void otherAddresses() throws Exception {
      for (int i = 0; i < 3; i++) {
        var _ = auth.authenticate(address("203.0.113.9"), "Bearer guess");
      }

      assertInstanceOf(
          ConsoleAuth.Result.Granted.class,
          auth.authenticate(address("203.0.113.10"), "Bearer " + OPS_SECRET));
    }

    @Test
    @DisplayName("should let the address in again once the lockout expired")
    void expires() throws Exception {
      InetAddress attacker = address("203.0.113.9");
      for (int i = 0; i < 3; i++) {
        var _ = auth.authenticate(attacker, "Bearer guess");
      }

      now.addAndGet(Duration.ofMinutes(10).toNanos() + 1);

      assertInstanceOf(
          ConsoleAuth.Result.Granted.class, auth.authenticate(attacker, "Bearer " + OPS_SECRET));
      assertEquals(
          new ConsoleAuth.Result.Wrong(1, 3, false), auth.authenticate(attacker, "Bearer guess"));
    }

    @Test
    @DisplayName("should forget the wrong tokens of an address that then sent the right one")
    void successResets() throws Exception {
      InetAddress client = address("10.0.0.1");
      var _ = auth.authenticate(client, "Bearer typo");
      var _ = auth.authenticate(client, "Bearer typo");

      var _ = auth.authenticate(client, "Bearer " + OPS_SECRET);

      assertEquals(
          new ConsoleAuth.Result.Wrong(1, 3, false), auth.authenticate(client, "Bearer typo"));
    }

    @Test
    @DisplayName("should count an IPv6 client by its /64")
    void ipv6() throws Exception {
      var _ = auth.authenticate(address("2001:db8::1"), "Bearer guess");
      var _ = auth.authenticate(address("2001:db8::2"), "Bearer guess");
      var _ = auth.authenticate(address("2001:db8::3"), "Bearer guess");

      assertInstanceOf(
          ConsoleAuth.Result.LockedOut.class,
          auth.authenticate(address("2001:db8::ffff"), "Bearer " + OPS_SECRET));
      assertInstanceOf(
          ConsoleAuth.Result.Granted.class,
          auth.authenticate(address("2001:db8:0:1::1"), "Bearer " + OPS_SECRET));
      assertEquals("2001:0db8:0000:0000::/64", ConsoleAuth.key(address("2001:db8::1")));
    }
  }

  @Nested
  @DisplayName("rate limits")
  class RateLimits {

    @Test
    @DisplayName("should refuse commands past the limit of the window, then allow them again")
    void commands() {
      for (int i = 0; i < ConsoleAuth.COMMANDS_PER_WINDOW; i++) {
        assertTrue(auth.allowCommand(OPS));
      }

      assertFalse(auth.allowCommand(OPS));
      assertTrue(auth.allowCommand(DASH), "each token has its own limit");
      now.addAndGet(ConsoleAuth.WINDOW.toNanos());
      assertTrue(auth.allowCommand(OPS));
    }

    @Test
    @DisplayName("should refuse streams past the limit of a token, until one closes")
    void streams() {
      for (int i = 0; i < ConsoleAuth.STREAMS_PER_TOKEN; i++) {
        assertTrue(auth.openStream(OPS));
      }

      assertFalse(auth.openStream(OPS));
      auth.closeStream(OPS);
      assertTrue(auth.openStream(OPS));
    }
  }
}
