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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What JMH itself allocates in one measurement iteration, measured by a benchmark that allocates
 * nothing.
 *
 * <p>The GC profiler counts the bytes every thread allocated during an iteration and divides them
 * by the operations the iteration ran. JMH's own bookkeeping around an iteration allocates a fixed
 * amount (about 7 KiB on JMH 1.37, whatever the iteration's length), so a benchmark that allocates
 * nothing still reads as {@code 7 KiB / operations}: nothing for a benchmark that runs millions of
 * operations per iteration, several bytes for one that runs a thousand, and a different number on a
 * faster or slower machine. The allocation guard ({@code bin/bench-guard.sh}) runs this benchmark
 * next to the guarded ones and subtracts from each its share of that fixed amount, which leaves
 * what the code under test allocates per operation.
 *
 * <p>It extends {@link AbstractMicrobenchmark} like every benchmark, so it runs in the same JVM, on
 * the same executor and under the same profiler as those it calibrates.
 */
public class HarnessAllocationBenchmark extends AbstractMicrobenchmark {

  /** Work per operation: enough that an iteration runs a moderate, machine-dependent count. */
  private static final long TOKENS = 1_000;

  /** Burns CPU without allocating: every byte the profiler counts here is the harness's. */
  @Benchmark
  public void idle() {
    Blackhole.consumeCPU(TOKENS);
  }
}
