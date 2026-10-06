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
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_7_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.ZlibStreams;
import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.HeapAllocations;
import dev.warp.protocol.fuzz.Rejections;
import dev.warp.protocol.fuzz.TrackingAllocator;
import dev.warp.protocol.fuzz.Wire;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

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
 * connection as the peer would. Every frame must come out decoded, or forwarded byte for byte,
 * until one is rejected with a {@link DecoderException}. A decoded packet must encode to bytes that
 * decode to a packet encoding to the same bytes. A compressed frame must come out the same whether
 * the decoder peeks at its packet id or inflates it.
 */
@DisplayName("MinecraftDecoder fuzzing")
class MinecraftDecoderFuzzTest {

  private static final List<ProtocolState> STATES = List.of(ProtocolState.values());

  private static final List<PacketDirection> DIRECTIONS = List.of(PacketDirection.values());

  private static final List<ProtocolVersion> VERSIONS = ProtocolVersion.values();

  /**
   * Heap a decoded byte may cost: a one-byte string, for one, takes a String and its array. A
   * length field that sizes an allocation by itself breaks this bound for any input.
   */
  private static final long HEAP_PER_PACKET_BYTE = 64;

  /** Heap the pipeline costs whatever the input: handlers, the inflater, error messages. */
  private static final long HEAP_PER_CONNECTION = 1 << 20;

