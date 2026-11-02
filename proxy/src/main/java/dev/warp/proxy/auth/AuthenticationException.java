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
package dev.warp.proxy.auth;

/**
 * Thrown when Mojang session authentication fails.
 *
 * <p>Causes include network errors, invalid responses from the session server, or the client
 * failing to authenticate (e.g. cracked client on an online-mode proxy).
 */
public final class AuthenticationException extends Exception {

  private static final long serialVersionUID = 1L;

  /** Whether the session server failed, rather than deny that the player joined. */
  private final boolean serverFault;

  /**
   * Creates the exception of a session server that failed: an unexpected answer.
   *
   * @param message what went wrong
   */
  public AuthenticationException(String message) {
    this(message, true);
  }

  /**
   * Creates the exception of a session server that could not be reached or understood.
   *
   * @param message what went wrong
   * @param cause the failure
   */
  public AuthenticationException(String message, Throwable cause) {
    super(message, cause);
    this.serverFault = true;
  }

  private AuthenticationException(String message, boolean serverFault) {
    super(message);
    this.serverFault = serverFault;
  }

  /**
   * Creates the exception of a player the session server says did not join: a cracked client, or a
   * forged login.
   *
   * @param message what went wrong
   * @return the exception
   */
  public static AuthenticationException notJoined(String message) {
    return new AuthenticationException(message, false);
  }

  /**
   * Returns whether the session server failed (unreachable, an error status such as 429, an answer
   * that does not parse), rather than deny that the player joined: while it fails, no player can
   * log in, which an operator must hear about.
   *
   * @return {@code true} for a fault of the session server
   */
  public boolean serverFault() {
    return serverFault;
  }
}
