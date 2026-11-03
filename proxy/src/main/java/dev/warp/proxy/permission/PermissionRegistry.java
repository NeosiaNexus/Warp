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
package dev.warp.proxy.permission;

import dev.warp.api.event.Subscription;
import dev.warp.api.permission.PermissionProvider;
import dev.warp.api.permission.Tristate;
import dev.warp.api.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The permission providers plugins registered, asked from the highest priority down. Checks read an
 * immutable snapshot: lock-free, and cheap enough for every node of every command list.
 */
public final class PermissionRegistry {

  private static final Logger logger = LoggerFactory.getLogger(PermissionRegistry.class);

  private static final Comparator<Entry> ORDER =
      Comparator.comparingInt(Entry::priority).reversed().thenComparingLong(Entry::order);

  private volatile List<Entry> providers = List.of();
  private final AtomicLong order = new AtomicLong();

  private record Entry(int priority, long order, String owner, PermissionProvider provider) {}

  /**
   * Registers a provider.
   *
   * @param owner the plugin that registers it, for logs
   * @param priority its priority
   * @param provider the provider
   * @return the registration
   */
  public synchronized Subscription register(
      String owner, int priority, PermissionProvider provider) {
    Entry entry = new Entry(priority, order.getAndIncrement(), owner, provider);
    List<Entry> next = new ArrayList<>(providers);
    next.add(entry);
    next.sort(ORDER);
    providers = List.copyOf(next);
    return new Subscription() {
      private volatile boolean active = true;

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public void unsubscribe() {
        if (active) {
          active = false;
          remove(entry);
        }
      }
    };
  }

  private synchronized void remove(Entry entry) {
    List<Entry> next = new ArrayList<>(providers);
    next.remove(entry);
    providers = List.copyOf(next);
  }

  /**
   * Checks a player's permission: the first provider with an opinion decides; without one, refused.
   * A provider that throws is logged and skipped.
   *
   * @param player the player
   * @param permission the permission node
   * @return whether it is granted
   */
  public boolean check(Player player, String permission) {
    for (Entry entry : providers) {
      Tristate answer;
      try {
        answer = entry.provider().check(player, permission);
      } catch (RuntimeException e) {
        logger.error(
            "Permission provider of {} failed checking {} for {}",
            entry.owner(),
            permission,
            player.username(),
            e);
        continue;
      }
      if (answer != Tristate.UNDEFINED) {
        return answer == Tristate.TRUE;
      }
    }
    return false;
  }
}
