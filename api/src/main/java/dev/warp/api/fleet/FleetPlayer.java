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
package dev.warp.api.fleet;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A player somewhere on the network: on this proxy or on another one.
 *
 * @param uuid the player's UUID
 * @param name the player's name
 * @param proxyId the id of the proxy it is connected to
 * @param server the backend it plays on, or {@code null} while it is between servers
 * @param since when it logged in to its proxy, on that proxy's clock
 */
public record FleetPlayer(
    UUID uuid, String name, String proxyId, @Nullable String server, Instant since) {

  /**
   * Returns the backend the player is on.
   *
   * @return the server's name, or empty while the player is between servers
   */
  public Optional<String> serverIfAny() {
    return Optional.ofNullable(server);
  }
}
