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

import dev.warp.api.command.CommandSource;
import dev.warp.proxy.command.WarpCommandManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.Message;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jspecify.annotations.Nullable;

/**
 * The JLine side of the command line: tab completion from the dispatcher's suggestions (with their
 * tooltips), and highlighting with the argument hint shown after the cursor. Neither may break the
 * console: anything they throw leaves the line as typed, without completion or colours.
 */
final class CommandLineSupport implements Completer, Highlighter {

  /** How long completion waits for suggestions, which a plugin's provider may compute slowly. */
  private static final long SUGGESTION_TIMEOUT_MILLIS = 1000;

  private final WarpCommandManager commands;
  private final CommandSource source;
  private final Palette palette;

  /** The line as it was when Tab listed its candidates, or {@code null}; the reader's thread. */
  private volatile @Nullable String listed;

  /**
   * Creates the support of a console.
   *
   * @param commands the commands
   * @param source the console
   * @param palette the colours of the terminal
   */
  CommandLineSupport(WarpCommandManager commands, CommandSource source, Palette palette) {
    this.commands = commands;
    this.source = source;
    this.palette = palette;
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    try {
      candidates.addAll(candidates(line.line(), line.cursor(), line.cursor() - line.wordCursor()));
    } catch (Exception e) {
      // No completion rather than a broken console.
    }
  }

  /**
   * Returns the completions of a line: each replaces the word under the cursor, as JLine does.
   *
   * @param line the whole line
   * @param cursor where the cursor is
   * @param wordStart where the word under the cursor starts
   * @return the candidates
   * @throws Exception if the suggestions fail or take too long
   */
  List<Candidate> candidates(String line, int cursor, int wordStart) throws Exception {
    String before = line.substring(0, cursor);
    int offset = 0;
    while (offset < before.length() && before.charAt(offset) == ' ') {
      offset++;
    }
    if (offset < before.length() && before.charAt(offset) == '/') {
      offset++;
    }
    Suggestions suggestions =
        commands
            .suggest(source, before.substring(offset))
            .get(SUGGESTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    List<Candidate> candidates = new java.util.ArrayList<>();
    for (Suggestion suggestion : suggestions.getList()) {
      int start = offset + suggestion.getRange().getStart();
      if (start < wordStart || start > cursor) {
        continue;
      }
      String value = line.substring(wordStart, start) + suggestion.getText();
      Message tooltip = suggestion.getTooltip();
      String description = tooltip == null ? null : tooltip.getString();
      candidates.add(
          new Candidate(value, suggestion.getText(), null, description, null, null, true));
    }
    return candidates;
  }

  @Override
  public AttributedString highlight(LineReader reader, String buffer) {
    try {
      CommandInput.Analysis analysis = CommandInput.analyse(commands, source, buffer);
      // After Tab, the list of candidates shows instead of the hint, until the line changes: in
      // tail tip mode, JLine clears that list at the next redraw (a log line, the status line)
      // when the line ends with a space.
      if ("\t".equals(reader.getLastBinding())) {
        listed = buffer;
      }
      boolean listing = buffer.equals(listed);
      if (!listing) {
        listed = null;
      }
      reader.setAutosuggestion(
          listing ? LineReader.SuggestionType.NONE : LineReader.SuggestionType.TAIL_TIP);
      reader.setTailTip(CommandInput.tailTip(buffer, analysis));
      return render(buffer, analysis);
    } catch (RuntimeException e) {
      reader.setTailTip("");
      return new AttributedString(buffer);
    }
  }

  /**
   * Styles a line by its analysis.
   *
   * @param buffer the line
   * @param analysis its analysis
   * @return the styled line
   */
  AttributedString render(String buffer, CommandInput.Analysis analysis) {
    AttributedStringBuilder out = new AttributedStringBuilder(buffer.length());
    int position = 0;
    for (CommandInput.Span span : analysis.spans()) {
      int start = Math.clamp(span.start(), position, buffer.length());
      int end = Math.clamp(span.end(), start, buffer.length());
      out.append(buffer, position, start);
      out.styled(palette.jline(span.style()), buffer.substring(start, end));
      position = end;
    }
    out.append(buffer, position, buffer.length());
    return out.toAttributedString();
  }
}
