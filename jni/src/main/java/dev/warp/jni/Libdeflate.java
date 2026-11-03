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

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * libdeflate's zlib functions, bound through the FFM API ({@code java.lang.foreign}).
 *
 * <p>Loading happens when this class initialises ({@link NativeLoader#load()}); if it fails, the
 * class fails to initialise and {@link Natives} reports why. Nothing else may touch this class
 * before {@link Natives#compression()} says the library is available.
 *
 * <p>Pointers and {@code size_t} values cross as Java {@code long}s, which every supported platform
 * passes in the same 64-bit registers (Platform refuses 32-bit JVMs). No {@link MemorySegment} or
 * arena is created per call, and the method handles are {@code static final}, which the JIT inlines
 * as constants.
 *
 * <p><b>Memory.</b> Every function takes raw addresses. The caller owns the memory behind them,
 * keeps it alive and unmodified for the duration of the call, and guarantees the lengths:
 * libdeflate reads at most {@code inLength} bytes from {@code in} and writes at most {@code
 * outAvailable} bytes to {@code out}. Nothing is retained after a call returns. Contexts are
 * allocated and freed only here; a context must not be used by two threads at once, nor after it is
 * freed.
 */
final class Libdeflate {

  /** {@code LIBDEFLATE_SUCCESS}: the data decompressed to exactly the expected size. */
  static final int SUCCESS = 0;

  /** {@code LIBDEFLATE_BAD_DATA}: the compressed data is invalid or corrupt. */
  static final int BAD_DATA = 1;

  /** {@code LIBDEFLATE_SHORT_OUTPUT}: the data decompressed to fewer bytes than expected. */
  static final int SHORT_OUTPUT = 2;

  /** {@code LIBDEFLATE_INSUFFICIENT_SPACE}: the data decompresses to more bytes than expected. */
  static final int INSUFFICIENT_SPACE = 3;

  /** The library this class is bound to. */
  static final NativeLoader.Loaded LIBRARY = load();

  private static final MethodHandle ALLOC_COMPRESSOR =
      bind("libdeflate_alloc_compressor", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));

  private static final MethodHandle FREE_COMPRESSOR =
      bind("libdeflate_free_compressor", FunctionDescriptor.ofVoid(JAVA_LONG));

  private static final MethodHandle ZLIB_COMPRESS =
      bind(
          "libdeflate_zlib_compress",
          FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));

  // A few arithmetic instructions: a critical call skips the thread-state transition, which costs
  // more than the function.
  private static final MethodHandle ZLIB_COMPRESS_BOUND =
      bind(
          "libdeflate_zlib_compress_bound",
          FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG),
          Linker.Option.critical(false));

  private static final MethodHandle ALLOC_DECOMPRESSOR =
      bind("libdeflate_alloc_decompressor", FunctionDescriptor.of(JAVA_LONG));

  private static final MethodHandle FREE_DECOMPRESSOR =
      bind("libdeflate_free_decompressor", FunctionDescriptor.ofVoid(JAVA_LONG));

  private static final MethodHandle ZLIB_DECOMPRESS =
      bind(
          "libdeflate_zlib_decompress",
          FunctionDescriptor.of(
              JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));

  private Libdeflate() {}

  // ---------------------------------------------------------------------------
  // Compression
  // ---------------------------------------------------------------------------

  /**
   * {@code libdeflate_alloc_compressor}: allocates a compressor, owned by the caller until {@link
   * #freeCompressor}. About 656 KiB at levels 2 to 9, 200 KiB at level 1.
   *
   * @param level 0 to 12
   * @return the compressor's address, or 0 if out of memory or the level is invalid
   */
  static long allocCompressor(int level) {
    try {
      return (long) ALLOC_COMPRESSOR.invokeExact(level);
    } catch (Throwable t) {
      throw unexpected(t);
    }
  }

  /**
   * {@code libdeflate_free_compressor}: frees a compressor. Its address is invalid afterwards.
   *
   * @param compressor an address from {@link #allocCompressor}, or 0 (ignored)
   */
  static void freeCompressor(long compressor) {
    try {
      FREE_COMPRESSOR.invokeExact(compressor);
    } catch (Throwable t) {
      throw unexpected(t);
    }
  }

  /**
   * {@code libdeflate_zlib_compress}: compresses {@code inLength} bytes at {@code in} into a zlib
   * stream at {@code out}. Reads only {@code [in, in + inLength)} and writes only {@code [out, out
   * + outAvailable)}; both ranges stay owned by the caller.
   *
   * @param compressor the calling thread's compressor
   * @param in address of the uncompressed bytes
   * @param inLength number of uncompressed bytes
   * @param out address of the output
   * @param outAvailable bytes writable at {@code out}; {@link #zlibCompressBound} is always enough
   * @return the compressed size, or 0 if it did not fit in {@code outAvailable} bytes
   */
  static long zlibCompress(long compressor, long in, long inLength, long out, long outAvailable) {
    try {
      return (long) ZLIB_COMPRESS.invokeExact(compressor, in, inLength, out, outAvailable);
    } catch (Throwable t) {
      throw unexpected(t);
    }
  }

  /**
   * {@code libdeflate_zlib_compress_bound}: the most bytes {@link #zlibCompress} can produce from
   * {@code inLength} bytes. Touches no buffer.
   *
   * @param compressor a compressor, or 0 for a bound valid for any compressor
   * @param inLength number of uncompressed bytes
   * @return the worst-case compressed size
   */
  static long zlibCompressBound(long compressor, long inLength) {
    try {
      return (long) ZLIB_COMPRESS_BOUND.invokeExact(compressor, inLength);
    } catch (Throwable t) {
      throw unexpected(t);
    }
  }

  // ---------------------------------------------------------------------------
  // Decompression
  // ---------------------------------------------------------------------------

  /**
   * {@code libdeflate_alloc_decompressor}: allocates a decompressor (about 11 KiB), owned by the
   * caller until {@link #freeDecompressor}.
   *
   * @return the decompressor's address, or 0 if out of memory
   */
  static long allocDecompressor() {
    try {
      return (long) ALLOC_DECOMPRESSOR.invokeExact();
    } catch (Throwable t) {
      throw unexpected(t);
    }
  }

  /**
   * {@code libdeflate_free_decompressor}: frees a decompressor. Its address is invalid afterwards.
   *
   * @param decompressor an address from {@link #allocDecompressor}, or 0 (ignored)
   */
  static void freeDecompressor(long decompressor) {
    try {
      FREE_DECOMPRESSOR.invokeExact(decompressor);
    } catch (Throwable t) {
      throw unexpected(t);
    }
  }

  /**
   * {@code libdeflate_zlib_decompress} with no {@code actual_out_nbytes_ret}: decompresses the zlib
   * stream at {@code in} into exactly {@code expectedLength} bytes at {@code out}, and fails if the
   * data is corrupt or decompresses to any other size. Reads only {@code [in, in + inLength)},
   * writes only {@code [out, out + expectedLength)}, whatever the input claims; both ranges stay
   * owned by the caller. Bytes after the end of the zlib stream are ignored, as {@link
   * java.util.zip.Inflater} ignores them. On failure the output's content is undefined.
   *
   * @param decompressor the calling thread's decompressor
   * @param in address of the zlib stream
   * @param inLength bytes readable at {@code in}
   * @param out address of the output
   * @param expectedLength the exact decompressed size, and the bytes writable at {@code out}
   * @return {@link #SUCCESS}, {@link #BAD_DATA}, {@link #SHORT_OUTPUT} or {@link
   *     #INSUFFICIENT_SPACE}
   */
  static int zlibDecompress(
      long decompressor, long in, long inLength, long out, long expectedLength) {
    try {
      return (int) ZLIB_DECOMPRESS.invokeExact(decompressor, in, inLength, out, expectedLength, 0L);
    } catch (Throwable t) {
      throw unexpected(t);
    }
  }

  // ---------------------------------------------------------------------------
  // Binding
  // ---------------------------------------------------------------------------

  private static NativeLoader.Loaded load() {
    try {
      return NativeLoader.load();
    } catch (NativeLoadException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  @SuppressWarnings("restricted") // native access is enabled by the jar's manifest
  private static MethodHandle bind(
      String name, FunctionDescriptor descriptor, Linker.Option... options) {
    MemorySegment symbol =
        LIBRARY
            .symbols()
            .find(name)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        LIBRARY.location()
                            + " does not export "
                            + name
                            + ", it is not Warp's build"));
    return Linker.nativeLinker().downcallHandle(symbol, descriptor, options);
  }

  /** A downcall declares {@code Throwable} but only ever throws unchecked exceptions or errors. */
  private static RuntimeException unexpected(Throwable t) {
    if (t instanceof RuntimeException e) {
      return e;
    }
    if (t instanceof Error e) {
      throw e;
    }
    return new IllegalStateException(t);
  }
}
