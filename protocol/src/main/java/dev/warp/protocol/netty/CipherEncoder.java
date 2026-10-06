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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

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
 * <p>Uses the {@link ByteBuffer}-based {@link Cipher#update(ByteBuffer, ByteBuffer)} API to work
 * directly with {@link ByteBuf#nioBuffer()}, avoiding intermediate {@code byte[]} copies on direct
 * buffers. AES/CFB8 is a streaming cipher — each call to {@code update()} produces exactly the same
 * number of output bytes as input bytes.
 *
 * @see CipherDecoder
 */
public final class CipherEncoder extends MessageToByteEncoder<ByteBuf> {

  private final Cipher cipher;

  /**
   * Creates a new cipher encoder.
   *
   * @param key the shared secret key (AES-128)
   * @throws GeneralSecurityException if the cipher cannot be initialised
   */
  public CipherEncoder(SecretKey key) throws GeneralSecurityException {
    this.cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    // The protocol fixes the IV to the shared secret itself, and every client does the same. The
    // secret is random and new for each connection, so no key and IV pair is ever reused.
    this.cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
    int readable = msg.readableBytes();
    if (readable == 0) {
      return;
    }

    out.ensureWritable(readable);

    if (msg.nioBufferCount() > 0) {
      // Zero-copy path for NIO-backed buffers.
      ByteBuffer source = msg.nioBuffer();
      ByteBuffer dest = out.nioBuffer(out.writerIndex(), readable);
      cipher.update(source, dest);
      out.writerIndex(out.writerIndex() + dest.position());
    } else {
      // Fallback for composite or non-NIO buffers.
      byte[] sourceBytes = new byte[readable];
      msg.readBytes(sourceBytes);
      byte[] encrypted = cipher.update(sourceBytes);
      out.writeBytes(encrypted);
    }
  }
}
