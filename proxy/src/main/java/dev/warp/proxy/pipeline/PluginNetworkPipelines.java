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
import dev.warp.api.network.NetworkPipelines;
import dev.warp.api.network.PipelineInitializer;

import java.util.Objects;
import java.util.function.Consumer;

/** One plugin's view of the pipeline registry: what it registers is owned by it and tracked. */
public final class PluginNetworkPipelines implements NetworkPipelines {

  private final PipelineRegistry registry;
  private final String owner;
  private final Consumer<Subscription> tracker;

  /**
   * Creates a plugin's registrar.
   *
   * @param registry the proxy's pipeline initializers
   * @param owner the plugin's identifier
   * @param tracker receives every registration, to remove it when the plugin goes away
   */
  public PluginNetworkPipelines(
      PipelineRegistry registry, String owner, Consumer<Subscription> tracker) {
    this.registry = registry;
    this.owner = owner;
    this.tracker = tracker;
  }

  @Override
  public Subscription initializeClients(PipelineInitializer<ClientPipeline> initializer) {
    Objects.requireNonNull(initializer, "initializer");
    Subscription subscription = registry.addClientInitializer(owner, initializer);
    tracker.accept(subscription);
    return subscription;
  }

  @Override
  public Subscription initializeBackends(PipelineInitializer<BackendPipeline> initializer) {
    Objects.requireNonNull(initializer, "initializer");
    Subscription subscription = registry.addBackendInitializer(owner, initializer);
    tracker.accept(subscription);
    return subscription;
  }
}
