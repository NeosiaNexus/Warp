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

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;

/**
 * An allocator that keeps every buffer it hands out, so that a fuzz test can check that the code
 * under test released them all, and never asked for more memory than its input justifies.
 *
 * <p>Buffers are unpooled: a leaked buffer is reported by {@link #assertAllReleased()}, never
 * recycled into another test.
 */
public final class TrackingAllocator extends AbstractByteBufAllocator {

  private final List<ByteBuf> buffers = new ArrayList<>();
  private int largestRequest;

  /** Creates an allocator that prefers direct buffers, as Netty's default allocator does. */
  public TrackingAllocator() {
    super(true);
  }

  @Override
  protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
    return track(new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity));
  }

  @Override
  protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
    return track(new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity));
  }

  @Override
  public boolean isDirectBufferPooled() {
    return false;
  }

  /**
   * Returns the largest initial capacity requested so far: what the code under test allocated up
   * front, before it read the bytes that would fill it.
   *
   * @return the largest request, in bytes
   */
  public int largestRequest() {
    return largestRequest;
  }

  /**
   * Asserts that every buffer allocated so far has been released.
   *
   * @throws AssertionError listing the buffers still referenced
   */
  public void assertAllReleased() {
    List<ByteBuf> leaked = buffers.stream().filter(buf -> buf.refCnt() > 0).toList();
    if (!leaked.isEmpty()) {
      throw new AssertionError(
          leaked.size() + " of " + buffers.size() + " buffers not released: " + leaked);
    }
  }

  private ByteBuf track(ByteBuf buf) {
    buffers.add(buf);
    largestRequest = Math.max(largestRequest, buf.capacity());
    return buf;
  }
}
