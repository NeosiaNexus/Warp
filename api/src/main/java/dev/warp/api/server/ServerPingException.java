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
package dev.warp.api.server;

import java.io.IOException;
import java.io.Serial;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Why {@link RegisteredServer#ping()} failed: the future it returns completes exceptionally with
 * this exception (wrapped in a {@link java.util.concurrent.CompletionException} by dependent stages
 * and {@code join()}, in an {@link java.util.concurrent.ExecutionException} by {@code get()}).
 *
 * <p>Switch on {@link #reason()} rather than on the cause, which depends on the transport.
 *
 * <p>Created by the proxy; plugins only catch it.
 */
public final class ServerPingException extends IOException {

  @Serial private static final long serialVersionUID = 1L;

  /** What went wrong. */
  public enum Reason {
    /** The server did not answer within the time-out, connection included. */
    TIMED_OUT,
    /** The server could not be reached: connection refused, unknown host, no route. */
    UNREACHABLE,
    /** The server closed the connection without answering. */
    CLOSED,
    /** The server answered something that is not a server list entry. */
    INVALID_RESPONSE
  }

  /** The server pinged. Records are not serializable here: kept out of the serialized form. */
  private final transient ServerInfo server;

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param server the server pinged
   * @param reason what went wrong
   * @param detail what happened, for the message
   * @param cause the underlying error, or {@code null}
   */
  public ServerPingException(
      ServerInfo server, Reason reason, String detail, @Nullable Throwable cause) {
    super("Ping of " + server.name() + " (" + server.address() + ") failed: " + detail, cause);
    this.server = Objects.requireNonNull(server, "server");
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /**
   * Returns the server that was pinged.
   *
   * @return the server
   */
  public ServerInfo server() {
    return server;
  }

  /**
   * Returns what went wrong.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
