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
package dev.warp.proxy.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LogCode")
class LogCodeTest {

  /** The proxy's main sources: the tests run in the module's directory. */
  private static final Path SOURCES = Path.of("src/main/java");

  /** The generated documentation. */
  private static final Path DOCS = Path.of("../docs/errors.md");

  /** Set to 1 to write docs/errors.md instead of checking it. */
  private static final String UPDATE = "WARP_UPDATE_DOCS";

  private static final Pattern ID = Pattern.compile("W-([A-Z]+)-(\\d{3})");

  /** A call to a logger's warn or error. */
  private static final Pattern WARN_OR_ERROR =
      Pattern.compile("\\b(\\w*(?:log|LOG)\\w*)\\.(warn|error)\\(");

  /** {@code LogCode.X.marker(),} or {@code e.code().marker(),}: a code as the first argument. */
  private static final Pattern FIRST_ARGUMENT_MARKER =
      Pattern.compile("\\s*[\\w.]+(?:\\(\\))?\\.marker\\(\\)\\s*,");

  /** The section of each category, in the order of the page. */
  private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

  static {
    CATEGORIES.put("CFG", "Configuration");
    CATEGORIES.put("START", "Startup");
    CATEGORIES.put("SYS", "The process");
    CATEGORIES.put("NAT", "Natives");
    CATEGORIES.put("PLG", "Plugins");
    CATEGORIES.put("EVT", "Events and plugin callbacks");
    CATEGORIES.put("CMD", "Commands");
    CATEGORIES.put("CON", "Console");
    CATEGORIES.put("DRAIN", "Drain");
    CATEGORIES.put("BOT", "Anti-bot");
    CATEGORIES.put("LOG", "Logging");
    CATEGORIES.put("MGMT", "Management");
    CATEGORIES.put("MSG", "BungeeCord channel");
    CATEGORIES.put("BACK", "Backends");
    CATEGORIES.put("AUTH", "Authentication and login");
    CATEGORIES.put("NET", "Connections");
  }

  @Nested
  @DisplayName("ids")
  class Ids {

    @Test
    @DisplayName("should be unique, well formed and numbered from 001 in each category")
    void shouldBeUniqueAndWellFormed() {
      // given
      Map<String, Integer> next = new LinkedHashMap<>();
      Set<String> seen = new HashSet<>();

      for (LogCode code : LogCode.values()) {
        // when
        Matcher matcher = ID.matcher(code.id());

        // then
        assertTrue(matcher.matches(), code + " has a malformed id: " + code.id());
        assertTrue(seen.add(code.id()), "duplicate id " + code.id());
        String category = matcher.group(1);
        assertTrue(CATEGORIES.containsKey(category), "no section for category " + category);
        int expected = next.merge(category, 1, Integer::sum);
        assertEquals(expected, Integer.parseInt(matcher.group(2)), code + " skips a number");
      }
    }

    @Test
    @DisplayName("should find a code by its id, and nothing for other names")
    void shouldFindCodeById() {
      // given
      LogCode code = LogCode.PORT_IN_USE;

      // when
      LogCode found = LogCode.byId(code.id());

      // then
      assertEquals(code, found);
      assertNull(LogCode.byId("WARP_FILE_ONLY"));
      assertNull(LogCode.byId(null));
      assertEquals(code.id(), code.marker().getName());
    }

    @Test
    @DisplayName("should link each code to its anchor in docs/errors.md")
    void shouldLinkToItsAnchor() {
      // given
      LogCode code = LogCode.CONFIG_INVALID;

      // when
      String link = code.documentation();

      // then
      assertEquals(LogCode.DOCUMENTATION + "#w-cfg-001", link);
    }
  }

  @Nested
  @DisplayName("documentation")
  class Documentation {

    @Test
    @DisplayName("should match docs/errors.md, which is generated from the codes")
    void shouldMatchTheDocumentation() throws IOException {
      // given
      String expected = render();

      // when
      if ("1".equals(System.getenv(UPDATE))) {
        Files.writeString(DOCS, expected, StandardCharsets.UTF_8);
      }
      String actual = Files.isRegularFile(DOCS) ? Files.readString(DOCS) : "";

      // then
      assertEquals(
          expected,
          actual,
          "docs/errors.md is out of date: run "
              + UPDATE
              + "=1 ./gradlew :proxy:test --tests '*LogCodeTest' and commit it");
    }

    @Test
    @DisplayName("should document no code that does not exist")
    void shouldDocumentOnlyExistingCodes() throws IOException {
      // given
      String docs = Files.readString(DOCS);

      // when
      Matcher matcher = ID.matcher(docs);

      // then
      while (matcher.find()) {
        assertNotNull(LogCode.byId(matcher.group()), matcher.group() + " is documented, unknown");
      }
    }

