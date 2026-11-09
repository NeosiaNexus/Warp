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

import dev.warp.api.event.Subscription;
import dev.warp.api.network.BackendPipeline;
import dev.warp.api.network.ClientPipeline;
import dev.warp.api.network.ConnectionPipeline;
import dev.warp.api.network.PipelineInitializer;
import dev.warp.api.player.InboundConnection;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.proxy.logging.LogCode;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pipeline initializers plugins registered, and their runs on new connections.
 *
 * <p>Registration is thread-safe; runs happen on each connection's event loop. A connection with no
 * initializer registered costs one list check.
 *
 * <p>Initializers get the raw channel, so after each one the registry checks that the handlers it
 * found (the proxy's, and earlier plugins') are all still there, in their order ({@link
 * ProtectedHandlers}). An initializer that removed, replaced or moved one has its connection
 * closed, as one that throws: a pipeline rearranged under the proxy would corrupt the stream. The
 * check costs nothing once the connection is set up.
 */
public final class PipelineRegistry {

  private static final Logger logger = LoggerFactory.getLogger(PipelineRegistry.class);

  /** Initializers of client connections, in registration order. */
  private final List<Registration<ClientPipeline>> clients = new CopyOnWriteArrayList<>();

  /** Initializers of backend connections, in registration order. */
  private final List<Registration<BackendPipeline>> backends = new CopyOnWriteArrayList<>();

  /** The plugins already reported for changing a protected handler: each is reported once. */
  private final Set<String> reportedPlugins = ConcurrentHashMap.newKeySet();

  /**
   * The connections whose pipeline an initializer ran on: the ones that may carry a plugin's
   * handlers. A channel leaves the group by itself when it closes; once the group is closed, a
   * channel added to it (a connection that raced the shutdown) is closed at once.
   */
  private final ChannelGroup connections =
      new DefaultChannelGroup("warp-plugin-pipelines", GlobalEventExecutor.INSTANCE, true);

  /** Set once the proxy stops: from then on no connection takes plugin handlers. */
  private volatile boolean closing;

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  /**
   * Registers an initializer of client connections.
   *
   * @param plugin the identifier of the plugin that owns it
   * @param initializer the initializer
   * @return the registration
   */
  public Subscription addClientInitializer(
      String plugin, PipelineInitializer<ClientPipeline> initializer) {
    return add(clients, new Registration<>(plugin, initializer));
  }

  /**
   * Registers an initializer of backend connections.
   *
   * @param plugin the identifier of the plugin that owns it
   * @param initializer the initializer
   * @return the registration
   */
  public Subscription addBackendInitializer(
      String plugin, PipelineInitializer<BackendPipeline> initializer) {
    return add(backends, new Registration<>(plugin, initializer));
  }

  private static <P extends ConnectionPipeline> Subscription add(
      List<Registration<P>> registrations, Registration<P> registration) {
    registrations.add(registration);
    return new Subscription() {
      private volatile boolean active = true;

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public void unsubscribe() {
        active = false;
        registrations.remove(registration);
      }
    };
  }

  // ---------------------------------------------------------------------------
  // Runs
  // ---------------------------------------------------------------------------

  /**
   * Runs the client initializers on a connection whose handshake asked for a login.
   *
   * @param host the connection
   * @param protocolVersion the client's protocol version number
   * @param connection the connection as plugins see it
   * @return {@code true} if the connection is set up; {@code false} if an initializer failed or
   *     changed a protected handler, in which case the connection was closed
   */
  public boolean initializeClient(
      PipelineHost host, int protocolVersion, InboundConnection connection) {
    if (clients.isEmpty()) {
      return true;
    }
    return run(
        clients,
        plugin ->
            new ClientPipelineImpl(
                host, plugin, protocolVersion, connection.remoteAddress(), connection),
        host,
        "client " + connection.remoteAddress());
  }

