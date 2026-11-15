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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.ConfigurationException.Problem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Configuration errors")
class ConfigValidationTest {

  @TempDir private Path directory;

  private WarpConfig load(String content) throws Exception {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }

  private List<Problem> problems(String content) {
    ConfigurationException error = assertThrows(ConfigurationException.class, () -> load(content));
    return error.problems();
  }

  private Problem only(String content) {
    List<Problem> problems = problems(content);
    assertEquals(1, problems.size(), problems.toString());
    return problems.getFirst();
  }

  private static void assertContains(String expected, Problem problem) {
    assertTrue(problem.description().contains(expected), problem.format());
  }

  @Nested
  @DisplayName("unknown keys")
  class UnknownKeys {

    @Test
    @DisplayName("should suggest the setting a typo was meant to be, naming its line")
    void typo() {
      Problem problem = only("forwarding { mode = none }\n\nonlin-mode = false\n");

      assertEquals(3, problem.line());
      assertTrue(problem.format().startsWith("warp.conf, line 3: onlin-mode"), problem.format());
      assertContains("Did you mean online-mode?", problem);
    }

    @Test
    @DisplayName("should suggest a setting of a block, written with an underscore")
    void nestedTypo() {
      assertContains(
          "Did you mean compression.threshold?",
          only("forwarding { mode = none }\ncompression { threshold_ = 300 }\n"));
    }

    @Test
    @DisplayName("should point a setting written outside its block to it")
    void misplaced() {
      assertContains(
          "Did you mean status.motd, in the status block?",
          only("forwarding { mode = none }\nmotd = \"hi\"\n"));
    }

    @Test
    @DisplayName("should report an unknown block once, not each of its keys")
    void unknownBlock() {
      Problem problem =
          only("forwarding { mode = none }\ncompresion { threshold = 1, level = 2 }\n");

      assertEquals("compresion", problem.key());
      assertContains("Did you mean compression?", problem);
    }

    @Test
    @DisplayName("should say what replaced a setting of an older layout")
    void moved() {
      assertContains(
          "servers block",
          only("forwarding { mode = none }\nbackend { address = \"localhost:25565\" }\n"));
    }

    @Test
    @DisplayName("should check the keys of each named server too")
    void serverKeys() {
      assertContains(
          "Did you mean servers.lobby.address?",
          only("forwarding { mode = none }\nservers { lobby { adress = \"localhost:25565\" } }\n"));
    }

    @Test
    @DisplayName("should accept the settings that exist without being in the default file")
    void optional() throws Exception {
      WarpConfig config =
          load(
              "forwarding { mode = none }\nstatus { max-players = 100, version-name = \"Warp\" }\n");

      assertEquals(100, config.status().maxPlayers());
    }
  }

  @Nested
  @DisplayName("values")
  class Values {

    @Test
    @DisplayName("should name the line, what was expected and what was found")
    void wrongType() {
      Problem problem = only("forwarding { mode = none }\nonline-mode = maybe\n");

      assertEquals(2, problem.line());
      assertContains("online-mode: expected true or false, found 'maybe'", problem);
    }

    @Test
    @DisplayName("should refuse a value where a block belongs, which would read as absent")
    void valueForBlock() {
      assertContains(
          "compression: expected a block { ... }, found a value",
          only("forwarding { mode = none }\ncompression = 5\n"));
    }

    @Test
    @DisplayName(
        "should refuse a number written as text that is not one, rather than use the default")
    void notANumber() {
      assertContains(
          "compression.threshold: expected a whole number, found 'big'",
          only("forwarding { mode = none }\ncompression { threshold = big }\n"));
    }

    @Test
    @DisplayName("should refuse an unknown server in fallback-order, with the closest one")
    void fallbackOrder() {
      assertContains(
          "Did you mean 'lobby'?",
          only(
              "forwarding { mode = none }\nservers { lobby { address = \"localhost:25565\" } }\n"
                  + "fallback-order = [\"loby\"]\n"));
    }

    @Test
    @DisplayName("should report unknown keys and a wrong value together")
    void together() {
      List<Problem> problems =
          problems("forwarding { mode = none }\nonlin-mode = true\ncompression { level = 12 }\n");

      assertEquals(2, problems.size(), problems.toString());
      assertEquals(3, problems.get(1).line());
    }

    @Test
    @DisplayName("should name the line of a syntax error and say how HOCON is written")
    void syntax() {
      Problem problem = only("forwarding { mode = none }\nbind = \"0.0.0.0:25577\n");

      assertEquals(2, problem.line());
      assertContains("not valid HOCON", problem);
    }

