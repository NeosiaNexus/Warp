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
package dev.warp.protocol.legacy;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Writes a {@link LegacyDisconnect} as the legacy kick packet: {@code 0xFF}, the text's length in
 * UTF-16 code units as a big-endian short, then the text in UTF-16BE. No frame length: it sits
 * nearer the network than the frame encoder, which never sees it.
 *
 * <p>Stateless and {@link ChannelHandler.Sharable}: use {@link #INSTANCE}. {@link
 * LegacyPingDecoder} removes it from the pipeline, with itself, as soon as the connection proves to
 * speak the framed protocol, so that it costs the packets of a session nothing.
 */
@ChannelHandler.Sharable
public final class LegacyDisconnectEncoder extends MessageToByteEncoder<LegacyDisconnect> {

  /** Shared singleton: the encoder is stateless. */
  public static final LegacyDisconnectEncoder INSTANCE = new LegacyDisconnectEncoder();

  /** The legacy kick packet's identifier. */
  static final int KICK = 0xFF;

  private LegacyDisconnectEncoder() {}

  @Override
  protected void encode(ChannelHandlerContext ctx, LegacyDisconnect msg, ByteBuf out) {
    String reason = msg.reason();
    out.ensureWritable(1 + 2 + reason.length() * 2);
    out.writeByte(KICK);
    out.writeShort(reason.length());
    out.writeCharSequence(reason, StandardCharsets.UTF_16BE);
  }
}
