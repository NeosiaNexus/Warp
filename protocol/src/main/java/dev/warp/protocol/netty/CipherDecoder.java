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
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

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
 * <p>Uses the {@link ByteBuffer}-based {@link Cipher#update(ByteBuffer, ByteBuffer)} API to work
 * directly with {@link ByteBuf#nioBuffer()}, avoiding intermediate {@code byte[]} copies on direct
 * buffers. This is the same zero-copy pattern used by {@link
 * dev.warp.protocol.compress.JavaCompressor JavaCompressor}.
 *
 * <h3>Improvement over Velocity</h3>
 *
 * <p>Velocity's {@code MinecraftCipherImpl} uses {@code Cipher.update(byte[], int, int, byte[],
 * int)} with manual array management. Warp uses the NIO {@link ByteBuffer} API for near-zero-copy
 * on direct buffers.
 */
public final class CipherDecoder extends MessageToMessageDecoder<ByteBuf> {

  private final Cipher cipher;

  /**
   * Creates a new cipher decoder.
   *
   * @param key the shared secret key (AES-128)
   * @throws GeneralSecurityException if the cipher cannot be initialised
   */
  public CipherDecoder(SecretKey key) throws GeneralSecurityException {
    this.cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    // The protocol fixes the IV to the shared secret itself, and every client does the same. The
    // secret is random and new for each connection, so no key and IV pair is ever reused.
    this.cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    int readable = in.readableBytes();
    if (readable == 0) {
      return;
    }

    ByteBuf output = ctx.alloc().directBuffer(readable);
    try {
      if (in.nioBufferCount() > 0) {
        // Zero-copy path for NIO-backed buffers (direct and most heap buffers).
        ByteBuffer source = in.nioBuffer();
        ByteBuffer dest = output.nioBuffer(0, readable);
        cipher.update(source, dest);
        output.writerIndex(dest.position());
      } else {
        // Fallback for composite or non-NIO buffers.
        byte[] sourceBytes = new byte[readable];
        in.readBytes(sourceBytes);
        byte[] decrypted = cipher.update(sourceBytes);
        output.writeBytes(decrypted);
      }
      in.skipBytes(in.readableBytes());

      out.add(output);
    } catch (Exception e) {
      output.release();
      throw e;
    }
  }
}
