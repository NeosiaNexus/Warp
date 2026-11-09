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
package dev.warp.api.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MetricNames")
class MetricNamesTest {

  @Nested
  @DisplayName("metric names")
  class Names {

    @Test
    @DisplayName("should accept Prometheus names with base units")
    void accepts() {
      assertDoesNotThrow(() -> MetricNames.validate("warp_logins_total", MetricType.COUNTER));
      assertDoesNotThrow(
          () -> MetricNames.validate("warp_login_duration_seconds", MetricType.HISTOGRAM));
      assertDoesNotThrow(() -> MetricNames.validate("jvm_memory_used_bytes", MetricType.GAUGE));
      assertDoesNotThrow(() -> MetricNames.validate("_private", MetricType.GAUGE));
    }

    @Test
    @DisplayName("should refuse invalid characters and a leading digit")
    void syntax() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MetricNames.validate("warp-logins_total", MetricType.COUNTER));
      assertThrows(
          IllegalArgumentException.class, () -> MetricNames.validate("1st", MetricType.GAUGE));
      assertThrows(
          IllegalArgumentException.class, () -> MetricNames.validate("a:b", MetricType.GAUGE));
      assertThrows(
          IllegalArgumentException.class, () -> MetricNames.validate("", MetricType.GAUGE));
    }

    @Test
    @DisplayName("should require _total on counters and refuse it elsewhere")
    void totalSuffix() {
      IllegalArgumentException counter =
          assertThrows(
              IllegalArgumentException.class,
              () -> MetricNames.validate("warp_logins", MetricType.COUNTER));
      assertTrue(String.valueOf(counter.getMessage()).contains("_total"), counter.getMessage());
      assertThrows(
          IllegalArgumentException.class,
          () -> MetricNames.validate("warp_players_total", MetricType.GAUGE));
      assertThrows(
          IllegalArgumentException.class,
          () -> MetricNames.validate("warp_lag_total", MetricType.HISTOGRAM));
    }

    @Test
    @DisplayName("should refuse scaled units such as _ms and _kb")
    void units() {
      for (String name :
          new String[] {
            "warp_lag_ms", "warp_lag_millis", "warp_size_kb", "warp_size_mb", "warp_load_percent"
          }) {
        IllegalArgumentException refused =
            assertThrows(
                IllegalArgumentException.class,
                () -> MetricNames.validate(name, MetricType.GAUGE),
                name);
        assertTrue(
            String.valueOf(refused.getMessage()).contains("base units"), refused.getMessage());
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> MetricNames.validate("warp_wait_ms_total", MetricType.COUNTER));
    }
  }

  @Nested
  @DisplayName("label names")
  class Labels {

    @Test
    @DisplayName("should refuse labels that take a value per player or address")
    void reserved() {
      for (String label : MetricNames.RESERVED_LABELS) {
        assertThrows(IllegalArgumentException.class, () -> MetricNames.validateLabel(label), label);
      }
      assertDoesNotThrow(() -> MetricNames.validateLabel("outcome"));
      assertDoesNotThrow(() -> MetricNames.validateLabel("server"));
    }

    @Test
    @DisplayName("should refuse invalid syntax and the __ prefix")
    void syntax() {
      assertThrows(IllegalArgumentException.class, () -> MetricNames.validateLabel("__name__"));
      assertThrows(IllegalArgumentException.class, () -> MetricNames.validateLabel("a-b"));
      assertThrows(IllegalArgumentException.class, () -> MetricNames.validateLabel("9lives"));
    }
  }

  @Nested
  @DisplayName("plugin prefixes")
  class Prefixes {

    @Test
    @DisplayName("should derive the prefix from the plugin id")
    void prefix() {
      assertEquals("luckperms_", MetricNames.pluginPrefix("luckperms"));
      assertEquals("my_plugin_", MetricNames.pluginPrefix("my-plugin"));
      assertEquals("my_plugin_", MetricNames.pluginPrefix("My.Plugin"));
      assertEquals("plugin_2fa_", MetricNames.pluginPrefix("2fa"));
    }

    @Test
    @DisplayName("should know the proxy's reserved prefixes")
    void reserved() {
      assertTrue(MetricNames.hasReservedPrefix("warp_logins_total"));
      assertTrue(MetricNames.hasReservedPrefix("jvm_threads_live"));
      assertTrue(MetricNames.hasReservedPrefix("process_open_fds"));
      assertFalse(MetricNames.hasReservedPrefix("warpdrive_total"));
      assertFalse(MetricNames.hasReservedPrefix("luckperms_lookups_total"));
    }
  }
}
