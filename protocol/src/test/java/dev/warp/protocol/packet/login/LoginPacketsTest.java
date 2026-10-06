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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McByteArray;
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

    static Stream<Arguments> signingKeyVersions() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, new byte[0], NOTCH_WITHOUT_UUID),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_2, concat(TRUE, BINARY_UUID), NOTCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signingKeyVersions")
    @DisplayName("should skip the chat signing key a 1.19 to 1.19.2 client sends")
    void skipsSigningKey(
        ProtocolVersion version, byte[] afterSignatureData, LoginStart expectedDecoded) {
      byte[] wire = concat(NOTCH_ON_WIRE, signingKey(), afterSignatureData);

      LoginStart decoded = decode(wire, version);

      assertEquals(expectedDecoded, decoded);
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

    /** Present signature data with a realistic 294-byte RSA public key and 512-byte signature. */
    private static byte[] signingKey() {
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeBoolean(true);
        buf.writeLong(1_656_000_000_000L); // key expiry timestamp
        McByteArray.write(buf, new byte[294]); // public key
        McByteArray.write(buf, new byte[512]); // signature
        return ByteBufUtil.getBytes(buf);
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
      LoginSuccess original = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false);

      LoginSuccess decoded = decode(encode(original, version), version);

      assertEquals(PLAYER_UUID, decoded.uuid(), form + " uuid");
      assertEquals("Notch", decoded.username());
      assertTrue(decoded.properties().isEmpty());
    }

    @ParameterizedTest(name = "{0} uses the {1} form")
    @MethodSource("versionsAcrossUuidBoundaries")
    @DisplayName("should write the uuid in the wire form of each version")
    void writesUuidInVersionForm(ProtocolVersion version, UuidForm form) {
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false);

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
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false);

      byte[] wire = encode(packet, ProtocolVersion.MINECRAFT_1_7_2);

      assertArrayEquals(concat(new byte[] {32}, ascii(UNDASHED_UUID), NOTCH_ON_WIRE), wire);
    }

    @Test
    @DisplayName("should send a 1.12.2 client the uuid as a 36-character dashed string")
    void writesExactDashedStringBytes() {
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false);

      byte[] wire = encode(packet, ProtocolVersion.MINECRAFT_1_12_2);

      assertArrayEquals(concat(new byte[] {36}, ascii(DASHED_UUID), NOTCH_ON_WIRE), wire);
    }

    @Test
    @DisplayName("should send a 1.16 client the uuid as 16 raw bytes")
    void writesExactBinaryBytes() {
      LoginSuccess packet = new LoginSuccess(PLAYER_UUID, "Notch", List.of(), false);

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
      LoginSuccess original = new LoginSuccess(uuid, "jeb_", props, true);
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
    @ValueSource(ints = {759, 766, 768})
    @DisplayName("should keep an unsigned property and an unset strict error handling flag")
    void unsignedPropertyWithoutStrictErrors(int protocol) {
      ProtocolVersion version = versionOf(protocol);
      List<LoginSuccess.Property> props =
          List.of(
              new LoginSuccess.Property("textures", "base64data", null),
              new LoginSuccess.Property("signed", "value", "signature"));
      LoginSuccess original = new LoginSuccess(PLAYER_UUID, "jeb_", props, false);

      LoginSuccess decoded = decode(encode(original, version), version);

      assertEquals(props, decoded.properties());
      assertFalse(decoded.strictErrorHandling());
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

    @Test
    @DisplayName("should write and read back a failed response without data")
    void responseWithoutData() {
      LoginPluginResponse original = new LoginPluginResponse(42, false, null);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginPluginResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertArrayEquals(new byte[] {42, 0}, ByteBufUtil.getBytes(buf));

        LoginPluginResponse decoded =
            LoginPluginResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(42, decoded.messageId());
        assertFalse(decoded.successful());
        assertNull(decoded.data());
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
        assertFalse(buf.isReadable(), "nothing after the verify token before 1.20.5");
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
    @DisplayName("should keep shouldAuthenticate unset from 1.20.5, for offline-mode servers")
    void offlineFrom1205() {
      EncryptionRequest original =
          new EncryptionRequest("", new byte[] {1, 2, 3}, new byte[] {4, 5, 6, 7}, false);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_5);
        EncryptionRequest decoded =
            EncryptionRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);
        assertFalse(decoded.shouldAuthenticate());
        assertFalse(buf.isReadable());
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

    @Test
    @DisplayName("should roundtrip at 1.8")
    void roundtrip18() {
      byte[] sharedSecret = new byte[128];
      byte[] verifyToken = new byte[128];
      sharedSecret[0] = 0x01;
      verifyToken[0] = 0x02;
      EncryptionResponse original = new EncryptionResponse(sharedSecret, verifyToken);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        EncryptionResponse decoded =
            EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertArrayEquals(sharedSecret, decoded.sharedSecret());
        assertArrayEquals(verifyToken, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.7.2 (short-prefixed arrays)")
    void roundtrip17() {
      EncryptionResponse original = new EncryptionResponse(new byte[] {1, 2}, new byte[] {3, 4});
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_7_2);
        EncryptionResponse decoded =
            EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_7_2);
        assertArrayEquals(new byte[] {1, 2}, decoded.sharedSecret());
        assertArrayEquals(new byte[] {3, 4}, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.19 (normal path with boolean prefix)")
    void roundtrip119NormalPath() {
      EncryptionResponse original = new EncryptionResponse(new byte[] {1, 2}, new byte[] {3, 4});
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_19);
        EncryptionResponse decoded =
            EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19);
        assertArrayEquals(new byte[] {1, 2}, decoded.sharedSecret());
        assertArrayEquals(new byte[] {3, 4}, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }

    /** The wire layout of {secret 1 2, token 3 4} on each side of the 1.19 to 1.19.2 prefix. */
    static Stream<Arguments> layouts() {
      byte[] arrays = {2, 1, 2, 2, 3, 4};
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, arrays),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, new byte[] {2, 1, 2, 1, 2, 3, 4}),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_1, new byte[] {2, 1, 2, 1, 2, 3, 4}),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_3, arrays),
          Arguments.of(ProtocolVersion.latest(), arrays));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    @DisplayName("should write the verify token behind a true flag only from 1.19 to 1.19.2")
    void layout(ProtocolVersion version, byte[] wire) {
      EncryptionResponse response = new EncryptionResponse(new byte[] {1, 2}, new byte[] {3, 4});
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(response, buf, version);
        assertArrayEquals(wire, ByteBufUtil.getBytes(buf));

        EncryptionResponse decoded = EncryptionResponse.CODEC.decode(buf, version);
        assertArrayEquals(new byte[] {1, 2}, decoded.sharedSecret());
        assertArrayEquals(new byte[] {3, 4}, decoded.verifyToken());
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should read the salt-signed 1.19 variant as an empty verify token, to its end")
    void saltSigned119() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McByteArray.write(buf, new byte[] {1, 2});
        buf.writeBoolean(false); // no verify token: a salt and a message signature instead
        buf.writeLong(0x0102_0304_0506_0708L);
        McByteArray.write(buf, new byte[] {9, 9, 9});

        EncryptionResponse decoded =
            EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19);

        assertArrayEquals(new byte[] {1, 2}, decoded.sharedSecret());
        assertArrayEquals(new byte[0], decoded.verifyToken());
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginAcknowledged
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginAcknowledged")
  class LoginAcknowledgedCodec {

    @Test
    @DisplayName("should read and write an empty packet")
    void empty() {
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginAcknowledged.CODEC.encode(
            new LoginAcknowledged(), buf, ProtocolVersion.MINECRAFT_1_20_2);
        assertFalse(buf.isReadable());
        assertEquals(
            new LoginAcknowledged(),
            LoginAcknowledged.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_2));
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
    @DisplayName("should reject more than 64 properties, even when all of them are there")
    void rejectTooManyProperties() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McUuid.write(buf, PLAYER_UUID);
        McString.write(buf, "TestPlayer", 16);
        VarInt.write(buf, 65);
        for (int i = 0; i < 65; i++) {
          McString.write(buf, "p" + i);
          McString.write(buf, "v");
          buf.writeBoolean(false);
        }
        buf.writeBoolean(false); // strict error handling
        assertThrows(
            DecoderException.class,
            () -> LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should accept exactly 64 properties")
    void acceptsSixtyFourProperties() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McUuid.write(buf, PLAYER_UUID);
        McString.write(buf, "TestPlayer", 16);
        VarInt.write(buf, 64);
        for (int i = 0; i < 64; i++) {
          McString.write(buf, "p" + i);
          McString.write(buf, "v");
          buf.writeBoolean(false);
        }
        buf.writeBoolean(false); // strict error handling

        LoginSuccess decoded = LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);

        assertEquals(64, decoded.properties().size());
        assertFalse(buf.isReadable());
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
          "f81d4fae-7dec-11d0-a765-00a0c91e6bf6-",
          // A digit in place of each dash: the hex digits around still parse.
          "f81d4fae07dec-11d0-a765-00a0c91e6bf6",
          "f81d4fae-7dec011d0-a765-00a0c91e6bf6",
          "f81d4fae-7dec-11d00a765-00a0c91e6bf6",
          "f81d4fae-7dec-11d0-a765000a0c91e6bf6"
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
