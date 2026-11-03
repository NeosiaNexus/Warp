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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/**
 * Finds the libdeflate built for this machine in the jar, installs it and opens it.
 *
 * <p>The jar holds one library per platform under {@value #RESOURCES}{@code <platform>/} (see
 * {@link Platform#id()}), and {@value #PROPERTIES} with the bundled version. The library is
 * installed by {@link LibraryCache} into the first usable of:
 *
 * <ol>
 *   <li>the directory named by {@code -D}{@value Natives#DIRECTORY_PROPERTY}, alone when set;
 *   <li>the user's cache directory: {@code $XDG_CACHE_HOME/warp/natives} or {@code
 *       ~/.cache/warp/natives} on Linux, {@code ~/Library/Caches/warp/natives} on macOS, {@code
 *       %LOCALAPPDATA%\warp\natives} on Windows. Private to the user, kept across restarts, and
 *       unlike {@code /tmp} rarely mounted {@code noexec};
 *   <li>a new owner-only directory under {@code java.io.tmpdir}, deleted when the JVM exits;
 *   <li>on Linux, when no directory works (a read-only filesystem with {@code /tmp} mounted {@code
 *       noexec}, as in hardened containers), an anonymous memory file: nothing touches the disk.
 * </ol>
 *
 * <p>Every failure is collected into the final message. A directory set explicitly is the only
 * place tried: if it cannot be used, the operator hears why rather than finding the library
 * elsewhere.
 */
final class NativeLoader {

  /** Jar directory of the bundled libraries, one subdirectory per platform. */
  static final String RESOURCES = "/dev/warp/jni/native/";

  /** Jar resource that records the bundled libdeflate version, written by the build. */
  static final String PROPERTIES = RESOURCES + "natives.properties";

  private static final String INSTALL_ADVICE =
      "Point -D"
          + Natives.DIRECTORY_PROPERTY
          + " at a directory that Warp's user owns, on a filesystem that allows executing files";

  private static final String BUILD_ADVICE =
      "Use a release of Warp, or build it on this machine with a C compiler installed"
          + " (jni/native/build-libdeflate.sh)";

  /** {@code MFD_CLOEXEC}: the memory file is not inherited by child processes. */
  private static final int MFD_CLOEXEC = 1;

  /** {@code RTLD_NOW}, the same on glibc and musl: resolve every symbol when loading. */
  private static final int RTLD_NOW = 2;

  private NativeLoader() {}

  /**
   * A loaded library.
   *
   * @param symbols the library's symbols, valid until the JVM exits
   * @param version the libdeflate version, such as {@code 1.26}
   * @param platform the platform whose build was loaded
   * @param location where the library was loaded from: a file, or a memory file and why
   */
  record Loaded(SymbolLookup symbols, String version, Platform platform, String location) {}

  /**
   * Loads the libdeflate bundled for this machine.
   *
   * @return the loaded library
   * @throws NativeLoadException if no bundled library could be loaded
   */
  static Loaded load() throws NativeLoadException {
    Platform platform = Platform.current();
    String version = bundledVersion();
    // Only this platform's build: loading another C library's build is not a safe retry, it can
    // crash the JVM inside dlopen (netty/netty#17348, musl on aarch64).
    byte[] library = resource(platform);
    if (library == null) {
      throw new NativeLoadException(
          "this build of Warp bundles no libdeflate for " + platform.id(), BUILD_ADVICE);
    }
    return install(library, "libdeflate-" + version, platform, version);
  }

  /**
   * Installs and opens one library, trying each place in turn (see the class documentation).
   *
   * @param library the library's bytes
   * @param baseName the file name prefix, such as {@code libdeflate-1.26}
   * @param platform the platform the library is built for
   * @param version the library version
   * @return the loaded library
   * @throws NativeLoadException if no place worked
   */
  static Loaded install(byte[] library, String baseName, Platform platform, String version)
      throws NativeLoadException {
    String fileName = LibraryCache.fileName(baseName, platform, library);
    List<String> failures = new ArrayList<>();
    for (Path directory : directories(platform)) {
      try {
        Path file = LibraryCache.install(directory, fileName, library);
        return new Loaded(open(file), version, platform, file.toString());
      } catch (IOException | NativeLoadException e) {
        failures.add(messageOf(e));
      }
    }
    if (System.getProperty(Natives.DIRECTORY_PROPERTY) == null) {
      try {
        Path file = LibraryCache.install(privateTemporaryDirectory(), fileName, library);
        file.toFile().deleteOnExit();
        return new Loaded(open(file), version, platform, file.toString());
      } catch (IOException | NativeLoadException e) {
        failures.add(messageOf(e));
      }
    }
    if (System.getProperty(Natives.DIRECTORY_PROPERTY) == null
        && platform.os().equals(Platform.LINUX)) {
      try {
        return new Loaded(
            openInMemory(fileName, library),
            version,
            platform,
            "a memory file, as no directory could be used (" + String.join("; ", failures) + ")");
      } catch (NativeLoadException e) {
        failures.add(messageOf(e));
      }
    }
    throw new NativeLoadException(
        "cannot load the " + platform.id() + " library: " + String.join("; ", failures),
        INSTALL_ADVICE);
  }

