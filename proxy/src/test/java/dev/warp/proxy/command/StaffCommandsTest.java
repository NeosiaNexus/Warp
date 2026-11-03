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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.permission.Tristate;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.proxy.i18n.PlayerMessages;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("/find, /alert and /server")
class StaffCommandsTest {

  private final AdminFixture proxy = new AdminFixture();
  private final RegisteredServer lobby = proxy.server("lobby");
  private final RegisteredServer survival = proxy.server("survival");

  @Nested
  @DisplayName("/find")
  class Find {

    @Test
    @DisplayName("should say where a player plays, with its latency and version")
    void found() {
      proxy.player("Steve", lobby);
      AdminFixture.Source source = AdminFixture.granted(FindCommand.PERMISSION);

      proxy.run(source, "find steve");

      assertEquals("Steve is on lobby (34 ms, 1.21.4)", source.last());
    }

    @Test
    @DisplayName("should show the address only with warp.command.find.address")
    void address() {
      proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "find Steve");

      assertEquals("Steve is on lobby (34 ms, 1.21.4) from 203.0.113.7", console.last());
    }

    @Test
    @DisplayName("should say when a player is not on a server yet, or not connected")
    void elsewhere() {
      proxy.player("Joining", null);
      AdminFixture.Source source = AdminFixture.granted(FindCommand.PERMISSION);

      proxy.run(source, "find Joining");
      proxy.run(source, "find Nobody");

      assertEquals(
          List.of(
              "Joining is not on a server yet (1.21.4)", "No player named Nobody is connected."),
          source.told());
    }

    @Test
    @DisplayName("should suggest players and not be the proxy's without its permission")
    void permission() {
      proxy.player("Steve", lobby);
      proxy.player("Sam", lobby);
      proxy.player("Alex", lobby);

      assertEquals(List.of("Sam", "Steve"), proxy.suggest(AdminFixture.console(), "find s"));
      assertFalse(proxy.run(AdminFixture.granted("warp.command.glist"), "find Steve"));
    }
  }

  @Nested
  @DisplayName("/alert")
  class Alert {

    @Test
    @DisplayName(
        "should tell every player of every server, in MiniMessage, and the sender how many")
    void alert() {
      Player steve = proxy.player("Steve", lobby);
      Player notch = proxy.player("Notch", survival);
      AdminFixture.Source source = AdminFixture.granted(AlertCommand.PERMISSION);

      proxy.run(source, "alert Restart in <bold>5</bold> minutes");

      assertEquals(List.of("[Alert] Restart in 5 minutes"), proxy.told(steve));
      assertEquals(proxy.told(steve), proxy.told(notch));
      assertEquals(
          NamedTextColor.RED,
          PlayerMessages.english(proxy.components(steve).getFirst()).children().getFirst().color());
      assertEquals("Alert sent to 2 players.", source.last());
    }

    @Test
    @DisplayName("should not be the proxy's without warp.command.alert")
    void denied() {
      Player steve = proxy.player("Steve", lobby);

      assertFalse(proxy.run(AdminFixture.granted("warp.command.send"), "alert hello"));
      assertTrue(proxy.told(steve).isEmpty());
    }
  }

  @Nested
  @DisplayName("/server")
  class Server {

    @Test
    @DisplayName("should list the servers to the console, and point it to /send to move someone")
    void console() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "server");
      proxy.run(console, "server lobby");

      assertEquals(
          List.of(
              "Servers: lobby, survival",
              "Only players switch servers: move them with /send <player> <server>."),
          console.told());
    }

    @Test
    @DisplayName("should be open to a player nobody set warp.command.server for")
    void undefined() {
      Player steve = proxy.player("Steve", lobby);

      assertTrue(proxy.run(steve, "server survival"));

      verify(steve).connect(survival);
    }

    @Test
    @DisplayName("should leave a player denied warp.command.server to its backend")
    void deniedPlayer() {
      Player steve = proxy.player("Steve", lobby);
      when(steve.permissionValue(ProxyCommands.SERVER_PERMISSION)).thenReturn(Tristate.FALSE);

      assertFalse(proxy.commands.owns(steve, "server survival"));
      assertFalse(proxy.run(steve, "server survival"));

      verify(steve, never()).connect(any());
    }

    @Test
    @DisplayName("should say when the player is already there")
    void already() {
      Player steve = proxy.player("Steve", lobby);
      proxy.moves(steve, new ConnectionResult.AlreadyConnected(lobby));

      proxy.run(steve, "server lobby");

      assertEquals(List.of("Already connected to lobby."), proxy.told(steve));
    }
  }

  @Nested
  @DisplayName("every command")
  class Every {

    @Test
    @DisplayName("should run with extra spaces between its words")
    void spaces() {
      Player steve = proxy.player("Steve", lobby);

      proxy.run(AdminFixture.console(), "send   Steve  survival ");

      verify(steve).connect(survival);
    }

    @Test
    @DisplayName("should need a permission of warp.command.<name>, but /server")
    void nodes() {
      Set<String> nodes =
          new HashSet<>(
              List.of(
                  WarpCommand.PERMISSION,
                  AntiBotCommand.PERMISSION,
                  GlistCommand.PERMISSION,
                  SendCommand.PERMISSION,
                  FindCommand.PERMISSION,
                  AlertCommand.PERMISSION,
                  ShutdownCommand.PERMISSION));
      AdminFixture.Source nobody = AdminFixture.granted();

      for (String command : List.of("warp", "glist", "send", "find", "alert", "shutdown")) {
        assertFalse(proxy.commands.owns(nobody, command), command);
      }
      assertTrue(nodes.stream().allMatch(node -> node.matches("warp\\.command\\.[a-z]+")));
      assertTrue(proxy.commands.owns(nobody, "server"));
    }
  }
}
