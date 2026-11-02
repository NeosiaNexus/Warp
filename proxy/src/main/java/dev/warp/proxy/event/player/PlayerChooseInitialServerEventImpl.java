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

import dev.warp.api.event.player.PlayerChooseInitialServerEvent;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/** {@link PlayerChooseInitialServerEvent} as the proxy fires it. */
public final class PlayerChooseInitialServerEventImpl implements PlayerChooseInitialServerEvent {

  private final Player player;
  private volatile @Nullable RegisteredServer initialServer;

  /**
   * Creates the event.
   *
   * @param player the player
   * @param proposed the server the proxy proposes, or {@code null} if it has none
   */
  public PlayerChooseInitialServerEventImpl(Player player, @Nullable RegisteredServer proposed) {
    this.player = player;
    this.initialServer = proposed;
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public Optional<RegisteredServer> initialServer() {
    return Optional.ofNullable(initialServer);
  }

  @Override
  public void setInitialServer(@Nullable RegisteredServer server) {
    this.initialServer = server;
  }
}
