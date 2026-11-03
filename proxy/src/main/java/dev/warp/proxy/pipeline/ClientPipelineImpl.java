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

import dev.warp.api.network.ClientPipeline;
import dev.warp.api.network.ConnectionSide;
import dev.warp.api.player.InboundConnection;

import java.net.InetSocketAddress;

/** A client connection's pipeline, as one plugin's initializer sees it. */
final class ClientPipelineImpl extends AbstractConnectionPipeline implements ClientPipeline {

  private final InboundConnection connection;

  ClientPipelineImpl(
      PipelineHost host,
      String plugin,
      int protocolVersion,
      InetSocketAddress remoteAddress,
      InboundConnection connection) {
    super(host, plugin, protocolVersion, remoteAddress);
    this.connection = connection;
  }

  @Override
  public ConnectionSide side() {
    return ConnectionSide.CLIENT;
  }

  @Override
  public InboundConnection connection() {
    return connection;
  }
}
