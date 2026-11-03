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
package dev.warp.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.SetActionBarText;
import dev.warp.protocol.packet.play.SetSubtitleText;
import dev.warp.protocol.packet.play.SetTitleText;
import dev.warp.protocol.packet.play.SetTitleTimes;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.protocol.text.ComponentCodec;

import java.time.Duration;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ComponentPackets")
class ComponentPacketsTest {

  private static final Component TEXT = Component.text("Hi", NamedTextColor.RED);
  private static final ProtocolVersion V1_7 = ProtocolVersion.MINECRAFT_1_7_6;
  private static final ProtocolVersion V1_8 = ProtocolVersion.MINECRAFT_1_8;
  private static final ProtocolVersion V1_10 = ProtocolVersion.MINECRAFT_1_10;
  private static final ProtocolVersion V1_11 = ProtocolVersion.MINECRAFT_1_11;
  private static final ProtocolVersion V1_16 = ProtocolVersion.MINECRAFT_1_16_4;
  private static final ProtocolVersion V1_17 = ProtocolVersion.MINECRAFT_1_17;
  private static final ProtocolVersion LATEST = ProtocolVersion.MINECRAFT_26_3;

  @Nested
  @DisplayName("chat and action bar")
  class ChatAndActionBar {

    @Test
    @DisplayName("should show chat in the chat box")
    void chat() {
      SystemChatMessage chat = (SystemChatMessage) ComponentPackets.chat(TEXT, LATEST);

      assertArrayEquals(ComponentCodec.encode(TEXT, LATEST), chat.rawContent());
      assertEquals(false, chat.overlay());
    }

    @Test
    @DisplayName("should show the action bar with section codes in a chat line before 1.11")
    void actionBar() {
      assertNull(ComponentPackets.actionBar(TEXT, V1_7));
      SystemChatMessage legacy =
          assertInstanceOf(SystemChatMessage.class, ComponentPackets.actionBar(TEXT, V1_10));
      assertTrue(legacy.overlay());
      assertArrayEquals(
          ComponentCodec.encode(ComponentCodec.legacyActionBar(TEXT), V1_10), legacy.rawContent());
      assertArrayEquals(
          ComponentCodec.encode(TEXT, V1_11),
          assertInstanceOf(SetActionBarText.class, ComponentPackets.actionBar(TEXT, V1_11)).text());
      assertInstanceOf(SetActionBarText.class, ComponentPackets.actionBar(TEXT, LATEST));
    }
  }

  @Nested
  @DisplayName("titles")
  class Titles {

    private final Title title =
        Title.title(
            Component.text("T"),
            Component.text("S"),
            Title.Times.times(Duration.ofMillis(1_049), Duration.ofSeconds(2), Duration.ZERO));

    @Test
    @DisplayName("should send the times, the subtitle, then the title, in whole ticks")
    void order() {
      List<PlayPacket> packets = ComponentPackets.title(title, V1_17);

      assertEquals(3, packets.size());
      assertEquals(new SetTitleTimes(20, 40, 0), packets.get(0));
      assertArrayEquals(
          ComponentCodec.encode(Component.text("S"), V1_17),
          ((SetSubtitleText) packets.get(1)).text());
      assertArrayEquals(
          ComponentCodec.encode(Component.text("T"), V1_17),
          ((SetTitleText) packets.get(2)).text());
    }

    @Test
    @DisplayName("should leave out times the title has none of, and send nothing on 1.7")
    void noTimes() {
      Title untimed = Title.title(Component.text("T"), Component.text("S"), null);

      assertEquals(2, ComponentPackets.title(untimed, V1_8).size());
      assertEquals(List.of(), ComponentPackets.title(title, V1_7));
    }

    @Test
    @DisplayName("should send each part on its own, nothing on 1.7")
    void parts() {
      assertInstanceOf(
          SetTitleText.class,
          ComponentPackets.titlePart(TitlePart.TITLE, Component.text("T"), V1_8));
      assertInstanceOf(
          SetSubtitleText.class,
          ComponentPackets.titlePart(TitlePart.SUBTITLE, Component.text("S"), V1_8));
      assertEquals(
          new SetTitleTimes(Integer.MAX_VALUE, 0, 1),
          ComponentPackets.titlePart(
              TitlePart.TIMES,
              Title.Times.times(Duration.ofDays(10_000), Duration.ZERO, Duration.ofMillis(50)),
              V1_8));
      assertNull(ComponentPackets.titlePart(TitlePart.TITLE, Component.text("T"), V1_7));
    }

