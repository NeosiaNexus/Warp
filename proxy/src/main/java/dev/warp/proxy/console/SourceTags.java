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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short names for the sources of log lines, in place of 36-character logger names: {@code antibot}
 * for {@code dev.warp.proxy.antibot.AttackDetector}, {@code plugin:primemc} for the logger of the
 * plugin {@code primemc} and for its classes, {@code netty} for Netty.
 */
public final class SourceTags {

  /** The width tags are padded to; longer ones push the message right rather than being cut. */
  static final int WIDTH = 10;

  /** Beyond this many logger names (a library naming loggers dynamically), tags are not cached. */
  private static final int CACHE_LIMIT = 4096;

  private static final String PROXY = "dev.warp.proxy.";
  private static final String WARP = "dev.warp.";

  /** Package aliases inside the proxy, for names longer than {@link #WIDTH}. */
  private static final Map<String, String> PROXY_ALIASES =
      Map.of("compression", "compress", "connection", "conn", "management", "manage");

  /** Well-known libraries, by package prefix. */
  private static final Map<String, String> LIBRARIES =
      Map.of(
          "io.netty.", "netty",
          "com.viaversion.", "via",
          "com.google.inject.", "guice",
          "org.spongepowered.configurate.", "config",
          "org.apache.logging.", "log4j",
          "org.jline.", "jline");

  private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

  /** Package prefixes of plugin classes (with a trailing dot), mapped to their plugin's tag. */
  private static final Map<String, String> PLUGIN_PACKAGES = new ConcurrentHashMap<>();

  private SourceTags() {}

  /**
   * Returns the tag of a logger.
   *
   * @param loggerName the logger's name
   * @return its tag
   */
  public static String of(String loggerName) {
    String cached = CACHE.get(loggerName);
    if (cached != null) {
      return cached;
    }
    String tag = compute(loggerName);
    if (CACHE.size() < CACHE_LIMIT) {
      CACHE.put(loggerName, tag);
    }
    return tag;
  }

  /**
   * Tags the loggers of a plugin's classes, named after them, as the plugin: {@code plugin:<id>}.
   * The plugin's own logger, named after its id, already is.
   *
   * @param packageName the package of the plugin's main class
   * @param pluginId the plugin's id
   */
  public static void registerPlugin(String packageName, String pluginId) {
    if (packageName.isEmpty() || packageName.startsWith(WARP)) {
      return;
    }
    PLUGIN_PACKAGES.put(packageName + ".", "plugin:" + pluginId);
    CACHE.clear();
  }

  /**
   * Returns whether a class belongs to a plugin, by the package of its main class.
   *
   * @param className the class's name
   * @return whether it is in a plugin's package
   */
  static boolean isPluginClass(String className) {
    for (String prefix : PLUGIN_PACKAGES.keySet()) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the loggers a tag stands for, to change their level: the package of a tag of the proxy
   * ({@code conn} is {@code dev.warp.proxy.connection}), a library's package, a plugin's logger and
   * its classes' package, the classes of the proxy's own package for {@code warp}; a name with a
   * dot is a logger name itself.
   *
   * @param target a tag or a logger name
   * @param existing the names of the loggers that exist, for the tags that cover several
   * @return the logger names, empty when the tag stands for nothing known
   */
  public static List<String> loggersOf(String target, Collection<String> existing) {
    String tag = target.strip();
    List<String> names = new ArrayList<>();
    if (tag.startsWith("plugin:")) {
      String id = tag.substring("plugin:".length());
      if (!id.isEmpty()) {
        names.add(id);
        for (Map.Entry<String, String> plugin : PLUGIN_PACKAGES.entrySet()) {
          if (plugin.getValue().equals(tag)) {
            String prefix = plugin.getKey();
            names.add(prefix.substring(0, prefix.length() - 1));
          }
        }
      }
      return names;
    }
    for (Map.Entry<String, String> library : LIBRARIES.entrySet()) {
      if (library.getValue().equals(tag)) {
        String prefix = library.getKey();
        names.add(prefix.substring(0, prefix.length() - 1));
      }
    }
    switch (tag) {
      case "stdout", "stderr" -> names.add(tag.toUpperCase(java.util.Locale.ROOT));
      case "protocol", "api" -> names.add(WARP + tag);
      case "natives" -> names.add(WARP + "jni");
      case "warp" -> {
        for (String name : existing) {
          if (name.startsWith(PROXY) && name.indexOf('.', PROXY.length()) < 0) {
            names.add(name);
          }
        }
      }
      default -> {
        if (!names.isEmpty()) {
          return names;
        }
        if (tag.indexOf('.') >= 0) {
          names.add(tag);
          return names;
        }
        String pkg = tag;
        for (Map.Entry<String, String> alias : PROXY_ALIASES.entrySet()) {
          if (alias.getValue().equals(tag)) {
            pkg = alias.getKey();
          }
        }
        String prefix = PROXY + pkg + ".";
        if (existing.stream().anyMatch(name -> name.startsWith(prefix))) {
          names.add(PROXY + pkg);
        }
      }
    }
    return names;
  }

  /**
   * Returns the tags of the loggers that exist, with those of the known libraries and plugins: the
   * suggestions of {@code /log}.
   *
   * @param existing the names of the loggers that exist
   * @return the tags, sorted
   */
  public static SortedSet<String> known(Collection<String> existing) {
    SortedSet<String> tags = new TreeSet<>();
    for (String name : existing) {
      tags.add(of(name));
    }
    tags.addAll(LIBRARIES.values());
    tags.addAll(PLUGIN_PACKAGES.values());
    tags.remove("root");
    return tags;
  }

  private static String compute(String name) {
    if (name.isEmpty()) {
      return "root";
    }
    if (name.equals("STDOUT") || name.equals("STDERR")) {
      return name.toLowerCase(java.util.Locale.ROOT);
    }
    if (name.startsWith(PROXY)) {
      String rest = name.substring(PROXY.length());
      int dot = rest.indexOf('.');
      if (dot < 0) {
        return "warp";
      }
      String pkg = rest.substring(0, dot);
      return PROXY_ALIASES.getOrDefault(pkg, pkg);
    }
    if (name.startsWith(WARP)) {
      String rest = name.substring(WARP.length());
      int dot = rest.indexOf('.');
      String module = dot < 0 ? rest : rest.substring(0, dot);
      return module.equals("jni") ? "natives" : module;
    }
    if (name.indexOf('.') < 0) {
      // A plugin's own logger is named after its id.
      return "plugin:" + name;
    }
    for (Map.Entry<String, String> plugin : PLUGIN_PACKAGES.entrySet()) {
      if (name.startsWith(plugin.getKey())) {
        return plugin.getValue();
      }
    }
    for (Map.Entry<String, String> library : LIBRARIES.entrySet()) {
      if (name.startsWith(library.getKey())) {
        return library.getValue();
      }
    }
    // org.example.thing.Class: the organisation's second segment names the project best.
    String[] parts = name.split("\\.", -1);
    return parts.length >= 3 ? parts[1] : parts[parts.length - 1];
  }
}
