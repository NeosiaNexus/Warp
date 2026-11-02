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
package dev.warp.api.event.player;

import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.util.Optional;
import java.util.UUID;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackStatus;
import org.jspecify.annotations.Nullable;

/**
 * Fired when a player's client reports what became of a resource pack: one the proxy sent ({@link
 * Player#sendResourcePacks}), whose answers the server never sees, or one a server sent, whose
 * answers go on to that server. Fired for every answer, in the order the client sent them: an
 * intermediate status ({@link ResourcePackStatus#intermediate()}) is followed by others.
 *
 * <p>A 1.7 client reports nothing. Before 1.20.3 a client answers without naming the pack: the
 * answer is for the last pack sent to it, and a server's pack is known by a UUID derived from its
 * URL. A pack the client already holds is not sent again (by the proxy, or when a server sends a
 * pack the client loaded on another server): the proxy reports it loaded itself, and fires this
 * event for it too.
 *
 * @param player the player
 * @param packId the pack's UUID
 * @param status what became of the pack
 * @param pack the pack, or {@code null} if the client answered for a pack the proxy did not see
 *     sent
 * @param server the server that sent the pack, or {@code null} if the proxy did
 */
public record PlayerResourcePackStatusEvent(
    Player player,
    UUID packId,
    ResourcePackStatus status,
    @Nullable ResourcePackInfo pack,
    @Nullable RegisteredServer server) {

  /**
   * Returns the pack.
   *
   * @return it, or empty if the client answered for a pack the proxy did not see sent
   */
  public Optional<ResourcePackInfo> packInfo() {
    return Optional.ofNullable(pack);
  }

  /**
   * Returns the server that sent the pack.
   *
   * @return it, or empty if the proxy sent it
   */
  public Optional<RegisteredServer> sendingServer() {
    return Optional.ofNullable(server);
  }

  /**
   * Returns whether the proxy sent the pack, through {@link Player#sendResourcePacks}.
   *
   * @return {@code true} for the proxy's packs
   */
  public boolean sentByProxy() {
    return server == null;
  }
}
