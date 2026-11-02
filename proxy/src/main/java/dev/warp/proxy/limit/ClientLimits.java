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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

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
 *   <li><b>Connections not logged in</b>, for the whole proxy: what a botnet of many addresses can
 *       hold at once, which per-address limits cannot bound. Over the cap, a new connection is
 *       reset before anything is read from it.
 *   <li><b>Deadlines</b> before login: {@link #handshakeTimeout()} from the accept to the
 *       handshake, {@link #statusTimeout()} for a whole server list ping, {@link #loginTimeout()}
 *       for a login. They are absolute: trickling bytes does not extend them.
 * </ul>
 *
 * <p>Addresses count IPv4 by address and IPv6 by /64 ({@link AddressKey}). Exempt addresses, and
 * the trusted proxies themselves (whose own connections are health checks), are never limited; nor
 * is a connection whose address is not an IP address, which a TCP listener never accepts.
 *
 * <p>Refusals are counted by {@link Refusal}, never logged one by one: {@link #report()} logs the
 * counts at most once per report interval, so that a flood cannot flood the logs. The rest of the
 * proxy counts its own refusals of clients that are not logged in here too ({@link
 * #refused(Refusal)}). Every method is thread-safe and lock-free; the per-address checks allocate a
 * boxed key and the address bytes, once per connection.
 */
public final class ClientLimits {

  private static final Logger logger = LoggerFactory.getLogger(ClientLimits.class);

  /** The prefix of the context keys a {@linkplain #report() report} gives its counts under. */
  public static final String REPORT_KEY_PREFIX = "refused.";

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
  private final int maxPending;
  private final AtomicInteger pending = new AtomicInteger();
  private final Duration loginTimeout;
  private final Duration handshakeTimeout;
  private final Duration statusTimeout;
  private final Duration reportInterval;

  /** Refusals since the start, by reason; filled once, then only read. */
  private final EnumMap<Refusal, LongAdder> refusals = new EnumMap<>(Refusal.class);

  /** The totals of the previous report; read and written by {@link #report()} only. */
  private final EnumMap<Refusal, Long> reported = new EnumMap<>(Refusal.class);

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
    this.maxPending = enabled ? config.maxPendingConnections() : 0;
    this.loginTimeout = enabled ? config.loginTimeout() : Duration.ZERO;
    this.handshakeTimeout = enabled ? config.handshakeTimeout() : Duration.ZERO;
    this.statusTimeout = enabled ? config.statusTimeout() : Duration.ZERO;
    this.reportInterval = config.reportInterval();
    for (Refusal reason : Refusal.values()) {
      refusals.put(reason, new LongAdder());
      reported.put(reason, 0L);
    }
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
    refused(Refusal.CONNECTION_RATE);
    return false;
  }

  /**
   * Counts a connection the proxy accepted among those not logged in yet, if it holds fewer than
   * the cap. Every successful call must be followed by exactly one {@link #endPending()}, once the
   * client is logged in or the connection closed.
   *
   * @return {@code false}, counted as a refusal, to reset the connection without reading it
   */
  public boolean tryStartPending() {
    int now = pending.incrementAndGet();
    if (maxPending != 0 && now > maxPending) {
      pending.decrementAndGet();
      refused(Refusal.PENDING_CONNECTIONS);
      return false;
    }
    return true;
  }

  /** Ends the count of a connection started by {@link #tryStartPending()}. */
  public void endPending() {
    pending.decrementAndGet();
  }

  /**
   * Returns how many connections are not logged in yet.
   *
   * @return the count
   */
  public int pendingConnections() {
    return pending.get();
  }

  /**
   * Returns the most connections not logged in the proxy holds at once.
   *
   * @return the cap, or 0 for none
   */
  public int maxPendingConnections() {
    return maxPending;
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
    refused(Refusal.STATUS_RATE);
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
      refused(Refusal.LOGINS_IN_PROGRESS);
      return TOO_MANY_IN_PROGRESS;
    }
    RateLimiter rate = logins;
    long wait = rate == null ? 0 : rate.tryAcquire(key);
    if (wait > 0) {
      if (inProgress != null) {
        inProgress.release(key);
      }
      refused(Refusal.LOGIN_RATE);
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
    refused(Refusal.AUTHENTICATION_BUSY);
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
        refused(Refusal.AUTHENTICATION_BUSY);
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

  /**
   * Returns the longest a connection may take to send its handshake, from the accept.
   *
   * @return the timeout, or {@link Duration#ZERO} for none
   */
  public Duration handshakeTimeout() {
    return handshakeTimeout;
  }

  /**
   * Returns the longest a server list ping may take, from its handshake.
   *
   * @return the timeout, or {@link Duration#ZERO} for none
   */
  public Duration statusTimeout() {
    return statusTimeout;
  }

  // ---------------------------------------------------------------------------
  // Refusals
  // ---------------------------------------------------------------------------

  /**
   * Counts a connection the proxy closed before its client logged in, for the report and the
   * metrics, instead of a log line.
   *
   * @param reason why
   */
  public void refused(Refusal reason) {
    counter(reason).increment();
  }

  /**
   * Returns how many connections were refused for a reason since the proxy started.
   *
   * @param reason the reason
   * @return the count
   */
  public long refusals(Refusal reason) {
    return counter(reason).sum();
  }

  private LongAdder counter(Refusal reason) {
    LongAdder counter = refusals.get(reason);
    if (counter == null) {
      throw new AssertionError("every reason has a counter: " + reason);
    }
    return counter;
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
   * Logs, at INFO, the connections refused before login since the previous report, in one line by
   * reason, and nothing if there were none. Each count also goes in the record's context, under
   * {@value #REPORT_KEY_PREFIX} and the reason in lower case ({@code refused.handshake_timeout}),
   * which JSON logs and plugin log sinks carry as fields. Called from one thread at a time.
   */
  public void report() {
    List<String> counts = new ArrayList<>();
    LoggingEventBuilder record = logger.atInfo();
    for (Refusal reason : Refusal.values()) {
      long total = counter(reason).sum();
      Long previous = reported.put(reason, total);
      long count = total - (previous == null ? 0 : previous);
      if (count > 0) {
        counts.add(reason.description() + ": " + count);
        record =
            record.addKeyValue(REPORT_KEY_PREFIX + reason.name().toLowerCase(Locale.ROOT), count);
      }
    }
    if (!counts.isEmpty()) {
      record.log(
          "Refused before login over the last {} s: {}",
          reportInterval.toSeconds(),
          String.join(", ", counts));
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
