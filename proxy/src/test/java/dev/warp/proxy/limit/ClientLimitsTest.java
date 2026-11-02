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
package dev.warp.proxy.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.LimitsConfig;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.NetUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ClientLimits")
class ClientLimitsTest {

  private static final SocketAddress CLIENT = socket("203.0.113.7");
  private static final SocketAddress OTHER_CLIENT = socket("203.0.113.8");

  private final AtomicLong now = new AtomicLong(TimeUnit.HOURS.toNanos(1));

  // ---------------------------------------------------------------------------
  // Connections and pings
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("connections and server list pings")
  class ConnectionsAndPings {

    @Test
    @DisplayName("should refuse the connections of an address over its rate")
    void connections() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);

      for (int i = 0; i < 10; i++) {
        assertTrue(limits.admitConnection(CLIENT), "connection " + i);
      }

      assertFalse(limits.admitConnection(CLIENT));
      assertTrue(limits.admitConnection(OTHER_CLIENT), "another address has its own budget");
      now.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
      assertTrue(limits.admitConnection(CLIENT), "one more after the interval");
    }

    @Test
    @DisplayName("should count server list pings apart from logins")
    void pingsApartFromLogins() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      for (int i = 0; i < 10; i++) {
        limits.admitStatusPing(CLIENT);
      }

      assertFalse(limits.admitStatusPing(CLIENT));
      assertInstanceOf(LoginAdmission.Admitted.class, limits.admitLogin(CLIENT));
    }

    @Test
    @DisplayName("should not count logins against server list pings")
    void loginsApartFromPings() {
      ClientLimits limits = limits(rates(rate(10, 1), rate(1, 3600)));
      limits.admitLogin(CLIENT);

      assertInstanceOf(LoginAdmission.TooFast.class, limits.admitLogin(CLIENT));
      assertTrue(limits.admitStatusPing(CLIENT));
    }

    @Test
    @DisplayName("should count every address of an IPv6 /64 as one")
    void ipv6Network() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);

      for (int i = 0; i < 10; i++) {
        assertTrue(limits.admitConnection(socket("2001:db8:1:2::" + Integer.toHexString(i + 1))));
      }

      assertFalse(limits.admitConnection(socket("2001:db8:1:2:abcd::1")));
      assertTrue(limits.admitConnection(socket("2001:db8:1:3::1")));
    }
  }

  // ---------------------------------------------------------------------------
  // Logins
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("logins")
  class Logins {

    @Test
    @DisplayName("should let a burst of logins in, then say how long to wait")
    void burstThenWait() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      release(limits.admitLogin(CLIENT));
      release(limits.admitLogin(CLIENT));

      LoginAdmission third = limits.admitLogin(CLIENT);

      LoginAdmission.TooFast tooFast = assertInstanceOf(LoginAdmission.TooFast.class, third);
      assertEquals(Duration.ofSeconds(3), tooFast.retryAfter());
    }

    @Test
    @DisplayName("should limit the logins in progress per address until they end")
    void inProgress() {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 2));
      LoginAdmission first = limits.admitLogin(CLIENT);
      limits.admitLogin(CLIENT);

      assertInstanceOf(LoginAdmission.TooManyInProgress.class, limits.admitLogin(CLIENT));
      assertInstanceOf(LoginAdmission.Admitted.class, limits.admitLogin(OTHER_CLIENT));

      release(first);
      assertInstanceOf(LoginAdmission.Admitted.class, limits.admitLogin(CLIENT));
    }

    @Test
    @DisplayName("should release a login's place once, however often it is released")
    void releaseOnce() {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 1));
      LoginAdmission first = limits.admitLogin(CLIENT);
      release(first);
      release(first);
      limits.admitLogin(CLIENT);

      assertInstanceOf(LoginAdmission.TooManyInProgress.class, limits.admitLogin(CLIENT));
    }

    @Test
    @DisplayName("should take no token from an attempt refused for the logins in progress")
    void refusalTakesNoToken() {
      ClientLimits limits = limits(withLogins(rate(2, 3600), 1));
      LoginAdmission first = limits.admitLogin(CLIENT);
      limits.admitLogin(CLIENT); // refused: one in progress

      release(first);

      assertInstanceOf(LoginAdmission.Admitted.class, limits.admitLogin(CLIENT));
    }

    @Test
    @DisplayName("should give back the place of an attempt refused as too fast")
    void tooFastGivesPlaceBack() {
      ClientLimits limits = limits(withLogins(rate(1, 3600), 1));
      release(limits.admitLogin(CLIENT));

      limits.admitLogin(CLIENT); // too fast

      now.addAndGet(TimeUnit.HOURS.toNanos(1));
      assertInstanceOf(LoginAdmission.Admitted.class, limits.admitLogin(CLIENT));
    }
  }

  // ---------------------------------------------------------------------------
  // Authentications
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("authentications")
  class Authentications {

    @Test
    @DisplayName("should cap the authentications in flight for the whole proxy")
    void cap() {
      ClientLimits limits = limits(withAuthentications(2));

      assertTrue(limits.tryStartAuthentication());
      assertTrue(limits.admitOnlineLogin());
      assertTrue(limits.tryStartAuthentication());
      assertFalse(limits.admitOnlineLogin());
      assertFalse(limits.tryStartAuthentication());

      limits.endAuthentication();
      assertTrue(limits.admitOnlineLogin());
      assertTrue(limits.tryStartAuthentication());
    }

    @Test
    @DisplayName("should not cap authentications when the maximum is 0")
    void unlimited() {
      ClientLimits limits = limits(withAuthentications(0));

      for (int i = 0; i < 1000; i++) {
        assertTrue(limits.tryStartAuthentication());
      }
      limits.endAuthentication();
      assertTrue(limits.admitOnlineLogin());
    }

    @Test
    @DisplayName("should cap the authentications of exempt addresses too")
    void exemptAddressesCount() {
      ClientLimits limits = limits(withAuthentications(1));
      limits.admitLogin(socket("127.0.0.1"));
      limits.tryStartAuthentication();

      assertFalse(limits.admitOnlineLogin());
    }
  }

  // ---------------------------------------------------------------------------
  // Exemptions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("exemptions")
  class Exemptions {

    @Test
    @DisplayName("should never limit loopback by default")
    void loopback() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);

      for (int i = 0; i < 1000; i++) {
        assertTrue(limits.admitConnection(socket("127.0.0.1")));
        assertTrue(limits.admitStatusPing(socket("::1")));
        assertInstanceOf(LoginAdmission.Admitted.class, limits.admitLogin(socket("127.0.0.2")));
      }
    }

    @Test
    @DisplayName("should never limit the trusted proxies, whose own connections are health checks")
    void trustedProxies() {
      ClientLimits limits =
          new ClientLimits(
              LimitsConfig.DEFAULTS, CidrRanges.parse(List.of("10.0.0.0/24")), now::get);

      for (int i = 0; i < 100; i++) {
        assertTrue(limits.admitConnection(socket("10.0.0.5")));
        assertTrue(limits.admitStatusPing(socket("10.0.0.5")));
      }
    }

    @Test
    @DisplayName("should limit nothing when disabled")
    void disabled() {
      ClientLimits limits = ClientLimits.unlimited();

      for (int i = 0; i < 1000; i++) {
        assertTrue(limits.admitConnection(CLIENT));
        assertTrue(limits.admitStatusPing(CLIENT));
        assertInstanceOf(LoginAdmission.Admitted.class, limits.admitLogin(CLIENT));
        assertTrue(limits.tryStartAuthentication());
      }
      assertEquals(Duration.ZERO, limits.loginTimeout());
    }

    @Test
    @DisplayName("should not limit a connection whose address is not an IP address")
    void notAnIpAddress() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      SocketAddress embedded = new EmbeddedChannel().remoteAddress();

      for (int i = 0; i < 100; i++) {
        assertTrue(limits.admitConnection(embedded));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ClientLimits limits(LimitsConfig config) {
    return new ClientLimits(config, CidrRanges.none(), now::get);
  }

  private static void release(LoginAdmission admission) {
    assertInstanceOf(LoginAdmission.Admitted.class, admission).slot().release();
  }

  /** The defaults with other server list ping and login rates. */
  private static LimitsConfig rates(LimitsConfig.Rate statusPings, LimitsConfig.Rate logins) {
    LimitsConfig d = LimitsConfig.DEFAULTS;
    return new LimitsConfig(
        true,
        d.exempt(),
        d.connections(),
        statusPings,
        logins,
        d.maxConcurrentLoginsPerAddress(),
        d.maxConcurrentAuthentications(),
        d.loginTimeout(),
        d.reportInterval(),
        d.maxPendingConnections(),
        d.handshakeTimeout(),
        d.statusTimeout());
  }

  private static LimitsConfig withLogins(LimitsConfig.Rate logins, int maxInProgress) {
    LimitsConfig d = LimitsConfig.DEFAULTS;
    return new LimitsConfig(
        true,
        d.exempt(),
        d.connections(),
        d.statusPings(),
        logins,
        maxInProgress,
        d.maxConcurrentAuthentications(),
        d.loginTimeout(),
        d.reportInterval(),
        d.maxPendingConnections(),
        d.handshakeTimeout(),
        d.statusTimeout());
  }

  private static LimitsConfig withAuthentications(int max) {
    LimitsConfig d = LimitsConfig.DEFAULTS;
    return new LimitsConfig(
        true,
        d.exempt(),
        d.connections(),
        d.statusPings(),
        d.logins(),
        d.maxConcurrentLoginsPerAddress(),
        max,
        d.loginTimeout(),
        d.reportInterval(),
        d.maxPendingConnections(),
        d.handshakeTimeout(),
        d.statusTimeout());
  }

  private static InetSocketAddress socket(String literal) {
    try {
      return new InetSocketAddress(
          InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal)), 40000);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  /** A rate in whole seconds. */
  private static LimitsConfig.Rate rate(int burst, int intervalSeconds) {
    return new LimitsConfig.Rate(burst, Duration.ofSeconds(intervalSeconds));
  }
}
