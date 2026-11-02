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
import dev.warp.protocol.command.CommandTree;
import dev.warp.protocol.command.CommandTreeCodec;
import dev.warp.protocol.command.CommandTreeDecoding;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * Server declares the command tree the client completes and checks commands against ({@code S→C},
 * 1.13+), on joining, on every server switch, and again whenever a player's permissions change.
 *
 * <p>The packet is held as its raw payload, decoded only when asked: a vanilla operator's tree is
 * some 1500 nodes and 20 KiB, which the proxy has no reason to parse unless it adds or removes
 * commands. {@link #tree} decodes it, {@link #of} encodes an edited tree; a packet whose tree is
 * opaque is forwarded with its payload untouched (see {@link dev.warp.protocol.command}).
 *
 * @param payload the packet's bytes after its id
 */
public record DeclareCommands(byte[] payload) implements PlayPacket {

  /** Codec for the raw payload: decoding copies it, it never parses the tree. */
  public static final PacketCodec<DeclareCommands> CODEC =
      new PacketCodec<>() {
        @Override
        public DeclareCommands decode(ByteBuf buf, ProtocolVersion version) {
          byte[] payload = new byte[buf.readableBytes()];
          buf.readBytes(payload);
          return new DeclareCommands(payload);
        }

        @Override
        public void encode(DeclareCommands packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.payload());
        }
      };

  /**
   * Returns the packet of a tree, encoded for a protocol version.
   *
   * @param tree the command tree
   * @param version the protocol version of the connection
   * @return the packet
   * @throws IllegalArgumentException if the tree holds an argument type the version's clients do
   *     not know
   */
  public static DeclareCommands of(CommandTree tree, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      CommandTreeCodec.encode(tree, buf, version);
      return new DeclareCommands(ByteBufUtil.getBytes(buf));
    } finally {
      buf.release();
    }
  }

  /**
   * Decodes the tree this packet declares.
   *
   * @param version the protocol version of the connection
   * @return the tree, or why it is left opaque
   * @throws io.netty.handler.codec.DecoderException if the packet is malformed
   */
  public CommandTreeDecoding tree(ProtocolVersion version) {
    return CommandTreeCodec.decode(Unpooled.wrappedBuffer(payload), version);
  }
}