    @Test
    @DisplayName("should never use the em dash, which the project keeps out of its pages")
    void shouldAvoidEmDashes() {
      // given
      String docs = render();

      // when
      int found = docs.indexOf('—');

      // then
      assertEquals(-1, found, "em dash in the documentation of the codes");
    }
  }

  @Nested
  @DisplayName("use in the proxy")
  class Use {

    @Test
    @DisplayName("should use every code at least once")
    void shouldUseEveryCode() throws IOException {
      // given
      String sources = allSources();

      for (LogCode code : LogCode.values()) {
        // when
        boolean used = sources.contains("LogCode." + code.name());

        // then
        assertTrue(used, code + " (" + code.id() + ") is documented but never logged");
      }
    }

    @Test
    @DisplayName("should give every warning and error of the proxy a code")
    void shouldCodeEveryWarningAndError() throws IOException {
      // given
      List<String> missing = new ArrayList<>();

      for (Path file : javaFiles()) {
        String text = Files.readString(file);
        Matcher matcher = WARN_OR_ERROR.matcher(text);
        while (matcher.find()) {
          // when
          String rest = text.substring(matcher.end(), Math.min(text.length(), matcher.end() + 200));

          // then: the first argument is a code's marker
          if (!FIRST_ARGUMENT_MARKER.matcher(rest).lookingAt()) {
            missing.add(SOURCES.relativize(file) + ":" + line(text, matcher.start()));
          }
        }
      }
      assertEquals(List.of(), missing, "warnings and errors without a LogCode marker");
    }

    @Test
    @DisplayName("should write codes only through LogCode, never as text")
    void shouldNotWriteCodesAsText() throws IOException {
      // given
      List<String> literals = new ArrayList<>();

      for (Path file : javaFiles()) {
        if (file.endsWith(Path.of("dev/warp/proxy/logging/LogCode.java"))) {
          continue;
        }
        // when
        Matcher matcher = ID.matcher(Files.readString(file));

        // then
        while (matcher.find()) {
          literals.add(SOURCES.relativize(file) + ": " + matcher.group());
        }
      }
      assertEquals(List.of(), literals, "codes written outside LogCode");
    }
  }

  // ---------------------------------------------------------------------------
  // The page
  // ---------------------------------------------------------------------------

  /** Renders docs/errors.md. */
  String render() {
    StringBuilder page = new StringBuilder();
    page.append("# Warning and error codes\n\n")
        .append(
            "<!-- Generated from proxy/src/main/java/dev/warp/proxy/logging/LogCode.java: after"
                + " changing a code, run\n     "
                + UPDATE
                + "=1 ./gradlew :proxy:test --tests '*LogCodeTest' -->\n\n")
        .append(
            "Every warning and error Warp writes for its operator ends with a code, such as"
                + " `W-CFG-001`: dimmed at the end of the console line (a link to its entry here, on"
                + " terminals that show links), in brackets at the end of the line in"
                + " `logs/warp.log`, and as `marker` in the JSON format. Search this page for the"
                + " code to learn why it happened and what to do.\n\n")
        .append(
            "A code keeps its meaning for good: a warning that goes away retires its code, which"
                + " is never given to another.\n\n");
    page.append("| Code | Level | What happened |\n|---|---|---|\n");
    for (LogCode code : LogCode.values()) {
      page.append("| [")
          .append(code.id())
          .append("](#")
          .append(code.id().toLowerCase(Locale.ROOT))
          .append(") | ")
          .append(code.error() ? "error" : "warning")
          .append(" | ")
          .append(code.title())
          .append(" |\n");
    }
    String section = null;
    for (LogCode code : LogCode.values()) {
      Matcher matcher = ID.matcher(code.id());
      String category = matcher.matches() ? matcher.group(1) : "";
      String title = CATEGORIES.getOrDefault(category, category);
      if (!title.equals(section)) {
        page.append("\n## ").append(title).append('\n');
        section = title;
      }
      page.append("\n### ")
          .append(code.id())
          .append("\n\n**")
          .append(code.title())
          .append("** (")
          .append(code.error() ? "error" : "warning")
          .append(")\n\n")
          .append("**Cause.** ")
          .append(code.cause())
          .append("\n\n**Fix.** ")
          .append(code.fix())
          .append('\n');
    }
    return page.toString();
  }

  private List<Path> javaFiles() throws IOException {
    try (Stream<Path> files = Files.walk(SOURCES)) {
      return files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
    }
  }

  private String allSources() throws IOException {
    StringBuilder all = new StringBuilder();
    for (Path file : javaFiles()) {
      if (!file.endsWith(Path.of("dev/warp/proxy/logging/LogCode.java"))) {
        all.append(Files.readString(file)).append('\n');
      }
    }
    return all.toString();
  }

  private int line(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
