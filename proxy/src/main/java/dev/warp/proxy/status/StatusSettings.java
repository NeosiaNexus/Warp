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
package dev.warp.proxy.status;

import dev.warp.api.server.Favicon;
import dev.warp.protocol.ProtocolVersion;

import java.util.Objects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jspecify.annotations.Nullable;

/**
 * The {@code status} block of {@code warp.conf}: the proxy's server list entry, and its player
 * limit.
 *
 * @param motd the message of the day
 * @param favicon the server icon, or {@code null} for none
 * @param maxPlayers the most players the proxy lets in, also shown as the maximum; {@code -1} for
 *     no limit, the server list then showing one more than the players online
 * @param showPlayerCount whether the server list shows the counts; without them clients show {@code
 *     ???}
 * @param sampleSize the most player names listed when hovering the counts; {@code 0} for none
 * @param versionName the version name shown, in red, to clients whose version the proxy does not
 *     support
 */
public record StatusSettings(
    Component motd,
    @Nullable Favicon favicon,
    int maxPlayers,
    boolean showPlayerCount,
    int sampleSize,
    String versionName) {

  /** The default message of the day, in MiniMessage. */
  public static final String DEFAULT_MOTD = "<#38bdf8>A Warp Proxy";

  /**
   * The default player limit: Velocity's default {@code show-max-players}, so that a network moving
   * from Velocity shows the same entry, now enforced.
   */
  public static final int DEFAULT_MAX_PLAYERS = 500;

  /** The default sample size: vanilla's (12 random players, refreshed every 5 seconds). */
  public static final int DEFAULT_SAMPLE_SIZE = 12;

  /** The value of {@link #maxPlayers()} that disables the limit. */
  public static final int NO_LIMIT = -1;

  /** Validates the settings. */
  public StatusSettings {
    Objects.requireNonNull(motd, "motd");
    Objects.requireNonNull(versionName, "versionName");
    if (maxPlayers < NO_LIMIT) {
      throw new IllegalArgumentException("maxPlayers must be -1 or more, got " + maxPlayers);
    }
    if (sampleSize < 0) {
      throw new IllegalArgumentException("sampleSize must be 0 or more, got " + sampleSize);
    }
  }

  /**
   * Returns the settings of a {@code warp.conf} without {@code status} block.
   *
   * @return the default settings
   */
  public static StatusSettings defaults() {
    return new StatusSettings(
        MiniMessage.miniMessage().deserialize(DEFAULT_MOTD),
        null,
        DEFAULT_MAX_PLAYERS,
        true,
        DEFAULT_SAMPLE_SIZE,
        defaultVersionName());
  }

  /**
   * Returns the default version name: the range of versions the proxy supports.
   *
   * @return a name such as "Warp 1.7.2-26.3"
   */
  public static String defaultVersionName() {
    return "Warp " + ProtocolVersion.oldest().name() + "-" + ProtocolVersion.latest().name();
  }

  /**
   * Returns whether a player is beyond the limit.
   *
   * @param place how many players are registered with it, counting it
   * @return {@code true} if the limit is on and {@code place} exceeds it
   */
  public boolean isFull(int place) {
    return maxPlayers != NO_LIMIT && place > maxPlayers;
  }
}
