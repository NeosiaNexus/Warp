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

import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.GameProfile;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;
import dev.warp.proxy.server.BackendServer;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;

/** A player as plugins see it: a view of the proxy's {@link ConnectedPlayer}. */
public final class WarpPlayer implements Player {

  private final ConnectedPlayer player;

  WarpPlayer(ConnectedPlayer player) {
    this.player = player;
  }

  @Override
  public UUID uniqueId() {
    return player.uuid();
  }

  @Override
  public String username() {
    return player.username();
  }

  @Override
  public GameProfile profile() {
    return player.apiProfile();
  }

  @Override
  public UUID sessionId() {
    return player.sessionId();
  }

  @Override
  public boolean isOnlineMode() {
    return player.isOnlineMode();
  }

  @Override
  public InetSocketAddress remoteAddress() {
    return player.remoteAddress();
  }

  @Override
  public Optional<String> virtualHost() {
    return Optional.ofNullable(player.clientConnection().virtualHost());
  }

  @Override
  public int protocolVersion() {
    return player.protocolVersion().protocol();
  }

  @Override
  public boolean isActive() {
    return player.clientConnection().channel().isActive();
  }

  @Override
  public Optional<ServerConnection> currentServer() {
    BackendServer server = player.currentServer();
    return server == null
        ? Optional.empty()
        : Optional.of(new WarpServerConnection(player, server));
  }

  @Override
  public Optional<RegisteredServer> pendingServer() {
    return Optional.ofNullable(player.pendingServer());
  }

  @Override
  public long ping() {
    return player.measuredLatencyMs();
  }

  @Override
  public CompletableFuture<ConnectionResult> connect(RegisteredServer server) {
    if (!(server instanceof BackendServer backend)) {
      throw new IllegalArgumentException("Not a server of this proxy: " + server);
    }
    return player.requestConnection(backend, false);
  }

  @Override
  public void sendMessage(Component message) {
    player.sendMessage(message);
  }

  @Override
  public boolean hasPermission(String permission) {
    return player.loginContext().permissions().check(this, permission);
  }

  @Override
  public boolean sendPluginMessage(ChannelIdentifier channel, byte[] data) {
    return player.sendPluginMessage(channel.id(), data);
  }

  @Override
  public void disconnect(Component reason) {
    player.disconnectByPlugin(reason);
  }

  /**
   * Returns the proxy's player behind this view.
   *
   * @return the player
   */
  public ConnectedPlayer connectedPlayer() {
    return player;
  }

  @Override
  public String toString() {
    return "Player[" + player.username() + "]";
  }
}
