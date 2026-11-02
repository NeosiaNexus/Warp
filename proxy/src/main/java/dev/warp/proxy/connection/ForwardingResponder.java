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
package dev.warp.proxy.connection;

import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;

import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Answers a backend's Velocity modern forwarding query on a backend connection with plugin packet
 * handlers, below those handlers, in the backend's own login protocol.
 *
 * <p>Player info forwarding is an exchange between the proxy and the backend: the player takes no
 * part in it. A translator between the two speaks the player's version on the proxy's side, and a
 * client older than 1.13 has no login query at all: ViaBackwards drops the backend's query and
 * answers it as a 1.12 client would, with "not understood", and the backend kicks the player
 * (PaperMC/Velocity#1700). Velocity's answer, with ViaVersion, is to translate such players up to
 * 1.13 on the client side as well, so that they reach Velocity's own handler, and then reports them
 * as 1.13 players (PaperMC/Velocity#1430).
 *
 * <p>This handler sits right after the {@code PacketDecompressor}, ahead of every plugin handler,
 * where packets are still the backend's own. The login query ({@code 0x04}) and its answer ({@code
 * 0x02}) have kept their ids and format since 1.13, which introduced them, so they read the same
 * whatever version the backend runs. It answers the {@code velocity:player_info} query from its own
 * context, so the answer goes to the backend past no plugin handler, passes everything else on, and
 * removes itself at Login Success.
 */
final class ForwardingResponder extends ChannelInboundHandlerAdapter {

  /** The handler's name in the pipeline. */
  static final String NAME = "forwarding-responder";

  /** Clientbound Login Success, since 1.7. */
  private static final int LOGIN_SUCCESS = 0x02;

  /** Clientbound login query (Login Plugin Request), since 1.13. */
  private static final int LOGIN_QUERY = 0x04;

  /** Serverbound login query answer (Login Plugin Response), since 1.13. */
  private static final int LOGIN_QUERY_ANSWER = 0x02;

  /** The longest channel name the protocol carries. */
  private static final int MAX_CHANNEL_LENGTH = 32_767;

  private final Supplier<byte[]> forwardingData;

  /**
   * Creates the responder.
   *
   * @param forwardingData builds the signed forwarding payload, when the backend asks for it
   */
  ForwardingResponder(Supplier<byte[]> forwardingData) {
    this.forwardingData = forwardingData;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof ByteBuf packet && answered(ctx, packet)) {
      return;
    }
    ctx.fireChannelRead(msg);
  }

  /**
   * Answers the packet if it is the forwarding query.
   *
   * @return {@code true} if it was, and was consumed; {@code false} to pass it on unchanged
   */
  private boolean answered(ChannelHandlerContext ctx, ByteBuf packet) {
    int start = packet.readerIndex();
    try {
      int id = VarInt.read(packet);
      if (id == LOGIN_SUCCESS) {
        // The login is over: nothing more to answer on this connection.
        ctx.pipeline().remove(this);
        return false;
      }
      if (id != LOGIN_QUERY) {
        return false;
      }
      int messageId = VarInt.read(packet);
      String channel = McString.read(packet, MAX_CHANNEL_LENGTH);
      if (!PlayerForwarding.VELOCITY_CHANNEL.equals(channel)) {
        return false;
      }
      byte[] data = forwardingData.get();
      ByteBuf answer = ctx.alloc().buffer(data.length + 8);
      VarInt.write(answer, LOGIN_QUERY_ANSWER);
      VarInt.write(answer, messageId);
      answer.writeBoolean(true);
      answer.writeBytes(data);
      var _ = ctx.writeAndFlush(answer);
      packet.release();
      return true;
    } finally {
      if (packet.refCnt() > 0) {
        packet.readerIndex(start);
      }
    }
  }
}
