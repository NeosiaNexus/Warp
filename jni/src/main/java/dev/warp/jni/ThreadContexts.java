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

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.util.concurrent.FastThreadLocal;

/**
 * The libdeflate contexts of one thread: one compressor per level in use and one decompressor.
 *
 * <p>Minecraft compresses every packet on its own, so a context carries nothing from one packet to
 * the next, and one per thread serves every connection the thread handles. Velocity and Paper
 * allocate a compressor and a decompressor per connection instead: about 667 KiB of native memory
 * per player at level 6. Here memory depends on the number of event loops, not of players.
 *
 * <p><b>Lifecycle.</b> Contexts are allocated on a thread's first compression or decompression.
 * Netty event loop threads remove their {@link FastThreadLocal}s as they terminate, which frees the
 * contexts when the proxy shuts its event loops down. Any other thread's contexts are freed by a
 * {@link Cleaner} once the {@link Thread} object is collected. Either way, at most once.
 *
 * <p>Confined to its thread: never shared, so a context is never used by two threads at once.
 */
final class ThreadContexts {

  private static final Cleaner CLEANER =
      Cleaner.create(
          task -> {
            Thread thread = new Thread(task, "warp-libdeflate-cleaner");
            thread.setDaemon(true);
            return thread;
          });

  /** Contexts allocated and not yet freed, across threads. */
  private static final AtomicInteger LIVE = new AtomicInteger();

  private static final FastThreadLocal<ThreadContexts> CURRENT =
      new FastThreadLocal<>() {
        @Override
        protected ThreadContexts initialValue() {
          return new ThreadContexts();
        }

        @Override
        protected void onRemoval(ThreadContexts contexts) {
          contexts.cleanable.clean();
        }
      };

  private final Contexts contexts = new Contexts();
  private final Cleaner.Cleanable cleanable;

  private ThreadContexts() {
    // The thread, not this object, is the referent: this object stays reachable from the thread's
    // locals for as long as the thread lives.
    cleanable = CLEANER.register(Thread.currentThread(), contexts);
  }

  /**
   * Returns the calling thread's contexts.
   *
   * @return the contexts, confined to the calling thread
   */
  static ThreadContexts current() {
    return CURRENT.get();
  }

  /**
   * Returns the number of contexts allocated and not yet freed, across all threads.
   *
   * @return the live context count
   */
  static int live() {
    return LIVE.get();
  }

  /**
   * Returns this thread's compressor for {@code level}, allocating it on first use.
   *
   * @param level 0 to {@link LibdeflateCodec#MAX_LEVEL}
   * @return the compressor's address, owned by this object
   * @throws OutOfMemoryError if libdeflate cannot allocate it
   */
  long compressor(int level) {
    long compressor = contexts.compressors[level];
    if (compressor == 0) {
      compressor = Libdeflate.allocCompressor(level);
      if (compressor == 0) {
        throw new OutOfMemoryError(
            "libdeflate could not allocate a level " + level + " compressor");
      }
      contexts.compressors[level] = compressor;
      LIVE.incrementAndGet();
    }
    return compressor;
  }

  /**
   * Returns this thread's decompressor, allocating it on first use.
   *
   * @return the decompressor's address, owned by this object
   * @throws OutOfMemoryError if libdeflate cannot allocate it
   */
  long decompressor() {
    long decompressor = contexts.decompressor;
    if (decompressor == 0) {
      decompressor = Libdeflate.allocDecompressor();
      if (decompressor == 0) {
        throw new OutOfMemoryError("libdeflate could not allocate a decompressor");
      }
      contexts.decompressor = decompressor;
      LIVE.incrementAndGet();
    }
    return decompressor;
  }

  /**
   * The native contexts themselves, and the action that frees them. Holds no reference to the
   * thread or to {@link ThreadContexts}, as a {@link Cleaner} action must not. The cleaner reads
   * these fields only after the owning thread has died and been collected.
   */
  private static final class Contexts implements Runnable {
    final long[] compressors = new long[LibdeflateCodec.MAX_LEVEL + 1];
    long decompressor;

    @Override
    public void run() {
      for (int level = 0; level < compressors.length; level++) {
        if (compressors[level] != 0) {
          Libdeflate.freeCompressor(compressors[level]);
          compressors[level] = 0;
          LIVE.decrementAndGet();
        }
      }
      if (decompressor != 0) {
        Libdeflate.freeDecompressor(decompressor);
        decompressor = 0;
        LIVE.decrementAndGet();
      }
    }
  }
}
