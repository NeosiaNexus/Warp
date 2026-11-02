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

import dev.warp.api.Warp;
import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.player.Player;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginContext;
import dev.warp.api.plugin.PluginDescription;

import java.io.File;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ProtocolDetectorService;
import com.viaversion.viaversion.api.platform.ViaServerProxyPlatform;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * ViaVersion's platform on Warp: what ViaVersion asks of the proxy it runs on.
 *
 * <p>Players are reached through Warp's API, never through internal classes. Scheduling uses
 * ViaVersion's own scheduler (Warp's API has none yet), off the event loops.
 */
final class WarpViaPlatform implements ViaServerProxyPlatform<Player> {

  private static final LegacyComponentSerializer LEGACY =
      LegacyComponentSerializer.builder().character('§').extractUrls().build();

  private final PluginContext context;
  private final Logger logger;
  private final File dataFolder;
  private final WarpViaConfig config;
  private final WarpViaApi api;
  private final BackendVersions versions;

  /**
   * Creates the platform.
   *
   * @param context the plugin's context
   * @param logger ViaVersion's logger, written to the plugin's
   * @param config ViaVersion's settings
   * @param versions the backend versions
   */
  WarpViaPlatform(
      PluginContext context, Logger logger, WarpViaConfig config, BackendVersions versions) {
    this.context = context;
    this.logger = logger;
    this.dataFolder = context.dataDirectory().toFile();
    this.config = config;
    this.api = new WarpViaApi(context.proxy());
    this.versions = versions;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }

  @Override
  public String getPlatformName() {
    return "Warp";
  }

  @Override
  public String getPlatformVersion() {
    return context.proxy().version();
  }

  @Override
  public boolean isProxy() {
    return true;
  }

  @Override
  public void sendMessage(UserConnection connection, String message) {
    player(connection).ifPresent(player -> player.sendMessage(LEGACY.deserialize(message)));
  }

  @Override
  public boolean kickPlayer(UserConnection connection, String message) {
    Optional<Player> player = player(connection);
    player.ifPresent(p -> p.disconnect(LEGACY.deserialize(message)));
    return player.isPresent();
  }

  @Override
  public void sendCustomPayload(UserConnection connection, String channel, byte[] message) {
    ChannelIdentifier identifier = new ChannelIdentifier(channel);
    var _ =
        player(connection)
            .flatMap(Player::currentServer)
            .map(server -> server.sendPluginMessage(identifier, message))
            .orElse(false);
  }

  @Override
  public void sendCustomPayloadToClient(UserConnection connection, String channel, byte[] message) {
    ChannelIdentifier identifier = new ChannelIdentifier(channel);
    var _ = player(connection).map(p -> p.sendPluginMessage(identifier, message)).orElse(false);
  }

  @Override
  public WarpViaApi getApi() {
    return api;
  }

  @Override
  public WarpViaConfig getConf() {
    return config;
  }

  @Override
  public File getDataFolder() {
    return dataFolder;
  }

  @Override
  public JsonObject getDump() {
    JsonObject dump = new JsonObject();
    JsonArray plugins = new JsonArray();
    for (PluginContainer plugin : context.proxy().plugins().plugins()) {
      PluginDescription description = plugin.description();
      JsonObject entry = new JsonObject();
      entry.addProperty("id", description.id());
      entry.addProperty("version", description.version());
      entry.addProperty("state", plugin.state().name());
      entry.addProperty("capabilities", description.capabilities().toString());
      plugins.add(entry);
    }
    dump.add("plugins", plugins);
    JsonObject servers = new JsonObject();
    for (var server : context.proxy().servers().servers()) {
      servers.addProperty(server.name(), versions.serverProtocolVersion(server.name()).getName());
    }
    dump.add("servers", servers);
    return dump;
  }

  @Override
  public boolean hasPlugin(String name) {
    return context.proxy().plugins().isEnabled(name);
  }

  @Override
  public ProtocolDetectorService protocolDetectorService() {
    return versions;
  }

  /** The proxy, for the classes that need more of it than the platform. */
  Warp proxy() {
    return context.proxy();
  }

  private Optional<Player> player(UserConnection connection) {
    UUID uuid = connection.getProtocolInfo().getUuid();
    return uuid == null ? Optional.empty() : context.proxy().player(uuid);
  }
}
