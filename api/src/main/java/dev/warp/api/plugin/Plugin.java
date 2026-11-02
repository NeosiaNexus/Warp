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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the main class of a Warp plugin.
 *
 * <p>The annotation processor shipped with the API reads this annotation at compile time, checks
 * it, and writes the {@code warp-plugin.json} descriptor the proxy loads the plugin from. Add the
 * API to the annotation processor path of the plugin's build:
 *
 * <pre>{@code
 * dependencies {
 *     compileOnly("dev.warp:warp-api:<version>")
 *     annotationProcessor("dev.warp:warp-api:<version>")
 * }
 * }</pre>
 *
 * <p>Exactly one class of a plugin carries this annotation. It must be public, not abstract, and
 * have exactly one public constructor.
 *
 * <pre>{@code
 * @Plugin(id = "lobby-router", name = "Lobby Router", version = "1.2.0", authors = "Steve")
 * public final class LobbyRouter implements WarpPlugin {
 *   public LobbyRouter(Warp proxy, Logger logger) { ... }
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Plugin {

  /**
   * The plugin's unique identifier: 1 to 64 characters, lowercase letters, digits, {@code -} and
   * {@code _}, starting with a letter. It names the plugin's data directory and its logger, and is
   * what other plugins depend on. Changing it later breaks plugins that depend on this one.
   *
   * @return the identifier
   */
  String id();

  /**
   * The plugin's display name. Defaults to the identifier.
   *
   * @return the name, or an empty string to use the identifier
   */
  String name() default "";

  /**
   * The plugin's version, free-form (semantic versioning recommended).
   *
   * @return the version, or an empty string if unspecified
   */
  String version() default "";

  /**
   * A one-line description of what the plugin does.
   *
   * @return the description, or an empty string if unspecified
   */
  String description() default "";

  /**
   * The plugin's website or source repository.
   *
   * @return the URL, or an empty string if unspecified
   */
  String url() default "";

  /**
   * The plugin's authors.
   *
   * @return the authors, possibly empty
   */
  String[] authors() default {};

  /**
   * The plugins this plugin needs. Required dependencies are loaded and enabled before this plugin,
   * and their classes are visible to it; a missing or failed required dependency prevents this
   * plugin from loading. Optional dependencies are ordered the same way when present.
   *
   * @return the dependencies, possibly empty
   */
  Dependency[] dependencies() default {};
}
