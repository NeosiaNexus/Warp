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
package dev.warp.proxy.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Well-known proxy plugins and what becomes of them on Warp, from {@code plugins.properties} next
 * to this class. Velocity and BungeeCord plugins do not run on Warp; the catalog says which ones
 * Warp replaces itself, which have a Warp plugin, and which have none yet.
 */
final class PluginCatalog {

  /** What becomes of a plugin on Warp. */
  enum Status {
    BUILT_IN,
    REPLACED,
    UNAVAILABLE,
    UNKNOWN
  }

  /** A jar found in {@code plugins/} and its fate. */
  record Plugin(String file, Status status, String text) {}

  private record Entry(String match, Status status, String text) {}

  private static final String RESOURCE = "plugins.properties";

  private static final String UNKNOWN_TEXT =
      "unknown plugin: Velocity and BungeeCord plugins do not run on Warp; ask its author for a"
          + " Warp build";

  private final List<Entry> entries;

  private PluginCatalog(List<Entry> entries) {
    this.entries = entries;
  }

  /**
   * Loads the bundled catalog.
   *
   * @return the catalog
   */
  static PluginCatalog load() {
    try (InputStream in = PluginCatalog.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the jar");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
  }

  /**
   * Parses a catalog.
   *
   * @param text the catalog, one {@code match = STATUS: text} line per plugin
   * @return the catalog
   */
  static PluginCatalog parse(String text) {
    List<Entry> entries = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      String stripped = line.strip();
      if (stripped.isEmpty() || stripped.startsWith("#")) {
        continue;
      }
      int equals = stripped.indexOf('=');
      int colon = stripped.indexOf(':', equals);
      if (equals < 0 || colon < 0) {
        throw new IllegalStateException(RESOURCE + ": not 'match = STATUS: text': " + line);
      }
      String match = stripped.substring(0, equals).strip().toLowerCase(Locale.ROOT);
      Status status =
          Status.valueOf(stripped.substring(equals + 1, colon).strip().toUpperCase(Locale.ROOT));
      entries.add(new Entry(match, status, stripped.substring(colon + 1).strip()));
    }
    return new PluginCatalog(List.copyOf(entries));
  }

  /**
   * Lists the jars of a plugins directory with their fate, by file name.
   *
   * @param plugins the directory; a missing one lists nothing
   * @return the plugins, by file name
   * @throws IOException if the directory cannot be listed
   */
  List<Plugin> scan(Path plugins) throws IOException {
    if (!Files.isDirectory(plugins)) {
      return List.of();
    }
    List<Plugin> found = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(plugins, "*.jar")) {
      for (Path jar : stream) {
        found.add(lookup(jar.getFileName().toString()));
      }
    }
    found.sort(Comparator.comparing(Plugin::file, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(found);
  }

  /**
   * Returns the fate of one jar.
   *
   * @param file the jar's file name
   * @return the plugin
   */
  Plugin lookup(String file) {
    String name = file.toLowerCase(Locale.ROOT);
    if (name.endsWith(".jar")) {
      name = name.substring(0, name.length() - 4);
    }
    for (Entry entry : entries) {
      if (name.contains(entry.match())) {
        return new Plugin(file, entry.status(), entry.text());
      }
    }
    return new Plugin(file, Status.UNKNOWN, UNKNOWN_TEXT);
  }
}
