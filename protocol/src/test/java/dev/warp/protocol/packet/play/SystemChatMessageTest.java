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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.TextComponent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("SystemChatMessage")
class SystemChatMessageTest {

  private static final String TEXT = "Servers: [lobby], survival";

  /** What a version expects after the text component. */
  enum Trailer {
    /** 1.7: nothing, there is no action bar. */
    NONE,
    /** 1.8 to 1.15.2: the position byte, 1 (system) or 2 (game info). */
    POSITION,
    /** 1.16 to 1.18.2: the position byte, then the nil sender UUID. */
    POSITION_AND_SENDER,
    /** 1.19: a VarInt chat type, 1 (system) or 2 (game info). */
    CHAT_TYPE,
    /** 1.19.1+: the overlay boolean. */
    OVERLAY;

    byte[] bytes(boolean overlay) {
      byte placement = (byte) (overlay ? 2 : 1);
      return switch (this) {
        case NONE -> new byte[0];
        case POSITION, CHAT_TYPE -> new byte[] {placement};
        case POSITION_AND_SENDER -> concat(new byte[] {placement}, new byte[McUuid.ENCODED_SIZE]);
        case OVERLAY -> new byte[] {(byte) (overlay ? 1 : 0)};
      };
    }
  }

  /**
   * One version on each side of every packet id or layout change from 1.7.2 to 1.21.4, with the id
   * and the trailer it expects. Velocity's {@code LegacyChatPacket} and {@code SystemChatPacket}
   * and minecraft-data ({@code chat} and {@code system_chat}) agree on every row.
   */
  static Stream<Arguments> versions() {
    return Stream.of(
        Arguments.of(ProtocolVersion.MINECRAFT_1_7_2, 0x02, Trailer.NONE),
        Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, 0x02, Trailer.NONE),
        Arguments.of(ProtocolVersion.MINECRAFT_1_8, 0x02, Trailer.POSITION),
        Arguments.of(ProtocolVersion.MINECRAFT_1_9, 0x0F, Trailer.POSITION),
        Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, 0x0F, Trailer.POSITION),
        Arguments.of(ProtocolVersion.MINECRAFT_1_13, 0x0E, Trailer.POSITION),
        Arguments.of(ProtocolVersion.MINECRAFT_1_14_4, 0x0E, Trailer.POSITION),
        Arguments.of(ProtocolVersion.MINECRAFT_1_15, 0x0F, Trailer.POSITION),
        Arguments.of(ProtocolVersion.MINECRAFT_1_15_2, 0x0F, Trailer.POSITION),
        Arguments.of(ProtocolVersion.MINECRAFT_1_16, 0x0E, Trailer.POSITION_AND_SENDER),
        // 1.16.4 and 1.16.5 share protocol 754
        Arguments.of(ProtocolVersion.MINECRAFT_1_16_4, 0x0E, Trailer.POSITION_AND_SENDER),
        Arguments.of(ProtocolVersion.MINECRAFT_1_17, 0x0F, Trailer.POSITION_AND_SENDER),
        Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, 0x0F, Trailer.POSITION_AND_SENDER),
        Arguments.of(ProtocolVersion.MINECRAFT_1_19, 0x5F, Trailer.CHAT_TYPE),
        // 1.19.1 and 1.19.2 share protocol 760
        Arguments.of(ProtocolVersion.MINECRAFT_1_19_1, 0x62, Trailer.OVERLAY),
        Arguments.of(ProtocolVersion.MINECRAFT_1_19_3, 0x60, Trailer.OVERLAY),
        Arguments.of(ProtocolVersion.MINECRAFT_1_19_4, 0x64, Trailer.OVERLAY),
        // 1.20 and 1.20.1 share protocol 763
        Arguments.of(ProtocolVersion.MINECRAFT_1_20_1, 0x64, Trailer.OVERLAY),
        Arguments.of(ProtocolVersion.MINECRAFT_1_20_2, 0x67, Trailer.OVERLAY),
        Arguments.of(ProtocolVersion.MINECRAFT_1_20_3, 0x69, Trailer.OVERLAY),
        Arguments.of(ProtocolVersion.MINECRAFT_1_20_5, 0x6C, Trailer.OVERLAY),
        // 1.21 and 1.21.1 share protocol 767
        Arguments.of(ProtocolVersion.MINECRAFT_1_21_1, 0x6C, Trailer.OVERLAY),
        Arguments.of(ProtocolVersion.MINECRAFT_1_21_2, 0x73, Trailer.OVERLAY),
        Arguments.of(ProtocolVersion.MINECRAFT_1_21_4, 0x73, Trailer.OVERLAY));
  }

  /** The rows of {@link #versions()} without the packet id: each version and its trailer. */
  static Stream<Arguments> trailers() {
    return versions().map(row -> Arguments.of(row.get()[0], row.get()[2]));
  }

  /** The rows of {@link #versions()} without the trailer: each version and its packet id. */
  static Stream<Arguments> packetIds() {
    return versions().map(row -> Arguments.of(row.get()[0], row.get()[1]));
  }

  // ---------------------------------------------------------------------------
  // Wire format
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("wire format")
  class WireFormat {

    @ParameterizedTest(name = "{0}: id {1}, {2}")
    @MethodSource("dev.warp.protocol.packet.play.SystemChatMessageTest#versions")
    @DisplayName("should send a chat-box message in the packet and layout of each version")
    void systemMessage(ProtocolVersion version, int packetId, Trailer trailer) {
      byte[] content = TextComponent.plainText(TEXT, version);

      byte[] wire = send(new SystemChatMessage(content, false), version);

      assertArrayEquals(concat(new byte[] {(byte) packetId}, content, trailer.bytes(false)), wire);
    }

    @ParameterizedTest(name = "{0}: id {1}, {2}")
    @MethodSource("dev.warp.protocol.packet.play.SystemChatMessageTest#versions")
    @DisplayName("should send an action bar message in the packet and layout of each version")
    void actionBarMessage(ProtocolVersion version, int packetId, Trailer trailer) {
      byte[] content = TextComponent.plainText(TEXT, version);

      byte[] wire = send(new SystemChatMessage(content, true), version);

      assertArrayEquals(concat(new byte[] {(byte) packetId}, content, trailer.bytes(true)), wire);
    }

    @Test
    @DisplayName("should send a 1.12.2 client a Chat Message with the system position")
    void exactLegacyBytes() {
      String json = "{\"text\":\"" + TEXT + "\"}";
      byte[] content = TextComponent.plainText(TEXT, ProtocolVersion.MINECRAFT_1_12_2);

      byte[] wire = send(new SystemChatMessage(content, false), ProtocolVersion.MINECRAFT_1_12_2);

      assertArrayEquals(
          concat(new byte[] {0x0F, (byte) json.length()}, ascii(json), new byte[] {1}), wire);
    }

    @Test
    @DisplayName(
        "should send a 1.16.5 client a Chat Message with the system position and no sender")
    void exactSenderBytes() {
      String json = "{\"text\":\"" + TEXT + "\"}";
      byte[] content = TextComponent.plainText(TEXT, ProtocolVersion.MINECRAFT_1_16_4);

      byte[] wire = send(new SystemChatMessage(content, false), ProtocolVersion.MINECRAFT_1_16_4);

      assertArrayEquals(
          concat(
              new byte[] {0x0E, (byte) json.length()},
              ascii(json),
              new byte[] {1},
              new byte[McUuid.ENCODED_SIZE]),
          wire);
    }

    @Test
    @DisplayName("should send a 1.19 client a System Chat Message with the system chat type")
    void exactChatTypeBytes() {
      String json = "{\"text\":\"" + TEXT + "\"}";
      byte[] content = TextComponent.plainText(TEXT, ProtocolVersion.MINECRAFT_1_19);

      byte[] wire = send(new SystemChatMessage(content, false), ProtocolVersion.MINECRAFT_1_19);

      assertArrayEquals(
          concat(new byte[] {0x5F, (byte) json.length()}, ascii(json), new byte[] {1}), wire);
    }
  }

  // ---------------------------------------------------------------------------
  // Decoding
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("decoding")
  class Decoding {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.play.SystemChatMessageTest#trailers")
    @DisplayName("should read back what it writes, consuming every byte")
    void roundtrip(ProtocolVersion version, Trailer trailer) {
      byte[] content = TextComponent.plainText(TEXT, version);

      for (boolean overlay : new boolean[] {false, true}) {
        ByteBuf buf = Unpooled.buffer();
        try {
          SystemChatMessage.CODEC.encode(new SystemChatMessage(content, overlay), buf, version);
          SystemChatMessage decoded = SystemChatMessage.CODEC.decode(buf, version);

          assertArrayEquals(content, decoded.rawContent());
          // 1.7 has no action bar: an overlay message is written, and read, as a chat message.
          assertEquals(overlay && trailer != Trailer.NONE, decoded.overlay());
          assertFalse(buf.isReadable(), "unread bytes");
        } finally {
          buf.release();
        }
      }
    }

    static Stream<Arguments> placements() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, 0, false),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, 1, false),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, 2, true),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, 0, false),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, 2, true),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, 1, false),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, 2, true),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, 7, false));
    }

    @ParameterizedTest(name = "{0}: {1} -> overlay {2}")
    @MethodSource("placements")
    @DisplayName("should read only the game info position or chat type as an action bar message")
    void placement(ProtocolVersion version, int placement, boolean overlay) {
      byte[] content = TextComponent.plainText(TEXT, version);
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeBytes(content);
        if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
          VarInt.write(buf, placement);
        } else {
          buf.writeByte(placement);
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16)) {
            buf.writeZero(McUuid.ENCODED_SIZE); // sender
          }
        }

        SystemChatMessage decoded = SystemChatMessage.CODEC.decode(buf, version);

        assertEquals(overlay, decoded.overlay());
        assertFalse(buf.isReadable(), "unread bytes");
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject an NBT message without its overlay byte")
    void nbtWithoutOverlay() {
      ByteBuf buf = Unpooled.buffer();
      try {
        assertThrows(
            DecoderException.class,
            () -> SystemChatMessage.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_3));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("registration")
  class Registration {

    @ParameterizedTest(name = "{0}: id {1}")
    @MethodSource("dev.warp.protocol.packet.play.SystemChatMessageTest#packetIds")
    @DisplayName("should be encode-only, so player chat and backend messages stay blind-forwarded")
    void neverDecoded(ProtocolVersion version, int packetId) {
      PacketRegistry clientbound =
          StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);

      assertEquals(packetId, clientbound.packetId(version, SystemChatMessage.class));
      assertNull(clientbound.lookup(version, packetId));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Encodes the packet as the proxy sends it to a client of this version: id, then body. */
  private byte[] send(SystemChatMessage packet, ProtocolVersion version) {
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new MinecraftEncoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY));
    try {
      assertTrue(channel.writeOutbound(packet));
      ByteBuf out = channel.readOutbound();
      try {
        return ByteBufUtil.getBytes(out);
      } finally {
        out.release();
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }
}
