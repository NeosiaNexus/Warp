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
package dev.warp.proxy.connection;

import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.player.Player;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.SetTitleText;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabListHeaderFooter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Player as an Adventure audience")
class AudienceTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  @Test
  @DisplayName("should write a message to the player's chat")
  void message() {
    SwitchHarness setup = inGame();
    Player player = setup.player.api();
    setup.sentToClient();

    player.sendMessage(Component.text("Welcome"));

    assertEquals(List.of(SystemChatMessage.class), types(setup.sentToClient()));
  }

  @Test
  @DisplayName("should show a title, and the tab list header and footer")
  void titleAndTabList() {
    SwitchHarness setup = inGame();
    Player player = setup.player.api();
    setup.sentToClient();

    player.showTitle(Title.title(Component.text("PrimeMC"), Component.empty()));
    player.sendPlayerListHeaderAndFooter(Component.text("Header"), Component.text("Footer"));

    List<Class<? extends dev.warp.protocol.packet.Packet>> sent = types(setup.sentToClient());
    assertTrue(sent.contains(SetTitleText.class), sent.toString());
    assertTrue(sent.contains(TabListHeaderFooter.class), sent.toString());
  }

  @Test
  @DisplayName("should expose the player's identity")
  void identity() {
    SwitchHarness setup = inGame();
    Player player = setup.player.api();

    assertEquals(setup.player.uuid(), player.identity().uuid());
    assertEquals(setup.player.username(), player.pointers().get(Identity.NAME).orElseThrow());
  }

  @Test
  @DisplayName("should refuse what it cannot send yet, rather than do nothing")
  void unsupported() {
    Player player = inGame().player.api();

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            player.playSound(
                Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1, 1)));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            player.showBossBar(
                BossBar.bossBar(
                    Component.text("x"), 1, BossBar.Color.RED, BossBar.Overlay.PROGRESS)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> player.removeResourcePacks(List.of(UUID.randomUUID())));
  }

  private SwitchHarness inGame() {
    SwitchHarness setup = new SwitchHarness(VERSION, List.of(), ProtocolState.PLAY);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    setup.player.joinedGame();
    return setup;
  }
}
