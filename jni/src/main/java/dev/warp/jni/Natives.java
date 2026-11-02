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

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Entry point to Warp's native libraries: tells whether they can be used, and why not.
 *
 * <p>Today this is libdeflate, for packet compression. The first call to {@link #compression()}
 * loads it (see {@link NativeLoader}) and runs a self-test: a round trip, both size errors,
 * corruption, and interoperability with {@code java.util.zip} in both directions. Any failure, at
 * any step, makes the library unavailable for the life of the JVM, with the reason in {@link
 * NativeStatus#detail()}, and callers fall back to {@code java.util.zip}. Nothing here throws.
 *
 * <p>System properties, read once:
 *
 * <ul>
 *   <li>{@code -D}{@value #DISABLE_PROPERTY}{@code =true}: never load a native library, use the
 *       Java implementations;
 *   <li>{@code -D}{@value #DIRECTORY_PROPERTY}{@code =<directory>}: install native libraries there
 *       instead of the user's cache directory. The directory must belong to the user running Warp
 *       and be writable by nobody else.
 * </ul>
 *
 * <p>Run {@code java -cp warp.jar dev.warp.jni.Natives} to see what a machine gets.
 */
public final class Natives {

  /** System property that disables every native library when set to {@code true}. */
  public static final String DISABLE_PROPERTY = "warp.natives.disable";

  /** System property naming the directory native libraries are installed into. */
  public static final String DIRECTORY_PROPERTY = "warp.natives.dir";

  private static final String REPORT_ADVICE =
      "Please report this line at https://github.com/NeosiaNexus/Warp/issues; start Warp with -D"
          + DISABLE_PROPERTY
          + "=true to skip the native library";

  private Natives() {}

  /**
   * Returns whether native compression (libdeflate) can be used, loading and testing it on the
   * first call. Thread-safe; later calls return the same result at no cost.
   *
   * @return the status of native compression
   */
  public static NativeStatus compression() {
    return Compression.STATUS;
  }

  /**
   * Returns whether native compression (libdeflate) is available on this platform.
   *
   * @return {@code true} if {@link LibdeflateCodec} can be used
   */
  public static boolean isNativeCompressionAvailable() {
    return compression().available();
  }

  /**
   * Prints whether native compression works on this machine and exits with status 0 if it does, 1
   * otherwise. With {@code --expect <platform>}, also fails unless that platform's build loaded (CI
   * uses it to check each bundled library on its own platform).
   *
   * @param args nothing, or {@code --expect} and a platform identifier such as {@code
   *     linux-aarch64-musl}
   */
  public static void main(String[] args) {
    NativeStatus status = compression();
    System.out.println(
        (status.available() ? "Native compression available: " : "Native compression unavailable: ")
            + status.detail());
    if (!status.advice().isEmpty()) {
      System.out.println(status.advice() + ".");
    }
    boolean ok = status.available();
    if (ok && args.length == 2 && args[0].equals("--expect")) {
      String loaded = Libdeflate.LIBRARY.platform().id();
      if (!loaded.equals(args[1])) {
        System.out.println("Expected the " + args[1] + " build, loaded the " + loaded + " build.");
        ok = false;
      }
    }
    System.exit(ok ? 0 : 1);
  }

  // ---------------------------------------------------------------------------
  // Probe
  // ---------------------------------------------------------------------------

  /** Initialised on the first call to {@link #compression()}, exactly once. */
  private static final class Compression {
    static final NativeStatus STATUS = probe(Boolean.getBoolean(DISABLE_PROPERTY));
  }

  /**
   * Loads and self-tests libdeflate, unless disabled. Loading happens once per JVM whatever the
   * number of calls; {@link #compression()} keeps the first result.
   *
   * @param disabled whether native libraries are disabled ({@value #DISABLE_PROPERTY})
   * @return the status
   */
  static NativeStatus probe(boolean disabled) {
    if (disabled) {
      return new NativeStatus(false, "disabled by -D" + DISABLE_PROPERTY + "=true", "");
    }
    // Without native access, today's JDK warns on the first native call and a future one refuses.
    String accessAdvice =
        Natives.class.getModule().isNativeAccessEnabled()
            ? ""
            : "Start the JVM with --enable-native-access=ALL-UNNAMED (java -jar warp.jar does it"
                + " through the jar's manifest)";
    String bindAdvice = accessAdvice.isEmpty() ? REPORT_ADVICE : accessAdvice;
    NativeLoader.Loaded library;
    try {
      library = Libdeflate.LIBRARY;
    } catch (ExceptionInInitializerError e) {
      Throwable cause = e.getCause();
      if (cause != null && cause.getCause() instanceof NativeLoadException failure) {
        return new NativeStatus(false, String.valueOf(failure.getMessage()), failure.advice());
      }
      return new NativeStatus(false, "cannot bind libdeflate: " + cause, bindAdvice);
    } catch (LinkageError | RuntimeException e) {
      return new NativeStatus(false, "cannot bind libdeflate: " + e, bindAdvice);
    }
    try {
      selfTest();
    } catch (DataFormatException | RuntimeException | LinkageError e) {
      return new NativeStatus(
          false,
          "libdeflate "
              + library.version()
              + " ("
              + library.platform().id()
              + ") failed its self-test: "
              + e.getMessage(),
          REPORT_ADVICE);
    }
    return new NativeStatus(
        true,
        "libdeflate "
            + library.version()
            + " ("
            + library.platform().id()
            + ") from "
            + library.location(),
        accessAdvice);
  }

  /**
   * Checks the loaded library before any packet goes through it. Runs once, on the thread that
   * first asks for the status, with contexts of its own that it frees.
   *
   * @throws DataFormatException if a check fails
   */
  static void selfTest() throws DataFormatException {
    byte[] input = sample(64 * 1024);
    long compressor = Libdeflate.allocCompressor(LibdeflateCodec.DEFAULT_LEVEL);
    long decompressor = Libdeflate.allocDecompressor();
    try (Arena arena = Arena.ofConfined()) {
      if (compressor == 0 || decompressor == 0) {
        throw new DataFormatException("cannot allocate libdeflate contexts");
      }
      MemorySegment in = arena.allocate(input.length);
      MemorySegment.copy(input, 0, in, JAVA_BYTE, 0, input.length);
      long bound = Libdeflate.zlibCompressBound(compressor, input.length);
      MemorySegment compressed = arena.allocate(bound);
      long size =
          Libdeflate.zlibCompress(
              compressor, in.address(), input.length, compressed.address(), bound);
      if (size <= 0 || size >= input.length) {
        throw new DataFormatException("compressing 64 KiB produced " + size + " bytes");
      }
      MemorySegment out = arena.allocate(input.length + 1L);

      expect(
          "round trip",
          Libdeflate.SUCCESS,
          decompress(decompressor, compressed, size, out, input.length));
      if (out.asSlice(0, input.length).mismatch(in) != -1) {
        throw new DataFormatException("round trip changed the data");
      }
      expect(
          "one byte less than the real size",
          Libdeflate.INSUFFICIENT_SPACE,
          decompress(decompressor, compressed, size, out, input.length - 1L));
      expect(
          "one byte more than the real size",
          Libdeflate.SHORT_OUTPUT,
          decompress(decompressor, compressed, size, out, input.length + 1L));

      // java.util.zip reads libdeflate's streams, and libdeflate reads java.util.zip's.
      byte[] stream = compressed.asSlice(0, size).toArray(JAVA_BYTE);
      if (!Arrays.equals(javaInflate(stream, input.length), input)) {
        throw new DataFormatException("java.util.zip reads libdeflate's stream differently");
      }
      byte[] javaStream = javaDeflate(input);
      MemorySegment javaCompressed = arena.allocate(javaStream.length);
      MemorySegment.copy(javaStream, 0, javaCompressed, JAVA_BYTE, 0, javaStream.length);
      expect(
          "java.util.zip's stream",
          Libdeflate.SUCCESS,
          decompress(decompressor, javaCompressed, javaStream.length, out, input.length));
      if (out.asSlice(0, input.length).mismatch(in) != -1) {
        throw new DataFormatException("libdeflate reads java.util.zip's stream differently");
      }

      // A corrupt checksum is rejected, not decompressed.
      compressed.set(JAVA_BYTE, size - 1, (byte) ~compressed.get(JAVA_BYTE, size - 1));
      expect(
          "a corrupt checksum",
          Libdeflate.BAD_DATA,
          decompress(decompressor, compressed, size, out, input.length));
    } finally {
      Libdeflate.freeCompressor(compressor);
      Libdeflate.freeDecompressor(decompressor);
    }
  }

  private static int decompress(
      long decompressor, MemorySegment in, long inLength, MemorySegment out, long expected) {
    return Libdeflate.zlibDecompress(decompressor, in.address(), inLength, out.address(), expected);
  }

  private static void expect(String what, int expected, int actual) throws DataFormatException {
    if (actual != expected) {
      throw new DataFormatException(
          "decompressing " + what + " returned " + actual + " instead of " + expected);
    }
  }

  /** Packet-like data: runs of structured bytes between runs of random ones. */
  private static byte[] sample(int length) {
    byte[] data = new byte[length];
    SplittableRandom random = new SplittableRandom(0x5741_5250L);
    for (int i = 0; i < length; i++) {
      data[i] = (i & 1023) < 640 ? (byte) ((i * 31) >>> 4 & 0x3F) : (byte) random.nextInt(256);
    }
    return data;
  }

  private static byte[] javaInflate(byte[] stream, int length) throws DataFormatException {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(stream);
      byte[] out = new byte[length];
      int produced = inflater.inflate(out);
      if (produced != length || !inflater.finished()) {
        throw new DataFormatException("java.util.zip inflated " + produced + " bytes");
      }
      return out;
    } finally {
      inflater.end();
    }
  }

  private static byte[] javaDeflate(byte[] input) {
    Deflater deflater = new Deflater();
    try {
      deflater.setInput(input);
      deflater.finish();
      byte[] buffer = new byte[input.length + 1024];
      int size = 0;
      while (!deflater.finished()) {
        size += deflater.deflate(buffer, size, buffer.length - size);
      }
      return Arrays.copyOf(buffer, size);
    } finally {
      deflater.end();
    }
  }
}
