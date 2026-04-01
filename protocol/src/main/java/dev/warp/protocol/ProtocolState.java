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
package dev.warp.protocol;

/**
 * The protocol states a Minecraft connection transitions through.
 *
 * <p>The standard flow is:
 *
 * <pre>
 *   HANDSHAKE -> STATUS (server list ping)
 *   HANDSHAKE -> LOGIN -> [CONFIGURATION] -> PLAY
 * </pre>
 *
 * <p>The {@link #CONFIGURATION} state was introduced in Minecraft 1.20.2 (protocol 764). On older
 * versions the connection moves directly from {@link #LOGIN} to {@link #PLAY}. Use {@link
 * ProtocolVersion#supportsConfigurationState()} to check support at runtime.
 */
public enum ProtocolState {

  /** Initial state; the client declares intent (status query or login). */
  HANDSHAKE,

  /** Server-list ping / motd exchange. */
  STATUS,

  /** Authentication, encryption, and compression negotiation. */
  LOGIN,

  /**
   * Resource-pack and registry synchronisation state, available since 1.20.2 (protocol 764).
   *
   * <p>Proxies that support blind forwarding must be aware that this state exists only when {@link
   * ProtocolVersion#supportsConfigurationState()} returns {@code true}.
   */
  CONFIGURATION,

  /** Main gameplay state — the vast majority of packets live here. */
  PLAY
}
