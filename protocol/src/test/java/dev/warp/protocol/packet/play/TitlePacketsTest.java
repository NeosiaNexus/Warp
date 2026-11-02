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
package dev.warp.protocol.packet.play;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.text.ComponentCodec;

import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Title packets")
class TitlePacketsTest {

  private static final Component TEXT = Component.text("Title", NamedTextColor.GOLD);

  private static <T extends dev.warp.protocol.packet.Packet> byte[] encode(
      PacketCodec<T> codec, T packet, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      codec.encode(packet, buf, version);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  private static <T extends dev.warp.protocol.packet.Packet> T decode(
      PacketCodec<T> codec, byte[] body, ProtocolVersion version) {
    ByteBuf buf = Unpooled.wrappedBuffer(body);
    T packet = codec.decode(buf, version);
    assertFalse(buf.isReadable(), "unread bytes");
    return packet;
  }

  private byte[] prefixed(int action, byte[] rest) {
    byte[] out = new byte[rest.length + 1];
    out[0] = (byte) action;
    System.arraycopy(rest, 0, out, 1, rest.length);
    return out;
  }

  /** Each text packet, its Title packet action, and the versions of the legacy and split forms. */
  static Stream<Arguments> textPackets() {
    return Stream.of(
        Arguments.of("title", 0, ProtocolVersion.MINECRAFT_1_8),
        Arguments.of("subtitle", 1, ProtocolVersion.MINECRAFT_1_8),
        Arguments.of("action bar", 2, ProtocolVersion.MINECRAFT_1_11));
  }

  @Nested
  @DisplayName("text")
  class Texts {

    private record Codecs(
        PacketCodec<? extends PlayPacket> codec,
        java.util.function.Function<byte[], PlayPacket> create,
        java.util.function.Function<PlayPacket, byte[]> text) {}

    @SuppressWarnings("unchecked")
    private static Codecs codecs(String name) {
      return switch (name) {
        case "title" ->
            new Codecs(SetTitleText.CODEC, SetTitleText::new, p -> ((SetTitleText) p).text());
        case "subtitle" ->
            new Codecs(
                SetSubtitleText.CODEC, SetSubtitleText::new, p -> ((SetSubtitleText) p).text());
        default ->
            new Codecs(
                SetActionBarText.CODEC, SetActionBarText::new, p -> ((SetActionBarText) p).text());
      };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.play.TitlePacketsTest#textPackets")
    @DisplayName("should be a Title packet action up to 1.16.5, the component alone from 1.17")
    @SuppressWarnings("unchecked")
    void legacyAndSplit(String name, int action, ProtocolVersion legacy) {
      Codecs codecs = codecs(name);
      PacketCodec<PlayPacket> codec = (PacketCodec<PlayPacket>) codecs.codec();
      for (ProtocolVersion version :
          new ProtocolVersion[] {
            legacy,
            ProtocolVersion.MINECRAFT_1_16_4,
            ProtocolVersion.MINECRAFT_1_17,
            ProtocolVersion.MINECRAFT_1_20_3,
            ProtocolVersion.MINECRAFT_26_3
          }) {
        byte[] component = ComponentCodec.encode(TEXT, version);
        PlayPacket packet = codecs.create().apply(component);
        byte[] wire = encode(codec, packet, version);

        byte[] expected =
            version.isOlderThan(ProtocolVersion.MINECRAFT_1_17)
                ? prefixed(action, component)
                : component;
        assertArrayEquals(expected, wire, version.toString());
        assertArrayEquals(component, codecs.text().apply(decode(codec, wire, version)));
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.play.TitlePacketsTest#textPackets")
    @DisplayName("should refuse a Title packet of another action")
    @SuppressWarnings("unchecked")
    void otherAction(String name, int action, ProtocolVersion legacy) {
      PacketCodec<PlayPacket> codec = (PacketCodec<PlayPacket>) codecs(name).codec();
      byte[] wire = prefixed(action + 1, ComponentCodec.encode(TEXT, legacy));

      assertThrows(
          DecoderException.class, () -> codec.decode(Unpooled.wrappedBuffer(wire), legacy));
    }

    @Test
    @DisplayName("should encode the components of its factories for the version")
    void factories() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_5;
      byte[] component = ComponentCodec.encode(TEXT, version);

      assertArrayEquals(component, SetTitleText.of(TEXT, version).text());
      assertArrayEquals(component, SetSubtitleText.of(TEXT, version).text());
      assertArrayEquals(component, SetActionBarText.of(TEXT, version).text());
    }
  }

  @Nested
  @DisplayName("times")
  class Times {

    @Test
    @DisplayName(
        "should take action 2 before 1.11, 3 up to 1.16.5, and be its own packet from 1.17")
    void actions() {
      SetTitleTimes times = new SetTitleTimes(10, 70, 20);
      byte[] ints = {0, 0, 0, 10, 0, 0, 0, 70, 0, 0, 0, 20};

      assertArrayEquals(
          prefixed(2, ints), encode(SetTitleTimes.CODEC, times, ProtocolVersion.MINECRAFT_1_10));
      assertArrayEquals(
          prefixed(3, ints), encode(SetTitleTimes.CODEC, times, ProtocolVersion.MINECRAFT_1_11));
      assertArrayEquals(
          prefixed(3, ints), encode(SetTitleTimes.CODEC, times, ProtocolVersion.MINECRAFT_1_16_4));
      assertArrayEquals(ints, encode(SetTitleTimes.CODEC, times, ProtocolVersion.MINECRAFT_1_17));
      assertEquals(
          times, decode(SetTitleTimes.CODEC, prefixed(2, ints), ProtocolVersion.MINECRAFT_1_8));
      assertEquals(
          times, decode(SetTitleTimes.CODEC, prefixed(3, ints), ProtocolVersion.MINECRAFT_1_12_2));
      assertEquals(times, decode(SetTitleTimes.CODEC, ints, ProtocolVersion.MINECRAFT_1_21_4));
      assertThrows(
          DecoderException.class,
          () ->
              SetTitleTimes.CODEC.decode(
                  Unpooled.wrappedBuffer(prefixed(2, ints)), ProtocolVersion.MINECRAFT_1_11));
    }
  }

  @Nested
  @DisplayName("tab list header and footer")
  class HeaderFooter {

    @Test
    @DisplayName("should read two components back, JSON before 1.20.3 and NBT from 1.20.3")
    void bothFormats() {
      for (ProtocolVersion version :
          new ProtocolVersion[] {ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.MINECRAFT_1_20_3}) {
        TabListHeaderFooter packet = TabListHeaderFooter.of(TEXT, Component.text("f"), version);
        byte[] wire = encode(TabListHeaderFooter.CODEC, packet, version);

        TabListHeaderFooter decoded = decode(TabListHeaderFooter.CODEC, wire, version);
        assertArrayEquals(ComponentCodec.encode(TEXT, version), decoded.header());
        assertArrayEquals(ComponentCodec.encode(Component.text("f"), version), decoded.footer());
      }
    }
  }
}
