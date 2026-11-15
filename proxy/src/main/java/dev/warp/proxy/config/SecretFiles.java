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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes the files that hold a secret (the forwarding secret, the anti-bot's secret and token keys)
 * so that they are readable by their owner only from the instant they exist.
 *
 * <p>A file created with the platform's defaults and restricted afterwards is readable by other
 * users in between; here the permissions are part of the creation itself. On a file system without
 * POSIX permissions (Windows) the file inherits those of its directory, as before.
 */
public final class SecretFiles {

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  private SecretFiles() {}

  /**
   * Creates {@code file} with {@code text} in it, readable and writable by its owner only.
   *
   * @param file the file, which must not exist; its directory must
   * @param text what to write, encoded as UTF-8
   * @throws java.nio.file.FileAlreadyExistsException when the file exists: a secret is never
   *     overwritten by a creation
   * @throws IOException when the file cannot be created or written
   */
  public static void create(Path file, String text) throws IOException {
    ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    try (SeekableByteChannel channel =
        Files.newByteChannel(
            file,
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            ownerOnly(file))) {
      while (bytes.hasRemaining()) {
        channel.write(bytes);
      }
    }
  }

  /**
   * Replaces {@code file} with {@code text} in one step: readers see the old content or the new,
   * never a part of either, and the new file is its owner's only from the start.
   *
   * @param file the file, which may exist; its directory must
   * @param text what to write, encoded as UTF-8
   * @throws IOException when the file cannot be written or moved into place
   */
  public static void replace(Path file, String text) throws IOException {
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    Files.deleteIfExists(temporary);
    try {
      create(temporary, text);
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static FileAttribute<?>[] ownerOnly(Path file) {
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)};
    }
    return new FileAttribute<?>[0];
  }
}
