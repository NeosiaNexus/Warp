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

/**
 * Sets up the pipeline of new connections, see {@link NetworkPipelines}.
 *
 * @param <P> the kind of connection: {@link ClientPipeline} or {@link BackendPipeline}
 */
@FunctionalInterface
public interface PipelineInitializer<P extends ConnectionPipeline> {

  /**
   * Sets up one connection, on its event loop: decides whether it needs handlers, and adds them.
   * Must not block.
   *
   * @param pipeline the connection's pipeline, valid during this call only
   * @throws Exception closes the connection, and is logged with the plugin's identifier
   */
  void initialize(P pipeline) throws Exception;
}
