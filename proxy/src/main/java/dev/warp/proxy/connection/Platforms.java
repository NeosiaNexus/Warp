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
package dev.warp.proxy.connection;

import dev.warp.api.player.ClientPlatform;

/** Describes a client's platform for staff: the log line of a login, {@code /find}. */
public final class Platforms {

  private Platforms() {}

  /**
   * Describes a platform as a suffix to a version: nothing for a vanilla Java client, {@code ",
   * Forge"} for a modded one, {@code ", Bedrock on Android"} for a Bedrock player.
   *
   * @param platform the platform
   * @return the suffix, empty for {@link ClientPlatform#JAVA}
   */
  public static String describe(ClientPlatform platform) {
    return switch (platform) {
      case ClientPlatform.Java _ -> "";
      case ClientPlatform.Forge _ -> ", Forge";
      case ClientPlatform.NeoForge _ -> ", NeoForge";
      case ClientPlatform.Bedrock bedrock -> ", Bedrock on " + bedrock.deviceOs().displayName();
    };
  }
}
