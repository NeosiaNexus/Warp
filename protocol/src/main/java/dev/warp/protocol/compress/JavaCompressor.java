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
package dev.warp.protocol.compress;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Pure-Java compressor using {@link Deflater} and {@link Inflater}.
 *
 * <p>Uses the {@link ByteBuffer}-based API with each buffer's own cached NIO view ({@link
 * ByteBuf#internalNioBuffer}), so that a call between direct buffers allocates nothing on the heap:
 * {@link ByteBuf#nioBuffer()} would create a duplicate and a slice per call, which escape analysis
 * cannot remove because the inflater keeps a reference to its input. A buffer that is not backed by
 * exactly one NIO buffer (a composite of several components) takes a slower path through a copy,
 * which Netty's allocators never hand out.
 *
 * <p>Not as fast as native implementations (libdeflate about 4x faster, zlib-ng about 2.5x) but
 * fully portable. Suitable as a fallback when native libraries are unavailable.
 *
 * <p>The native zlib streams are created on first use: a decoder only ever inflates and an encoder
 * only ever deflates, and an idle {@link Deflater} alone pins ~256 KiB of native memory.
 */
public final class JavaCompressor implements PacketCompressor {

  /** Growth increment when the output buffer runs out of space during deflation. */
  private static final int DEFLATE_GROW = 8192;

  private final int level;
  private @Nullable Deflater deflater;
  private @Nullable Inflater inflater;
  private boolean closed;

  /**
   * Creates a new Java-based compressor.
   *
   * @param level the compression level (0 to 9), or {@link Deflater#DEFAULT_COMPRESSION}
   */
  public JavaCompressor(int level) {
    this.level = level;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The two buffers must not share memory: each one's cached NIO view is in use for the whole
   * call.
   */
  @Override
  public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
      throws DataFormatException {
    Inflater inflater = inflater();
    destination.ensureWritable(uncompressedSize);

    inflater.setInput(readableView(source));
    try {
      int writerIndex = destination.writerIndex();
      int produced;
      if (destination.nioBufferCount() == 1) {
        produced = inflater.inflate(destination.internalNioBuffer(writerIndex, uncompressedSize));
        destination.writerIndex(writerIndex + produced);
      } else {
        // Several components: a view of them would be a copy, so inflate through one instead.
        byte[] staging = new byte[uncompressedSize];
        produced = inflater.inflate(staging);
        destination.writeBytes(staging, 0, produced);
      }

      // If the inflater isn't finished, the actual data is larger than claimed: reject it. This
      // catches decompression bombs that lie about their uncompressed size.
      if (!inflater.finished()) {
        throw new DataFormatException(
            "Compressed stream contains more data than claimed uncompressed size of "
                + uncompressedSize
                + " bytes");
      }
    } finally {
      inflater.reset();
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>The two buffers must not share memory: each one's cached NIO view is in use for the whole
   * call.
   */
  @Override
  public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
    Deflater deflater = deflater();

    deflater.setInput(readableView(source));
    deflater.finish();
    try {
      while (!deflater.finished()) {
        if (!destination.isWritable()) {
          destination.ensureWritable(DEFLATE_GROW);
        }
        int writerIndex = destination.writerIndex();
        int writable = destination.writableBytes();
        if (destination.nioBufferCount() == 1) {
          int produced = deflater.deflate(destination.internalNioBuffer(writerIndex, writable));
          destination.writerIndex(writerIndex + produced);
        } else {
          // Several components: a view of them would be a copy, so deflate through one instead.
          byte[] staging = new byte[writable];
          int produced = deflater.deflate(staging);
          destination.writeBytes(staging, 0, produced);
        }
      }
    } finally {
      deflater.reset();
    }
  }

  /**
   * Returns the readable bytes of {@code buffer} as an NIO buffer: its cached view when it is
   * backed by one NIO buffer (no allocation), else a merged copy.
   */
  private static ByteBuffer readableView(ByteBuf buffer) {
    int index = buffer.readerIndex();
    int length = buffer.readableBytes();
    return buffer.nioBufferCount() == 1
        ? buffer.internalNioBuffer(index, length)
        : buffer.nioBuffer(index, length);
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      if (deflater != null) {
        deflater.end();
      }
      if (inflater != null) {
        inflater.end();
      }
    }
  }

  private Inflater inflater() {
    ensureOpen();
    Inflater current = inflater;
    if (current == null) {
      current = new Inflater();
      inflater = current;
    }
    return current;
  }

  private Deflater deflater() {
    ensureOpen();
    Deflater current = deflater;
    if (current == null) {
      current = new Deflater(level);
      deflater = current;
    }
    return current;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("Compressor is closed");
    }
  }
}
