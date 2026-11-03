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
package dev.warp.api.network;

import dev.warp.api.event.Subscription;

/**
 * Registers the plugin's pipeline initializers. Each plugin has its own, from {@link
 * dev.warp.api.plugin.PluginContext#network()}, available to plugins that declare {@link
 * dev.warp.api.plugin.Capability#NETWORK_PIPELINE}. See the {@link dev.warp.api.network package
 * documentation} for the pipeline, the positions and the threading contract.
 *
 * <pre>{@code
 * network.initializeBackends(pipeline -> {
 *   if (needsTranslation(pipeline.protocolVersion(), pipeline.server())) {
 *     pipeline.addHandler(PipelinePosition.PACKET, "decoder", new TranslatingDecoder());
 *     pipeline.addHandler(PipelinePosition.PACKET, "encoder", new TranslatingEncoder());
 *   }
 * });
 * }</pre>
 *
 * <p>Initializers only apply to connections set up after they were registered; closing the
 * subscription, or disabling the plugin, stops them for new connections and leaves the handlers of
 * open connections in place until those close.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface NetworkPipelines {

  /**
   * Registers an initializer for client connections that start a login, run once their handshake
   * was read.
   *
   * @param initializer the initializer
   * @return the registration; closing it stops the initializer for new connections
   */
  Subscription initializeClients(PipelineInitializer<ClientPipeline> initializer);

  /**
   * Registers an initializer for the connections the proxy opens to backend servers, run before
   * each connects.
   *
   * @param initializer the initializer
   * @return the registration; closing it stops the initializer for new connections
   */
  Subscription initializeBackends(PipelineInitializer<BackendPipeline> initializer);
}
