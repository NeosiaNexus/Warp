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
package dev.warp.proxy.pipeline;

import dev.warp.api.network.BackendPipeline;
import dev.warp.api.network.ConnectionSide;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.net.InetSocketAddress;

/** A backend connection's pipeline, as one plugin's initializer sees it. */
final class BackendPipelineImpl extends AbstractConnectionPipeline implements BackendPipeline {

  private final Player player;
  private final RegisteredServer server;

  BackendPipelineImpl(
      PipelineHost host,
      String plugin,
      int protocolVersion,
      InetSocketAddress remoteAddress,
      Player player,
      RegisteredServer server) {
    super(host, plugin, protocolVersion, remoteAddress);
    this.player = player;
    this.server = server;
  }

  @Override
  public ConnectionSide side() {
    return ConnectionSide.BACKEND;
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public RegisteredServer server() {
    return server;
  }
}