  /**
   * Returns the directories to install into, in order: the configured one alone, or the user's
   * cache directory.
   *
   * @param platform the platform, which decides where its users keep caches
   * @return the candidate directories, possibly none
   */
  static List<Path> directories(Platform platform) {
    String configured = System.getProperty(Natives.DIRECTORY_PROPERTY);
    if (configured != null) {
      return List.of(Path.of(configured));
    }
    Path cache = userCacheDirectory(platform);
    return cache == null ? List.of() : List.of(cache.resolve("warp").resolve("natives"));
  }

  private static @Nullable Path userCacheDirectory(Platform platform) {
    try {
      String home = System.getProperty("user.home");
      Path homeDirectory =
          home == null || home.isBlank() || home.equals("?") ? null : Path.of(home);
      return switch (platform.os()) {
        case Platform.WINDOWS -> {
          String local = System.getenv("LOCALAPPDATA");
          yield local == null || local.isBlank() ? null : Path.of(local);
        }
        case Platform.MACOS ->
            homeDirectory == null ? null : homeDirectory.resolve("Library/Caches");
        default -> {
          String xdg = System.getenv("XDG_CACHE_HOME");
          if (xdg != null && Path.of(xdg).isAbsolute()) {
            yield Path.of(xdg);
          }
          yield homeDirectory == null ? null : homeDirectory.resolve(".cache");
        }
      };
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /** Creates an owner-only directory of a random name under {@code java.io.tmpdir}. */
  private static Path privateTemporaryDirectory() throws IOException {
    // On POSIX systems, createTempDirectory makes the directory rwx------.
    Path directory = Files.createTempDirectory("warp-natives-");
    // Registered first, deleted last: after the library inside it.
    directory.toFile().deleteOnExit();
    return directory;
  }

  private static String messageOf(Exception e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  // ---------------------------------------------------------------------------
  // Opening
  // ---------------------------------------------------------------------------

  /**
   * Opens a library file through the FFM API.
   *
   * @param file the library
   * @return its symbols, valid until the JVM exits
   * @throws NativeLoadException if the system loader refuses the file
   */
  @SuppressWarnings("restricted") // native access is enabled by the jar's manifest
  static SymbolLookup open(Path file) throws NativeLoadException {
    try {
      // The global arena never unloads the library: contexts may live until the JVM exits.
      return SymbolLookup.libraryLookup(file, Arena.global());
    } catch (IllegalArgumentException e) {
      // The system loader's message repeats the file name: first on glibc, inside on musl.
      String path = file.toAbsolutePath().toString();
      String reason = loaderMessage(file, e);
      reason =
          reason.startsWith(path + ": ")
              ? reason.substring(path.length() + 2)
              : reason.replace(" " + path, "");
      throw new NativeLoadException("cannot open " + file + ": " + reason, INSTALL_ADVICE, e);
    }
  }

  /**
   * Asks the JNI loader why a library cannot be opened: unlike {@link SymbolLookup}, it reports the
   * system loader's reason, such as "failed to map segment from shared object" on a {@code noexec}
   * mount, or a missing dependency.
   */
  @SuppressWarnings("restricted")
  private static String loaderMessage(Path file, IllegalArgumentException failure) {
    try {
      System.load(file.toAbsolutePath().toString());
      return String.valueOf(failure.getMessage());
    } catch (UnsatisfiedLinkError e) {
      return String.valueOf(e.getMessage());
    }
  }

  /**
   * Linux only: loads a library from an anonymous memory file ({@code memfd_create}), for machines
   * where no directory can hold an executable file. The file is opened with {@code dlopen} itself:
   * {@link SymbolLookup#libraryLookup} and {@link System#load} resolve its {@code /proc/self/fd}
   * link to a name that does not exist on disk. The memory file stays open, as the library stays
   * loaded, until the JVM exits.
   *
   * @param name the memory file's name, shown in {@code /proc/<pid>/maps}
   * @param library the library's bytes
   * @return the library's symbols, valid until the JVM exits
   * @throws NativeLoadException if the C library lacks {@code memfd_create} or refuses to load it
   */
  @SuppressWarnings("restricted") // native access is enabled by the jar's manifest
  static SymbolLookup openInMemory(String name, byte[] library) throws NativeLoadException {
    Linker linker = Linker.nativeLinker();
    MethodHandle memfdCreate =
        libc(linker, "memfd_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    MethodHandle dlopen = libc(linker, "dlopen", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    MethodHandle dlerror = libc(linker, "dlerror", FunctionDescriptor.of(ADDRESS));
    MethodHandle dlsym = libc(linker, "dlsym", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    MemorySegment handle;
    try (Arena arena = Arena.ofConfined()) {
      int fd = (int) memfdCreate.invokeExact(arena.allocateFrom(name), MFD_CLOEXEC);
      if (fd < 0) {
        throw new NativeLoadException("in memory: memfd_create failed", INSTALL_ADVICE);
      }
      Path file = Path.of("/proc/self/fd/" + fd);
      Files.write(file, library);
      handle = (MemorySegment) dlopen.invokeExact(arena.allocateFrom(file.toString()), RTLD_NOW);
      if (handle.equals(MemorySegment.NULL)) {
        MemorySegment error = (MemorySegment) dlerror.invokeExact();
        throw new NativeLoadException(
            "in memory: "
                + (error.equals(MemorySegment.NULL)
                    ? "dlopen failed"
                    : error.reinterpret(Long.MAX_VALUE).getString(0)),
            INSTALL_ADVICE);
      }
    } catch (NativeLoadException e) {
      throw e;
    } catch (Throwable e) {
      throw new NativeLoadException("in memory: " + e, INSTALL_ADVICE, e);
    }
    MemorySegment opened = handle;
    return symbol -> {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment address =
            (MemorySegment) dlsym.invokeExact(opened, arena.allocateFrom(symbol));
        return address.equals(MemorySegment.NULL) ? Optional.empty() : Optional.of(address);
      } catch (Throwable e) {
        throw new IllegalStateException("dlsym failed for " + symbol, e);
      }
    };
  }

  @SuppressWarnings("restricted")
  private static MethodHandle libc(Linker linker, String name, FunctionDescriptor descriptor)
      throws NativeLoadException {
    MemorySegment function =
        linker
            .defaultLookup()
            .find(name)
            .orElseThrow(
                () ->
                    new NativeLoadException(
                        "in memory: the C library has no " + name, INSTALL_ADVICE));
    return linker.downcallHandle(function, descriptor);
  }

  // ---------------------------------------------------------------------------
  // Jar resources
  // ---------------------------------------------------------------------------

  /**
   * Reads the library bundled for {@code platform}.
   *
   * @param platform the platform
   * @return its bytes, or {@code null} if this jar has none for it
   * @throws NativeLoadException if the resource exists but cannot be read
   */
  static byte @Nullable [] resource(Platform platform) throws NativeLoadException {
    String name = RESOURCES + platform.id() + "/" + platform.libraryFileName();
    try (InputStream in = NativeLoader.class.getResourceAsStream(name)) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      throw new NativeLoadException(
          "cannot read " + name + " from Warp's jar: " + e.getMessage(),
          "Download Warp's jar again",
          e);
    }
  }

  /** Reads the bundled libdeflate version, which the build records next to the libraries. */
  private static String bundledVersion() throws NativeLoadException {
    try (InputStream in = NativeLoader.class.getResourceAsStream(PROPERTIES)) {
      Properties properties = new Properties();
      if (in != null) {
        properties.load(in);
      }
      String version = properties.getProperty("libdeflate.version");
      if (version == null) {
        throw new NativeLoadException("this build of Warp bundles no native library", BUILD_ADVICE);
      }
      return version;
    } catch (IOException e) {
      throw new NativeLoadException(
          "cannot read " + PROPERTIES + " from Warp's jar: " + e.getMessage(),
          "Download Warp's jar again",
          e);
    }
  }
}
