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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Hyperlinks")
class HyperlinksTest {

  private record Piece(String text, @Nullable String uri) {}

  private static List<Piece> split(String text) {
    List<Piece> pieces = new ArrayList<>();
    Hyperlinks.split(text, (piece, uri) -> pieces.add(new Piece(piece, uri)));
    return pieces;
  }

  private static final Palette LINKS = Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, true);

  @Nested
  @DisplayName("URLs")
  class Urls {

    @Test
    @DisplayName("should link a URL without the punctuation that ends the sentence")
    void shouldTrimPunctuation() {
      // given
      String text =
          "Install Java 25 (for example Eclipse Temurin, https://adoptium.net), then retry.";

      // when
      List<Piece> pieces = split(text);

      // then
      assertEquals(3, pieces.size(), pieces.toString());
      assertEquals(new Piece("https://adoptium.net", "https://adoptium.net"), pieces.get(1));
      assertTrue(pieces.get(2).text().startsWith("), then"));
    }

    @Test
    @DisplayName("should keep a closing parenthesis that the URL opened")
    void shouldKeepBalancedParentheses() {
      // given
      String url = "https://en.wikipedia.org/wiki/Warp_(video_game)";

      // when
      String trimmed = Hyperlinks.trimUrl(url + ".");

      // then
      assertEquals(url, trimmed);
    }

    @Test
    @DisplayName("should leave text without a link as one plain piece")
    void shouldLeavePlainText() {
      // given
      String text = "Ready in 1.24 s";

      // when
      List<Piece> pieces = split(text);

      // then
      assertEquals(List.of(new Piece(text, null)), pieces);
    }
  }

  @Nested
  @DisplayName("file paths")
  class Paths {

    @Test
    @DisplayName("should link a relative path to the file in the working directory")
    void shouldLinkRelativePaths() {
      // given
      String text = "JSON layout in src/main/resources/warp-log-layout.json";

      // when
      List<Piece> pieces = split(text);

      // then
      Piece path = pieces.get(1);
      assertEquals("src/main/resources/warp-log-layout.json", path.text());
      assertNotNull(path.uri());
      assertTrue(path.uri().startsWith("file://"), path.uri());
      String expected =
          Path.of("src/main/resources/warp-log-layout.json").toAbsolutePath().toUri().getRawPath();
      assertTrue(path.uri().endsWith(expected), path.uri());
    }

    @Test
    @DisplayName("should not link a path to no file, such as a backend's")
    void shouldNotLinkMissingFiles() {
      // given
      String text = "Paper: proxies.velocity.secret in config/paper-global.yml";

      // when
      List<Piece> pieces = split(text);

      // then
      assertEquals(List.of(new Piece(text, null)), pieces);
    }

    @Test
    @DisplayName("should not take a version or an address for a file")
    void shouldIgnoreVersionsAndAddresses() {
      // given
      String text = "Paper 1.21.4 at 127.0.0.1:25565, Warp 0.1.0";

      // when
      List<Piece> pieces = split(text);

      // then
      assertEquals(1, pieces.size(), pieces.toString());
    }
  }

  @Nested
  @DisplayName("in styled text")
  class InStyledText {

    @Test
    @DisplayName("should wrap the text in OSC 8 on an output that shows links")
    void shouldWrapInOsc8() {
      // given
      Styled out = new Styled(LINKS, Glyphs.UNICODE);

      // when
      String text = out.link("https://example.com", null, "docs").toString();

      // then
      assertEquals("\u001B]8;;https://example.com\u001B\\docs\u001B]8;;\u001B\\", text);
    }

    @Test
    @DisplayName("should write the text alone where links do not show")
    void shouldWriteTextAlone() {
      // given
      Styled colours =
          new Styled(Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false), Glyphs.UNICODE);
      Styled plain = new Styled(Palette.PLAIN, Glyphs.UNICODE);

      // when
      String coloured = colours.appendLinking(null, "see https://example.com").toString();
      String text = plain.link("https://example.com", Style.MUTED, "docs").toString();

      // then
      assertFalse(coloured.contains("\u001B]8"), coloured);
      assertEquals("docs", text);
    }

    @Test
    @DisplayName("should never let a palette without colours show links")
    void shouldNeedColours() {
      // given
      Palette palette = Palette.of(ColorDepth.NONE, Theme.DARK, true);

      // when
      boolean links = palette.hyperlinks();

      // then
      assertFalse(links);
    }

    @Test
    @DisplayName("should strip links with every other escape sequence")
    void shouldStripLinks() {
      // given
      String linked =
          new Styled(LINKS, Glyphs.UNICODE).appendLinking(null, "at https://x.dev").toString();

      // when
      String stripped = Ansi.strip(linked);

      // then
      assertEquals("at https://x.dev", stripped);
    }
  }
}
