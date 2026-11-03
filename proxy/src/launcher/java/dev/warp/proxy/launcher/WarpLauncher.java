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
package dev.warp.proxy.launcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The jar's {@code Main-Class}: compiled for Java 8, so that a Java too old for Warp loads it and
 * says which Java to install, instead of failing with an {@code UnsupportedClassVersionError} the
 * operator has to decode. On a recent enough Java it hands over to {@code WarpBootstrap}.
 */
public final class WarpLauncher {

  /** The oldest Java Warp runs on. */
  static final int REQUIRED_JAVA = 25;

  /** The exit code on a Java too old: {@code EX_CONFIG}, as for an invalid configuration. */
  static final int JAVA_TOO_OLD = 78;

  private static final String BOOTSTRAP = "dev.warp.proxy.WarpBootstrap";

  private WarpLauncher() {}

  /**
   * Checks the Java version, then starts Warp.
   *
   * @param args the command-line arguments, passed on
   * @throws Throwable whatever Warp's own entry point throws
   */
  public static void main(String[] args) throws Throwable {
    int feature = featureVersion(System.getProperty("java.specification.version", "0"));
    if (feature < REQUIRED_JAVA) {
      System.err.println(tooOld(feature, System.getProperty("java.home", "unknown")));
      System.exit(JAVA_TOO_OLD);
      return;
    }
    Method main = Class.forName(BOOTSTRAP).getMethod("main", String[].class);
    try {
      main.invoke(null, (Object) args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      throw cause != null ? cause : e;
    }
  }

  /**
   * Reads the feature version of a {@code java.specification.version}: {@code 1.8} is 8, {@code 21}
   * is 21.
   *
   * @param specification the property's value
   * @return the feature version, 0 if it cannot be read
   */
  static int featureVersion(String specification) {
    String version = specification.startsWith("1.") ? specification.substring(2) : specification;
    int end = 0;
    while (end < version.length() && Character.isDigit(version.charAt(end))) {
      end++;
    }
    return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
  }

  /**
   * Returns what the operator reads on a Java too old: which Java runs, which one Warp needs, and
   * how to get it.
   *
   * @param feature the feature version of the running Java
   * @param javaHome where the running Java is installed
   * @return the message
   */
  static String tooOld(int feature, String javaHome) {
    return "Warp cannot start: it needs Java "
        + REQUIRED_JAVA
        + " or newer, and was started with Java "
        + feature
        + " ("
        + javaHome
        + ")."
        + System.lineSeparator()
        + "Install Java "
        + REQUIRED_JAVA
        + " (for example Eclipse Temurin, https://adoptium.net), then start Warp with it: <java "
        + REQUIRED_JAVA
        + " directory>/bin/java -jar warp.jar. On a hosting panel, choose Java "
        + REQUIRED_JAVA
        + " in the server's settings.";
  }
}
