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
package dev.warp.api.server;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * A server list entry: what a client shows when it refreshes its server list.
 *
 * @param version the version name and protocol the server announces
 * @param players the player counts and sample, or {@code null} when the server hides them
 * @param description the message of the day
 * @param favicon the server icon as a {@code data:image/png;base64,} URI, or {@code null}
 */
public record ServerPing(
    Version version, @Nullable Players players, Component description, @Nullable String favicon) {

  /** Validates the entry. */
  public ServerPing {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(description, "description");
  }

  /**
   * Returns the player counts and sample.
   *
   * @return them, or empty when the server hides them
   */
  public Optional<Players> playerInfo() {
    return Optional.ofNullable(players);
  }

  /**
   * Returns the server icon.
   *
   * @return the {@code data:} URI, or empty if none
   */
  public Optional<String> faviconUri() {
    return Optional.ofNullable(favicon);
  }

  /**
   * The version a server announces.
   *
   * @param name the version name shown when the client's protocol differs, such as "1.21.4"
   * @param protocol the protocol version number
   */
  public record Version(String name, int protocol) {

    /** Validates the version. */
    public Version {
      Objects.requireNonNull(name, "name");
    }
  }

  /**
   * Player counts and the sample shown when hovering them.
   *
   * @param online how many players are online
   * @param max the maximum shown
   * @param sample a few players, unmodifiable
   */
  public record Players(int online, int max, List<SamplePlayer> sample) {

    /** Copies the sample. */
    public Players {
      sample = List.copyOf(sample);
    }
  }

  /**
   * A player listed in the sample.
   *
   * @param name the name shown
   * @param id the player's UUID
   */
  public record SamplePlayer(String name, UUID id) {

    /** Validates the sample entry. */
    public SamplePlayer {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(id, "id");
    }
  }
}
