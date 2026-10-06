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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Play packet codecs")
class PlayPacketsTest {

  // ---------------------------------------------------------------------------
  // KeepAlive
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("KeepAlive")
  class KeepAliveCodec {

    @Test
    @DisplayName("should roundtrip as long for 1.12.2+")
    void roundtripLong() {
      KeepAlive original = new KeepAlive(123456789L);
      ByteBuf buf = Unpooled.buffer();
      try {
        KeepAlive.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(8, buf.readableBytes()); // long = 8 bytes
        KeepAlive decoded = KeepAlive.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(123456789L, decoded.id());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip as VarInt for 1.8–1.12.1")
    void roundtripVarInt() {
      KeepAlive original = new KeepAlive(42);
      ByteBuf buf = Unpooled.buffer();
      try {
        KeepAlive.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        KeepAlive decoded = KeepAlive.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertEquals(42L, decoded.id());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip as int for 1.7.x")
    void roundtripInt() {
      KeepAlive original = new KeepAlive(12345);
      ByteBuf buf = Unpooled.buffer();
      try {
        KeepAlive.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_7_2);
        assertEquals(4, buf.readableBytes()); // int = 4 bytes
        KeepAlive decoded = KeepAlive.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_7_2);
        assertEquals(12345L, decoded.id());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // PlayPluginMessage
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("PlayPluginMessage")
  class PlayPluginMessageCodec {

    @Test
    @DisplayName("should roundtrip plugin message")
    void roundtrip() {
      byte[] data = {0x01, 0x02, 0x03, 0x04};
      PlayPluginMessage original = new PlayPluginMessage("minecraft:brand", data);
      ByteBuf buf = Unpooled.buffer();
      try {
        PlayPluginMessage.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        PlayPluginMessage decoded =
            PlayPluginMessage.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals("minecraft:brand", decoded.channel());
        assertArrayEquals(data, decoded.data());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Transfer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Transfer")
  class TransferCodec {

    @Test
    @DisplayName("should roundtrip transfer packet")
    void roundtrip() {
      Transfer original = new Transfer("play.example.com", 25565);
      ByteBuf buf = Unpooled.buffer();
      try {
        Transfer.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_5);
        Transfer decoded = Transfer.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);
        assertEquals("play.example.com", decoded.host());
        assertEquals(25565, decoded.port());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // PlayDisconnect
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("PlayDisconnect")
  class PlayDisconnectCodec {

    static Stream<ProtocolVersion> jsonVersions() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_3));
    }

    static Stream<ProtocolVersion> nbtVersions() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonVersions")
    @DisplayName("should write a plain text reason as a VarInt-prefixed JSON string before 1.20.3")
    void plainTextJson(ProtocolVersion version) {
      byte[] wire = encode(PlayDisconnect.ofPlainText("Kicked", version), version);

      // VarInt 17, then {"text":"Kicked"}.
      assertArrayEquals(utf8("\u0011{\"text\":\"Kicked\"}"), wire);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nbtVersions")
    @DisplayName("should write a plain text reason as an NBT string tag from 1.20.3")
    void plainTextNbt(ProtocolVersion version) {
      byte[] wire = encode(PlayDisconnect.ofPlainText("Kicked", version), version);

      // TAG_String (8), unsigned-short length 6, then the text.
      assertArrayEquals(utf8("\u0008\u0000\u0006Kicked"), wire);
    }

    @Test
    @DisplayName("should roundtrip a backend's reason byte for byte")
    void roundtrip() {
      byte[] rawReason = utf8("\u0008\u0000\u0006Kicked");

      PlayDisconnect decoded =
          PlayDisconnect.CODEC.decode(
              Unpooled.wrappedBuffer(rawReason), ProtocolVersion.MINECRAFT_1_21_4);

      assertArrayEquals(rawReason, encode(decoded, ProtocolVersion.MINECRAFT_1_21_4));
    }

    private static byte[] encode(PlayDisconnect packet, ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        PlayDisconnect.CODEC.encode(packet, buf, version);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private static byte[] utf8(String text) {
      return text.getBytes(StandardCharsets.UTF_8);
    }
  }

  // ---------------------------------------------------------------------------
  // JoinGame (minimal decode)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("JoinGame")
  class JoinGameCodec {

    @Test
    @DisplayName("should roundtrip with raw remainder")
    void roundtrip() {
      byte[] remainder = {0x00, 0x01, 0x02, 0x03, 0x04};
      JoinGame original = new JoinGame(42, true, 1, remainder);
      ByteBuf buf = Unpooled.buffer();
      try {
        JoinGame.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        JoinGame decoded = JoinGame.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(42, decoded.entityId());
        assertEquals(true, decoded.isHardcore());
        assertEquals(1, decoded.gameMode());
        assertArrayEquals(remainder, decoded.rawRemainder());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should pack hardcore bit into gameMode byte for pre-1.16.2")
    void hardcoreBitPre1162() {
      byte[] remainder = {0x00};
      JoinGame original = new JoinGame(99, true, 0, remainder);
      ByteBuf buf = Unpooled.buffer();
      try {
        JoinGame.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_9);
        // entityId (4 bytes) + gameMode byte with hardcore bit packed
        assertEquals(99, buf.readInt());
        int rawGameMode = buf.readUnsignedByte();
        // hardcore = bit 0x08, gameMode = 0 -> raw byte = 0x08
        assertEquals(0x08, rawGameMode);
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip at 1.9 preserving hardcore=true and gameMode=0")
    void roundtripPre1162() {
      byte[] remainder = {0x01, 0x02};
      JoinGame original = new JoinGame(7, true, 0, remainder);
      ByteBuf buf = Unpooled.buffer();
      try {
        JoinGame.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_9);
        JoinGame decoded = JoinGame.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_9);
        assertEquals(7, decoded.entityId());
        assertTrue(decoded.isHardcore());
        assertEquals(0, decoded.gameMode());
        assertArrayEquals(remainder, decoded.rawRemainder());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // SystemChatMessage
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("SystemChatMessage")
  class SystemChatMessageCodec {

    @Test
    @DisplayName("should roundtrip JSON content for pre-1.20.3")
    void roundtripJson() {
      // Build raw content as a VarInt-prefixed JSON string (what McString.write produces)
      ByteBuf temp = Unpooled.buffer();
      McString.write(temp, "{\"text\":\"hello\"}");
      byte[] rawContent = new byte[temp.readableBytes()];
      temp.readBytes(rawContent);
      temp.release();

      SystemChatMessage original = new SystemChatMessage(rawContent, false);
      ByteBuf buf = Unpooled.buffer();
      try {
        SystemChatMessage.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_19_3);
        SystemChatMessage decoded =
            SystemChatMessage.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19_3);
        assertArrayEquals(rawContent, decoded.rawContent());
        assertEquals(false, decoded.overlay());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip NBT content for 1.20.3+")
    void roundtripNbt() {
      byte[] rawContent = {0x08, 0x00, 0x05, 'h', 'e', 'l', 'l', 'o'};
      SystemChatMessage original = new SystemChatMessage(rawContent, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        SystemChatMessage.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_3);
        SystemChatMessage decoded =
            SystemChatMessage.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_3);
        assertArrayEquals(rawContent, decoded.rawContent());
        assertEquals(true, decoded.overlay());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ResourcePackResponse
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ResourcePackResponse")
  class ResourcePackResponseCodec {

    @Test
    @DisplayName("should roundtrip for pre-1.20.3 (no UUID)")
    void roundtripPreUuid() {
      ResourcePackResponse original = new ResourcePackResponse(new UUID(0, 0), 0);
      ByteBuf buf = Unpooled.buffer();
      try {
        ResourcePackResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        ResourcePackResponse decoded =
            ResourcePackResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertEquals(0, decoded.result());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.20.3+ (with UUID)")
    void roundtripWithUuid() {
      UUID uuid = UUID.randomUUID();
      ResourcePackResponse original = new ResourcePackResponse(uuid, 3);
      ByteBuf buf = Unpooled.buffer();
      try {
        ResourcePackResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_3);
        ResourcePackResponse decoded =
            ResourcePackResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_3);
        assertEquals(uuid, decoded.uuid());
        assertEquals(3, decoded.result());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // PlayClientSettings
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("PlayClientSettings")
  class PlayClientSettingsCodec {

    /** Settings of a 1.21.4 client, every field away from its default. */
    private static final PlayClientSettings NEWEST_SETTINGS =
        settings((byte) 0, (byte) 0x7F, 0, true, false, 2);

    /**
     * Settings packets as node-minecraft-protocol 1.68 (the protocol library of the end-to-end
     * bots) serialises them, packet id excluded: locale {@code en_GB}, view distance 12, chat
     * commands only, chat colors on, then every later field away from its default (all skin parts,
     * left hand, text filtering on, server listing refused, minimal particles), so that a field
     * read at the wrong place cannot go unnoticed. 1.7.x sends difficulty 2 and shows the cape.
     */
    static Stream<Arguments> capturedPackets() {
      return Stream.of(
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_7_6,
              "05656e5f47420c01010201",
              settings((byte) 2, (byte) 0x01, 1, false, true, 0)),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_8,
              "05656e5f47420c01017f",
              settings((byte) 0, (byte) 0x7F, 1, false, true, 0)),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_12_2,
              "05656e5f47420c01017f00",
              settings((byte) 0, (byte) 0x7F, 0, false, true, 0)),
          // 1.16.4 and 1.16.5 share protocol 754
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_16_4,
              "05656e5f47420c01017f00",
              settings((byte) 0, (byte) 0x7F, 0, false, true, 0)),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_17_1,
              "05656e5f47420c01017f0001",
              settings((byte) 0, (byte) 0x7F, 0, true, true, 0)),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_18_2,
              "05656e5f47420c01017f000100",
              settings((byte) 0, (byte) 0x7F, 0, true, false, 0)),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_21_4,
              "05656e5f47420c01017f00010002",
              settings((byte) 0, (byte) 0x7F, 0, true, false, 2)));
    }

    /** Payload sizes on each side of every layout change, for {@link #NEWEST_SETTINGS}. */
    static Stream<Arguments> payloadSizeAcrossLayoutChanges() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_2, 11),
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, 11),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, 10),
          Arguments.of(ProtocolVersion.MINECRAFT_1_9, 11),
          Arguments.of(ProtocolVersion.MINECRAFT_1_16_4, 11),
          Arguments.of(ProtocolVersion.MINECRAFT_1_17, 12),
          Arguments.of(ProtocolVersion.MINECRAFT_1_17_1, 12),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18, 13),
          Arguments.of(ProtocolVersion.MINECRAFT_1_21, 13),
          Arguments.of(ProtocolVersion.MINECRAFT_1_21_2, 14),
          Arguments.of(ProtocolVersion.latest(), 14));
    }

    /**
     * Chat mode {@code 0x82}, a value with its high bit set, where a byte and a VarInt differ on
     * the wire: one byte before 1.9, the two-byte VarInt {@code 82 01} from 1.9. Read the wrong
     * way, it swallows or leaves a byte and misaligns every later field.
     */
    static Stream<Arguments> chatModeWithHighBit() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, "05656e5f47420c82010201"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, "05656e5f47420c82017f"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_9, "05656e5f47420c8201017f01"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("capturedPackets")
    @DisplayName("should decode a client's packet completely, field by field")
    void decodesCapturedPacket(
        ProtocolVersion version, String wireHex, PlayClientSettings expected) {
      byte[] wire = HexFormat.of().parseHex(wireHex);

      PlayClientSettings decoded = decode(wire, version);

      assertEquals(expected, decoded);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("capturedPackets")
    @DisplayName("should encode the decoded settings back to the client's exact bytes")
    void encodesCapturedPacketIdentically(
        ProtocolVersion version, String wireHex, PlayClientSettings decoded) {
      byte[] forwarded = encode(decoded, version);

      assertEquals(wireHex, HexFormat.of().formatHex(forwarded));
    }

    @ParameterizedTest(name = "{0}: {1} bytes")
    @MethodSource("payloadSizeAcrossLayoutChanges")
    @DisplayName("should write exactly the fields of each layout and read all of them back")
    void writesOnlyTheFieldsOfEachLayout(ProtocolVersion version, int expectedSize) {
      byte[] wire = encode(NEWEST_SETTINGS, version);

      byte[] reencoded = encode(decode(wire, version), version);

      assertEquals(expectedSize, wire.length);
      assertArrayEquals(wire, reencoded);
    }

    @Test
    @DisplayName("should give a 1.8 client the neutral defaults of the fields it does not send")
    void defaultsFieldsAnOldClientDoesNotSend() {
      byte[] wire = HexFormat.of().parseHex("05656e5f47420c01017f");

      PlayClientSettings decoded = decode(wire, ProtocolVersion.MINECRAFT_1_8);

      assertEquals(0, decoded.difficulty(), "difficulty");
      assertEquals(1, decoded.mainHand(), "main hand: right");
      assertFalse(decoded.enableTextFiltering(), "text filtering: off");
      assertTrue(decoded.allowServerListings(), "server listing: allowed");
      assertEquals(0, decoded.particleStatus(), "particles: all");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chatModeWithHighBit")
    @DisplayName("should keep the chat mode one byte before 1.9 and a VarInt from 1.9")
    void keepsTheChatModeEncodingOfEachLayout(ProtocolVersion version, String wireHex) {
      byte[] wire = HexFormat.of().parseHex(wireHex);

      PlayClientSettings decoded = decode(wire, version);

      assertEquals(0x82, decoded.chatMode());
      assertTrue(decoded.chatColors());
      assertEquals(wireHex, HexFormat.of().formatHex(encode(decoded, version)));
    }

    private static PlayClientSettings settings(
        byte difficulty,
        byte skinParts,
        int mainHand,
        boolean textFiltering,
        boolean serverListings,
        int particleStatus) {
      return new PlayClientSettings(
          "en_GB",
          (byte) 12,
          1,
          true,
          difficulty,
          skinParts,
          mainHand,
          textFiltering,
          serverListings,
          particleStatus);
    }

    private static byte[] encode(PlayClientSettings packet, ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        PlayClientSettings.CODEC.encode(packet, buf, version);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private static PlayClientSettings decode(byte[] wire, ProtocolVersion version) {
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        PlayClientSettings decoded = PlayClientSettings.CODEC.decode(buf, version);
        assertEquals(0, buf.readableBytes(), "bytes left after PlayClientSettings");
        return decoded;
      } finally {
        buf.release();
      }
    }
  }
}
