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

import dev.warp.proxy.config.LimitsConfig;
import dev.warp.proxy.network.CidrRange;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The proxy's limits on what one client address may do, and on the authentications it runs at once:
 * the first line of defence against connection floods and bot attacks.
 *
 * <ul>
 *   <li><b>Connections</b>: every new TCP connection, checked before anything is read from it, once
 *       a PROXY protocol header has given the client's address (so that the limit counts clients,
 *       not the load balancer).
 *   <li><b>Server list pings</b> and <b>logins</b>: separate budgets, so that refreshing the server
 *       list never locks a player out, and pinging bots do not exhaust the logins of an address.
 *   <li><b>Logins in progress</b> per address: bots that start logins and stall hold at most a few
 *       each, until {@link #loginTimeout()} closes them.
 *   <li><b>Authentications in flight</b>, for the whole proxy: the RSA decryption and the session
 *       server request of an online-mode login. Counted from the encryption response only, so that
 *       bots stalling earlier in the login hold none of them.
 * </ul>
 *
 * <p>Addresses count IPv4 by address and IPv6 by /64 ({@link AddressKey}). Exempt addresses, and
 * the trusted proxies themselves (whose own connections are health checks), are never limited; nor
 * is a connection whose address is not an IP address, which a TCP listener never accepts.
 *
 * <p>Refusals are counted, never logged one by one: {@link #report()} logs the counts at most once
 * per report interval, so that a flood cannot flood the logs. Every method is thread-safe and
 * lock-free; the per-address checks allocate a boxed key and the address bytes, once per
 * connection.
 */
public final class ClientLimits {

  private static final Logger logger = LoggerFactory.getLogger(ClientLimits.class);

  private static final LoginAdmission ADMITTED = new LoginAdmission.Admitted(LoginSlot.NONE);
  private static final LoginAdmission TOO_MANY_IN_PROGRESS = new LoginAdmission.TooManyInProgress();

  private final boolean enabled;

  /** Addresses never limited: the configured ones and the trusted proxies. */
  private final CidrRanges unlimitedAddresses;

  private final @Nullable RateLimiter connections;
  private final @Nullable RateLimiter statusPings;
  private final @Nullable RateLimiter logins;
  private final @Nullable ConcurrencyLimiter loginsInProgress;
  private final int maxAuthentications;
  private final AtomicInteger authentications = new AtomicInteger();
  private final Duration loginTimeout;
  private final Duration reportInterval;

  private final LongAdder refusedConnections = new LongAdder();
  private final LongAdder refusedStatusPings = new LongAdder();
  private final LongAdder refusedLoginsTooFast = new LongAdder();
  private final LongAdder refusedLoginsInProgress = new LongAdder();
  private final LongAdder refusedAuthentications = new LongAdder();
  private final LongAdder timedOutLogins = new LongAdder();

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  /**
   * Creates the limits of a proxy.
   *
   * @param config the {@code limits} configuration
   * @param trustedProxies the load balancers trusted to send the PROXY protocol, never limited;
   *     empty without the PROXY protocol
   */
  public ClientLimits(LimitsConfig config, CidrRanges trustedProxies) {
    this(config, trustedProxies, System::nanoTime);
  }

  /**
   * Creates the limits of a proxy on the given clock.
   *
   * @param config the {@code limits} configuration
   * @param trustedProxies the load balancers trusted to send the PROXY protocol, never limited
   * @param nanoClock a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
   */
  ClientLimits(LimitsConfig config, CidrRanges trustedProxies, LongSupplier nanoClock) {
    this.enabled = config.enabled();
    List<CidrRange> unlimited = new ArrayList<>(config.exempt().ranges());
    unlimited.addAll(trustedProxies.ranges());
    this.unlimitedAddresses = CidrRanges.of(unlimited);
    this.connections = rateLimiter(config.connections(), nanoClock);
    this.statusPings = rateLimiter(config.statusPings(), nanoClock);
    this.logins = rateLimiter(config.logins(), nanoClock);
    this.loginsInProgress =
        enabled && config.maxConcurrentLoginsPerAddress() > 0
            ? new ConcurrencyLimiter(config.maxConcurrentLoginsPerAddress())
            : null;
    this.maxAuthentications = enabled ? config.maxConcurrentAuthentications() : 0;
    this.loginTimeout = enabled ? config.loginTimeout() : Duration.ZERO;
    this.reportInterval = config.reportInterval();
  }

  /**
   * Returns limits that limit nothing.
   *
   * @return unlimited limits
   */
  public static ClientLimits unlimited() {
    return new ClientLimits(LimitsConfig.DISABLED, CidrRanges.none());
  }

  private @Nullable RateLimiter rateLimiter(LimitsConfig.Rate rate, LongSupplier nanoClock) {
    return enabled && rate.limited()
        ? new RateLimiter(rate.burst(), rate.interval(), nanoClock)
        : null;
  }

  // ---------------------------------------------------------------------------
  // Admission
  // ---------------------------------------------------------------------------

  /**
   * Counts a new connection of {@code client}, and says whether to accept it.
   *
   * @param client the client's address
   * @return {@code false} to drop the connection without reading it
   */
  public boolean admitConnection(SocketAddress client) {
    if (admit(connections, client)) {
      return true;
    }
    refusedConnections.increment();
    return false;
  }

  /**
   * Counts a server list ping of {@code client}, and says whether to answer it.
   *
   * @param client the client's address
   * @return {@code false} to close the connection without an answer
   */
  public boolean admitStatusPing(SocketAddress client) {
    if (admit(statusPings, client)) {
      return true;
    }
    refusedStatusPings.increment();
    return false;
  }

  /**
   * Counts a login attempt of {@code client}, and says whether it may go on. An admitted login
   * holds a {@link LoginSlot} until it is released, which the caller must do when the login ends,
   * whichever way. A refused attempt takes nothing from the address's budgets.
   *
   * @param client the client's address
   * @return the decision
   */
  public LoginAdmission admitLogin(SocketAddress client) {
    InetAddress address = limitedAddress(client);
    if (address == null) {
      return ADMITTED;
    }
    long key = AddressKey.of(address);
    // Logins in progress first: that check can be undone, a token of the rate limiter cannot.
    ConcurrencyLimiter inProgress = loginsInProgress;
    if (inProgress != null && !inProgress.tryAcquire(key)) {
      refusedLoginsInProgress.increment();
      return TOO_MANY_IN_PROGRESS;
    }
    RateLimiter rate = logins;
    long wait = rate == null ? 0 : rate.tryAcquire(key);
    if (wait > 0) {
      if (inProgress != null) {
        inProgress.release(key);
      }
      refusedLoginsTooFast.increment();
      return new LoginAdmission.TooFast(Duration.ofNanos(wait));
    }
    return inProgress == null
        ? ADMITTED
        : new LoginAdmission.Admitted(new LoginSlot(inProgress, key));
  }

  /**
   * Checks, as an online-mode login starts, that the proxy is not authenticating as many players as
   * it may, so that the client can be told while it still reads unencrypted packets. Takes no slot:
   * the login takes one with {@link #tryStartAuthentication()} once it sent its encryption
   * response, so that bots stalling before it hold none.
   *
   * @return {@code false}, counted as a refusal, if the authentications in flight are at their most
   */
  public boolean admitOnlineLogin() {
    if (maxAuthentications == 0 || authentications.get() < maxAuthentications) {
      return true;
    }
    refusedAuthentications.increment();
    return false;
  }

  /**
   * Starts an authentication if fewer than the maximum are in flight. Every successful call must be
   * followed by exactly one {@link #endAuthentication()}.
   *
   * @return {@code false} if the proxy is authenticating as many players as it may
   */
  public boolean tryStartAuthentication() {
    if (maxAuthentications == 0) {
      return true;
    }
    while (true) {
      int current = authentications.get();
      if (current >= maxAuthentications) {
        refusedAuthentications.increment();
        return false;
      }
      if (authentications.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  /** Ends an authentication started by {@link #tryStartAuthentication()}. */
  public void endAuthentication() {
    if (maxAuthentications != 0) {
      authentications.decrementAndGet();
    }
  }

  /**
   * Returns the longest a login may take, from the login handshake until the player is logged in.
   *
   * @return the timeout, or {@link Duration#ZERO} for none
   */
  public Duration loginTimeout() {
    return loginTimeout;
  }

  /** Counts a login closed for taking longer than {@link #loginTimeout()}. */
  public void loginTimedOut() {
    timedOutLogins.increment();
  }

  // ---------------------------------------------------------------------------
  // Report
  // ---------------------------------------------------------------------------

  /**
   * Returns how often {@link #report()} should run.
   *
   * @return the report interval
   */
  public Duration reportInterval() {
    return reportInterval;
  }

  /**
   * Logs, at INFO, what the limits refused since the previous report, in one line, and nothing if
   * they refused nothing.
   */
  public void report() {
    List<String> counts = new ArrayList<>(6);
    count(counts, "connections refused", refusedConnections);
    count(counts, "server list pings refused", refusedStatusPings);
    count(counts, "logins refused as too fast", refusedLoginsTooFast);
    count(counts, "logins refused as too many in progress per address", refusedLoginsInProgress);
    count(counts, "logins refused as too many authenticating", refusedAuthentications);
    count(counts, "logins timed out", timedOutLogins);
    if (!counts.isEmpty()) {
      logger.info(
          "Limits over the last {} s: {}", reportInterval.toSeconds(), String.join(", ", counts));
    }
  }

  private static void count(List<String> counts, String what, LongAdder counter) {
    long count = counter.sumThenReset();
    if (count > 0) {
      counts.add(what + ": " + count);
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private boolean admit(@Nullable RateLimiter limiter, SocketAddress client) {
    if (limiter == null) {
      return true;
    }
    InetAddress address = limitedAddress(client);
    return address == null || limiter.tryAcquire(AddressKey.of(address)) == 0;
  }

  /** Returns the address to count {@code client} under, or {@code null} if it is not limited. */
  private @Nullable InetAddress limitedAddress(SocketAddress client) {
    if (!enabled || !(client instanceof InetSocketAddress socket)) {
      return null;
    }
    InetAddress address = socket.getAddress();
    if (address == null || unlimitedAddresses.contains(address)) {
      return null;
    }
    return address;
  }
}
