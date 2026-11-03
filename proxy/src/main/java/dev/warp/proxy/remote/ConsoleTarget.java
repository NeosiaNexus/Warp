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
package dev.warp.proxy.remote;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A proxy the client attaches to: a short name (the prefix of its lines when there are several),
 * the base address of its management server, and the token.
 *
 * @param name the name, such as {@code eu-1}
 * @param base the base address, such as {@code https://eu-1.example.net:9901}
 * @param token the bearer token
 */
record ConsoleTarget(String name, URI base, String token) {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,32}");

  /**
   * Parses a {@code --url} value: {@code [name=]url}, the name defaulting to the host's first
   * label.
   *
   * @param value the value
   * @param token the token
   * @return the target
   * @throws IllegalArgumentException if the address or the name is invalid
   */
  static ConsoleTarget parse(String value, String token) {
    String name = null;
    String address = value;
    int equals = value.indexOf('=');
    int scheme = value.indexOf("://");
    if (equals > 0 && (scheme < 0 || equals < scheme)) {
      name = value.substring(0, equals);
      address = value.substring(equals + 1);
    }
    URI uri;
    try {
      uri = new URI(address.endsWith("/") ? address.substring(0, address.length() - 1) : address);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("invalid address " + address + ": " + e.getReason(), e);
    }
    String protocol = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if ((!protocol.equals("http") && !protocol.equals("https")) || uri.getHost() == null) {
      throw new IllegalArgumentException(
          "invalid address " + address + ": use http://host:port or https://host:port");
    }
    if (uri.getUserInfo() != null || uri.getQuery() != null) {
      throw new IllegalArgumentException(
          "invalid address " + address + ": no user, password or query; the token goes in a file");
    }
    if (name == null) {
      String host = uri.getHost();
      int dot = host.indexOf('.');
      name = dot > 0 && !Character.isDigit(host.charAt(0)) ? host.substring(0, dot) : host;
      name = name.replace(':', '-').replace("[", "").replace("]", "");
      name = name.length() > 32 ? name.substring(0, 32) : name;
    }
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "invalid name " + name + ": 1 to 32 letters, digits, dots, dashes or underscores");
    }
    return new ConsoleTarget(name, uri, token);
  }

  /**
   * Returns the same target under another name.
   *
   * @param newName the name
   * @return the target
   */
  ConsoleTarget named(String newName) {
    return new ConsoleTarget(newName, base, token);
  }

  /**
   * Returns the address of an endpoint.
   *
   * @param path the endpoint's path, such as {@code /console/logs?replay=10}
   * @return its address
   */
  URI resolve(String path) {
    return URI.create(base + path);
  }

  /** Names the target without its token. */
  @Override
  public String toString() {
    return name + " (" + ConsoleHttp.describe(base) + ")";
  }
}
