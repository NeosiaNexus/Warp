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
package dev.warp.jni;

/** Entry point for loading native libraries (libdeflate, OpenSSL). */
public final class Natives {

  private Natives() {}

  /**
   * Returns whether native compression (libdeflate) is available on this platform.
   *
   * @return {@code true} if native compression can be used
   */
  public static boolean isNativeCompressionAvailable() {
    return false; // TODO: implement native library loading
  }

  /**
   * Returns whether native crypto (OpenSSL/BoringSSL) is available on this platform.
   *
   * @return {@code true} if native crypto can be used
   */
  public static boolean isNativeCryptoAvailable() {
    return false; // TODO: implement native library loading
  }
}
