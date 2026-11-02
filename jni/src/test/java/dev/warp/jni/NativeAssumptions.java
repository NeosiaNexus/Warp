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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Skips a test that needs native libdeflate where it cannot load, unless the build requires it. */
final class NativeAssumptions {

  private NativeAssumptions() {}

  /**
   * Skips the calling test when native compression is unavailable, or fails it when the build ran
   * with {@code -Pwarp.natives.required=true} (as CI does), so that a broken native library can
   * never pass as a skipped test.
   */
  static void requireNativeCompression() {
    NativeStatus status = Natives.compression();
    if (Boolean.getBoolean("warp.natives.required")) {
      assertTrue(status.available(), () -> "Native compression is required: " + status.detail());
    } else {
      assumeTrue(status.available(), () -> "Native compression unavailable: " + status.detail());
    }
  }
}
