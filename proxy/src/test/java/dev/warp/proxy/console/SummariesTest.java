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

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Summaries and the status line")
class SummariesTest {

  private static final Summaries.Startup FACTS =
      new Summaries.Startup(
          "25.0.2 (Eclipse Adoptium)",
          8,
          512L << 20,
          "epoll",
          "0.0.0.0:25565",
          false,
          "libdeflate 1.26, native",
          true,
          true,
          "modern",
          false,
          List.of(
              new Summaries.Server("lobby", "127.0.0.1:30066", 3, "Paper 1.21.4"),
              new Summaries.Server("creative", "127.0.0.1:30068", -1, null)),
          List.of("primemc 1.2.0"),
          1,
          "on, calm",
          null);

  @Nested
  @DisplayName("the banner")
  class Banner {

    @Test
    @DisplayName("shows a server whose ping has not answered yet as still pinging")
    void pendingServer() {
      Summaries.Startup facts =
          new Summaries.Startup(
              "25.0.2 (Eclipse Adoptium)",
              8,
              512L << 20,
              "epoll",
              "0.0.0.0:25565",
              false,
              "libdeflate 1.26, native",
              true,
              true,
              "modern",
              false,
              List.of(
                  new Summaries.Server("lobby", "127.0.0.1:30066", 3, "Paper 1.21.4"),
                  Summaries.Server.pending("creative", "127.0.0.1:30068")),
              List.of(),
              0,
              "on, calm",
              null);

      String text = Summaries.startup(facts).getFormattedMessage();

      assertTrue(text.contains("  servers      1 of 2 reachable, 1 still pinging"), text);
      assertTrue(text.contains("- creative  127.0.0.1:30068  still pinging"), text);
      assertFalse(text.contains("unreachable"), text);
    }

    @Test
    @DisplayName("keeps the startup line of the log file")
    void plain() {
      ConsoleBlock banner = Summaries.banner("1.0.0", "abc1234", "main");

      assertTrue(
          banner
              .getFormattedMessage()
              .startsWith("Starting Warp 1.0.0 (commit: abc1234, branch: main)\n"));
    }

    @Test
    @DisplayName("draws the logo in Unicode, or in ASCII where the output cannot show it")
    void logo() {
      ConsoleBlock banner = Summaries.banner("1.0.0", "abc1234", "main");

      assertTrue(
          banner
              .render(Palette.of(ColorDepth.NONE, Theme.DARK, false), Glyphs.UNICODE)
              .contains("█ █ █   Warp 1.0.0"));
      String ascii = banner.render(Palette.of(ColorDepth.NONE, Theme.DARK, false), Glyphs.ASCII);
      assertTrue(ascii.chars().allMatch(c -> c < 128), ascii);
    }
  }

  @Nested
  @DisplayName("the startup summary")
  class Startup {

    @Test
    @DisplayName("lists the facts, one per line, in plain text for the log file")
    void plain() {
      String text = Summaries.startup(FACTS).getFormattedMessage();

      assertTrue(text.startsWith("Startup summary:\n"));
      assertTrue(
          text.contains("  java         25.0.2 (Eclipse Adoptium) | 8 cores | 512 MiB heap"));
      assertTrue(text.contains("  servers      1 of 2 reachable"));
      assertTrue(text.contains("+ lobby     127.0.0.1:30066  3 ms  Paper 1.21.4"));
      assertTrue(text.contains("- creative  127.0.0.1:30068  unreachable"));
      assertTrue(text.contains("  plugins      primemc 1.2.0 (1 failed)"));
      assertTrue(text.contains("  management   off"));
      assertFalse(text.contains("\u001B"));
      assertFalse(text.lines().anyMatch(line -> line.endsWith(" ")), "no trailing spaces");
    }

    @Test
    @DisplayName("colours what works and what does not")
    void colours() {
      String rendered =
          Summaries.startup(FACTS)
              .render(Palette.of(ColorDepth.ANSI_16, Theme.DARK, false), Glyphs.UNICODE);

      assertTrue(
          rendered.contains(
              Palette.of(ColorDepth.ANSI_16, Theme.DARK, false).open(Style.GOOD) + "●"));
      assertTrue(
          rendered.contains(
              Palette.of(ColorDepth.ANSI_16, Theme.DARK, false).open(Style.BAD) + "unreachable"));
    }
  }

  @Test
  @DisplayName("the shutdown summary tells why, how long and who was disconnected")
  void shutdown() {
    String text =
        Summaries.shutdown(
                new Summaries.Shutdown("drain finished", Duration.ofMinutes(75), 3, "completed"))
            .getFormattedMessage();

    assertTrue(text.contains("  cause        drain finished"));
    assertTrue(text.contains("  uptime       1 h 15 min"));
    assertTrue(text.contains("  players      3 disconnected"));
    assertTrue(text.contains("  drain        completed"));
  }

  @Test
  @DisplayName("durations read naturally")
  void durations() {
    assertEquals("42 s", Summaries.duration(Duration.ofSeconds(42)));
    assertEquals("3 min 12 s", Summaries.duration(Duration.ofSeconds(192)));
    assertEquals("2 d 3 h", Summaries.duration(Duration.ofHours(51)));
  }

  @Nested
  @DisplayName("the status line")
  class Status {

    private final ConsoleMode mode = new ConsoleMode(ColorDepth.NONE, true, true, null, true, null);

    @Test
    @DisplayName("shows players, servers, connections, the attack level and memory")
    void calm() {
      String line =
          StatusLine.render(
              new StatusLine.Snapshot(12, 3, 3, 4, false, false, null, 212L << 20, 512L << 20),
              mode);

      assertEquals(" ● 12 players · 3/3 servers · 4 conn/s · calm · 212/512 MiB", line);
    }

    @Test
    @DisplayName("says when the proxy is under attack, locked down or draining")
    void trouble() {
      String line =
          StatusLine.render(
              new StatusLine.Snapshot(
                  1, 0, 2, 900, true, true, "draining, 1 still connected", 100L << 20, 512L << 20),
              mode);

      assertTrue(line.contains("1 player ·"));
      assertTrue(line.contains("under attack · lockdown · draining, 1 still connected"));
    }
  }
}
