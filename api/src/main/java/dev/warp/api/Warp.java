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
package dev.warp.api;

/**
 * Entry point to the Warp API.
 *
 * <p>Provides access to core proxy services. Plugin code should depend on this interface rather
 * than internal implementations. Obtain the singleton via {@link WarpProvider#get()}.
 *
 * <h2>Planned API surface</h2>
 *
 * The following managers will be exposed as the API matures:
 *
 * <ul>
 *   <li>{@code EventManager} — publish/subscribe event bus for proxy and player events.
 *   <li>{@code ServerManager} — dynamic backend server registry (add/remove/drain).
 *   <li>{@code PlayerManager} — connected-player queries and bulk operations.
 *   <li>{@code CommandManager} — command registration and dispatch.
 *   <li>{@code Scheduler} — async and tick-aligned task scheduling.
 * </ul>
 *
 * <p>These are intentionally <em>not</em> declared as methods yet to avoid depending on types that
 * do not exist. They will be added incrementally as each subsystem is implemented.
 */
public interface Warp {

  /** Returns the proxy version string (e.g. {@code "0.1.0-SNAPSHOT"}). */
  String version();
}
