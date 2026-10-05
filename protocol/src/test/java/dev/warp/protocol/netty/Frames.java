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

import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.ZlibStreams;

import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/** Builds Minecraft wire frames byte by byte for tests. */
final class Frames {

  private Frames() {}

  /**
   * Returns {@code data} as a zlib stream, as vanilla's {@code Deflater} produces it.
   *
   * @param data the bytes to compress
   * @param level the zlib level
   * @return the compressed bytes
   */
  static byte[] zlib(byte[] data, int level) {
    return ZlibStreams.zlib(data, level, Deflater.DEFAULT_STRATEGY);
  }

  /**
   * Returns a complete uncompressed-connection frame: {@code [Length][packet]}.
   *
   * @param packet the packet bytes ({@code [id][payload]})
   * @return the frame
   */
  static ByteBuf plain(byte[] packet) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, packet.length);
    return frame.writeBytes(packet);
  }

  /**
   * Returns a complete compressed-connection frame carrying the packet uncompressed: {@code
   * [Length][0][packet]}.
   *
   * @param packet the packet bytes
   * @return the frame
   */
  static ByteBuf uncompressed(byte[] packet) {
    ByteBuf body = Unpooled.buffer();
    body.writeByte(0).writeBytes(packet);
    return framed(body);
  }

  /**
   * Returns a complete compressed-connection frame carrying the packet zlib-compressed: {@code
   * [Length][Data Length][zlib(packet)]}.
   *
   * @param packet the packet bytes
   * @param level the zlib level
   * @return the frame
   */
  static ByteBuf compressed(byte[] packet, int level) {
    return compressedClaiming(packet.length, zlib(packet, level));
  }

  /**
   * Returns a complete compressed-connection frame with an arbitrary declared size.
   *
   * @param dataLength the declared uncompressed size
   * @param zlib the compressed bytes
   * @return the frame
   */
  static ByteBuf compressedClaiming(int dataLength, byte[] zlib) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, dataLength);
    body.writeBytes(zlib);
    return framed(body);
  }

  /**
   * Prepends the length prefix to a frame body.
   *
   * @param body the frame body; released
   * @return the complete frame
   */
  static ByteBuf framed(ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  /**
   * Returns a packet: a one-byte packet id followed by {@code payload}.
   *
   * @param id the packet id (below 128)
   * @param payload the packet body
   * @return {@code [id][payload]}
   */
  static byte[] packet(int id, byte[] payload) {
    byte[] packet = new byte[payload.length + 1];
    packet[0] = (byte) id;
    System.arraycopy(payload, 0, packet, 1, payload.length);
    return packet;
  }

  /**
   * Returns the readable bytes of {@code buf} and releases it.
   *
   * @param buf the buffer
   * @return its readable bytes
   */
  static byte[] drain(ByteBuf buf) {
    try {
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }
}
