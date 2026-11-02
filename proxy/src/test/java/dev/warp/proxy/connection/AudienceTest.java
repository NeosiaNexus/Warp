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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.player.AudienceFeature;
import dev.warp.api.player.Player;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.ClearDialog;
import dev.warp.protocol.packet.play.DeleteChat;
import dev.warp.protocol.packet.play.SetTitleText;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabListHeaderFooter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
  @DisplayName("should refuse what no proxy can do, for every client, rather than do nothing")
  void refuseWhatNeedsTheBackend() {
    SwitchHarness setup = inGame();
    Player player = setup.player.api();
    setup.sentToClient();

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            player.openBook(
                Book.builder().title(Component.text("t")).author(Component.text("a")).build()));
    assertThrows(UnsupportedOperationException.class, () -> player.showDialog(new DialogLike() {}));
    SignedMessage chat =
        new TestSignedMessage(SignedMessage.signature(new byte[256]), "hello", null);
    assertThrows(
        UnsupportedOperationException.class,
        () -> player.sendMessage(chat, ChatType.CHAT.bind(Component.text("Alex"))));
    assertEquals(List.of(), setup.sentToClient());
  }

  @Test
  @DisplayName("should show a system signed message as a chat message")
  void systemSignedMessage() {
    SwitchHarness setup = inGame();
    Player player = setup.player.api();
    setup.sentToClient();

    player.sendMessage(
        SignedMessage.system("hello", Component.text("Hello")),
        ChatType.CHAT.bind(Component.text("Server")));

    assertEquals(List.of(SystemChatMessage.class), types(setup.sentToClient()));
  }

  @Test
  @DisplayName("should delete a signed message by its signature, and refuse one of another size")
  void deleteMessage() {
    SwitchHarness setup = inGame();
    Player player = setup.player.api();
    setup.sentToClient();

    player.deleteMessage(SignedMessage.signature(new byte[256]));

    assertEquals(List.of(DeleteChat.class), types(setup.sentToClient()));
    assertThrows(
        IllegalArgumentException.class,
        () -> player.deleteMessage(SignedMessage.signature(new byte[12])));
  }

  @Test
  @DisplayName("should close the dialog on screen from 1.21.6, in play and in configuration")
  void closeDialog() {
    SwitchHarness modern = inGame(ProtocolVersion.MINECRAFT_1_21_6);
    SwitchHarness older = inGame(ProtocolVersion.MINECRAFT_1_21_5);
    modern.sentToClient();
    older.sentToClient();

    modern.player.api().closeDialog();
    older.player.api().closeDialog();
    modern.player.startConfiguration();
    modern.sentToClient();
    modern.player.api().closeDialog();

    List<SwitchHarness.Sent> inConfiguration = modern.sentToClient();
    assertEquals(List.of(ClearDialog.class), types(inConfiguration));
    assertEquals(ProtocolState.CONFIGURATION, inConfiguration.getFirst().state());
    assertEquals(List.of(), older.sentToClient());
  }

  @Nested
  @DisplayName("features by client version")
  class Features {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.AudienceTest#versions")
    @DisplayName("should tell what each client version can show")
    void byVersion(ProtocolVersion version) {
      boolean is18 = version.isAtLeast(ProtocolVersion.MINECRAFT_1_8);

      assertTrue(WarpPlayer.supports(AudienceFeature.POSITIONED_SOUND, version));
      assertTrue(WarpPlayer.supports(AudienceFeature.RESOURCE_PACK, version));
      assertEquals(is18, WarpPlayer.supports(AudienceFeature.ACTION_BAR, version));
      assertEquals(is18, WarpPlayer.supports(AudienceFeature.TITLE, version));
      assertEquals(is18, WarpPlayer.supports(AudienceFeature.RESOURCE_PACK_STATUS, version));
      assertEquals(
          is18, WarpPlayer.supports(AudienceFeature.PLAYER_LIST_HEADER_AND_FOOTER, version));
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_9),
          WarpPlayer.supports(AudienceFeature.BOSS_BAR, version));
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_9_4),
          WarpPlayer.supports(AudienceFeature.STOP_SOUND, version));
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1),
          WarpPlayer.supports(AudienceFeature.DELETE_MESSAGE, version));
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3),
          WarpPlayer.supports(AudienceFeature.EMITTED_SOUND, version));
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3),
          WarpPlayer.supports(AudienceFeature.MULTIPLE_RESOURCE_PACKS, version));
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_6),
          WarpPlayer.supports(AudienceFeature.CLOSE_DIALOG, version));
    }

    @Test
    @DisplayName("should reach the players that can show something, and skip the others silently")
    void mixedAudience() {
      SwitchHarness modern = inGame(ProtocolVersion.MINECRAFT_1_21_4);
      SwitchHarness legacy = inGame(ProtocolVersion.MINECRAFT_1_8);
      modern.sentToClient();
      legacy.sentToClient();
      Audience both = Audience.audience(modern.player.api(), legacy.player.api());

      both.showBossBar(
          BossBar.bossBar(Component.text("x"), 1, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
      both.playSound(Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1, 1));
      both.stopSound(SoundStop.all());
      both.clearResourcePacks();

      assertTrue(
          types(modern.sentToClient()).contains(dev.warp.protocol.packet.play.BossBar.class));
      assertEquals(List.of(), legacy.sentToClient());
      assertTrue(modern.player.api().supports(AudienceFeature.BOSS_BAR));
      assertFalse(legacy.player.api().supports(AudienceFeature.BOSS_BAR));
    }
  }

  static Stream<ProtocolVersion> versions() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_7_6,
        ProtocolVersion.MINECRAFT_1_8,
        ProtocolVersion.MINECRAFT_1_9,
        ProtocolVersion.MINECRAFT_1_9_4,
        ProtocolVersion.MINECRAFT_1_12_2,
        ProtocolVersion.MINECRAFT_1_19,
        ProtocolVersion.MINECRAFT_1_19_1,
        ProtocolVersion.MINECRAFT_1_19_3,
        ProtocolVersion.MINECRAFT_1_20_2,
        ProtocolVersion.MINECRAFT_1_20_3,
        ProtocolVersion.MINECRAFT_1_21_5,
        ProtocolVersion.MINECRAFT_1_21_6,
        ProtocolVersion.latest());
  }

  /** A player's signed chat message, as a backend would hand it over. */
  private record TestSignedMessage(
      SignedMessage.Signature signature, String message, @Nullable Component unsignedContent)
      implements SignedMessage {

    @Override
    public Identity identity() {
      return Identity.identity(UUID.randomUUID());
    }

    @Override
    public Instant timestamp() {
      return Instant.EPOCH;
    }

    @Override
    public long salt() {
      return 1;
    }
  }

  private SwitchHarness inGame() {
    return inGame(VERSION);
  }

  private SwitchHarness inGame(ProtocolVersion version) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    setup.player.joinedGame();
    return setup;
  }
}
