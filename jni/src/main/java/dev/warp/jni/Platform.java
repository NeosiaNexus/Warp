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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * An operating system, architecture and C library that Warp bundles a native library for.
 *
 * <p>The identifier ({@link #id()}) names the jar directory that holds the library, and the
 * platform argument of {@code jni/native/build-libdeflate.sh}: {@code linux-x86_64}, {@code
 * linux-aarch64-musl}, {@code macos-aarch64}, {@code windows-x86_64}...
 *
 * @param os {@code linux}, {@code macos} or {@code windows}
 * @param arch {@code x86_64} or {@code aarch64}
 * @param musl whether the C library is musl rather than glibc (Linux only)
 */
record Platform(String os, String arch, boolean musl) {

  static final String LINUX = "linux";
  static final String MACOS = "macos";
  static final String WINDOWS = "windows";

  /**
   * Returns the platform identifier, the name of its directory in the jar.
   *
   * @return for example {@code linux-x86_64-musl}
   */
  String id() {
    return os + "-" + arch + (musl ? "-musl" : "");
  }

  /**
   * Returns the file name of libdeflate on this platform.
   *
   * @return {@code libdeflate.so}, {@code libdeflate.dylib} or {@code deflate.dll}
   */
  String libraryFileName() {
    return switch (os) {
      case MACOS -> "libdeflate.dylib";
      case WINDOWS -> "deflate.dll";
      default -> "libdeflate.so";
    };
  }

  /**
   * Detects the platform this JVM runs on.
   *
   * @return the platform
   * @throws NativeLoadException if Warp bundles no library for this machine
   */
  static Platform current() throws NativeLoadException {
    return detect(
        System.getProperty("os.name", ""),
        System.getProperty("os.arch", ""),
        ValueLayout.ADDRESS.byteSize(),
        Platform::jvmUsesMusl);
  }

  /**
   * Maps the JVM's description of the machine to a platform.
   *
   * @param osName the {@code os.name} system property
   * @param osArch the {@code os.arch} system property
   * @param addressSize the size of a native pointer, in bytes
   * @param musl tells whether the C library is musl; asked on Linux only
   * @return the platform
   * @throws NativeLoadException if Warp bundles no library for this machine
   */
  static Platform detect(String osName, String osArch, long addressSize, BooleanSupplier musl)
      throws NativeLoadException {
    String name = osName.toLowerCase(Locale.ROOT);
    String os;
    if (name.startsWith("linux")) {
      os = LINUX;
    } else if (name.startsWith("mac") || name.startsWith("darwin")) {
      os = MACOS;
    } else if (name.startsWith("windows")) {
      os = WINDOWS;
    } else {
      throw new NativeLoadException(
          "no native library is bundled for " + osName + " " + osArch,
          NativeLoadException.UNSUPPORTED_ADVICE);
    }
    String arch =
        switch (osArch.toLowerCase(Locale.ROOT)) {
          case "amd64", "x86_64", "x64" -> "x86_64";
          case "aarch64", "arm64" -> "aarch64";
          default ->
              throw new NativeLoadException(
                  "no native library is bundled for " + osName + " " + osArch,
                  NativeLoadException.UNSUPPORTED_ADVICE);
        };
    if (os.equals(WINDOWS) && !arch.equals("x86_64")) {
      throw new NativeLoadException(
          "no native library is bundled for " + osName + " " + osArch,
          NativeLoadException.UNSUPPORTED_ADVICE);
    }
    // The bindings pass pointers as Java longs, which is only right with 64-bit pointers.
    if (addressSize != Long.BYTES) {
      throw new NativeLoadException(
          "native libraries need a 64-bit JVM, this one has " + addressSize * 8 + "-bit pointers",
          "Run Warp on a 64-bit JVM");
    }
    return new Platform(os, arch, os.equals(LINUX) && musl.getAsBoolean());
  }

  /**
   * Tells whether the JVM runs on musl: from its own memory map, the C library actually loaded in
   * this process. This holds on Alpine with a glibc compatibility layer (which runs on musl), and
   * on a glibc system with stray musl files. Without {@code /proc}, the musl dynamic loader's
   * presence decides.
   */
  private static boolean jvmUsesMusl() {
    try (Stream<String> maps = Files.lines(Path.of("/proc/self/maps"))) {
      return maps.anyMatch(line -> line.contains("/ld-musl-") || line.contains("/libc.musl-"));
    } catch (IOException | UncheckedIOException e) {
      try (DirectoryStream<Path> loaders =
          Files.newDirectoryStream(Path.of("/lib"), "ld-musl-*.so.1")) {
        return loaders.iterator().hasNext();
      } catch (IOException | UncheckedIOException e2) {
        return false;
      }
    }
  }
}
