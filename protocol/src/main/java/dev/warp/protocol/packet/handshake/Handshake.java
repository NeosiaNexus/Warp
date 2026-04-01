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
package dev.warp.protocol.packet.handshake;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * The initial handshake packet sent by the client ({@code C→S, ID 0x00}).
 *
 * <p>Declares the protocol version, target server, and intended next state (status or login). This
 * packet has been stable since Minecraft 1.7.2; the only addition is {@code nextState = 3}
 * (transfer) in 1.20.5.
 *
 * @param protocolVersion the client's protocol version number
 * @param serverAddress the hostname the client connected to (may contain FML markers)
 * @param serverPort the port the client connected to
 * @param nextState the intended next state (1 = status, 2 = login, 3 = transfer)
 */
public record Handshake(int protocolVersion, String serverAddress, int serverPort, int nextState)
    implements HandshakePacket {

  /** Maximum hostname length per protocol spec. */
  private static final int MAX_SERVER_ADDRESS = 255;

  /** Codec for reading and writing handshake packets. */
  public static final PacketCodec<Handshake> CODEC =
      new PacketCodec<>() {
        @Override
        public Handshake decode(ByteBuf buf, ProtocolVersion version) {
          int protocolVersion = VarInt.read(buf);
          String serverAddress = McString.read(buf, MAX_SERVER_ADDRESS);
          int serverPort = buf.readUnsignedShort();
          int nextState = VarInt.read(buf);
          return new Handshake(protocolVersion, serverAddress, serverPort, nextState);
        }

        @Override
        public void encode(Handshake packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.protocolVersion());
          McString.write(buf, packet.serverAddress(), MAX_SERVER_ADDRESS);
          buf.writeShort(packet.serverPort());
          VarInt.write(buf, packet.nextState());
        }
      };
}
