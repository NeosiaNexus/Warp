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
package dev.warp.api.server;

import java.util.Objects;

/**
 * The route a client's connection matched: which {@code routes} entry of {@code warp.conf} its
 * virtual host fell under, and where that entry sends it.
 *
 * <p>A route matches the host name of the client's handshake once cleaned (lowercase, no trailing
 * dot, no {@code :port}, no Forge or BungeeCord suffix). The most specific entry wins: an exact
 * host before a wildcard, and a wildcard with more literal labels before one with fewer. A wildcard
 * entry such as {@code *.play.example.com} captures the labels the star stood for, so that a target
 * of {@code $1} names the server or group after them: {@code kitpvp.play.example.com} goes to
 * {@code kitpvp}.
 *
 * @param host the entry as configured, normalized to lowercase, such as {@code *.play.example.com}
 *     or {@code play.example.com:25566}
 * @param target the server or group the entry sends the connection to, with any capture already
 *     substituted, in lowercase
 */
public record Route(String host, String target) {

  /** Validates the route. */
  public Route {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(target, "target");
  }
}
