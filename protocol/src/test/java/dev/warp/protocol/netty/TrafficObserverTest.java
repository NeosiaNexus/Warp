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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.handshake.Handshake;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrafficObserver")
class TrafficObserverTest {

  /** Counts every call. */
  private static final class Counting implements TrafficObserver {
    private int blind;
    private long blindBytes;
    private int decoded;
    private long decodedBytes;
    private int passthrough;
    private int reencoded;
    private long wire;
    private long raw;

    @Override
    public void blind(int wireBytes) {
      blind++;
      blindBytes += wireBytes;
    }

    @Override
    public void decoded(int wireBytes) {
      decoded++;
      decodedBytes += wireBytes;
    }

    @Override
    public void passthrough(int wireBytes, int rawBytes) {
      passthrough++;
      wire += wireBytes;
      raw += rawBytes;
    }

    @Override
    public void reencoded(int wireBytes, int rawBytes) {
      reencoded++;
      wire += wireBytes;
      raw += rawBytes;
    }

    int blindFrames() {
      return blind;
    }

    long blindBytesSeen() {
      return blindBytes;
    }

    int decodedFrames() {
      return decoded;
    }

    long decodedBytesSeen() {
      return decodedBytes;
    }

    int passthroughFrames() {
      return passthrough;
    }

    int reencodedFrames() {
      return reencoded;
    }

    long wireBytes() {
      return wire;
    }

    long rawBytes() {
      return raw;
    }
  }

  @Test
  @DisplayName("should see a decoded packet with its wire size, and a blind frame with its own")
  void decoder() {
    MinecraftDecoder decoder =
        new MinecraftDecoder(
            PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.HANDSHAKE);
    Counting counting = new Counting();
    decoder.observer(counting);
    EmbeddedChannel ch = new EmbeddedChannel(decoder);
    ByteBuf handshake = Unpooled.buffer();
    VarInt.write(handshake, 0x00);
    VarInt.write(handshake, 769);
    VarInt.write(handshake, 9);
    handshake.writeBytes("localhost".getBytes(StandardCharsets.UTF_8));
    handshake.writeShort(25565);
    VarInt.write(handshake, 2);
    ByteBuf framed = Frames.framed(handshake);
    int handshakeWire = framed.readableBytes();

    assertTrue(ch.writeInbound(framed));
    assertInstanceOf(Handshake.class, ch.readInbound());
    decoder.setState(ProtocolState.PLAY);
    ByteBuf unknown = Unpooled.buffer();
    VarInt.write(unknown, 0x7E); // no such packet: forwarded blind
    unknown.writeBytes(new byte[40]);
    ByteBuf blindFrame = Frames.framed(unknown);
    int blindWire = blindFrame.readableBytes();
    assertTrue(ch.writeInbound(blindFrame));
    Object out = ch.readInbound();
    assertInstanceOf(ByteBuf.class, out);
    ReferenceCountUtil.release(out);

    assertEquals(1, counting.decodedFrames());
    assertEquals(handshakeWire, counting.decodedBytesSeen());
    assertEquals(1, counting.blindFrames());
    assertEquals(blindWire, counting.blindBytesSeen());
    assertEquals(0, counting.passthroughFrames() + counting.reencodedFrames());
    assertFalse(ch.finish());
  }

  @Test
  @DisplayName("should tell a compressed frame passed verbatim from one compressed again")
  void forwarder() {
    JavaCompressor compressor = new JavaCompressor(6);
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new CompressionEncoder(256, compressor),
            new MinecraftEncoder(
                PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY));
    FrameForwarder forwarder = new FrameForwarder(channel);
    forwarder.compressionEnabled(256);
    Counting counting = new Counting();
    forwarder.observer(counting);
    FrameDecompressor sameThreshold =
        new FrameDecompressor(
            256, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor);
    FrameDecompressor lowerThreshold =
        new FrameDecompressor(
            64, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor);
    byte[] big = packet(1000);
    byte[] small = packet(100);
    ByteBuf accepted = Frames.compressed(big, 6);
    int acceptedWire = accepted.readableBytes();
    ByteBuf refused = Frames.compressed(small, 6); // compressed below this side's threshold
    int refusedWire = refused.readableBytes();

    forwarder.forward(accepted, sameThreshold, null);
    forwarder.forward(refused, lowerThreshold, null);

    assertEquals(1, counting.passthroughFrames());
    assertEquals(1, counting.reencodedFrames());
    assertEquals(acceptedWire + refusedWire, counting.wireBytes());
    assertEquals(big.length + small.length, counting.rawBytes());
    channel.flushOutbound();
    assertTrue(channel.releaseOutbound());
    assertFalse(channel.finish());
  }

  private static byte[] packet(int size) {
    byte[] payload = new byte[size - 1];
    new Random(size).nextBytes(payload);
    return Frames.packet(0x55, payload);
  }
}
