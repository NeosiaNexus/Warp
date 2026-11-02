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
package dev.warp.proxy.connection;

import dev.warp.protocol.netty.FrameLimitException;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;

/**
 * Watches a client connection from its accept until its client is logged in, then goes away: a
 * logged-in player carries nothing of it.
 *
 * <ul>
 *   <li>It holds the connection's place among the connections not logged in ({@link
 *       ClientLimits#tryStartPending()}), given back once the client is logged in or gone.
 *   <li>It enforces the absolute deadlines of the states before login: the handshake within {@link
 *       ClientLimits#handshakeTimeout()} of the accept (a PROXY protocol header included), then a
 *       server list ping within {@link ClientLimits#statusTimeout()} of its handshake. Bytes
 *       trickled in do not extend them, unlike the read timeout that any byte used to reset. A
 *       login is timed by its handler ({@link ClientLimits#loginTimeout()}), which can tell the
 *       client why.
 *   <li>It turns what a client can make the pipeline throw before login (a frame over the limits of
 *       its state, a malformed packet, an idle time-out) into a {@link Refusal} count, where it
 *       used to be a log line per connection.
 * </ul>
 *
 * <p>Used on the connection's event loop only.
 */
final class ConnectionGuard {

  private final MinecraftConnection connection;
  private final Channel channel;
  private final ClientLimits limits;

  /** The deadline of the current state, or {@code null} when none runs. */
  private @Nullable ScheduledFuture<?> deadline;

  /** Whether the client is logged in or gone: the guard has given everything back. */
  private boolean ended;

  /**
   * Creates the guard of a connection that holds a place among the connections not logged in, and
   * starts its handshake deadline.
   *
   * @param connection the client connection
   * @param limits the proxy's limits, which counted the connection with {@link
   *     ClientLimits#tryStartPending()}
   */
  ConnectionGuard(MinecraftConnection connection, ClientLimits limits) {
    this.connection = connection;
    this.channel = connection.channel();
    this.limits = limits;
    schedule(limits.handshakeTimeout(), Refusal.HANDSHAKE_TIMEOUT);
  }

  // ---------------------------------------------------------------------------
  // States
  // ---------------------------------------------------------------------------

  /** The handshake asked for the server list: the whole ping now has the status timeout. */
  void statusStarted() {
    schedule(limits.statusTimeout(), Refusal.STATUS_TIMEOUT);
  }

  /** The handshake asked to log in: the login handler's own timeout takes over. */
  void loginStarted() {
    cancelDeadline();
  }

  /** The client is logged in, or the connection closed: gives everything back. Idempotent. */
  void end() {
    if (ended) {
      return;
    }
    ended = true;
    cancelDeadline();
    limits.endPending();
  }

  // ---------------------------------------------------------------------------
  // Failures before login
  // ---------------------------------------------------------------------------

  /**
   * Counts what the pipeline threw for a client that is not logged in, if a client can cause it at
   * will.
   *
   * @param cause what the pipeline threw
   * @return the reason it was counted under, or {@code null} for anything else, which may reveal a
   *     bug and is logged as before
   */
  @Nullable Refusal failed(Throwable cause) {
    Refusal reason;
    if (cause instanceof ReadTimeoutException) {
      reason = Refusal.IDLE_TIMEOUT;
    } else if (cause instanceof FrameLimitException || cause instanceof DecoderException) {
      reason = Refusal.INVALID_FRAME;
    } else {
      return null;
    }
    limits.refused(reason, connection.remoteAddress());
    return reason;
  }

  // ---------------------------------------------------------------------------
  // Deadlines
  // ---------------------------------------------------------------------------

  private void schedule(Duration timeout, Refusal reason) {
    cancelDeadline();
    if (ended || timeout.isZero()) {
      return;
    }
    deadline =
        channel
            .eventLoop()
            .schedule(() -> expired(reason), timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void expired(Refusal reason) {
    deadline = null;
    if (ended || !channel.isActive()) {
      return;
    }
    limits.refused(reason, connection.remoteAddress());
    ServerChannelInitializer.reset(channel);
  }

  private void cancelDeadline() {
    ScheduledFuture<?> pending = deadline;
    if (pending != null) {
      deadline = null;
      pending.cancel(false);
    }
  }
}
