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
package dev.warp.protocol.packet.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
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
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Config packet codecs")
class ConfigPacketsTest {

  // ---------------------------------------------------------------------------
  // ConfigPluginMessage
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ConfigPluginMessage")
  class ConfigPluginMessageCodec {

    @Test
    @DisplayName("should roundtrip with channel and data")
    void roundtrip() {
      byte[] data = {0x01, 0x02, 0x03};
      ConfigPluginMessage original = new ConfigPluginMessage("minecraft:brand", data);
      ByteBuf buf = Unpooled.buffer();
      try {
        ConfigPluginMessage.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        ConfigPluginMessage decoded =
            ConfigPluginMessage.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals("minecraft:brand", decoded.channel());
        assertArrayEquals(data, decoded.data());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ConfigDisconnect
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ConfigDisconnect")
  class ConfigDisconnectCodec {

    @Test
    @DisplayName("should roundtrip with raw bytes")
    void roundtrip() {
      byte[] rawReason = {0x0A, 0x0B, 0x0C, 0x0D};
      ConfigDisconnect original = new ConfigDisconnect(rawReason);
      ByteBuf buf = Unpooled.buffer();
      try {
        ConfigDisconnect.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        ConfigDisconnect decoded =
            ConfigDisconnect.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertArrayEquals(rawReason, decoded.rawReason());
      } finally {
        buf.release();
      }
    }

    static Stream<ProtocolVersion> nbtVersions() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should write a plain text reason as a VarInt-prefixed JSON string on 1.20.2")
    void plainTextJson() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_20_2;

      byte[] wire = encode(ConfigDisconnect.ofPlainText("Kicked", version), version);

      // VarInt 17, then {"text":"Kicked"}.
      assertArrayEquals(utf8("\u0011{\"text\":\"Kicked\"}"), wire);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nbtVersions")
    @DisplayName("should write a plain text reason as an NBT string tag from 1.20.3")
    void plainTextNbt(ProtocolVersion version) {
      byte[] wire = encode(ConfigDisconnect.ofPlainText("Kicked", version), version);

      // TAG_String (8), unsigned-short length 6, then the text.
      assertArrayEquals(utf8("\u0008\u0000\u0006Kicked"), wire);
    }

    private static byte[] encode(ConfigDisconnect packet, ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        ConfigDisconnect.CODEC.encode(packet, buf, version);
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
  // FinishConfiguration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("FinishConfiguration")
  class FinishConfigurationCodec {

    @Test
    @DisplayName("should roundtrip empty packet")
    void roundtrip() {
      FinishConfiguration original = new FinishConfiguration();
      ByteBuf buf = Unpooled.buffer();
      try {
        FinishConfiguration.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(0, buf.readableBytes());
        FinishConfiguration decoded =
            FinishConfiguration.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(original, decoded);
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // AcknowledgeFinishConfiguration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("AcknowledgeFinishConfiguration")
  class AcknowledgeFinishConfigurationCodec {

    @Test
    @DisplayName("should roundtrip empty packet")
    void roundtrip() {
      AcknowledgeFinishConfiguration original = new AcknowledgeFinishConfiguration();
      ByteBuf buf = Unpooled.buffer();
      try {
        AcknowledgeFinishConfiguration.CODEC.encode(
            original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(0, buf.readableBytes());
        AcknowledgeFinishConfiguration decoded =
            AcknowledgeFinishConfiguration.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(original, decoded);
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ClientInformation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ClientInformation")
  class ClientInformationCodec {

    @Test
    @DisplayName("should roundtrip at 1.20.2 without particleStatus")
    void roundtripWithoutParticleStatus() {
      ClientInformation original =
          new ClientInformation("en_US", (byte) 12, 0, true, (byte) 0x7F, 1, false, true, 0);
      ByteBuf buf = Unpooled.buffer();
      try {
        ClientInformation.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_2);
        ClientInformation decoded =
            ClientInformation.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_2);
        assertEquals("en_US", decoded.locale());
        assertEquals(12, decoded.viewDistance());
        assertEquals(0, decoded.chatMode());
        assertTrue(decoded.chatColors());
        assertEquals(0x7F, decoded.displayedSkinParts());
        assertEquals(1, decoded.mainHand());
        assertEquals(false, decoded.enableTextFiltering());
        assertTrue(decoded.allowServerListings());
        assertEquals(0, decoded.particleStatus());
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip at 1.21.4 with particleStatus")
    void roundtripWithParticleStatus() {
      ClientInformation original =
          new ClientInformation("fr_FR", (byte) 16, 1, false, (byte) 0x01, 0, true, false, 2);
      ByteBuf buf = Unpooled.buffer();
      try {
        ClientInformation.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        ClientInformation decoded =
            ClientInformation.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals("fr_FR", decoded.locale());
        assertEquals(16, decoded.viewDistance());
        assertEquals(1, decoded.chatMode());
        assertEquals(false, decoded.chatColors());
        assertEquals(0x01, decoded.displayedSkinParts());
        assertEquals(0, decoded.mainHand());
        assertTrue(decoded.enableTextFiltering());
        assertEquals(false, decoded.allowServerListings());
        assertEquals(2, decoded.particleStatus());
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }

    /**
     * A 26.x client's packet, packet id excluded: the layout of 1.21.2, which every protocol up to
     * 26.3 keeps. The 26.1 bytes are node-minecraft-protocol 1.68's (vanilla 26.1 writes the same
     * fields); the 26.2 and 26.3 ones are Mojang's own codec's, run from the server jars (26.3
     * encodes its three enums by an id equal to their ordinal). The settings are those of the play
     * state's captures: locale {@code en_GB}, view distance 12, chat commands only, chat colors on,
     * all skin parts, left hand, text filtering on, server listing refused, minimal particles.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("versions26")
    @DisplayName("should decode a 26.x client's packet and encode it back to the same bytes")
    void keepsTheBytesOfA26Client(ProtocolVersion version) {
      String wireHex = "05656e5f47420c01017f00010002";
      ByteBuf buf = Unpooled.buffer().writeBytes(HexFormat.of().parseHex(wireHex));
      try {
        ClientInformation decoded = ClientInformation.CODEC.decode(buf, version);
        buf.clear();
        ClientInformation.CODEC.encode(decoded, buf, version);

        assertEquals(
            new ClientInformation("en_GB", (byte) 12, 1, true, (byte) 0x7F, 0, true, false, 2),
            decoded);
        assertEquals(wireHex, HexFormat.of().formatHex(ByteBufUtil.getBytes(buf)));
      } finally {
        buf.release();
      }
    }

    /** The 26.x protocols, which all keep the 1.21.2 layout. */
    static Stream<ProtocolVersion> versions26() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_26_1,
          ProtocolVersion.MINECRAFT_26_2,
          ProtocolVersion.MINECRAFT_26_3);
    }
  }

  // ---------------------------------------------------------------------------
  // ConfigPluginMessage: the brand
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ConfigPluginMessage brand")
  class Brand {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

    /** A brand message's data: one Minecraft string. */
    private static byte[] brandData(String brand) {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, brand);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private static ByteBuf frame(String channel, byte[] data) {
      ByteBuf buf = Unpooled.buffer();
      ConfigPluginMessage.CODEC.encode(new ConfigPluginMessage(channel, data), buf, VERSION);
      return buf;
    }

    @Test
    @DisplayName("should read a well-formed brand")
    void readsBrand() {
      assertEquals(
          "vanilla",
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData("vanilla")).brand());
      assertEquals(
          "fabric",
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData("fabric")).brand());
    }

    @Test
    @DisplayName("should read a brand of exactly 128 bytes and refuse one of 129")
    void longestBrand() {
      String longest = "b".repeat(ConfigPluginMessage.MAX_BRAND_BYTES);
      byte[] data = brandData(longest);
      assertEquals(ConfigPluginMessage.MAX_BRAND_BYTES + 2, data.length, "a two-byte length");

      assertEquals(
          longest, new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, data).brand());
      assertNull(
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData(longest + "b"))
              .brand());
    }

    @Test
    @DisplayName("should count the brand's bytes, not its characters")
    void bytesNotCharacters() {
      String accented = "\u00e9".repeat(ConfigPluginMessage.MAX_BRAND_BYTES); // 2 bytes each

      assertNull(
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData(accented)).brand());
      assertEquals(
          "\u00e9".repeat(64),
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData("\u00e9".repeat(64)))
              .brand());
    }

    @Test
    @DisplayName("should return no brand for another channel, however well-formed the data")
    void otherChannel() {
      assertNull(new ConfigPluginMessage("minecraft:register", brandData("vanilla")).brand());
    }

    @Test
    @DisplayName("should return no brand for an empty string")
    void emptyBrand() {
      assertNull(new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData("")).brand());
    }

    @Test
    @DisplayName("should return no brand when bytes follow the string")
    void trailingBytes() {
      byte[] data = brandData("vanilla");
      byte[] longer = java.util.Arrays.copyOf(data, data.length + 1);

      assertNull(new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, longer).brand());
    }

    @Test
    @DisplayName("should return no brand for control characters")
    void controlCharacters() {
      assertNull(
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData("van\u0000illa"))
              .brand());
      assertNull(
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, brandData("vanilla\n"))
              .brand());
    }

    @Test
    @DisplayName("should return no brand for a string longer than its data, or no data at all")
    void malformedString() {
      assertNull(
          new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, new byte[] {10, 'a'}).brand());
      assertNull(new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, new byte[0]).brand());
    }

