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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pipeline initializers plugins registered, and their runs on new connections.
 *
 * <p>Registration is thread-safe; runs happen on each connection's event loop. A connection with no
 * initializer registered costs one list check.
 */
public final class PipelineRegistry {

  private static final Logger logger = LoggerFactory.getLogger(PipelineRegistry.class);

  /** Initializers of client connections, in registration order. */
  private final List<Registration<ClientPipeline>> clients = new CopyOnWriteArrayList<>();

  /** Initializers of backend connections, in registration order. */
  private final List<Registration<BackendPipeline>> backends = new CopyOnWriteArrayList<>();

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
   * @return {@code true} if the connection is set up; {@code false} if an initializer failed, in
   *     which case the connection was closed
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
   * @return {@code true} if the connection is set up; {@code false} if an initializer failed, in
   *     which case the connection was closed
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

  private static boolean run(
      List<? extends Registration<?>> registrations,
      Function<String, AbstractConnectionPipeline> pipelines,
      PipelineHost host,
      String description) {
    for (Registration<?> registration : registrations) {
      AbstractConnectionPipeline pipeline = pipelines.apply(registration.plugin());
      try {
        initializerOf(registration).initialize(pipeline);
      } catch (Exception | LinkageError e) {
        logger.error(
            "Plugin {} failed to set up the {}: closing it", registration.plugin(), description, e);
        var _ = host.channel().close();
        return false;
      } finally {
        pipeline.close();
      }
    }
    return true;
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
