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
package dev.warp.api.messaging;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The name of a plugin message channel, as it appears on the wire: {@code namespace:path} since
 * Minecraft 1.13, or a legacy name such as {@code BungeeCord} before.
 *
 * @param id the channel's wire name
 */
public record ChannelIdentifier(String id) {

  private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9._-]+");
  private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

  /** Longest legacy channel name the protocol carries. */
  private static final int MAX_LEGACY_LENGTH = 20;

  /**
   * Validates the name.
   *
   * @throws IllegalArgumentException if it is neither a valid {@code namespace:path} nor a valid
   *     legacy name
   */
  public ChannelIdentifier {
    Objects.requireNonNull(id, "id");
    int colon = id.indexOf(':');
    boolean valid =
        colon > 0
            ? NAMESPACE.matcher(id.substring(0, colon)).matches()
                && PATH.matcher(id.substring(colon + 1)).matches()
            : !id.isEmpty()
                && id.length() <= MAX_LEGACY_LENGTH
                && id.chars().allMatch(c -> c > ' ');
    if (!valid) {
      throw new IllegalArgumentException(
          "Invalid channel '"
              + id
              + "': use namespace:path with lowercase letters, digits, '.', '_', '-' (and '/' in"
              + " the path), or a legacy name of 1 to 20 visible characters");
    }
  }

  /**
   * Returns a modern channel.
   *
   * @param namespace the namespace, usually the plugin's identifier
   * @param path the path
   * @return the channel {@code namespace:path}
   */
  public static ChannelIdentifier of(String namespace, String path) {
    return new ChannelIdentifier(namespace + ":" + path);
  }

  /**
   * Returns whether this is a legacy (pre-1.13) channel name.
   *
   * @return {@code true} if the name has no namespace
   */
  public boolean isLegacy() {
    return id.indexOf(':') < 0;
  }

  @Override
  public String toString() {
    return id;
  }
}
