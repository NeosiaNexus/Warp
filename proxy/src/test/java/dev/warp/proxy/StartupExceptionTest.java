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
package dev.warp.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.warp.proxy.config.ConfigurationException;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Startup failures")
class StartupExceptionTest {

  @Nested
  @DisplayName("listening address")
  class Bind {

    @Test
    @DisplayName("should say a port is taken, by what, and how to fix it")
    void portInUse() throws Exception {
      try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        InetSocketAddress address =
            new InetSocketAddress(InetAddress.getLoopbackAddress(), taken.getLocalPort());

        StartupException error =
            assertThrows(
                StartupException.class, () -> StartupException.checkBindable(address, "bind"));

        assertEquals(StartupException.UNAVAILABLE, error.exitCode());
        assertTrue(
            String.valueOf(error.getMessage()).contains("is already in use"), error.getMessage());
        assertTrue(
            String.valueOf(error.getMessage()).contains("in bind in warp.conf"),
            error.getMessage());
      }
    }

    @Test
    @DisplayName("should explain a port below 1024 refused for lack of rights")
    void permissionDenied() {
      StartupException error =
          StartupException.cannotBind(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 80),
              "bind",
              new BindException("Permission denied"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("Ports below 1024"), error.getMessage());
    }

    @Test
    @DisplayName("should explain an address that is not this machine's")
    void notLocal() {
      StartupException error =
          StartupException.cannotBind(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 25577),
              "management.bind",
              new BindException("Cannot assign requested address"));

      assertTrue(String.valueOf(error.getMessage()).contains("0.0.0.0"), error.getMessage());
      assertTrue(
          String.valueOf(error.getMessage()).contains("management.bind"), error.getMessage());
    }

    @Test
    @DisplayName("should accept a free port")
    void free() {
      StartupException.checkBindable(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), "bind");
    }
  }

  @Nested
  @DisplayName("plugins directory")
  class PluginsDirectory {

    @TempDir private Path directory;

    @Test
    @DisplayName("should create a missing plugins directory")
    void created() {
      StartupException.checkReadableDirectory(directory.resolve("plugins"), "plugins");

      assertTrue(Files.isDirectory(directory.resolve("plugins")));
    }

    @Test
    @DisplayName("should refuse a plugins file where the directory belongs")
    void file() throws Exception {
      Files.writeString(directory.resolve("plugins"), "");

      StartupException error =
          assertThrows(
              StartupException.class,
              () ->
                  StartupException.checkReadableDirectory(directory.resolve("plugins"), "plugins"));

      assertEquals(StartupException.IO_ERROR, error.exitCode());
      assertTrue(String.valueOf(error.getMessage()).contains("it is a file"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse a plugins directory it cannot read, naming the user to give access")
    void unreadable() throws Exception {
      assumeTrue(!"root".equals(System.getProperty("user.name")), "root reads everything");
      Path plugins = Files.createDirectory(directory.resolve("plugins"));
      assumeTrue(Files.getFileStore(plugins).supportsFileAttributeView("posix"));
      Files.setPosixFilePermissions(plugins, PosixFilePermissions.fromString("---------"));
      try {
        StartupException error =
            assertThrows(
                StartupException.class,
                () -> StartupException.checkReadableDirectory(plugins, "plugins"));

        assertEquals(StartupException.IO_ERROR, error.exitCode());
        assertTrue(
            String.valueOf(error.getMessage()).contains("permission denied"), error.getMessage());
      } finally {
        Files.setPosixFilePermissions(plugins, PosixFilePermissions.fromString("rwx------"));
      }
    }
  }

  @Nested
  @DisplayName("invalid configuration")
  class Configuration {

    @Test
    @DisplayName("should list every problem on its own line, then say what to do")
    void described() {
      ConfigurationException error =
          ConfigurationException.of(
              List.of(
                  new ConfigurationException.Problem(
                      Path.of("warp.conf"), 3, "onlin-mode", "onlin-mode is not a setting"),
                  new ConfigurationException.Problem(
                      Path.of("warp.conf"), 7, "bind", "bind: expected host:port")));

      String text = WarpBootstrap.describe(error);

      assertTrue(text.startsWith("Warp cannot start: its configuration has 2 problems."), text);
      assertTrue(text.contains("  - warp.conf, line 3: onlin-mode is not a setting"), text);
      assertTrue(text.contains("  - warp.conf, line 7: bind: expected host:port"), text);
    }
  }
}
