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

/**
 * Counts what a connection's decoder and forwarder do with each frame, for the proxy's metrics. One
 * observer per connection, set once; every method runs on the connection's event loop, once per
 * frame, so an implementation must not allocate, lock or block.
 *
 * <p>{@link #NONE} is the default: the JIT inlines its empty methods, so an unobserved connection
 * pays nothing.
 */
public interface TrafficObserver {

  /** Counts nothing. */
  TrafficObserver NONE =
      new TrafficObserver() {
        @Override
        public void blind(int wireBytes) {}

        @Override
        public void decoded(int wireBytes) {}

        @Override
        public void passthrough(int wireBytes, int rawBytes) {}

        @Override
        public void reencoded(int wireBytes, int rawBytes) {}
      };

  /**
   * The decoder emitted a frame without reading its packet: the blind path.
   *
   * @param wireBytes the frame's size on the wire, length prefix included
   */
  void blind(int wireBytes);

  /**
   * The decoder read a packet from a frame.
   *
   * @param wireBytes the frame's size on the wire, length prefix included
   */
  void decoded(int wireBytes);

  /**
   * The forwarder wrote a compressed frame as it arrived, compressed bytes included.
   *
   * @param wireBytes the frame's size on the wire
   * @param rawBytes the packet's size once inflated, as the frame declares it
   */
  void passthrough(int wireBytes, int rawBytes);

  /**
   * The forwarder inflated a compressed frame and handed the packet to this connection's encoder,
   * which compresses it again under its own threshold.
   *
   * @param wireBytes the frame's size on the wire
   * @param rawBytes the packet's size once inflated
   */
  void reencoded(int wireBytes, int rawBytes);
}
