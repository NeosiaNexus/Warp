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
package dev.warp.protocol.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McIdentifier;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.cookie.CookiePayload;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.EncryptionResponse;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginPluginResponse;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.StatusRequest;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks every bound of {@link FrameLimits} against the codec of its packet: the largest and the
 * smallest packet the codec writes must have exactly the bound's size, so that the frame decoder
 * never refuses what the codec would read.
 */
@DisplayName("FrameLimits")
class FrameLimitsTest {

  /** A character UTF-8 writes in three bytes, the most a protocol string allows per character. */
  private static final char WIDE = 'ࠀ';

  private static final ProtocolVersion LATEST = ProtocolVersion.latest();

  private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

  static Stream<ProtocolVersion> versions() {
    return ProtocolVersion.values().stream();
  }

  @Nested
  @DisplayName("handshake and status")
  class HandshakeAndStatus {

    @Test
    @DisplayName("should bound the handshake by the largest and smallest one its codec writes")
    void handshake() {
      FrameLimits limits = FrameLimits.HANDSHAKE;
      int largest =
          size(
              Handshake.CODEC,
              new Handshake(-1, wide(Handshake.MAX_SERVER_ADDRESS), 65535, -1),
              LATEST);
      int smallest = size(Handshake.CODEC, new Handshake(0, "", 0, 0), LATEST);

      assertEquals(largest, limits.largest(0x00));
      assertEquals(smallest, limits.smallest(0x00));
      assertEquals(largest, limits.largest());
      assertFalse(limits.allows(0x01));
    }

    @Test
    @DisplayName("should allow exactly a status request and a ping")
    void status() {
      FrameLimits limits = FrameLimits.STATUS;

      assertEquals(size(StatusRequest.CODEC, new StatusRequest(), LATEST), limits.largest(0x00));
      assertEquals(size(StatusRequest.CODEC, new StatusRequest(), LATEST), limits.smallest(0x00));
      assertEquals(
          size(PingRequest.CODEC, new PingRequest(Long.MIN_VALUE), LATEST), limits.largest(1));
      assertEquals(size(PingRequest.CODEC, new PingRequest(0), LATEST), limits.smallest(1));
      assertFalse(limits.allows(0x02));
      assertEquals(9, limits.largest());
    }

    @Test
    @DisplayName("should leave clients that are logged in to the protocol's own bound")
    void loggedIn() {
      assertNull(FrameLimits.serverbound(ProtocolState.CONFIGURATION, ProtocolVersion.latest()));
      assertNull(FrameLimits.serverbound(ProtocolState.PLAY, ProtocolVersion.latest()));
    }
  }

  @Nested
  @DisplayName("login")
  class Login {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.netty.FrameLimitsTest#versions")
    @DisplayName("should bound Login Start by the largest and smallest one its codec writes")
    void loginStart(ProtocolVersion version) {
      FrameLimits limits = login(version, false);
      boolean uuidRequired = version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2);
      LoginStart.ProfilePublicKey key =
          new LoginStart.ProfilePublicKey(Long.MIN_VALUE, new byte[512], new byte[4096]);
      LoginStart largest = new LoginStart(wide(16), uuidRequired ? null : key, PLAYER);
      LoginStart smallest = new LoginStart("", null, uuidRequired ? PLAYER : null);

      assertEquals(size(LoginStart.CODEC, largest, version), limits.largest(0x00));
      assertEquals(size(LoginStart.CODEC, smallest, version), limits.smallest(0x00));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.netty.FrameLimitsTest#versions")
    @DisplayName(
        "should bound Encryption Response by the largest and smallest one its codec writes")
    void encryptionResponse(ProtocolVersion version) {
      FrameLimits limits = login(version, false);
      boolean signable =
          version.isBetween(ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2);
      EncryptionResponse largest =
          new EncryptionResponse(
              new byte[256],
              signable
                  ? new EncryptionResponse.SignedToken(1, new byte[256])
                  : new EncryptionResponse.EncryptedToken(new byte[256]));
      EncryptionResponse smallest =
          new EncryptionResponse(new byte[0], new EncryptionResponse.EncryptedToken(new byte[0]));

      assertEquals(size(EncryptionResponse.CODEC, largest, version), limits.largest(0x01));
      assertEquals(size(EncryptionResponse.CODEC, smallest, version), limits.smallest(0x01));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.netty.FrameLimitsTest#versions")
    @DisplayName("should bound a Login Plugin Response to the answer of a request never sent")
    void loginPluginResponse(ProtocolVersion version) {
      FrameLimits limits = login(version, false);
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_13)) {
        assertFalse(limits.allows(0x02));
        return;
      }

      assertEquals(
          size(LoginPluginResponse.CODEC, new LoginPluginResponse(-1, true, null), version),
          limits.largest(0x02));
      assertEquals(
          size(LoginPluginResponse.CODEC, new LoginPluginResponse(0, false, null), version),
          limits.smallest(0x02));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.netty.FrameLimitsTest#versions")
    @DisplayName("should allow Login Acknowledged from 1.20.2, as its identifier alone")
    void loginAcknowledged(ProtocolVersion version) {
      FrameLimits limits = login(version, false);
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        assertFalse(limits.allows(0x03));
        return;
      }

