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

import dev.warp.api.player.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBarImplementation;
import net.kyori.adventure.bossbar.BossBarViewer;

/**
 * Warp's side of an Adventure boss bar: {@link BossBar#viewers()} lists the players the proxy shows
 * it to.
 *
 * <p>Adventure creates one of these per bar through {@link Provider}, found with {@link
 * java.util.ServiceLoader}, the first time {@link #attach} asks for it, and keeps it in the bar. It
 * holds nothing itself: the viewers live in one map of the proxy, written by {@link ProxyBossBars}
 * on each player's event loop as it shows and hides the bar, and emptied of a player when the
 * player is gone. A bar is in the map only while someone views it, so a bar a plugin drops is never
 * held, and two implementations of one bar (Adventure creates it lazily, unsynchronized) list the
 * same viewers. Safe from any thread.
 */
public final class WarpBossBarImplementation implements BossBarImplementation {

  /** The players each bar is shown to; Adventure bars are compared by identity. */
  private static final Map<BossBar, Set<Player>> VIEWERS = new ConcurrentHashMap<>();

  private final BossBar bar;

  private WarpBossBarImplementation(BossBar bar) {
    this.bar = bar;
  }

  /**
   * Returns the players the bar is shown to, as an unmodifiable snapshot.
   *
   * @return the players, in no particular order
   */
  @Override
  public Iterable<? extends BossBarViewer> viewers() {
    return viewersOf(bar);
  }

  /**
   * Makes Adventure create the implementation of a bar, without which {@link BossBar#viewers()}
   * lists nobody. Does nothing for a bar that is not Adventure's own.
   *
   * @param bar the bar
   */
  static void attach(BossBar bar) {
    try {
      var _ = BossBarImplementation.get(bar, WarpBossBarImplementation.class);
    } catch (ClassCastException notAdventures) {
      // A BossBar implemented elsewhere (a test double): its viewers() is its own.
    }
  }

  /**
   * Records that a player is shown a bar.
   *
   * @param bar the bar
   * @param player the player
   */
  static void shown(BossBar bar, Player player) {
    // Inside compute, as hidden: a set emptied and dropped by another player's hide is never added
    // to after it left the map.
    VIEWERS.compute(
        bar,
        (_, players) -> {
          Set<Player> viewers = players == null ? ConcurrentHashMap.newKeySet() : players;
          viewers.add(player);
          return viewers;
        });
  }

  /**
   * Records that a player is no longer shown a bar; the bar leaves the map with its last viewer.
   *
   * @param bar the bar
   * @param player the player
   */
  static void hidden(BossBar bar, Player player) {
    VIEWERS.computeIfPresent(
        bar,
        (_, players) -> {
          players.remove(player);
          return players.isEmpty() ? null : players;
        });
  }

  /**
   * Returns the players a bar is shown to.
   *
   * @param bar the bar
   * @return an unmodifiable snapshot
   */
  static List<Player> viewersOf(BossBar bar) {
    Set<Player> players = VIEWERS.get(bar);
    return players == null ? List.of() : List.copyOf(players);
  }

  /**
   * Returns whether the proxy holds a bar, for tests: only while someone views it.
   *
   * @param bar the bar
   * @return {@code true} if the bar is in the map
   */
  static boolean held(BossBar bar) {
    return VIEWERS.containsKey(bar);
  }

  /** Creates the implementation of each bar; named in {@code META-INF/services}. */
  public static final class Provider implements BossBarImplementation.Provider {

    /** Creates the provider: called by {@link java.util.ServiceLoader}. */
    public Provider() {}

    @Override
    public BossBarImplementation create(BossBar bar) {
      return new WarpBossBarImplementation(bar);
    }
  }
}
