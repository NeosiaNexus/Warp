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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The server groups players are balanced over: those of {@code warp.conf} and those plugins
 * register. Groups follow the lifecycle of servers: a group a plugin registers stays until it is
 * unregistered, so a plugin that owns one unregisters it when it is disabled.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ServerGroupRegistry {

  /**
   * Returns a group by name.
   *
   * @param name the name, in any case
   * @return the group, or empty if none is registered under that name
   */
  Optional<ServerGroup> group(String name);

  /**
   * Returns a snapshot of every registered group, sorted by name.
   *
   * @return the groups, unmodifiable
   */
  Collection<ServerGroup> groups();

  /**
   * Registers a group, replacing the one registered under its name if any. Members need not be
   * registered servers yet: a member is used from the moment its server is registered.
   *
   * @param name the name, stored in lowercase; must not be the name of a registered server
   * @param members the members, at least one, with distinct servers
   * @param balancer the balancer picking among them; a fresh instance per group when it keeps state
   * @return the group now registered under the name
   * @throws IllegalArgumentException if {@code members} is empty or names a server twice, or if a
   *     server is registered under {@code name}
   */
  ServerGroup register(String name, List<ServerGroup.Member> members, Balancer balancer);

  /**
   * Unregisters a group by name. Players on its servers are not moved; the name no longer resolves
   * for new players, routes and fallbacks.
   *
   * @param name the name, in any case
   * @return the group that was registered, or empty if none was
   */
  Optional<ServerGroup> unregister(String name);

  /**
   * Unregisters a group only if it is still registered as this instance, so that a stale call
   * cannot remove a group registered again since.
   *
   * @param group the group
   * @return {@code true} if it was unregistered
   */
  boolean unregister(ServerGroup group);

  /**
   * Returns a fresh balancer for a built-in strategy, ready to be given to {@link #register}.
   *
   * @param strategy the strategy's name as written in {@code warp.conf}: {@code round-robin},
   *     {@code least-connections}, {@code lowest-latency}, {@code random} or {@code weighted}, in
   *     any case; {@code fill} needs a capacity, see {@link #fill(int)}
   * @return a new balancer, or empty if the name is not a built-in strategy
   */
  Optional<Balancer> balancer(String strategy);

  /**
   * Returns a fresh {@code fill} balancer: players fill the first server of the group up to {@code
   * capacity} players, then the next, and so on; past the last one, the least filled server.
   *
   * @param capacity the players a server takes before the next one is filled, at least 1
   * @return a new balancer
   * @throws IllegalArgumentException if {@code capacity} is less than 1
   */
  Balancer fill(int capacity);

  /**
   * Returns the names of the built-in strategies, as {@link #balancer(String)} and {@code
   * warp.conf} take them.
   *
   * @return the names, unmodifiable
   */
  Set<String> strategies();
}
