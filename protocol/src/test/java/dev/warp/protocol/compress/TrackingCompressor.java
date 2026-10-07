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
package dev.warp.protocol.compress;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;

/** A {@link JavaCompressor} that counts how many times it is closed, for ownership tests. */
public final class TrackingCompressor implements PacketCompressor {

  private final JavaCompressor delegate = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
  private int closes;

  @Override
  public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
      throws DataFormatException {
    delegate.inflate(source, destination, uncompressedSize);
  }

  @Override
  public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
    delegate.deflate(source, destination);
  }

  @Override
  public void close() {
    closes++;
    delegate.close();
  }

  /**
   * Returns how many times {@link #close()} was called.
   *
   * @return the number of calls
   */
  public int closes() {
    return closes;
  }
}
