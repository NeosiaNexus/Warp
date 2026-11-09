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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.MetricType;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PrometheusText")
class PrometheusTextTest {

  private MetricRegistry registry() {
    MetricRegistry registry = new MetricRegistry(0);
    registry.counter("warp_logins_total", "Login attempts.", "outcome").labels("success").add(7);
    registry.gauge("warp_players_online", "Players in PLAY.", () -> 3);
    registry.histogram("warp_lag_seconds", "Lag.", Buckets.of(0.001, 0.01)).labels().observe(0.005);
    registry
        .gauge("warp_quoted", "Help with a \\ backslash\nand a newline.", "value")
        .labels("a \"b\" \\ c\nd")
        .set(1.5);
    registry.collector(
        "warp_summary",
        out -> {
          out.family("warp_wait_seconds", MetricType.SUMMARY, "Waits.");
          out.sample("warp_wait_seconds_sum", List.of(), List.of(), 0.25);
          out.sample("warp_wait_seconds_count", List.of(), List.of(), 2);
        });
    return registry;
  }

  @Nested
  @DisplayName("text format 0.0.4")
  class Text {

    @Test
    @DisplayName("should write HELP, TYPE and samples, escaped, in a stable order")
    void golden() {
      String text = PrometheusText.write(registry(), PrometheusText.Format.TEXT, null);

      assertEquals(
          """
          # HELP warp_wait_seconds Waits.
          # TYPE warp_wait_seconds summary
          warp_wait_seconds_sum 0.25
          warp_wait_seconds_count 2
          # HELP warp_lag_seconds Lag.
          # TYPE warp_lag_seconds histogram
          warp_lag_seconds_bucket{le="0.001"} 0
          warp_lag_seconds_bucket{le="0.01"} 1
          warp_lag_seconds_bucket{le="+Inf"} 1
          warp_lag_seconds_sum 0.005
          warp_lag_seconds_count 1
          # HELP warp_logins_total Login attempts.
          # TYPE warp_logins_total counter
          warp_logins_total{outcome="success"} 7
          # HELP warp_metrics_collector_errors_total Scrapes a collector failed and was skipped from.
          # TYPE warp_metrics_collector_errors_total counter
          # HELP warp_metrics_label_overflow_total Label values recorded as 'other' because the metric had too many distinct values.
          # TYPE warp_metrics_label_overflow_total counter
          # HELP warp_players_online Players in PLAY.
          # TYPE warp_players_online gauge
          warp_players_online 3
          # HELP warp_quoted Help with a \\\\ backslash\\nand a newline.
          # TYPE warp_quoted gauge
          warp_quoted{value="a \\"b\\" \\\\ c\\nd"} 1.5
          """,
          text);
    }

    @Test
    @DisplayName("should keep only the families with the filter's prefix")
    void filter() {
      String text = PrometheusText.write(registry(), PrometheusText.Format.TEXT, "warp_players_");

      assertEquals(
          """
          # HELP warp_players_online Players in PLAY.
          # TYPE warp_players_online gauge
          warp_players_online 3
          """,
          text);
    }
  }

  @Nested
  @DisplayName("OpenMetrics")
  class OpenMetrics {

    @Test
    @DisplayName("should name counter families without _total and end with EOF")
    void counters() {
      String text =
          PrometheusText.write(registry(), PrometheusText.Format.OPENMETRICS, "warp_logins_");

      assertEquals(
          """
          # HELP warp_logins Login attempts.
          # TYPE warp_logins counter
          warp_logins_total{outcome="success"} 7
          # EOF
          """,
          text);
    }

    @Test
    @DisplayName("should be chosen from Accept only when OpenMetrics is wanted")
    void negotiation() {
      assertEquals(PrometheusText.Format.TEXT, PrometheusText.negotiate(null));
      assertEquals(PrometheusText.Format.TEXT, PrometheusText.negotiate("*/*"));
      assertEquals(
          PrometheusText.Format.TEXT, PrometheusText.negotiate("text/plain;version=0.0.4"));
      assertEquals(
          PrometheusText.Format.OPENMETRICS,
          PrometheusText.negotiate(
              "application/openmetrics-text;version=1.0.0;q=0.5,application/openmetrics-text;"
                  + "version=0.0.1;q=0.4,text/plain;version=0.0.4;q=0.3,*/*;q=0.2"));
      assertEquals(
          PrometheusText.Format.OPENMETRICS,
          PrometheusText.negotiate("Application/OpenMetrics-Text"));
      assertEquals(
          PrometheusText.Format.TEXT, PrometheusText.negotiate("application/openmetrics-text;q=0"));
      assertEquals(
          "application/openmetrics-text; version=1.0.0; charset=utf-8",
          PrometheusText.Format.OPENMETRICS.contentType());
    }
  }

  @Test
  @DisplayName("should format numbers as Prometheus reads them")
  void numbers() {
    assertEquals("0", PrometheusText.number(0));
    assertEquals("42", PrometheusText.number(42.0));
    assertEquals("-3", PrometheusText.number(-3));
    assertEquals("0.005", PrometheusText.number(0.005));
    assertEquals("1.0E15", PrometheusText.number(1e15));
    assertEquals("+Inf", PrometheusText.number(Double.POSITIVE_INFINITY));
    assertEquals("-Inf", PrometheusText.number(Double.NEGATIVE_INFINITY));
    assertEquals("NaN", PrometheusText.number(Double.NaN));
    assertTrue(PrometheusText.number(1e-4).contains("E-4"));
    assertFalse(PrometheusText.number(2.5).contains("E"));
  }
}
