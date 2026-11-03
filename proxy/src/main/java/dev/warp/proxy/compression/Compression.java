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
import dev.warp.jni.NativeStatus;
import dev.warp.jni.Natives;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.PacketCompressor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The zlib implementation behind every connection's packet compression, chosen once per JVM:
 * libdeflate, bundled and called through the FFM API, when it loads and passes its self-test, else
 * {@code java.util.zip}. Both produce and accept the same zlib streams, apply the same checks and
 * fail the same frames, so the choice only changes speed and memory.
 *
 * <p>{@code -Dwarp.natives.disable=true} forces {@code java.util.zip}.
 */
public final class Compression {

  private static final Logger logger = LoggerFactory.getLogger(Compression.class);

  private Compression() {}

  /**
   * Creates the compressor of one pipeline handler.
   *
   * @param level the zlib level, -1 (default, 6) to 9
   * @return a native compressor if libdeflate is available, a {@link JavaCompressor} otherwise
   */
  public static PacketCompressor create(int level) {
    return create(level, Natives.isNativeCompressionAvailable());
  }

  /**
   * Creates the compressor of one pipeline handler with the given implementation.
   *
   * @param level the zlib level, -1 (default, 6) to 9
   * @param nativeAvailable whether native libdeflate passed its self-test
   * @return a native compressor if {@code nativeAvailable}, a {@link JavaCompressor} otherwise
   */
  static PacketCompressor create(int level, boolean nativeAvailable) {
    return nativeAvailable
        ? new LibdeflateCompressor(LibdeflateCodec.create(level))
        : new JavaCompressor(level);
  }

  /**
   * Loads and tests the native library now, then logs which implementation compresses packets, and
   * why when it is not the native one. Called once at startup, before any connection, so that the
   * log says what will actually run.
   */
  public static void logImplementation() {
    NativeStatus status = Natives.compression();
    if (status.available()) {
      logger.debug("Compression: {}", status.detail());
      if (!status.advice().isEmpty()) {
        logger.warn("{}.", status.advice());
      }
    } else if (status.advice().isEmpty()) {
      logger.debug("Compression: java.util.zip (native libdeflate {})", status.detail());
    } else {
      logger.warn(
          "Compression: java.util.zip, which is slower, because native libdeflate is"
              + " unavailable: {}. {}. To keep java.util.zip without this warning, start Warp"
              + " with -D{}=true.",
          status.detail(),
          status.advice(),
          Natives.DISABLE_PROPERTY);
    }
  }
}
