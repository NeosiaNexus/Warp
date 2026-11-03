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

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.warp.api.command.CommandSource;
import dev.warp.proxy.logging.LogCapture;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WarpCommandManager")
class WarpCommandManagerTest {

  private final WarpCommandManager commands = new WarpCommandManager(Runnable::run);

  @Nested
  @DisplayName("failing commands")
  class FailingCommands {

    @Test
    @DisplayName("should keep a hostile command line from forging a second log line")
    void hostileCommandLine() throws Exception {
      var _ =
          commands.register(
              "test",
              LiteralArgumentBuilder.<CommandSource>literal("boom")
                  .then(
                      RequiredArgumentBuilder.<CommandSource, String>argument(
                              "text", greedyString())
                          .executes(
                              _ -> {
                                throw new IllegalStateException("boom");
                              })));
      CommandSource player = mock(CommandSource.class);
      try (LogCapture capture = new LogCapture()) {

        boolean matched =
            commands.execute(player, "boom x\n[12:00:00 ERROR]: forged\u001B[2J").join();

        assertTrue(matched);
        String logged = capture.await(record -> record.message().contains("failed")).message();
        assertEquals("Command /boom x\\n[12:00:00 ERROR]: forged\\u001B[2J failed", logged);
        assertTrue(logged.chars().noneMatch(c -> c == '\n' || c == '\r' || c == 0x1B));
        assertFalse(
            capture.anyAfterSettling(record -> record.message().startsWith("[12:00:00 ERROR]")));
      }
    }
  }
}
