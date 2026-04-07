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
package dev.warp.proxy.connection;

import dev.warp.protocol.packet.login.LoginSuccess;

import java.util.List;
import java.util.UUID;

/**
 * Represents an authenticated player's identity.
 *
 * <p>Constructed after successful Mojang authentication (online mode) or generated from the
 * username (offline mode). Reuses {@link LoginSuccess.Property} for profile properties (skin, cape)
 * to avoid duplication.
 *
 * @param uuid the player's UUID
 * @param name the player's username
 * @param properties game profile properties (skin textures, cape), empty for offline mode
 */
public record GameProfile(UUID uuid, String name, List<LoginSuccess.Property> properties) {

  /** Ensures the properties list is immutable. */
  public GameProfile {
    properties = List.copyOf(properties);
  }
}
