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
package dev.warp.protocol.bench;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * JMH executor whose worker threads are Netty {@code FastThreadLocalThread}s, like event loops.
 *
 * <p>Netty's {@code FastThreadLocal}, {@code Recycler} and pooled-allocator thread caches take
 * slower fallback paths on plain threads, so benchmarking pipeline code on JMH's default workers
 * would misrepresent production. This mirrors the harness executor of Netty's own microbench
 * module. {@link AbstractMicrobenchmark} enables it for every benchmark through the fork arguments
 * {@link #JMH_EXECUTOR} and {@link #JMH_EXECUTOR_CLASS}.
 */
public final class EventLoopLikeExecutor extends ThreadPoolExecutor {

  /** Fork JVM argument selecting a custom JMH executor. */
  public static final String JMH_EXECUTOR = "-Djmh.executor=CUSTOM";

  /** Fork JVM argument naming this class as the custom JMH executor. */
  public static final String JMH_EXECUTOR_CLASS =
      "-Djmh.executor.class=dev.warp.protocol.bench.EventLoopLikeExecutor";

  /**
   * Creates the executor; the signature is the one JMH requires for custom executors.
   *
   * @param maxThreads the number of benchmark threads
   * @param prefix the thread name prefix
   */
  public EventLoopLikeExecutor(int maxThreads, String prefix) {
    super(
        maxThreads,
        maxThreads,
        0,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        new DefaultThreadFactory(prefix));
  }
}
