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
package dev.warp.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Durations")
class DurationsTest {

  @Nested
  @DisplayName("parse")
  class Parse {

    @ParameterizedTest(name = "{0} -> {1} ms")
    @CsvSource({
      "250ms, 250",
      "3s, 3000",
      "2m, 120000",
      "1h, 3600000",
      "30d, 2592000000",
      "30 seconds, 30000",
      "1 minute, 60000",
      "5 millis, 5",
      "2 days, 172800000",
      "15S, 15000",
      "0, 0",
      "0s, 0"
    })
    @DisplayName("should read a whole number with a HOCON unit, and a bare 0")
    void units(String value, long millis) throws ConfigurationException {
      assertEquals(Duration.ofMillis(millis), Durations.parse(value, "key"));
    }

    @Test
    @DisplayName("should refuse a bare number, suggesting both units it may have meant")
    void bareNumber() {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> Durations.parse("3000", "a.key"));

      assertEquals(
          "a.key needs a unit: write 3000ms for milliseconds, or 3000s for seconds (durations take"
              + " ms, s, m, h or d)",
          error.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "s", "1.5s", "-1s", "10 weeks", "3 sec", "ms5"})
    @DisplayName("should refuse what is not a whole number and a unit, naming the key")
    void invalid(String value) {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> Durations.parse(value, "a.key"));

      assertTrue(String.valueOf(error.getMessage()).startsWith("a.key "), error.getMessage());
    }

    @Test
    @DisplayName("should refuse a duration too large for a Duration")
    void tooLarge() {
      assertThrows(
          ConfigurationException.class, () -> Durations.parse("999999999999999d", "a.key"));
    }
  }

  @Nested
  @DisplayName("format")
  class Format {

    @ParameterizedTest(name = "{0} ms -> {1}")
    @CsvSource({
      "0, 0s",
      "100, 100ms",
      "1500, 1500ms",
      "5000, 5s",
      "1800000, 30m",
      "7200000, 2h",
      "2592000000, 30d"
    })
    @DisplayName("should write the largest unit that divides the duration")
    void largestUnit(long millis, String expected) {
      assertEquals(expected, Durations.format(Duration.ofMillis(millis)));
    }

    @Test
    @DisplayName("should write what parse reads back")
    void roundTrip() throws ConfigurationException {
      Duration duration = Duration.ofMinutes(90);

      assertEquals(duration, Durations.parse(Durations.format(duration), "key"));
    }
  }
}