      int size = size(LoginAcknowledged.CODEC, new LoginAcknowledged(), version);
      assertEquals(size, limits.largest(0x03));
      assertEquals(size, limits.smallest(0x03));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.netty.FrameLimitsTest#versions")
    @DisplayName("should allow a Cookie Response only once the proxy asked for a cookie")
    void cookieResponse(ProtocolVersion version) {
      FrameLimits unasked = login(version, false);
      FrameLimits asked = login(version, true);
      assertFalse(unasked.allows(0x04));
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_5)) {
        assertFalse(asked.allows(0x04));
        return;
      }

      CookieResponse largest =
          new CookieResponse(
              "a:" + "b".repeat(McIdentifier.MAX_LENGTH - 2), new byte[CookiePayload.MAX_SIZE]);
      assertEquals(size(CookieResponse.CODEC, largest, version), asked.largest(0x04));
      assertEquals(
          size(CookieResponse.CODEC, new CookieResponse("", null), version), asked.smallest(0x04));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.netty.FrameLimitsTest#versions")
    @DisplayName("should keep a frame of the largest login under 5 KiB without cookies")
    void largestLogin(ProtocolVersion version) {
      assertTrue(login(version, false).largest() < 5 * 1024, () -> login(version, false) + "");
    }

    @Test
    @DisplayName("should share one instance per protocol")
    void shared() {
      ProtocolVersion version = ProtocolVersion.latest();

      assertSame(cached(version, false), cached(version, false));
      assertSame(cached(version, true), cached(version, true));
      assertSame(FrameLimits.HANDSHAKE, FrameLimits.serverbound(ProtocolState.HANDSHAKE, version));
      assertSame(FrameLimits.STATUS, FrameLimits.serverbound(ProtocolState.STATUS, version));
      assertSame(cached(version, false), FrameLimits.serverbound(ProtocolState.LOGIN, version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.netty.FrameLimitsTest#versions")
    @DisplayName("should cache the very limits it computes for the version")
    void cachedAsComputed(ProtocolVersion version) {
      for (boolean cookies : new boolean[] {false, true}) {
        FrameLimits cached = cached(version, cookies);
        FrameLimits computed = login(version, cookies);

        assertEquals(ProtocolState.LOGIN, cached.state());
        assertEquals(computed.largest(), cached.largest());
        for (int packetId = 0; packetId < 8; packetId++) {
          assertEquals(computed.smallest(packetId), cached.smallest(packetId));
          assertEquals(computed.largest(packetId), cached.largest(packetId));
        }
      }
    }
  }

  @Nested
  @DisplayName("checks")
  class Checks {

    @Test
    @DisplayName("should refuse packet identifiers outside the state, overlong ones included")
    void identifiers() {
      FrameLimits limits = FrameLimits.STATUS;

      assertFalse(limits.allows(-1));
      assertFalse(limits.allows(0x80));
      assertFalse(limits.allows(0x02, 1));
      assertTrue(limits.allows(0x01, 9));
      assertFalse(limits.allows(0x01, 8));
      assertFalse(limits.allows(0x01, 10));
      assertEquals(0, limits.largest(0x05));
      assertEquals(0, limits.smallest(0x05));
      assertEquals(0, limits.largest(-1));
    }

    @Test
    @DisplayName("should name its state and largest frame")
    void describes() {
      assertEquals(ProtocolState.STATUS, FrameLimits.STATUS.state());
      assertEquals("FrameLimits[STATUS, largest 9 bytes]", FrameLimits.STATUS.toString());
    }

    @Test
    @DisplayName("should let the frame decoder emit a frame of each packet's largest size")
    void decoderAtBound() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_19_1;
      FrameLimits limits = login(version, false);
      byte[] packet = new byte[limits.largest(0x00)];
      packet[0] = 0x00;
      EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(limits));

      channel.writeInbound(Frames.plain(packet));

      ByteBuf frame = channel.readInbound();
      assertNotNull(frame);
      frame.release();
    }

    @Test
    @DisplayName("should make the frame decoder refuse one byte more, before buffering it")
    void decoderOverBound() {
      FrameLimits limits = FrameLimits.HANDSHAKE;
      ByteBuf header = Unpooled.buffer();
      VarInt.write(header, limits.largest(0x00) + 1);
      header.writeByte(0x00);
      EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(limits));

      FrameLimitException refused =
          assertInstanceOf(
              FrameLimitException.class,
              assertThrows(DecoderException.class, () -> channel.writeInbound(header)));
      assertEquals(FrameLimitException.Reason.TOO_LONG, refused.reason());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The limits of the LOGIN state, computed for this call: what the bounds are checked on. */
  private FrameLimits login(ProtocolVersion version, boolean cookies) {
    return FrameLimits.login(version, cookies);
  }

  /** The limits of the LOGIN state as the frame decoder gets them. */
  private FrameLimits cached(ProtocolVersion version, boolean cookies) {
    FrameLimits limits = FrameLimits.serverbound(ProtocolState.LOGIN, version, cookies);
    assertNotNull(limits);
    return limits;
  }

  private String wide(int length) {
    char[] chars = new char[length];
    Arrays.fill(chars, WIDE);
    return new String(chars);
  }

  /** The size of a packet's frame: its identifier and what its codec writes. */
  private <T extends Packet> int size(PacketCodec<T> codec, T packet, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      codec.encode(packet, buf, version);
      return 1 + buf.readableBytes();
    } finally {
      buf.release();
    }
  }
}
