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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.logging.log4j.message.Message;
import org.jspecify.annotations.Nullable;

/**
 * A block of styled lines the console prints as it is, without the time, level and source of a log
 * line: the banner, the startup and shutdown summaries. As a log {@link Message} it is one record,
 * whose text (a title, then the lines in plain text) goes to the log file and the JSON format.
 */
public final class ConsoleBlock implements Message {

  private static final long serialVersionUID = 1L;

  private static final Object[] NO_PARAMETERS = {};

  private final String title;
  private final transient List<List<Segment>> lines;
  private final String plain;

  /** A piece of a line: text in a style, or a symbol picked by the output's {@link Glyphs}. */
  record Segment(
      @Nullable Style style, @Nullable String text, @Nullable Function<Glyphs, String> glyph) {

    String text(Glyphs glyphs) {
      Function<Glyphs, String> symbol = glyph;
      String value = text;
      return symbol != null ? symbol.apply(glyphs) : value != null ? value : "";
    }
  }

  private ConsoleBlock(String title, List<List<Segment>> lines) {
    this.title = title;
    this.lines = lines;
    StringBuilder text = new StringBuilder(title);
    for (List<Segment> line : lines) {
      text.append('\n');
      int start = text.length();
      for (Segment segment : line) {
        text.append(segment.text(Glyphs.ASCII));
      }
      // Trailing spaces of padded columns mean nothing in a file.
      int end = text.length();
      while (end > start && text.charAt(end - 1) == ' ') {
        end--;
      }
      text.setLength(end);
    }
    this.plain = text.toString();
  }

  /**
   * Starts a block.
   *
   * @param title its first line in the log file and the JSON format, which the console does not
   *     show: the block says it better
   * @return a builder
   */
  static Builder builder(String title) {
    return new Builder(title);
  }

  /**
   * Renders the block for the console.
   *
   * @param depth the colours of the output
   * @param glyphs the symbols the output can show
   * @return the lines, separated by line feeds
   */
  String render(ColorDepth depth, Glyphs glyphs) {
    Styled out = new Styled(depth, glyphs);
    boolean first = true;
    for (List<Segment> line : lines) {
      if (!first) {
        out.append('\n');
      }
      first = false;
      for (Segment segment : line) {
        out.append(segment.style(), segment.text(glyphs));
      }
    }
    return out.toString();
  }

  /** Returns the title, the first line of the plain text. */
  String title() {
    return title;
  }

  @Override
  public String getFormattedMessage() {
    return plain;
  }

  @Override
  @SuppressWarnings("deprecation") // Still part of Message; deprecated for Log4j 3.
  public String getFormat() {
    return plain;
  }

  @Override
  public Object[] getParameters() {
    return NO_PARAMETERS;
  }

  @Override
  public @Nullable Throwable getThrowable() {
    return null;
  }

  /** Builds a block line by line. */
  static final class Builder {

    private final String title;
    private final List<List<Segment>> lines = new ArrayList<>();
    private List<Segment> current = new ArrayList<>();

    private Builder(String title) {
      this.title = title;
    }

    /**
     * Appends styled text to the current line.
     *
     * @param style the style, or {@code null}
     * @param text the text
     * @return this builder
     */
    Builder text(@Nullable Style style, String text) {
      current.add(new Segment(style, text, null));
      return this;
    }

    /**
     * Appends plain text to the current line.
     *
     * @param text the text
     * @return this builder
     */
    Builder text(String text) {
      return text(null, text);
    }

    /**
     * Appends a symbol to the current line.
     *
     * @param style the style, or {@code null}
     * @param glyph picks the symbol
     * @return this builder
     */
    Builder glyph(@Nullable Style style, Function<Glyphs, String> glyph) {
      current.add(new Segment(style, null, glyph));
      return this;
    }

    /**
     * Ends the current line.
     *
     * @return this builder
     */
    Builder end() {
      lines.add(List.copyOf(current));
      current = new ArrayList<>();
      return this;
    }

    /**
     * Returns the block, ending the current line if it holds anything.
     *
     * @return the block
     */
    ConsoleBlock build() {
      if (!current.isEmpty()) {
        end();
      }
      return new ConsoleBlock(title, List.copyOf(lines));
    }
  }
}
