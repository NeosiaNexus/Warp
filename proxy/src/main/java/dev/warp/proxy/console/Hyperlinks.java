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
package dev.warp.proxy.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Clickable links in the console: OSC 8 sequences around a text, for the URLs and file paths of log
 * lines and for the documentation of error codes. Only on terminals known to show them ({@link
 * ConsoleMode}); never in files, pipes or panels, which get the text alone.
 *
 * <p>File paths become {@code file://} links with this machine's host name, as the OSC 8
 * specification asks, so that a terminal on another machine (over SSH) does not open a file of its
 * own by the same name. Relative paths resolve against the proxy's working directory.
 */
final class Hyperlinks {

  /** Ends a link. */
  static final String CLOSE = "\u001B]8;;\u001B\\";

  /** A URL, or a file path with one of the extensions a proxy's files have. */
  private static final Pattern TARGET =
      Pattern.compile(
          "(?<url>https?://[^\\s<>\"'`{}|\\\\^]+)"
              + "|(?<![\\w/\\\\.:-])(?<path>(?:[A-Za-z]:[\\\\/]|/|\\.{1,2}/)?"
              + "(?:[\\w.-]+[\\\\/])*[\\w-][\\w.-]*"
              + "\\.(?:conf|jar|log|gz|json|ya?ml|toml|properties|txt|zip|db|journal|snapshot))"
              + "(?![\\w/\\\\.-])");

  /** Characters that end a sentence rather than a URL. */
  private static final String TRAILING = ".,;:!?'\"";

  private static final Path WORKING_DIRECTORY = Path.of("").toAbsolutePath();

  private static final String HOST = host();

  private Hyperlinks() {}

  /** Receives a piece of text and, when it is a link, where it goes. */
  @FunctionalInterface
  interface Pieces {
    /**
     * Receives a piece.
     *
     * @param text the text
     * @param uri where it links to, or {@code null} for plain text
     */
    void accept(String text, @Nullable String uri);
  }

  /**
   * Returns the sequence that starts a link.
   *
   * @param uri where the link goes, without control characters
   * @return the sequence
   */
  static String open(String uri) {
    return "\u001B]8;;" + uri + "\u001B\\";
  }

  /**
   * Splits a text into plain pieces and links.
   *
   * @param text the text
   * @param pieces receives each piece, in order
   */
  static void split(String text, Pieces pieces) {
    if (text.indexOf('/') < 0 && text.indexOf('\\') < 0 && text.indexOf('.') < 0) {
      pieces.accept(text, null);
      return;
    }
    Matcher matcher = TARGET.matcher(text);
    int position = 0;
    while (matcher.find()) {
      String found = matcher.group();
      int end = matcher.end();
      String uri;
      if (matcher.group("url") != null) {
        String url = trimUrl(found);
        end = matcher.start() + url.length();
        found = url;
        uri = url.length() > "https://".length() ? url : null;
      } else {
        uri = fileUri(found);
      }
      if (uri == null) {
        continue;
      }
      if (matcher.start() > position) {
        pieces.accept(text.substring(position, matcher.start()), null);
      }
      pieces.accept(found, uri);
      position = end;
    }
    if (position < text.length()) {
      pieces.accept(text.substring(position), null);
    }
  }

  /**
   * Removes what ends a sentence rather than the URL: a final full stop, comma or quote, and a
   * closing bracket without its opening one.
   */
  static String trimUrl(String url) {
    int end = url.length();
    while (end > 0) {
      char last = url.charAt(end - 1);
      if (TRAILING.indexOf(last) >= 0) {
        end--;
      } else if ((last == ')' || last == ']')
          && count(url, end, last == ')' ? '(' : '[') < count(url, end, last)) {
        end--;
      } else {
        break;
      }
    }
    return url.substring(0, end);
  }

  private static int count(String text, int end, char c) {
    int count = 0;
    for (int i = 0; i < end; i++) {
      if (text.charAt(i) == c) {
        count++;
      }
    }
    return count;
  }

  /**
   * Returns the {@code file://} URI of a path, resolved against the working directory.
   *
   * @param path the path, as written
   * @return the URI, or {@code null} when it is not a valid path
   */
  static @Nullable String fileUri(String path) {
    try {
      Path resolved = WORKING_DIRECTORY.resolve(path).normalize();
      return "file://" + HOST + resolved.toUri().getRawPath();
    } catch (InvalidPathException | java.io.IOError | SecurityException e) {
      return null;
    }
  }

  /**
   * This machine's host name, as the terminal compares it: without a DNS lookup, which can take
   * seconds on a machine without a name server. Empty when unknown, which terminals take as "this
   * machine".
   */
  private static String host() {
    String name = System.getenv("HOSTNAME");
    if (name == null || name.isBlank()) {
      name = System.getenv("COMPUTERNAME");
    }
    if ((name == null || name.isBlank())
        && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
      try {
        name = Files.readString(Path.of("/proc/sys/kernel/hostname"), StandardCharsets.UTF_8);
      } catch (IOException | RuntimeException e) {
        name = null;
      }
    }
    if (name == null) {
      return "";
    }
    String host = name.strip();
    return host.matches("[A-Za-z0-9.-]+") ? host : "";
  }
}