    @Test
    @DisplayName("should watch the brand message whole, its data included")
    void watchesBrand() {
      ByteBuf buf = frame(ConfigPluginMessage.BRAND_CHANNEL, brandData("vanilla"));
      try {
        ConfigPluginMessage watched = ConfigPluginMessage.BRAND_WATCH.watch(buf, VERSION);

        assertNotNull(watched);
        assertEquals(ConfigPluginMessage.BRAND_CHANNEL, watched.channel());
        assertArrayEquals(brandData("vanilla"), watched.data());
        assertEquals("vanilla", watched.brand());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should watch nothing of a message on another channel")
    void watchesOtherChannelsNot() {
      ByteBuf buf = frame("minecraft:register", "warp:probe".getBytes(StandardCharsets.UTF_8));
      try {
        assertNull(ConfigPluginMessage.BRAND_WATCH.watch(buf, VERSION));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should keep 131 bytes of a long brand message, enough to refuse it as a brand")
    void watchesLongBrandTruncated() {
      byte[] data = new byte[300];
      java.util.Arrays.fill(data, (byte) 'x');
      ByteBuf buf = frame(ConfigPluginMessage.BRAND_CHANNEL, data);
      try {
        ConfigPluginMessage watched = ConfigPluginMessage.BRAND_WATCH.watch(buf, VERSION);

        assertNotNull(watched);
        assertEquals(ConfigPluginMessage.MAX_BRAND_BYTES + 3, watched.data().length);
        assertNull(watched.brand(), "longer than a brand can be");
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should keep a brand message of 131 bytes whole")
    void watchesExactly131Bytes() {
      byte[] data = new byte[ConfigPluginMessage.MAX_BRAND_BYTES + 3];
      ByteBuf buf = frame(ConfigPluginMessage.BRAND_CHANNEL, data);
      try {
        ConfigPluginMessage watched = ConfigPluginMessage.BRAND_WATCH.watch(buf, VERSION);

        assertNotNull(watched);
        assertEquals(data.length, watched.data().length);
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should watch nothing of a frame whose channel cannot be read")
    void watchesMalformedNot() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 200); // a channel of 200 bytes, of which none follow
        assertNull(ConfigPluginMessage.BRAND_WATCH.watch(buf, VERSION));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ConfigPing and ConfigPong
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ConfigPing and ConfigPong")
  class PingPong {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_6;

    @ParameterizedTest(name = "id {0}")
    @ValueSource(ints = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x12345678})
    @DisplayName("should roundtrip a ping as one big-endian int")
    void pingRoundtrip(int id) {
      ByteBuf buf = Unpooled.buffer();
      try {
        ConfigPing.CODEC.encode(new ConfigPing(id), buf, VERSION);
        assertEquals(4, buf.readableBytes());
        assertEquals(id, buf.getInt(0));
        assertEquals(new ConfigPing(id), ConfigPing.CODEC.decode(buf, VERSION));
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "id {0}")
    @ValueSource(ints = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x12345678})
    @DisplayName("should roundtrip a pong as one big-endian int")
    void pongRoundtrip(int id) {
      ByteBuf buf = Unpooled.buffer();
      try {
        ConfigPong.CODEC.encode(new ConfigPong(id), buf, VERSION);
        assertEquals(4, buf.readableBytes());
        assertEquals(id, buf.getInt(0));
        assertEquals(new ConfigPong(id), ConfigPong.CODEC.decode(buf, VERSION));
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should watch a pong whole")
    void watchesPong() {
      ByteBuf buf = Unpooled.buffer().writeInt(0x7ead_beef);
      try {
        assertEquals(new ConfigPong(0x7ead_beef), ConfigPong.WATCH.watch(buf, VERSION));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should watch nothing of a pong shorter than an int")
    void watchesShortPongNot() {
      ByteBuf buf = Unpooled.buffer().writeShort(7);
      try {
        assertNull(ConfigPong.WATCH.watch(buf, VERSION));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // KnownPacks
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("KnownPacks")
  class KnownPacksCodec {

    @Test
    @DisplayName("should roundtrip with 2 packs")
    void roundtrip() {
      List<KnownPacks.Pack> packs =
          List.of(
              new KnownPacks.Pack("minecraft", "core", "1.21.4"),
              new KnownPacks.Pack("custom", "mypack", "2.0"));
      KnownPacks original = new KnownPacks(packs);
      ByteBuf buf = Unpooled.buffer();
      try {
        KnownPacks.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        KnownPacks decoded = KnownPacks.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(2, decoded.packs().size());
        assertEquals("minecraft", decoded.packs().get(0).namespace());
        assertEquals("core", decoded.packs().get(0).id());
        assertEquals("1.21.4", decoded.packs().get(0).version());
        assertEquals("custom", decoded.packs().get(1).namespace());
        assertEquals("mypack", decoded.packs().get(1).id());
        assertEquals("2.0", decoded.packs().get(1).version());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should accept 128 packs, as many as vanilla does")
    void acceptsMostPacks() {
      ByteBuf buf = packs(128);
      try {
        assertEquals(
            128, KnownPacks.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4).packs().size());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject more than 128 packs, even when all of them are there")
    void rejectTooManyPacks() {
      ByteBuf buf = packs(129);
      try {
        assertThrows(
            DecoderException.class,
            () -> KnownPacks.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4));
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {129, -1, Integer.MIN_VALUE})
    @DisplayName("should reject a pack count above 128 or below 0")
    void rejectPackCountOutOfRange(int count) {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, count);
        DecoderException rejection =
            assertThrows(
                DecoderException.class,
                () -> KnownPacks.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4));
        assertNull(rejection.getCause(), "rejected by the codec, not by a failure");
      } finally {
        buf.release();
      }
    }

    private static ByteBuf packs(int count) {
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, count);
      for (int i = 0; i < count; i++) {
        McString.write(buf, "minecraft");
        McString.write(buf, "pack" + i);
        McString.write(buf, "1.21.4");
      }
      return buf;
    }
  }

  // ---------------------------------------------------------------------------
  // RegistryData
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("RegistryData")
  class RegistryDataCodec {

    @Test
    @DisplayName("should roundtrip with registry ID and raw entries")
    void roundtrip() {
      byte[] rawEntries = {0x01, 0x02, 0x03, 0x04, 0x05};
      RegistryData original = new RegistryData("minecraft:dimension_type", rawEntries);
      ByteBuf buf = Unpooled.buffer();
      try {
        RegistryData.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        RegistryData decoded = RegistryData.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals("minecraft:dimension_type", decoded.registryId());
        assertArrayEquals(rawEntries, decoded.rawEntries());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ServerData
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ServerData")
  class ServerDataCodec {

    @Test
    @DisplayName("should roundtrip raw data")
    void roundtrip() {
      byte[] rawData = {0x10, 0x20, 0x30, 0x40};
      ServerData original = new ServerData(rawData);
      ByteBuf buf = Unpooled.buffer();
      try {
        ServerData.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        ServerData decoded = ServerData.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertArrayEquals(rawData, decoded.rawData());
      } finally {
        buf.release();
      }
    }
  }
}
