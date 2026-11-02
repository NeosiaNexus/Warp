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
package dev.warp.proxy.compression;

import dev.warp.jni.LibdeflateCodec;
import dev.warp.protocol.compress.PacketCompressor;

import java.util.zip.DataFormatException;

import io.netty.buffer.ByteBuf;

/**
 * {@link PacketCompressor} backed by the bundled libdeflate ({@link LibdeflateCodec}).
 *
 * <p>Holds no native memory of its own: the libdeflate contexts belong to the event loop thread
 * that calls it, and serve every connection on that thread. Closing it therefore frees nothing, and
 * a pipeline handler removed early (by a plugin, during a server switch) can never leave a freed
 * context behind for a packet still in flight, the use-after-free behind Paper's "Object already
 * disposed" errors (PaperMC/Paper#6546).
 *
 * <p>Buffer ownership is the caller's, as for every {@link PacketCompressor}: nothing is retained
 * or released, native code only touches the buffers during the call.
 */
public final class LibdeflateCompressor implements PacketCompressor {

  private final LibdeflateCodec codec;

  /**
   * Creates a compressor.
   *
   * @param codec the codec, which fixes the compression level
   */
  public LibdeflateCompressor(LibdeflateCodec codec) {
    this.codec = codec;
  }

  @Override
  public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
      throws DataFormatException {
    codec.inflate(source, destination, uncompressedSize);
  }

  @Override
  public void deflate(ByteBuf source, ByteBuf destination) {
    codec.deflate(source, destination);
  }

  @Override
  public int deflateBound(int uncompressedSize) {
    return codec.deflateBound(uncompressedSize);
  }

  /** Frees nothing: the native contexts belong to the event loop thread, not to this object. */
  @Override
  public void close() {}
}
