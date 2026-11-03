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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.command.CommandSource;
import dev.warp.api.player.Player;
import dev.warp.proxy.console.ConsoleCommandSource;
import dev.warp.proxy.logging.LogLevels;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("/log")
class LogCommandTest {

  private final LogLevels levels = new LogLevels();
  private final CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
  private final ConsoleCommandSource console = spy(new ConsoleCommandSource());

  LogCommandTest() {
    dispatcher.register(LogCommand.build(levels, () -> null));
    // The anti-bot's logger exists, as it does once the proxy started.
    var _ = LogManager.getLogger("dev.warp.proxy.antibot.AttackDetector");
  }

  @AfterEach
  void resetLevels() {
    var _ = levels.reset("antibot");
  }

  /** The translation key of the last message sent to the console. */
  private String lastReply() {
    ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
    verify(console, atLeastOnce()).sendMessage(captor.capture());
    Component reply = captor.getValue();
    return reply instanceof TranslatableComponent translatable ? translatable.key() : "";
  }

  @Nested
  @DisplayName("who may run it")
  class Who {

    @Test
    @DisplayName("should not exist for a player, even one granted every permission")
    void shouldNotExistForPlayers() {
      // given
      Player player = mock(Player.class);
      when(player.hasPermission(anyString())).thenReturn(true);

      // when
      boolean visible = dispatcher.getRoot().getChild("log").canUse(player);

      // then
      assertFalse(visible);
      assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("log tail", player));
    }

    @Test
    @DisplayName("should exist for the console")
    void shouldExistForTheConsole() {
      assertTrue(dispatcher.getRoot().getChild("log").canUse(console));
    }
  }

  @Nested
  @DisplayName("/log level")
  class Level {

    @Test
    @DisplayName("should change a source's level until reset, and put it back on reset")
    void shouldChangeAndReset() throws CommandSyntaxException {
      // given
      var logger = LogManager.getLogger("dev.warp.proxy.antibot.AttackDetector");
      assertFalse(logger.isDebugEnabled());

      // when
      dispatcher.execute("log level antibot debug", console);

      // then
      assertTrue(logger.isDebugEnabled());
      assertEquals("warp.command.log.level.set", lastReply());

      // when
      dispatcher.execute("log level antibot reset", console);

      // then
      assertFalse(logger.isDebugEnabled());
      assertEquals("warp.command.log.level.reset", lastReply());
    }

    @Test
    @DisplayName("should list what changed, and say when nothing did")
    void shouldList() throws CommandSyntaxException {
      // given nothing changed
      dispatcher.execute("log level", console);
      assertEquals("warp.command.log.level.none", lastReply());

      // when
      dispatcher.execute("log level antibot trace", console);
      dispatcher.execute("log level", console);

      // then
      assertEquals("warp.command.log.level.list", lastReply());
    }

    @Test
    @DisplayName("should refuse an unknown source and an unknown level")
    void shouldRefuseUnknowns() throws CommandSyntaxException {
      // when
      dispatcher.execute("log level nothing-like-this debug", console);

      // then
      assertEquals("warp.command.log.source.unknown", lastReply());

      // when
      dispatcher.execute("log level antibot loud", console);

      // then
      assertEquals("warp.command.log.level.invalid", lastReply());
    }

    @Test
    @DisplayName("should complete sources, a plugin's tag with its colon included")
    void shouldCompleteSources() {
      // given
      var parse = dispatcher.parse("log level ", console);

      // when
      List<String> suggestions =
          dispatcher.getCompletionSuggestions(parse).join().getList().stream()
              .map(Suggestion::getText)
              .toList();

      // then
      assertTrue(suggestions.contains("netty"), suggestions.toString());
      assertTrue(suggestions.contains("via"), suggestions.toString());
    }
  }

  @Nested
  @DisplayName("/log filter and /log tail")
  class FilterAndTail {

    @Test
    @DisplayName("should say the JSON console cannot do them")
    void shouldNeedTheTextConsole() throws CommandSyntaxException {
      // when
      dispatcher.execute("log filter antibot", console);

      // then
      assertEquals("warp.command.log.unavailable", lastReply());

      // when
      dispatcher.execute("log tail 5", console);

      // then
      assertEquals("warp.command.log.unavailable", lastReply());
    }
  }
}
