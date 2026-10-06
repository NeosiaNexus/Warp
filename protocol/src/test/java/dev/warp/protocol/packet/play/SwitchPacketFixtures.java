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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketWatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;

/**
 * Reference wire bytes from {@code switch-packets.txt}, written by node-minecraft-protocol: the
 * packets Warp reads and writes to switch servers before 1.20.2, per protocol version.
 */
public final class SwitchPacketFixtures {

  private static final Map<Integer, Map<String, byte[]>> FIXTURES = load();

  private SwitchPacketFixtures() {}

  /** Returns the protocol versions the fixtures cover, oldest first. */
  static List<ProtocolVersion> versions() {
    return FIXTURES.keySet().stream()
        .map(protocol -> Objects.requireNonNull(ProtocolVersion.byProtocolId(protocol)))
        .toList();
  }

  /** Returns the versions that have a {@code packet} fixture, oldest first. */
  static Stream<ProtocolVersion> versionsWith(String packet) {
    return versions().stream().filter(version -> has(version, packet));
  }

  /** Returns whether the fixtures have {@code packet} for {@code version}. */
  static boolean has(ProtocolVersion version, String packet) {
    return FIXTURES.getOrDefault(version.protocol(), Map.of()).containsKey(packet);
  }

  /**
   * Returns the body of {@code packet} at {@code version}, without its packet ID.
   *
   * @param version the protocol version
   * @param packet the name of the fixture, as in {@code switch-packets.txt}
   * @return a copy of the bytes
   * @throws IllegalArgumentException if there is no such fixture
   */
  public static byte[] bytes(ProtocolVersion version, String packet) {
    byte[] bytes = FIXTURES.getOrDefault(version.protocol(), Map.of()).get(packet);
    if (bytes == null) {
      throw new IllegalArgumentException("No fixture " + packet + " for " + version);
    }
    return bytes.clone();
  }

  /** Decodes {@code packet} at {@code version}, checking that the codec reads every byte. */
  static <T extends Packet> T decode(PacketCodec<T> codec, ProtocolVersion version, String packet) {
    ByteBuf buf = Unpooled.wrappedBuffer(bytes(version, packet));
    try {
      T decoded = codec.decode(buf, version);
      if (buf.isReadable()) {
        throw new AssertionError(
            buf.readableBytes() + " bytes left after decoding " + packet + " at " + version);
      }
      return decoded;
    } finally {
      buf.release();
    }
  }

  /**
   * What a watch reported for a fixture, and how far into it the watch read.
   *
   * @param packet what the watch reported, or {@code null}
   * @param read the bytes the watch read
   * @param length the fixture's length
   */
  record Watched<T extends Packet>(@Nullable T packet, int read, int length) {}

  /** Runs {@code watch} over {@code packet} at {@code version}. */
  static <T extends Packet> Watched<T> watch(
      PacketWatch<T> watch, ProtocolVersion version, String packet) {
    ByteBuf buf = Unpooled.wrappedBuffer(bytes(version, packet));
    try {
      T reported = watch.watch(buf, version);
      return new Watched<>(reported, buf.readerIndex(), buf.writerIndex());
    } finally {
      buf.release();
    }
  }

  /** Encodes {@code packet} at {@code version} and returns its bytes. */
  static <T extends Packet> byte[] encode(PacketCodec<T> codec, T packet, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      codec.encode(packet, buf, version);
      byte[] bytes = new byte[buf.readableBytes()];
      buf.readBytes(bytes);
      return bytes;
    } finally {
      buf.release();
    }
  }

  private static Map<Integer, Map<String, byte[]>> load() {
    Map<Integer, Map<String, byte[]>> fixtures = new TreeMap<>();
    try (InputStream in =
            Objects.requireNonNull(
                SwitchPacketFixtures.class.getResourceAsStream("switch-packets.txt"));
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 3) {
          throw malformed(line, null);
        }
        int protocol;
        byte[] body;
        try {
          protocol = Integer.parseInt(fields[0]);
          body = HexFormat.of().parseHex(fields[2]);
        } catch (NumberFormatException e) {
          throw malformed(line, e);
        } catch (IllegalArgumentException e) { // not hexadecimal
          throw malformed(line, e);
        }
        fixtures.computeIfAbsent(protocol, k -> new HashMap<>()).put(fields[1], body);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return fixtures;
  }

  private static IllegalStateException malformed(String line, @Nullable Throwable cause) {
    return new IllegalStateException("switch-packets.txt: malformed line: " + line, cause);
  }
}
