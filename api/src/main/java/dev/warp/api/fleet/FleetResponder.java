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
package dev.warp.api.fleet;

import java.util.concurrent.CompletableFuture;

/** Answers the requests other proxies send on a channel with {@link FleetBus#request}. */
@FunctionalInterface
public interface FleetResponder {

  /**
   * Answers a request. Called on a fleet thread, never on a connection's event loop; it may do
   * blocking work, but the requester waits for the future, with its own time-out.
   *
   * @param request the request, its {@link FleetMessage#senderProxyId} the proxy to answer
   * @return completed with the reply's bytes; a failure is reported to the requester as a failure
   */
  CompletableFuture<byte[]> answer(FleetMessage request);
}
