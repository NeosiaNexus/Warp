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
package dev.warp.proxy.config;

import dev.warp.proxy.server.Balancers;
import dev.warp.proxy.server.HostRoutes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * Reads the {@code groups} and {@code routes} blocks of {@code warp.conf}.
 *
 * <p>Names are checked against the servers block when it has static servers. With an empty block
 * ({@code servers {}}), servers come from plugins at runtime, and so may the names: a group's
 * members and a route's target are then resolved when a player needs them.
 */
final class RoutingConfig {

  private RoutingConfig() {}

  /**
   * Reads the {@code groups} block.
   *
   * @param root the configuration
   * @param servers the static servers, by lowercase name
   * @return the groups, by lowercase name, in the order of the file
   * @throws ConfigurationException if a group is malformed, named like a server, lists a server
   *     that is not configured (with static servers), or has a strategy or settings that do not go
   *     together
   */
  static Map<String, GroupConfig> parseGroups(
      CommentedConfigurationNode root, Map<String, ?> servers) throws ConfigurationException {
    CommentedConfigurationNode node = root.node("groups");
    Map<String, GroupConfig> groups = new LinkedHashMap<>();
    if (node.virtual() || node.empty()) {
      return groups;
    }
    if (!node.isMap()) {
      throw ConfigurationException.invalid(
          "groups",
          "a block of named groups, such as groups { lobbies { servers = [\"lobby1\", \"lobby2\"]"
              + " } }",
          WarpConfig.describe(node));
    }
    boolean checkNames = !servers.isEmpty();
    for (var entry : node.childrenMap().entrySet()) {
      String written = entry.getKey().toString();
      String name = written.toLowerCase(Locale.ROOT);
      String key = "groups." + written;
      if (servers.containsKey(name)) {
        throw ConfigurationException.at(
            key,
            "is also the name of a server: a group and a server cannot share a name, rename one");
      }
      if (groups.containsKey(name)) {
        throw ConfigurationException.at(key, "is listed twice (names are case-insensitive)");
      }
      groups.put(name, parseGroup(entry.getValue(), name, key, servers, checkNames));
    }
    return groups;
  }

  private static GroupConfig parseGroup(
      CommentedConfigurationNode node,
      String name,
      String key,
      Map<String, ?> servers,
      boolean checkNames)
      throws ConfigurationException {
    if (!node.isMap()) {
      throw ConfigurationException.invalid(
          key,
          "a block with its servers and strategy, such as { servers = [\"lobby1\", \"lobby2\"],"
              + " strategy = \"least-connections\" }",
          WarpConfig.describe(node));
    }
    CommentedConfigurationNode serversNode = node.node("servers");
    if (serversNode.virtual()) {
      throw ConfigurationException.at(
          key + ".servers",
          "missing: each group lists its servers, such as [\"lobby1\", \"lobby2\"]");
    }
    if (!serversNode.isList() || serversNode.childrenList().isEmpty()) {
      throw ConfigurationException.invalid(
          key + ".servers",
          "a list of at least one server name, such as [\"lobby1\", \"lobby2\"]",
          WarpConfig.describe(serversNode));
    }
    Set<String> members = new LinkedHashSet<>();
    for (var child : serversNode.childrenList()) {
      String server = WarpConfig.stringValue(child, "").trim();
      String member = server.toLowerCase(Locale.ROOT);
      if (member.isEmpty()) {
        throw ConfigurationException.invalid(key + ".servers", "server names", "an empty name");
      }
      if (checkNames && !servers.containsKey(member)) {
        throw unknown(key + ".servers", server, servers.keySet(), "servers block");
      }
      if (!members.add(member)) {
        throw ConfigurationException.at(key + ".servers", "lists '" + member + "' twice");
      }
    }

    String strategy =
        WarpConfig.stringValue(node.node("strategy"), Balancers.LEAST_CONNECTIONS)
            .trim()
            .toLowerCase(Locale.ROOT);
    if (!Balancers.isStrategy(strategy)) {
      throw unknown(key + ".strategy", strategy, Balancers.NAMES, "strategies");
    }

    CommentedConfigurationNode weightsNode = node.node("weights");
    Map<String, Integer> weights = new LinkedHashMap<>();
    if (!weightsNode.virtual()) {
      if (!strategy.equals(Balancers.WEIGHTED)) {
        throw ConfigurationException.at(
            key + ".weights",
            "only the weighted strategy uses weights, and this group's strategy is "
                + strategy
                + ": set strategy = \"weighted\", or remove the weights");
      }
      if (!weightsNode.isMap()) {
        throw ConfigurationException.invalid(
            key + ".weights",
            "a block of server weights, such as { lobby1 = 3, lobby2 = 1 }",
            WarpConfig.describe(weightsNode));
      }
      for (var weight : weightsNode.childrenMap().entrySet()) {
        String server = weight.getKey().toString();
        String member = server.toLowerCase(Locale.ROOT);
        String weightKey = key + ".weights." + server;
        if (!members.contains(member)) {
          throw unknown(weightKey, server, members, "group's servers");
        }
        int value = WarpConfig.intValue(weight.getValue(), 1);
        if (value < 1) {
          throw ConfigurationException.invalid(weightKey, "a weight of 1 or more", value);
        }
        weights.put(member, value);
      }
    }

    CommentedConfigurationNode capacityNode = node.node("capacity");
    int capacity = 0;
    if (strategy.equals(Balancers.FILL)) {
      if (capacityNode.virtual()) {
        throw ConfigurationException.at(
            key + ".capacity",
            "missing: the fill strategy fills each server up to a capacity, such as capacity ="
                + " 100");
      }
      capacity = WarpConfig.intValue(capacityNode, 0);
      if (capacity < 1) {
        throw ConfigurationException.invalid(
            key + ".capacity", "a capacity of 1 or more", capacity);
      }
    } else if (!capacityNode.virtual()) {
      throw ConfigurationException.at(
          key + ".capacity",
          "only the fill strategy uses a capacity, and this group's strategy is "
              + strategy
              + ": set strategy = \"fill\", or remove the capacity");
    }
    return new GroupConfig(name, List.copyOf(members), weights, strategy, capacity);
  }

