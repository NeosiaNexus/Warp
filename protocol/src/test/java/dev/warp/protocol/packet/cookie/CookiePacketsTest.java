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
package dev.warp.protocol.packet.cookie;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;

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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Cookie packet codecs")
class CookiePacketsTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  // ---------------------------------------------------------------------------
  // CookieRequest
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CookieRequest")
  class CookieRequestCodec {

    @Test
    @DisplayName("should write the key as a protocol string")
    void wireFormat() {
      byte[] wire = encode(CookieRequest.CODEC, new CookieRequest("primemc:session"));

      assertArrayEquals(string("primemc:session"), wire);
    }

    @Test
    @DisplayName("should read back the key exactly as written, without its namespace if omitted")
    void keepsKeyAsWritten() {
      CookieRequest decoded = decode(CookieRequest.CODEC, string("session"));

      assertEquals("session", decoded.key());
    }

    @Test
    @DisplayName("should refuse to decode a key that is not an identifier")
    void refusesInvalidKey() {
      assertThrows(DecoderException.class, () -> decode(CookieRequest.CODEC, string("Not Valid")));
    }

    @Test
    @DisplayName("should refuse to be created with a key that is not an identifier")
    void refusesInvalidKeyOnCreation() {
      assertThrows(IllegalArgumentException.class, () -> new CookieRequest("UPPER:case"));
    }
  }

  // ---------------------------------------------------------------------------
  // CookieResponse
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CookieResponse")
  class CookieResponseCodec {

    @Test
    @DisplayName("should write the key, true, then the length-prefixed payload")
    void wireFormatWithPayload() {
      byte[] wire =
          encode(CookieResponse.CODEC, new CookieResponse("minecraft:a", new byte[] {7, 8}));

      assertArrayEquals(concat(string("minecraft:a"), new byte[] {1, 2, 7, 8}), wire);
    }

    @Test
    @DisplayName("should write the key and false when the client holds no cookie")
    void wireFormatWithoutPayload() {
      byte[] wire = encode(CookieResponse.CODEC, new CookieResponse("minecraft:a", null));

      assertArrayEquals(concat(string("minecraft:a"), new byte[] {0}), wire);
    }

    @Test
    @DisplayName("should keep an empty cookie apart from no cookie at all")
    void emptyIsNotAbsent() {
      CookieResponse empty = new CookieResponse("minecraft:a", new byte[0]);

      byte[] wire = encode(CookieResponse.CODEC, empty);
      CookieResponse decoded = decode(CookieResponse.CODEC, wire);

      assertArrayEquals(concat(string("minecraft:a"), new byte[] {1, 0}), wire);
      assertNotNull(decoded.payload());
      assertEquals(0, decoded.payload().length);
    }

    @Test
    @DisplayName("should decode an absent payload as null")
    void absentPayload() {
      CookieResponse decoded =
          decode(CookieResponse.CODEC, concat(string("minecraft:a"), new byte[] {0}));

      assertNull(decoded.payload());
    }

    @Test
    @DisplayName("should decode a payload of exactly 5 KiB")
    void largestPayload() {
      byte[] payload = new byte[CookiePayload.MAX_SIZE];
      payload[payload.length - 1] = 42;

      CookieResponse decoded =
          decode(
              CookieResponse.CODEC,
              encode(CookieResponse.CODEC, new CookieResponse("minecraft:a", payload)));

      assertArrayEquals(payload, decoded.payload());
    }

    @Test
    @DisplayName("should refuse a payload above 5 KiB before allocating it")
    void refusesOversizedPayload() {
      byte[] wire = concat(string("minecraft:a"), new byte[] {1}, lengthPrefix(5121));

      assertThrows(DecoderException.class, () -> decode(CookieResponse.CODEC, wire));
    }

    @Test
    @DisplayName("should refuse to be created with a payload above 5 KiB")
    void refusesOversizedPayloadOnCreation() {
      byte[] payload = new byte[CookiePayload.MAX_SIZE + 1];

      assertThrows(
          IllegalArgumentException.class, () -> new CookieResponse("minecraft:a", payload));
    }
  }

  // ---------------------------------------------------------------------------
  // StoreCookie
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("StoreCookie")
  class StoreCookieCodec {

    @Test
    @DisplayName("should write the key, then the length-prefixed payload")
    void wireFormat() {
      byte[] wire = encode(StoreCookie.CODEC, new StoreCookie("primemc:s", new byte[] {1, 2, 3}));

      assertArrayEquals(concat(string("primemc:s"), new byte[] {3, 1, 2, 3}), wire);
    }

    @Test
    @DisplayName("should round-trip a payload of exactly 5 KiB")
    void largestPayload() {
      byte[] payload = new byte[CookiePayload.MAX_SIZE];
      payload[0] = -1;

      StoreCookie decoded =
          decode(StoreCookie.CODEC, encode(StoreCookie.CODEC, new StoreCookie("a", payload)));

      assertEquals("a", decoded.key());
      assertArrayEquals(payload, decoded.payload());
    }

    @Test
    @DisplayName("should refuse a payload above 5 KiB on decode")
    void refusesOversizedPayload() {
      byte[] wire = concat(string("a"), lengthPrefix(5121));

      assertThrows(DecoderException.class, () -> decode(StoreCookie.CODEC, wire));
    }

    @Test
    @DisplayName("should refuse to be created with a payload above 5 KiB")
    void refusesOversizedPayloadOnCreation() {
      byte[] payload = new byte[CookiePayload.MAX_SIZE + 1];

      IllegalArgumentException refused =
          assertThrows(IllegalArgumentException.class, () -> new StoreCookie("a", payload));
      assertEquals("Cookie payload too large: 5121 bytes (max 5120)", refused.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Every version, every state
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("registered packets")
  class Registered {

    /** Each cookie packet, the states it exists in and a sample of it. */
    static Stream<Arguments> registrations() {
      Stream.Builder<Arguments> cases = Stream.builder();
      List<Packet> samples =
          List.of(
              new CookieRequest("primemc:session"),
              new CookieResponse("primemc:session", new byte[] {1, 2, 3}),
              new StoreCookie("primemc:session", new byte[] {4, 5}));
      for (ProtocolVersion version : ProtocolVersion.values()) {
        if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_5)) {
          continue;
        }
        for (Packet sample : samples) {
          for (ProtocolState state : statesOf(sample)) {
            cases.add(Arguments.of(version, state, sample));
          }
        }
      }
      return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("registrations")
    @DisplayName("should encode each packet at its id and read it back in every state it exists in")
    void roundTripsThroughRegistry(ProtocolVersion version, ProtocolState state, Packet sample) {
      PacketRegistry registry = StateRegistry.get(state, directionOf(sample));
      PacketRegistry.Encoding encoding = registry.encoding(version, sample.getClass());

      byte[] body = encodeWith(encoding.codec(), sample, version);
      Packet decoded = decodeWith(encoding.codec(), body, version);

      assertEquals(registry.packetId(version, sample.getClass()), encoding.packetId());
      assertEquals(describe(sample), describe(decoded));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.cookie.CookiePacketsTest#versionsBefore1205")
    @DisplayName("should not exist before 1.20.5 in any state")
    void absentBefore1205(ProtocolVersion version) {
      for (ProtocolState state : ProtocolState.values()) {
        for (PacketDirection direction : PacketDirection.values()) {
          PacketRegistry registry = StateRegistry.get(state, direction);
          for (Class<? extends Packet> type :
              List.of(CookieRequest.class, CookieResponse.class, StoreCookie.class)) {
            assertThrows(IllegalArgumentException.class, () -> registry.packetId(version, type));
          }
        }
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.cookie.CookiePacketsTest#versionsFrom1205")
    @DisplayName("should decode requests and responses, and forward a server's stored cookies")
    void readersByType(ProtocolVersion version) {
      for (ProtocolState state :
          List.of(ProtocolState.LOGIN, ProtocolState.CONFIGURATION, ProtocolState.PLAY)) {
        PacketRegistry clientbound = StateRegistry.get(state, PacketDirection.CLIENTBOUND);
        PacketRegistry serverbound = StateRegistry.get(state, PacketDirection.SERVERBOUND);

        assertSame(
            CookieRequest.CODEC,
            clientbound.lookup(version, clientbound.packetId(version, CookieRequest.class)));
        assertSame(
            CookieResponse.CODEC,
            serverbound.lookup(version, serverbound.packetId(version, CookieResponse.class)));
      }
      for (ProtocolState state : List.of(ProtocolState.CONFIGURATION, ProtocolState.PLAY)) {
        PacketRegistry clientbound = StateRegistry.get(state, PacketDirection.CLIENTBOUND);

        assertNull(clientbound.lookup(version, clientbound.packetId(version, StoreCookie.class)));
      }
    }

    @Test
    @DisplayName("should not register Store Cookie in the login state, where it does not exist")
    void noStoreDuringLogin() {
      PacketRegistry clientbound =
          StateRegistry.get(ProtocolState.LOGIN, PacketDirection.CLIENTBOUND);

      assertThrows(
          IllegalArgumentException.class,
          () -> clientbound.packetId(ProtocolVersion.MINECRAFT_26_3, StoreCookie.class));
    }

    private static List<ProtocolState> statesOf(Packet sample) {
      return sample instanceof StoreCookie
          ? List.of(ProtocolState.CONFIGURATION, ProtocolState.PLAY)
          : List.of(ProtocolState.LOGIN, ProtocolState.CONFIGURATION, ProtocolState.PLAY);
    }

    private static PacketDirection directionOf(Packet sample) {
      return sample instanceof CookieResponse
          ? PacketDirection.SERVERBOUND
          : PacketDirection.CLIENTBOUND;
    }

    /** Records with arrays compare by reference: compare their content instead. */
    private static String describe(Packet packet) {
      return switch (packet) {
        case CookieRequest request -> "request " + request.key();
        case CookieResponse response ->
            "response "
                + response.key()
                + " "
                + (response.payload() == null ? "-" : ByteBufUtil.hexDump(response.payload()));
        case StoreCookie store ->
            "store " + store.key() + " " + ByteBufUtil.hexDump(store.payload());
        default -> packet.toString();
      };
    }
  }

  static Stream<ProtocolVersion> versionsBefore1205() {
    return ProtocolVersion.values().stream()
        .filter(version -> version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_5));
  }

  static Stream<ProtocolVersion> versionsFrom1205() {
    return ProtocolVersion.values().stream()
        .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5));
  }

  // ---------------------------------------------------------------------------
  // Helpers: expected wire formats are written out independently of the codecs
  // ---------------------------------------------------------------------------

  private <T extends Packet> byte[] encode(PacketCodec<T> codec, T packet) {
    ByteBuf buf = Unpooled.buffer();
    try {
      codec.encode(packet, buf, VERSION);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /** Decodes {@code wire}, which must be consumed whole as the decoder requires. */
  private <T extends Packet> T decode(PacketCodec<T> codec, byte[] wire) {
    ByteBuf buf = Unpooled.wrappedBuffer(wire);
    try {
      T packet = codec.decode(buf, VERSION);
      assertFalse(buf.isReadable(), "trailing bytes");
      return packet;
    } finally {
      buf.release();
    }
  }

  @SuppressWarnings("unchecked")
  private byte[] encodeWith(PacketCodec<?> codec, Packet packet, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      ((PacketCodec<Packet>) codec).encode(packet, buf, version);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  private Packet decodeWith(PacketCodec<?> codec, byte[] body, ProtocolVersion version) {
    ByteBuf buf = Unpooled.wrappedBuffer(body);
    try {
      Packet packet = codec.decode(buf, version);
      assertFalse(buf.isReadable(), "trailing bytes");
      return packet;
    } finally {
      buf.release();
    }
  }

  private byte[] string(String text) {
    ByteBuf buf = Unpooled.buffer();
    try {
      McString.write(buf, text);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /** A VarInt length announcing {@code length} bytes, followed by none of them. */
  private byte[] lengthPrefix(int length) {
    ByteBuf buf = Unpooled.buffer();
    try {
      VarInt.write(buf, length);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  private byte[] concat(byte[]... parts) {
    ByteBuf buf = Unpooled.buffer();
    try {
      for (byte[] part : parts) {
        buf.writeBytes(part);
      }
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }
}
