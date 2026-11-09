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

import dev.warp.api.player.Player;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A named set of servers that a {@link Balancer} spreads players over: the {@code groups} block of
 * {@code warp.conf}, or a group a plugin registered.
 *
 * <p>A group stands wherever a server name does: as the default server, in the fallback order, or
 * as the target of a route. Its name shares the namespace of server names, and a server registered
 * under the same name wins. Groups are flat: a member is always a server, never another group.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ServerGroup {

  /**
   * Returns the group's name, in lowercase.
   *
   * @return the name
   */
  String name();

  /**
   * Returns the group's members as configured, registered or not, in the configured order.
   *
   * @return the members, unmodifiable
   */
  List<Member> members();

  /**
   * Returns the member servers registered right now, in the configured order. A member whose server
   * is not registered (unregistered since, or never registered yet) is left out.
   *
   * @return the servers, unmodifiable
   */
  Collection<RegisteredServer> servers();

  /**
   * Returns the balancer that picks among the servers.
   *
   * @return the balancer
   */
  Balancer balancer();

  /**
   * Returns the name of the balancing strategy, for display: a built-in name such as {@code
   * least-connections}, or the simple class name of a plugin's balancer.
   *
   * @return the strategy
   */
  String strategy();

  /**
   * Returns whether this group is still registered under its name.
   *
   * @return {@code true} while registered
   */
  boolean isRegistered();

  /**
   * Picks the server a player would be sent to, as the proxy does: skipping the server the player
   * plays on and the servers whose circuit breaker is open, then asking the balancer.
   *
   * @param player the player, whose current server is never picked
   * @return the server, or empty if no member can take the player right now
   */
  Optional<RegisteredServer> choose(Player player);

  /**
   * Picks a server for a player who must not land on some servers.
   *
   * @param excludedServers names of the servers to skip, in any case
   * @return the server, or empty if no other member can take a player right now
   */
  Optional<RegisteredServer> choose(Collection<String> excludedServers);

  /**
   * A member of a group.
   *
   * @param server the server's name, in lowercase
   * @param weight its share under the weighted strategy, at least 1; other strategies ignore it
   */
  record Member(String server, int weight) {

    /**
     * Validates the member.
     *
     * @param server the server's name
     * @param weight its weight, at least 1
     */
    public Member {
      Objects.requireNonNull(server, "server");
      if (weight < 1) {
        throw new IllegalArgumentException("A weight is at least 1, got " + weight);
      }
    }

    /**
     * Returns a member with the default weight of 1.
     *
     * @param server the server's name, in any case
     * @return the member
     */
    public static Member of(String server) {
      return new Member(server, 1);
    }
  }
}
