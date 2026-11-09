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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Gauge;
import dev.warp.api.metrics.Histogram;
import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.Metrics;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MetricRegistry")
class MetricRegistryTest {

  private final MetricRegistry registry = new MetricRegistry(4);

  @Nested
  @DisplayName("registration")
  class Registration {

    @Test
    @DisplayName("should return the same metric for the same name, type and labels")
    void idempotent() {
      Counter first = registry.counter("warp_a_total", "A.", "kind");
      Counter second = registry.counter("warp_a_total", "A.", "kind");

      assertSame(first, second);
      assertSame(first.labels("x"), second.labels("x"));
    }

    @Test
    @DisplayName("should refuse a name registered as another type or with other labels")
    void conflicts() {
      registry.counter("warp_a_total", "A.", "kind");
      registry.gauge("warp_g", "G.");
      registry.histogram("warp_h_seconds", "H.", Buckets.FAST);

      assertThrows(
          IllegalArgumentException.class, () -> registry.counter("warp_a_total", "A.", "other"));
      assertThrows(IllegalArgumentException.class, () -> registry.gauge("warp_g", "G.", "kind"));
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.histogram("warp_h_seconds", "H.", Buckets.LONG));
      assertThrows(IllegalArgumentException.class, () -> registry.gauge("warp_g", "G.", () -> 1));
      assertThrows(
          IllegalArgumentException.class, () -> registry.counter("warp_a_total", "A.", "k", "k"));
    }

