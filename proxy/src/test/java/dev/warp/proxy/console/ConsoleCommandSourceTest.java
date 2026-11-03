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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.Warp;
import dev.warp.api.command.CommandSource;
import dev.warp.proxy.WarpServer;
import dev.warp.proxy.config.WarpConfig;
import dev.warp.proxy.i18n.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The console as a command source")
class ConsoleCommandSourceTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("is what Warp#console returns, with every permission")
  void api() throws Exception {
    Warp warp = new WarpServer(WarpConfig.load(directory), directory);

    CommandSource console = warp.console();

    assertInstanceOf(ConsoleCommandSource.class, console);
    assertSame(console, warp.console());
    assertTrue(console.hasPermission("warp.command.anything"));
  }

  @Test
  @DisplayName("logs what it is sent as a component, for the console to colour")
  void logs() {
    List<LogEvent> events =
        captured(() -> new ConsoleCommandSource().sendMessage(Component.text("Hello")));

    assertEquals(1, events.size());
    ComponentMessage message =
        assertInstanceOf(ComponentMessage.class, events.getFirst().getMessage());
    assertEquals(Component.text("Hello"), message.component());
    assertEquals("Hello", message.getFormattedMessage());
  }

  @Test
  @DisplayName("renders the proxy's messages in the configured default language")
  void defaultLanguage() throws Exception {
    Files.writeString(
        directory.resolve("warp.conf"), "messages {\n  default-locale = \"fr_FR\"\n}\n");
    Warp warp = new WarpServer(WarpConfig.load(directory), directory);

    List<LogEvent> events =
        captured(() -> warp.console().sendMessage(Message.UNKNOWN_SERVER.of("nowhere")));

    assertEquals(1, events.size());
    assertEquals(
        "Serveur inconnu : nowhere. Tapez /server pour voir la liste des serveurs.",
        events.getFirst().getMessage().getFormattedMessage());
  }

  /** What the console's logger receives while {@code send} runs. */
  private static List<LogEvent> captured(Runnable send) {
    String name = "dev.warp.proxy.console.Console";
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    Configuration configuration = context.getConfiguration();
    List<LogEvent> events = new CopyOnWriteArrayList<>();
    AbstractAppender capture =
        new AbstractAppender("capture-console", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            events.add(event.toImmutable());
          }
        };
    capture.start();
    LoggerConfig logger =
        LoggerConfig.newBuilder()
            .setLoggerName(name)
            .setLevel(Level.ALL)
            .setAdditivity(false)
            .setConfig(configuration)
            .build();
    logger.addAppender(capture, Level.ALL, null);
    configuration.addLogger(name, logger);
    context.updateLoggers();
    try {
      send.run();

      long deadline = System.nanoTime() + 5_000_000_000L;
      while (events.isEmpty() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      return List.copyOf(events);
    } finally {
      configuration.removeLogger(name);
      context.updateLoggers();
      capture.stop();
    }
  }
}
