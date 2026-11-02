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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("NativeLoader")
class NativeLoaderTest {

  @TempDir private Path temp;

  @Nested
  @DisplayName("directories")
  class Directories {

    @Test
    @DisplayName("should use the configured directory alone")
    void configured() {
      String previous = System.setProperty(Natives.DIRECTORY_PROPERTY, temp.toString());
      try {
        assertEquals(
            List.of(temp), NativeLoader.directories(new Platform(Platform.LINUX, "x86_64", false)));
      } finally {
        restore(previous);
      }
    }

    @Test
    @DisplayName("should default to the user's cache directory on each system")
    void userCache() {
      String previous = System.clearProperty(Natives.DIRECTORY_PROPERTY);
      try {
        Path home = Path.of(System.getProperty("user.home", ""));
        assertEquals(
            List.of(home.resolve("Library/Caches/warp/natives")),
            NativeLoader.directories(new Platform(Platform.MACOS, "aarch64", false)));
        List<Path> linux = NativeLoader.directories(new Platform(Platform.LINUX, "x86_64", false));
        assertEquals(1, linux.size());
        assertTrue(linux.getFirst().endsWith(Path.of("warp", "natives")), linux.toString());
      } finally {
        restore(previous);
      }
    }

    private static void restore(@Nullable String previous) {
      if (previous == null) {
        System.clearProperty(Natives.DIRECTORY_PROPERTY);
      } else {
        System.setProperty(Natives.DIRECTORY_PROPERTY, previous);
      }
    }
  }

  @Nested
  @DisplayName("opening")
  class Opening {

    @Test
    @DisplayName("should say why the system loader refuses a file")
    void refused() throws IOException {
      // given: not a library at all. Never a truncated one: glibc's dlopen dies of SIGBUS on those,
      // which is why LibraryCache checks size and digest before anything is loaded. HotSpot prints
      // a stack guard warning about this file before refusing it.
      Path garbage = Files.write(temp.resolve("libgarbage.so"), new byte[] {1, 2, 3, 4});

      // when
      NativeLoadException e =
          assertThrows(NativeLoadException.class, () -> NativeLoader.open(garbage));

      // then
      assertTrue(
          String.valueOf(e.getMessage()).startsWith("cannot open " + garbage + ": "),
          e.getMessage());
      assertTrue(
          String.valueOf(e.getMessage()).length() > ("cannot open " + garbage + ": ").length());
    }

    @Test
    @DisplayName("should load the bundled library from the cache and find libdeflate's API")
    void fromCache() throws Exception {
      // given
      byte[] library = bundledLibrary();
      Path file =
          LibraryCache.install(
              temp, LibraryCache.fileName("libdeflate-test", Platform.current(), library), library);

      // when
      SymbolLookup symbols = NativeLoader.open(file);

      // then
      assertTrue(symbols.find("libdeflate_zlib_decompress").isPresent());
      assertTrue(symbols.find("libdeflate_gzip_compress").isEmpty(), "only zlib is built");
    }

    @Test
    @DisplayName("should load the bundled library from memory on Linux, for noexec machines")
    void fromMemory() throws Exception {
      // given
      Platform platform = Platform.current();
      assumeTrue(platform.os().equals(Platform.LINUX), "memory files are Linux-only");
      byte[] library = bundledLibrary();

      // when
      SymbolLookup symbols = NativeLoader.openInMemory("libdeflate-test", library);

      // then
      assertTrue(symbols.find("libdeflate_alloc_compressor").isPresent());
      assertTrue(symbols.find("no_such_symbol").isEmpty());
    }

    private static byte[] bundledLibrary() throws NativeLoadException {
      NativeAssumptions.requireNativeCompression();
      byte[] library = NativeLoader.resource(Platform.current());
      assumeTrue(library != null, "no library bundled for this machine");
      return Objects.requireNonNull(library);
    }
  }
}
