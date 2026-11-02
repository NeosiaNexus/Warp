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
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * Decrypts incoming Minecraft protocol data using AES/CFB8/NoPadding.
 *
 * <p>Minecraft encrypts the entire TCP stream (including VarInt frame lengths) using AES/CFB8 with
 * a 128-bit key. The IV is the shared secret itself (same 16 bytes used as both key and IV). This
 * handler must sit before {@link FrameDecoder} in the inbound pipeline.
 *
 * <h3>Implementation</h3>
 *
 * <p>Decrypts in place: a socket read hands its buffer to the pipeline alone, so the ciphertext is
 * overwritten with its plaintext and the same buffer is passed on, with no second buffer. A
 * read-only buffer, which cannot be overwritten, is decrypted into a new one instead. The cipher
 * runs through {@link AesCfb8}, which allocates nothing per read on any kind of buffer. Velocity's
 * Java cipher also decrypts in place, but needs a heap buffer, copies a direct read into a new one
 * first, and hands the JDK the same array as input and output, which the JDK then copies.
 *
 * @see CipherEncoder
 */
public final class CipherDecoder extends MessageToMessageDecoder<ByteBuf> {

  private final AesCfb8 cipher;

  /**
   * Creates a new cipher decoder.
   *
   * @param key the shared secret key (AES-128)
   * @throws GeneralSecurityException if the cipher cannot be initialised
   */
  public CipherDecoder(SecretKey key) throws GeneralSecurityException {
    this.cipher = new AesCfb8(Cipher.DECRYPT_MODE, key);
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
      throws ShortBufferException {
    int length = in.readableBytes();
    if (length == 0) {
      return;
    }
    int readerIndex = in.readerIndex();
    if (!in.isReadOnly()) {
      cipher.transform(in, readerIndex, in, readerIndex, length);
      out.add(in.retain()); // MessageToMessageDecoder releases its own reference after decode()
      return;
    }
    ByteBuf plain = ctx.alloc().buffer(length);
    try {
      cipher.transform(in, readerIndex, plain, 0, length);
    } catch (ShortBufferException | RuntimeException e) {
      plain.release();
      throw e;
    }
    in.skipBytes(length);
    out.add(plain.writerIndex(length));
  }
}
