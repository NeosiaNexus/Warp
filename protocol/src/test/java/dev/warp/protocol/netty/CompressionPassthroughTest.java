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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.PacketCompressor;
import dev.warp.protocol.packet.PacketDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * End-to-end relay of clientbound traffic through the proxy's real handlers: backend wire bytes →
 * {@link FrameDecoder} → {@link MinecraftDecoder} → {@link FrameForwarder} → client encoders → a
 * vanilla-like client decoder. Every packet must arrive intact, and the zlib work the proxy does is
 * counted, so the passthrough guarantees are asserted rather than assumed.
 */
@DisplayName("Compression passthrough, end to end")
class CompressionPassthroughTest {

  private static final int UNREGISTERED_ID = 0x7F;
  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;

  @ParameterizedTest(name = "backend {0}, client {1}, {2}")
  @CsvSource({
    "256, 256, modern",
    "256, 256, legacy",
    "64, 256, modern",
    "64, 256, legacy",
    "1024, 256, modern",
    "1024, 256, legacy",
    "-1, 256, modern",
    "256, -1, modern",
    "-1, -1, modern"
  })
  @DisplayName("should deliver every packet intact for any threshold pair")
  void deliversIntact(int backendThreshold, int clientThreshold, String client) {
    ProtocolVersion version = client.equals("modern") ? MODERN : ProtocolVersion.MINECRAFT_1_16_4;
    Relay relay = new Relay(backendThreshold, clientThreshold, version, true);
    List<byte[]> packets = packets(200);

    List<byte[]> received = relay.run(packets);

    assertEquals(packets.size(), received.size());
    for (int i = 0; i < packets.size(); i++) {
      assertArrayEquals(packets.get(i), received.get(i), "packet " + i);
    }
  }

  @ParameterizedTest(name = "{0} client")
  @CsvSource({"modern", "legacy"})
  @DisplayName("should run no zlib at all when thresholds are equal")
  void noZlibWithEqualThresholds(String client) {
    ProtocolVersion version = client.equals("modern") ? MODERN : ProtocolVersion.MINECRAFT_1_16_4;
    Relay relay = new Relay(256, 256, version, true);

    relay.run(packets(200));

    assertEquals(0, relay.backendZlib.inflations(), "inflations on the backend leg");
    assertEquals(0, relay.clientZlib.deflations(), "deflations on the client leg");
  }

  @org.junit.jupiter.api.Test
  @DisplayName("should never re-deflate for a modern client when the backend compresses more")
  void noDeflateWhenBackendCompressesMore() {
    Relay relay = new Relay(64, 256, MODERN, true);

    relay.run(packets(200));

    assertEquals(0, relay.clientZlib.deflations());
  }

  @org.junit.jupiter.api.Test
  @DisplayName("should compress only what the backend left uncompressed above the client threshold")
  void deflatesOnlyBetweenThresholds() {
    Relay relay = new Relay(1024, 256, MODERN, true);
    List<byte[]> packets = packets(200);

    relay.run(packets);

    long between = packets.stream().filter(p -> p.length >= 256 && p.length < 1024).count();
    assertEquals(between, relay.clientZlib.deflations());
    assertEquals(0, relay.backendZlib.inflations());
  }

  @ParameterizedTest(name = "backend {0}")
  @CsvSource({"256", "64"})
  @DisplayName("should inflate and re-deflate every compressed frame when passthrough is off")
  void transcodesWhenDisabled(int backendThreshold) {
    Relay relay = new Relay(backendThreshold, 256, MODERN, false);
    List<byte[]> packets = packets(200);

    List<byte[]> received = relay.run(packets);

    for (int i = 0; i < packets.size(); i++) {
      assertArrayEquals(packets.get(i), received.get(i));
    }
    long compressedOnBackend = packets.stream().filter(p -> p.length >= backendThreshold).count();
    assertEquals(compressedOnBackend, relay.backendZlib.inflations());
  }

  // ---------------------------------------------------------------------------
  // Fixture: one backend leg, one client leg, wired like the proxy wires them
  // ---------------------------------------------------------------------------

  private static final class Relay {
    final CountingCompressor backendZlib = new CountingCompressor();
    final CountingCompressor clientZlib = new CountingCompressor();
    private final int backendThreshold;
    private final int clientThreshold;
    private final EmbeddedChannel backendLeg;
    private final EmbeddedChannel clientLeg;
    private final MinecraftDecoder backendDecoder;
    private final FrameForwarder toClient;

