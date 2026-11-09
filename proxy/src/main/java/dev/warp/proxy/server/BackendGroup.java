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
package dev.warp.proxy.server;

import dev.warp.api.player.Player;
import dev.warp.api.server.Balancer;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerGroup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/** A server group: its members, its balancer, and the registries that resolve and watch them. */
public final class BackendGroup implements ServerGroup {

  private final String name;
  private final List<Member> members;
  private final Balancer balancer;
  private final String strategy;
  private final ServerRegistry servers;
  private final ServerGroupRegistry groups;

  BackendGroup(
      String name,
      List<Member> members,
      Balancer balancer,
      String strategy,
      ServerRegistry servers,
      ServerGroupRegistry groups) {
    this.name = name;
    this.members = members;
    this.balancer = balancer;
    this.strategy = strategy;
    this.servers = servers;
    this.groups = groups;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<Member> members() {
    return members;
  }

  @Override
  public Collection<RegisteredServer> servers() {
    List<RegisteredServer> registered = new ArrayList<>(members.size());
    for (Member member : members) {
      BackendServer server = servers.getServer(member.server());
      if (server != null) {
        registered.add(server);
      }
    }
    return List.copyOf(registered);
  }

  @Override
  public Balancer balancer() {
    return balancer;
  }

  @Override
  public String strategy() {
    return strategy;
  }

  @Override
  public boolean isRegistered() {
    return groups.getGroup(name) == this;
  }

  @Override
  public Optional<RegisteredServer> choose(Player player) {
    Set<String> excluded =
        player.currentServer().map(c -> Set.of(c.server().name())).orElse(Set.of());
    return Optional.ofNullable(choose(excluded, true));
  }

  @Override
  public Optional<RegisteredServer> choose(Collection<String> excludedServers) {
    Set<String> excluded = new HashSet<>(excludedServers.size());
    for (String server : excludedServers) {
      excluded.add(server.toLowerCase(Locale.ROOT));
    }
    return Optional.ofNullable(choose(excluded, true));
  }

  /**
   * Picks the server a player is sent to.
   *
   * @param excluded names of the servers to skip, lowercase
   * @param healthyOnly whether to skip the servers whose breaker is open
   * @return the server, or {@code null} if none is left to pick from or the balancer declined
   */
  @Nullable BackendServer choose(Set<String> excluded, boolean healthyOnly) {
    ServerHealth health = servers.health();
    List<Balancer.Candidate> candidates = new ArrayList<>(members.size());
    for (Member member : members) {
      if (excluded.contains(member.server())) {
        continue;
      }
      BackendServer server = servers.getServer(member.server());
      if (server == null || (healthyOnly && !health.allows(member.server()))) {
        continue;
      }
      candidates.add(new ServerCandidate(server, member.weight(), health.latency(member.server())));
    }
    if (candidates.isEmpty()) {
      return null;
    }
    RegisteredServer chosen = balancer.choose(List.copyOf(candidates)).orElse(null);
    if (!(chosen instanceof BackendServer backend)) {
      return null;
    }
    health.picked(backend.name());
    return backend;
  }

  @Override
  public String toString() {
    return "BackendGroup[" + name + " " + strategy + " " + members + "]";
  }

  private record ServerCandidate(BackendServer server, int weight, Optional<Duration> latency)
      implements Balancer.Candidate {

    @Override
    public int connections() {
      return server.playerCount();
    }
  }
}
