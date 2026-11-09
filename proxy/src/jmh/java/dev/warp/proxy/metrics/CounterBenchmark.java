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
package dev.warp.proxy.metrics;

import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Histogram;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The cost of one increment on the hot path: a striped counter child on a thread with a slot (what
 * an event loop pays per packet), against the same child without a slot (the shared adder), a bare
 * {@link LongAdder} and an {@link AtomicLong}, with 4 threads contending.
 *
 * <pre>
 * ./gradlew :proxy:jmh -Pjmh.includes=CounterBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class CounterBenchmark {

  private Counter.Child striped;
  private Counter.Child unstriped;
  private Histogram.Child histogram;
  private final LongAdder adder = new LongAdder();
  private final AtomicLong atomic = new AtomicLong();

  /** Registers the children once, as a handler does. */
  @Setup
  public void setUp() {
    MetricRegistry registry = new MetricRegistry(8);
    striped = registry.counter("warp_bench_total", "Bench.").labels();
    unstriped = new MetricRegistry(0).counter("warp_bench_total", "Bench.").labels();
    histogram = registry.histogram("warp_bench_seconds", "Bench.", Buckets.NETWORK).labels();
  }

  /** Each benchmark thread takes a slot of its own, as an event loop does. */
  @State(Scope.Thread)
  public static class Slot {
    private static final AtomicLong NEXT = new AtomicLong();

    /** Takes the next slot. */
    @Setup
    public void setUp() {
      LoopIndex.assign((int) (NEXT.getAndIncrement() % 8));
    }
  }

  /**
   * One increment on a thread with a slot: the event loop case.
   *
   * @param slot the thread's slot, assigned once
   */
  @Benchmark
  public void stripedInc(Slot slot) {
    striped.inc();
  }

  /**
   * One histogram observation on a thread with a slot.
   *
   * @param slot the thread's slot, assigned once
   */
  @Benchmark
  public void stripedObserve(Slot slot) {
    histogram.observeNanos(1_500_000);
  }

  /** One increment through the shared adder: the case without a slot. */
  @Benchmark
  public void sharedAdderInc() {
    unstriped.inc();
  }

  /** A bare LongAdder, for comparison. */
  @Benchmark
  public void longAdderInc() {
    adder.increment();
  }

  /** A bare AtomicLong, for comparison. */
  @Benchmark
  public void atomicLongInc() {
    atomic.incrementAndGet();
  }
}
