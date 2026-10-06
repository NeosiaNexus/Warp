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
import io.netty.handler.codec.DecoderException;
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

    /** 0x12345678 as a VarInt: seven bits at a time, low group first. */
    private static final byte[] VAR_INT_0X12345678 = {
      (byte) 0xF8, (byte) 0xAC, (byte) 0xD1, (byte) 0x91, 0x01
    };

    static Stream<Arguments> wireFormsAcrossIdWidths() {
      byte[] int32 = {0x12, 0x34, 0x56, 0x78};
      byte[] int64 = {0, 0, 0, 0, 0x12, 0x34, 0x56, 0x78};
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_2, int32),
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, int32),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, VAR_INT_0X12345678),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_1, VAR_INT_0X12345678),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, int64),
          Arguments.of(ProtocolVersion.latest(), int64));
    }

    static Stream<ProtocolVersion> versionsWithIntId() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_7_2,
          ProtocolVersion.MINECRAFT_1_7_6,
          ProtocolVersion.MINECRAFT_1_8,
          ProtocolVersion.MINECRAFT_1_12_1);
    }

    static Stream<ProtocolVersion> versionsWithLongId() {
      return Stream.of(ProtocolVersion.MINECRAFT_1_12_2, ProtocolVersion.latest());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wireFormsAcrossIdWidths")
    @DisplayName("should write the id as an int, then a VarInt, then a long")
    void writesIdInVersionWireForm(ProtocolVersion version, byte[] expected) {
      KeepAlive packet = new KeepAlive(0x12345678L);

      byte[] wire = encode(packet, version);

      assertArrayEquals(expected, wire);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsWithIntId")
    @DisplayName("should roundtrip every int id, negative ones included, before 1.12.2")
    void roundtripsIntIds(ProtocolVersion version) {
      for (long id : new long[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
        KeepAlive decoded = decode(encode(new KeepAlive(id), version), version);

        assertEquals(id, decoded.id(), version + " id " + id);
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsWithIntId")
    @DisplayName("should refuse to truncate an id that does not fit in 32 bits before 1.12.2")
    void refusesIdsOutsideIntRange(ProtocolVersion version) {
      long[] tooWide = {
        Integer.MAX_VALUE + 1L, Integer.MIN_VALUE - 1L, 1L << 32, Long.MAX_VALUE, Long.MIN_VALUE
      };
      for (long id : tooWide) {
        ByteBuf buf = Unpooled.buffer();
        try {
          assertThrows(
              IllegalArgumentException.class,
              () -> KeepAlive.CODEC.encode(new KeepAlive(id), buf, version),
              version + " id " + id);
          assertEquals(0, buf.writerIndex(), "nothing may be written for id " + id);
        } finally {
          buf.release();
        }
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsWithLongId")
    @DisplayName("should roundtrip every long id from 1.12.2")
    void roundtripsLongIds(ProtocolVersion version) {
      for (long id : new long[] {Long.MIN_VALUE, 0xDEADBEEFCAFEBABEL, -1, 0, Long.MAX_VALUE}) {
        KeepAlive decoded = decode(encode(new KeepAlive(id), version), version);

        assertEquals(id, decoded.id(), version + " id " + id);
      }
    }

    @Test
    @DisplayName("should report a 64-bit id from 1.12.2 only")
    void reportsLongIdFrom1122() {
      assertFalse(KeepAlive.hasLongId(ProtocolVersion.MINECRAFT_1_7_2));
      assertFalse(KeepAlive.hasLongId(ProtocolVersion.MINECRAFT_1_8));
      assertFalse(KeepAlive.hasLongId(ProtocolVersion.MINECRAFT_1_12_1));
      assertTrue(KeepAlive.hasLongId(ProtocolVersion.MINECRAFT_1_12_2));
      assertTrue(KeepAlive.hasLongId(ProtocolVersion.latest()));
    }

    private static byte[] encode(KeepAlive packet, ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        KeepAlive.CODEC.encode(packet, buf, version);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private static KeepAlive decode(byte[] wire, ProtocolVersion version) {
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        KeepAlive decoded = KeepAlive.CODEC.decode(buf, version);
        assertEquals(0, buf.readableBytes(), "trailing bytes after the id");
        return decoded;
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
  // ResourcePackResponse
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ResourcePackResponse")
  class ResourcePackResponseCodec {

    private static final UUID NO_UUID = new UUID(0, 0);
    private static final String HASH = "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12";

    @ParameterizedTest(name = "{0}")
    @MethodSource("withHash")
    @DisplayName("should write the pack hash, then the result, from 1.8 to 1.9.4")
    void hashThenResult(ProtocolVersion version) {
      ResourcePackResponse original = new ResourcePackResponse(NO_UUID, HASH, 1);
      byte[] expected = new byte[42];
      expected[0] = 40; // VarInt byte length of the hash
      System.arraycopy(HASH.getBytes(StandardCharsets.US_ASCII), 0, expected, 1, 40);
      expected[41] = 1; // VarInt result

      assertWire(original, version, expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resultOnly")
    @DisplayName("should write the result alone from 1.10 to 1.20.2")
    void resultAlone(ProtocolVersion version) {
      ResourcePackResponse original = new ResourcePackResponse(NO_UUID, "", 1);

      assertWire(original, version, new byte[] {1});
    }

    @Test
    @DisplayName("should write the pack UUID, then the result, from 1.20.3")
    void uuidThenResult() {
      UUID uuid = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
      ResourcePackResponse original = new ResourcePackResponse(uuid, "", 3);
      byte[] expected = HexFormat.of().parseHex("f81d4fae7dec11d0a76500a0c91e6bf603");

      assertWire(original, ProtocolVersion.MINECRAFT_1_20_3, expected);
    }

    /** The first and last version of the layout with the hash. */
    static Stream<ProtocolVersion> withHash() {
      return Stream.of(ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.MINECRAFT_1_9_4);
    }

    /** The first and last version of the layout with the result alone. */
    static Stream<ProtocolVersion> resultOnly() {
      return Stream.of(ProtocolVersion.MINECRAFT_1_10, ProtocolVersion.MINECRAFT_1_20_2);
    }

    /** Encodes {@code packet} to exactly {@code expected}, which decodes back to it. */
    private static void assertWire(
        ResourcePackResponse packet, ProtocolVersion version, byte[] expected) {
      ByteBuf buf = Unpooled.buffer();
      try {
        ResourcePackResponse.CODEC.encode(packet, buf, version);
        assertArrayEquals(expected, ByteBufUtil.getBytes(buf));

        ResourcePackResponse decoded = ResourcePackResponse.CODEC.decode(buf, version);

        assertEquals(packet, decoded);
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ChatCommand
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ChatCommand")
  class ChatCommandCodec {

    /** Longer than the 256 characters a command could have before 1.20.5. */
    private static final String LONG_COMMAND = "say " + "a".repeat(296);

    @Test
    @DisplayName("should read an unsigned command longer than 256 characters from 1.20.5")
    void longUnsignedCommand() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, LONG_COMMAND);

        ChatCommand decoded = ChatCommand.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);

        assertEquals(LONG_COMMAND, decoded.command());
        assertEquals(0, decoded.rawSignatureData().length);
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should write an unsigned command longer than 256 characters as the string alone")
    void writeLongUnsignedCommand() {
      ChatCommand packet = new ChatCommand(LONG_COMMAND, new byte[0], 0);
      ByteBuf expected = Unpooled.buffer();
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(expected, LONG_COMMAND);

        ChatCommand.CODEC.encode(packet, buf, ProtocolVersion.MINECRAFT_1_20_5);

        assertEquals(expected, buf);
      } finally {
        expected.release();
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject a command longer than 256 characters before 1.20.5")
    void longCommandBefore1205() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, LONG_COMMAND);

        assertThrows(
            DecoderException.class,
            () -> ChatCommand.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_3));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should keep the signing fields after the command before 1.20.5")
    void signingFieldsBefore1205() {
      // Timestamp, salt, no argument signatures, last-seen offset 0, 3 bytes of acknowledgements.
      byte[] signingFields = {0, 0, 1, -110, 42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0};
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "server survival");
        buf.writeBytes(signingFields);

        ChatCommand decoded = ChatCommand.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_3);
        buf.clear();
        ChatCommand.CODEC.encode(decoded, buf, ProtocolVersion.MINECRAFT_1_20_3);

        assertEquals("server survival", decoded.command());
        assertEquals("server survival", McString.read(buf));
        assertArrayEquals(signingFields, ByteBufUtil.getBytes(buf));
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
