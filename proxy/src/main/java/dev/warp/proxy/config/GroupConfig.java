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

import java.util.List;
import java.util.Map;

/**
 * One entry of the {@code groups} block: servers a balancer spreads players over.
 *
 * @param name the group's name, lowercase
 * @param servers the member servers, lowercase, in the configured order, at least one
 * @param weights the weight of each member for the {@code weighted} strategy; a member not listed
 *     weighs 1
 * @param strategy the strategy's name, lowercase, one of the built-in ones
 * @param capacity the players a server takes before the next one is filled, for the {@code fill}
 *     strategy; {@code 0} for the other strategies
 */
public record GroupConfig(
    String name,
    List<String> servers,
    Map<String, Integer> weights,
    String strategy,
    int capacity) {

  /** Copies the collections. */
  public GroupConfig {
    servers = List.copyOf(servers);
    weights = Map.copyOf(weights);
  }

  /**
   * Returns a member's weight.
   *
   * @param server the member, lowercase
   * @return its weight, 1 unless configured
   */
  public int weight(String server) {
    return weights.getOrDefault(server, 1);
  }
}
