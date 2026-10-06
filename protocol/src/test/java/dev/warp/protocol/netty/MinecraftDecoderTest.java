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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.PacketCompressor;
import dev.warp.protocol.compress.TrackingCompressor;
import dev.warp.protocol.compress.ZlibStreams;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayClientSettings;
import dev.warp.protocol.packet.status.StatusRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("MinecraftDecoder")
class MinecraftDecoderTest {

  // ---------------------------------------------------------------------------
  // Known packet decode
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("known packet decode")
  class KnownPacketDecode {

    @Test
    @DisplayName("should decode Handshake packet from raw bytes")
    void handshake() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Build wire bytes: [VarInt: 0x00][VarInt: protocol][String: address][Short: port][VarInt:
      // nextState]
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00); // packet ID
      VarInt.write(buf, 769); // protocol version 1.21.4
      VarInt.write(buf, 9); // string length
      buf.writeBytes("localhost".getBytes(StandardCharsets.UTF_8));
      buf.writeShort(25565); // port
      VarInt.write(buf, 2); // next state = login

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      Object out = ch.readInbound();
      assertInstanceOf(Handshake.class, out);
      Handshake handshake = (Handshake) out;
      assertEquals(769, handshake.protocolVersion());
      assertEquals("localhost", handshake.serverAddress());
      assertEquals(25565, handshake.serverPort());
      assertEquals(2, handshake.nextState());

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode StatusRequest (empty payload)")
    void statusRequest() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // StatusRequest: packet ID 0x00, no payload
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      Object out = ch.readInbound();
      assertInstanceOf(StatusRequest.class, out);

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode KeepAlive with version-dependent encoding (1.21.4 — long)")
    void keepAliveLong() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // KeepAlive clientbound in 1.21.4: ID 0x27, payload = long
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x27);
      buf.writeLong(0xDEADBEEFCAFEBABEL);

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      Object out = ch.readInbound();
      assertInstanceOf(KeepAlive.class, out);
      assertEquals(0xDEADBEEFCAFEBABEL, ((KeepAlive) out).id());

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode KeepAlive with version-dependent encoding (1.8 — VarInt)")
    void keepAliveVarInt() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // KeepAlive clientbound in 1.8: ID 0x00, payload = VarInt
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      VarInt.write(buf, 42);

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      Object out = ch.readInbound();
      assertInstanceOf(KeepAlive.class, out);
      assertEquals(42L, ((KeepAlive) out).id());

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode BundleDelimiter (empty payload, PLAY clientbound)")
    void bundleDelimiter() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // BundleDelimiter: ID 0x00 in 1.19.4+, no payload
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      Object out = ch.readInbound();
      assertInstanceOf(BundleDelimiter.class, out);

