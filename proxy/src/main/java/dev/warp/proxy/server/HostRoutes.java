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
package dev.warp.proxy.server;

import dev.warp.api.server.Route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The {@code routes} of {@code warp.conf}, compiled: which server or group a client goes to for the
 * host name it connected with.
 *
 * <p>An entry's key is a host, in any case, with or without a trailing dot, optionally followed by
 * {@code :port} to match only clients that connected to that port. Its first label may be {@code
 * *}, which stands for one or more labels: {@code *.example.com} matches {@code a.example.com} and
 * {@code a.b.example.com}, and {@code *} alone matches every host. The labels the star stood for
 * are the capture, which a target of {@code $1} is replaced with.
 *
 * <p>Matching is deterministic: an exact host wins over a wildcard, a wildcard with more literal
 * labels over one with fewer, an entry with a port over the same entry without, and two entries
 * that still tie are taken in the order of their keys. Entries are matched against the {@link
 * #clean cleaned} host, so the Forge and BungeeCord suffixes, the case, a trailing dot or a port
 * the client typed never make an entry miss.
 */
public final class HostRoutes {

  /** No route: every client goes to the default. */
  public static final HostRoutes EMPTY = new HostRoutes(List.of());

  /** The capture placeholder in a target. */
  public static final String CAPTURE = "$1";

  private static final int NO_PORT = -1;

  private final List<Entry> entries;

  private HostRoutes(List<Entry> entries) {
    this.entries = entries;
  }

  /**
   * Compiles routes.
   *
   * @param routes keys to targets, each key as {@link #parseKey} takes it, each target a server or
   *     group name, or one containing {@link #CAPTURE} for a wildcard key
   * @return the routes
   * @throws IllegalArgumentException if a key is invalid, if two keys are the same host once
   *     normalized, or if a target captures without a wildcard key
   */
  public static HostRoutes compile(Map<String, String> routes) {
    List<Entry> entries = new ArrayList<>(routes.size());
    for (var route : routes.entrySet()) {
      Key key = parseKey(route.getKey());
      String target = route.getValue().trim().toLowerCase(Locale.ROOT);
      if (target.isEmpty()) {
        throw new IllegalArgumentException("the target of '" + route.getKey() + "' is empty");
      }
      if (target.contains(CAPTURE) && !key.wildcard()) {
        throw new IllegalArgumentException(
            "the target '"
                + target
                + "' uses "
                + CAPTURE
                + ", but '"
                + route.getKey()
                + "' has no * to capture");
      }
      for (Entry other : entries) {
        if (other.key.equals(key)) {
          throw new IllegalArgumentException(
              "'" + route.getKey() + "' and '" + other.written + "' are the same host");
        }
      }
      entries.add(new Entry(key, route.getKey(), target));
    }
    entries.sort(
        Comparator.comparing((Entry e) -> e.key.wildcard())
            .thenComparing(e -> -e.key.literalLabels())
            .thenComparing(e -> e.key.port() == NO_PORT)
            .thenComparing(e -> e.key.pattern()));
    return new HostRoutes(List.copyOf(entries));
  }

  /**
   * Parses and normalizes a route key.
   *
   * @param written the key as written in the configuration
   * @return the key
   * @throws IllegalArgumentException if it is empty, has a bad port, a star elsewhere than as its
   *     first label, or an empty label
   */
  public static Key parseKey(String written) {
    String host = written.trim().toLowerCase(Locale.ROOT);
    int port = NO_PORT;
    int colon = host.lastIndexOf(':');
    if (colon >= 0 && host.indexOf(':') == colon) {
      String portText = host.substring(colon + 1);
      host = host.substring(0, colon);
      try {
        port = Integer.parseInt(portText);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "'" + written + "' ends with ':" + portText + "', which is not a port", e);
      }
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException(
            "'" + written + "' has port " + port + ", outside 1 to 65535");
      }
    }
    while (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (host.isEmpty()) {
      throw new IllegalArgumentException("'" + written + "' names no host");
    }
    boolean wildcard = false;
    String suffix = host;
    if (host.equals("*")) {
      wildcard = true;
      suffix = "";
    } else if (host.startsWith("*.")) {
      wildcard = true;
      suffix = host.substring(2);
    }
    if (suffix.contains("*")) {
      throw new IllegalArgumentException(
          "'" + written + "' has a * elsewhere than as its first label, such as *.example.com");
    }
    for (String label : suffix.split("\\.", -1)) {
      if (label.isEmpty() && !suffix.isEmpty()) {
        throw new IllegalArgumentException("'" + written + "' has an empty label");
      }
      if (label.chars().anyMatch(Character::isWhitespace)) {
        throw new IllegalArgumentException("'" + written + "' contains whitespace");
      }
    }
    return new Key(host, suffix, wildcard, port);
  }

  /**
   * Cleans the address field of a handshake into the host the routes are matched against: cut at
   * the first NUL (Forge's {@code \0FML\0}, {@code \0FML2\0} and {@code \0FML3\0} markers, a
   * BungeeCord forwarding payload), without trailing dots or a {@code :port}, in lowercase.
   *
   * @param serverAddress the address field, as sent
   * @return the host, possibly empty
   */
  public static String clean(String serverAddress) {
    String host = serverAddress;
    int nul = host.indexOf('\0');
    if (nul >= 0) {
      host = host.substring(0, nul);
    }
    host = host.trim();
    int colon = host.lastIndexOf(':');
    if (colon >= 0 && host.indexOf(':') == colon && isDigits(host, colon + 1)) {
      host = host.substring(0, colon);
    }
    while (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    return host.toLowerCase(Locale.ROOT);
  }

  private static boolean isDigits(String text, int from) {
    if (from >= text.length()) {
      return false;
    }
    for (int i = from; i < text.length(); i++) {
      if (text.charAt(i) < '0' || text.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Finds the route for a connection.
   *
   * @param host the cleaned host, see {@link #clean}
   * @param port the port the client connected to, from its handshake
   * @return the most specific matching route, with its capture substituted, or {@code null} if no
   *     entry matches
   */
  public @Nullable Route match(String host, int port) {
    if (host.isEmpty()) {
      return null;
    }
    for (Entry entry : entries) {
      Key key = entry.key;
      if (key.port() != NO_PORT && key.port() != port) {
        continue;
      }
      String capture;
      if (!key.wildcard()) {
        if (!host.equals(key.pattern())) {
          continue;
        }
        capture = "";
      } else if (key.suffix().isEmpty()) {
        capture = host;
      } else if (host.length() > key.suffix().length() + 1
          && host.endsWith(key.suffix())
          && host.charAt(host.length() - key.suffix().length() - 1) == '.') {
        capture = host.substring(0, host.length() - key.suffix().length() - 1);
      } else {
        continue;
      }
      String target = entry.target.replace(CAPTURE, capture);
      return new Route(key.pattern() + (key.port() == NO_PORT ? "" : ":" + key.port()), target);
    }
    return null;
  }

  /**
   * Returns the routes, most specific first, as pairs of key and target.
   *
   * @return the routes, unmodifiable
   */
  public List<Map.Entry<String, String>> entries() {
    return entries.stream()
        .map(
            e ->
                Map.entry(
                    e.key.pattern() + (e.key.port() == NO_PORT ? "" : ":" + e.key.port()),
                    e.target))
        .toList();
  }

  /**
   * Returns whether there is no route.
   *
   * @return {@code true} if every client goes to the default
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * A normalized route key.
   *
   * @param pattern the host in lowercase, with its leading {@code *.} if any, without port
   * @param suffix the literal labels after the star (the whole pattern when not a wildcard)
   * @param wildcard whether the first label is a star
   * @param port the port to match, or {@code -1} for any
   */
  public record Key(String pattern, String suffix, boolean wildcard, int port) {

    /**
     * Returns how many literal labels the key has: its specificity among wildcards.
     *
     * @return the label count
     */
    int literalLabels() {
      return suffix.isEmpty() ? 0 : suffix.split("\\.").length;
    }
  }

  private record Entry(Key key, String written, String target) {}
}
