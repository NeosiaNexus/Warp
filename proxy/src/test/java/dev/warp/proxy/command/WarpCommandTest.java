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
package dev.warp.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginDescription;
import dev.warp.api.plugin.PluginState;
import dev.warp.proxy.WarpBuildInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.kyori.adventure.text.event.ClickEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("/warp")
class WarpCommandTest {

  private final AdminFixture proxy = new AdminFixture();

  @Nested
  @DisplayName("overview")
  class Overview {

    @Test
    @DisplayName("should show version, uptime, Java, players, compression, memory and plugins")
    void overview() {
      proxy.player("Steve", proxy.server("lobby"));
      proxy.pluginList.add(plugin("luckperms", PluginState.ENABLED, null));
      proxy.pluginList.add(plugin("broken", PluginState.FAILED, new IllegalStateException("x")));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "warp");

      List<String> lines = List.of(console.last().split("\n"));
      assertTrue(lines.getFirst().startsWith("Warp " + WarpBuildInfo.VERSION), lines.toString());
      assertTrue(lines.get(1).startsWith("Up 1d 3h · Java " + Runtime.version()), lines.get(1));
      assertEquals("Players 1 · Servers 1", lines.get(2));
      assertEquals("Compression libdeflate 1.24 (native)", lines.get(3));
      assertTrue(lines.get(4).startsWith("Heap "), lines.get(4));
      assertEquals("Plugins 2 (1 enabled, 1 failed)", lines.get(5));
      assertEquals("More: /warp plugins, /warp antibot", lines.get(6));
    }

    @Test
    @DisplayName("should copy the version when it is clicked")
    void copyVersion() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "warp");

      ClickEvent<?> click = console.lastComponent().children().getFirst().clickEvent();
      assertNotNull(click);
      assertEquals(ClickEvent.Action.COPY_TO_CLIPBOARD, click.action());
    }

    @Test
    @DisplayName("should show the drain while one runs")
    void draining() {
      proxy.draining(
          new DrainStatus(
              DrainPhase.ANNOUNCING,
              DrainTrigger.COMMAND,
              Instant.EPOCH,
              Duration.ofSeconds(3),
              5,
              0,
              0,
              0,
              0,
              null));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "warp");

      assertTrue(
          console
              .last()
              .contains("Shutting down for 3s (announcing, by /shutdown), 5 players left"),
          console.last());
    }
  }

  @Nested
  @DisplayName("plugins")
  class Plugins {

    @Test
    @DisplayName("should list each plugin with its version and state, why it failed on hover")
    void list() {
      proxy.pluginList.add(plugin("zeta", PluginState.ENABLED, null));
      proxy.pluginList.add(plugin("alpha", PluginState.FAILED, new IllegalStateException("boom")));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "warp plugins");

      assertEquals(
          "2 plugins (1 enabled, 1 failed)\n- Alpha 1.0 failed\n- Zeta 1.0 enabled",
          console.last());
      String hover = AdminFixture.hovers(console.lastComponent());
      assertTrue(hover.contains("java.lang.IllegalStateException: boom"), hover);
    }

    @Test
    @DisplayName("should say where plugins go when there are none")
    void none() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "warp plugins");

      assertEquals("No plugins: drop their jars into plugins/.", console.last());
    }
  }

  @Nested
  @DisplayName("permissions")
  class Permissions {

    @Test
    @DisplayName("should not be the proxy's for a source with none of its permissions")
    void denied() {
      AdminFixture.Source source = AdminFixture.granted("warp.command.glist");

      assertFalse(proxy.run(source, "warp"));
    }

    @Test
    @DisplayName("should only list what a source granted warp.command.antibot alone may use")
    void antiBotOnly() {
      AdminFixture.Source source = AdminFixture.granted(AntiBotCommand.PERMISSION);

      proxy.run(source, "warp");
      proxy.run(source, "warp plugins");

      assertEquals("More: /warp antibot", source.told().getFirst());
      assertTrue(source.last().contains("Incorrect argument"), source.last());
      assertFalse(proxy.suggest(source, "warp ").contains("plugins"));
    }
  }

  private static PluginContainer plugin(String id, PluginState state, @Nullable Throwable failure) {
    PluginContainer plugin = mock(PluginContainer.class);
    String name = Character.toUpperCase(id.charAt(0)) + id.substring(1);
    when(plugin.description())
        .thenReturn(
            new PluginDescription(
                id, name, "1.0", "A plugin", "", List.of("Someone"), List.of(), "x.Main"));
    when(plugin.state()).thenReturn(state);
    when(plugin.failure()).thenReturn(Optional.ofNullable(failure));
    return plugin;
  }
}
