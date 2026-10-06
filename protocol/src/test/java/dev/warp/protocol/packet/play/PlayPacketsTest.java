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
      ChatCommand packet = new ChatCommand(LONG_COMMAND, new byte[0]);
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
      byte[] signingFields = {0, 0, 1, -110, 42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0};
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

    @Test
    @DisplayName("should roundtrip at 1.20.2 (no particleStatus)")
    void roundtrip1202() {
      PlayClientSettings original =
          new PlayClientSettings("en_US", (byte) 12, 0, true, (byte) 127, 1, false, true, 0);
      ByteBuf buf = Unpooled.buffer();
      try {
        PlayClientSettings.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_2);
        PlayClientSettings decoded =
            PlayClientSettings.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_2);
        assertEquals("en_US", decoded.locale());
        assertEquals(12, decoded.viewDistance());
        assertEquals(0, decoded.particleStatus());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip at 1.21.4 (with particleStatus)")
    void roundtrip1214() {
      PlayClientSettings original =
          new PlayClientSettings("fr_FR", (byte) 8, 1, false, (byte) 0, 0, true, false, 2);
      ByteBuf buf = Unpooled.buffer();
      try {
        PlayClientSettings.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        PlayClientSettings decoded =
            PlayClientSettings.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals("fr_FR", decoded.locale());
        assertEquals(2, decoded.particleStatus());
      } finally {
        buf.release();
      }
    }
  }
}
