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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Methodology shared by every benchmark: the mode, the unit, the iterations and the forked JVM.
 *
 * <p>Benchmarks extend this class, and JMH reads these annotations from it, so no benchmark can run
 * in a JVM set up differently from the others, as Netty's own microbench module does. Where it
 * changes the code under test, each fork runs like {@code bin/warp.sh} runs the proxy: Netty keeps
 * {@code sun.misc.Unsafe} (Netty stops using it on Java 25 unless memory access is allowed) and
 * leak detection is off. Benchmark threads are Netty {@code FastThreadLocalThread}s, like event
 * loops ({@link EventLoopLikeExecutor}). The heap is fixed and pre-touched, which keeps resizing
 * and page faults out of the measurement.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(
    value = 3,
    jvmArgsAppend = {
      "-Xms2g",
      "-Xmx2g",
      "-XX:+AlwaysPreTouch",
      "--sun-misc-unsafe-memory-access=allow",
      "-Dio.netty.leakDetection.level=disabled",
      EventLoopLikeExecutor.JMH_EXECUTOR,
      EventLoopLikeExecutor.JMH_EXECUTOR_CLASS
    })
public abstract class AbstractMicrobenchmark {}
