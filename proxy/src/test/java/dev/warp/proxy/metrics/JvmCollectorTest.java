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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JvmCollector")
class JvmCollectorTest {

  @Test
  @DisplayName("should expose memory, GC, threads and process metrics under the standard names")
  void standardNames() {
    MetricRegistry registry = new MetricRegistry(0);
    JvmCollector.install(registry);

    RecordingVisitor out = RecordingVisitor.of(registry);

    assertTrue(out.hasFamily("jvm_memory_used_bytes"));
    assertTrue(out.hasFamily("jvm_memory_max_bytes"));
    assertTrue(out.hasFamily("jvm_gc_collections_total"));
    assertTrue(out.hasFamily("jvm_gc_pause_seconds"));
    assertTrue(out.hasFamily("jvm_threads_live"));
    assertTrue(out.hasFamily("process_start_time_seconds"));
    assertTrue(out.hasFamily("process_cpu_seconds_total"));
    assertTrue(out.hasFamily("process_memory_limit_bytes"));
    assertTrue(out.hasFamily("jvm_info"));
    assertTrue(out.value("jvm_threads_live") >= 1);
    assertTrue(out.value("process_memory_limit_bytes") > 0);
    assertTrue(
        out.allSamples().stream()
            .anyMatch(
                sample ->
                    sample.name().equals("jvm_memory_used_bytes")
                        && sample.labelValues().contains("heap")
                        && sample.value() >= 0));
    assertFalse(out.samples().containsKey("warp_metrics_collector_errors_total{collector=jvm}"));
  }
}
