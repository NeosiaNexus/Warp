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

import dev.warp.api.event.player.KickedFromServerEvent;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.util.Objects;
import java.util.Optional;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** {@link KickedFromServerEvent} as the proxy fires it. */
public final class KickedFromServerEventImpl implements KickedFromServerEvent {

  private final Player player;
  private final RegisteredServer server;
  private final Cause cause;
  private final @Nullable Component serverKickReason;
  private final boolean duringServerConnect;
  private volatile Result result;

  /**
   * Creates the event.
   *
   * @param player the player
   * @param server the server lost
   * @param cause how it was lost
   * @param serverKickReason the server's reason, or {@code null} if it gave none
   * @param duringServerConnect whether the player was being moved to it
   * @param proposed the result the proxy proposes
   */
  public KickedFromServerEventImpl(
      Player player,
      RegisteredServer server,
      Cause cause,
      @Nullable Component serverKickReason,
      boolean duringServerConnect,
      Result proposed) {
    this.player = player;
    this.server = server;
    this.cause = cause;
    this.serverKickReason = serverKickReason;
    this.duringServerConnect = duringServerConnect;
    this.result = proposed;
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public RegisteredServer server() {
    return server;
  }

  @Override
  public Cause cause() {
    return cause;
  }

  @Override
  public Optional<Component> serverKickReason() {
    return Optional.ofNullable(serverKickReason);
  }

  @Override
  public boolean kickedDuringServerConnect() {
    return duringServerConnect;
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
