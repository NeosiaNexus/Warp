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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.plugin.PluginDependency;
import dev.warp.api.plugin.PluginDescription;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PluginOrder")
class PluginOrderTest {

  @Test
  @DisplayName("should order independent plugins alphabetically")
  void alphabetical() {
    PluginOrder order = PluginOrder.of(List.of(plugin("c"), plugin("a"), plugin("b")));

    assertEquals(List.of("a", "b", "c"), ids(order));
    assertTrue(order.failures().isEmpty());
  }

  @Test
  @DisplayName("should put dependencies, required or optional, before their dependents")
  void dependenciesFirst() {
    PluginOrder order =
        PluginOrder.of(
            List.of(
                plugin("app", required("db"), optional("cache")),
                plugin("db"),
                plugin("cache", required("db"))));

    assertEquals(List.of("db", "cache", "app"), ids(order));
  }

  @Test
  @DisplayName("should ignore a missing optional dependency")
  void missingOptional() {
    PluginOrder order = PluginOrder.of(List.of(plugin("app", optional("absent"))));

    assertEquals(List.of("app"), ids(order));
  }

  @Test
  @DisplayName("should fail plugins requiring a missing plugin, transitively")
  void missingRequired() {
    PluginOrder order =
        PluginOrder.of(
            List.of(plugin("a", required("absent")), plugin("b", required("a")), plugin("c")));

    assertEquals(List.of("c"), ids(order));
    assertTrue(String.valueOf(order.failures().get("a")).contains("not installed"));
    assertTrue(String.valueOf(order.failures().get("b")).contains("cannot load"));
  }

  @Test
  @DisplayName("should fail the plugins of a cycle and load the rest")
  void cycle() {
    PluginOrder order =
        PluginOrder.of(
            List.of(
                plugin("a", required("b")),
                plugin("b", optional("a")),
                plugin("c"),
                plugin("d", required("a"))));

    assertEquals(List.of("c"), ids(order));
    assertTrue(String.valueOf(order.failures().get("a")).contains("cycle between a, b, d"));
    assertTrue(order.failures().containsKey("b"));
    assertTrue(order.failures().containsKey("d"));
  }

  private static List<String> ids(PluginOrder order) {
    return order.order().stream().map(PluginCandidate::id).toList();
  }

  private static PluginCandidate plugin(String id, PluginDependency... dependencies) {
    return new PluginCandidate(
        Path.of(id + ".jar"),
        new PluginDescription(
            id, "", "", "", "", List.of(), Arrays.asList(dependencies), id + ".Main"));
  }

  private static PluginDependency required(String id) {
    return new PluginDependency(id, false);
  }

  private static PluginDependency optional(String id) {
    return new PluginDependency(id, true);
  }
}
