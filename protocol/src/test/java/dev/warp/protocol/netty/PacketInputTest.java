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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.fuzz.InboundRecorder;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.play.KeepAlive;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Packet input (handlers between the framing layer and the decoder)")
class PacketInputTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;
  private static final int THRESHOLD = 256;

  /** A packet id no state registers in PLAY: the decoder forwards it untouched. */
  private static final int OPAQUE_ID = 0x7F;

  @Nested
  @DisplayName("PacketDecompressor")
  class Decompressor {

    @Test
    @DisplayName("should strip the length prefix of an uncompressed connection's frames")
    void unframesUncompressed() {
      EmbeddedChannel ch = new EmbeddedChannel(new PacketDecompressor(null));
      byte[] packet = Frames.packet(OPAQUE_ID, new byte[] {1, 2, 3});

      assertTrue(ch.writeInbound(Frames.plain(packet)));

      assertArrayEquals(packet, Frames.drain(ch.readInbound()));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should strip the Data Length of packets sent uncompressed once compressed")
    void unframesBelowThreshold() {
      EmbeddedChannel ch = new EmbeddedChannel(new PacketDecompressor(decompressor(true)));
      byte[] packet = Frames.packet(OPAQUE_ID, new byte[] {4, 5});

      assertTrue(ch.writeInbound(Frames.uncompressed(packet)));

      assertArrayEquals(packet, Frames.drain(ch.readInbound()));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should inflate compressed frames from the frame after compression is enabled")
    void inflatesOnceEnabled() {
      PacketDecompressor handler = new PacketDecompressor(null);
      EmbeddedChannel ch = new EmbeddedChannel(handler);
      byte[] before = Frames.packet(OPAQUE_ID, new byte[] {9});
      byte[] after = randomPacket(1000);

      ch.writeInbound(Frames.plain(before));
      handler.enableCompression(decompressor(false));
      ch.writeInbound(Frames.compressed(after, Deflater.DEFAULT_COMPRESSION));

      assertArrayEquals(before, Frames.drain(ch.readInbound()));
      assertArrayEquals(after, Frames.drain(ch.readInbound()));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should unframe every frame of a read in pooled buffers, as the proxy gets them")
    void unframesPooledFrames() {
      PacketDecompressor handler = new PacketDecompressor(null);
      List<byte[]> packets = new ArrayList<>();
      EmbeddedChannel ch =
          new EmbeddedChannel(
              new FrameDecoder(),
              handler,
              new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                  packets.add(Frames.drain((ByteBuf) msg));
                  // The first packet switches compression on, as a Set Compression does.
                  if (packets.size() == 1) {
                    handler.enableCompression(decompressor(false));
                  }
                }
              });
      ch.config().setAllocator(PooledByteBufAllocator.DEFAULT);
      ByteBuf wire = PooledByteBufAllocator.DEFAULT.directBuffer();
      // Set Compression, then a packet sent uncompressed (Data Length 0): one read, two frames.
      wire.writeBytes(new byte[] {3, 3, (byte) 0x80, 2, 5, 0, 2, 1, 2, 3});

      ch.writeInbound(wire);

      assertArrayEquals(new byte[] {3, (byte) 0x80, 2}, packets.get(0));
      assertArrayEquals(new byte[] {2, 1, 2, 3}, packets.get(1));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should reject a compressed frame below the threshold from a client, as vanilla")
    void rejectsBelowThresholdFromClients() {
      EmbeddedChannel ch = new EmbeddedChannel(new PacketDecompressor(decompressor(true)));

      assertThrows(
          DecoderException.class,
          () -> ch.writeInbound(Frames.compressed(new byte[100], Deflater.DEFAULT_COMPRESSION)));
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should drop the frames read along with the one that closed the connection")
    void dropsFramesAfterRejection() {
      InboundRecorder recorder = new InboundRecorder();
      EmbeddedChannel ch =
          new EmbeddedChannel(
              new FrameDecoder(), new PacketDecompressor(decompressor(true)), recorder);
      ByteBuf belowThreshold = Frames.compressed(new byte[100], Deflater.DEFAULT_COMPRESSION);

      ch.writeInbound(Unpooled.wrappedBuffer(belowThreshold, Frames.uncompressed(new byte[] {1})));

      assertEquals(1, recorder.failures().size());
      assertInstanceOf(DecoderException.class, recorder.failures().getFirst());
      assertEquals(List.of(), recorder.messages());
      ch.finishAndReleaseAll();
    }
  }

  @Nested
  @DisplayName("MinecraftDecoder in packet input")
  class Decoder {

    @Test
    @DisplayName("should forward a packet it does not read as the bare packet, from its start")
    void forwardsBarePacket() {
      MinecraftDecoder decoder = playDecoder();
      decoder.usePacketInput();
      EmbeddedChannel ch = new EmbeddedChannel(decoder);
      byte[] packet = Frames.packet(OPAQUE_ID, new byte[] {1, 2, 3});
      // A plugin handler may hand over a buffer whose reader index is not 0.
      ByteBuf offset = Unpooled.buffer().writeBytes(new byte[] {(byte) 0xEE, (byte) 0xEE});
      offset.writeBytes(packet).readerIndex(2);

      assertTrue(ch.writeInbound(offset));

      ByteBuf forwarded = ch.readInbound();
      assertFalse(decoder.emitsFrames());
      assertArrayEquals(packet, Frames.drain(forwarded));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode the packets it reads from their bare bytes")
    void decodesBarePacket() {
      MinecraftDecoder decoder = playDecoder();
      decoder.usePacketInput();
      EmbeddedChannel ch = new EmbeddedChannel(decoder);
      ByteBuf packet = Unpooled.buffer();
      VarInt.write(packet, keepAliveId());
      packet.writeLong(42);

      assertTrue(ch.writeInbound(packet));

      assertEquals(new KeepAlive(42), assertInstanceOf(KeepAlive.class, ch.readInbound()));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should hand over the decompressor it was using, and refuse to take another")
    void handsOverItsDecompressor() {
      MinecraftDecoder decoder = playDecoder();
      FrameDecompressor decompressor = decompressor(false);
      decoder.enableCompression(decompressor, true);

      assertSame(decompressor, decoder.usePacketInput());
      assertNull(decoder.decompressor());
      assertThrows(
          IllegalStateException.class, () -> decoder.enableCompression(decompressor(false), true));
      decompressor.close();
    }

    @Test
    @DisplayName("should keep emitting frames until switched to packet input")
    void emitsFramesByDefault() {
      assertTrue(playDecoder().emitsFrames());
    }
  }

  @Nested
  @DisplayName("FrameForwarder with a source in packet input")
  class Forwarding {

    @Test
    @DisplayName("should frame a small bare packet uncompressed for a compressed connection")
    void framesSmallPacket() {
      EmbeddedChannel sink = compressedSink();
      FrameForwarder forwarder = new FrameForwarder(sink);
      forwarder.compressionEnabled(THRESHOLD);
      byte[] packet = Frames.packet(OPAQUE_ID, new byte[] {1, 2});
      MinecraftDecoder source = playDecoder();
      source.usePacketInput();

      forwarder.forward(Unpooled.wrappedBuffer(packet), source);

      ByteBuf frame = Unpooled.wrappedBuffer(output(sink));
      assertEquals(VarInt.read(frame), frame.readableBytes());
      assertEquals(0, VarInt.read(frame)); // Data Length 0: sent uncompressed
      assertArrayEquals(packet, Frames.drain(frame));
    }

    @Test
    @DisplayName("should compress a bare packet at the threshold, never forward it verbatim")
    void compressesLargePacket() {
      EmbeddedChannel sink = compressedSink();
      FrameForwarder forwarder = new FrameForwarder(sink);
      forwarder.compressionEnabled(THRESHOLD);
      byte[] packet = randomPacket(1000);
      MinecraftDecoder source = playDecoder();
      source.usePacketInput();

      forwarder.forward(Unpooled.wrappedBuffer(packet), source);

      EmbeddedChannel peer = new EmbeddedChannel(new PacketDecompressor(decompressor(true)));
      peer.writeInbound(Unpooled.wrappedBuffer(output(sink)));
      assertArrayEquals(packet, Frames.drain(peer.readInbound()));
      assertFalse(peer.finish());
    }

    @Test
    @DisplayName("should release a bare packet forwarded to a closed connection")
    void releasesWhenClosed() {
      EmbeddedChannel sink = compressedSink();
      FrameForwarder forwarder = new FrameForwarder(sink);
      var _ = sink.close();
      ByteBuf packet = Unpooled.wrappedBuffer(Frames.packet(OPAQUE_ID, new byte[] {1}));

      forwarder.forwardPacket(packet);

      assertEquals(0, packet.refCnt());
    }
  }

  @Nested
  @DisplayName("a handler between the two layers")
  class Pipeline {

    @Test
    @DisplayName("should see each packet decompressed, and hand the decoder what it emits")
    void translatesBetweenLayers() {
      PacketDecompressor decompressor = new PacketDecompressor(null);
      MinecraftDecoder decoder = playDecoder();
      decoder.usePacketInput();
      // A translator in miniature: rewrites the opaque id into the id of a Keep Alive.
      MessageToMessageDecoder<ByteBuf> translator =
          new MessageToMessageDecoder<>() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf packet, List<Object> out) {
              assertEquals(OPAQUE_ID, VarInt.read(packet));
              ByteBuf rewritten = ctx.alloc().buffer();
              VarInt.write(rewritten, keepAliveId());
              out.add(rewritten.writeBytes(packet));
            }
          };
      EmbeddedChannel ch =
          new EmbeddedChannel(new FrameDecoder(), decompressor, translator, decoder);
      decompressor.enableCompression(decompressor(false));

      ch.writeInbound(Frames.uncompressed(opaqueLong(7)));
      ch.writeInbound(Frames.compressed(opaqueLong(8), Deflater.DEFAULT_COMPRESSION));

      assertEquals(new KeepAlive(7), ch.readInbound());
      assertEquals(new KeepAlive(8), ch.readInbound());
      assertFalse(ch.finish());
    }

    private static byte[] opaqueLong(long value) {
      return Frames.packet(OPAQUE_ID, Frames.drain(Unpooled.buffer().writeLong(value)));
    }
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private static MinecraftDecoder playDecoder() {
    return new MinecraftDecoder(PacketDirection.SERVERBOUND, VERSION, ProtocolState.PLAY);
  }

  private static int keepAliveId() {
    return StateRegistry.get(ProtocolState.PLAY, PacketDirection.SERVERBOUND)
        .packetId(VERSION, KeepAlive.class);
  }

  private static FrameDecompressor decompressor(boolean validating) {
    return new FrameDecompressor(
        THRESHOLD,
        validating,
        FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
        new JavaCompressor(Deflater.DEFAULT_COMPRESSION));
  }

  private static byte[] randomPacket(int size) {
    byte[] payload = new byte[size - 1];
    new Random(size).nextBytes(payload);
    return Frames.packet(OPAQUE_ID, payload);
  }

  private static EmbeddedChannel compressedSink() {
    return new EmbeddedChannel(
        new CompressionEncoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)),
        new MinecraftEncoder(PacketDirection.CLIENTBOUND, VERSION, ProtocolState.PLAY));
  }

  private static byte[] output(EmbeddedChannel channel) {
    channel.flushOutbound();
    ByteBuf all = Unpooled.buffer();
    for (ByteBuf out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
      all.writeBytes(out);
      out.release();
    }
    assertFalse(channel.finish());
    return Frames.drain(all);
  }
}
