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
package dev.warp.protocol.command;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.bench.AbstractMicrobenchmark;
import dev.warp.protocol.command.CommandTreeDecoding.Decoded;
import dev.warp.protocol.packet.play.DeclareCommands;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Cost of the commands packet a vanilla server sends an operator, captured live (1.21.4: 1465
 * nodes, 22 KiB; 26.3: 35 KiB), per packet:
 *
 * <ul>
 *   <li>{@code copyPayload}: what the lazy integration costs when nothing edits the tree, the
 *       payload copied out of the frame;
 *   <li>{@code decode}: the whole tree decoded;
 *   <li>{@code encode}: the decoded tree encoded again;
 *   <li>{@code mergeAndEncode}: a decoded tree with a handful of proxy commands merged in, then
 *       encoded, what the proxy does on each join and server switch once it has commands.
 * </ul>
 */
@State(Scope.Thread)
@SuppressWarnings("checkstyle:VisibilityModifier") // JMH injects @Param fields directly
public class CommandTreeBenchmark extends AbstractMicrobenchmark {

  @Param({"1.21.4", "26.3"})
  String version;

  private ProtocolVersion protocol;
  private ByteBuf payload;
  private CommandTree tree;
  private CommandTree proxyCommands;
  private ByteBuf out;

  /** Loads the captured payload and decodes it once, for the benchmarks that start from a tree. */
  @Setup(Level.Trial)
  public void setUp() {
    protocol =
        ProtocolVersion.values().stream()
            .filter(v -> v.name().equals(version))
            .findFirst()
            .orElseThrow();
    payload = Unpooled.directBuffer().writeBytes(capture(version));
    tree = ((Decoded) CommandTreeCodec.decode(payload, protocol)).tree();
    CommandTree.Builder builder = CommandTree.builder();
    for (String name : new String[] {"msg", "friend", "party", "hub", "maintenance"}) {
      int command = builder.literal(builder.root(), name);
      int player = builder.argument(command, "player", ArgumentParser.word());
      builder.executable(player).suggestions(player, CommandNode.Argument.ASK_SERVER);
      builder.executable(builder.argument(player, "message", ArgumentParser.greedyString()));
    }
    proxyCommands = builder.build();
    out = PooledByteBufAllocator.DEFAULT.directBuffer(64 * 1024);
  }

  /** Releases the buffers. */
  @TearDown(Level.Trial)
  public void tearDown() {
    payload.release();
    out.release();
  }

  /**
   * Copies the payload out of the frame, as {@link DeclareCommands#CODEC} does.
   *
   * @return the packet
   */
  @Benchmark
  public DeclareCommands copyPayload() {
    return DeclareCommands.CODEC.decode(payload.duplicate(), protocol);
  }

  /**
   * Decodes the tree.
   *
   * @return the decoding
   */
  @Benchmark
  public CommandTreeDecoding decode() {
    return CommandTreeCodec.decode(payload, protocol);
  }

  /**
   * Encodes the decoded tree.
   *
   * @return the bytes written
   */
  @Benchmark
  public int encode() {
    out.clear();
    CommandTreeCodec.encode(tree, out, protocol);
    return out.writerIndex();
  }

  /**
   * Merges five proxy commands into the decoded tree, the proxy's replacing the backend's {@code
   * /msg}, and encodes the result.
   *
   * @return the bytes written
   */
  @Benchmark
  public int mergeAndEncode() {
    out.clear();
    CommandTreeCodec.encode(tree.merge(proxyCommands, NameConflict.PROXY_WINS), out, protocol);
    return out.writerIndex();
  }

  private static byte[] capture(String version) {
    try (InputStream in = CommandTreeBenchmark.class.getResourceAsStream(version + ".bin")) {
      if (in == null) {
        throw new IllegalStateException("No captured tree of " + version);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
