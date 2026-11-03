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
 * <p>Immutable: change one part with the {@code with} methods, which keep the others, such as
 * {@code ping.withDescription(motd)}.
 *
 * @param version the version name and protocol the server announces
 * @param players the player counts and sample, or {@code null} when the server hides them (clients
 *     show {@code ???})
 * @param description the message of the day; clients show two lines of it
 * @param favicon the server icon, or {@code null} for none
 */
public record ServerPing(
    Version version, @Nullable Players players, Component description, @Nullable Favicon favicon) {

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
   * @return the icon, or empty if none
   */
  public Optional<Favicon> icon() {
    return Optional.ofNullable(favicon);
  }

  /**
   * Returns this entry with another version.
   *
   * @param version the version
   * @return the new entry
   */
  public ServerPing withVersion(Version version) {
    return new ServerPing(version, players, description, favicon);
  }

  /**
   * Returns this entry with other player counts.
   *
   * @param players the counts and sample, or {@code null} to hide them
   * @return the new entry
   */
  public ServerPing withPlayers(@Nullable Players players) {
    return new ServerPing(version, players, description, favicon);
  }

  /**
   * Returns this entry with another message of the day.
   *
   * @param description the message of the day
   * @return the new entry
   */
  public ServerPing withDescription(Component description) {
    return new ServerPing(version, players, description, favicon);
  }

  /**
   * Returns this entry with another icon.
   *
   * @param favicon the icon, or {@code null} for none
   * @return the new entry
   */
  public ServerPing withFavicon(@Nullable Favicon favicon) {
    return new ServerPing(version, players, description, favicon);
  }

  /**
   * The version a server announces. A client compares the protocol with its own: when they differ,
   * it lists the server as incompatible and shows the name, in red, in place of the player count.
   *
   * @param name the version name, such as "1.21.4"
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
   * @param sample a few players, unmodifiable; clients show each name on a line of its own
   */
  public record Players(int online, int max, List<SamplePlayer> sample) {

    /** Copies the sample. */
    public Players {
      sample = List.copyOf(sample);
    }

    /**
     * Returns these counts with another online count.
     *
     * @param online how many players are online
     * @return the new counts
     */
    public Players withOnline(int online) {
      return new Players(online, max, sample);
    }

    /**
     * Returns these counts with another maximum.
     *
     * @param max the maximum shown
     * @return the new counts
     */
    public Players withMax(int max) {
      return new Players(online, max, sample);
    }

    /**
     * Returns these counts with another sample.
     *
     * @param sample the players shown when hovering the counts
     * @return the new counts
     */
    public Players withSample(List<SamplePlayer> sample) {
      return new Players(online, max, sample);
    }
  }

  /**
   * A player listed in the sample. The sample can also carry lines of text: give each a nil UUID
   * ({@code new UUID(0, 0)}), as vanilla servers do for {@link #ANONYMOUS} players.
   *
   * @param name the name shown
   * @param id the player's UUID
   */
  public record SamplePlayer(String name, UUID id) {

    /**
     * How a vanilla server lists a player who turned off "Allow Server Listings" in the client's
     * settings (1.18+): the proxy lists such players the same way.
     */
    public static final SamplePlayer ANONYMOUS =
        new SamplePlayer("Anonymous Player", new UUID(0, 0));

    /** Validates the sample entry. */
    public SamplePlayer {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(id, "id");
    }
  }
}