    @Test
    @DisplayName("should enforce the name and label rules")
    void rules() {
      assertThrows(IllegalArgumentException.class, () -> registry.counter("warp_a", "A."));
      assertThrows(IllegalArgumentException.class, () -> registry.gauge("warp_g", "G.", "player"));
      assertThrows(IllegalArgumentException.class, () -> registry.gauge("warp_lag_ms", "G."));
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.counter("warp_a_total", "A.", "k").labels("x", "y"));
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.counter("warp_b_total", "B.", "k").labels(""));
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.counter("warp_c_total", "C.").labels().add(-1));
    }

    @Test
    @DisplayName("should refuse a collector id twice")
    void collectors() {
      registry.collector("warp_x", out -> {});

      assertThrows(IllegalArgumentException.class, () -> registry.collector("warp_x", out -> {}));
    }
  }

  @Nested
  @DisplayName("values")
  class Values {

    @Test
    @DisplayName("should count, set and observe, and collect sorted by name and labels")
    void collect() {
      Counter counter = registry.counter("warp_b_total", "B.", "kind");
      counter.labels("z").add(5);
      counter.labels("a").inc();
      Gauge gauge = registry.gauge("warp_a", "A.", "state");
      gauge.labels("play").set(3);
      gauge.labels("play").inc();
      gauge.labels("login").dec();
      registry.gauge("warp_pull", "P.", () -> 42.5);
      Histogram histogram = registry.histogram("warp_c_seconds", "C.", Buckets.of(0.1, 1));
      histogram.labels().observe(0.05);
      histogram.labels().observe(0.5);
      histogram.labels().observe(7);
      histogram.labels().observeNanos(50_000_000);
      histogram.labels().observe(Double.NaN);

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(
          List.of(
              "warp_a",
              "warp_b_total",
              "warp_c_seconds",
              "warp_metrics_collector_errors_total",
              "warp_metrics_label_overflow_total",
              "warp_pull"),
          out.families().stream().map(RecordingVisitor.Family::name).toList());
      assertEquals(-1, out.value("warp_a{state=login}"));
      assertEquals(4, out.value("warp_a{state=play}"));
      assertEquals(1, out.value("warp_b_total{kind=a}"));
      assertEquals(5, out.value("warp_b_total{kind=z}"));
      assertEquals(42.5, out.value("warp_pull"));
      assertEquals(2, out.value("warp_c_seconds_bucket{le=0.1}"));
      assertEquals(3, out.value("warp_c_seconds_bucket{le=1}"));
      assertEquals(4, out.value("warp_c_seconds_bucket{le=+Inf}"));
      assertEquals(4, out.value("warp_c_seconds_count"));
      assertEquals(7.6, out.value("warp_c_seconds_sum"), 1e-9);
      assertEquals(4, histogram.labels().count());
      assertEquals(6, counter.labels("a").value() + counter.labels("z").value());
      assertEquals(MetricType.HISTOGRAM, out.families().get(2).type());
    }

    @Test
    @DisplayName("should run collectors in isolation and count the ones that fail")
    void failingCollector() {
      registry.collector(
          "warp_bad",
          out -> {
            throw new IllegalStateException("boom");
          });
      registry.collector(
          "warp_good",
          out -> {
            out.family("warp_good", MetricType.GAUGE, "Good.");
            out.sample("warp_good", List.of(), List.of(), 1);
          });

      RecordingVisitor first = RecordingVisitor.of(registry);
      RecordingVisitor second = RecordingVisitor.of(registry);

      assertEquals(1, first.value("warp_good"));
      assertEquals(1, first.value("warp_metrics_collector_errors_total{collector=warp_bad}"));
      assertEquals(2, second.value("warp_metrics_collector_errors_total{collector=warp_bad}"));
    }
  }

  @Nested
  @DisplayName("cardinality")
  class Cardinality {

    @Test
    @DisplayName("should collapse a plugin label's 33rd value to other and count the overflow")
    void labelValues() {
      Metrics plugin = registry.scoped("demo");
      Counter counter = plugin.counter("lookups_total", "L.", "kind");
      for (int i = 0; i < MetricRegistry.MAX_LABEL_VALUES + 5; i++) {
        counter.labels("v" + i).inc();
      }

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(5, out.value("demo_lookups_total{kind=other}"));
      assertEquals(1, out.value("demo_lookups_total{kind=v0}"));
      assertFalse(out.samples().containsKey("demo_lookups_total{kind=v40}"));
      assertEquals(5, out.value("warp_metrics_label_overflow_total{metric=demo_lookups_total}"));
      assertSame(counter.labels("v99"), counter.labels("v98"));
    }

    @Test
    @DisplayName("should cap a plugin at 2000 series and share one other series beyond")
    void seriesCap() {
      Metrics plugin = registry.scoped("big");
      Counter counter = plugin.counter("rows_total", "R.", "a", "b", "c");
      String[] values = new String[13]; // 13^3 = 2197 combinations, over the cap
      for (int i = 0; i < values.length; i++) {
        values[i] = "v" + i;
      }
      int created = 0;
      for (String a : values) {
        for (String b : values) {
          for (String c : values) {
            counter.labels(a, b, c).inc();
            created++;
          }
        }
      }
      Counter.Child overflow = counter.labels("v0", "v1", "v2");
      overflow.inc();

      RecordingVisitor out = RecordingVisitor.of(registry);
      long series =
          out.allSamples().stream().filter(s -> s.name().equals("big_rows_total")).count();

      assertEquals(13 * 13 * 13, created);
      assertTrue(series <= MetricRegistry.MAX_PLUGIN_SERIES + 1, "series " + series);
      assertTrue(series >= MetricRegistry.MAX_PLUGIN_SERIES, "series " + series);
      assertTrue(out.samples().containsKey("big_rows_total{a=other,b=other,c=other}"));
      assertEquals(
          created + 1,
          out.allSamples().stream()
              .filter(s -> s.name().equals("big_rows_total"))
              .mapToDouble(RecordingVisitor.Sample::value)
              .sum());
    }

    @Test
    @DisplayName("should give the proxy's own metrics a higher label cap")
    void proxyCap() {
      Gauge versions = registry.gauge("warp_versions", "V.", "version");
      for (int i = 0; i < 100; i++) {
        versions.labels(Integer.toString(i)).set(1);
      }

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(1, out.value("warp_versions{version=99}"));
      assertFalse(out.samples().containsKey("warp_versions{version=other}"));
    }
  }

  @Nested
  @DisplayName("plugin scope")
  class Scope {

    @Test
    @DisplayName("should prefix names with the plugin id and refuse the proxy's prefixes")
    void prefix() {
      ScopedMetrics plugin = registry.scoped("My-Plugin");
      Counter counter = plugin.counter("lookups_total", "L.");

      assertEquals("my_plugin_lookups_total", counter.name());
      assertEquals("my_plugin_", plugin.prefix());
      assertThrows(IllegalArgumentException.class, () -> plugin.gauge("warp_players", "P."));
      assertThrows(IllegalArgumentException.class, () -> plugin.gauge("jvm_x", "P."));
      assertThrows(
          IllegalArgumentException.class, () -> registry.scoped("warp").counter("x_total", "X."));
    }

    @Test
    @DisplayName("should remove everything the plugin registered when closed")
    void close() {
      ScopedMetrics plugin = registry.scoped("demo");
      plugin.counter("a_total", "A.").labels().inc();
      plugin.gauge("b", "B.", () -> 1);
      plugin.collector(
          "c",
          out -> {
            out.family("demo_c", MetricType.GAUGE, "C.");
            out.sample("demo_c", List.of(), List.of(), 1);
          });
      assertTrue(RecordingVisitor.of(registry).hasFamily("demo_a_total"));
      assertTrue(RecordingVisitor.of(registry).hasFamily("demo_c"));

      plugin.close();
      RecordingVisitor out = RecordingVisitor.of(registry);

      assertFalse(plugin.isActive());
      assertFalse(out.hasFamily("demo_a_total"));
      assertFalse(out.hasFamily("demo_b"));
      assertFalse(out.hasFamily("demo_c"));
      assertThrows(IllegalStateException.class, () -> plugin.counter("d_total", "D."));
      // A reloaded plugin registers afresh.
      assertEquals("demo_a_total", registry.scoped("demo").counter("a_total", "A.").name());
    }

    @Test
    @DisplayName("should skip a plugin collector that writes outside its prefix")
    void collectorPrefix() {
      ScopedMetrics plugin = registry.scoped("demo");
      plugin.collector(
          "c",
          out -> {
            out.family("warp_fake", MetricType.GAUGE, "Fake.");
            out.sample("warp_fake", List.of(), List.of(), 1);
          });

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertFalse(out.hasFamily("warp_fake"));
      assertEquals(1, out.value("warp_metrics_collector_errors_total{collector=demo_c}"));
    }
  }
}
