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

import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.SetActionBarText;
import dev.warp.protocol.packet.play.SetSubtitleText;
import dev.warp.protocol.packet.play.SetTitleText;
import dev.warp.protocol.packet.play.SetTitleTimes;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.protocol.text.ComponentCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConnectedPlayer components")
class PlayerComponentsTest {

  private static final Component HELLO = Component.text("Hello", NamedTextColor.GOLD);

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  /** A player of {@code version} whose client is in PLAY, in its world when {@code inGame}. */
  private SwitchHarness player(ProtocolVersion version, boolean inGame) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY);
    harnesses.add(setup);
    if (inGame) {
      setup.player.joinedGame();
    }
    return setup;
  }

  /** The body Warp's codec writes for {@code packet} in PLAY. */
  private static byte[] body(Packet packet, ProtocolVersion version) {
    @SuppressWarnings("unchecked")
    PacketCodec<Packet> codec =
        (PacketCodec<Packet>)
            StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
                .encoding(version, packet.getClass())
                .codec();
    ByteBuf buf = Unpooled.buffer();
    try {
      codec.encode(packet, buf, version);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Chat and action bar
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("messages")
  class Messages {

    @Test
    @DisplayName("should send a chat message encoded for the client's version")
    void chat() {
      for (ProtocolVersion version :
          List.of(
              ProtocolVersion.MINECRAFT_1_7_6,
              ProtocolVersion.MINECRAFT_1_16_4,
              ProtocolVersion.MINECRAFT_1_21_5)) {
        SwitchHarness setup = player(version, true);

        setup.player.sendMessage(HELLO);

        List<SwitchHarness.Sent> sent = setup.sentToClient();
        assertEquals(List.of(SystemChatMessage.class), types(sent), version.toString());
        assertArrayEquals(
            body(SystemChatMessage.of(HELLO, version), version), sent.getFirst().body());
      }
    }

    @Test
    @DisplayName("should keep messages until the client is in a game, then send them in order")
    void keptUntilJoinGame() {
      SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_21_4, false);

      setup.player.sendMessage(Component.text("first"));
      setup.player.sendSystemMessage("second");
      assertEquals(List.of(), setup.sentToClient());

      setup.player.joinedGame();

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(2, sent.size());
      assertEquals(
          Component.text("first"),
          ComponentCodec.decode(
              sent.get(0).as(SystemChatMessage.class).rawContent(),
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.PLAY));
      assertEquals(
          Component.text("second"),
          ComponentCodec.decode(
              sent.get(1).as(SystemChatMessage.class).rawContent(),
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.PLAY));
    }

    @Test
    @DisplayName("should keep at most 128 packets for a client not in a game")
    void boundedQueue() {
      SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_21_4, false);

      for (int i = 0; i < 130; i++) {
        setup.player.sendMessage(Component.text(i));
      }
      setup.player.joinedGame();

      assertEquals(128, setup.sentToClient().size());
    }

    @Test
    @DisplayName("should keep messages sent during the configuration phase of a switch")
    void keptDuringConfiguration() {
      SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_21_4, true);

      setup.player.startConfiguration();
      setup.player.sendMessage(HELLO);

      assertEquals(List.of(StartConfiguration.class), types(setup.sentToClient()));
      assertEquals(ProtocolState.CONFIGURATION, setup.client.encoder().state());
      setup.client.setState(ProtocolState.PLAY); // configured again
      setup.player.joinedGame();
      assertEquals(List.of(SystemChatMessage.class), types(setup.sentToClient()));
    }

    @Test
    @DisplayName("should show an action bar with the packet of each version, none on 1.7")
    void actionBar() {
      Map<ProtocolVersion, Packet> expected =
          Map.of(
              ProtocolVersion.MINECRAFT_1_8,
              new SystemChatMessage(
                  ComponentCodec.encode(
                      ComponentCodec.legacyActionBar(HELLO), ProtocolVersion.MINECRAFT_1_8),
                  true),
              ProtocolVersion.MINECRAFT_1_12_2,
              SetActionBarText.of(HELLO, ProtocolVersion.MINECRAFT_1_12_2),
              ProtocolVersion.MINECRAFT_1_21_5,
              SetActionBarText.of(HELLO, ProtocolVersion.MINECRAFT_1_21_5));
      for (var entry : expected.entrySet()) {
        SwitchHarness setup = player(entry.getKey(), true);

        setup.player.sendActionBar(HELLO);

        List<SwitchHarness.Sent> sent = setup.sentToClient();
        assertEquals(1, sent.size(), entry.getKey().toString());
        assertArrayEquals(body(entry.getValue(), entry.getKey()), sent.getFirst().body());
      }
      SwitchHarness legacy = player(ProtocolVersion.MINECRAFT_1_7_6, true);
      legacy.player.sendActionBar(HELLO);
      assertEquals(List.of(), legacy.sentToClient());
    }

    @Test
    @DisplayName("should drop an action bar sent while the client is not in a game")
    void actionBarDropped() {
      SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_21_4, false);

      setup.player.sendActionBar(HELLO);
      setup.player.joinedGame();

      assertEquals(List.of(), setup.sentToClient());
    }

    @Test
    @DisplayName("should render the translations plugins registered, in the player's language")
    void translations() {
      TranslationStore.StringBased<java.text.MessageFormat> store =
          TranslationStore.messageFormat(Key.key("warp", "components-test"));
      store.register("warp.test.greeting", Locale.FRANCE, new java.text.MessageFormat("Bonjour"));
      GlobalTranslator.translator().addSource(store);
      try {
        SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_21_4, true);
        setup.player.cacheClientSettings(
            new ClientInformation("fr_fr", (byte) 8, 0, true, (byte) 0x7F, 1, false, true, 0));

        setup.player.sendMessage(Component.translatable("warp.test.greeting"));

        byte[] content = setup.sentToClient().getFirst().as(SystemChatMessage.class).rawContent();
        assertEquals(
            Component.text("Bonjour"),
            ComponentCodec.decode(content, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY));
      } finally {
        GlobalTranslator.translator().removeSource(store);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Titles
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("titles")
  class Titles {

    private final Title title =
        Title.title(
            Component.text("Title"),
            Component.text("Sub"),
            Title.Times.times(
                Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1)));

    @Test
    @DisplayName("should send the times, the subtitle, then the title")
    void showTitle() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      SwitchHarness setup = player(version, true);

      setup.player.showTitle(title);

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(
          List.of(SetTitleTimes.class, SetSubtitleText.class, SetTitleText.class), types(sent));
      assertEquals(new SetTitleTimes(10, 60, 20), sent.get(0).as(SetTitleTimes.class));
    }

    @Test
    @DisplayName("should send each part, and clear or reset the title")
    void partsAndClearing() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      SwitchHarness setup = player(version, true);

      setup.player.sendTitlePart(TitlePart.SUBTITLE, Component.text("s"));
      setup.player.sendTitlePart(TitlePart.TITLE, Component.text("t"));
      setup.player.sendTitlePart(TitlePart.TIMES, Title.DEFAULT_TIMES);
      setup.player.clearTitle();
      setup.player.resetTitle();

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(
          List.of(
              SetSubtitleText.class,
              SetTitleText.class,
              SetTitleTimes.class,
              ClearTitles.class,
              ClearTitles.class),
          types(sent));
      assertFalse(sent.get(3).as(ClearTitles.class).reset());
      assertTrue(sent.get(4).as(ClearTitles.class).reset());
    }

    @Test
    @DisplayName("should hide then reset a title up to 1.16.5, and send nothing on 1.7")
    void legacy() {
      SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_12_2, true);
      setup.player.resetTitle();
      // The Title packet: hide (4), then reset (5) from 1.11.
      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(2, sent.size());
      assertArrayEquals(new byte[] {4}, sent.get(0).body());
      assertArrayEquals(new byte[] {5}, sent.get(1).body());

      SwitchHarness legacy = player(ProtocolVersion.MINECRAFT_1_7_6, true);
      legacy.player.showTitle(title);
      legacy.player.sendTitlePart(TitlePart.TITLE, Component.text("t"));
      legacy.player.clearTitle();
      legacy.player.sendPlayerListHeaderAndFooter(HELLO, HELLO);
      assertEquals(List.of(), legacy.sentToClient());
    }
  }

  // ---------------------------------------------------------------------------
  // Tab list header and footer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("tab list header and footer")
  class HeaderAndFooter {

    @Test
    @DisplayName("should send them, and again after the next Join Game")
    void keptAcrossSwitches() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      SwitchHarness setup = player(version, true);
      byte[] expected = body(TabListHeaderFooter.of(HELLO, Component.empty(), version), version);

      setup.player.sendPlayerListHeaderAndFooter(HELLO, Component.empty());
      assertArrayEquals(expected, setup.sentToClient().getFirst().body());
      assertEquals(HELLO, setup.player.playerListHeader());
      assertEquals(Component.empty(), setup.player.playerListFooter());

      setup.player.startConfiguration();
      setup.sentToClient();
      setup.client.setState(ProtocolState.PLAY);
      setup.player.joinedGame();

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(TabListHeaderFooter.class), types(sent));
      assertArrayEquals(expected, sent.getFirst().body());
    }

    @Test
    @DisplayName("should not send them after a Join Game when the proxy set none")
    void noneSet() {
      SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_21_4, true);

      setup.player.joinedGame();

      assertEquals(List.of(), setup.sentToClient());
    }
  }

  // ---------------------------------------------------------------------------
  // Disconnect
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("disconnect")
  class Disconnect {

    @Test
    @DisplayName("should send a play disconnect in PLAY, closing an open bundle first")
    void play() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      SwitchHarness setup = player(version, true);
      setup.player.toggleBundle();

      setup.player.disconnect(HELLO);

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(BundleDelimiter.class, PlayDisconnect.class), types(sent));
      assertEquals(HELLO, sent.get(1).as(PlayDisconnect.class).decodedReason(version));
      assertFalse(setup.client.channel().isOpen());
    }

    @Test
    @DisplayName("should send a configuration disconnect once Start Configuration went out")
    void afterStartConfiguration() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      SwitchHarness setup = player(version, true);
      setup.player.startConfiguration();
      setup.sentToClient();

      setup.player.disconnect(HELLO);

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(ConfigDisconnect.class), types(sent));
      assertEquals(HELLO, sent.getFirst().as(ConfigDisconnect.class).decodedReason(version));
    }

    @Test
    @DisplayName("should send a login disconnect as JSON in the login state")
    void login() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_5;
      SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.LOGIN);
      harnesses.add(setup);

      setup.player.disconnect(HELLO);

      byte[] body = setup.sentToClient().getFirst().body();
      assertArrayEquals(
          body(LoginDisconnect.of(HELLO, version), version, ProtocolState.LOGIN), body);
    }

    @Test
    @DisplayName("should send a configuration disconnect after Login Success, still read in LOGIN")
    void loginSuccessWindow() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_5;
      SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.LOGIN);
      harnesses.add(setup);
      setup.client.encoder().setState(ProtocolState.CONFIGURATION); // Login Success went out
      var decoder = setup.client.decoder();

      setup.player.disconnect(HELLO);

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(ConfigDisconnect.class), types(sent));
      assertEquals(ProtocolState.LOGIN, decoder.state());
    }

    @Test
    @DisplayName("should replace a reason too large for the client, and disconnect regardless")
    void tooLarge() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_13_2;
      SwitchHarness setup = player(version, true);

      setup.player.disconnect(Component.text("x".repeat(40_000)));

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(
          Component.text("Disconnected"),
          sent.getFirst().as(PlayDisconnect.class).decodedReason(version));
      assertFalse(setup.client.channel().isOpen());
    }

    @Test
    @DisplayName("should send nothing more once disconnected")
    void nothingAfter() {
      SwitchHarness setup = player(ProtocolVersion.MINECRAFT_1_21_4, true);
      setup.player.disconnect(HELLO);
      setup.sentToClient();

      setup.player.sendMessage(HELLO);
      setup.player.disconnect(HELLO);

      assertEquals(List.of(), setup.sentToClient());
    }

    private static byte[] body(Packet packet, ProtocolVersion version, ProtocolState state) {
      @SuppressWarnings("unchecked")
      PacketCodec<Packet> codec =
          (PacketCodec<Packet>)
              StateRegistry.get(state, PacketDirection.CLIENTBOUND)
                  .encoding(version, packet.getClass())
                  .codec();
      ByteBuf buf = Unpooled.buffer();
      try {
        codec.encode(packet, buf, version);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }
  }
}
