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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.LogFormat;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("log4j2.xml")
class LoggingConfigurationTest {

  private static LogEvent event(Message message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("dev.warp.proxy.connection.ConnectedPlayer")
        .setLevel(Level.INFO)
        .setMessage(message)
        .setThreadName("main")
        .setTimeMillis(0)
        .build();
  }

  /**
   * Loads the shipped configuration with a console format into a context of its own, runs {@code
   * check}, then stops it. Never the JVM's shared context: stopping that one would leave every
   * logger created before this test attached to a dead context, silent for the tests that follow.
   */
  private static void withConfiguration(LogFormat format, Consumer<LoggerContext> check)
      throws URISyntaxException {
    URL resource = LoggingConfigurationTest.class.getClassLoader().getResource("log4j2.xml");
    assertNotNull(resource);
    URI uri = resource.toURI();
    String previous = System.getProperty(LogFormat.SYSTEM_PROPERTY);
    System.setProperty(LogFormat.SYSTEM_PROPERTY, format.configName());
    String name = "test-" + format.configName();
    LoggerContext context = new LoggerContext(name);
    try {
      context.start(ConfigurationFactory.getInstance().getConfiguration(context, name, uri));
      check.accept(context);
    } finally {
      context.stop();
      if (previous == null) {
        System.clearProperty(LogFormat.SYSTEM_PROPERTY);
      } else {
        System.setProperty(LogFormat.SYSTEM_PROPERTY, previous);
      }
    }
  }

  @Test
  @DisplayName("gives the text format Warp's console")
  void textConsole() throws URISyntaxException {
    withConfiguration(
        LogFormat.TEXT,
        context -> {
          Appender console = context.getConfiguration().getAppender("Console");

          assertInstanceOf(WarpConsoleAppender.class, console);
        });
  }

  @Test
  @DisplayName("keeps the JSON format as it was: one object per line, never coloured")
  void jsonUnchanged() throws URISyntaxException {
    withConfiguration(
        LogFormat.JSON,
        context -> {
          Appender console = context.getConfiguration().getAppender("Console");
          ConsoleAppender appender = assertInstanceOf(ConsoleAppender.class, console);
          JsonTemplateLayout layout =
              assertInstanceOf(JsonTemplateLayout.class, appender.getLayout());

          String line =
              layout.toSerializable(
                  event(
                      new ComponentMessage(Component.text("Steve joined", NamedTextColor.GREEN))));

          assertFalse(line.contains("\u001B"));
          assertEquals(1, line.strip().lines().count());
          JsonObject json = JsonParser.parseString(line).getAsJsonObject();
          assertEquals("Steve joined", json.get("message").getAsString());
          assertEquals("INFO", json.get("level").getAsString());
          assertEquals(
              "dev.warp.proxy.connection.ConnectedPlayer", json.get("logger").getAsString());
          assertEquals("main", json.get("thread").getAsString());
          assertTrue(
              json.get("timestamp").getAsString().matches("\\d{4}-\\d\\d-\\d\\dT[\\d:.]{12}Z"));
        });
  }

  @Test
  @DisplayName("writes the log file in plain text: no escape code, no control character")
  void plainFile() throws URISyntaxException {
    withConfiguration(
        LogFormat.TEXT,
        context -> {
          RollingFileAppender file =
              assertInstanceOf(
                  RollingFileAppender.class, context.getConfiguration().getAppender("File"));

          String line =
              file.getLayout()
                  .toSerializable(
                      event(
                          new SimpleMessage("\u001B[31mred\u001B[0m and\r forged\u001B]0;t\u0007")))
                  .toString();

          assertTrue(line.endsWith(" - red and forged" + System.lineSeparator()), line);
          assertTrue(line.contains("[main] INFO "), line);
        });
  }

  @Test
  @DisplayName("ends a coded line of the log file with its code, and no other marker")
  void codeInFile() throws URISyntaxException {
    withConfiguration(
        LogFormat.TEXT,
        context -> {
          // given
          RollingFileAppender file =
              assertInstanceOf(
                  RollingFileAppender.class, context.getConfiguration().getAppender("File"));
          LogEvent coded =
              Log4jLogEvent.newBuilder()
                  .setLoggerName("dev.warp.proxy.WarpServer")
                  .setLevel(Level.WARN)
                  .setMarker(org.apache.logging.log4j.MarkerManager.getMarker("W-START-002"))
                  .setMessage(new SimpleMessage("Cannot listen"))
                  .setThreadName("main")
                  .setTimeMillis(0)
                  .build();
          LogEvent fileOnly =
              Log4jLogEvent.newBuilder()
                  .setLoggerName("dev.warp.proxy.config.WarpConfig")
                  .setLevel(Level.INFO)
                  .setMarker(org.apache.logging.log4j.MarkerManager.getMarker("WARP_FILE_ONLY"))
                  .setMessage(new SimpleMessage("  bind: 0.0.0.0:25577"))
                  .setThreadName("main")
                  .setTimeMillis(0)
                  .build();

          // when
          String codedLine = file.getLayout().toSerializable(coded).toString();
          String fileOnlyLine = file.getLayout().toSerializable(fileOnly).toString();
          String plainLine =
              file.getLayout().toSerializable(event(new SimpleMessage("hi"))).toString();

          // then
          assertTrue(
              codedLine.endsWith(" - Cannot listen [W-START-002]" + System.lineSeparator()),
              codedLine);
          assertTrue(
              fileOnlyLine.endsWith(" - bind: 0.0.0.0:25577" + System.lineSeparator())
                  || fileOnlyLine.endsWith(" -   bind: 0.0.0.0:25577" + System.lineSeparator()),
              fileOnlyLine);
          assertTrue(plainLine.endsWith(" - hi" + System.lineSeparator()), plainLine);
        });
  }
}
