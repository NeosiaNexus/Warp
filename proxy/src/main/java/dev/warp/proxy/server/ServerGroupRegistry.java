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

import dev.warp.api.server.Balancer;
import dev.warp.api.server.ServerGroup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/** The registry of server groups, backed by a {@link ServerRegistry} for the member servers. */
public final class ServerGroupRegistry implements dev.warp.api.server.ServerGroupRegistry {

  private final Map<String, BackendGroup> groups = new ConcurrentHashMap<>();
  private final ServerRegistry servers;

  ServerGroupRegistry(ServerRegistry servers) {
    this.servers = servers;
  }

  @Override
  public Optional<ServerGroup> group(String name) {
    return Optional.ofNullable(getGroup(name));
  }

  @Override
  public Collection<ServerGroup> groups() {
    return List.copyOf(allGroups());
  }

  /**
   * Returns the group registered under a name.
   *
   * @param name the name, in any case
   * @return the group, or {@code null} if none is registered under that name
   */
  public @Nullable BackendGroup getGroup(String name) {
    return groups.get(normalize(name));
  }

  /**
   * Returns a snapshot of every registered group.
   *
   * @return the groups, unmodifiable, sorted by name
   */
  public List<BackendGroup> allGroups() {
    List<BackendGroup> snapshot = new ArrayList<>(groups.values());
    snapshot.sort((a, b) -> a.name().compareTo(b.name()));
    return List.copyOf(snapshot);
  }

  @Override
  public ServerGroup register(String name, List<ServerGroup.Member> members, Balancer balancer) {
    return register(name, members, balancer, balancer.getClass().getSimpleName());
  }

  /**
   * Registers a group, naming its strategy for display.
   *
   * @param name the name, stored in lowercase
   * @param members the members, at least one, with distinct servers
   * @param balancer the balancer
   * @param strategy the strategy's name, shown by the admin commands
   * @return the group now registered
   * @throws IllegalArgumentException as {@link #register(String, List, Balancer)}
   */
  public BackendGroup register(
      String name, List<ServerGroup.Member> members, Balancer balancer, String strategy) {
    String key = normalize(name);
    if (key.isEmpty()) {
      throw new IllegalArgumentException("A group needs a name");
    }
    if (members.isEmpty()) {
      throw new IllegalArgumentException("Group '" + key + "' has no server");
    }
    if (servers.getServer(key) != null) {
      throw new IllegalArgumentException("'" + key + "' is the name of a registered server");
    }
    List<ServerGroup.Member> normalized = new ArrayList<>(members.size());
    Set<String> seen = new HashSet<>();
    for (ServerGroup.Member member : members) {
      String server = normalize(member.server());
      if (!seen.add(server)) {
        throw new IllegalArgumentException("Group '" + key + "' lists '" + server + "' twice");
      }
      normalized.add(new ServerGroup.Member(server, member.weight()));
    }
    BackendGroup group =
        new BackendGroup(key, List.copyOf(normalized), balancer, strategy, servers, this);
    groups.put(key, group);
    return group;
  }

  @Override
  public Optional<ServerGroup> unregister(String name) {
    return Optional.ofNullable(groups.remove(normalize(name)));
  }

  @Override
  public boolean unregister(ServerGroup group) {
    return group instanceof BackendGroup backend && groups.remove(backend.name(), backend);
  }

  @Override
  public Optional<Balancer> balancer(String strategy) {
    return Optional.ofNullable(Balancers.of(strategy));
  }

  @Override
  public Balancer fill(int capacity) {
    return Balancers.fill(capacity);
  }

  @Override
  public Set<String> strategies() {
    return Set.copyOf(Balancers.NAMES);
  }

  private static String normalize(String name) {
    return name.trim().toLowerCase(Locale.ROOT);
  }
}
