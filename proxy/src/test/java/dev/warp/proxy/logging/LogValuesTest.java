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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("LogValues")
class LogValuesTest {

  /** Whether a log reader, a terminal or a JSON consumer could act on the character. */
  private static boolean actionable(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.CONTROL
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR
        || type == Character.FORMAT
        || type == Character.SURROGATE;
  }

  @Nested
  @DisplayName("line breaks")
  class LineBreaks {

    @Test
    @DisplayName("should write a line feed as \\n")
    void lineFeed() {
      String hostile = "echo hi\n[12:00:00 INFO]: forged";

      String safe = LogValues.safe(hostile);

      assertEquals("echo hi\\n[12:00:00 INFO]: forged", safe);
    }

    @Test
    @DisplayName("should write a carriage return as \\r")
    void carriageReturn() {
      String hostile = "echo hi\rforged";

      String safe = LogValues.safe(hostile);

      assertEquals("echo hi\\rforged", safe);
    }

    @Test
    @DisplayName("should write both halves of a CRLF")
    void crlf() {
      String hostile = "a\r\nb";

      String safe = LogValues.safe(hostile);

      assertEquals("a\\r\\nb", safe);
    }

    @Test
    @DisplayName("should escape the Unicode line and paragraph separators")
    void unicodeSeparators() {
      String hostile = "a\u2028b\u2029c";

      String safe = LogValues.safe(hostile);

      assertEquals("a\\u2028b\\u2029c", safe);
    }

    @Test
    @DisplayName("should escape the next-line control, U+0085")
    void nextLine() {
      String hostile = "a\u0085b";

      String safe = LogValues.safe(hostile);

      assertEquals("a\\u0085b", safe);
    }
  }

  @Nested
  @DisplayName("control characters")
  class ControlCharacters {

    @Test
    @DisplayName("should escape NUL")
    void nul() {
      String hostile = "a\u0000b";

      String safe = LogValues.safe(hostile);

      assertEquals("a\\u0000b", safe);
    }

    @Test
    @DisplayName("should escape the ESC of a terminal escape sequence, leaving its text inert")
    void escapeSequence() {
      String hostile = "\u001B[2J\u001B[31mred\u001B[0m";

      String safe = LogValues.safe(hostile);

      assertEquals("\\u001B[2J\\u001B[31mred\\u001B[0m", safe);
    }

    @Test
    @DisplayName("should write a tab as \\t")
    void tab() {
      String hostile = "a\tb";

      String safe = LogValues.safe(hostile);

      assertEquals("a\\tb", safe);
    }

    @Test
    @DisplayName("should escape DEL, the C1 controls and the bell")
    void otherControls() {
      String hostile = "\u0007\u007F\u009B";

      String safe = LogValues.safe(hostile);

      assertEquals("\\u0007\\u007F\\u009B", safe);
    }

    @Test
    @DisplayName("should escape a bidirectional override, which makes text read backwards")
    void bidiOverride() {
      String hostile = "user\u202Enimda";

      String safe = LogValues.safe(hostile);

      assertEquals("user\\u202Enimda", safe);
    }

    @Test
    @DisplayName("should escape an unpaired surrogate")
    void unpairedSurrogate() {
      String hostile = "a\uD800b";

      String safe = LogValues.safe(hostile);

      assertEquals("a\\uD800b", safe);
    }

    static Stream<String> hostile() {
      StringBuilder every = new StringBuilder();
      for (char c = 0; c < 0x100; c++) {
        every.append(c);
      }
      return Stream.of(
          every.toString(),
          "\r\n\u2028\u2029\u0085\u000B\u000C",
          "\u001B]0;title\u0007\u001B[1A\u001B[2K",
          "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF",
          "\uDC00\uD800");
    }

    @ParameterizedTest
    @MethodSource("hostile")
    @DisplayName("should leave nothing a log reader or a terminal would act on")
    void nothingActionable(String hostile) {
      String safe = LogValues.safe(hostile, 10_000);

      assertTrue(safe.codePoints().noneMatch(LogValuesTest::actionable), safe);
    }
  }

  @Nested
  @DisplayName("printable text")
  class PrintableText {

    @Test
    @DisplayName("should keep printable text as it is, accents, quotes and emoji included")
    void unchanged() {
      String text = "say \"H\u00E9llo\" \u4E16\u754C \uD83D\uDE00 ~!@#$%^&*()_+{}|:<>?\\";

      String safe = LogValues.safe(text);

      assertEquals(text, safe);
    }

    @Test
    @DisplayName("should keep the empty text empty")
    void empty() {
      String safe = LogValues.safe("");

      assertEquals("", safe);
    }

    @Test
    @DisplayName("should write null as the logger would")
    void nullValue() {
      String safe = LogValues.safe(null);

      assertEquals("null", safe);
    }
  }

  @Nested
  @DisplayName("length")
  class Length {

    @Test
    @DisplayName("should keep a value of exactly the maximum length whole")
    void atTheLimit() {
      String value = "a".repeat(LogValues.MAX_LENGTH);

      String safe = LogValues.safe(value);

      assertEquals(value, safe);
    }

    @Test
    @DisplayName("should cut a longer value and say how many characters it left out")
    void cut() {
      String value = "a".repeat(LogValues.MAX_LENGTH) + "b".repeat(1000);

      String safe = LogValues.safe(value);

      assertEquals("a".repeat(LogValues.MAX_LENGTH) + "...(1000 more)", safe);
    }

    @Test
    @DisplayName("should honour a maximum the caller gives")
    void customMaximum() {
      String value = "abcdef";

      String safe = LogValues.safe(value, 4);

      assertEquals("abcd...(2 more)", safe);
    }

    @Test
    @DisplayName("should count the characters of the value, not of their escapes")
    void countsBeforeEscaping() {
      String value = "\n\n\nabc";

      String safe = LogValues.safe(value, 4);

      assertEquals("\\n\\n\\na...(2 more)", safe);
    }

    @Test
    @DisplayName("should not cut a surrogate pair in half")
    void surrogatePair() {
      String value = "abc\uD83D\uDE00def";

      String safe = LogValues.safe(value, 4);

      assertEquals("abc...(5 more)", safe);
    }

    @Test
    @DisplayName("should reject a maximum below 1")
    void invalidMaximum() {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> LogValues.safe("a", 0));

      assertEquals("maxLength must be 1 or more, not 0", error.getMessage());
    }
  }
}
