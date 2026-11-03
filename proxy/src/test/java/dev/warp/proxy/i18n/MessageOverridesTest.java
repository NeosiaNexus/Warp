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
package dev.warp.proxy.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.ConfigurationException;
import dev.warp.proxy.config.ConfigurationException.Problem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Message overrides")
class MessageOverridesTest {

  @TempDir private Path directory;

  private Path messages() {
    return directory.resolve(MessageOverrides.DIRECTORY);
  }

  private void write(String file, String content) throws IOException {
    Files.createDirectories(messages());
    Files.writeString(messages().resolve(file), content, StandardCharsets.UTF_8);
  }

  private Problem onlyProblem() {
    ConfigurationException error =
        assertThrows(ConfigurationException.class, () -> MessageOverrides.load(messages()));
    assertEquals(1, error.problems().size(), error.getMessage());
    return error.problems().getFirst();
  }

  @Nested
  @DisplayName("template")
  class Template {

    @Test
    @DisplayName("should write a template listing every message, commented out, on first start")
    void firstStart() throws Exception {
      Map<Locale, Map<String, String>> overrides = MessageOverrides.load(messages());

      String template =
          Files.readString(
              messages().resolve(MessageOverrides.EVERY_LANGUAGE), StandardCharsets.UTF_8);
      assertEquals(Map.of(), overrides);
      for (Message message : Message.values()) {
        assertTrue(template.contains("\n#" + message.key() + "="), message.key());
      }
      assertEquals(Map.of(Locale.ROOT, Map.of()), MessageOverrides.load(messages()));
    }
  }

  @Nested
  @DisplayName("valid overrides")
  class Valid {

    @Test
    @DisplayName("should read every language's file and one language's, as UTF-8")
    void read() throws Exception {
      write("messages.properties", "warp.login.lockdown = <gold>Closed for now\n");
      write("messages_fr.properties", "warp.login.lockdown=Fermé pour l'instant\n");

      Map<Locale, Map<String, String>> overrides = MessageOverrides.load(messages());

      assertEquals(
          "<gold>Closed for now",
          Objects.requireNonNull(overrides.get(Locale.ROOT)).get("warp.login.lockdown"));
      assertEquals(
          "Fermé pour l'instant",
          Objects.requireNonNull(overrides.get(Locale.FRENCH)).get("warp.login.lockdown"));
    }

    @Test
    @DisplayName("should let a chat message be emptied, which then is not sent")
    void emptyChat() throws Exception {
      write("messages.properties", "warp.connect.connecting=\n");

      assertEquals(
          "",
          Objects.requireNonNull(MessageOverrides.load(messages()).get(Locale.ROOT))
              .get("warp.connect.connecting"));
    }
  }

  @Nested
  @DisplayName("invalid overrides")
  class Invalid {

    @Test
    @DisplayName("should name the file and line of an unknown key, with the closest one")
    void unknownKey() throws Exception {
      write("messages_fr.properties", "# comment\n\nwarp.login.lockdwn=Fermé\n");

      Problem problem = onlyProblem();

      assertEquals(3, problem.line());
      assertTrue(
          problem.format().startsWith("messages/messages_fr.properties, line 3: "),
          problem.format());
      assertTrue(
          problem.description().contains("Did you mean warp.login.lockdown?"),
          problem.description());
    }

    @Test
    @DisplayName("should name an unknown placeholder, and the ones the message has")
    void unknownPlaceholder() throws Exception {
      write("messages.properties", "warp.connect.failed=<bad>No <srever>\n");

      Problem problem = onlyProblem();

      assertTrue(
          problem.description().contains("unknown placeholder <srever>"), problem.description());
      assertTrue(
          problem.description().contains("This message has <server>."), problem.description());
    }

    @Test
    @DisplayName("should refuse an empty disconnect reason")
    void emptyDisconnect() throws Exception {
      write("messages.properties", "warp.login.lockdown=\n");

      assertTrue(onlyProblem().description().contains("cannot be empty"));
    }

    @Test
    @DisplayName("should refuse a key set twice in a file, naming both lines")
    void duplicate() throws Exception {
      write("messages.properties", "warp.login.lockdown=A\nwarp.login.lockdown=B\n");

      assertTrue(onlyProblem().description().contains("lines 1 and 2"));
    }

    @Test
    @DisplayName("should refuse a file named after no language")
    void badFileName() throws Exception {
      write("messages_french.properties", "warp.login.lockdown=A\n");

      assertTrue(onlyProblem().description().contains("'french' is not a language"));
    }

    @Test
    @DisplayName("should refuse a file that is not UTF-8")
    void notUtf8() throws Exception {
      Files.createDirectories(messages());
      Files.write(
          messages().resolve("messages_fr.properties"),
          "warp.login.lockdown=Fermé\n".getBytes(StandardCharsets.ISO_8859_1));

      assertTrue(onlyProblem().description().contains("not UTF-8"));
    }

    @Test
    @DisplayName("should report every problem of every file at once")
    void allAtOnce() throws Exception {
      write("messages.properties", "warp.nope=A\n");
      write("messages_fr.properties", "warp.login.lockdown=\n");

      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> MessageOverrides.load(messages()));

      assertEquals(2, error.problems().size(), error.getMessage());
    }
  }
}
