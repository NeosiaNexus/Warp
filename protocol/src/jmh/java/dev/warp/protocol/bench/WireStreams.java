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
package dev.warp.protocol.bench;

import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.netty.CipherDecoder;
import dev.warp.protocol.netty.CompressionDecoder;
import dev.warp.protocol.netty.CompressionEncoder;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import javax.crypto.SecretKey;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;

/** Builds and parses Minecraft wire streams exactly as a peer would see them on a socket. */
public final class WireStreams {

  private WireStreams() {}

  /**
   * Encodes packets into one contiguous wire stream, as a backend would write them to its socket.
   *
   * @param packets packet payloads ({@code [id][body]}); not released
   * @param threshold the compression threshold, or a negative value for no compression
   * @param level the zlib level used for packets at or above the threshold
   * @return a direct buffer holding every frame back to back, owned by the caller
   */
  public static ByteBuf encode(List<ByteBuf> packets, int threshold, int level) {
    EmbeddedChannel channel =
        new EmbeddedChannel(
            threshold < 0
                ? FrameEncoder.INSTANCE
                : new CompressionEncoder(threshold, new JavaCompressor(level)));
    ByteBuf stream = Unpooled.directBuffer();
    for (ByteBuf packet : packets) {
      channel.writeOutbound(packet.retainedDuplicate());
      drainOutbound(channel, stream);
    }
    channel.finishAndReleaseAll();
    return stream;
  }

  /**
   * Splits a stream into consecutive retained slices of at most {@code readSize} bytes, mimicking
   * how socket reads cut a TCP stream at arbitrary frame boundaries.
   *
   * @param stream the stream to split; its reader index is not modified
   * @param readSize the maximum slice size
   * @return the slices, each holding one reference to {@code stream}
   */
  public static List<ByteBuf> splitIntoReads(ByteBuf stream, int readSize) {
    List<ByteBuf> reads = new ArrayList<>();
    for (int offset = stream.readerIndex(); offset < stream.writerIndex(); offset += readSize) {
      int length = Math.min(readSize, stream.writerIndex() - offset);
      reads.add(stream.retainedSlice(offset, length));
    }
    return reads;
  }

  /**
   * Splits a stream into one direct buffer per frame, length prefix included.
   *
   * @param stream the stream to split; its reader index is not modified
   * @param alloc the allocator for the per-frame copies
   * @return the frames, owned by the caller
   */
  public static List<ByteBuf> splitFrames(ByteBuf stream, ByteBufAllocator alloc) {
    List<ByteBuf> frames = new ArrayList<>();
    ByteBuf cursor = stream.duplicate();
    while (cursor.isReadable()) {
      int start = cursor.readerIndex();
      int length = VarInt.read(cursor);
      int frameLength = cursor.readerIndex() - start + length;
      frames.add(alloc.directBuffer(frameLength).writeBytes(stream, start, frameLength));
      cursor.skipBytes(length);
    }
    return frames;
  }

  /**
   * Parses a wire stream back into packet payloads, as a client would.
   *
   * @param stream the stream to parse; released by this method
   * @param threshold the compression threshold, or a negative value for no compression
   * @param key the AES key if the stream is encrypted, or {@code null}
   * @return the decoded payloads, owned by the caller
   * @throws GeneralSecurityException if the cipher cannot be initialised
   */
  public static List<ByteBuf> decode(ByteBuf stream, int threshold, @Nullable SecretKey key)
      throws GeneralSecurityException {
    EmbeddedChannel channel = new EmbeddedChannel();
    if (key != null) {
      channel.pipeline().addLast(new CipherDecoder(key));
    }
    channel.pipeline().addLast(new FrameDecoder());
    if (threshold >= 0) {
      channel
          .pipeline()
          .addLast(
              new CompressionDecoder(threshold, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));
    }
    channel.writeInbound(stream);
    List<ByteBuf> packets = new ArrayList<>();
    for (ByteBuf packet = channel.readInbound(); packet != null; packet = channel.readInbound()) {
      packets.add(packet);
    }
    channel.finishAndReleaseAll();
    return packets;
  }

  /**
   * Moves every pending outbound buffer of {@code channel} into {@code sink}, releasing each one.
   *
   * @param channel the channel to drain
   * @param sink the buffer receiving the bytes
   */
  public static void drainOutbound(EmbeddedChannel channel, ByteBuf sink) {
    for (ByteBuf out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
      sink.writeBytes(out);
      out.release();
    }
  }
}
