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
package dev.warp.via;

import dev.warp.api.event.player.ServerPostConnectEvent;
import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.player.Player;

import java.nio.charset.StandardCharsets;

import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.ConnectionDetails;
import com.viaversion.viaversion.libs.gson.JsonObject;

/**
 * Sends ViaVersion's connection details each time a player reaches a server: the player's real
 * version to the server ({@code vv:proxy_details}, read by ViaVersion and anti-cheats on the
 * backend), and the server's version to the client (read by client mods such as ViaFabricPlus).
 *
 * <p>Every server gets them, on every switch, translated or not: on Velocity they only reached the
 * first server for a while (ViaVersion#4565), and only players with a ViaVersion connection got
 * them. The payloads are ViaVersion's own format ({@link ConnectionDetails}).
 */
final class ConnectionDetailsSender {

  /** The channel the details travel on, owned by this plugin so that nobody else relays it. */
  static final ChannelIdentifier CHANNEL = new ChannelIdentifier(ConnectionDetails.PROXY_CHANNEL);

  private static final int SPEC_VERSION = 1;

  private final ViaPlatform<?> platform;
  private final ViaVersionConfig config;
  private final BackendVersions versions;

  /**
   * Creates the sender.
   *
   * @param platform the platform, which names the proxy in the details
   * @param config ViaVersion's settings, which may turn either kind of details off
   * @param versions the backend versions
   */
  ConnectionDetailsSender(
      ViaPlatform<?> platform, ViaVersionConfig config, BackendVersions versions) {
    this.platform = platform;
    this.config = config;
    this.versions = versions;
  }

  /**
   * Sends the details for a player that reached a server.
   *
   * @param event the event
   */
  void serverReached(ServerPostConnectEvent event) {
    Player player = event.player();
    ProtocolVersion client = ProtocolVersion.getProtocol(player.protocolVersion());
    if (config.sendPlayerDetails()) {
      JsonObject details = details(client);
      details.addProperty("platformName", platform.getPlatformName());
      details.addProperty("platformVersion", platform.getPlatformVersion());
      var _ =
          player
              .currentServer()
              .map(server -> server.sendPluginMessage(CHANNEL, bytes(details)))
              .orElse(false);
    }
    if (config.sendServerDetails()) {
      ProtocolVersion backend = versions.serverProtocolVersion(event.server().name());
      var _ =
          player.sendPluginMessage(CHANNEL, bytes(details(backend.isKnown() ? backend : client)));
    }
  }

  /** The fields both kinds of details share. */
  static JsonObject details(ProtocolVersion version) {
    JsonObject details = new JsonObject();
    details.addProperty("specVersion", SPEC_VERSION);
    details.addProperty("version", version.getOriginalVersion());
    details.addProperty("versionName", version.getName());
    details.addProperty("versionType", version.getVersionType().name());
    return details;
  }

  private static byte[] bytes(JsonObject details) {
    return details.toString().getBytes(StandardCharsets.UTF_8);
  }
}
