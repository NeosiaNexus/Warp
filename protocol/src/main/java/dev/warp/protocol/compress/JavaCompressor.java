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

/**
 * Pure-Java compressor using {@link Deflater} and {@link Inflater}.
 *
 * <p>Uses the {@link ByteBuffer}-based API (Java 11+) with {@link ByteBuf#nioBuffer()} for
 * near-zero-copy operation on direct buffers. No intermediate {@code byte[]} allocations on the hot
 * path.
 *
 * <p>Not as fast as native implementations (libdeflate ≈ 4× faster, zlib-ng ≈ 2.5×) but fully
 * portable. Suitable as a fallback when native libraries are unavailable.
 */
public final class JavaCompressor implements PacketCompressor {

  /** Growth increment when the output buffer runs out of space during deflation. */
  private static final int DEFLATE_GROW = 8192;

  private final Deflater deflater;
  private final Inflater inflater;
  private boolean closed;

  /**
   * Creates a new Java-based compressor.
   *
   * @param level the compression level (0–9), or {@link Deflater#DEFAULT_COMPRESSION}
   */
  public JavaCompressor(int level) {
    this.deflater = new Deflater(level);
    this.inflater = new Inflater();
  }

  @Override
  public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
      throws DataFormatException {
    ensureOpen();

    // nioBuffer() returns a ByteBuffer view — zero-copy for direct buffers.
    inflater.setInput(source.nioBuffer());
    try {
      destination.ensureWritable(uncompressedSize);
      ByteBuffer destNio = destination.nioBuffer(destination.writerIndex(), uncompressedSize);
      inflater.inflate(destNio);
      destination.writerIndex(destination.writerIndex() + destNio.position());

      // If the inflater isn't finished, the actual data is larger than claimed — reject.
      // This catches decompression bombs that lie about their uncompressed size.
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

  @Override
  public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
    ensureOpen();

    deflater.setInput(source.nioBuffer());
    deflater.finish();
    try {
      while (!deflater.finished()) {
        if (!destination.isWritable()) {
          destination.ensureWritable(DEFLATE_GROW);
        }
        ByteBuffer destNio =
            destination.nioBuffer(destination.writerIndex(), destination.writableBytes());
        int produced = deflater.deflate(destNio);
        destination.writerIndex(destination.writerIndex() + produced);
      }
    } finally {
      deflater.reset();
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      deflater.end();
      inflater.end();
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("Compressor is closed");
    }
  }
}
