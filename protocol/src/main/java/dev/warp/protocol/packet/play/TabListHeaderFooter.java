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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.TextComponent;

import io.netty.buffer.ByteBuf;

/**
 * Server sets the text above and below the tab list ({@code S→C}, from 1.8).
 *
 * <p>Before 1.20.2 the header and footer survive the Join Game of a server switch; the proxy sends
 * empty ones ({@link #empty(ProtocolVersion)}) when a player switches, as Velocity does. Layout:
 * two text components, VarInt-prefixed JSON strings before 1.20.3 (Velocity's {@code
 * HeaderAndFooterPacket}, minecraft-data's {@code packet_playerlist_header}). The proxy only uses
 * it before 1.20.2.
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
    byte[] empty = TextComponent.plainText("", version);
    return new TabListHeaderFooter(empty, empty);
  }

  /** Codec for reading and writing tab list header and footer packets (JSON components). */
  public static final PacketCodec<TabListHeaderFooter> CODEC =
      new PacketCodec<>() {
        @Override
        public TabListHeaderFooter decode(ByteBuf buf, ProtocolVersion version) {
          return new TabListHeaderFooter(readComponent(buf), readComponent(buf));
        }

        @Override
        public void encode(TabListHeaderFooter packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.header());
          buf.writeBytes(packet.footer());
        }
      };

  /** Reads a JSON component with its length prefix, exactly as on the wire. */
  private static byte[] readComponent(ByteBuf buf) {
    int start = buf.readerIndex();
    McString.skip(buf);
    byte[] component = new byte[buf.readerIndex() - start];
    buf.getBytes(start, component);
    return component;
  }
}