  /**
   * Reads the {@code routes} block.
   *
   * @param root the configuration
   * @param servers the static servers, by lowercase name
   * @param groups the groups, by lowercase name
   * @return the routes, normalized key to lowercase target, in the order of the file
   * @throws ConfigurationException if a key is not a host, two keys are the same host, a target
   *     captures without a wildcard, or a target is neither a server nor a group (with static
   *     servers)
   */
  static Map<String, String> parseRoutes(
      CommentedConfigurationNode root, Map<String, ?> servers, Map<String, ?> groups)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("routes");
    Map<String, String> routes = new LinkedHashMap<>();
    if (node.virtual() || node.empty()) {
      return routes;
    }
    if (!node.isMap()) {
      throw ConfigurationException.invalid(
          "routes",
          "a block of hosts and their servers, such as routes { \"play.example.com\" = \"lobby\""
              + " }",
          WarpConfig.describe(node));
    }
    boolean checkNames = !servers.isEmpty();
    List<String> targets = new ArrayList<>(servers.keySet());
    targets.addAll(groups.keySet());
    for (var entry : node.childrenMap().entrySet()) {
      String written = entry.getKey().toString();
      String key = "routes.\"" + written + "\"";
      HostRoutes.Key host;
      try {
        host = HostRoutes.parseKey(written);
      } catch (IllegalArgumentException e) {
        throw ConfigurationException.at(
            key, e.getMessage() + ": a host such as play.example.com or *.example.com");
      }
      String target = WarpConfig.stringValue(entry.getValue(), "").trim().toLowerCase(Locale.ROOT);
      if (target.isEmpty()) {
        throw ConfigurationException.invalid(
            key, "the name of a server or group, or $1 after a wildcard", "an empty value");
      }
      if (target.contains(HostRoutes.CAPTURE)) {
        if (!host.wildcard()) {
          throw ConfigurationException.at(
              key,
              "sends players to '"
                  + target
                  + "', but $1 only stands for the labels a * matched: use a wildcard host such as"
                  + " *.example.com, or name the server");
        }
      } else if (checkNames && !targets.contains(target)) {
        throw unknown(key, target, targets, "servers and groups");
      }
      String normalized = host.pattern() + (host.port() < 0 ? "" : ":" + host.port());
      String previous = routes.put(normalized, target);
      if (previous != null) {
        throw ConfigurationException.at(
            key, "is the same host as another entry (hosts are case-insensitive): keep one");
      }
    }
    return routes;
  }

  /** A name that is not among the known ones, with the closest if it is a typo. */
  private static ConfigurationException unknown(
      String key, String name, Collection<String> known, String what) {
    String suggestion = Suggestions.closest(name, known);
    return ConfigurationException.invalid(
        key,
        "one of the " + what + ": " + String.join(", ", known),
        "'" + name + "'" + (suggestion != null ? ". Did you mean '" + suggestion + "'?" : ""));
  }
}
