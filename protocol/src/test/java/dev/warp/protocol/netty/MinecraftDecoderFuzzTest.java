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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_7_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_8;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_2;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.HeapAllocations;
import dev.warp.protocol.fuzz.Rejections;
import dev.warp.protocol.fuzz.TrackingAllocator;
import dev.warp.protocol.fuzz.Wire;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketReader;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.PacketWatch;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.EncryptionResponse;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginPluginRequest;
import dev.warp.protocol.packet.login.LoginPluginResponse;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.login.SetCompression;
import dev.warp.protocol.packet.play.AcknowledgeConfiguration;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.LegacyPlayerInfo;
import dev.warp.protocol.packet.play.PlayClientSettings;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SwitchPacketFixtures;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.PongResponse;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.DataFormatException;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes {@link MinecraftDecoder}, behind a {@link FrameDecoder} as in the proxy, in every state,
 * direction and protocol version, on uncompressed and compressed connections.
 *
 * <p>The input is the uncompressed stream of packets a peer sends; the test frames them for the
 * connection as the peer would, truthfully or, when compressing, declaring wrong sizes or sending
 * damaged streams ({@link Wire#compressedBody}). Every frame must come out decoded, watched or
 * forwarded byte for byte as {@link Wire#decompressed} says, until one Warp may reject is rejected
 * with a {@link DecoderException}. A decoded packet must encode to bytes that decode to a packet
 * encoding to the same bytes. Peeking at compressed frames must not change what comes out of them,
 * except that it may forward a lying frame it does not read, as the proxy does for backends.
 */
@DisplayName("MinecraftDecoder fuzzing")
class MinecraftDecoderFuzzTest {

  /**
   * The states an input chooses from, by place. Spelled out rather than {@code values()}, so that a
   * kept input means the same state whatever the order of the enum.
   */
  private static final List<ProtocolState> STATES =
      List.of(
          ProtocolState.HANDSHAKE,
          ProtocolState.STATUS,
          ProtocolState.LOGIN,
          ProtocolState.CONFIGURATION,
          ProtocolState.PLAY);

  /** The directions an input chooses from, by place, spelled out as {@link #STATES}. */
  private static final List<PacketDirection> DIRECTIONS =
      List.of(PacketDirection.SERVERBOUND, PacketDirection.CLIENTBOUND);

  /** The largest protocol number an input can name: two bytes. */
  private static final int MAX_PROTOCOL = 0xFFFF;

  private static final int MAX_SIZE = FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE;

  /**
   * Heap a decoded byte may cost: a one-byte string, for one, takes a String and its array. A
   * length field that sizes an allocation by itself breaks this bound for any input.
   */
  private static final long HEAP_PER_PACKET_BYTE = 64;

  /** Heap the pipeline costs whatever the input: handlers, the inflater, error messages. */
  private static final long HEAP_PER_CONNECTION = 1 << 20;

  @FuzzTest
  @DisplayName("should decode, watch or forward every frame until rejecting one, leaking nothing")
  void decode(FuzzedDataProvider data) {
    ProtocolState state = data.pickValue(STATES);
    PacketDirection direction = data.pickValue(DIRECTIONS);
    ProtocolVersion version = versionOf(data.consumeInt(0, MAX_PROTOCOL));
    int threshold = data.consumeInt(0, 255) - 1;
    boolean compressed = threshold >= 0;
    Connection connection =
        new Connection(
            state,
            direction,
            version,
            threshold,
            compressed && data.consumeBoolean(),
            compressed ? data.consumeInt(Wire.TRUTHFUL, Wire.TRUNCATED) : Wire.TRUTHFUL,
            compressed ? data.consumeInt(0, 255) : 0);
    boolean takeInflated = data.consumeBoolean();
    List<byte[]> packets = Wire.frames(data.consumeRemainingAsBytes()).payloads();

    List<Frame> frames = packets.stream().map(connection::frame).toList();
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    frames.forEach(frame -> wire.writeBytes(frame.bytes()));
    byte[] stream = wire.toByteArray();
    // Allocations follow the bytes received, or a size the decoder checked first.
    long maxCapacity =
        Math.max(
            64 + 2L * stream.length, frames.stream().mapToLong(Frame::allocatable).max().orElse(0));

    Outcome inflating = connection.decode(stream, false, takeInflated);
    List<String> inflated = check(connection, frames, inflating, false, takeInflated);
    assertTrue(
        inflating.largestCapacity() <= maxCapacity,
        () -> "allocated " + inflating.largestCapacity() + " bytes for " + stream.length);
    if (compressed) {
      Outcome peeking = connection.decode(stream, true, takeInflated);
      List<String> peeked = check(connection, frames, peeking, true, takeInflated);
      assertTrue(
          peeking.largestCapacity() <= maxCapacity,
          () -> "allocated " + peeking.largestCapacity() + " bytes for " + stream.length);
      int agreed = (int) frames.stream().takeWhile(frame -> frame.packet() != null).count();
      assertEquals(
          inflated.subList(0, Math.min(agreed, inflated.size())),
          peeked.subList(0, Math.min(agreed, peeked.size())),
          "peeking changed frames Warp accepts");
    }

    long packetBytes = packets.stream().mapToLong(packet -> packet.length).sum();
    HeapAllocations.assertAtMost(
        HEAP_PER_PACKET_BYTE * packetBytes + HEAP_PER_CONNECTION,
        () -> connection.decode(stream, false, takeInflated));
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    FuzzSeeds seeds = new FuzzSeeds(MinecraftDecoderFuzzTest.class, "decode");
    ProtocolVersion latest = ProtocolVersion.latest();
    addSeed(seeds, uncompressed(ProtocolState.HANDSHAKE, PacketDirection.SERVERBOUND, latest));
    addSeed(seeds, uncompressed(ProtocolState.STATUS, PacketDirection.SERVERBOUND, latest));
    addSeed(seeds, uncompressed(ProtocolState.STATUS, PacketDirection.CLIENTBOUND, latest));
    for (ProtocolVersion version :
        List.of(
            MINECRAFT_1_7_6,
            MINECRAFT_1_8,
            MINECRAFT_1_12_2,
            MINECRAFT_1_16_4,
            MINECRAFT_1_19,
            MINECRAFT_1_19_3,
            MINECRAFT_1_20_1,
            MINECRAFT_1_20_2,
            MINECRAFT_1_20_6,
            MINECRAFT_1_21_4,
            latest)) {
      for (ProtocolState state : STATES) {
        if (state == ProtocolState.LOGIN
            || state == ProtocolState.PLAY
            || (state == ProtocolState.CONFIGURATION && version.supportsConfigurationState())) {
          addSeed(seeds, uncompressed(state, PacketDirection.SERVERBOUND, version));
          addSeed(seeds, uncompressed(state, PacketDirection.CLIENTBOUND, version));
        }
      }
    }
    // Compressed connections: everything compressed, the vanilla threshold, and compressed
    // packets below it, which a server rejects and a client accepts.
    ProtocolState play = ProtocolState.PLAY;
    ProtocolState login = ProtocolState.LOGIN;
    PacketDirection serverbound = PacketDirection.SERVERBOUND;
    PacketDirection clientbound = PacketDirection.CLIENTBOUND;
    addSeed(seeds, compressed(play, serverbound, MINECRAFT_1_21_4, 0, false, Wire.TRUTHFUL, 0));
    addSeed(seeds, compressed(play, clientbound, MINECRAFT_1_20_1, 64, false, Wire.TRUTHFUL, 0));
    addSeed(seeds, compressed(login, serverbound, MINECRAFT_1_8, 64, true, Wire.TRUTHFUL, 0));
    addSeed(seeds, compressed(login, clientbound, MINECRAFT_1_8, 64, true, Wire.TRUTHFUL, 0));
    // A backend lying about sizes or sending damaged streams: forwarded when peeking skips them.
    for (int framing = Wire.ONE_MORE; framing <= Wire.TRUNCATED; framing++) {
      addSeed(seeds, compressed(play, clientbound, MINECRAFT_1_20_1, 0, false, framing, 9));
    }
    addSeed(seeds, compressed(play, serverbound, latest, 0, false, Wire.CORRUPT, 9));
    seeds.verify();
  }

  // ---------------------------------------------------------------------------
  // Checks
  // ---------------------------------------------------------------------------

  /**
   * Checks what came out of each frame, against what Warp makes of it, and returns it: the packet
   * decoded, as its encoding, or the frame watched or forwarded, until the frame rejected.
   */
  private static List<String> check(
      Connection connection,
      List<Frame> frames,
      Outcome outcome,
      boolean peeking,
      boolean takeInflated) {
    List<Object> outputs = outcome.outputs();
    List<String> summary = new ArrayList<>();
    int next = 0;
    for (Frame frame : frames) {
      if (next == outputs.size()) {
        // Nothing came out of this frame or any after it: it was rejected.
        assertEquals(1, outcome.failures().size(), "frames lost without a failure");
        assertTrue(connection.mayReject(frame), "rejected a frame Warp forwards");
        Rejections.assertRejection(
            outcome.failures().getFirst(),
            IndexOutOfBoundsException.class,
            DataFormatException.class);
        summary.add("rejected");
        return summary;
      }
      Object output = outputs.get(next++);
      byte[] packet = frame.packet();
      if (packet == null) {
        assertTrue(peeking && frame.peekable(), "came out of a frame Warp rejects");
        summary.add("forwarded " + forwarded(frame, output, false));
        continue;
      }
      int id = packetId(packet);
      assertTrue(id >= 0, "came out of a frame without a packet id");
      boolean mustKeep = takeInflated && frame.compressed() && !peeking;
      switch (connection.registry().lookup(connection.version(), id)) {
        case null -> summary.add("forwarded " + forwarded(frame, output, mustKeep));
        case PacketCodec<?> codec -> {
          Packet decoded = assertInstanceOf(Packet.class, output, "forwarded a packet it decodes");
          summary.add("decoded " + HexFormat.of().formatHex(reencode(connection, id, decoded)));
          assertSame(codec, connection.encoding(decoded).codec(), "decodes with another codec");
        }
        case PacketWatch<?> _ -> {
          String report = "";
          if (output instanceof Packet reported) {
            report = reported.getClass().getSimpleName() + " ";
            assertTrue(next < outputs.size(), "reported a watched packet, then lost its frame");
            output = outputs.get(next++);
          }
          summary.add("watched " + report + forwarded(frame, output, mustKeep));
        }
      }
    }
    assertEquals(outputs.size(), next, "came out of no frame");
    assertTrue(outcome.failures().isEmpty(), "failed once every frame came out");
    return summary;
  }

  /** Checks that a frame came out as received, with its inflated packet if taken, and names it. */
  private static String forwarded(Frame frame, Object output, boolean mustKeep) {
    Forwarded forwarded = assertInstanceOf(Forwarded.class, output, "decoded a packet it forwards");
    assertArrayEquals(frame.bytes(), forwarded.frame(), "forwarded another frame");
    if (forwarded.inflated() != null) {
      assertArrayEquals(frame.packet(), forwarded.inflated(), "inflated another packet");
    } else {
      assertFalse(mustKeep, "did not keep the packet it inflated for the relay");
    }
    return HexFormat.of().formatHex(forwarded.frame());
  }

  /**
   * Encodes a decoded packet, decodes that, and checks that it encodes to the same bytes: what the
   * proxy writes of a packet it read must read back the same.
   */
  private static byte[] reencode(Connection connection, int id, Packet packet) {
    String name = packet.getClass().getSimpleName();
    PacketRegistry.Encoding encoding = connection.encoding(packet);
    assertEquals(id, encoding.packetId(), () -> name + " encodes to another packet id");
    byte[] once = encode(encoding.codec(), packet, connection.version());
    ByteBuf buf = Unpooled.wrappedBuffer(once);
    Packet again = encoding.codec().decode(buf, connection.version());
    assertFalse(buf.isReadable(), () -> name + " does not read all it writes");
    assertArrayEquals(
        once, encode(encoding.codec(), again, connection.version()), () -> name + " round trip");
    return once;
  }

  @SuppressWarnings("unchecked") // the registry pairs each packet class with its own codec
  private static byte[] encode(PacketCodec<?> codec, Packet packet, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    ((PacketCodec<Packet>) codec).encode(packet, buf, version);
    return ByteBufUtil.getBytes(buf);
  }

  /** The id a packet starts with, or {@code -1} if it starts with no valid, non-negative one. */
  private static int packetId(byte[] packet) {
    Wire.VarNum id = Wire.varNum(packet, 0, 5);
    return id == null ? -1 : Math.max(-1, (int) id.value());
  }

  /**
   * The version an input names by protocol number, so that a kept input keeps its meaning as
   * versions are added: the newest version of that protocol or, if none has it, of the newest
   * protocol below it, or the oldest version.
   */
  private static ProtocolVersion versionOf(int protocol) {
    ProtocolVersion chosen = ProtocolVersion.oldest();
    for (ProtocolVersion version : ProtocolVersion.values()) {
      if (version.protocol() <= protocol) {
        chosen = version;
      }
    }
    return chosen;
  }

  // ---------------------------------------------------------------------------
  // Connection
  // ---------------------------------------------------------------------------

  /**
   * The inbound side of a connection.
   *
   * @param state the protocol state
   * @param direction whose packets it receives: serverbound from a player, clientbound from a
   *     backend
   * @param version the protocol version
   * @param threshold the compression threshold, or {@code -1} if the connection is uncompressed
   * @param compressAll whether the peer compresses packets below the threshold too
   * @param framing how the peer frames the packets it compresses ({@link Wire#compressedBody})
   * @param at where that framing damages a stream
   */
  private record Connection(
      ProtocolState state,
      PacketDirection direction,
      ProtocolVersion version,
      int threshold,
      boolean compressAll,
      int framing,
      int at) {

    boolean compressed() {
      return threshold >= 0;
    }

    /** Whether the peer is a player, whose compressed packets must not be below the threshold. */
    boolean fromPlayer() {
      return direction == PacketDirection.SERVERBOUND;
    }

    PacketRegistry registry() {
      return StateRegistry.get(state, direction);
    }

    PacketRegistry.Encoding encoding(Packet packet) {
      return registry().encoding(version, packet.getClass());
    }

    /** Returns the frame the peer sends for a packet, and what Warp must make of it. */
    Frame frame(byte[] packet) {
      if (!compressed()) {
        return new Frame(Wire.frame(packet), packet, false, false, 0);
      }
      byte[] body = Wire.compressedBody(packet, threshold, compressAll, framing, at);
      Wire.VarNum dataLength = Objects.requireNonNull(Wire.varNum(body, 0, 5));
      int declared = (int) dataLength.value();
      int compressedLength = body.length - dataLength.length();
      // What the decoder checks before peeking: the size, against the threshold and against what
      // DEFLATE can expand the stream to (1032:1, plus a maximal match).
      boolean peekable =
          declared > 0
              && !(fromPlayer() && declared < threshold)
              && declared <= 1032L * compressedLength + 258;
      return new Frame(
          Wire.frame(body),
          Wire.decompressed(body, threshold, fromPlayer(), MAX_SIZE),
          declared != 0,
          peekable,
          peekable && declared <= MAX_SIZE ? declared : 0);
    }

    /**
     * Whether the decoder may reject a frame: Warp rejects it, or its packet has no valid id, or
     * the decoder reads the packet, which may be malformed. A frame forwarded unread never is.
     */
    boolean mayReject(Frame frame) {
      byte[] packet = frame.packet();
      return packet == null
          || packetId(packet) < 0
          || registry().lookup(version, packetId(packet)) != null;
    }

    /**
     * Decodes a stream as the proxy does, and checks that every buffer was released.
     *
     * @param peek whether to forward compressed frames without inflating those the id allows, as
     *     the proxy does for backends
     * @param takeInflated whether to take the bytes inflated to verify forwarded frames, as the
     *     proxy's relay does when it must re-encode them
     */
    Outcome decode(byte[] stream, boolean peek, boolean takeInflated) {
      TrackingAllocator alloc = new TrackingAllocator();
      MinecraftDecoder decoder = new MinecraftDecoder(direction, version, state);
      if (compressed()) {
        decoder.enableCompression(
            new FrameDecompressor(threshold, fromPlayer(), MAX_SIZE, new JavaCompressor(6)), peek);
      }
      Relay relay = new Relay(decoder, takeInflated);
      EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), decoder, relay);
      channel.config().setAllocator(alloc);
      channel.writeInbound(alloc.buffer(stream.length).writeBytes(stream));
      channel.finishAndReleaseAll();
      alloc.assertAllReleased();
      return new Outcome(relay.outputs, relay.failures, alloc.largestCapacity());
    }
  }

  /**
   * A frame the peer sends, and what Warp must make of it.
   *
   * @param bytes the frame, length prefix included
   * @param packet the packet it carries ({@link Wire#decompressed}), or {@code null} if Warp
   *     rejects it
   * @param compressed whether it declares a Data Length other than 0
   * @param peekable whether a decoder peeking at compressed frames may forward it without inflating
   *     it: it is compressed, and its declared size passes the checks made before peeking
   * @param allocatable the most bytes the decoder may allocate to inflate it
   */
  @SuppressWarnings("ArrayRecordComponent") // compared with assertArrayEquals, never with equals
  private record Frame(
      byte[] bytes,
      byte @Nullable [] packet,
      boolean compressed,
      boolean peekable,
      long allocatable) {}

  /**
   * What came out of a stream.
   *
   * @param outputs in order, each a {@link Packet} or a {@link Forwarded} frame
   * @param failures the exceptions caught; the channel closed on the first
   * @param largestCapacity the largest buffer allocated
   */
  private record Outcome(List<Object> outputs, List<Throwable> failures, int largestCapacity) {}

  /**
   * A frame forwarded as received.
   *
   * @param frame its bytes
   * @param inflated the packet the decoder inflated to verify it, if taken and inflated
   */
  @SuppressWarnings("ArrayRecordComponent") // compared with assertArrayEquals, never with equals
  private record Forwarded(byte[] frame, byte @Nullable [] inflated) {}

  /** The end of the pipeline, standing for the proxy's relay: it records and releases. */
  private static final class Relay extends ChannelInboundHandlerAdapter {

    private final MinecraftDecoder decoder;
    private final boolean takeInflated;
    private final List<Object> outputs = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();

    Relay(MinecraftDecoder decoder, boolean takeInflated) {
      this.decoder = decoder;
      this.takeInflated = takeInflated;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (!(msg instanceof ByteBuf frame)) {
        outputs.add(msg);
        return;
      }
      try {
        ByteBuf inflated = takeInflated ? decoder.takeInflatedPacket(frame) : null;
        byte[] packet = null;
        if (inflated != null) {
          packet = ByteBufUtil.getBytes(inflated);
          inflated.release();
        }
        outputs.add(new Forwarded(ByteBufUtil.getBytes(frame), packet));
      } finally {
        frame.release();
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      failures.add(cause);
      var _ = ctx.close(); // as the proxy does
    }
  }

  // ---------------------------------------------------------------------------
  // Seeds
  // ---------------------------------------------------------------------------

  private static final UUID ALICE = UUID.fromString("5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01");

  private static final UUID SESSION = UUID.fromString("0e1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b");

  private static final byte[] REASON = "{\"text\":\"Bye\"}".getBytes(StandardCharsets.UTF_8);

  /** The name of each framing of {@link Wire#compressedBody}, by its number, for seed names. */
  private static final List<String> FRAMINGS =
      List.of("truthful", "one-more", "one-less", "beyond-deflate", "corrupt", "truncated");

  /**
   * The signing fields of a command from 1.19.3 to 1.20.4: timestamp and salt, no argument
   * signature, and a last-seen update acknowledging nothing.
   */
  private static final int UNSIGNED_COMMAND_FIELDS = 2 * Long.BYTES + 1 + 1 + 3;

  /** A valid body of each packet the decoder reads, by its reader, at a given version. */
  private static final Map<PacketReader, Function<ProtocolVersion, byte[]>> SAMPLES =
      Map.ofEntries(
          sample(Handshake.CODEC, v -> new Handshake(v.protocol(), "localhost", 25565, 2)),
          sample(StatusRequest.CODEC, v -> new StatusRequest()),
          sample(PingRequest.CODEC, v -> new PingRequest(42)),
          sample(StatusResponse.CODEC, v -> new StatusResponse("{\"description\":\"Warp\"}")),
          sample(PongResponse.CODEC, v -> new PongResponse(42)),
          sample(LoginStart.CODEC, v -> new LoginStart("Alice", ALICE)),
          sample(
              EncryptionResponse.CODEC,
              v ->
                  new EncryptionResponse(
                      new byte[16], new EncryptionResponse.EncryptedToken(new byte[4]))),
          sample(LoginPluginResponse.CODEC, v -> new LoginPluginResponse(1, true, new byte[] {1})),
          sample(LoginAcknowledged.CODEC, v -> new LoginAcknowledged()),
          sample(LoginDisconnect.CODEC, v -> new LoginDisconnect("{\"text\":\"Bye\"}")),
          sample(
              EncryptionRequest.CODEC,
              v -> new EncryptionRequest("", new byte[16], new byte[4], true)),
          sample(
              LoginSuccess.CODEC,
              v ->
                  new LoginSuccess(
                      ALICE,
                      "Alice",
                      List.of(new LoginSuccess.Property("textures", "e30=", "c2ln")),
                      false,
                      v.isAtLeast(MINECRAFT_26_2) ? SESSION : null)),
          sample(SetCompression.CODEC, v -> new SetCompression(256)),
          sample(
              LoginPluginRequest.CODEC,
              v -> new LoginPluginRequest(1, "velocity:player_info", new byte[] {1})),
          sample(
              ClientInformation.CODEC,
              v ->
                  new ClientInformation(
                      "en_us", (byte) 10, 0, true, (byte) 0x7f, 1, false, true, 0)),
          sample(AcknowledgeFinishConfiguration.CODEC, v -> new AcknowledgeFinishConfiguration()),
          sample(KeepAlive.CODEC, v -> new KeepAlive(42)),
          sample(ConfigDisconnect.CODEC, v -> new ConfigDisconnect(REASON)),
          sample(FinishConfiguration.CODEC, v -> new FinishConfiguration()),
          sample(LegacyChatMessage.CODEC, v -> new LegacyChatMessage("/server lobby")),
          sample(
              ChatCommand.CODEC,
              v ->
                  new ChatCommand(
                      "server lobby",
                      new byte
                          [v.isAtLeast(MINECRAFT_1_19_3) && v.isOlderThan(MINECRAFT_1_20_5)
                              ? UNSIGNED_COMMAND_FIELDS
                              : 0],
                      0)),
          sample(AcknowledgeConfiguration.CODEC, v -> new AcknowledgeConfiguration()),
          sample(
              PlayClientSettings.CODEC,
              v ->
                  new PlayClientSettings(
                      "en_us", (byte) 10, 0, true, (byte) 0, (byte) 0x7f, 1, false, true, 0)),
          sample(BundleDelimiter.CODEC, v -> new BundleDelimiter()),
          sample(PlayDisconnect.CODEC, v -> new PlayDisconnect(REASON)),
          sample(StartConfiguration.CODEC, v -> new StartConfiguration()),
          fixture(BossBar.WATCH, "boss_bar_add"),
          fixture(LegacyPlayerInfo.WATCH, "legacy_player_info_add"),
          fixture(PlayerInfo.WATCH, "player_info_add"),
          fixture(PlayerInfoUpdate.WATCH, "player_info_update_all"),
          Map.entry(
              PlayerInfoRemove.WATCH,
              v -> encode(PlayerInfoRemove.CODEC, new PlayerInfoRemove(List.of(ALICE)), v)),
          Map.entry(
              JoinGame.CODEC,
              v ->
                  v.supportsConfigurationState()
                      ? encode(
                          JoinGame.CODEC,
                          new JoinGame(42, false, new JoinGame.Opaque(new byte[] {1, 2, 3})),
                          v)
                      : SwitchPacketFixtures.bytes(v, "join_game")));

  private static Connection uncompressed(
      ProtocolState state, PacketDirection direction, ProtocolVersion version) {
    return new Connection(state, direction, version, -1, false, Wire.TRUTHFUL, 0);
  }

  private static Connection compressed(
      ProtocolState state,
      PacketDirection direction,
      ProtocolVersion version,
      int threshold,
      boolean compressAll,
      int framing,
      int at) {
    return new Connection(state, direction, version, threshold, compressAll, framing, at);
  }

  /**
   * Adds a seed holding one valid packet of each kind the decoder reads in the state, direction and
   * version of a connection, framed for it.
   */
  private static void addSeed(FuzzSeeds seeds, Connection connection) {
    ProtocolVersion version = connection.version();
    assertSame(version, versionOf(version.protocol()), "a seed names another version");
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    for (int id = 0; id < 0x100; id++) {
      PacketReader reader = connection.registry().lookup(version, id);
      if (reader != null) {
        Function<ProtocolVersion, byte[]> sample = SAMPLES.get(reader);
        assertNotNull(sample, () -> "no sample of a packet read in " + connection);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.writeBytes(Wire.varInt(id));
        packet.writeBytes(sample.apply(version));
        stream.writeBytes(Wire.frame(packet.toByteArray()));
      }
    }
    List<String> name =
        new ArrayList<>(
            List.of(
                version.name(),
                connection.state().name().toLowerCase(Locale.ROOT),
                connection.direction().name().toLowerCase(Locale.ROOT)));
    // Choices: state, direction, protocol (two bytes, high first), threshold + 1 (0 for none),
    // then if compressed: compress all, framing and where it damages a stream; then take the
    // inflated packets.
    List<Integer> choices =
        new ArrayList<>(
            List.of(
                STATES.indexOf(connection.state()),
                DIRECTIONS.indexOf(connection.direction()),
                version.protocol() >> 8,
                version.protocol() & 0xFF,
                connection.threshold() + 1));
    if (connection.compressed()) {
      name.add("threshold-" + connection.threshold());
      if (connection.compressAll()) {
        name.add("all-compressed");
      }
      if (connection.framing() != Wire.TRUTHFUL) {
        name.add(FRAMINGS.get(connection.framing()));
      }
      choices.addAll(
          List.of(connection.compressAll() ? 1 : 0, connection.framing(), connection.at()));
    }
    choices.add(connection.compressed() ? 1 : 0);
    seeds.add(
        String.join("-", name),
        stream.toByteArray(),
        choices.stream().mapToInt(Integer::intValue).toArray());
  }

  private static <T extends Packet>
      Map.Entry<PacketReader, Function<ProtocolVersion, byte[]>> sample(
          PacketCodec<T> codec, Function<ProtocolVersion, T> packet) {
    return Map.entry(codec, version -> encode(codec, packet.apply(version), version));
  }

  private static Map.Entry<PacketReader, Function<ProtocolVersion, byte[]>> fixture(
      PacketWatch<?> watch, String name) {
    return Map.entry(watch, version -> SwitchPacketFixtures.bytes(version, name));
  }
}
