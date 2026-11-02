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
package dev.warp.api.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PluginDescription")
class PluginDescriptionTest {

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(
      strings = {
        "a",
        "lobby-router",
        "prime_mc",
        "x9",
        "a234567890123456789012345678901234567890123456789012345678901234"
      })
  @DisplayName("should accept valid identifiers")
  void validIds(String id) {
    assertTrue(PluginDescription.isValidId(id));
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(
      strings = {
        "",
        "Lobby",
        "9lives",
        "-x",
        "has space",
        "dots.are.out",
        "a2345678901234567890123456789012345678901234567890123456789012345"
      })
  @DisplayName("should refuse invalid identifiers")
  void invalidIds(String id) {
    assertFalse(PluginDescription.isValidId(id));
    assertThrows(IllegalArgumentException.class, () -> description(id, List.of()));
  }

  @Test
  @DisplayName("should use the identifier as the name when none is given")
  void defaultsName() {
    PluginDescription description =
        new PluginDescription("router", " ", "", "", "", List.of(), List.of(), "a.Main");

    assertEquals("router", description.name());
  }

  @Test
  @DisplayName("should refuse a plugin depending on itself")
  void selfDependency() {
    assertThrows(
        IllegalArgumentException.class,
        () -> description("router", List.of(new PluginDependency("router", false))));
  }

  @Test
  @DisplayName("should refuse a dependency declared twice")
  void duplicateDependency() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            description(
                "router",
                List.of(new PluginDependency("db", false), new PluginDependency("db", true))));
  }

  @Test
  @DisplayName("should copy its lists so later changes do not leak in")
  void copiesLists() {
    List<String> authors = new ArrayList<>(List.of("Steve"));
    PluginDescription description =
        new PluginDescription("router", "", "", "", "", authors, List.of(), "a.Main");

    authors.add("Alex");

    assertEquals(List.of("Steve"), description.authors());
    assertThrows(UnsupportedOperationException.class, () -> description.authors().add("x"));
  }

  @Test
  @DisplayName("should refuse an invalid dependency identifier")
  void invalidDependencyId() {
    assertThrows(IllegalArgumentException.class, () -> new PluginDependency("Not Valid", false));
  }

  private static PluginDescription description(String id, List<PluginDependency> dependencies) {
    return new PluginDescription(id, "", "", "", "", List.of(), dependencies, "a.Main");
  }
}
