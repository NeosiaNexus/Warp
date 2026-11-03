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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.i18n.PlayerMessages;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("/shutdown")
class ShutdownCommandTest {

  private final AdminFixture proxy = new AdminFixture();
  private final RegisteredServer lobby = proxy.server("lobby");

  @Nested
  @DisplayName("starting the drain")
  class Starting {

    @Test
    @DisplayName("should drain with the reason, and tell every player at once")
    void reason() {
      Player steve = proxy.player("Steve", lobby);
      Player alex = proxy.player("Alex", null);
      AdminFixture.Source console = AdminFixture.console();

      assertTrue(proxy.run(console, "shutdown Back in <red>5</red> minutes"));

      assertEquals(1, proxy.control.shutdowns.size());
      Component reason = proxy.control.shutdowns.getFirst();
      assertNotNull(reason);
      assertEquals("The server is shutting down: Back in 5 minutes", AdminFixture.plain(reason));
      assertEquals(List.of("The server is shutting down: Back in 5 minutes"), proxy.told(steve));
      assertEquals(proxy.told(steve), proxy.told(alex));
      assertTrue(hasColor(PlayerMessages.english(reason), NamedTextColor.RED), reason.toString());
    }

    @Test
    @DisplayName("should drain with drain.message, and tell it to the players, without a reason")
    void noReason() {
      Player steve = proxy.player("Steve", lobby);

      proxy.run(AdminFixture.console(), "shutdown");

      assertEquals(1, proxy.control.shutdowns.size());
      assertNull(proxy.control.shutdowns.getFirst());
      assertEquals(List.of("This proxy is restarting. Please reconnect."), proxy.told(steve));
    }

    @Test
    @DisplayName("should tell the sender what happens to the players, and when Warp stops")
    void plan() {
      proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "shutdown");

      assertEquals(
          "Shutting down. 1 player is transferred to next.example.net:25565 from 5s on (clients"
              + " older than 1.20.5 are kept); Warp stops once empty, or in 30m at most.",
          console.last());
    }

    @Test
    @DisplayName("should say players are kicked or kept when there is nowhere to transfer them")
    void plans() {
      DrainConfig kick =
          new DrainConfig(
              null,
              false,
              Duration.ofSeconds(5),
              Duration.ofMillis(100),
              DrainConfig.CannotTransfer.KICK,
              DrainConfig.NewPlayers.ACCEPT,
              Duration.ofMinutes(30),
              Component.text("Bye"));
      DrainConfig keep =
          new DrainConfig(
              null,
              false,
              Duration.ofSeconds(5),
              Duration.ofMillis(100),
              DrainConfig.CannotTransfer.KEEP,
              DrainConfig.NewPlayers.ACCEPT,
              Duration.ofMinutes(30),
              Component.text("Bye"));

      String kicked = AdminFixture.plain(ShutdownCommand.started(kick, 2));
      String kept = AdminFixture.plain(ShutdownCommand.started(keep, 0));

      assertTrue(kicked.contains("2 players are kicked from 5s on"), kicked);
      assertTrue(kept.contains("0 players are kept until they leave"), kept);
    }
  }

  @Nested
  @DisplayName("refusals")
  class Refusals {

    @Test
    @DisplayName("should say where a drain in progress stands rather than start another")
    void already() {
      Player steve = proxy.player("Steve", lobby);
      proxy.draining(
          new DrainStatus(
              DrainPhase.TRANSFERRING,
              DrainTrigger.SIGNAL,
              Instant.EPOCH,
              Duration.ofSeconds(75),
              12,
              3,
              0,
              0,
              0,
              null));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "shutdown again");

      assertTrue(proxy.control.shutdowns.isEmpty());
      assertTrue(proxy.told(steve).isEmpty());
      assertEquals(
          "Already shutting down for 1m 15s (transferring, by SIGTERM), 12 players left.",
          console.last());
    }

    @Test
    @DisplayName("should say why the proxy cannot drain, telling no player")
    void notRunning() {
      Player steve = proxy.player("Steve", lobby);
      proxy.control.refuse(new IllegalStateException("The proxy is not running"));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "shutdown");

      assertEquals("Cannot shut down: The proxy is not running", console.last());
      assertTrue(proxy.told(steve).isEmpty());
    }

    @Test
    @DisplayName("should not be the proxy's for a source without warp.command.shutdown")
    void denied() {
      AdminFixture.Source source = AdminFixture.granted("warp.command.warp");

      assertFalse(proxy.run(source, "shutdown"));
      assertTrue(proxy.control.shutdowns.isEmpty());
    }

    @Test
    @DisplayName("should work for a player granted warp.command.shutdown")
    void player() {
      Player admin = proxy.player("Admin", lobby);
      org.mockito.Mockito.when(admin.hasPermission(ShutdownCommand.PERMISSION)).thenReturn(true);

      assertTrue(proxy.run(admin, "shutdown"));

      assertEquals(1, proxy.control.shutdowns.size());
    }
  }

  private static boolean hasColor(Component component, NamedTextColor color) {
    if (color.equals(component.color()) && component instanceof TextComponent) {
      return true;
    }
    return component.children().stream().anyMatch(child -> hasColor(child, color));
  }
}