    Relay(int backendThreshold, int clientThreshold, ProtocolVersion version, boolean passthrough) {
      this.backendThreshold = backendThreshold;
      this.clientThreshold = clientThreshold;
      backendDecoder =
          new MinecraftDecoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY);
      if (backendThreshold >= 0) {
        backendDecoder.enableCompression(
            new FrameDecompressor(
                backendThreshold,
                false,
                FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
                backendZlib),
            passthrough);
      }
      // Forward from inside the pipeline, synchronously, as MinecraftConnection does.
      backendLeg =
          new EmbeddedChannel(
              new FrameDecoder(),
              backendDecoder,
              new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                  toClient.forward((ByteBuf) msg, backendDecoder);
                }
              });

      MinecraftEncoder clientEncoder =
          new MinecraftEncoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY);
      clientLeg =
          clientThreshold >= 0
              ? new EmbeddedChannel(
                  new CompressionEncoder(clientThreshold, clientZlib), clientEncoder)
              : new EmbeddedChannel(FrameEncoder.INSTANCE, clientEncoder);
      toClient = new FrameForwarder(clientLeg);
      if (clientThreshold >= 0) {
        toClient.compressionEnabled(clientThreshold);
      }
      toClient.setVerbatimEnabled(passthrough);
    }

    List<byte[]> run(List<byte[]> packets) {
      // The backend sends its packets as one stream, cut into socket-sized reads.
      ByteBuf stream = backendStream(packets);
      while (stream.isReadable()) {
        backendLeg.writeInbound(stream.readRetainedSlice(Math.min(1500, stream.readableBytes())));
      }
      stream.release();
      clientLeg.flushOutbound();
      ByteBuf wire = Unpooled.buffer();
      for (ByteBuf out = clientLeg.readOutbound(); out != null; out = clientLeg.readOutbound()) {
        wire.writeBytes(out);
        out.release();
      }
      assertFalse(backendLeg.finish());
      assertFalse(clientLeg.finish());
      return clientDecode(wire);
    }

    /** Encodes packets as a vanilla backend with the given threshold would. */
    private ByteBuf backendStream(List<byte[]> packets) {
      EmbeddedChannel encoder =
          new EmbeddedChannel(
              backendThreshold >= 0
                  ? new CompressionEncoder(backendThreshold, new JavaCompressor(6))
                  : FrameEncoder.INSTANCE);
      ByteBuf stream = Unpooled.buffer();
      for (byte[] packet : packets) {
        encoder.writeOutbound(Unpooled.wrappedBuffer(packet));
        ByteBuf frame = encoder.readOutbound();
        stream.writeBytes(frame);
        frame.release();
      }
      assertFalse(encoder.finish());
      return stream;
    }

    /** Decodes the client-bound wire bytes the way a vanilla client does. */
    private List<byte[]> clientDecode(ByteBuf wire) {
      EmbeddedChannel client = new EmbeddedChannel(new FrameDecoder());
      if (clientThreshold >= 0) {
        client
            .pipeline()
            .addLast(
                new CompressionDecoder(
                    new FrameDecompressor(
                        clientThreshold,
                        false,
                        FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
                        new JavaCompressor(6))));
      }
      client.writeInbound(wire);
      List<byte[]> packets = new ArrayList<>();
      for (ByteBuf packet = client.readInbound(); packet != null; packet = client.readInbound()) {
        if (clientThreshold < 0) {
          dev.warp.protocol.codec.VarInt.skip(packet); // length prefix of the complete frame
        }
        packets.add(Frames.drain(packet));
      }
      assertFalse(client.finish());
      return packets;
    }
  }

  /** A mix of packet sizes on both sides of every threshold under test. */
  private static List<byte[]> packets(int count) {
    Random random = new Random(42);
    List<byte[]> packets = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int size =
          switch (random.nextInt(5)) {
            case 0 -> 1 + random.nextInt(60);
            case 1 -> 60 + random.nextInt(300);
            case 2 -> 300 + random.nextInt(1000);
            case 3 -> 1000 + random.nextInt(4000);
            default -> 4000 + random.nextInt(60_000);
          };
      byte[] payload = new byte[size];
      for (int j = 0; j < size; j++) {
        payload[j] = (byte) (random.nextInt(4) == 0 ? random.nextInt(256) : j % 17);
      }
      packets.add(Frames.packet(UNREGISTERED_ID, payload));
    }
    return packets;
  }

  /** Counts zlib work, delegating to the JDK's zlib. */
  private static final class CountingCompressor implements PacketCompressor {
    private final JavaCompressor delegate = new JavaCompressor(6);
    private int inflations;
    private int deflations;

    int inflations() {
      return inflations;
    }

    int deflations() {
      return deflations;
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      inflations++;
      delegate.inflate(source, destination, uncompressedSize);
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
      deflations++;
      delegate.deflate(source, destination);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
