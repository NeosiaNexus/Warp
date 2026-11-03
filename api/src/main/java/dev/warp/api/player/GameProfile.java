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
package dev.warp.api.player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A player's Mojang profile: UUID, name and properties (the {@code textures} property carries the
 * skin and cape).
 *
 * @param id the player's UUID
 * @param name the player's name
 * @param properties the profile properties, unmodifiable
 */
public record GameProfile(UUID id, String name, List<Property> properties) {

  /**
   * Validates the profile and copies its properties.
   *
   * @throws IllegalArgumentException if the name is blank
   */
  public GameProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("A profile name cannot be blank");
    }
    properties = List.copyOf(properties);
  }

  /**
   * Returns a copy of this profile with other properties, for example another skin.
   *
   * @param properties the new properties
   * @return the new profile
   */
  public GameProfile withProperties(List<Property> properties) {
    return new GameProfile(id, name, properties);
  }

  /**
   * A profile property, signed by Mojang when it comes from Mojang.
   *
   * @param name the property's name, such as {@code textures}
   * @param value the property's value, Base64 for textures
   * @param signature Mojang's signature of the value, or {@code null} if unsigned
   */
  public record Property(String name, String value, @Nullable String signature) {

    /** Validates the property. */
    public Property {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
    }
  }
}
