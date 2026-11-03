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

import dev.warp.proxy.config.RemoteConsoleConfig;
import dev.warp.proxy.config.RemoteConsoleConfig.Token;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * Who may use the remote console, and how much: bearer tokens compared in constant time, a lockout
 * of the addresses that send wrong tokens, and limits on commands, completions and streams per
 * token.
 *
 * <p>The comparison digests the token presented and compares it with every configured digest,
 * without stopping at a match: how long it takes says nothing of the tokens. An address that sent
 * {@code lockoutAfter} wrong tokens within the lockout duration is locked out for that duration:
 * its requests are refused before their token is even read, so guessing stops there. IPv6 clients
 * are counted by their /64, which a single host usually owns whole. A missing token is refused
 * without counting: it guesses nothing.
 */
final class ConsoleAuth {

  /** The window of the rate limits. */
  static final Duration WINDOW = Duration.ofSeconds(10);

  /** Commands one token may run per window. */
  static final int COMMANDS_PER_WINDOW = 20;

  /** Completions one token may ask for per window. */
  static final int SUGGESTIONS_PER_WINDOW = 100;

  /** Streams one token may hold open at once. */
  static final int STREAMS_PER_TOKEN = 4;

  /** Streams all tokens may hold open at once. */
  static final int STREAMS_TOTAL = 32;

  /** Past this many addresses tracked, the expired ones are forgotten. */
  private static final int TRACKED_LIMIT = 4096;

  private static final String BEARER = "Bearer ";

  /** What became of a request's token. */
  sealed interface Result {

    /**
     * The token is valid.
     *
     * @param token the token
     */
    record Granted(Token token) implements Result {}

    /** No token was presented. */
    record Missing() implements Result {}

    /**
     * The token is wrong.
     *
     * @param failures the wrong tokens of this address so far
     * @param limit how many lock it out
     * @param lockedOut whether this one locked it out
     */
    record Wrong(int failures, int limit, boolean lockedOut) implements Result {}

    /**
     * The address is locked out; its token was not read.
     *
     * @param retryAfter how long until it may try again
     */
    record LockedOut(Duration retryAfter) implements Result {}
  }

  private final List<Token> tokens;
  private final int lockoutAfter;
  private final long lockoutNanos;
  private final LongSupplier clock;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();
  private final Map<String, Window> commandWindows = new ConcurrentHashMap<>();
  private final Map<String, Window> suggestionWindows = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> streams = new ConcurrentHashMap<>();
  private final AtomicInteger openStreams = new AtomicInteger();

