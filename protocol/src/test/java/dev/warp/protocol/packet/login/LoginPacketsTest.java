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
package dev.warp.protocol.packet.login;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Login packet codecs")
class LoginPacketsTest {

  /** Both halves have their sign bit set, so a signed/unsigned slip would show up. */
  private static final UUID PLAYER_UUID = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");

  private static final byte[] BINARY_UUID =
      HexFormat.of().parseHex("f81d4fae7dec11d0a76500a0c91e6bf6");

  /** The VarInt-prefixed username {@code "Notch"} as it appears on the wire. */
  private static final byte[] NOTCH_ON_WIRE = concat(new byte[] {5}, ascii("Notch"));

  private static byte[] ascii(String str) {
    return str.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static byte[] filled(int length, int value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  // ---------------------------------------------------------------------------
  // LoginStart
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginStart")
  class LoginStartCodec {

    private static final byte[] FALSE = {0};
    private static final byte[] TRUE = {1};

    private static final LoginStart NOTCH = new LoginStart("Notch", PLAYER_UUID);
    private static final LoginStart NOTCH_WITHOUT_UUID = new LoginStart("Notch", null);

    /**
     * Every Login Start layout, on each side of every layout change: the bytes {@link #NOTCH}
     * encodes to, and what those bytes decode back to.
     */
    static Stream<Arguments> layoutPerVersion() {
      return Stream.of(
          // name
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, NOTCH_ON_WIRE, NOTCH_WITHOUT_UUID),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, NOTCH_ON_WIRE, NOTCH_WITHOUT_UUID),
          // name, signature data (absent)
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_19, concat(NOTCH_ON_WIRE, FALSE), NOTCH_WITHOUT_UUID),
          // name, signature data (absent), optional uuid; 1.19.1 and 1.19.2 share protocol 760
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_19_1,
              concat(NOTCH_ON_WIRE, FALSE, TRUE, BINARY_UUID),
              NOTCH),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_19_2,
              concat(NOTCH_ON_WIRE, FALSE, TRUE, BINARY_UUID),
              NOTCH),
          // name, optional uuid
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_19_3, concat(NOTCH_ON_WIRE, TRUE, BINARY_UUID), NOTCH),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_20_1, concat(NOTCH_ON_WIRE, TRUE, BINARY_UUID), NOTCH),
          // name, uuid
          Arguments.of(ProtocolVersion.MINECRAFT_1_20_2, concat(NOTCH_ON_WIRE, BINARY_UUID), NOTCH),
          Arguments.of(ProtocolVersion.latest(), concat(NOTCH_ON_WIRE, BINARY_UUID), NOTCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layoutPerVersion")
    @DisplayName("should roundtrip through the exact layout of each version")
    void roundtripsThroughVersionLayout(
        ProtocolVersion version, byte[] expectedWire, LoginStart expectedDecoded) {
      byte[] wire = encode(NOTCH, version);
      LoginStart decoded = decode(wire, version);

      assertArrayEquals(expectedWire, wire);
      assertEquals(expectedDecoded, decoded);
    }

    /**
     * A realistic profile key: a 2048-bit RSA key is 294 bytes of DER, and Mojang's 4096-bit key
     * signs it with 512 bytes. Distinct fillers catch a swap of the two arrays.
     */
    private static final LoginStart.ProfilePublicKey KEY =
        new LoginStart.ProfilePublicKey(1_656_000_000_000L, filled(294, 0x30), filled(512, 0x5A));

    /** {@link #KEY} on the wire after its presence flag, written out by hand. */
    private static final byte[] KEY_ON_WIRE =
        concat(
            HexFormat.of().parseHex("00000181914ab000"), // expiry, big-endian long
            new byte[] {(byte) 0xA6, 0x02}, // VarInt 294
            filled(294, 0x30),
            new byte[] {(byte) 0x80, 0x04}, // VarInt 512
            filled(512, 0x5A));

    /** The bytes of a login with {@link #KEY}, where the field exists (1.19 to 1.19.2). */
    static Stream<Arguments> profileKeyLayouts() {
      return Stream.of(
          // name, key (present)
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_19,
              new LoginStart("Notch", KEY, null),
              concat(NOTCH_ON_WIRE, TRUE, KEY_ON_WIRE)),
          // name, key (present), uuid (present)
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_19_1,
              new LoginStart("Notch", KEY, PLAYER_UUID),
              concat(NOTCH_ON_WIRE, TRUE, KEY_ON_WIRE, TRUE, BINARY_UUID)),
          Arguments.of(
              ProtocolVersion.MINECRAFT_1_19_2,
              new LoginStart("Notch", KEY, PLAYER_UUID),
              concat(NOTCH_ON_WIRE, TRUE, KEY_ON_WIRE, TRUE, BINARY_UUID)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileKeyLayouts")
    @DisplayName("should roundtrip the profile key of a 1.19 to 1.19.2 client byte for byte")
    void roundtripsProfileKey(ProtocolVersion version, LoginStart login, byte[] expectedWire) {
      byte[] wire = encode(login, version);
      LoginStart decoded = decode(expectedWire, version);

      assertArrayEquals(expectedWire, wire);
      assertEquals(login.name(), decoded.name());
      assertEquals(login.playerUuid(), decoded.playerUuid());
      LoginStart.ProfilePublicKey key = decoded.profileKey();
      assertNotNull(key, "profile key");
      assertEquals(KEY.expiresAt(), key.expiresAt());
      assertArrayEquals(KEY.publicKey(), key.publicKey());
      assertArrayEquals(KEY.keySignature(), key.keySignature());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsWithoutProfileKey")
    @DisplayName("should leave the profile key out where the field does not exist")
    void dropsProfileKeyOutside119(ProtocolVersion version) {
      byte[] withKey = encode(new LoginStart("Notch", KEY, PLAYER_UUID), version);

      assertArrayEquals(encode(NOTCH, version), withKey);
    }

    static Stream<ProtocolVersion> versionsWithoutProfileKey() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_18_2,
          ProtocolVersion.MINECRAFT_1_19_3,
          ProtocolVersion.MINECRAFT_1_20_2);
    }

    /** A key or signature one byte longer than vanilla reads, after the expiry. */
    static Stream<Arguments> oversizedKeys() {
      return Stream.of(
          Arguments.of(
              "513-byte key",
              concat(new byte[] {(byte) 0x81, 0x04}, new byte[513], new byte[] {0})),
          Arguments.of(
              "4097-byte signature",
              concat(new byte[] {0}, new byte[] {(byte) 0x81, 0x20}, new byte[4097])));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedKeys")
    @DisplayName("should refuse a profile key larger than vanilla reads")
    void rejectsOversizedKey(String what, byte[] arrays) {
      byte[] wire = concat(NOTCH_ON_WIRE, TRUE, new byte[Long.BYTES], arrays);
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        assertThrows(
            DecoderException.class,
            () -> LoginStart.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19_2),
            what);
      } finally {
        buf.release();
      }
    }

    /** The optional uuid flagged as absent, on each side of the signature data removal. */
    static Stream<Arguments> absentUuidLayouts() {
      return Stream.of(
          // name, signature data (absent), uuid (absent)
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_2, concat(NOTCH_ON_WIRE, FALSE, FALSE)),
          // name, uuid (absent)
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_3, concat(NOTCH_ON_WIRE, FALSE)),
          Arguments.of(ProtocolVersion.MINECRAFT_1_20_1, concat(NOTCH_ON_WIRE, FALSE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("absentUuidLayouts")
    @DisplayName("should flag the uuid as absent when 1.19.1 to 1.20.1 has none, and read it back")
    void roundtripsAbsentUuid(ProtocolVersion version, byte[] expectedWire) {
      byte[] wire = encode(NOTCH_WITHOUT_UUID, version);
      LoginStart decoded = decode(wire, version);

      assertArrayEquals(expectedWire, wire);
      assertEquals(NOTCH_WITHOUT_UUID, decoded);
    }

    @Test
    @DisplayName("should refuse to write a 1.20.2+ login without a uuid")
    void rejectsMissingUuidFrom1202() {
      ByteBuf buf = Unpooled.buffer();
      try {
        assertThrows(
            IllegalStateException.class,
            () ->
                LoginStart.CODEC.encode(NOTCH_WITHOUT_UUID, buf, ProtocolVersion.MINECRAFT_1_20_2));
      } finally {
        buf.release();
      }
    }

    private static byte[] encode(LoginStart packet, ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginStart.CODEC.encode(packet, buf, version);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private static LoginStart decode(byte[] wire, ProtocolVersion version) {
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        LoginStart decoded = LoginStart.CODEC.decode(buf, version);
        assertEquals(0, buf.readableBytes(), "bytes left after LoginStart");
        return decoded;
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginSuccess
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginSuccess")
  class LoginSuccessCodec {

    private static final String DASHED_UUID = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6";
    private static final String UNDASHED_UUID = "f81d4fae7dec11d0a76500a0c91e6bf6";

    private static final UUID SESSION_ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    private static final LoginSuccess NOTCH_WITH_SKIN =
        new LoginSuccess(
            PLAYER_UUID,
            "Notch",
            List.of(new LoginSuccess.Property("textures", "dGV4dHVyZXM=", "c2ln")),
            false,
            SESSION_ID);

    /**
     * {@link #NOTCH_WITH_SKIN} as vanilla writes it from 26.2, with Mojang's own codec ({@code
     * ClientboundLoginFinishedPacket.STREAM_CODEC}, run from the 26.2 and 26.3 server jars): uuid,
     * name, one property (name, value, signature present, signature), then the play session ID.
     */
    private static final String MOJANG_FROM_26_2 =
        "f81d4fae7dec11d0a76500a0c91e6bf6"
            + "054e6f746368"
            + "01"
            + "087465787475726573"
            + "0c64475634644856795a584d3d"
            + "010463326c6e"
            + "00112233445566778899aabbccddeeff";

    /** How the player UUID is laid out on the wire. */
    enum UuidForm {
      UNDASHED,
      DASHED,
      BINARY
    }

    static Stream<Arguments> versionsAcrossUuidBoundaries() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_2, UuidForm.UNDASHED),
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, UuidForm.DASHED),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, UuidForm.DASHED),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, UuidForm.DASHED),
          Arguments.of(ProtocolVersion.MINECRAFT_1_15_2, UuidForm.DASHED),
          Arguments.of(ProtocolVersion.MINECRAFT_1_16, UuidForm.BINARY),
          // 1.16.4 and 1.16.5 share protocol 754
          Arguments.of(ProtocolVersion.MINECRAFT_1_16_4, UuidForm.BINARY),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, UuidForm.BINARY),
          Arguments.of(ProtocolVersion.MINECRAFT_1_20_5, UuidForm.BINARY),
          Arguments.of(ProtocolVersion.latest(), UuidForm.BINARY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsAcrossUuidBoundaries")
    @DisplayName("should roundtrip the uuid and username on each side of every uuid format change")
    void roundtripAcrossUuidBoundaries(ProtocolVersion version, UuidForm form) {
      LoginSuccess original = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false, SESSION_ID);

      LoginSuccess decoded = decode(encode(original, version), version);

      assertEquals(PLAYER_UUID, decoded.uuid(), form + " uuid");
      assertEquals("Notch", decoded.username());
      assertTrue(decoded.properties().isEmpty());
    }

    @ParameterizedTest(name = "{0} uses the {1} form")
    @MethodSource("versionsAcrossUuidBoundaries")
    @DisplayName("should write the uuid in the wire form of each version")
    void writesUuidInVersionForm(ProtocolVersion version, UuidForm form) {
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false, SESSION_ID);

      byte[] wire = encode(packet, version);

      byte[] expected =
          switch (form) {
            case UNDASHED -> concat(new byte[] {32}, ascii(UNDASHED_UUID));
            case DASHED -> concat(new byte[] {36}, ascii(DASHED_UUID));
            case BINARY -> BINARY_UUID;
          };
      assertArrayEquals(expected, Arrays.copyOf(wire, expected.length));
    }

    @Test
    @DisplayName("should send a 1.7.2 client the uuid as a 32-character string without dashes")
    void writesExactUndashedStringBytes() {
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false, SESSION_ID);

      byte[] wire = encode(packet, ProtocolVersion.MINECRAFT_1_7_2);

      assertArrayEquals(concat(new byte[] {32}, ascii(UNDASHED_UUID), NOTCH_ON_WIRE), wire);
    }

    @Test
    @DisplayName("should send a 1.12.2 client the uuid as a 36-character dashed string")
    void writesExactDashedStringBytes() {
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false, SESSION_ID);

      byte[] wire = encode(packet, ProtocolVersion.MINECRAFT_1_12_2);

      assertArrayEquals(concat(new byte[] {36}, ascii(DASHED_UUID), NOTCH_ON_WIRE), wire);
    }

    @Test
    @DisplayName("should send a 1.16 client the uuid as 16 raw bytes")
    void writesExactBinaryBytes() {
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false, SESSION_ID);

      byte[] wire = encode(packet, ProtocolVersion.MINECRAFT_1_16);

      assertArrayEquals(concat(BINARY_UUID, NOTCH_ON_WIRE), wire);
    }

    @ParameterizedTest(name = "protocol {0}")
    @ValueSource(ints = {5, 47, 340, 578})
    @DisplayName("should decode the dashed uuid string a 1.7.6 to 1.15.2 backend sends")
    void decodesBackendDashedString(int protocol) {
      ProtocolVersion version = versionOf(protocol);
      byte[] wire = concat(new byte[] {36}, ascii(DASHED_UUID), NOTCH_ON_WIRE);

      LoginSuccess decoded = decode(wire, version);

      assertEquals(PLAYER_UUID, decoded.uuid());
      assertEquals("Notch", decoded.username());
    }

    @Test
    @DisplayName("should accept an undashed uuid string from a 1.7.6 to 1.15.2 server")
    void acceptsUndashedStringAfter176() {
      byte[] wire = concat(new byte[] {32}, ascii(UNDASHED_UUID), NOTCH_ON_WIRE);

      LoginSuccess decoded = decode(wire, ProtocolVersion.MINECRAFT_1_8);

      assertEquals(PLAYER_UUID, decoded.uuid());
    }

    @Test
    @DisplayName("should accept a dashed uuid string from a 1.7.2 server")
    void acceptsDashedStringAt172() {
      byte[] wire = concat(new byte[] {36}, ascii(DASHED_UUID), NOTCH_ON_WIRE);

      LoginSuccess decoded = decode(wire, ProtocolVersion.MINECRAFT_1_7_2);

      assertEquals(PLAYER_UUID, decoded.uuid());
    }

    @Test
    @DisplayName("should accept an uppercase uuid string")
    void acceptsUppercaseString() {
      byte[] wire =
          concat(new byte[] {36}, ascii(DASHED_UUID.toUpperCase(Locale.ROOT)), NOTCH_ON_WIRE);

      LoginSuccess decoded = decode(wire, ProtocolVersion.MINECRAFT_1_15_2);

      assertEquals(PLAYER_UUID, decoded.uuid());
    }

    @Test
    @DisplayName("should roundtrip for 1.20.5+ (properties + strictErrorHandling)")
    void roundtrip1205() {
      UUID uuid = UUID.randomUUID();
      List<LoginSuccess.Property> props =
          List.of(new LoginSuccess.Property("textures", "base64data", "signature"));
      LoginSuccess original = new LoginSuccess(uuid, "jeb_", props, true, null);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginSuccess.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_5);
        LoginSuccess decoded = LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);
        assertEquals(uuid, decoded.uuid());
        assertEquals("jeb_", decoded.username());
        assertEquals(1, decoded.properties().size());
        assertEquals("textures", decoded.properties().get(0).name());
        assertEquals("signature", decoded.properties().get(0).signature());
        assertTrue(decoded.strictErrorHandling());
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fromTheSessionId")
    @DisplayName("should write the exact bytes of Mojang's codec, the play session ID last")
    void writesMojangBytesFrom262(ProtocolVersion version) {
      byte[] wire = encode(NOTCH_WITH_SKIN, version);

      assertEquals(MOJANG_FROM_26_2, HexFormat.of().formatHex(wire));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fromTheSessionId")
    @DisplayName("should read every field of the bytes of Mojang's codec")
    void readsMojangBytesFrom262(ProtocolVersion version) {
      LoginSuccess decoded = decode(HexFormat.of().parseHex(MOJANG_FROM_26_2), version);

      assertEquals(NOTCH_WITH_SKIN, decoded);
    }

    /**
     * The Login Success of a vanilla server, as it arrived on the wire, packet id excluded: a bot
     * named {@code WarpCapture} logging in to the vanilla 26.2 and 26.3 servers, offline and
     * uncompressed. Each server draws its own play session ID.
     */
    static Stream<Arguments> vanillaServerCaptures() {
      return Stream.of(
          Arguments.of(
              ProtocolVersion.MINECRAFT_26_2,
              "c25a55d31da930c0b9b6e004bd8fb3c80b576172704361707475726500"
                  + "996f7370c5674490a666d3900fcd81fc",
              UUID.fromString("996f7370-c567-4490-a666-d3900fcd81fc")),
          Arguments.of(
              ProtocolVersion.MINECRAFT_26_3,
              "c25a55d31da930c0b9b6e004bd8fb3c80b576172704361707475726500"
                  + "80d927cc4f1b4ba9ba021d483a72d41c",
              UUID.fromString("80d927cc-4f1b-4ba9-ba02-1d483a72d41c")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vanillaServerCaptures")
    @DisplayName("should read a vanilla server's packet to its last byte and write it back as is")
    void keepsTheBytesOfAVanillaServer(ProtocolVersion version, String wireHex, UUID sessionId) {
      byte[] wire = HexFormat.of().parseHex(wireHex);

      LoginSuccess decoded = decode(wire, version);

      assertEquals("WarpCapture", decoded.username());
      assertTrue(decoded.properties().isEmpty());
      assertEquals(sessionId, decoded.sessionId());
      assertArrayEquals(wire, encode(decoded, version));
    }

    @Test
    @DisplayName("should have no play session ID before 26.2")
    void noSessionIdBefore262() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_26_1;
      String withoutSessionId = MOJANG_FROM_26_2.substring(0, MOJANG_FROM_26_2.length() - 32);

      byte[] wire = encode(NOTCH_WITH_SKIN, version);
      LoginSuccess decoded = decode(HexFormat.of().parseHex(withoutSessionId), version);

      assertEquals(withoutSessionId, HexFormat.of().formatHex(wire));
      assertNull(decoded.sessionId());
    }

    @Test
    @DisplayName("should refuse to write a 26.2 packet without a play session ID")
    void requiresSessionIdFrom262() {
      LoginSuccess withoutSessionId =
          new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false, null);

      assertThrows(
          IllegalStateException.class,
          () -> encode(withoutSessionId, ProtocolVersion.MINECRAFT_26_2));
    }

    static Stream<ProtocolVersion> fromTheSessionId() {
      return Stream.of(ProtocolVersion.MINECRAFT_26_2, ProtocolVersion.MINECRAFT_26_3);
    }

    private static byte[] encode(LoginSuccess packet, ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginSuccess.CODEC.encode(packet, buf, version);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private static LoginSuccess decode(byte[] wire, ProtocolVersion version) {
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        LoginSuccess decoded = LoginSuccess.CODEC.decode(buf, version);
        assertEquals(0, buf.readableBytes(), "bytes left after LoginSuccess");
        return decoded;
      } finally {
        buf.release();
      }
    }

    private static ProtocolVersion versionOf(int protocol) {
      ProtocolVersion version = ProtocolVersion.byProtocolId(protocol);
      if (version == null) {
        throw new IllegalArgumentException("Unknown protocol " + protocol);
      }
      return version;
    }
  }

  // ---------------------------------------------------------------------------
  // SetCompression
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("SetCompression")
  class SetCompressionCodec {

    @Test
    @DisplayName("should roundtrip compression threshold")
    void roundtrip() {
      SetCompression original = new SetCompression(256);
      ByteBuf buf = Unpooled.buffer();
      try {
        SetCompression.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        SetCompression decoded = SetCompression.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(256, decoded.threshold());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginDisconnect
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginDisconnect")
  class LoginDisconnectCodec {

    private static final String REASON_JSON = "{\"text\":\"Invalid username\"}";

    /** {@link #REASON_JSON} on the wire: its UTF-8 length as a VarInt (27), then its bytes. */
    private static final byte[] REASON_WIRE =
        ("\u001B" + REASON_JSON).getBytes(StandardCharsets.UTF_8);

    static Stream<ProtocolVersion> allVersions() {
      return ProtocolVersion.values().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVersions")
    @DisplayName("should write a plain text reason as a VarInt-prefixed JSON string")
    void writesPrefixedJson(ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginDisconnect.CODEC.encode(LoginDisconnect.ofPlainText("Invalid username"), buf, version);

        assertArrayEquals(REASON_WIRE, ByteBufUtil.getBytes(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should write a translated reason as a VarInt-prefixed translatable component")
    void writesTranslatable() {
      String json = "{\"translate\":\"multiplayer.disconnect.invalid_public_key_signature\"}";
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginDisconnect.CODEC.encode(
            LoginDisconnect.ofTranslation("multiplayer.disconnect.invalid_public_key_signature"),
            buf,
            ProtocolVersion.MINECRAFT_1_19_2);

        // 67 bytes of JSON: a one-byte VarInt length.
        assertArrayEquals(concat(new byte[] {67}, ascii(json)), ByteBufUtil.getBytes(buf));
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVersions")
    @DisplayName("should read a backend's reason as its JSON, without the length prefix")
    void readsJson(ProtocolVersion version) {
      ByteBuf buf = Unpooled.wrappedBuffer(REASON_WIRE);

      LoginDisconnect decoded = LoginDisconnect.CODEC.decode(buf, version);

      assertEquals(REASON_JSON, decoded.reason());
      assertFalse(buf.isReadable());
    }

    @Test
    @DisplayName("should reject a reason whose length runs past the packet")
    void rejectsUnprefixedJson() {
      // What Warp used to send: the JSON without its length, so '{' (123) reads as the length.
      ByteBuf buf = Unpooled.wrappedBuffer(REASON_JSON.getBytes(StandardCharsets.UTF_8));

      assertThrows(
          DecoderException.class,
          () -> LoginDisconnect.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8));
    }

    /** The longest reason vanilla reads: 32 767 characters until 1.13.2, 262 144 from 1.14. */
    static Stream<Arguments> versionsAcrossLengthBoundary() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_2, 32_767),
          Arguments.of(ProtocolVersion.MINECRAFT_1_13, 32_767),
          Arguments.of(ProtocolVersion.MINECRAFT_1_13_2, 32_767),
          Arguments.of(ProtocolVersion.MINECRAFT_1_14, 262_144),
          Arguments.of(ProtocolVersion.MINECRAFT_1_20_3, 262_144),
          Arguments.of(ProtocolVersion.MINECRAFT_26_1, 262_144));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("versionsAcrossLengthBoundary")
    @DisplayName("should write a reason as long as the client reads and refuse a longer one")
    void writeLengthLimit(ProtocolVersion version, int maxLength) {
      String longest = "x".repeat(maxLength);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginDisconnect.CODEC.encode(new LoginDisconnect(longest), buf, version);

        assertArrayEquals(prefixedString(longest), ByteBufUtil.getBytes(buf));
        assertThrows(
            IllegalArgumentException.class,
            () -> LoginDisconnect.CODEC.encode(new LoginDisconnect(longest + "x"), buf, version));
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("versionsAcrossLengthBoundary")
    @DisplayName("should read a reason as long as vanilla writes and reject a longer one")
    void readLengthLimit(ProtocolVersion version, int maxLength) {
      String longest = "x".repeat(maxLength);

      LoginDisconnect decoded =
          LoginDisconnect.CODEC.decode(Unpooled.wrappedBuffer(prefixedString(longest)), version);

      assertEquals(longest, decoded.reason());
      assertThrows(
          DecoderException.class,
          () ->
              LoginDisconnect.CODEC.decode(
                  Unpooled.wrappedBuffer(prefixedString(longest + "x")), version));
    }

    /** A protocol string written out independently of the codec: VarInt length, then UTF-8. */
    private static byte[] prefixedString(String text) {
      byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, utf8.length);
        buf.writeBytes(utf8);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginPluginRequest / Response
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginPlugin")
  class LoginPluginCodec {

    @Test
    @DisplayName("should roundtrip login plugin request")
    void requestRoundtrip() {
      byte[] data = {0x01, 0x02, 0x03};
      LoginPluginRequest original = new LoginPluginRequest(42, "velocity:player_info", data);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginPluginRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        LoginPluginRequest decoded =
            LoginPluginRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(42, decoded.messageId());
        assertEquals("velocity:player_info", decoded.channel());
        assertArrayEquals(data, decoded.data());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip login plugin response with data")
    void responseWithData() {
      byte[] data = {0x04, 0x05};
      LoginPluginResponse original = new LoginPluginResponse(42, true, data);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginPluginResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        LoginPluginResponse decoded =
            LoginPluginResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(42, decoded.messageId());
        assertTrue(decoded.successful());
        assertArrayEquals(data, decoded.data());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // EncryptionRequest
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("EncryptionRequest")
  class EncryptionRequestCodec {

    @Test
    @DisplayName("should roundtrip at 1.8 without shouldAuthenticate")
    void roundtrip18() {
      byte[] publicKey = new byte[162];
      byte[] verifyToken = new byte[4];
      publicKey[0] = 0x30;
      verifyToken[0] = 0x01;
      EncryptionRequest original = new EncryptionRequest("", publicKey, verifyToken, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        EncryptionRequest decoded =
            EncryptionRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertEquals("", decoded.serverId());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertArrayEquals(verifyToken, decoded.verifyToken());
        assertTrue(decoded.shouldAuthenticate());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.7.2 (short-prefixed arrays)")
    void roundtrip17() {
      EncryptionRequest original =
          new EncryptionRequest("", new byte[] {1, 2, 3}, new byte[] {4, 5, 6, 7}, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_7_2);
        EncryptionRequest decoded =
            EncryptionRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_7_2);
        assertEquals("", decoded.serverId());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.publicKey());
        assertArrayEquals(new byte[] {4, 5, 6, 7}, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip at 1.20.5 with shouldAuthenticate=true")
    void roundtrip1205() {
      byte[] publicKey = new byte[162];
      byte[] verifyToken = new byte[4];
      publicKey[0] = 0x30;
      verifyToken[0] = 0x02;
      EncryptionRequest original = new EncryptionRequest("", publicKey, verifyToken, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_5);
        EncryptionRequest decoded =
            EncryptionRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);
        assertEquals("", decoded.serverId());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertArrayEquals(verifyToken, decoded.verifyToken());
        assertTrue(decoded.shouldAuthenticate());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // EncryptionResponse
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("EncryptionResponse")
  class EncryptionResponseCodec {

    /** What a 1024-bit server key encrypts the shared secret and the verify token to. */
    private static final byte[] SECRET = filled(128, 0x11);

    private static final byte[] TOKEN = filled(128, 0x22);

    /** What a 2048-bit player key signs the verify token to. */
    private static final byte[] SIGNATURE = filled(256, 0x33);

    /** The sign bit is set, so a signed/unsigned slip would show up. */
    private static final long SALT = 0x8877665544332211L;

    private static final byte[] VARINT_128 = {(byte) 0x80, 0x01};
    private static final byte[] VARINT_256 = {(byte) 0x80, 0x02};

    private static final EncryptionResponse ENCRYPTED =
        new EncryptionResponse(SECRET, new EncryptionResponse.EncryptedToken(TOKEN));

    private static final EncryptionResponse SIGNED =
        new EncryptionResponse(SECRET, new EncryptionResponse.SignedToken(SALT, SIGNATURE));

    /** The bytes of {@link #ENCRYPTED} on each side of every layout change. */
    static Stream<Arguments> encryptedLayouts() {
      byte[] shortPrefixed =
          concat(new byte[] {0, (byte) 128}, SECRET, new byte[] {0, (byte) 128}, TOKEN);
      byte[] varIntPrefixed = concat(VARINT_128, SECRET, VARINT_128, TOKEN);
      byte[] withFlag = concat(VARINT_128, SECRET, new byte[] {1}, VARINT_128, TOKEN);
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_2, shortPrefixed),
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, shortPrefixed),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, varIntPrefixed),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, varIntPrefixed),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, withFlag),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_1, withFlag),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_2, withFlag),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_3, varIntPrefixed),
          Arguments.of(ProtocolVersion.latest(), varIntPrefixed));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encryptedLayouts")
    @DisplayName(
        "should roundtrip an encrypted verify token through the exact layout of each version")
    void roundtripsEncryptedToken(ProtocolVersion version, byte[] expectedWire) {
      byte[] wire = encode(ENCRYPTED, version);
      EncryptionResponse decoded = decode(expectedWire, version);

      assertArrayEquals(expectedWire, wire);
      assertArrayEquals(SECRET, decoded.sharedSecret());
      EncryptionResponse.EncryptedToken token =
          assertInstanceOf(EncryptionResponse.EncryptedToken.class, decoded.verifyToken());
      assertArrayEquals(TOKEN, token.encrypted());
    }

    static Stream<ProtocolVersion> signedTokenVersions() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_19,
          ProtocolVersion.MINECRAFT_1_19_1,
          ProtocolVersion.MINECRAFT_1_19_2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signedTokenVersions")
    @DisplayName("should roundtrip the salt and signature of a 1.19 to 1.19.2 client byte for byte")
    void roundtripsSignedToken(ProtocolVersion version) {
      // shared secret, false (no encrypted token), salt as a big-endian long, signature
      byte[] expectedWire =
          concat(
              VARINT_128,
              SECRET,
              new byte[] {0},
              HexFormat.of().parseHex("8877665544332211"),
              VARINT_256,
              SIGNATURE);

      byte[] wire = encode(SIGNED, version);
      EncryptionResponse decoded = decode(expectedWire, version);

      assertArrayEquals(expectedWire, wire);
      assertArrayEquals(SECRET, decoded.sharedSecret());
      EncryptionResponse.SignedToken token =
          assertInstanceOf(EncryptionResponse.SignedToken.class, decoded.verifyToken());
      assertEquals(SALT, token.salt());
      assertArrayEquals(SIGNATURE, token.signature());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsWithoutSignedToken")
    @DisplayName("should refuse to write a signed verify token where the protocol has none")
    void rejectsSignedTokenOutside119(ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        assertThrows(
            IllegalStateException.class,
            () -> EncryptionResponse.CODEC.encode(SIGNED, buf, version));
      } finally {
        buf.release();
      }
    }

    static Stream<ProtocolVersion> versionsWithoutSignedToken() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_7_2,
          ProtocolVersion.MINECRAFT_1_18_2,
          ProtocolVersion.MINECRAFT_1_19_3,
          ProtocolVersion.latest());
    }

    @Test
    @DisplayName("should refuse a signature longer than 256 bytes")
    void rejectsOversizedSignature() {
      byte[] wire =
          concat(
              VARINT_128,
              SECRET,
              new byte[] {0},
              new byte[Long.BYTES],
              new byte[] {(byte) 0x81, 0x02}, // VarInt 257
              new byte[257]);
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        assertThrows(
            DecoderException.class,
            () -> EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19_2));
      } finally {
        buf.release();
      }
    }

    private static byte[] encode(EncryptionResponse packet, ProtocolVersion version) {
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(packet, buf, version);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private static EncryptionResponse decode(byte[] wire, ProtocolVersion version) {
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        EncryptionResponse decoded = EncryptionResponse.CODEC.decode(buf, version);
        assertEquals(0, buf.readableBytes(), "bytes left after EncryptionResponse");
        return decoded;
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginSuccess (error cases)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginSuccess error handling")
  class LoginSuccessErrors {

    @Test
    @DisplayName("should reject more than 64 properties")
    void rejectTooManyProperties() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // Write UUID (128-bit)
        McUuid.write(buf, UUID.randomUUID());
        // Write username
        McString.write(buf, "TestPlayer", 16);
        // Write property count > 64
        VarInt.write(buf, 65);
        assertThrows(
            DecoderException.class,
            () -> LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5));
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = {
          "",
          "not-a-uuid",
          "f81d4fae7dec11d0a76500a0c91e6bf",
          "f81d4fae7dec11d0a76500a0c91e6bfg",
          "f81d4fae-7dec-11d0-a765-00a0c91e6bfg",
          "f81d4fae7-dec-11d0-a765-00a0c91e6bf6",
          "+81d4fae-7dec-11d0-a765-00a0c91e6bf6",
          "f81d4fae-7dec-11d0-a765-00a0c91e6bf6-"
        })
    @DisplayName("should reject a malformed uuid string before 1.16")
    void rejectMalformedUuidString(String uuid) {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, uuid);
        McString.write(buf, "Notch", 16);
        assertThrows(
            DecoderException.class,
            () -> LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_12_2));
      } finally {
        buf.release();
      }
    }
  }
}
