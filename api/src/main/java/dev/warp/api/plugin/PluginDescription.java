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
package dev.warp.api.plugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a plugin declares about itself, read from its {@code warp-plugin.json} descriptor.
 *
 * @param id the unique identifier, see {@link Plugin#id()}
 * @param name the display name; the identifier when none was declared
 * @param version the version, or an empty string
 * @param description the one-line description, or an empty string
 * @param url the website or source repository, or an empty string
 * @param authors the authors, unmodifiable
 * @param dependencies the plugins this one needs, unmodifiable
 * @param mainClass the fully qualified name of the class annotated with {@link Plugin}
 * @param capabilities the low-level access the plugin declared, see {@link Plugin#capabilities()};
 *     unmodifiable
 */
public record PluginDescription(
    String id,
    String name,
    String version,
    String description,
    String url,
    List<String> authors,
    List<PluginDependency> dependencies,
    String mainClass,
    Set<Capability> capabilities) {

  /** The form every plugin identifier takes. */
  public static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

  /**
   * Validates the description and copies its lists.
   *
   * @throws IllegalArgumentException if the identifier does not match {@link #ID_PATTERN}, a
   *     dependency is declared twice, or the plugin depends on itself
   */
  public PluginDescription {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(mainClass, "mainClass");
    if (!isValidId(id)) {
      throw new IllegalArgumentException(
          "Invalid plugin id '"
              + id
              + "': use 1 to 64 lowercase letters, digits, '-' or '_', starting with a letter");
    }
    if (name.isBlank()) {
      name = id;
    }
    authors = List.copyOf(authors);
    dependencies = List.copyOf(dependencies);
    Objects.requireNonNull(capabilities, "capabilities");
    capabilities =
        capabilities.isEmpty()
            ? Set.of()
            : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    for (int i = 0; i < dependencies.size(); i++) {
      String dependency = dependencies.get(i).id();
      if (dependency.equals(id)) {
        throw new IllegalArgumentException("Plugin '" + id + "' depends on itself");
      }
      for (int j = 0; j < i; j++) {
        if (dependencies.get(j).id().equals(dependency)) {
          throw new IllegalArgumentException(
              "Plugin '" + id + "' declares its dependency '" + dependency + "' twice");
        }
      }
    }
  }

  /**
   * Describes a plugin that declares no capability.
   *
   * @param id the unique identifier
   * @param name the display name, or blank for the identifier
   * @param version the version, or an empty string
   * @param description the one-line description, or an empty string
   * @param url the website or source repository, or an empty string
   * @param authors the authors
   * @param dependencies the plugins this one needs
   * @param mainClass the fully qualified name of the main class
   */
  public PluginDescription(
      String id,
      String name,
      String version,
      String description,
      String url,
      List<String> authors,
      List<PluginDependency> dependencies,
      String mainClass) {
    this(id, name, version, description, url, authors, dependencies, mainClass, Set.of());
  }

  /**
   * Returns whether the plugin declared a capability.
   *
   * @param capability the capability
   * @return {@code true} if it is among {@link #capabilities()}
   */
  public boolean hasCapability(Capability capability) {
    return capabilities.contains(capability);
  }

  /**
   * Returns whether a string is a valid plugin identifier.
   *
   * @param id the candidate
   * @return {@code true} if it matches {@link #ID_PATTERN}
   */
  public static boolean isValidId(String id) {
    return ID_PATTERN.matcher(id).matches();
  }
}
