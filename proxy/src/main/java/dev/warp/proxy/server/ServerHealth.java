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
package dev.warp.proxy.server;

import dev.warp.proxy.logging.LogCode;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the proxy knows about each server's health: a circuit breaker fed by its pings and by the
 * players' logins, and the round trip of its last successful ping.
 *
 * <p>The breaker of a server is closed while it answers. A failed ping or a connection that could
 * not be opened opens it: the balancers skip the server for a cool-down. A server that accepts the
 * connection but refuses the login may be refusing that one player (a ban, a whitelist), so a
 * refusal only counts, and the breaker opens after {@link #LOGIN_REFUSALS_TO_OPEN} refusals in a
 * row with no login in between. Once the cool-down has passed the breaker is half-open, and one
 * player is let through as a probe; its login closes the breaker again if the server accepts it, or
 * opens it for another cool-down if the connection or the login fails. A probe that never reports
 * (the player left meanwhile) expires after a cool-down, and another is let through. A successful
 * ping closes the breaker at any time.
 *
 * <p>Lock-free: every change is a compare-and-set on the server's snapshot.
 */
public final class ServerHealth {

  private static final Logger logger = LoggerFactory.getLogger(ServerHealth.class);

  /** How long a breaker stays open before a probe is let through. */
  public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(10);

  /**
   * How many logins in a row a server with a closed breaker may refuse before its breaker opens.
   * Below that, the refusals are taken as aimed at those players.
   */
  public static final int LOGIN_REFUSALS_TO_OPEN = 3;

  /** The state of a server's circuit breaker. */
  public enum State {
    /** The server answers: it is picked normally. */
    CLOSED,
    /** The server failed recently: it is skipped until its cool-down passes. */
    OPEN,
    /** The cool-down has passed: one connection is let through to find out. */
    HALF_OPEN
  }

  /**
   * A server's health at one moment.
   *
   * @param state the breaker's state
   * @param failures the failures in a row since it last answered
   * @param latency the round trip of its last successful ping, or {@code null} if none yet
   * @param openedAtNanos when the breaker opened, or a probe was let through; meaningful outside
   *     {@link State#CLOSED}
   * @param probing whether a probe is in flight
   */
  public record Status(
      State state, int failures, @Nullable Duration latency, long openedAtNanos, boolean probing) {

    /**
     * Returns the round trip of the last successful ping.
     *
     * @return the round trip, or empty if the server never answered a ping
     */
    public Optional<Duration> rtt() {
      return Optional.ofNullable(latency);
    }
  }

  private static final Status UNKNOWN = new Status(State.CLOSED, 0, null, 0, false);

  private final Map<String, AtomicReference<Status>> servers = new ConcurrentHashMap<>();
  private final LongSupplier clock;
  private final long cooldownNanos;

  /** Creates a health tracker with the default cool-down and the system clock. */
  public ServerHealth() {
    this(System::nanoTime, DEFAULT_COOLDOWN);
  }

  /**
   * Creates a health tracker.
   *
   * @param clock the clock, in nanoseconds
   * @param cooldown how long a breaker stays open, positive
   */
  public ServerHealth(LongSupplier clock, Duration cooldown) {
    if (cooldown.isZero() || cooldown.isNegative()) {
      throw new IllegalArgumentException("A cool-down must be positive, got " + cooldown);
    }
    this.clock = clock;
    this.cooldownNanos = cooldown.toNanos();
  }

  /**
   * Returns a server's health.
   *
   * @param server the server's name, lowercase
   * @return its status, closed with no failure for a server never heard of
   */
  public Status status(String server) {
    AtomicReference<Status> ref = servers.get(server);
    if (ref == null) {
      return UNKNOWN;
    }
    Status status = Objects.requireNonNull(ref.get());
    if (status.state() == State.OPEN && elapsed(status.openedAtNanos())) {
      return new Status(
          State.HALF_OPEN, status.failures(), status.latency(), status.openedAtNanos(), false);
    }
    return status;
  }

  /**
   * Returns the round trip of a server's last successful ping.
   *
   * @param server the server's name, lowercase
   * @return the round trip, or empty if it never answered a ping
   */
  public Optional<Duration> latency(String server) {
    return status(server).rtt();
  }

  /**
   * Returns whether a server may be picked right now: its breaker is closed, or it is half-open
   * with no probe in flight. Does not take the probe: call {@link #picked} once the server is
   * chosen.
   *
   * @param server the server's name, lowercase
   * @return {@code true} if a player may be sent there
   */
  public boolean allows(String server) {
    Status status = status(server);
    return switch (status.state()) {
      case CLOSED -> true;
      case OPEN -> false;
      case HALF_OPEN -> !status.probing() || elapsed(status.openedAtNanos());
    };
  }

  /**
   * Records that a player is sent to a server. On a half-open breaker, that player is the probe:
   * nobody else is sent there until it reports.
   *
   * @param server the server's name, lowercase
   */
  public void picked(String server) {
    AtomicReference<Status> ref = servers.get(server);
    if (ref == null) {
      return;
    }
    while (true) {
      Status current = Objects.requireNonNull(ref.get());
      if (current.state() == State.CLOSED) {
        return;
      }
      if (current.state() == State.OPEN && !elapsed(current.openedAtNanos())) {
        return;
      }
      Status probing =
          new Status(
              State.HALF_OPEN, current.failures(), current.latency(), clock.getAsLong(), true);
      if (ref.compareAndSet(current, probing)) {
        return;
      }
    }
  }

  /**
   * Records a ping the server answered: closes its breaker and keeps the round trip.
   *
   * @param server the server's name, lowercase
   * @param rtt the round trip of the ping
   */
  public void pingSucceeded(String server, Duration rtt) {
    close(server, rtt);
  }

  /**
   * Records a ping the server did not answer: opens its breaker.
   *
   * @param server the server's name, lowercase
   */
  public void pingFailed(String server) {
    open(server, "it did not answer a ping");
  }

  /**
   * Records a login the server accepted: closes its breaker. A connection that is merely open
   * proves nothing, a server may accept every connection and refuse every login.
   *
   * @param server the server's name, lowercase
   */
  public void connectSucceeded(String server) {
    close(server, null);
  }

  /**
   * Records a connection that could not be opened (refused, unreachable, timed out): opens its
   * breaker.
   *
   * @param server the server's name, lowercase
   */
  public void connectFailed(String server) {
    open(server, "a player could not connect to it");
  }

  /**
   * Records a login the server refused, or a connection it closed before the login was over. On a
   * closed breaker the refusal counts, and the breaker opens at the {@link #LOGIN_REFUSALS_TO_OPEN
   * third} in a row; a probe refused opens it at once.
   *
   * @param server the server's name, lowercase
   */
  public void loginRefused(String server) {
    AtomicReference<Status> ref =
        servers.computeIfAbsent(server, _ -> new AtomicReference<>(UNKNOWN));
    while (true) {
      Status current = Objects.requireNonNull(ref.get());
      if (current.state() != State.CLOSED || current.failures() + 1 >= LOGIN_REFUSALS_TO_OPEN) {
        open(server, "it refused the logins of " + LOGIN_REFUSALS_TO_OPEN + " players in a row");
        return;
      }
      Status counted =
          new Status(State.CLOSED, current.failures() + 1, current.latency(), 0, false);
      if (ref.compareAndSet(current, counted)) {
        return;
      }
    }
  }

  /**
   * Forgets a server that was unregistered.
   *
   * @param server the server's name, lowercase
   */
  public void forget(String server) {
    servers.remove(server);
  }

  private void close(String server, @Nullable Duration rtt) {
    AtomicReference<Status> ref =
        servers.computeIfAbsent(server, _ -> new AtomicReference<>(UNKNOWN));
    while (true) {
      Status current = Objects.requireNonNull(ref.get());
      Status closed = new Status(State.CLOSED, 0, rtt != null ? rtt : current.latency(), 0, false);
      if (ref.compareAndSet(current, closed)) {
        if (current.state() != State.CLOSED) {
          logger.info("Server '{}' answers again: it takes players", server);
        }
        return;
      }
    }
  }

  private void open(String server, String why) {
    AtomicReference<Status> ref =
        servers.computeIfAbsent(server, _ -> new AtomicReference<>(UNKNOWN));
    while (true) {
      Status current = Objects.requireNonNull(ref.get());
      Status opened =
          new Status(
              State.OPEN, current.failures() + 1, current.latency(), clock.getAsLong(), false);
      if (ref.compareAndSet(current, opened)) {
        if (current.state() == State.CLOSED) {
          logger.warn(
              LogCode.SERVER_BREAKER_OPENED.marker(),
              "Server '{}' is skipped by the balancers for {} seconds: {}",
              server,
              cooldownNanos / 1_000_000_000L,
              why);
        }
        return;
      }
    }
  }

  private boolean elapsed(long sinceNanos) {
    return clock.getAsLong() - sinceNanos >= cooldownNanos;
  }
}
