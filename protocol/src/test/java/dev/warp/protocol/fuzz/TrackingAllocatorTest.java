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
package dev.warp.protocol.fuzz;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrackingAllocator")
class TrackingAllocatorTest {

  @Nested
  @DisplayName("largest capacity")
  class LargestCapacity {

    @Test
    @DisplayName("should count a buffer at its initial capacity")
    void initial() {
      TrackingAllocator alloc = new TrackingAllocator();
      alloc.heapBuffer(100).release();
      alloc.directBuffer(40).release();
      assertEquals(100, alloc.largestCapacity());
    }

    @Test
    @DisplayName("should count a heap or direct buffer grown by writes past its capacity")
    void grownByWrites() {
      for (boolean direct : new boolean[] {false, true}) {
        TrackingAllocator alloc = new TrackingAllocator();
        ByteBuf buf = direct ? alloc.directBuffer(16) : alloc.heapBuffer(16);
        buf.writeZero(1000);
        assertEquals(buf.capacity(), alloc.largestCapacity(), direct ? "direct" : "heap");
        buf.release();
      }
    }

    @Test
    @DisplayName("should keep the largest capacity once a buffer shrinks")
    void shrunk() {
      TrackingAllocator alloc = new TrackingAllocator();
      ByteBuf buf = alloc.buffer(16);
      buf.capacity(4096).capacity(8);
      assertEquals(4096, alloc.largestCapacity());
      buf.release();
    }
  }

  @Nested
  @DisplayName("assertAllReleased")
  class AssertAllReleased {

    @Test
    @DisplayName("should pass once every buffer is released")
    void released() {
      TrackingAllocator alloc = new TrackingAllocator();
      alloc.buffer(8).release();
      assertDoesNotThrow(alloc::assertAllReleased);
    }

    @Test
    @DisplayName("should fail while a buffer is still referenced")
    void leaked() {
      TrackingAllocator alloc = new TrackingAllocator();
      ByteBuf buf = alloc.buffer(8);
      assertThrows(AssertionError.class, alloc::assertAllReleased);
      buf.release();
    }
  }
}
