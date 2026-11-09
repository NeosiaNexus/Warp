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
import dev.warp.protocol.packet.play.KeepAlive;

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

  private static final int KEEP_ALIVE_ID_1_21_4 = 0x27;

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

  @Test
  @DisplayName("should see a compressed packet it inflates and decodes with its wire size")
  void decoderCompressed() {
    MinecraftDecoder decoder =
        new MinecraftDecoder(
            PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
    decoder.enableCompression(
        new FrameDecompressor(
            256, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, new JavaCompressor(6)),
        true);
    Counting counting = new Counting();
    decoder.observer(counting);
    EmbeddedChannel ch = new EmbeddedChannel(decoder);
    byte[] keepAlive = Frames.packet(KEEP_ALIVE_ID_1_21_4, new byte[] {0, 0, 0, 0, 0, 0, 0, 42});
    ByteBuf frame = Frames.compressed(keepAlive, 6);
    int wire = frame.readableBytes();

    assertTrue(ch.writeInbound(frame));

    assertInstanceOf(KeepAlive.class, ch.readInbound());
    assertEquals(1, counting.decodedFrames());
    assertEquals(wire, counting.decodedBytesSeen());
    assertEquals(0, counting.blindFrames());
    assertFalse(ch.finish());
  }

  @Test
  @DisplayName("should report the packet size of an uncompressed frame passed verbatim")
  void uncompressedPassthrough() {
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
    FrameDecompressor source =
        new FrameDecompressor(
            256, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor);
    byte[] small = packet(100);
    ByteBuf frame = Frames.uncompressed(small); // below both thresholds: Data Length 0
    int wire = frame.readableBytes();

    forwarder.forward(frame, source, null);

    assertEquals(1, counting.passthroughFrames());
    assertEquals(0, counting.reencodedFrames());
    assertEquals(wire, counting.wireBytes());
    assertEquals(small.length, counting.rawBytes());
    channel.flushOutbound();
    assertTrue(channel.releaseOutbound());
    assertFalse(channel.finish());
  }

  @Test
  @DisplayName("should count frames the source already inflated the same way")
  void forwarderWithInflatedPackets() {
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
    ByteBuf refused = Frames.compressed(small, 6);
    int refusedWire = refused.readableBytes();

    forwarder.forward(accepted, sameThreshold, Unpooled.wrappedBuffer(big.clone()));
    forwarder.forward(refused, lowerThreshold, Unpooled.wrappedBuffer(small.clone()));

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
