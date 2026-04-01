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

import java.util.zip.DataFormatException;

import io.netty.buffer.ByteBuf;

/**
 * Abstraction for packet compression and decompression.
 *
 * <p>Implementations may use Java's built-in {@link java.util.zip.Deflater}/{@link
 * java.util.zip.Inflater} or native libraries (libdeflate, zlib-ng) via JNI. Instances are
 * <b>not</b> thread-safe — each Netty channel must use its own compressor.
 *
 * <p>Lifecycle: the owning pipeline handler calls {@link #close()} from {@code handlerRemoved()}.
 * If a compressor is shared between two handlers (decoder and encoder), only one should close it,
 * or each handler should use a separate instance.
 */
public interface PacketCompressor extends AutoCloseable {

  /**
   * Decompresses data from {@code source} into {@code destination}.
   *
   * <p>The caller must ensure {@code destination} has at least {@code uncompressedSize} writable
   * bytes (via {@link ByteBuf#ensureWritable(int)} or pre-allocation). After a successful call, the
   * destination's writer index is advanced by the number of decompressed bytes produced.
   *
   * @param source the compressed data to read from
   * @param destination the buffer to write decompressed data into
   * @param uncompressedSize the expected uncompressed size — used as an upper bound on output and
   *     for validation. If the actual decompressed data exceeds this size, a {@link
   *     DataFormatException} is thrown
   * @throws DataFormatException if the compressed data is malformed or the decompressed output
   *     exceeds {@code uncompressedSize}
   */
  void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
      throws DataFormatException;

  /**
   * Compresses data from {@code source} into {@code destination}.
   *
   * <p>After a successful call, the destination's writer index is advanced by the number of
   * compressed bytes produced. The destination buffer is grown as needed.
   *
   * @param source the uncompressed data to read from
   * @param destination the buffer to write compressed data into
   * @throws DataFormatException if compression fails
   */
  void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException;

  /**
   * Releases underlying resources (native memory, Deflater/Inflater instances). Idempotent —
   * calling close on an already-closed compressor is a no-op.
   */
  @Override
  void close();
}
