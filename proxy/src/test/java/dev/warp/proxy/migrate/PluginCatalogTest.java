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
package dev.warp.proxy.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PluginCatalog")
class PluginCatalogTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("should know the usual plugins from the bundled catalog")
  void bundled() {
    PluginCatalog catalog = PluginCatalog.load();

    assertEquals(PluginCatalog.Status.REPLACED, catalog.lookup("ViaVersion-5.2.0.jar").status());
    assertEquals(PluginCatalog.Status.REPLACED, catalog.lookup("ViaBackwards-5.2.0.jar").status());
    assertEquals(PluginCatalog.Status.BUILT_IN, catalog.lookup("Sonar-Velocity.jar").status());
    assertEquals(PluginCatalog.Status.BUILT_IN, catalog.lookup("limbofilter-1.1.jar").status());
    assertEquals(PluginCatalog.Status.UNAVAILABLE, catalog.lookup("LuckPerms-Bungee.jar").status());
    assertEquals(PluginCatalog.Status.UNAVAILABLE, catalog.lookup("Geyser-Velocity.jar").status());
    assertEquals(
        PluginCatalog.Status.UNAVAILABLE, catalog.lookup("floodgate-velocity.jar").status());
    assertEquals(PluginCatalog.Status.UNAVAILABLE, catalog.lookup("RedisBungee.jar").status());
    assertEquals(PluginCatalog.Status.UNAVAILABLE, catalog.lookup("LiteBans.jar").status());
    assertEquals(PluginCatalog.Status.UNAVAILABLE, catalog.lookup("Plan-5.6.jar").status());
    assertEquals(PluginCatalog.Status.UNKNOWN, catalog.lookup("Something.jar").status());
  }

  @Test
  @DisplayName("should list the jars of a plugins directory in name order, and nothing without one")
  void scan() throws Exception {
    Path plugins = Files.createDirectories(directory.resolve("plugins"));
    Files.createFile(plugins.resolve("b.jar"));
    Files.createFile(plugins.resolve("A.jar"));
    Files.createFile(plugins.resolve("notes.txt"));

    List<PluginCatalog.Plugin> found = PluginCatalog.load().scan(plugins);

    assertEquals(
        List.of("A.jar", "b.jar"), found.stream().map(PluginCatalog.Plugin::file).toList());
    assertTrue(PluginCatalog.load().scan(directory.resolve("missing")).isEmpty());
  }

  @Test
  @DisplayName("should take the first matching line, and refuse a malformed one")
  void parse() {
    PluginCatalog catalog =
        PluginCatalog.parse("# comment\n\nviabackwards = REPLACED: one\nvia = UNAVAILABLE: two\n");

    assertEquals("one", catalog.lookup("ViaBackwards.jar").text());
    assertEquals("two", catalog.lookup("ViaRewind.jar").text());
    assertThrows(IllegalStateException.class, () -> PluginCatalog.parse("broken line\n"));
  }
}
