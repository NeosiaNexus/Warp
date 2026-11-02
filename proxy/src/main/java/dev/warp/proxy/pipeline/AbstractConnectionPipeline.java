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

import dev.warp.api.network.ConnectionPipeline;
import dev.warp.api.network.ConnectionSide;
import dev.warp.api.network.PipelinePosition;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import org.jspecify.annotations.Nullable;

/**
 * The view of a connection's pipeline given to one plugin's initializer: it names the plugin's
 * handlers after it, stops accepting handlers once the initializer returned, and keeps track of the
 * handlers the initializer must leave where they are ({@link ProtectedHandlers}).
 */
abstract class AbstractConnectionPipeline implements ConnectionPipeline {

  private final PipelineHost host;
  private final String plugin;
  private final int protocolVersion;
  private final InetSocketAddress remoteAddress;

  /** Cleared once the initializer returned: the view is only valid during the call. */
  private boolean open = true;

  /** The handlers the initializer must leave where they are, the proxy's new ones included. */
  private ProtectedHandlers protectedHandlers = ProtectedHandlers.NONE;

  /** The first change to a protected handler, found when the initializer added a handler. */
  private ProtectedHandlers.@Nullable Violation violation;

  /**
   * Creates the view.
   *
   * @param host the connection
   * @param plugin the identifier of the plugin whose initializer receives it
   * @param protocolVersion the protocol version the proxy speaks on the connection
   * @param remoteAddress the peer's address
   */
  AbstractConnectionPipeline(
      PipelineHost host, String plugin, int protocolVersion, InetSocketAddress remoteAddress) {
    this.host = host;
    this.plugin = plugin;
    this.protocolVersion = protocolVersion;
    this.remoteAddress = remoteAddress;
  }

  @Override
  public abstract ConnectionSide side();

  @Override
  public int protocolVersion() {
    return protocolVersion;
  }

  @Override
  public InetSocketAddress remoteAddress() {
    return remoteAddress;
  }

  @Override
  public Channel channel() {
    return host.channel();
  }

  @Override
  public String addHandler(PipelinePosition position, String name, ChannelHandler handler) {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(handler, "handler");
    if (!open) {
      throw new IllegalStateException(
          "Plugin "
              + plugin
              + " added handler '"
              + name
              + "' after its initializer returned: add handlers during the call only");
    }
    if (name.isBlank()) {
      throw new IllegalArgumentException("A handler name must not be blank");
    }
    String qualified = plugin + ":" + name;
    ChannelPipeline pipeline = host.channel().pipeline();
    if (pipeline.get(qualified) != null) {
      throw new IllegalArgumentException(
          "Plugin " + plugin + " already added a handler named '" + name + "' to this connection");
    }
    // The proxy may put handlers of its own in (packet level): they are protected too, but only
    // once what the initializer did so far was checked, so that none of its changes is taken in.
    if (violation == null) {
      violation = protectedHandlers.check(pipeline);
    }
    Set<String> before = Set.copyOf(pipeline.names());
    host.addPluginHandler(position, qualified, handler);
    if (violation == null) {
      protectedHandlers = protectedHandlers.withAddedByProxy(pipeline, before, qualified);
    }
    return qualified;
  }

  @Override
  public List<String> handlers(PipelinePosition position) {
    return host.pluginHandlers(position);
  }

  @Override
  public boolean decompressesPackets() {
    return host.isPacketLevel();
  }

  /**
   * Sets the handlers the initializer must leave where they are: those of the pipeline before it
   * ran.
   *
   * @param handlers the handlers
   */
  void protect(ProtectedHandlers handlers) {
    protectedHandlers = handlers;
  }

  /**
   * Returns the first change the initializer made to a protected handler. Call it once the
   * initializer returned.
   *
   * @return the change, or {@code null} if it left them all where they were
   */
  ProtectedHandlers.@Nullable Violation violation() {
    ProtectedHandlers.Violation found = violation;
    return found != null ? found : protectedHandlers.check(host.channel().pipeline());
  }

  /** Stops accepting handlers: the initializer returned. */
  void close() {
    open = false;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + side() + " " + remoteAddress + "]";
  }
}