  /**
   * Runs the backend initializers on a connection about to connect to a server.
   *
   * @param host the connection
   * @param protocolVersion the protocol version the proxy speaks on it: the client's
   * @param player the player it is for
   * @param server the server it leads to
   * @return {@code true} if the connection is set up; {@code false} if an initializer failed or
   *     changed a protected handler, in which case the connection was closed
   */
  public boolean initializeBackend(
      PipelineHost host, int protocolVersion, Player player, RegisteredServer server) {
    if (backends.isEmpty()) {
      return true;
    }
    InetSocketAddress address = server.info().address();
    return run(
        backends,
        plugin -> new BackendPipelineImpl(host, plugin, protocolVersion, address, player, server),
        host,
        "backend connection of " + player.username() + " to " + server.name());
  }

  private boolean run(
      List<? extends Registration<?>> registrations,
      Function<String, AbstractConnectionPipeline> pipelines,
      PipelineHost host,
      String description) {
    if (closing) {
      // The proxy is stopping, or stopped: a plugin's handler on this connection could run after
      // the plugin's class loader closed. The connection goes like a listener that is gone.
      var _ = host.channel().close();
      return false;
    }
    var _ = connections.add(host.channel());
    ChannelPipeline channelPipeline = host.channel().pipeline();
    ProtectedHandlers protectedHandlers = ProtectedHandlers.of(channelPipeline);
    for (Registration<?> registration : registrations) {
      AbstractConnectionPipeline pipeline = pipelines.apply(registration.plugin());
      pipeline.protect(protectedHandlers);
      try {
        initializerOf(registration).initialize(pipeline);
      } catch (Exception | LinkageError e) {
        logger.error(
            LogCode.PIPELINE_SETUP_FAILED.marker(),
            "Plugin {} failed to set up the {}: closing it",
            registration.plugin(),
            description,
            e);
        var _ = host.channel().close();
        return false;
      } finally {
        pipeline.close();
      }
      ProtectedHandlers.Violation violation = pipeline.violation();
      if (violation != null) {
        report(registration.plugin(), violation, description);
        var _ = host.channel().close();
        return false;
      }
      // What this plugin added is protected from the next ones.
      protectedHandlers = ProtectedHandlers.of(channelPipeline);
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // Shutdown
  // ---------------------------------------------------------------------------

  /**
   * Closes every connection that carries plugin handlers and waits for them to close, so that no
   * handler runs once its plugin is disabled and its class loader closed (a {@link
   * NoClassDefFoundError} in the middle of a decode, otherwise). From this call on, a connection
   * that asks for plugin handlers is closed instead. Idempotent.
   *
   * @param timeout how long to wait for the connections to close
   * @return {@code true} if every connection closed within the timeout
   */
  public boolean closeConnections(Duration timeout) {
    closing = true;
    return connections.close().awaitUninterruptibly(timeout.toMillis());
  }

  /**
   * Returns how many connections carrying plugin handlers are open.
   *
   * @return the number of connections
   */
  public int connectionCount() {
    return connections.size();
  }

  /**
   * Logs a change to a protected handler: once per plugin, which would do it on every connection.
   */
  private void report(String plugin, ProtectedHandlers.Violation violation, String description) {
    if (reportedPlugins.add(plugin)) {
      logger.error(
          LogCode.PIPELINE_TAMPERED.marker(),
          "Plugin {} {} on the {}: closing it. A plugin may add handlers to a pipeline, never"
              + " remove, replace or move the proxy's or another plugin's; each of its connections"
              + " is closed until it is fixed (reported once)",
          plugin,
          violation.describe(),
          description);
    } else {
      logger.debug("Plugin {} {} on the {}: closing it", plugin, violation.describe(), description);
    }
  }

  /** The registration was made for the kind of pipeline it is run on: see the two lists. */
  @SuppressWarnings("unchecked")
  private static PipelineInitializer<ConnectionPipeline> initializerOf(
      Registration<?> registration) {
    return (PipelineInitializer<ConnectionPipeline>) registration.initializer();
  }

  /**
   * A registered initializer.
   *
   * @param plugin the identifier of the plugin that owns it
   * @param initializer the initializer
   * @param <P> the kind of pipeline it sets up
   */
  private record Registration<P extends ConnectionPipeline>(
      String plugin, PipelineInitializer<P> initializer) {}
}
