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
package dev.warp.proxy.event.player;

import dev.warp.api.event.player.PreLoginEvent;
import dev.warp.api.player.InboundConnection;

import java.util.Objects;

/** {@link PreLoginEvent} as the proxy fires it. */
public final class PreLoginEventImpl implements PreLoginEvent {

  private final InboundConnection connection;
  private final String username;
  private volatile Result result = new Allowed();

  /**
   * Creates the event.
   *
   * @param connection the connection logging in
   * @param username the name it claims
   */
  public PreLoginEventImpl(InboundConnection connection, String username) {
    this.connection = connection;
    this.username = username;
  }

  @Override
  public InboundConnection connection() {
    return connection;
  }

  @Override
  public String username() {
    return username;
  }

  @Override
  public Result result() {
    return result;
  }

  @Override
  public void setResult(Result result) {
    this.result = Objects.requireNonNull(result, "result");
  }

  @Override
  public String toString() {
    return "PreLoginEvent[" + username + ", " + result + "]";
  }
}
