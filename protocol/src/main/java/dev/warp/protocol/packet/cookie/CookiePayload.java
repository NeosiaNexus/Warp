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
package dev.warp.protocol.packet.cookie;

import dev.warp.protocol.codec.McByteArray;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * The payload of a cookie: a byte array of at most {@value #MAX_SIZE} bytes, the limit vanilla
 * enforces on both the stored and the returned cookie.
 */
public final class CookiePayload {

  /** Largest cookie payload, in bytes, that a vanilla client stores or a vanilla server accepts. */
  public static final int MAX_SIZE = 5120;

  private CookiePayload() {}

  /**
   * Returns {@code payload} if a vanilla peer accepts it.
   *
   * @param payload the payload to check
   * @return {@code payload}
   * @throws IllegalArgumentException if it is larger than {@value #MAX_SIZE} bytes
   */
  public static byte[] requireValid(byte[] payload) {
    if (payload.length > MAX_SIZE) {
      throw new IllegalArgumentException(
          "Cookie payload too large: " + payload.length + " bytes (max " + MAX_SIZE + ")");
    }
    return payload;
  }

  /**
   * Reads a payload, refusing one above {@value #MAX_SIZE} bytes before allocating it.
   *
   * @throws DecoderException if the length is negative, too large, or longer than the buffer
   */
  static byte[] read(ByteBuf buf) {
    return McByteArray.read(buf, MAX_SIZE);
  }

  /** Writes a payload already checked by {@link #requireValid}. */
  static void write(ByteBuf buf, byte[] payload) {
    McByteArray.write(buf, payload);
  }
}