  /**
   * Creates the authentication of a console.
   *
   * @param config the console's section
   * @param clock {@link System#nanoTime()}, or a test's clock
   */
  ConsoleAuth(RemoteConsoleConfig config, LongSupplier clock) {
    this.tokens = config.tokens();
    this.lockoutAfter = config.lockoutAfter();
    this.lockoutNanos = config.lockoutDuration().toNanos();
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------
  // Tokens and lockout
  // ---------------------------------------------------------------------------

  /**
   * Checks a request's {@code Authorization} header.
   *
   * @param address where the request comes from
   * @param authorization the header, or {@code null}
   * @return the token, or why there is none
   */
  Result authenticate(InetAddress address, @Nullable String authorization) {
    String key = key(address);
    long now = clock.getAsLong();
    Failures current = failures.get(key);
    if (current != null && current.lockedUntil - now > 0) {
      return new Result.LockedOut(Duration.ofNanos(current.lockedUntil - now));
    }
    if (authorization == null
        || authorization.length() <= BEARER.length()
        || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return new Result.Missing();
    }
    byte[] presented = RemoteConsoleConfig.sha256(authorization.substring(BEARER.length()).strip());
    Token match = null;
    for (Token token : tokens) {
      // Every digest is compared, whether or not one matched already.
      if (MessageDigest.isEqual(presented, token.digest()) && match == null) {
        match = token;
      }
    }
    if (match != null) {
      failures.remove(key);
      return new Result.Granted(match);
    }
    if (failures.size() > TRACKED_LIMIT) {
      failures.values().removeIf(entry -> entry.expired(now, lockoutNanos));
    }
    Failures updated =
        failures.compute(
            key,
            (_, entry) ->
                entry == null || entry.expired(now, lockoutNanos)
                    ? new Failures(1, now, now)
                    : new Failures(entry.count + 1, entry.first, entry.lockedUntil));
    if (updated.count >= lockoutAfter) {
      failures.put(key, new Failures(updated.count, updated.first, now + lockoutNanos));
      return new Result.Wrong(updated.count, lockoutAfter, true);
    }
    return new Result.Wrong(updated.count, lockoutAfter, false);
  }

  /**
   * Returns how an address is counted: itself, or its /64 for IPv6.
   *
   * @param address the address
   * @return the key of its failures, as the audit log shows it
   */
  static String key(InetAddress address) {
    if (address instanceof Inet6Address) {
      byte[] bytes = address.getAddress();
      StringBuilder prefix = new StringBuilder();
      for (int i = 0; i < 8; i += 2) {
        prefix
            .append(HexFormat.of().toHexDigits(bytes[i]))
            .append(HexFormat.of().toHexDigits(bytes[i + 1]));
        prefix.append(':');
      }
      return prefix.append(":/64").toString();
    }
    return address.getHostAddress();
  }

  /**
   * Wrong tokens of one address.
   *
   * @param count how many
   * @param first when the first was sent
   * @param lockedUntil until when the address is locked out; {@code first} when it is not
   */
  private record Failures(int count, long first, long lockedUntil) {

    boolean expired(long now, long duration) {
      return now - first > duration && now - lockedUntil > 0;
    }
  }

  // ---------------------------------------------------------------------------
  // Rate limits
  // ---------------------------------------------------------------------------

  /**
   * Counts a command against its token's limit.
   *
   * @param token the token
   * @return {@code true} if it may run
   */
  boolean allowCommand(Token token) {
    return allow(commandWindows, token, COMMANDS_PER_WINDOW);
  }

  /**
   * Counts a completion against its token's limit.
   *
   * @param token the token
   * @return {@code true} if it may be answered
   */
  boolean allowSuggestion(Token token) {
    return allow(suggestionWindows, token, SUGGESTIONS_PER_WINDOW);
  }

  /**
   * Opens a stream, if neither its token nor the server holds too many.
   *
   * @param token the token
   * @return {@code true} if it may open; {@link #closeStream} must then follow
   */
  boolean openStream(Token token) {
    AtomicInteger count = streams.computeIfAbsent(token.name(), _ -> new AtomicInteger());
    if (count.incrementAndGet() > STREAMS_PER_TOKEN) {
      count.decrementAndGet();
      return false;
    }
    if (openStreams.incrementAndGet() > STREAMS_TOTAL) {
      openStreams.decrementAndGet();
      count.decrementAndGet();
      return false;
    }
    return true;
  }

  /**
   * Closes a stream opened by {@link #openStream}.
   *
   * @param token its token
   */
  void closeStream(Token token) {
    AtomicInteger count = streams.get(token.name());
    if (count != null) {
      count.decrementAndGet();
    }
    openStreams.decrementAndGet();
  }

  private boolean allow(Map<String, Window> windows, Token token, int max) {
    long now = clock.getAsLong();
    long length = WINDOW.toNanos();
    Window window =
        windows.compute(
            token.name(),
            (_, current) ->
                current == null || now - current.start >= length
                    ? new Window(now, 1)
                    : new Window(current.start, current.count + 1));
    return window.count <= max;
  }

  /**
   * A fixed window of a rate limit.
   *
   * @param start when it began
   * @param count the requests in it
   */
  private record Window(long start, int count) {}
}