  @FuzzTest
  @DisplayName("should decode or forward every frame until rejecting one, leaking nothing")
  void decode(FuzzedDataProvider data) {
    Connection connection =
        new Connection(
            data.pickValue(STATES),
            data.pickValue(DIRECTIONS),
            data.pickValue(VERSIONS),
            data.consumeInt(0, 255) - 1,
            data.consumeBoolean());
    boolean takeInflated = data.consumeBoolean();
    List<byte[]> packets = Wire.frames(data.consumeRemainingAsBytes()).payloads();

    List<byte[]> frames = packets.stream().map(connection::frame).toList();
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    frames.forEach(wire::writeBytes);
    byte[] stream = wire.toByteArray();

    Outcome inflating = connection.decode(stream, false, takeInflated);
    List<String> decoded = check(connection, packets, frames, inflating);
    if (connection.compressed()) {
      Outcome peeking = connection.decode(stream, true, takeInflated);
      assertEquals(decoded, check(connection, packets, frames, peeking), "peeking changed frames");
    }

    // Allocations follow the bytes received, never the lengths they declare.
    int largestPacket = packets.stream().mapToInt(packet -> packet.length).max().orElse(0);
    assertTrue(
        inflating.largestRequest() <= Math.max(64 + 2L * stream.length, largestPacket),
        () -> "allocated " + inflating.largestRequest() + " bytes for " + stream.length);
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
    addSeed(seeds, ProtocolState.HANDSHAKE, PacketDirection.SERVERBOUND, latest, -1, false);
    addSeed(seeds, ProtocolState.STATUS, PacketDirection.SERVERBOUND, latest, -1, false);
    addSeed(seeds, ProtocolState.STATUS, PacketDirection.CLIENTBOUND, latest, -1, false);
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
            MINECRAFT_1_20_5,
            MINECRAFT_1_21_4,
            latest)) {
      for (ProtocolState state : ProtocolState.values()) {
        if (state == ProtocolState.LOGIN
            || state == ProtocolState.PLAY
            || (state == ProtocolState.CONFIGURATION && version.supportsConfigurationState())) {
          addSeed(seeds, state, PacketDirection.SERVERBOUND, version, -1, false);
          addSeed(seeds, state, PacketDirection.CLIENTBOUND, version, -1, false);
        }
      }
    }
    // Compressed connections: everything compressed, the vanilla threshold, and compressed
    // packets below it, which a server rejects and a client accepts.
    addSeed(seeds, ProtocolState.PLAY, PacketDirection.SERVERBOUND, MINECRAFT_1_21_4, 0, false);
    addSeed(seeds, ProtocolState.PLAY, PacketDirection.CLIENTBOUND, MINECRAFT_1_20_1, 64, false);
    addSeed(seeds, ProtocolState.LOGIN, PacketDirection.SERVERBOUND, MINECRAFT_1_8, 64, true);
    addSeed(seeds, ProtocolState.LOGIN, PacketDirection.CLIENTBOUND, MINECRAFT_1_8, 64, true);
    seeds.verify();
  }

  // ---------------------------------------------------------------------------
  // Checks
  // ---------------------------------------------------------------------------

  /**
   * Checks what came out of each frame, and returns it: the packet decoded, as its encoding, or the
   * frame forwarded.
   */
  private static List<String> check(
      Connection connection, List<byte[]> packets, List<byte[]> frames, Outcome outcome) {
    List<Object> outputs = outcome.outputs();
    List<String> summary = new ArrayList<>();
    for (int i = 0; i < outputs.size(); i++) {
      Wire.VarNum id = Wire.varNum(packets.get(i), 0, 5);
      assertNotNull(id, "came out of a frame without a packet id");
      PacketCodec<?> codec = connection.registry().lookup(connection.version(), (int) id.value());
      switch (outputs.get(i)) {
        case Forwarded forwarded -> {
          assertNull(codec, "forwarded a packet it decodes");
          assertArrayEquals(frames.get(i), forwarded.frame(), "forwarded another frame");
          if (forwarded.inflated() != null) {
            assertArrayEquals(packets.get(i), forwarded.inflated(), "inflated another packet");
          }
          summary.add("forwarded " + HexFormat.of().formatHex(forwarded.frame()));
        }
        case Packet packet -> {
          assertNotNull(codec, "decoded a packet it forwards");
          byte[] encoded = reencode(connection, (int) id.value(), packet);
          summary.add("decoded " + HexFormat.of().formatHex(encoded));
        }
        default -> throw new AssertionError("Unexpected output " + outputs.get(i));
      }
    }
    List<Throwable> failures = outcome.failures();
    if (failures.isEmpty()) {
      assertEquals(frames.size(), outputs.size(), "frames lost without a failure");
    } else {
      assertEquals(1, failures.size(), "frames decoded after a failure");
      assertTrue(outputs.size() < frames.size(), "a frame failed and came out");
      Rejections.assertRejection(
          failures.getFirst(), IndexOutOfBoundsException.class, DataFormatException.class);
      summary.add("rejected");
    }
    return summary;
  }

  /**
   * Encodes a decoded packet, decodes that, and checks that it encodes to the same bytes: what the
   * proxy writes of a packet it read must read back the same.
   */
  private static byte[] reencode(Connection connection, int id, Packet packet) {
    String name = packet.getClass().getSimpleName();
    PacketRegistry.Encoding encoding =
        connection.registry().encoding(connection.version(), packet.getClass());
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
   */
  private record Connection(
      ProtocolState state,
      PacketDirection direction,
      ProtocolVersion version,
      int threshold,
      boolean compressAll) {

    boolean compressed() {
      return threshold >= 0;
    }

    PacketRegistry registry() {
      return StateRegistry.get(state, direction);
    }

    /** Returns the frame the peer sends for a packet. */
    byte[] frame(byte[] packet) {
      if (!compressed()) {
        return Wire.frame(packet);
      }
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      if (packet.length < threshold && !compressAll) {
        body.write(0);
        body.writeBytes(packet);
      } else {
        body.writeBytes(Wire.varInt(packet.length));
        body.writeBytes(ZlibStreams.zlib(packet, 6, Deflater.DEFAULT_STRATEGY));
      }
      return Wire.frame(body.toByteArray());
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
        boolean fromPlayer = direction == PacketDirection.SERVERBOUND;
        decoder.enableCompression(
            new FrameDecompressor(
                threshold,
                fromPlayer,
                FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
                new JavaCompressor(6)),
            peek);
      }
      Relay relay = new Relay(decoder, takeInflated);
      EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), decoder, relay);
      channel.config().setAllocator(alloc);
      channel.writeInbound(alloc.buffer(stream.length).writeBytes(stream));
      channel.finishAndReleaseAll();
      alloc.assertAllReleased();
      return new Outcome(relay.outputs, relay.failures, alloc.largestRequest());
    }
  }

  /**
   * What came out of a stream.
   *
   * @param outputs per frame in order, a {@link Packet} or a {@link Forwarded} frame
   * @param failures the exceptions caught; the channel closed on the first
   * @param largestRequest the largest buffer requested
   */
  private record Outcome(List<Object> outputs, List<Throwable> failures, int largestRequest) {}

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

  private static final byte[] REASON = "{\"text\":\"Bye\"}".getBytes(StandardCharsets.UTF_8);

  /** A valid body of each packet the decoder decodes, at a given version. */
  private static final Map<PacketCodec<?>, Function<ProtocolVersion, byte[]>> SAMPLES =
      Map.ofEntries(
          sample(Handshake.CODEC, v -> new Handshake(v.protocol(), "localhost", 25565, 2)),
          sample(StatusRequest.CODEC, v -> new StatusRequest()),
          sample(PingRequest.CODEC, v -> new PingRequest(42)),
          sample(StatusResponse.CODEC, v -> new StatusResponse("{\"description\":\"Warp\"}")),
          sample(PongResponse.CODEC, v -> new PongResponse(42)),
          sample(LoginStart.CODEC, v -> new LoginStart("Alice", ALICE)),
          sample(EncryptionResponse.CODEC, v -> new EncryptionResponse(new byte[16], new byte[4])),
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
                      false)),
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
          sample(ChatCommand.CODEC, v -> new ChatCommand("server lobby", new byte[0])),
          sample(AcknowledgeConfiguration.CODEC, v -> new AcknowledgeConfiguration()),
          sample(
              PlayClientSettings.CODEC,
              v ->
                  new PlayClientSettings(
                      "en_us", (byte) 10, 0, true, (byte) 0, (byte) 0x7f, 1, false, true, 0)),
          sample(BundleDelimiter.CODEC, v -> new BundleDelimiter()),
          sample(PlayDisconnect.CODEC, v -> new PlayDisconnect(REASON)),
          sample(StartConfiguration.CODEC, v -> new StartConfiguration()),
          fixture(BossBar.CODEC, "boss_bar_add"),
          fixture(PlayerInfo.CODEC, "player_info_add"),
          fixture(PlayerInfoUpdate.CODEC, "player_info_update_all"),
          sample(PlayerInfoRemove.CODEC, v -> new PlayerInfoRemove(List.of(ALICE))),
          Map.entry(
              JoinGame.CODEC,
              v ->
                  v.supportsConfigurationState()
                      ? encode(
                          JoinGame.CODEC,
                          new JoinGame(42, false, new JoinGame.Opaque(new byte[] {1, 2, 3})),
                          v)
                      : SwitchPacketFixtures.bytes(v, "join_game")));

  /**
   * Adds a seed holding one valid packet of each kind the decoder decodes at that state, direction
   * and version.
   *
   * @param threshold the compression threshold, or {@code -1} for an uncompressed connection
   */
  private static void addSeed(
      FuzzSeeds seeds,
      ProtocolState state,
      PacketDirection direction,
      ProtocolVersion version,
      int threshold,
      boolean compressAll) {
    PacketRegistry registry = StateRegistry.get(state, direction);
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    for (int id = 0; id < 0x100; id++) {
      PacketCodec<?> codec = registry.lookup(version, id);
      if (codec != null) {
        Function<ProtocolVersion, byte[]> sample = SAMPLES.get(codec);
        assertNotNull(sample, "no sample of a packet decoded at " + state + " " + version);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.writeBytes(Wire.varInt(id));
        packet.writeBytes(sample.apply(version));
        stream.writeBytes(Wire.frame(packet.toByteArray()));
      }
    }
    String name =
        String.join(
            "-",
            version.name(),
            state.name().toLowerCase(Locale.ROOT),
            direction.name().toLowerCase(Locale.ROOT));
    if (threshold >= 0) {
      name += "-threshold-" + threshold + (compressAll ? "-all-compressed" : "");
    }
    // Choices: state, direction, version, threshold + 1 (0 for none), compress all, take the
    // inflated packets.
    seeds.add(
        name,
        stream.toByteArray(),
        STATES.indexOf(state),
        DIRECTIONS.indexOf(direction),
        VERSIONS.indexOf(version),
        threshold + 1,
        compressAll ? 1 : 0,
        threshold >= 0 ? 1 : 0);
  }

  private static <T extends Packet>
      Map.Entry<PacketCodec<?>, Function<ProtocolVersion, byte[]>> sample(
          PacketCodec<T> codec, Function<ProtocolVersion, T> packet) {
    return Map.entry(codec, version -> encode(codec, packet.apply(version), version));
  }

  private static Map.Entry<PacketCodec<?>, Function<ProtocolVersion, byte[]>> fixture(
      PacketCodec<?> codec, String name) {
    return Map.entry(codec, version -> SwitchPacketFixtures.bytes(version, name));
  }
}
