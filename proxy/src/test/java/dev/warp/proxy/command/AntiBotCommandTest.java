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
package dev.warp.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AntiBotCommand")
class AntiBotCommandTest {

  @Test
  @DisplayName("should print instants in the console's zone, formatted as the log timestamps")
  void instantsInConsoleZone() {
    Instant instant = Instant.parse("2026-11-09T18:12:00Z");

    assertEquals("2026-11-09 19:12:00", AntiBotCommand.when(instant, ZoneId.of("Europe/Paris")));
    assertEquals("2026-11-09 18:12:00", AntiBotCommand.when(instant, ZoneId.of("UTC")));
  }
}
