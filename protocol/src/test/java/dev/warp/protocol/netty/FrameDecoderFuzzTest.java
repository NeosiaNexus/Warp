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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.InboundRecorder;
import dev.warp.protocol.fuzz.Rejections;
import dev.warp.protocol.fuzz.TrackingAllocator;
import dev.warp.protocol.fuzz.Wire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes {@link FrameDecoder} against the reference framing of {@link Wire}, feeding each stream in
 * fragments of fuzzer-chosen sizes: the frames must not depend on how TCP split the bytes.
 */
@DisplayName("FrameDecoder fuzzing")
class FrameDecoderFuzzTest {

  /** Fragment sizes cycle through up to this many values. */
  private static final int MAX_FRAGMENT_SIZES = 3;

  /**
   * The limits of the pre-login states a fuzz input may pick, by position: an input keeps its
   * meaning when versions are added.
   */
  private static final List<FrameLimits> LIMITS =
      List.of(
          FrameLimits.HANDSHAKE,
          FrameLimits.STATUS,
          login(ProtocolVersion.MINECRAFT_1_7_2, false),
          login(ProtocolVersion.MINECRAFT_1_12_2, false),
          login(ProtocolVersion.MINECRAFT_1_19, false),
          login(ProtocolVersion.MINECRAFT_1_19_1, false),
          login(ProtocolVersion.MINECRAFT_1_20_2, false),
          login(ProtocolVersion.MINECRAFT_1_20_5, true));

  @FuzzTest
  @DisplayName("should frame any stream like the reference, however fragmented, releasing all")
  void decode(FuzzedDataProvider data) {
    run(data, null);
  }

  @FuzzTest
  @DisplayName("should refuse frames over a state's limits from their header, however fragmented")
  void decodeLimited(FuzzedDataProvider data) {
    FrameLimits limits = LIMITS.get(data.consumeInt(0, LIMITS.size() - 1));
    TrackingAllocator alloc = run(data, limits);
    // Never more than the largest packet of the state and one read are held at once.
    assertTrue(
        alloc.largestCapacity() <= 2 * (limits.largest() + Wire.MAX_FRAME_LENGTH_BYTES + 256),
        () -> "allocated " + alloc.largestCapacity() + " bytes under " + limits);
  }

  private static TrackingAllocator run(FuzzedDataProvider data, @Nullable FrameLimits limits) {
    boolean direct = data.consumeBoolean();
    int[] fragmentSizes = new int[1 + data.consumeInt(0, MAX_FRAGMENT_SIZES - 1)];
    for (int i = 0; i < fragmentSizes.length; i++) {
      fragmentSizes[i] = 1 + data.consumeInt(0, 255);
    }
    byte[] stream = data.consumeRemainingAsBytes();

    TrackingAllocator alloc = new TrackingAllocator();
    InboundRecorder recorder = new InboundRecorder();
    EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(limits), recorder);
    channel.config().setAllocator(alloc);
    for (int offset = 0, i = 0; offset < stream.length && channel.isActive(); i++) {
      int length = Math.min(fragmentSizes[i % fragmentSizes.length], stream.length - offset);
      ByteBuf fragment = direct ? alloc.directBuffer(length) : alloc.heapBuffer(length);
      channel.writeInbound(fragment.writeBytes(stream, offset, length));
      offset += length;
    }
    channel.finishAndReleaseAll();

    Wire.Framing expected = Wire.frames(stream, limits);
    List<Object> frames = recorder.messages();
    assertEquals(expected.frames().size(), frames.size(), "frame count");
    for (int i = 0; i < frames.size(); i++) {
      assertArrayEquals(expected.frames().get(i), ByteBufUtil.getBytes((ByteBuf) frames.get(i)));
    }
    assertEquals(expected.malformed() ? 1 : 0, recorder.failures().size(), "failure count");
    recorder.failures().forEach(Rejections::assertRejection);

    recorder.release();
    alloc.assertAllReleased();
    // Buffers grow with the bytes received, never with the lengths they declare.
    assertTrue(
        alloc.largestCapacity() <= Math.max(64, 2 * stream.length),
        () -> "allocated " + alloc.largestCapacity() + " bytes for " + stream.length);
    return alloc;
  }

  private static FrameLimits login(ProtocolVersion version, boolean cookies) {
    return Objects.requireNonNull(FrameLimits.serverbound(ProtocolState.LOGIN, version, cookies));
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    HexFormat hex = HexFormat.of();
    ByteArrayOutputStream frames = new ByteArrayOutputStream();
    frames.writeBytes(Wire.frame(hex.parseHex("00")));
    frames.writeBytes(Wire.frame(new byte[200])); // two-byte length
    frames.writeBytes(hex.parseHex("00")); // empty frame
    frames.writeBytes(hex.parseHex("8580000102030405")); // padded three-byte length, as Velocity
    frames.writeBytes(Wire.frame(hex.parseHex("2a")));
    byte[] stream = frames.toByteArray();

    // Choices: direct, fragment size count - 1, then each fragment size - 1.
    new FuzzSeeds(FrameDecoderFuzzTest.class, "decode")
        .add("frames", stream, 0, 0, 255)
        .add("frames-byte-by-byte", stream, 0, 0, 0)
        .add("frames-direct-irregular", stream, 1, 2, 2, 6, 0)
        .add("incomplete-largest-frame", hex.parseHex("ffff7f0102030405"), 0, 0, 255)
        .add("malformed-length", hex.parseHex("012a80808001"), 0, 0, 255)
        .verify();

    // A status exchange, a login start of 1.20.2, a handshake announcing more than it may hold.
    ByteArrayOutputStream status = new ByteArrayOutputStream();
    status.writeBytes(Wire.frame(hex.parseHex("00")));
    status.writeBytes(Wire.frame(hex.parseHex("010000000000000001")));
    byte[] loginStart =
        Wire.frame(hex.parseHex("0006776172705f5f" + "069a79f444e94726a5befca90e38aaf5"));
    // Choices: limits, then direct, fragment size count - 1, then each fragment size - 1.
    new FuzzSeeds(FrameDecoderFuzzTest.class, "decodeLimited")
        .add("status", status.toByteArray(), 1, 0, 0, 255)
        .add("status-byte-by-byte", status.toByteArray(), 1, 0, 0, 0)
        .add("login-start", loginStart, 6, 1, 1, 2, 0)
        .add("oversized-handshake", hex.parseHex("8d0600ff"), 0, 0, 0, 255)
        .add("packet-not-in-state", hex.parseHex("0102"), 1, 0, 0, 255)
        .verify();
  }
}