      assertFalse(ch.finish());
    }

    /**
     * The settings packet each client layout sends right after entering PLAY, with its packet id
     * (serialised by node-minecraft-protocol, as the end-to-end bots send it).
     */
    static Stream<Arguments> clientSettingsPackets() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, 0x15, "05656e5f47420c01010201"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, 0x15, "05656e5f47420c01017f"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, 0x04, "05656e5f47420c01017f00"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_16_4, 0x05, "05656e5f47420c01017f00"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_17_1, 0x05, "05656e5f47420c01017f0001"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, 0x05, "05656e5f47420c01017f000100"),
          Arguments.of(ProtocolVersion.MINECRAFT_1_21_4, 0x0C, "05656e5f47420c01017f00010002"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientSettingsPackets")
    @DisplayName("should decode the settings a client sends on entering PLAY, to the last byte")
    void clientSettings(ProtocolVersion version, int packetId, String payloadHex) {
      MinecraftDecoder decoder =
          new MinecraftDecoder(PacketDirection.SERVERBOUND, version, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, packetId);
      buf.writeBytes(HexFormat.of().parseHex(payloadHex));

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      PlayClientSettings settings = assertInstanceOf(PlayClientSettings.class, ch.readInbound());
      assertEquals("en_GB", settings.locale());
      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Blind forwarding
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("blind forwarding")
  class BlindForwarding {

    @Test
    @DisplayName("should forward an unregistered packet as its complete frame in PLAY state")
    void unregisteredPacketId() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Packet ID 0x7F — not registered for PLAY serverbound
      byte[] payload = {0x01, 0x02, 0x03, 0x04};
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x7F);
      buf.writeBytes(payload);

      int expectedSize = buf.readableBytes();

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      // Output should be a raw ByteBuf, NOT a Packet
      Object out = ch.readInbound();
      assertInstanceOf(ByteBuf.class, out);
      assertFalse(out instanceof Packet);

      ByteBuf forwarded = (ByteBuf) out;
      // The complete frame: length prefix, then packet ID VarInt + payload
      assertEquals(expectedSize, VarInt.read(forwarded));
      assertEquals(expectedSize, forwarded.readableBytes());

      // Verify contents: packet ID 0x7F is 1 byte, then the payload
      int packetId = VarInt.read(forwarded);
      assertEquals(0x7F, packetId);

      byte[] forwardedPayload = new byte[forwarded.readableBytes()];
      forwarded.readBytes(forwardedPayload);
      assertArrayEquals(payload, forwardedPayload);
      forwarded.release();

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should forward packet with multi-byte VarInt ID as raw ByteBuf")
    void multiBytPacketId() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Packet ID 0x100 (2-byte VarInt) — not registered
      byte[] payload = {(byte) 0xAA, (byte) 0xBB};
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x100);
      buf.writeBytes(payload);

      int expectedSize = buf.readableBytes();

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      Object out = ch.readInbound();
      assertInstanceOf(ByteBuf.class, out);

      ByteBuf forwarded = (ByteBuf) out;
      assertEquals(expectedSize, VarInt.read(forwarded));
      assertEquals(expectedSize, forwarded.readableBytes());
      forwarded.release();

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should forward unknown packet with empty payload")
    void emptyPayload() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Packet ID 0x7E — unknown, no payload
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x7E);

      assertTrue(ch.writeInbound(Frames.framed(buf)));

      Object out = ch.readInbound();
      assertInstanceOf(ByteBuf.class, out);

      ByteBuf forwarded = (ByteBuf) out;
      assertEquals(1, VarInt.read(forwarded)); // length prefix
      assertEquals(1, forwarded.readableBytes()); // just the VarInt packet ID
      forwarded.release();

      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Compressed connections
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("compressed connections")
  class CompressedConnections {

    private static final int THRESHOLD = 256;
    private static final int UNREGISTERED_ID = 0x7F;
    private static final int KEEP_ALIVE_ID_1_21_4 = 0x27;

    @Test
    @DisplayName(
        "should forward an uninspected compressed frame byte for byte without inflating it")
    void forwardsWithoutInflating() {
      EmbeddedChannel ch =
          compressedChannel(PacketDirection.CLIENTBOUND, new RefusingCompressor(), true);
      ByteBuf frame = Frames.compressed(Frames.packet(UNREGISTERED_ID, new byte[2000]), 6);
      byte[] wire = ByteBufUtil.getBytes(frame);

      assertTrue(ch.writeInbound(frame));

      assertArrayEquals(wire, Frames.drain(ch.readInbound()));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName(
        "should verify untrusted frames by inflating them, then forward the original bytes")
    void verifiesUntrustedFrames() {
      CountingCompressor compressor = new CountingCompressor();
      EmbeddedChannel ch = compressedChannel(PacketDirection.SERVERBOUND, compressor, false);
      ByteBuf frame = Frames.compressed(Frames.packet(UNREGISTERED_ID, new byte[2000]), 6);
      byte[] wire = ByteBufUtil.getBytes(frame);

      assertTrue(ch.writeInbound(frame));

      assertArrayEquals(wire, Frames.drain(ch.readInbound()));
      assertEquals(1, compressor.inflations);
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should inflate and decode a compressed packet the proxy inspects")
    void decodesInspectedPacket() {
      EmbeddedChannel ch =
          compressedChannel(PacketDirection.CLIENTBOUND, new CountingCompressor(), true);
      byte[] keepAlive = Frames.packet(KEEP_ALIVE_ID_1_21_4, new byte[] {0, 0, 0, 0, 0, 0, 0, 42});

      assertTrue(ch.writeInbound(Frames.compressed(keepAlive, 6)));

      Object out = ch.readInbound();
      assertInstanceOf(KeepAlive.class, out);
      assertEquals(42L, ((KeepAlive) out).id());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should inflate when the packet id cannot be peeked, and still forward verbatim")
    void inflatesWhenPeekDeclines() {
      CountingCompressor compressor = new CountingCompressor();
      EmbeddedChannel ch = compressedChannel(PacketDirection.CLIENTBOUND, compressor, true);
      byte[] packet = Frames.packet(UNREGISTERED_ID, new byte[300]);
      ByteBuf frame =
          Frames.compressedClaiming(packet.length, ZlibStreams.withEmptyStoredBlocks(packet, 16));
      byte[] wire = ByteBufUtil.getBytes(frame);

      assertTrue(ch.writeInbound(frame));

      assertArrayEquals(wire, Frames.drain(ch.readInbound()));
      assertEquals(1, compressor.inflations);
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should forward uncompressed frames of a compressed connection verbatim")
    void forwardsUncompressedFrames() {
      EmbeddedChannel ch =
          compressedChannel(PacketDirection.CLIENTBOUND, new RefusingCompressor(), true);
      ByteBuf frame = Frames.uncompressed(Frames.packet(UNREGISTERED_ID, new byte[] {1, 2, 3}));
      byte[] wire = ByteBufUtil.getBytes(frame);

      assertTrue(ch.writeInbound(frame));

      assertArrayEquals(wire, Frames.drain(ch.readInbound()));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should reject a frame declaring more than DEFLATE can produce")
    void rejectsImpossibleDeclaredSize() {
      EmbeddedChannel ch =
          compressedChannel(PacketDirection.CLIENTBOUND, new RefusingCompressor(), true);
      byte[] tiny = Frames.zlib(new byte[300], 6);

      assertThrows(
          DecoderException.class,
          () -> ch.writeInbound(Frames.compressedClaiming(8_000_000, tiny)));
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should hand the inflated packet of a verified frame to that frame's forwarder")
    void handsInflatedPacketToItsFrame() {
      EmbeddedChannel ch =
          compressedChannel(PacketDirection.SERVERBOUND, new CountingCompressor(), false);
      MinecraftDecoder decoder = ch.pipeline().get(MinecraftDecoder.class);
      byte[] packet = Frames.packet(UNREGISTERED_ID, new byte[2000]);

      assertTrue(ch.writeInbound(Frames.compressed(packet, 6)));
      ByteBuf frame = ch.readInbound();

      assertNull(decoder.takeInflatedPacket(Unpooled.EMPTY_BUFFER), "another frame");
      ByteBuf inflated = decoder.takeInflatedPacket(frame);
      assertNotNull(inflated);
      assertArrayEquals(packet, Frames.drain(inflated));
      assertNull(decoder.takeInflatedPacket(frame), "handed over once");
      frame.release();
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should release an inflated packet nobody took once the next frame arrives")
    void releasesUntakenPacketOnNextFrame() {
      EmbeddedChannel ch =
          compressedChannel(PacketDirection.SERVERBOUND, new CountingCompressor(), false);
      RecordingAllocator alloc = new RecordingAllocator();
      ch.config().setAllocator(alloc);

      assertTrue(
          ch.writeInbound(Frames.compressed(Frames.packet(UNREGISTERED_ID, new byte[500]), 6)));
      ch.<ByteBuf>readInbound().release();
      ByteBuf kept = alloc.only();
      assertEquals(1, kept.refCnt());

      assertTrue(ch.writeInbound(Frames.uncompressed(Frames.packet(UNREGISTERED_ID, new byte[3]))));
      ch.<ByteBuf>readInbound().release();
      assertEquals(0, kept.refCnt());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should release the inflated bytes of a packet it decodes")
    void releasesDecodedPacketBytes() {
      EmbeddedChannel ch =
          compressedChannel(PacketDirection.CLIENTBOUND, new CountingCompressor(), true);
      RecordingAllocator alloc = new RecordingAllocator();
      ch.config().setAllocator(alloc);
      byte[] keepAlive = Frames.packet(KEEP_ALIVE_ID_1_21_4, new byte[] {0, 0, 0, 0, 0, 0, 0, 42});

      assertTrue(ch.writeInbound(Frames.compressed(keepAlive, 6)));

      assertInstanceOf(KeepAlive.class, ch.readInbound());
      assertEquals(0, alloc.only().refCnt());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should release the kept packet and close its decompressor when removed")
    void releasesAndClosesOnRemoval() {
      TrackingCompressor compressor = new TrackingCompressor();
      EmbeddedChannel ch = compressedChannel(PacketDirection.SERVERBOUND, compressor, false);
      RecordingAllocator alloc = new RecordingAllocator();
      ch.config().setAllocator(alloc);
      assertTrue(
          ch.writeInbound(Frames.compressed(Frames.packet(UNREGISTERED_ID, new byte[500]), 6)));
      ch.<ByteBuf>readInbound().release();

      ch.pipeline().remove(MinecraftDecoder.class);

      assertEquals(0, alloc.only().refCnt());
      assertEquals(1, compressor.closes());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should close the decompressor it replaces when compression is enabled again")
    void closesReplacedDecompressor() {
      TrackingCompressor first = new TrackingCompressor();
      TrackingCompressor second = new TrackingCompressor();
      EmbeddedChannel ch = compressedChannel(PacketDirection.CLIENTBOUND, first, true);
      MinecraftDecoder decoder = ch.pipeline().get(MinecraftDecoder.class);

      decoder.enableCompression(
          new FrameDecompressor(
              THRESHOLD, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, second),
          true);

      assertEquals(1, first.closes());
      assertEquals(0, second.closes());
      assertFalse(ch.finish());
    }

    private static EmbeddedChannel compressedChannel(
        PacketDirection direction, PacketCompressor compressor, boolean peek) {
      MinecraftDecoder decoder =
          new MinecraftDecoder(direction, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      decoder.enableCompression(
          new FrameDecompressor(
              THRESHOLD,
              direction == PacketDirection.SERVERBOUND,
              FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
              compressor),
          peek);
      return new EmbeddedChannel(decoder);
    }
  }

  @Nested
  @DisplayName("encode-only packets")
  class EncodeOnlyPackets {

    @Test
    @DisplayName("should forward encode-only packets instead of decoding them")
    void forwardsEncodeOnlyPackets() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);
      // System chat (0x73 since 1.21.2): the proxy sends it, but never needs to read it.
      ByteBuf frame = Frames.plain(Frames.packet(0x73, new byte[] {0x08, 0x00, 0x01, 0x41, 0x00}));
      byte[] wire = ByteBufUtil.getBytes(frame);

      assertTrue(ch.writeInbound(frame));

      assertArrayEquals(wire, Frames.drain(ch.readInbound()));
      assertFalse(ch.finish());
    }
  }

  /** Records the direct buffers it allocates, to check who releases them. */
  private static final class RecordingAllocator extends AbstractByteBufAllocator {
    private final List<ByteBuf> allocated = new ArrayList<>();

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
      return UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
      ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity);
      allocated.add(buf);
      return buf;
    }

    @Override
    public boolean isDirectBufferPooled() {
      return false;
    }

    /** Returns the one buffer allocated so far. */
    ByteBuf only() {
      assertEquals(1, allocated.size(), "direct buffers allocated");
      return allocated.getFirst();
    }
  }

  /** Fails the test if the decoder inflates anything. */
  private static final class RefusingCompressor implements PacketCompressor {
    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize) {
      throw new AssertionError("frame must be forwarded without inflating");
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) {
      throw new AssertionError("decoder must not deflate");
    }

    @Override
    public void close() {}
  }

  /** Counts inflations, delegating to the JDK's zlib. */
  private static final class CountingCompressor implements PacketCompressor {
    private final JavaCompressor delegate = new JavaCompressor(6);
    private int inflations;

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      inflations++;
      delegate.inflate(source, destination, uncompressedSize);
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
      delegate.deflate(source, destination);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Boundary validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("boundary validation")
  class BoundaryValidation {

    @Test
    @DisplayName("should reject packet with trailing bytes after decode")
    void trailingBytes() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // StatusRequest (ID 0x00) has empty payload, but we add trailing garbage
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      buf.writeByte(0xFF); // trailing byte — should trigger validation failure

      DecoderException ex =
          assertThrows(DecoderException.class, () -> ch.writeInbound(Frames.framed(buf)));
      String msg = Objects.requireNonNull(ex.getMessage());
      assertTrue(msg.contains("trailing bytes"));
      assertTrue(msg.contains("StatusRequest"));

      ch.finish();
    }

    @Test
    @DisplayName("should accept packet that consumes all bytes exactly")
    void exactConsumption() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // StatusRequest (ID 0x00) has empty payload — exactly 0 bytes after ID
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);

      assertTrue(ch.writeInbound(Frames.framed(buf)));
      assertInstanceOf(StatusRequest.class, ch.readInbound());

      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // State transitions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("state transitions")
  class StateTransitions {

    @Test
    @DisplayName("should use updated registry after setState")
    void stateChange() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // In HANDSHAKE state, packet ID 0x00 is Handshake
      ByteBuf handshakeBuf = Unpooled.buffer();
      VarInt.write(handshakeBuf, 0x00);
      VarInt.write(handshakeBuf, 769);
      VarInt.write(handshakeBuf, 9);
      handshakeBuf.writeBytes("localhost".getBytes(StandardCharsets.UTF_8));
      handshakeBuf.writeShort(25565);
      VarInt.write(handshakeBuf, 1); // next state = status

      assertTrue(ch.writeInbound(Frames.framed(handshakeBuf)));
      assertInstanceOf(Handshake.class, ch.readInbound());

      // Switch to STATUS state
      decoder.setState(ProtocolState.STATUS);
      assertEquals(ProtocolState.STATUS, decoder.state());

      // Now packet ID 0x00 is StatusRequest (empty payload)
      ByteBuf statusBuf = Unpooled.buffer();
      VarInt.write(statusBuf, 0x00);

      assertTrue(ch.writeInbound(Frames.framed(statusBuf)));
      assertInstanceOf(StatusRequest.class, ch.readInbound());

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should update version via setVersion")
    void versionChange() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_7_2,
              ProtocolState.HANDSHAKE);

      assertEquals(ProtocolVersion.MINECRAFT_1_7_2, decoder.version());

      decoder.setVersion(ProtocolVersion.MINECRAFT_1_21_4);
      assertEquals(ProtocolVersion.MINECRAFT_1_21_4, decoder.version());
      assertEquals(PacketDirection.SERVERBOUND, decoder.direction());
    }
  }

  // ---------------------------------------------------------------------------
  // Dead channel
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("dead channel")
  class DeadChannel {

    @Test
    @DisplayName("should produce no output on inactive channel")
    void inactiveChannel() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Close the channel, then write data
      ch.close().awaitUninterruptibly();

      // Build a valid Handshake packet
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      VarInt.write(buf, 769);
      VarInt.write(buf, 9);
      buf.writeBytes("localhost".getBytes(StandardCharsets.UTF_8));
      buf.writeShort(25565);
      VarInt.write(buf, 2);

      // writeInbound may return false or throw depending on Netty version,
      // but no Packet should appear in the inbound queue.
      ByteBuf frame = Frames.framed(buf);
      try {
        ch.writeInbound(frame);
      } catch (Exception ignored) {
        // Channel is closed — write may fail. Ensure the frame is released to
        // prevent leaks if the pipeline did not process it.
        if (frame.refCnt() > 0) {
          frame.release();
        }
      }

      assertNull(ch.readInbound());
    }

    @Test
    @DisplayName("should drop a frame that reaches it after the connection closed")
    void dropsFramesOfClosedConnection() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);
      ChannelHandlerContext ctx = ch.pipeline().context(decoder);
      ch.close().syncUninterruptibly();
      ByteBuf frame = Frames.plain(Frames.packet(0x7F, new byte[] {1, 2, 3}));
      List<Object> out = new ArrayList<>();

      decoder.decode(ctx, frame, out);

      assertTrue(out.isEmpty());
      frame.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("error handling")
  class ErrorHandling {

    @Test
    @DisplayName("should reject negative packet ID")
    void negativePacketId() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Write a VarInt encoding of -1 (5 bytes, all continuation bits + sign)
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, -1);

      assertThrows(DecoderException.class, () -> ch.writeInbound(Frames.framed(buf)));
      ch.finish();
    }

    @Test
    @DisplayName("should wrap codec exception with packet context")
    void codecException() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Handshake (ID 0x00) needs at least a VarInt + String + Short + VarInt.
      // Send only the packet ID — the codec will fail reading the first field.
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      // no payload — truncated

      DecoderException ex =
          assertThrows(DecoderException.class, () -> ch.writeInbound(Frames.framed(buf)));
      String msg = Objects.requireNonNull(ex.getMessage());
      assertTrue(msg.contains("0x0"));
      assertTrue(msg.contains("HANDSHAKE"));

      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Roundtrip with encoder
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should survive encode → decode roundtrip for Handshake")
    void handshakeRoundtrip() {
      Handshake original = new Handshake(769, "mc.example.com", 25565, 2);

      // Encode
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel encoderCh = new EmbeddedChannel(encoder);
      assertTrue(encoderCh.writeOutbound(original));
      ByteBuf wire = encoderCh.readOutbound();
      assertNotNull(wire);

      // Decode
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel decoderCh = new EmbeddedChannel(decoder);
      assertTrue(decoderCh.writeInbound(Frames.framed(wire)));

      Object out = decoderCh.readInbound();
      assertInstanceOf(Handshake.class, out);
      Handshake decoded = (Handshake) out;
      assertEquals(original.protocolVersion(), decoded.protocolVersion());
      assertEquals(original.serverAddress(), decoded.serverAddress());
      assertEquals(original.serverPort(), decoded.serverPort());
      assertEquals(original.nextState(), decoded.nextState());

      assertFalse(encoderCh.finish());
      assertFalse(decoderCh.finish());
    }

    @Test
    @DisplayName("should survive encode → decode roundtrip for KeepAlive across versions")
    void keepAliveRoundtrip() {
      KeepAlive original = new KeepAlive(123456789L);

      // Test with 1.21.4 (long encoding)
      roundtripKeepAlive(original, ProtocolVersion.MINECRAFT_1_21_4);

      // Test with 1.8 (VarInt encoding — value must fit in int)
      roundtripKeepAlive(new KeepAlive(42), ProtocolVersion.MINECRAFT_1_8);
    }

    private void roundtripKeepAlive(KeepAlive original, ProtocolVersion version) {
      // Use PLAY clientbound — KeepAlive is registered in both directions
      MinecraftEncoder encoder =
          new MinecraftEncoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY);
      EmbeddedChannel encoderCh = new EmbeddedChannel(encoder);
      assertTrue(encoderCh.writeOutbound(original));
      ByteBuf wire = encoderCh.readOutbound();
      assertNotNull(wire);

      MinecraftDecoder decoder =
          new MinecraftDecoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY);
      EmbeddedChannel decoderCh = new EmbeddedChannel(decoder);
      assertTrue(decoderCh.writeInbound(Frames.framed(wire)));

      Object out = decoderCh.readInbound();
      assertInstanceOf(KeepAlive.class, out);
      assertEquals(original.id(), ((KeepAlive) out).id());

      assertFalse(encoderCh.finish());
      assertFalse(decoderCh.finish());
    }
  }
}
