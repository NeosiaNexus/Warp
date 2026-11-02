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
package dev.warp.proxy.plugin;

import dev.warp.api.plugin.PluginDependency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Orders plugins so that every plugin comes after the plugins it depends on, and finds those that
 * cannot load: a required dependency missing (or itself unable to load), or a dependency cycle.
 *
 * <p>The order is deterministic: among plugins whose dependencies are satisfied, identifiers are
 * taken alphabetically, so the same plugins always load in the same order.
 *
 * @param order the plugins that can load, dependencies first
 * @param failures why each other plugin cannot load, by identifier
 */
record PluginOrder(List<PluginCandidate> order, Map<String, String> failures) {

  /**
   * Orders candidates.
   *
   * @param candidates plugins with distinct identifiers
   * @return the order and the failures
   */
  static PluginOrder of(List<PluginCandidate> candidates) {
    Map<String, PluginCandidate> byId = new LinkedHashMap<>();
    for (PluginCandidate candidate : candidates) {
      byId.put(candidate.id(), candidate);
    }
    Map<String, String> failures = new LinkedHashMap<>();

    // Missing required dependencies, propagated to everything that requires a failed plugin.
    boolean changed = true;
    while (changed) {
      changed = false;
      for (PluginCandidate candidate : candidates) {
        if (failures.containsKey(candidate.id())) {
          continue;
        }
        for (PluginDependency dependency : candidate.description().dependencies()) {
          if (dependency.optional()) {
            continue;
          }
          if (!byId.containsKey(dependency.id())) {
            failures.put(
                candidate.id(),
                "it requires plugin '" + dependency.id() + "', which is not installed");
            changed = true;
            break;
          }
          if (failures.containsKey(dependency.id())) {
            failures.put(
                candidate.id(), "it requires plugin '" + dependency.id() + "', which cannot load");
            changed = true;
            break;
          }
        }
      }
    }

    // Kahn's algorithm over the plugins left, with present optional dependencies as edges too.
    Map<String, Integer> pending = new HashMap<>();
    Map<String, List<String>> dependents = new HashMap<>();
    for (PluginCandidate candidate : candidates) {
      if (failures.containsKey(candidate.id())) {
        continue;
      }
      int count = 0;
      for (PluginDependency dependency : candidate.description().dependencies()) {
        if (byId.containsKey(dependency.id()) && !failures.containsKey(dependency.id())) {
          count++;
          dependents.computeIfAbsent(dependency.id(), _ -> new ArrayList<>()).add(candidate.id());
        }
      }
      pending.put(candidate.id(), count);
    }
    TreeSet<String> ready = new TreeSet<>();
    pending.forEach(
        (id, count) -> {
          if (count == 0) {
            ready.add(id);
          }
        });
    List<PluginCandidate> order = new ArrayList<>(pending.size());
    while (!ready.isEmpty()) {
      String id = ready.pollFirst();
      order.add(byId.get(id));
      pending.remove(id);
      for (String dependent : dependents.getOrDefault(id, List.of())) {
        int left = pending.merge(dependent, -1, Integer::sum);
        if (left == 0) {
          ready.add(dependent);
        }
      }
    }
    if (!pending.isEmpty()) {
      List<String> cycle = new ArrayList<>(new TreeSet<>(pending.keySet()));
      for (String id : cycle) {
        failures.put(id, "it is part of a dependency cycle between " + String.join(", ", cycle));
      }
    }
    return new PluginOrder(List.copyOf(order), Map.copyOf(failures));
  }
}
