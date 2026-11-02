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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Natives")
class NativesTest {

  @Nested
  @DisplayName("when disabled")
  class Disabled {

    @Test
    @DisplayName("should report the switch and load nothing")
    void disabled() {
      // when
      NativeStatus status = Natives.probe(true);

      // then
      assertFalse(status.available());
      assertEquals("disabled by -Dwarp.natives.disable=true", status.detail());
      assertEquals("", status.advice(), "nothing to do: the operator chose this");
    }
  }

  @Nested
  @DisplayName("when available")
  class Available {

    @Test
    @DisplayName("should name the library, its platform and where it was loaded from")
    void describesTheLibrary() throws NativeLoadException {
      // given
      NativeAssumptions.requireNativeCompression();

      // when
      NativeStatus status = Natives.compression();

      // then
      assertTrue(status.available());
      assertTrue(status.detail().startsWith("libdeflate "), status.detail());
      assertTrue(status.detail().contains("(" + Platform.current().id() + ")"), status.detail());
      assertEquals("", status.advice(), "the tests run with native access enabled");
    }

    @Test
    @DisplayName("should pass its self-test again")
    void selfTest() {
      NativeAssumptions.requireNativeCompression();
      assertDoesNotThrow(Natives::selfTest);
    }

    @Test
    @DisplayName("should load once and answer the same afterwards")
    void once() {
      assertSame(Natives.compression(), Natives.compression());
      assertEquals(Natives.compression().available(), Natives.isNativeCompressionAvailable());
    }
  }
}
