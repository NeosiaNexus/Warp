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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Installs a native library from the jar into a directory the system loader can open it from.
 *
 * <p>Two proxies starting at the same moment, or a user who can write where the library goes, must
 * not be able to make Warp load anything but the bytes in its jar:
 *
 * <ul>
 *   <li><b>Content-addressed.</b> The file name carries the library's SHA-256 ({@link #fileName}),
 *       so builds never share a name, and a file found under that name is reused only if it still
 *       hashes to it: the library is written once, not at every start.
 *   <li><b>Atomic.</b> The bytes go to a temporary file of a random name in the same directory,
 *       created exclusively, flushed to disk, then renamed over the final name. A concurrent start
 *       either finds the complete file or renames identical bytes over it; nobody ever opens a
 *       partly written library. Where the rename cannot replace a library another process has
 *       loaded (Windows), that process's copy is used once it checks out.
 *   <li><b>Private.</b> The directory must belong to the user running Warp and be writable by
 *       nobody else, checked after resolving links: in a world-writable directory such as {@code
 *       /tmp}, another user could otherwise plant a library under the name Warp is about to load.
 *       Directories Warp creates are owner-only. A reused file must be a regular file (not a link)
 *       owned by that user.
 * </ul>
 *
 * <p>Not thread-safe; called once, when the library loads.
 */
final class LibraryCache {

  private static final boolean POSIX =
      FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

  private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");

  private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      PosixFilePermissions.fromString("rwx------");

  private LibraryCache() {}

  /**
   * Returns the cache file name for a library: its base name, its platform and the start of its
   * SHA-256, which tells builds apart.
   *
   * @param baseName the library name, such as {@code libdeflate-1.26}
   * @param platform the platform the library is built for
   * @param library the library's bytes
   * @return for example {@code libdeflate-1.26-linux-x86_64-0123456789abcdef.so}
   */
  static String fileName(String baseName, Platform platform, byte[] library) {
    String name = platform.libraryFileName();
    String extension = name.substring(name.lastIndexOf('.'));
    String digest = HexFormat.of().formatHex(sha256(library), 0, 8);
    return baseName + "-" + platform.id() + "-" + digest + extension;
  }

  /**
   * Makes sure {@code directory} holds {@code fileName} with exactly the bytes of {@code library},
   * writing it if needed.
   *
   * @param directory where to install the library; created owner-only if missing
   * @param fileName the name to install it under, from {@link #fileName}
   * @param library the library's bytes
   * @return the installed file, ready to be loaded
   * @throws IOException if the directory cannot be used safely or the file cannot be written
   */
  static Path install(Path directory, String fileName, byte[] library) throws IOException {
    if (Files.notExists(directory)) {
      if (POSIX) {
        Files.createDirectories(
            directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
      } else {
        Files.createDirectories(directory);
      }
    }
    Path realDirectory = directory.toRealPath();
    byte[] digest = sha256(library);
    Path target = realDirectory.resolve(fileName);

    // A directory this process cannot write to, such as a container image's layer under a
    // read-only root filesystem: the library installed there when the image was built is used as
    // it is, provided the directory is private to its owner (who alone could have written the
    // file) and the file holds exactly the bundled library.
    if (!Files.isWritable(realDirectory)) {
      UserPrincipal owner = Files.getOwner(realDirectory, LinkOption.NOFOLLOW_LINKS);
      checkPrivate(realDirectory, owner);
      if (holds(target, owner, digest, library.length)) {
        return target;
      }
      throw new IOException(realDirectory + " is read-only and does not hold the library");
    }

    // Created exclusively under a random name: also tells which user this process writes files as.
    Path temporary = Files.createTempFile(realDirectory, ".install-", ".tmp", ownerOnlyFile());
    try {
      UserPrincipal self = Files.getOwner(temporary, LinkOption.NOFOLLOW_LINKS);
      checkPrivate(realDirectory, self);
      if (holds(target, self, digest, library.length)) {
        return target;
      }
      try (FileChannel channel =
          FileChannel.open(
              temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer bytes = ByteBuffer.wrap(library);
        while (bytes.hasRemaining()) {
          channel.write(bytes);
        }
        channel.force(true);
      }
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        if (holds(target, self, digest, library.length)) {
          return target; // another process installed (and on Windows, loaded) the same library
        }
        throw e;
      }
      if (!holds(target, self, digest, library.length)) {
        throw new IOException(target + " changed while it was being installed");
      }
      return target;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /**
   * Fails unless {@code directory} belongs to {@code self} and, on POSIX systems, no other user can
   * write to it.
   */
  private static void checkPrivate(Path directory, UserPrincipal self) throws IOException {
    UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
    if (!owner.equals(self)) {
      throw new IOException(
          directory + " belongs to " + owner.getName() + ", not to " + self.getName());
    }
    if (POSIX) {
      Set<PosixFilePermission> permissions =
          Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
      if (permissions.contains(PosixFilePermission.GROUP_WRITE)
          || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
        throw new IOException(
            directory
                + " is writable by other users ("
                + PosixFilePermissions.toString(permissions)
                + ")");
      }
    }
  }

  /**
   * Tells whether {@code file} is a regular file owned by {@code self} that holds exactly the
   * library with the given digest and length.
   */
  private static boolean holds(Path file, UserPrincipal self, byte[] digest, int length)
      throws IOException {
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      return false;
    }
    return attributes.isRegularFile()
        && attributes.size() == length
        && Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(self)
        && MessageDigest.isEqual(sha256(Files.readAllBytes(file)), digest);
  }

  private static FileAttribute<?>[] ownerOnlyFile() {
    return POSIX
        ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
        : new FileAttribute<?>[0];
  }

  /**
   * Returns the SHA-256 of {@code bytes}.
   *
   * @param bytes the bytes to hash
   * @return the 32-byte digest
   */
  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("Every JDK implements SHA-256", e);
    }
  }
}
