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
package dev.warp.protocol.netty;

import dev.warp.protocol.packet.Packet;

import io.netty.buffer.ByteBuf;

/**
 * Handles decoded packets and connection lifecycle events for a given protocol state.
 *
 * <p>Each protocol state (handshake, status, login, configuration, play) provides its own
 * implementation. The active handler is swapped on the {@link MinecraftDecoder}'s owning connection
 * whenever the protocol state changes.
 *
 * <h3>Design rationale — why 4 methods, not 67</h3>
 *
 * <p>Velocity's {@code MinecraftSessionHandler} declares one overloaded {@code handle()} method per
 * packet type (~67 methods), forming a classic <em>fat interface</em>. Adding a single packet
 * requires editing the interface <em>and</em> every packet class. Worse, the default return value
 * ({@code false}) silently swallows unhandled packets — no compile-time safety.
 *
 * <p>Warp's approach relies on Java 21 sealed interfaces: each protocol state has a sealed packet
 * hierarchy (e.g. {@link dev.warp.protocol.packet.status.StatusPacket StatusPacket}).
 * Implementations of this interface use exhaustive {@code switch} expressions over the sealed
 * hierarchy, so the compiler rejects any handler that misses a packet type. The result is a
 * 4-method interface that is both minimal and strictly type-safe.
 *
 * <h3>Thread safety</h3>
 *
 * <p>All methods are invoked from the Netty event loop thread that owns the channel. No
 * synchronization is required within implementations.
 */
public interface SessionHandler {

  /**
   * Handles a decoded, typed packet.
   *
   * <p>Implementations should use exhaustive pattern matching on the appropriate sealed
   * sub-interface to dispatch the packet. For example, a status handler would switch on {@link
   * dev.warp.protocol.packet.status.StatusPacket StatusPacket}.
   *
   * @param packet the decoded packet
   */
  void handle(Packet packet);

  /**
   * Handles a raw, unregistered packet that was not deserialized (blind forwarding path).
   *
   * <p>The buffer contains the complete packet data including the packet ID bytes. The handler is
   * responsible for releasing or forwarding the buffer. The default implementation releases the
   * buffer immediately, which is the correct behavior for states that do not support blind
   * forwarding (handshake, status, login).
   *
   * @param buf the raw packet buffer (caller transfers ownership)
   */
  default void handleBlind(ByteBuf buf) {
    buf.release();
  }

  /**
   * Called when this handler becomes the active handler on a connection.
   *
   * <p>Use this to perform one-time setup that requires the connection to be available (e.g.
   * scheduling a timeout). The default implementation does nothing.
   */
  default void activated() {}

  /**
   * Called when the connection is closed or the handler is replaced.
   *
   * <p>Use this to release resources (cancel timers, clean up state). The default implementation
   * does nothing.
   */
  default void disconnected() {}
}
