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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.text.ComponentCodec;

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;

/**
 * Server sets the text above and below the tab list ({@code S→C}, from 1.8).
 *
 * <p>Two text components, VarInt-prefixed JSON strings before 1.20.3, NBT from 1.20.3 (Velocity's
 * {@code HeaderAndFooterPacket}, minecraft-data's {@code packet_playerlist_header}). An empty
 * component hides its part.
 *
 * <p>Before 1.20.2 the header and footer survive the Join Game of a server switch; the proxy sends
 * empty ones ({@link #empty(ProtocolVersion)}) when a player switches, as Velocity does. From
 * 1.20.2 the configuration phase of a switch clears them on the client.
 *
 * @param header the header component, encoded as on the wire
 * @param footer the footer component, encoded as on the wire
 */
@SuppressWarnings("ArrayRecordComponent") // components are never mutated
public record TabListHeaderFooter(byte[] header, byte[] footer) implements PlayPacket {

  /**
   * Creates the packet that clears the header and footer: an empty component hides each of them.
   *
   * @param version the client's protocol version, which selects the component encoding
   * @return the packet
   */
  public static TabListHeaderFooter empty(ProtocolVersion version) {
    byte[] empty = ComponentCodec.encode(Component.empty(), version);
    return new TabListHeaderFooter(empty, empty);
  }

  /**
   * Creates the packet that sets the header and footer for a client of {@code version}.
   *
   * @param header the text above the tab list, empty to hide it
   * @param footer the text below the tab list, empty to hide it
   * @param version the client's protocol version, from 1.8
   * @return the packet
   * @throws IllegalArgumentException if a component is too large for the client
   */
  public static TabListHeaderFooter of(
      Component header, Component footer, ProtocolVersion version) {
    return new TabListHeaderFooter(
        ComponentCodec.encode(header, version), ComponentCodec.encode(footer, version));
  }

  /** Codec for reading and writing tab list header and footer packets. */
  public static final PacketCodec<TabListHeaderFooter> CODEC =
      new PacketCodec<>() {
        @Override
        public TabListHeaderFooter decode(ByteBuf buf, ProtocolVersion version) {
          byte[] header = ComponentCodec.readRaw(buf, version);
          return new TabListHeaderFooter(header, ComponentCodec.readRaw(buf, version));
        }

        @Override
        public void encode(TabListHeaderFooter packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.header());
          buf.writeBytes(packet.footer());
        }
      };
}
