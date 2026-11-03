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

import java.util.Objects;
import java.util.Optional;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** The proxy's {@link PlayerChooseInitialServerEvent}. */
public final class PlayerChooseInitialServerEventImpl implements PlayerChooseInitialServerEvent {

  /** The reason a player is disconnected with when no server can take it. */
  public static final Component NO_SERVER_AVAILABLE =
      Component.text("No server is available right now. Try again later.");

  private final Player player;
  private final @Nullable RegisteredServer proposed;
  private volatile Result result;

  /**
   * Creates the event.
   *
   * @param player the player
   * @param proposed the server the proxy proposes, or {@code null} if it has none: the result then
   *     starts as a denial with {@link #NO_SERVER_AVAILABLE}
   */
  public PlayerChooseInitialServerEventImpl(Player player, @Nullable RegisteredServer proposed) {
    this.player = player;
    this.proposed = proposed;
    this.result = proposed == null ? new Denied(NO_SERVER_AVAILABLE) : new Allowed(proposed);
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public Optional<RegisteredServer> proposedServer() {
    return Optional.ofNullable(proposed);
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
