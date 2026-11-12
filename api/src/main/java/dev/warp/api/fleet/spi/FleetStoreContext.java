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
package dev.warp.api.fleet.spi;

import java.util.concurrent.Executor;

/**
 * What a store learns when the fleet connects it.
 *
 * @param networkId the network's name ({@code fleet.network}): a store serves one network, and keys
 *     or topics of different networks must never meet
 * @param nodeId this proxy's node id
 * @param executor runs the listeners of {@link FleetStore#subscribe} and {@link
 *     FleetStore#onStateChange}: fleet threads, never a connection's event loop. A store may run
 *     its own I/O wherever it likes, but must never block a thread it did not create
 */
public record FleetStoreContext(String networkId, String nodeId, Executor executor) {}
