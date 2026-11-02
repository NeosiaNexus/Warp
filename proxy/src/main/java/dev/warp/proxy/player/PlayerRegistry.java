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
package dev.warp.proxy.player;

import dev.warp.proxy.connection.ConnectedPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The players connected to the proxy, by UUID and by name.
 *
 * <p>A player is registered once it is authenticated, before the proxy tells the client its login
 * succeeded, and unregistered as soon as it disconnects. At most one player holds a UUID: {@link
 * #register} settles a second login under the same UUID atomically, so two logins racing for the
 * same account always end with exactly one of them registered.
 *
 * <p>Lookups are lock-free and never block; snapshots are copies, safe to iterate from any thread.
 */
public final class PlayerRegistry {

  private final Map<UUID, ConnectedPlayer> byId = new ConcurrentHashMap<>();
  private final Map<String, ConnectedPlayer> byName = new ConcurrentHashMap<>();

  /** Outcome of {@link #register}. */
  public enum Registration {
    /** The player is registered. */
    REGISTERED,
    /** A session with the same UUID is connected and the policy keeps it: the newcomer is out. */
    REFUSED
  }

  /**
   * Registers a player. If another session holds its UUID, either that session is disconnected
   * (with {@code kickExisting}) and the newcomer takes its place, or the newcomer is refused.
   *
   * @param player the newly authenticated player
   * @param kickExisting whether an existing session with the same UUID is kicked
   * @return whether the player is now registered
   */
  public Registration register(ConnectedPlayer player, boolean kickExisting) {
    while (true) {
      ConnectedPlayer existing = byId.putIfAbsent(player.uuid(), player);
      if (existing == null || existing == player) {
        byName.put(key(player.username()), player);
        return Registration.REGISTERED;
      }
      if (!kickExisting) {
        return Registration.REFUSED;
      }
      existing.disconnectDuplicate();
      // disconnectDuplicate unregisters it; removing here too covers a session that was already
      // disconnecting, so that the loop always makes progress.
      unregister(existing);
    }
  }

  /**
   * Unregisters a player, if it is still the one registered under its UUID and name.
   *
   * @param player the player that left
   */
  public void unregister(ConnectedPlayer player) {
    byId.remove(player.uuid(), player);
    byName.remove(key(player.username()), player);
  }

  /**
   * Returns the player connected with a UUID.
   *
   * @param uuid the player's UUID
   * @return the player, or {@code null} if none is connected with it
   */
  public @Nullable ConnectedPlayer get(UUID uuid) {
    return byId.get(uuid);
  }

  /**
   * Returns the player connected with a name, ignoring case.
   *
   * @param username the player's name
   * @return the player, or {@code null} if none is connected with it
   */
  public @Nullable ConnectedPlayer get(String username) {
    return byName.get(key(username));
  }

  /**
   * Returns a snapshot of the connected players.
   *
   * @return the players, unmodifiable
   */
  public List<ConnectedPlayer> all() {
    return List.copyOf(byId.values());
  }

  /**
   * Returns how many players are connected, without copying them.
   *
   * @return the player count
   */
  public int count() {
    return byId.size();
  }

  private static String key(String username) {
    return username.toLowerCase(Locale.ROOT);
  }
}
