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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("LibraryCache")
class LibraryCacheTest {

  private static final boolean POSIX =
      FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

  private static final Platform LINUX = new Platform("linux", "x86_64", false);

  private static final byte[] LIBRARY =
      "pretend this is an ELF shared object".getBytes(StandardCharsets.US_ASCII);

  @TempDir private Path temp;

  @Nested
  @DisplayName("names")
  class Names {

    @Test
    @DisplayName("should name a library by version, platform and content")
    void contentAddressed() {
      String name = LibraryCache.fileName("libdeflate-1.26", LINUX, LIBRARY);
      assertTrue(name.matches("libdeflate-1\\.26-linux-x86_64-[0-9a-f]{16}\\.so"), name);
    }

    @Test
    @DisplayName("should give different builds different names")
    void differentBuilds() {
      byte[] other = LIBRARY.clone();
      other[0] ^= 1;
      assertNotEquals(
          LibraryCache.fileName("libdeflate-1.26", LINUX, LIBRARY),
          LibraryCache.fileName("libdeflate-1.26", LINUX, other));
      assertNotEquals(
          LibraryCache.fileName("libdeflate-1.26", LINUX, LIBRARY),
          LibraryCache.fileName("libdeflate-1.26", new Platform("linux", "x86_64", true), LIBRARY));
    }
  }

  @Nested
  @DisplayName("installation")
  class Installation {

    @Test
    @DisplayName("should create a missing directory, private to the user")
    void createsPrivateDirectory() throws IOException {
      // given
      Path directory = temp.resolve("cache/warp/natives");

      // when
      Path file = LibraryCache.install(directory, "lib.so", LIBRARY);

      // then
      assertArrayEquals(LIBRARY, Files.readAllBytes(file));
      if (POSIX) {
        assertEquals(
            "rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
        assertFalse(
            Files.getPosixFilePermissions(file).toString().contains("OTHERS"),
            "the library is not readable by others");
      }
    }

    @Test
    @DisplayName("should reuse an intact library instead of writing it again")
    void reusesIntactFile() throws IOException {
      // given
      Path file = LibraryCache.install(temp, "lib.so", LIBRARY);
      FileTime old = FileTime.fromMillis(1_000_000L);
      Files.setLastModifiedTime(file, old);

      // when
      Path again = LibraryCache.install(temp, "lib.so", LIBRARY);

      // then
      assertEquals(file, again);
      assertEquals(old, Files.getLastModifiedTime(again), "not rewritten");
    }

    @Test
    @DisplayName("should use a library installed in a read-only directory, as in a container image")
    void readOnlyDirectory() throws IOException {
      assumeTrue(POSIX, "needs POSIX permissions");
      // given
      Path file = LibraryCache.install(temp, "lib.so", LIBRARY);
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("r-x------"));
      assumeFalse(Files.isWritable(temp), "the directory stays writable (running as root)");
      try {
        // when
        Path again = LibraryCache.install(temp, "lib.so", LIBRARY);

        // then
        assertEquals(file, again);
        IOException other =
            assertThrows(IOException.class, () -> LibraryCache.install(temp, "other.so", LIBRARY));
        assertTrue(String.valueOf(other.getMessage()).contains("read-only"));
      } finally {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
      }
    }

    @Test
    @DisplayName("should replace a file whose bytes changed")
    void replacesTamperedFile() throws IOException {
      // given
      Path file = LibraryCache.install(temp, "lib.so", LIBRARY);
      Files.write(file, "malicious".getBytes(StandardCharsets.US_ASCII));

      // when
      LibraryCache.install(temp, "lib.so", LIBRARY);

      // then
      assertArrayEquals(LIBRARY, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("should replace a link planted under the library's name, not follow it")
    void replacesLink() throws IOException {
      // given
      Path target = Files.write(temp.resolve("elsewhere.so"), LIBRARY);
      Path link = temp.resolve("lib.so");
      try {
        Files.createSymbolicLink(link, target);
      } catch (UnsupportedOperationException | IOException e) {
        assumeTrue(false, "symbolic links unsupported here");
      }

      // when
      Path file = LibraryCache.install(temp, "lib.so", LIBRARY);

      // then
      assertFalse(Files.isSymbolicLink(file));
      assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    @DisplayName("should refuse a directory other users can write to")
    void refusesSharedDirectory() throws IOException {
      assumeTrue(POSIX);
      // given: like /tmp, where anyone could plant a library under the name Warp loads
      Path shared = Files.createDirectory(temp.resolve("shared"));
      Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));

      // when
      IOException e =
          assertThrows(IOException.class, () -> LibraryCache.install(shared, "lib.so", LIBRARY));

      // then
      assertTrue(
          String.valueOf(e.getMessage()).contains("writable by other users"), e.getMessage());
      assertFalse(Files.exists(shared.resolve("lib.so")));
    }

    @Test
    @DisplayName("should leave no temporary file behind")
    void noLeftovers() throws IOException {
      LibraryCache.install(temp, "lib.so", LIBRARY);
      try (Stream<Path> files = Files.list(temp)) {
        assertEquals(List.of(temp.resolve("lib.so")), files.toList());
      }
    }
  }

  @Nested
  @DisplayName("concurrency")
  class Concurrency {

    @Test
    @DisplayName("should let installs starting at the same moment all end with the intact library")
    void concurrentInstalls() throws Exception {
      // given: proxies starting together, each about to install the same library
      int installers = 16;
      byte[] library = new byte[256 * 1024];
      for (int i = 0; i < library.length; i++) {
        library[i] = (byte) (i * 31 + (i >>> 9));
      }
      CyclicBarrier start = new CyclicBarrier(installers);
      Set<Path> results = ConcurrentHashMap.newKeySet();
      ExecutorService pool = Executors.newFixedThreadPool(installers);
      try {
        // when
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < installers; i++) {
          futures.add(
              pool.submit(
                  () -> {
                    start.await();
                    Path file = LibraryCache.install(temp, "lib.so", library);
                    // each installer sees the complete library, never a partial file
                    assertArrayEquals(library, Files.readAllBytes(file));
                    results.add(file);
                    return null;
                  }));
        }
        for (Future<?> future : futures) {
          future.get();
        }
      } finally {
        pool.shutdownNow();
      }

      // then
      assertEquals(Set.of(temp.resolve("lib.so")), results);
      try (Stream<Path> files = Files.list(temp)) {
        assertEquals(1, files.count(), "no temporary file left");
      }
    }
  }
}