    @Test
    @DisplayName("should hide a title, and hide then reset it up to 1.16.5")
    void clearing() {
      assertEquals(List.of(new ClearTitles(false)), ComponentPackets.clearTitle(V1_8));
      assertEquals(List.of(), ComponentPackets.clearTitle(V1_7));
      assertEquals(List.of(new ClearTitles(true)), ComponentPackets.resetTitle(V1_17));
      assertEquals(
          List.of(new ClearTitles(false), new ClearTitles(true)),
          ComponentPackets.resetTitle(V1_16));
      assertEquals(
          List.of(new ClearTitles(false), new ClearTitles(true)),
          ComponentPackets.resetTitle(V1_8));
      assertEquals(List.of(), ComponentPackets.resetTitle(V1_7));
    }
  }

  @Nested
  @DisplayName("header, footer and disconnects")
  class Others {

    @Test
    @DisplayName("should set the header and footer from 1.8")
    void headerAndFooter() {
      TabListHeaderFooter packet =
          assertInstanceOf(
              TabListHeaderFooter.class,
              ComponentPackets.headerAndFooter(TEXT, Component.empty(), V1_8));

      assertArrayEquals(ComponentCodec.encode(TEXT, V1_8), packet.header());
      assertArrayEquals(ComponentCodec.encode(Component.empty(), V1_8), packet.footer());
      assertNull(ComponentPackets.headerAndFooter(TEXT, TEXT, V1_7));
    }

    @Test
    @DisplayName("should pick the disconnect of the state the client reads")
    void disconnects() {
      assertNull(ComponentPackets.disconnect(TEXT, ProtocolState.HANDSHAKE, LATEST));
      assertNull(ComponentPackets.disconnect(TEXT, ProtocolState.STATUS, LATEST));
      assertEquals(
          new LoginDisconnect(ComponentCodec.json(TEXT, LATEST)),
          ComponentPackets.disconnect(TEXT, ProtocolState.LOGIN, LATEST));
      Packet configuration = ComponentPackets.disconnect(TEXT, ProtocolState.CONFIGURATION, LATEST);
      Packet play = ComponentPackets.disconnect(TEXT, ProtocolState.PLAY, LATEST);
      assertArrayEquals(
          ComponentCodec.encode(TEXT, LATEST),
          assertInstanceOf(ConfigDisconnect.class, configuration).rawReason());
      assertArrayEquals(
          ComponentCodec.encode(TEXT, LATEST),
          assertInstanceOf(PlayDisconnect.class, play).rawReason());
    }
  }

  @Nested
  @DisplayName("disconnect packets")
  class DisconnectPackets {

    @Test
    @DisplayName("should read back the reasons they carry")
    void decodedReasons() {
      assertEquals(TEXT, PlayDisconnect.of(TEXT, LATEST).decodedReason(LATEST));
      assertEquals(TEXT, ConfigDisconnect.of(TEXT, LATEST).decodedReason(LATEST));
      assertEquals(TEXT, LoginDisconnect.of(TEXT, LATEST).decodedReason());
      assertEquals(
          Component.text("Kicked"), PlayDisconnect.ofPlainText("Kicked", V1_8).decodedReason(V1_8));
      assertEquals(
          Component.text("Kicked"),
          ConfigDisconnect.ofPlainText("Kicked", ProtocolVersion.MINECRAFT_1_20_2)
              .decodedReason(ProtocolVersion.MINECRAFT_1_20_2));
    }

    @Test
    @DisplayName("should refuse a login reason longer than the client reads")
    void loginLimit() {
      Component fits = Component.text("a".repeat(32_756));
      Component tooLong = Component.text("a".repeat(32_757));

      LoginDisconnect.of(fits, ProtocolVersion.MINECRAFT_1_13_2);
      assertThrows(
          IllegalArgumentException.class,
          () -> LoginDisconnect.of(tooLong, ProtocolVersion.MINECRAFT_1_13_2));
      LoginDisconnect.of(tooLong, ProtocolVersion.MINECRAFT_1_14);
    }
  }
}
