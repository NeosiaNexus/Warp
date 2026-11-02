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
package dev.warp.proxy.plugin;

import dev.warp.api.plugin.PluginDependency;
import dev.warp.api.plugin.PluginDescription;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the plugin jars of a directory and reads their descriptors.
 *
 * <p>A jar that cannot be loaded never stops the others: it is reported with what is wrong and how
 * to fix it, and skipped. Two jars declaring the same identifier are both skipped, rather than
 * loading whichever happens to be listed first.
 */
final class PluginDiscovery {

  private static final Logger logger = LoggerFactory.getLogger(PluginDiscovery.class);

  /** The descriptor the API's annotation processor writes at the root of a plugin jar. */
  static final String DESCRIPTOR = "warp-plugin.json";

  private PluginDiscovery() {}

  /**
   * Reads every plugin jar of a directory, creating the directory if it does not exist.
   *
   * @param directory the plugins directory
   * @return the valid plugins, sorted by jar name
   */
  static List<PluginCandidate> discover(Path directory) {
    List<Path> jars;
    try {
      Files.createDirectories(directory);
      try (Stream<Path> files = Files.list(directory)) {
        jars =
            files
                .filter(Files::isRegularFile)
                .filter(
                    file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted()
                .toList();
      }
    } catch (IOException e) {
      logger.error("Cannot list the plugins directory {}: {}", directory, e.getMessage());
      return List.of();
    }

    Map<String, List<PluginCandidate>> byId = new LinkedHashMap<>();
    for (Path jar : jars) {
      PluginCandidate candidate = read(jar);
      if (candidate != null) {
        byId.computeIfAbsent(candidate.id(), _ -> new ArrayList<>()).add(candidate);
      }
    }

    List<PluginCandidate> candidates = new ArrayList<>(byId.size());
    for (var entry : byId.entrySet()) {
      if (entry.getValue().size() > 1) {
        logger.error(
            "Plugin id '{}' is declared by several jars, none of them is loaded: {}. Keep only"
                + " one.",
            entry.getKey(),
            entry.getValue().stream().map(c -> c.jar().getFileName().toString()).toList());
        continue;
      }
      candidates.add(entry.getValue().getFirst());
    }
    return List.copyOf(candidates);
  }

  /**
   * Reads one jar's descriptor.
   *
   * @param jar the jar
   * @return the candidate, or {@code null} if the jar is not a valid Warp plugin (reported)
   */
  static @Nullable PluginCandidate read(Path jar) {
    String name = jar.getFileName().toString();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry descriptor = zip.getEntry(DESCRIPTOR);
      if (descriptor == null) {
        logger.error("{} is not a Warp plugin: it has no {}{}", name, DESCRIPTOR, hint(zip));
        return null;
      }
      try (InputStream in = zip.getInputStream(descriptor);
          Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        return new PluginCandidate(jar, parse(JsonParser.parseReader(reader)));
      }
    } catch (IOException e) {
      logger.error("Cannot read plugin jar {}: {}", name, e.getMessage());
    } catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
      logger.error("Invalid {} in {}: {}", DESCRIPTOR, name, e.getMessage());
    }
    return null;
  }

  /** Recognises plugins of other platforms, to tell the user what they dropped in. */
  private static String hint(ZipFile zip) {
    if (zip.getEntry("velocity-plugin.json") != null) {
      return ". It is a Velocity plugin; Warp plugins are built against the Warp API.";
    }
    if (zip.getEntry("bungee.yml") != null || zip.getEntry("plugin.yml") != null) {
      return ". It is a Bukkit or BungeeCord plugin; Warp plugins are built against the Warp API.";
    }
    return ". Was it built with the Warp API on its annotation processor path?";
  }

  /**
   * Parses a descriptor.
   *
   * @param json the descriptor's content
   * @return the description
   * @throws IllegalArgumentException if a required field is missing or a value is invalid
   */
  static PluginDescription parse(JsonElement json) {
    if (!json.isJsonObject()) {
      throw new IllegalArgumentException("the descriptor is not a JSON object");
    }
    JsonObject root = json.getAsJsonObject();
    List<String> authors = new ArrayList<>();
    for (JsonElement author : array(root, "authors")) {
      authors.add(author.getAsString());
    }
    List<PluginDependency> dependencies = new ArrayList<>();
    for (JsonElement element : array(root, "dependencies")) {
      if (!element.isJsonObject()) {
        throw new IllegalArgumentException("each dependency is an object with an id");
      }
      JsonObject dependency = element.getAsJsonObject();
      JsonElement optional = dependency.get("optional");
      dependencies.add(
          new PluginDependency(
              required(dependency, "id"), optional != null && optional.getAsBoolean()));
    }
    return new PluginDescription(
        required(root, "id"),
        optional(root, "name"),
        optional(root, "version"),
        optional(root, "description"),
        optional(root, "url"),
        authors,
        dependencies,
        required(root, "main"));
  }

  private static String required(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
      throw new IllegalArgumentException("missing \"" + key + "\"");
    }
    return value.getAsString();
  }

  private static String optional(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }

  private static JsonArray array(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull()) {
      return new JsonArray();
    }
    if (!value.isJsonArray()) {
      throw new IllegalArgumentException("\"" + key + "\" must be an array");
    }
    return value.getAsJsonArray();
  }
}
