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
package dev.warp.proxy.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StartupTimings")
class StartupTimingsTest {

  private static final long MS = 1_000_000;

  @Nested
  @DisplayName("summary")
  class Summary {

    @Test
    @DisplayName("should name each phase with its time, and the longest plugin")
    void shouldNamePhases() {
      // given
      StartupTimings timings = new StartupTimings();
      Map<String, Long> plugins = new LinkedHashMap<>();
      plugins.put("showcase", 1100 * MS);
      plugins.put("tiny", 30 * MS);
      timings.record("jvm", 410 * MS);
      timings.record("config", 42 * MS);
      timings.record("plugins", 1200 * MS, plugins);
      timings.record("bind", 1 * MS);

      // when
      String summary = timings.summary(Glyphs.ASCII);

      // then
      assertEquals("jvm 410 ms | config 42 ms | plugins 1.20 s (showcase 1.10 s, 1 more)", summary);
    }

    @Test
    @DisplayName("should count what no phase accounts for as other, once finished")
    void shouldCountTheRest() {
      // given
      StartupTimings timings = new StartupTimings();
      timings.record("jvm", 400 * MS);

      // when
      timings.finish(1000 * MS);

      // then
      List<StartupTimings.Phase> phases = timings.phases();
      assertEquals("other", phases.getLast().name());
      assertEquals(600 * MS, phases.getLast().nanos());
      assertEquals(1000 * MS, timings.totalNanos());
    }

    @Test
    @DisplayName("should be dimmed under the message column in the console, plain in the file")
    void shouldRenderAsABlock() {
      // given
      StartupTimings timings = new StartupTimings();
      timings.record("config", 42 * MS);

      // when
      ConsoleBlock block = timings.block();
      String coloured =
          block.render(Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false), Glyphs.UNICODE);

      // then
      assertTrue(block.getFormattedMessage().startsWith("Startup: config 42 ms"));
      assertTrue(coloured.startsWith(" ".repeat(LineFormatter.MESSAGE_COLUMN) + "\u001B["));
      assertFalse(block.getFormattedMessage().contains("\u001B"));
    }
  }

  @Test
  @DisplayName("should format durations for people")
  void shouldFormatDurations() {
    assertEquals("820 µs", StartupTimings.format(820_000));
    assertEquals("42 ms", StartupTimings.format(42 * MS));
    assertEquals("1.24 s", StartupTimings.format(1240 * MS));
  }
}
