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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Platform")
class PlatformTest {

  @Nested
  @DisplayName("detection")
  class Detection {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
      "Linux, amd64, false, linux-x86_64, libdeflate.so",
      "Linux, aarch64, false, linux-aarch64, libdeflate.so",
      "Linux, amd64, true, linux-x86_64-musl, libdeflate.so",
      "Linux, aarch64, true, linux-aarch64-musl, libdeflate.so",
      "Mac OS X, x86_64, false, macos-x86_64, libdeflate.dylib",
      "Mac OS X, aarch64, false, macos-aarch64, libdeflate.dylib",
      "Windows 11, amd64, false, windows-x86_64, deflate.dll",
      "Windows Server 2025, amd64, false, windows-x86_64, deflate.dll"
    })
    @DisplayName("should map every machine Warp bundles a library for to its directory")
    void supported(String osName, String osArch, boolean musl, String id, String library)
        throws NativeLoadException {
      Platform platform = Platform.detect(osName, osArch, 8, () -> musl);
      assertEquals(id, platform.id());
      assertEquals(library, platform.libraryFileName());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
      "FreeBSD, amd64",
      "SunOS, amd64",
      "AIX, ppc64",
      "Linux, arm",
      "Linux, x86",
      "Linux, riscv64",
      "Linux, ppc64le",
      "Linux, s390x",
      "Windows 11, aarch64"
    })
    @DisplayName("should refuse machines without a bundled library, saying which")
    void unsupported(String osName, String osArch) {
      NativeLoadException e =
          assertThrows(
              NativeLoadException.class, () -> Platform.detect(osName, osArch, 8, () -> false));
      assertTrue(String.valueOf(e.getMessage()).contains(osName + " " + osArch), e.getMessage());
      assertFalse(e.advice().isEmpty());
    }

    @Test
    @DisplayName("should refuse a 32-bit JVM, whose pointers do not fit the bindings")
    void thirtyTwoBit() {
      NativeLoadException e =
          assertThrows(
              NativeLoadException.class, () -> Platform.detect("Linux", "amd64", 4, () -> false));
      assertTrue(String.valueOf(e.getMessage()).contains("64-bit"));
    }

    @Test
    @DisplayName("should ask for the C library on Linux only")
    void muslOnLinuxOnly() throws NativeLoadException {
      AtomicBoolean asked = new AtomicBoolean();
      Platform platform =
          Platform.detect(
              "Mac OS X",
              "aarch64",
              8,
              () -> {
                asked.set(true);
                return true;
              });
      assertFalse(asked.get());
      assertFalse(platform.musl());
    }
  }

  @Test
  @DisplayName("should detect the machine running the tests")
  void current() throws NativeLoadException {
    Platform platform = Platform.current();
    assertTrue(platform.id().startsWith(platform.os() + "-" + platform.arch()));
  }
}
