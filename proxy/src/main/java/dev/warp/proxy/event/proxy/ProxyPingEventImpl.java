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
package dev.warp.proxy.event.proxy;

import dev.warp.api.event.proxy.ProxyPingEvent;
import dev.warp.api.player.InboundConnection;
import dev.warp.api.server.ServerPing;
import dev.warp.proxy.logging.LogCode;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProxyPingEvent} as the proxy fires it.
 *
 * <p>Once the proxy took the entry to answer ({@link #answer()}), later changes are ignored and
 * logged, with the stack of the code that made them: a handler that changes the entry from a
 * callback it did not return (PaperMC/Velocity#1349) learns why its change never shows.
 */
public final class ProxyPingEventImpl implements ProxyPingEvent {

  private static final Logger logger = LoggerFactory.getLogger(ProxyPingEventImpl.class);

  /** Whether a late change was reported at WARN already; later ones go to DEBUG. */
  private static final AtomicBoolean REPORTED_LATE_CHANGE = new AtomicBoolean();

  private final InboundConnection connection;
  private volatile ServerPing ping;
  private volatile Result result = new Answer();
  private volatile boolean answered;

  /**
   * Creates the event.
   *
   * @param connection the connection asking
   * @param proposal the entry the proxy proposes
   */
  public ProxyPingEventImpl(InboundConnection connection, ServerPing proposal) {
    this.connection = connection;
    this.ping = proposal;
  }

  @Override
  public InboundConnection connection() {
    return connection;
  }

  @Override
  public ServerPing ping() {
    return ping;
  }

  @Override
  public void setPing(ServerPing ping) {
    Objects.requireNonNull(ping, "ping");
    if (answered) {
      reportLateChange();
      return;
    }
    this.ping = ping;
  }

  @Override
  public Result result() {
    return result;
  }

  @Override
  public void setResult(Result result) {
    Objects.requireNonNull(result, "result");
    if (answered) {
      reportLateChange();
      return;
    }
    this.result = result;
  }

  /**
   * Takes the outcome the proxy acts on, once every handler is done: from now on, changes are
   * ignored.
   *
   * @return the entry to answer with, or {@code null} if a handler chose not to answer
   */
  public @Nullable ServerPing answer() {
    answered = true;
    return result instanceof Ignore ? null : ping;
  }

  private static void reportLateChange() {
    String message =
        "A plugin changed a ProxyPingEvent after the proxy answered the ping: ignored. A handler that"
            + " changes it from a callback must return that callback's stage, such as"
            + " CompletableFuture, to hold the ping until it is done";
    if (REPORTED_LATE_CHANGE.compareAndSet(false, true)) {
      logger.warn(
          LogCode.PING_CHANGED_LATE.marker(),
          "{} (reported once; later ones are logged at DEBUG)",
          message,
          new IllegalStateException("Changed here"));
    } else {
      logger.debug(message, new IllegalStateException("Changed here"));
    }
  }

  @Override
  public String toString() {
    return "ProxyPingEvent[" + connection + ", " + ping + ", " + result + "]";
  }
}
