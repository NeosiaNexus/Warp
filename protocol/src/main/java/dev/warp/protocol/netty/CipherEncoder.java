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

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Encrypts outgoing Minecraft protocol data using AES/CFB8/NoPadding.
 *
 * <p>Minecraft encrypts the entire TCP stream (including VarInt frame lengths) using AES/CFB8 with
 * a 128-bit key. The IV is the shared secret itself (same 16 bytes used as both key and IV). This
 * handler must sit after {@link FrameEncoder} (or {@link CompressionEncoder}) in the outbound
 * pipeline.
 *
 * <h3>Implementation</h3>
 *
 * <p>Each frame is encrypted into a new buffer of exactly its size from the channel's allocator,
 * direct as the socket wants it. The frame itself is never written to: a frame forwarded verbatim
 * can be a slice of a backend's read buffer, or go to several players, each with their own key. The
 * cipher runs through {@link AesCfb8}, which allocates nothing per frame on any kind of buffer,
 * where the JDK's {@code ByteBuffer} API allocates about twice the frame's size on direct ones.
 *
 * @see CipherDecoder
 */
public final class CipherEncoder extends MessageToByteEncoder<ByteBuf> {

  private final AesCfb8 cipher;

  /**
   * Creates a new cipher encoder.
   *
   * @param key the shared secret key (AES-128)
   * @throws GeneralSecurityException if the cipher cannot be initialised
   */
  public CipherEncoder(SecretKey key) throws GeneralSecurityException {
    this.cipher = new AesCfb8(Cipher.ENCRYPT_MODE, key);
  }

  @Override
  protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect) {
    // AES/CFB8 keeps the length, so the frame's size is exactly what the output needs.
    int size = msg.readableBytes();
    return preferDirect ? ctx.alloc().ioBuffer(size) : ctx.alloc().heapBuffer(size);
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out)
      throws ShortBufferException {
    int length = msg.readableBytes();
    if (length == 0) {
      return;
    }
    out.ensureWritable(length);
    int writerIndex = out.writerIndex();
    cipher.transform(msg, msg.readerIndex(), out, writerIndex, length);
    msg.skipBytes(length);
    out.writerIndex(writerIndex + length);
  }
}
