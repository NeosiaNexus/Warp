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
package dev.warp.proxy.drain;

import dev.warp.api.player.TransferResult;
import dev.warp.proxy.connection.ConnectedPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;

/**
 * A connected player, as the drain deals with it.
 *
 * @param player the player
 */
record PlayerCandidate(ConnectedPlayer player) implements DrainCandidate {

  @Override
  public UUID sessionId() {
    return player.sessionId();
  }

  @Override
  public String username() {
    return player.username();
  }

  @Override
  public boolean isActive() {
    return player.api().isActive();
  }

  @Override
  public boolean supportsTransfers() {
    return player.supportsTransfers();
  }

  @Override
  public CompletableFuture<TransferResult> transfer(String host, int port) {
    return player.requestTransfer(host, port);
  }

  @Override
  public void kick(Component reason) {
    player.api().disconnect(reason);
  }
}
