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

import dev.warp.proxy.logging.LogCode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when Warp cannot start for a reason the operator can fix: its message says what happened
 * and what to do, in one or two sentences, and is logged without a stack trace; the process then
 * exits with {@link #exitCode()}, which tells a supervisor not to restart it in a loop.
 *
 * <p>The exit codes follow {@code sysexits.h}:
 *
 * <ul>
 *   <li>{@value #CONFIGURATION}: the configuration is invalid, or Java is too old;
 *   <li>{@value #UNAVAILABLE}: an address Warp must listen on is taken or not available;
 *   <li>{@value #IO_ERROR}: a file or directory Warp needs cannot be read.
 * </ul>
 */
public final class StartupException extends RuntimeException {

  /** The configuration is invalid ({@code EX_CONFIG}). */
  public static final int CONFIGURATION = 78;

  /** An address to listen on is taken or not available ({@code EX_UNAVAILABLE}). */
  public static final int UNAVAILABLE = 69;

  /** A file or directory cannot be read ({@code EX_IOERR}). */
  public static final int IO_ERROR = 74;

  private final int exitCode;
  private final LogCode code;

  /**
   * Creates the exception.
   *
   * @param exitCode the process's exit code
   * @param code the code of the error, which links to its documentation
   * @param message what happened and what to do, for the operator
   * @param cause the underlying error, or {@code null}
   */
  public StartupException(int exitCode, LogCode code, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.exitCode = exitCode;
    this.code = code;
  }

  /**
   * Returns the exit code the process ends with.
   *
   * @return the exit code
   */
  public int exitCode() {
    return exitCode;
  }

  /**
   * Returns the code of the error, which the log shows with a link to its documentation.
   *
   * @return the code
   */
  public LogCode code() {
    return code;
  }

  // ---------------------------------------------------------------------------
  // Checks run before anything starts
  // ---------------------------------------------------------------------------

  /**
   * Checks that Warp can listen on an address, before plugins load: a port another program holds is
   * reported at once, with the fix, rather than after everything started.
   *
   * @param address the address
   * @param key the setting it comes from, such as {@code bind}
   * @throws StartupException if it cannot be bound
   */
  static void checkBindable(InetSocketAddress address, String key) {
    if (address.isUnresolved()) {
      throw new StartupException(
          CONFIGURATION,
          LogCode.LISTEN_ADDRESS_UNRESOLVED,
          "Cannot listen on "
              + describe(address)
              + ": the host name does not resolve. Fix "
              + key
              + " in warp.conf, or use 0.0.0.0 to listen on every address",
          null);
    }
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(true);
      socket.bind(address);
    } catch (IOException e) {
      throw cannotBind(address, key, e);
    }
  }

  /**
   * Describes why Warp cannot listen on an address, with the fix.
   *
   * @param address the address
   * @param key the setting it comes from, such as {@code bind}
   * @param cause why binding failed
   * @return the exception
   */
  public static StartupException cannotBind(
      InetSocketAddress address, String key, Throwable cause) {
    String why = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
    String where = describe(address);
    String message;
    LogCode code;
    if (why.contains("in use")) {
      code = LogCode.PORT_IN_USE;
      message =
          "Cannot listen on "
              + where
              + ": port "
              + address.getPort()
              + " is already in use, by another Warp or another server. Stop that program, or set"
              + " another port in "
              + key
              + " in warp.conf";
    } else if (why.contains("permission denied") || why.contains("access")) {
      code = LogCode.PORT_PERMISSION_DENIED;
      message =
          "Cannot listen on "
              + where
              + ": permission denied. Ports below 1024 need extra rights: use a port from 1024 up"
              + " in "
              + key
              + " in warp.conf (25565 is Minecraft's), and forward the low port to it if needed";
    } else if (why.contains("assign") || why.contains("not available")) {
      code = LogCode.ADDRESS_NOT_LOCAL;
      message =
          "Cannot listen on "
              + where
              + ": "
              + address.getAddress().getHostAddress()
              + " is not an address of this machine. Use one of its addresses in "
              + key
              + " in warp.conf, or 0.0.0.0 to listen on all of them";
    } else {
      code = LogCode.CANNOT_LISTEN;
      message =
          "Cannot listen on "
              + where
              + " ("
              + cause.getMessage()
              + "). Check "
              + key
              + " in warp.conf";
    }
    return new StartupException(UNAVAILABLE, code, message, cause);
  }

  /**
   * Checks that a directory Warp reads, such as {@code plugins}, can be read, creating it if it
   * does not exist.
   *
   * @param directory the directory
   * @param what what it holds, for the message, such as {@code plugins}
   * @throws StartupException if it cannot be created or read
   */
  static void checkReadableDirectory(Path directory, String what) {
    Path absolute = directory.toAbsolutePath().normalize();
    if (Files.exists(absolute) && !Files.isDirectory(absolute)) {
      throw new StartupException(
          IO_ERROR,
          LogCode.PLUGINS_DIRECTORY_UNREADABLE,
          "Cannot read the "
              + what
              + " directory "
              + absolute
              + ": it is a file. Move it away, and Warp creates the directory",
          null);
    }
    try {
      Files.createDirectories(absolute);
      try (var listed = Files.list(absolute)) {
        var _ = listed.findAny();
      }
    } catch (AccessDeniedException e) {
      throw new StartupException(
          IO_ERROR,
          LogCode.PLUGINS_DIRECTORY_UNREADABLE,
          "Cannot read the "
              + what
              + " directory "
              + absolute
              + ": permission denied. Give the user running Warp ("
              + System.getProperty("user.name")
              + ") read and write access to it",
          e);
    } catch (IOException e) {
      throw new StartupException(
          IO_ERROR,
          LogCode.PLUGINS_DIRECTORY_UNREADABLE,
          "Cannot read the " + what + " directory " + absolute + " (" + e + ")",
          e);
    }
  }

  private static String describe(InetSocketAddress address) {
    return address.getHostString() + ":" + address.getPort();
  }
}
