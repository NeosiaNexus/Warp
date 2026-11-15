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
package dev.warp.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SecretFiles")
class SecretFilesTest {

  @TempDir private Path directory;

  @Nested
  @DisplayName("create")
  class Create {

    @Test
    @DisplayName("should write the text as UTF-8")
    void writesTheText() throws Exception {
      Path file = directory.resolve("secret");

      SecretFiles.create(file, "clé\n");

      assertEquals("clé\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("should leave the file readable and writable by its owner only")
    void ownerOnly() throws Exception {
      Path file = directory.resolve("secret");

      SecretFiles.create(file, "secret\n");

      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test
    @DisplayName("should refuse to overwrite a file that exists, and leave it as it was")
    void neverOverwrites() throws Exception {
      Path file = directory.resolve("secret");
      Files.writeString(file, "the operator's secret\n");

      assertThrows(FileAlreadyExistsException.class, () -> SecretFiles.create(file, "new\n"));

      assertEquals("the operator's secret\n", Files.readString(file));
    }

    @Test
    @DisplayName("should fail when the directory does not exist")
    void missingDirectory() {
      Path file = directory.resolve("missing").resolve("secret");

      assertThrows(NoSuchFileException.class, () -> SecretFiles.create(file, "secret\n"));
    }
  }

  @Nested
  @DisplayName("replace")
  class Replace {

    @Test
    @DisplayName("should create the file when it does not exist")
    void createsTheFile() throws Exception {
      Path file = directory.resolve("keys");

      SecretFiles.replace(file, "first\n");

      assertEquals("first\n", Files.readString(file));
    }

    @Test
    @DisplayName("should replace the content of a file that exists")
    void replacesTheContent() throws Exception {
      Path file = directory.resolve("keys");
      SecretFiles.replace(file, "a much longer first content\n");

      SecretFiles.replace(file, "second\n");

      assertEquals("second\n", Files.readString(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("should restrict to its owner a file that was readable by everyone")
    void restrictsALooseFile() throws Exception {
      Path file = directory.resolve("keys");
      Files.writeString(file, "old\n");
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

      SecretFiles.replace(file, "new\n");

      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test
    @DisplayName("should leave no temporary file behind")
    void leavesNothingBehind() throws Exception {
      Path file = directory.resolve("keys");

      SecretFiles.replace(file, "first\n");
      SecretFiles.replace(file, "second\n");

      try (Stream<Path> files = Files.list(directory)) {
        assertEquals(1, files.count());
      }
    }

    @Test
    @DisplayName("should write over a temporary file a crashed run left behind")
    void survivesAStaleTemporaryFile() throws Exception {
      Path file = directory.resolve("keys");
      Path stale = directory.resolve("keys.tmp");
      Files.writeString(stale, "half written");

      SecretFiles.replace(file, "whole\n");

      assertEquals("whole\n", Files.readString(file));
      assertFalse(Files.exists(stale));
    }

    @Test
    @DisplayName("should keep the old file and leave no temporary file when the move fails")
    void keepsTheOldFileOnFailure() throws Exception {
      Path file = directory.resolve("keys");
      Files.createDirectories(file.resolve("child"));

      assertThrows(java.io.IOException.class, () -> SecretFiles.replace(file, "new\n"));

      assertFalse(Files.exists(directory.resolve("keys.tmp")));
    }
  }
}