    @Test
    @DisplayName("should refuse a file written for a newer Warp")
    void newerLayout() {
      assertContains(
          "written for a newer Warp",
          only(
              "config-version = "
                  + (WarpConfig.CONFIG_VERSION + 1)
                  + "\nforwarding { mode = none }\n"));
    }

    @Test
    @DisplayName("should refuse a default language that is not one")
    void defaultLocale() {
      assertContains(
          "messages.default-locale: expected a language",
          only("forwarding { mode = none }\nmessages { default-locale = \"french\" }\n"));
    }

    @Test
    @DisplayName("should read the default language")
    void defaultLocaleRead() throws Exception {
      WarpConfig config =
          load("forwarding { mode = none }\nmessages { default-locale = \"fr_FR\" }\n");

      assertEquals(Locale.FRANCE, config.messages().defaultLocale());
    }

    @Test
    @DisplayName("should report an invalid message override with the configuration")
    void messageOverride() throws Exception {
      Files.createDirectories(directory.resolve("messages"));
      Files.writeString(
          directory.resolve("messages").resolve("messages.properties"), "warp.nope = x\n");

      Problem problem = only("forwarding { mode = none }\n");

      assertTrue(
          problem.format().startsWith("messages/messages.properties, line 1"), problem.format());
    }
  }

  @Nested
  @DisplayName("forwarding secret")
  class ForwardingSecret {

    @Test
    @DisplayName("should refuse a secret file the operator named that does not exist")
    void missingNamed() {
      Problem problem =
          only("forwarding { mode = velocity, secret-file = \"secrets/proxy.key\" }\n");

      assertEquals("forwarding.secret-file", problem.key());
      assertContains("does not exist", problem);
      assertContains("paper-global.yml", problem);
    }

    @Test
    @DisplayName("should refuse an empty secret file")
    void empty() throws Exception {
      Files.writeString(directory.resolve("forwarding.secret"), "\n");

      assertContains("is empty", only("forwarding { mode = velocity }\n"));
    }

    @Test
    @DisplayName("should generate the default secret file when it does not exist, as documented")
    void generated() throws Exception {
      WarpConfig config = load("forwarding { mode = velocity }\n");

      String written = Files.readString(directory.resolve("forwarding.secret")).trim();
      assertArrayEquals(written.getBytes(StandardCharsets.UTF_8), config.forwardingSecret());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("should generate the default secret file readable by its owner only")
    void generatedForItsOwnerOnly() throws Exception {
      load("forwarding { mode = velocity }\n");

      assertEquals(
          PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(directory.resolve("forwarding.secret")));
    }

    @Test
    @DisplayName("should refuse a secret file that is a directory")
    void directory() throws Exception {
      Files.createDirectories(directory.resolve("forwarding.secret"));

      assertContains("is a directory", only("forwarding { mode = velocity }\n"));
    }
  }

  @Nested
  @DisplayName("default file")
  class DefaultFile {

    @Test
    @DisplayName("should document every setting it reads, so that none of its keys is unknown")
    void selfConsistent() throws Exception {
      WarpConfig.load(directory);

      ConfigFile written = ConfigFile.read(directory.resolve("warp.conf"));
      assertEquals(
          List.of(),
          written.compareWith(
              directory.resolve("warp.conf"),
              ConfigFile.parse(WarpConfig.defaultConfig()).normalized(),
              Set.of(),
              Map.of()));
    }

    @Test
    @DisplayName("should hold the layout version and the messages block")
    void documented() throws Exception {
      WarpConfig.load(directory);

      String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
      assertTrue(written.contains("\nconfig-version = 1\n"), written);
      assertTrue(written.contains("default-locale = \"en_US\""), written);
      assertTrue(Files.exists(directory.resolve("messages").resolve("messages.properties")));
    }
  }

  @Nested
  @DisplayName("suggestions")
  class SuggestionsTest {

    @Test
    @DisplayName("should count a swap, a missing, an extra and a wrong character as one edit each")
    void distance() {
      assertEquals(1, Suggestions.distance("thershold", "threshold"));
      assertEquals(1, Suggestions.distance("treshold", "threshold"));
      assertEquals(1, Suggestions.distance("thresholdd", "threshold"));
      assertEquals(1, Suggestions.distance("threshald", "threshold"));
    }

    @Test
    @DisplayName("should suggest nothing for a name too far from every known one")
    void tooFar() {
      assertEquals(null, Suggestions.closest("banana", List.of("bind", "online-mode")));
    }
  }
}
