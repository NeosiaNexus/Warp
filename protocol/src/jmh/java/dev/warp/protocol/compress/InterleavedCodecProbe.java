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
package dev.warp.protocol.compress;

import dev.warp.protocol.bench.BenchmarkConfig;
import dev.warp.protocol.bench.PacketCorpus.Workload;
import dev.warp.protocol.compress.CodecCostBenchmark.Implementation;

import java.util.Arrays;
import java.util.Locale;

import io.netty.util.concurrent.FastThreadLocalThread;
import org.jspecify.annotations.Nullable;

/**
 * Times {@link CodecCostBenchmark}'s operations with the implementations interleaved: each round
 * runs one pass of every implementation, in a rotating order, so that whatever else the machine is
 * doing weighs on all of them alike. On a shared or virtualised machine, where JMH's sequential
 * trials drift by tens of percent from one to the next, this keeps the ratios between
 * implementations meaningful. JMH remains the reference for allocation and for absolute numbers on
 * a quiet machine.
 *
 * <p>Prints, per workload and operation, the median and minimum time per packet over the rounds,
 * and each implementation's median relative to Velocity's.
 *
 * <pre>
 * java -Xms2g -Xmx2g --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
 *     -cp protocol/build/libs/protocol-*-jmh.jar dev.warp.protocol.compress.InterleavedCodecProbe \
 *     [seconds per operation, default 40] [connections, default 1]
 * </pre>
 */
public final class InterleavedCodecProbe {

  private static final String[] OPERATIONS = {"deflate", "inflate", "encrypt", "decrypt"};

  /** Sum of every operation's result, printed so that no operation can be optimised away. */
  private static long checksum;

  private InterleavedCodecProbe() {}

  /**
   * Runs every workload and operation and prints a Markdown table.
   *
   * @param args seconds of measurement per workload and operation (default 40), and connections the
   *     packets are spread over (default 1, see {@link CodecCostBenchmark#connections})
   * @throws Exception if a codec fails
   */
  public static void main(String[] args) throws Exception {
    long budget = (args.length > 0 ? Long.parseLong(args[0]) : 40) * 1_000_000_000L;
    int connections = args.length > 1 ? Integer.parseInt(args[1]) : 1;
    Throwable[] failure = new Throwable[1];
    // A FastThreadLocalThread, like an event loop: Warp's per-thread contexts take their fast path.
    Thread thread =
        new FastThreadLocalThread(
            () -> {
              try {
                run(budget, connections);
              } catch (Throwable t) {
                failure[0] = t;
              }
            },
            "interleaved-probe");
    thread.start();
    thread.join();
    if (failure[0] != null) {
      throw new IllegalStateException(failure[0]);
    }
  }

  private static void run(long budget, int connections) throws Exception {
    System.out.println("Packets spread over " + connections + " connection(s).");
    System.out.println();
    Implementation[] implementations = Implementation.values();
    System.out.println(
        "| Workload | Operation | "
            + String.join(
                " | ",
                Arrays.stream(implementations).map(i -> i + " median (min)").toArray(String[]::new))
            + " | WARP_NATIVE / VELOCITY_NATIVE | rounds |");
    System.out.println("|---|---|" + "---|".repeat(implementations.length) + "---|---|");
    for (Workload workload : new Workload[] {Workload.CHUNK, Workload.MIXED, Workload.SMALL}) {
      CodecCostBenchmark[] states = new CodecCostBenchmark[implementations.length];
      for (int i = 0; i < implementations.length; i++) {
        states[i] = new CodecCostBenchmark();
        states[i].workload = workload;
        states[i].implementation = implementations[i];
        states[i].level = 6;
        states[i].connections = connections;
        states[i].setUp();
      }
      // The cipher is one per connection everywhere: only compression depends on connections.
      for (String operation : connections > 1 ? new String[] {"deflate", "inflate"} : OPERATIONS) {
        // Warm-up: a third of the budget, interleaved too.
        rounds(states, operation, budget / 3, null);
        long[][] times = new long[implementations.length][4096];
        int rounds = rounds(states, operation, budget, times);
        StringBuilder row = new StringBuilder("| " + workload + " | " + operation + " | ");
        double[] medians = new double[implementations.length];
        for (int i = 0; i < implementations.length; i++) {
          long[] sorted = Arrays.copyOf(times[i], rounds);
          Arrays.sort(sorted);
          medians[i] = sorted[rounds / 2] / (double) BenchmarkConfig.PACKETS;
          double min = sorted[0] / (double) BenchmarkConfig.PACKETS;
          row.append(String.format(Locale.ROOT, "%,.0f ns (%,.0f) | ", medians[i], min));
        }
        row.append(
            String.format(
                Locale.ROOT,
                "%.2f | %d |",
                medians[indexOf(implementations, Implementation.WARP_NATIVE)]
                    / medians[indexOf(implementations, Implementation.VELOCITY_NATIVE)],
                rounds));
        System.out.println(row);
      }
      for (CodecCostBenchmark state : states) {
        state.tearDown();
      }
    }
    System.out.println();
    System.out.println("(checksum " + checksum + ")");
  }

  private static int indexOf(Implementation[] implementations, Implementation implementation) {
    return Arrays.asList(implementations).indexOf(implementation);
  }

  /** Runs interleaved rounds for {@code budget} nanoseconds; returns how many were recorded. */
  private static int rounds(
      CodecCostBenchmark[] states, String operation, long budget, long @Nullable [][] times)
      throws Exception {
    long end = System.nanoTime() + budget;
    int round = 0;
    while (System.nanoTime() < end && (times == null || round < times[0].length)) {
      for (int k = 0; k < states.length; k++) {
        int i = (round + k) % states.length;
        long start = System.nanoTime();
        checksum += once(states[i], operation);
        long elapsed = System.nanoTime() - start;
        if (times != null) {
          times[i][round] = elapsed;
        }
      }
      round++;
    }
    return round;
  }

  private static long once(CodecCostBenchmark state, String operation) throws Exception {
    return switch (operation) {
      case "deflate" -> state.deflate();
      case "inflate" -> state.inflate();
      case "encrypt" -> state.encrypt();
      default -> state.decrypt();
    };
  }
}
