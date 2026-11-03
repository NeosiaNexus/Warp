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
import dev.warp.proxy.logging.LogCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The permission providers plugins registered, asked from the highest priority down. Checks read an
 * immutable snapshot: lock-free, and cheap enough for every node of every command list.
 *
 * <p>When permissions change (a provider says so, or providers come and go), the players' command
 * lists are refreshed: {@link Player#refreshCommands()} debounces, so a burst costs one refresh.
 */
public final class PermissionRegistry {

  private static final Logger logger = LoggerFactory.getLogger(PermissionRegistry.class);

  private static final Comparator<Entry> ORDER =
      Comparator.comparingInt(Entry::priority).reversed().thenComparingLong(Entry::order);

  private volatile List<Entry> providers = List.of();
  private final AtomicLong order = new AtomicLong();
  private volatile Supplier<? extends Collection<? extends Player>> online = List::of;

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
    allChanged();
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
    allChanged();
  }

  // ---------------------------------------------------------------------------
  // Changes
  // ---------------------------------------------------------------------------

  /**
   * Sets where the online players are, whose command lists {@link #allChanged()} refreshes.
   *
   * @param online the online players, asked at each change
   */
  public void trackPlayers(Supplier<? extends Collection<? extends Player>> online) {
    this.online = online;
  }

  /**
   * Refreshes what depends on a player's permissions: its command list.
   *
   * @param player the player whose permissions changed
   */
  public void changed(Player player) {
    player.refreshCommands();
  }

  /** Refreshes what depends on the permissions of every online player. */
  public void allChanged() {
    for (Player player : online.get()) {
      player.refreshCommands();
    }
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
    return value(player, permission) == Tristate.TRUE;
  }

  /**
   * Returns the first opinion the providers have on a player's permission. A provider that throws
   * is logged and skipped.
   *
   * @param player the player
   * @param permission the permission node
   * @return the answer, {@link Tristate#UNDEFINED} if no provider has one
   */
  public Tristate value(Player player, String permission) {
    for (Entry entry : providers) {
      Tristate answer;
      try {
        answer = entry.provider().check(player, permission);
      } catch (RuntimeException e) {
        logger.error(
            LogCode.PERMISSION_PROVIDER_FAILED.marker(),
            "Permission provider of {} failed checking {} for {}",
            entry.owner(),
            permission,
            player.username(),
            e);
        continue;
      }
      if (answer != Tristate.UNDEFINED) {
        return answer;
      }
    }
    return Tristate.UNDEFINED;
  }
}
