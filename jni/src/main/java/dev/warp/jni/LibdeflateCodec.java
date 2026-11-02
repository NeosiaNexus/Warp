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
package dev.warp.jni;

import java.util.zip.DataFormatException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * zlib compression and decompression of whole buffers with the bundled libdeflate: Minecraft's
 * packet compression, about twice as fast as {@code java.util.zip} on small packets and several
 * times on chunks.
 *
 * <p>An instance only holds a level; the native contexts belong to the calling thread (see {@link
 * ThreadContexts}), so one instance can be shared by every connection and thread. Calls never
 * block, never take a lock, and allocate nothing when both buffers are direct.
 *
 * <p><b>Buffer ownership.</b> The caller owns both buffers before, during and after each call: the
 * codec neither retains nor releases them, and native code only touches their memory during the
 * call. Buffers with a memory address (direct buffers, as Netty's pooled allocator hands out) are
 * read and written in place. Others (heap, composite of several components) are copied through a
 * temporary direct buffer that is released before the call returns. The source's indices are left
 * as they were; the destination's writer index advances by the number of bytes produced.
 *
 * <p>Use only once {@link Natives#compression()} reports the library available.
 */
public final class LibdeflateCodec {

  /** The highest compression level libdeflate offers (slowest, smallest). */
  public static final int MAX_LEVEL = 12;

  /** The level used for {@code -1}, as zlib's default. */
  public static final int DEFAULT_LEVEL = 6;

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DataFormatException BAD_DATA =
      stackless("Compressed data is invalid or corrupt");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DataFormatException SHORT_OUTPUT =
      stackless("Compressed data decompresses to fewer bytes than claimed");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DataFormatException INSUFFICIENT_SPACE =
      stackless("Compressed data decompresses to more bytes than claimed");

  private final int level;

  private LibdeflateCodec(int level) {
    this.level = level;
  }

  /**
   * Returns a codec compressing at {@code level}.
   *
   * @param level 0 (no compression) to {@value #MAX_LEVEL}, or -1 for {@value #DEFAULT_LEVEL}
   * @return the codec
   * @throws IllegalArgumentException if the level is out of range
   * @throws IllegalStateException if the native library is unavailable
   */
  public static LibdeflateCodec create(int level) {
    if (level < -1 || level > MAX_LEVEL) {
      throw new IllegalArgumentException("Compression level must be -1 to 12, got " + level);
    }
    NativeStatus status = Natives.compression();
    if (!status.available()) {
      throw new IllegalStateException("Native libdeflate is unavailable: " + status.detail());
    }
    return new LibdeflateCodec(level == -1 ? DEFAULT_LEVEL : level);
  }

  /**
   * Returns the compression level.
   *
   * @return 0 to {@value #MAX_LEVEL}
   */
  public int level() {
    return level;
  }

  /**
   * Returns the most bytes {@link #deflate} can produce from {@code length} bytes, so that a caller
   * can size its output once.
   *
   * @param length the uncompressed size
   * @return the worst-case compressed size, slightly above {@code length}
   */
  public int deflateBound(int length) {
    return Math.toIntExact(Libdeflate.zlibCompressBound(0L, length));
  }

  /**
   * Compresses the readable bytes of {@code source} into one zlib stream appended to {@code
   * destination}, grown first to fit the worst case.
   *
   * @param source the uncompressed bytes; indices unchanged
   * @param destination receives the zlib stream at its writer index, which advances past it
   */
  public void deflate(ByteBuf source, ByteBuf destination) {
    int length = source.readableBytes();
    long compressor = ThreadContexts.current().compressor(level);
    int bound = Math.toIntExact(Libdeflate.zlibCompressBound(compressor, length));
    destination.ensureWritable(bound);
    if (source.hasMemoryAddress() && destination.hasMemoryAddress()) {
      long produced =
          Libdeflate.zlibCompress(
              compressor,
              source.memoryAddress() + source.readerIndex(),
              length,
              destination.memoryAddress() + destination.writerIndex(),
              bound);
      advance(destination, produced);
      return;
    }
    boolean copySource = !source.hasMemoryAddress();
    ByteBuf directSource = copySource ? directCopy(source, length) : source;
    ByteBuf directDestination = ByteBufAllocator.DEFAULT.directBuffer(bound);
    try {
      long produced =
          Libdeflate.zlibCompress(
              compressor,
              directSource.memoryAddress() + directSource.readerIndex(),
              length,
              directDestination.memoryAddress(),
              bound);
      advance(directDestination, produced);
      destination.writeBytes(directDestination);
    } finally {
      directDestination.release();
      if (copySource) {
        directSource.release();
      }
    }
  }

  /**
   * Decompresses the zlib stream in the readable bytes of {@code source} into exactly {@code
   * uncompressedSize} bytes appended to {@code destination}. The stream is untrusted: libdeflate
   * writes at most {@code uncompressedSize} bytes whatever it claims, and any other size is an
   * error. Bytes after the end of the stream are ignored, as {@link java.util.zip.Inflater} does.
   *
   * @param source the zlib stream; indices unchanged
   * @param destination grown to fit {@code uncompressedSize} bytes, then receives them at its
   *     writer index, which advances past them; unchanged if decompression fails
   * @param uncompressedSize the size the data must decompress to
   * @throws DataFormatException if the data is corrupt or decompresses to another size
   */
  public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
      throws DataFormatException {
    if (uncompressedSize < 0) {
      throw new IllegalArgumentException("Negative uncompressed size: " + uncompressedSize);
    }
    int length = source.readableBytes();
    long decompressor = ThreadContexts.current().decompressor();
    destination.ensureWritable(uncompressedSize);
    int result;
    if (source.hasMemoryAddress() && destination.hasMemoryAddress()) {
      result =
          Libdeflate.zlibDecompress(
              decompressor,
              source.memoryAddress() + source.readerIndex(),
              length,
              destination.memoryAddress() + destination.writerIndex(),
              uncompressedSize);
      check(result);
      destination.writerIndex(destination.writerIndex() + uncompressedSize);
      return;
    }
    boolean copySource = !source.hasMemoryAddress();
    ByteBuf directSource = copySource ? directCopy(source, length) : source;
    ByteBuf directDestination = ByteBufAllocator.DEFAULT.directBuffer(uncompressedSize);
    try {
      result =
          Libdeflate.zlibDecompress(
              decompressor,
              directSource.memoryAddress() + directSource.readerIndex(),
              length,
              directDestination.memoryAddress(),
              uncompressedSize);
      check(result);
      destination.writeBytes(directDestination, 0, uncompressedSize);
    } finally {
      directDestination.release();
      if (copySource) {
        directSource.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Copies the readable bytes of {@code buffer} into a new direct buffer, to release. */
  private static ByteBuf directCopy(ByteBuf buffer, int length) {
    return ByteBufAllocator.DEFAULT
        .directBuffer(length)
        .writeBytes(buffer, buffer.readerIndex(), length);
  }

  private static void advance(ByteBuf destination, long produced) {
    if (produced <= 0) {
      // Cannot happen: the output was sized with libdeflate's own worst-case bound.
      throw new IllegalStateException("libdeflate produced nothing in its worst-case bound");
    }
    destination.writerIndex(destination.writerIndex() + (int) produced);
  }

  private static void check(int result) throws DataFormatException {
    switch (result) {
      case Libdeflate.SUCCESS -> {}
      case Libdeflate.SHORT_OUTPUT -> throw SHORT_OUTPUT;
      case Libdeflate.INSUFFICIENT_SPACE -> throw INSUFFICIENT_SPACE;
      default -> throw BAD_DATA;
    }
  }

  /** Pre-allocated, without a stack trace: the error paths of a hot decoder must not allocate. */
  private static DataFormatException stackless(String message) {
    return new DataFormatException(message) {
      @java.io.Serial private static final long serialVersionUID = 1L;

      @Override
      public synchronized Throwable fillInStackTrace() {
        return this;
      }
    };
  }
}
