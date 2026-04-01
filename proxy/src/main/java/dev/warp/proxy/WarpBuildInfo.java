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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build information populated at compile time by Gradle's {@code processResources}.
 *
 * <p>Values are read from {@code warp-build.properties} on the classpath. The properties file is
 * generated during the build with the project version, git commit hash, and branch name.
 */
public final class WarpBuildInfo {

  /** Warp version from {@code version.txt} (e.g. {@code "0.1.0-beta.2"}). */
  public static final String VERSION;

  /** Abbreviated git commit hash at build time (e.g. {@code "3a1b2c3"}). */
  public static final String GIT_COMMIT;

  /** Git branch at build time (e.g. {@code "main"}). */
  public static final String GIT_BRANCH;

  static {
    Properties props = new Properties();
    try (InputStream in =
        WarpBuildInfo.class.getClassLoader().getResourceAsStream("warp-build.properties")) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException ignored) {
      // Fall through — defaults below.
    }
    VERSION = props.getProperty("version", "unknown");
    GIT_COMMIT = props.getProperty("git.commit", "unknown");
    GIT_BRANCH = props.getProperty("git.branch", "unknown");
  }

  private WarpBuildInfo() {}
}
