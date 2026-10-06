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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.bench.BenchmarkConfig;
import dev.warp.protocol.bench.PacketCorpus;
import dev.warp.protocol.bench.WireStreams;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * CPU and allocation cost, per packet, of relaying the tab list and boss bar packets the proxy
 * follows for clients before 1.20.2, to clear them on a server switch.
 *
 * <p>Wires the legs as {@link ForwardingPathBenchmark} does ({@link FrameDecoder} → {@link
 * MinecraftDecoder} → a tail → {@link FrameForwarder} → client encoders), for a client of {@link
 * #version}. The tail does what the proxy's backend handler does: it follows the tab list entries
 * and boss bars the decoder reports, in sets of UUIDs as {@code ServerLeftovers} does, and forwards
 * every frame. Both legs use vanilla's threshold (256) and the backend stream is cut into 16 KiB
 * reads, so the frames can go to the client verbatim.
 *
 * <p>{@link Path#BLIND} relays the same packets under a packet ID the proxy does not register: the
 * cost of forwarding these frames without reading them at all, the floor a watched packet is
 * measured against.
 *
 * <p>Every trial first checks that the client receives every packet intact, and that the tail saw
 * every player join and leave.
 */
@State(Scope.Thread)
@SuppressWarnings("checkstyle:VisibilityModifier") // JMH injects @Param fields directly
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {
      "-Xms2g",
      "-Xmx2g",
      "-XX:+AlwaysPreTouch",
      "-Dio.netty.leakDetection.level=disabled"
    })
@OperationsPerInvocation(BenchmarkConfig.PACKETS)
public class WatchedPacketsBenchmark {

  private static final int READ_SIZE = 16 * 1024;

  /** Players listed in each latency update. */
  private static final int LISTED_PLAYERS = 40;

  /** Boss bars the server animates. */
  private static final int BOSS_BARS = 4;

  /** Base64 alphabet, for skins that compress like real ones. */
  private static final byte[] BASE64 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
          .getBytes(StandardCharsets.US_ASCII);

  /** Client version: legacy Player Info, or Player Info Update and Remove (1.19.3+). */
  @Param({"1.12.2", "1.20.1"})
  String version;

  /** Traffic the server sends. */
  public enum Traffic {
    /** Boss bar health updates (21 B): what an animated bar sends, up to every tick. */
    BOSS_BAR_PROGRESS,
    /** Latency of 40 listed players (~740 B, compressed): broadcast by servers periodically. */
    TAB_LIST_LATENCY,
    /** A player joining with a signed skin (~1.2 KiB, compressed), then leaving (18 B). */
    TAB_LIST_JOIN_LEAVE
  }

  @Param({"BOSS_BAR_PROGRESS", "TAB_LIST_LATENCY", "TAB_LIST_JOIN_LEAVE"})
  Traffic traffic;

  /** How the proxy relays the packets. */
  public enum Path {
    /** Under their real packet IDs: watched (or, before watching existed, decoded). */
    WATCHED,
    /** Under a packet ID the proxy does not register: forwarded without being read. */
    BLIND
  }

  @Param({"WATCHED", "BLIND"})
  Path path;

  private ProtocolVersion protocolVersion;
  private List<ByteBuf> corpus;
  private ByteBuf backendStream;
  private List<ByteBuf> reads;
  private EmbeddedChannel backendLeg;
  private EmbeddedChannel clientLeg;
  private Leftovers leftovers;

  /**
   * Builds the corpus, the backend wire stream and both pipeline legs, then validates one pass.
   *
   * @throws GeneralSecurityException never: the client leg is not encrypted
   */
  @Setup(Level.Trial)
  public void setUp() throws GeneralSecurityException {
    protocolVersion =
        switch (version) {
          case "1.12.2" -> ProtocolVersion.MINECRAFT_1_12_2;
          case "1.20.1" -> ProtocolVersion.MINECRAFT_1_20_1;
          default -> throw new IllegalArgumentException("Unsupported version " + version);
        };
    corpus = corpus(traffic, path, protocolVersion, BenchmarkConfig.PACKETS);
    backendStream =
        WireStreams.encode(corpus, BenchmarkConfig.THRESHOLD, Deflater.DEFAULT_COMPRESSION);
    reads = WireStreams.splitIntoReads(backendStream, READ_SIZE);
    System.out.printf(
        "Corpus %s %s at %s: %d packets, %.1f B/packet on the wire%n",
        traffic,
        path,
        version,
        corpus.size(),
        (double) backendStream.readableBytes() / corpus.size());

    clientLeg = channel();
    clientLeg
        .pipeline()
        .addLast(
            new CompressionEncoder(
                BenchmarkConfig.THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)),
            new MinecraftEncoder(PacketDirection.CLIENTBOUND, protocolVersion, ProtocolState.PLAY));
    FrameForwarder toClient = new FrameForwarder(clientLeg);
    toClient.compressionEnabled(BenchmarkConfig.THRESHOLD);

    MinecraftDecoder backendDecoder =
        new MinecraftDecoder(PacketDirection.CLIENTBOUND, protocolVersion, ProtocolState.PLAY);
    FrameDecompressor decompressor =
        new FrameDecompressor(
            BenchmarkConfig.THRESHOLD,
            false,
            FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
            new JavaCompressor(Deflater.DEFAULT_COMPRESSION));
    // The inflate budget stops floods; a benchmark is one, by design.
    decompressor.setMaxDecompressionRate(0);
    backendDecoder.enableCompression(decompressor, true);
    leftovers = new Leftovers();
    // What BackendPlaySessionHandler does, synchronously from the pipeline tail.
    backendLeg =
        channel(
            new FrameDecoder(),
            backendDecoder,
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof ByteBuf frame) {
                  toClient.forward(frame, backendDecoder);
                } else {
                  leftovers.track((Packet) msg); // its frame comes next
                }
              }
            });

    validate();
  }

  /**
   * Relays the whole corpus once: backend reads in, client-ready bytes out.
   *
   * @return the number of bytes written towards the client, consumed by JMH
   */
  @Benchmark
  public long relayClientbound() {
    long written = 0;
    for (ByteBuf read : reads) {
      written += relayRead(read, null);
    }
    return written;
  }

  /** Releases every buffer and closes both legs. */
  @TearDown(Level.Trial)
  public void tearDown() {
    backendLeg.finishAndReleaseAll();
    clientLeg.finishAndReleaseAll();
    reads.forEach(ByteBuf::release);
    backendStream.release();
    corpus.forEach(ByteBuf::release);
  }

  // ---------------------------------------------------------------------------
  // Relay
  // ---------------------------------------------------------------------------

  private long relayRead(ByteBuf read, @Nullable ByteBuf capture) {
    backendLeg.writeInbound(read.retainedDuplicate());
    clientLeg.flush();
    long written = 0;
    for (ByteBuf out = clientLeg.readOutbound(); out != null; out = clientLeg.readOutbound()) {
      written += out.readableBytes();
      if (capture != null) {
        capture.writeBytes(out, out.readerIndex(), out.readableBytes());
      }
      out.release();
    }
    return written;
  }

  private void validate() throws GeneralSecurityException {
    ByteBuf captured = Unpooled.directBuffer();
    for (ByteBuf read : reads) {
      relayRead(read, captured);
    }
    List<ByteBuf> decoded = WireStreams.decode(captured, BenchmarkConfig.THRESHOLD, null);
    try {
      if (decoded.size() != corpus.size()) {
        throw new IllegalStateException(
            "Relayed " + decoded.size() + " packets, expected " + corpus.size());
      }
      for (int i = 0; i < corpus.size(); i++) {
        if (!ByteBufUtil.equals(decoded.get(i), corpus.get(i))) {
          throw new IllegalStateException("Packet " + i + " corrupted by the relay");
        }
      }
    } finally {
      decoded.forEach(ByteBuf::release);
    }
    int joins =
        path == Path.WATCHED && traffic == Traffic.TAB_LIST_JOIN_LEAVE ? corpus.size() / 2 : 0;
    if (leftovers.joined != joins || !leftovers.tabList.isEmpty()) {
      throw new IllegalStateException(
          leftovers.joined + " players joined and " + leftovers.tabList.size() + " stayed");
    }
  }

  private static EmbeddedChannel channel(ChannelHandler... handlers) {
    EmbeddedChannel channel = new EmbeddedChannel(handlers);
    channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
    return channel;
  }

  /** Follows the tab list and the boss bars as {@code ServerLeftovers} does. */
  private static final class Leftovers {
    final Set<UUID> tabList = new HashSet<>();
    final Set<UUID> bossBars = new HashSet<>();
    int joined;

    void track(Packet packet) {
      switch (packet) {
        case PlayerInfo info -> {
          if (info.action() == PlayerInfo.ADD_PLAYER) {
            joined += info.profileIds().size();
            tabList.addAll(info.profileIds());
          } else if (info.action() == PlayerInfo.REMOVE_PLAYER) {
            info.profileIds().forEach(tabList::remove);
          }
        }
        case PlayerInfoUpdate update -> {
          if ((update.actions() & PlayerInfoUpdate.ADD_PLAYER) != 0) {
            joined += update.profileIds().size();
            tabList.addAll(update.profileIds());
          }
        }
        case PlayerInfoRemove remove -> remove.profileIds().forEach(tabList::remove);
        case BossBar bossBar -> {
          if (bossBar.action() == BossBar.ADD) {
            bossBars.add(bossBar.uuid());
          } else if (bossBar.action() == BossBar.REMOVE) {
            bossBars.remove(bossBar.uuid());
          }
        }
        default -> throw new IllegalStateException("Unexpected " + packet);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Corpus
  // ---------------------------------------------------------------------------

  /** Generates {@code count} packets ({@code [id][body]}), the same bytes on every run. */
  private static List<ByteBuf> corpus(
      Traffic traffic, Path path, ProtocolVersion version, int count) {
    PacketRegistry registry = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
    boolean upsert = version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3);
    int blindId = PacketCorpus.blindPacketIds(version)[0];
    int bossBarId = path == Path.BLIND ? blindId : registry.packetId(version, BossBar.class);
    int addId =
        path == Path.BLIND
            ? blindId
            : registry.packetId(version, upsert ? PlayerInfoUpdate.class : PlayerInfo.class);
    int removeId =
        path == Path.BLIND
            ? blindId
            : registry.packetId(version, upsert ? PlayerInfoRemove.class : PlayerInfo.class);
    SplittableRandom rng = new SplittableRandom(BenchmarkConfig.SEED);
    UUID[] bars = new UUID[BOSS_BARS];
    for (int i = 0; i < bars.length; i++) {
      bars[i] = new UUID(rng.nextLong(), rng.nextLong());
    }
    UUID[] listed = new UUID[LISTED_PLAYERS];
    for (int i = 0; i < listed.length; i++) {
      listed[i] = new UUID(rng.nextLong(), rng.nextLong());
    }

    List<ByteBuf> packets = new ArrayList<>(count);
    UUID joining = listed[0];
    for (int i = 0; i < count; i++) {
      ByteBuf buf = Unpooled.buffer();
      switch (traffic) {
        case BOSS_BAR_PROGRESS -> {
          VarInt.write(buf, bossBarId);
          writeUuid(buf, bars[i % bars.length]);
          VarInt.write(buf, 2); // update health
          buf.writeFloat((float) rng.nextDouble());
        }
        case TAB_LIST_LATENCY -> {
          VarInt.write(buf, addId);
          if (upsert) {
            buf.writeByte(PlayerInfoUpdate.UPDATE_LATENCY);
          } else {
            VarInt.write(buf, PlayerInfo.UPDATE_LATENCY);
          }
          VarInt.write(buf, listed.length);
          for (UUID player : listed) {
            writeUuid(buf, player);
            VarInt.write(buf, rng.nextInt(20, 300));
          }
        }
        case TAB_LIST_JOIN_LEAVE -> {
          if (i % 2 == 0) {
            joining = new UUID(rng.nextLong(), rng.nextLong());
            VarInt.write(buf, addId);
            writeJoin(buf, version, joining, rng);
          } else {
            VarInt.write(buf, removeId);
            if (!upsert) {
              VarInt.write(buf, PlayerInfo.REMOVE_PLAYER);
            }
            VarInt.write(buf, 1);
            writeUuid(buf, joining);
          }
        }
      }
      packets.add(buf);
    }
    return packets;
  }

  /** A player joining as vanilla announces it: name, signed skin, game mode, latency. */
  private static void writeJoin(
      ByteBuf buf, ProtocolVersion version, UUID player, SplittableRandom rng) {
    boolean upsert = version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3);
    if (upsert) {
      // Add, chat session, game mode, listed, latency, display name: vanilla's join.
      buf.writeByte(0x3F);
    } else {
      VarInt.write(buf, PlayerInfo.ADD_PLAYER);
    }
    VarInt.write(buf, 1);
    writeUuid(buf, player);
    McString.write(buf, "Player" + rng.nextInt(100_000));
    VarInt.write(buf, 1); // properties
    McString.write(buf, "textures");
    McString.write(buf, base64(rng, 568)); // the skin and cape URLs, as JSON
    buf.writeBoolean(true);
    McString.write(buf, base64(rng, 684)); // Mojang's 4096-bit RSA signature
    if (upsert) {
      buf.writeBoolean(false); // no chat session
      VarInt.write(buf, 0); // game mode
      buf.writeBoolean(true); // listed
      VarInt.write(buf, rng.nextInt(20, 300)); // latency
      buf.writeBoolean(false); // display name
    } else {
      VarInt.write(buf, 0); // game mode
      VarInt.write(buf, rng.nextInt(20, 300)); // latency
      buf.writeBoolean(false); // display name
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
        buf.writeBoolean(false); // public key
      }
    }
  }

  private static String base64(SplittableRandom rng, int length) {
    byte[] chars = new byte[length];
    for (int i = 0; i < length; i++) {
      chars[i] = BASE64[rng.nextInt(BASE64.length)];
    }
    return new String(chars, StandardCharsets.US_ASCII);
  }

  private static void writeUuid(ByteBuf buf, UUID uuid) {
    buf.writeLong(uuid.getMostSignificantBits());
    buf.writeLong(uuid.getLeastSignificantBits());
  }
}
