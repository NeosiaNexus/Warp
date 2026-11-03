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

import dev.warp.api.event.player.ServerPreConnectEvent;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/** {@link ServerPreConnectEvent} as the proxy fires it. */
public final class ServerPreConnectEventImpl implements ServerPreConnectEvent {

  private final Player player;
  private final RegisteredServer originalServer;
  private final @Nullable RegisteredServer previousServer;
  private volatile Result result;

  /**
   * Creates the event.
   *
   * @param player the player
   * @param originalServer the server the connection was requested to
   * @param previousServer the server it plays on, or {@code null} on its first connection
   */
  public ServerPreConnectEventImpl(
      Player player, RegisteredServer originalServer, @Nullable RegisteredServer previousServer) {
    this.player = player;
    this.originalServer = originalServer;
    this.previousServer = previousServer;
    this.result = new Allowed(originalServer);
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public RegisteredServer originalServer() {
    return originalServer;
  }

  @Override
  public Optional<RegisteredServer> previousServer() {
    return Optional.ofNullable(previousServer);
  }

  @Override
  public Result result() {
    return result;
  }

  @Override
  public void setResult(Result result) {
    this.result = Objects.requireNonNull(result, "result");
  }
}
