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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.jspecify.annotations.Nullable;

/**
 * Reads the command line being typed at the console with the proxy's Brigadier dispatcher: which
 * parts are a known command, its arguments, or an error, and what the arguments still to type are.
 * Pure and quick: it runs on every key press.
 */
final class CommandInput {

  /** Hints longer than this are cut: the line being typed matters more. */
  private static final int MAX_HINT = 60;

  private CommandInput() {}

  /**
   * A styled range of the line.
   *
   * @param start the first character
   * @param end after the last character
   * @param style the style
   */
  record Span(int start, int end, Style style) {}

  /**
   * What the line holds.
   *
   * @param spans the styled ranges, in order, not overlapping
   * @param hint the usage of the arguments after the word being typed, such as {@code <server>};
   *     empty when there is nothing to hint
   * @param midWord whether the line ends in the middle of a word, rather than after a space
   */
  record Analysis(List<Span> spans, String hint, boolean midWord) {}

  /**
   * Analyses a line.
   *
   * @param commands the commands
   * @param source who types
   * @param line the whole line, possibly with a leading {@code /}
   * @return what it holds
   */
  static Analysis analyse(WarpCommandManager commands, CommandSource source, String line) {
    int offset = 0;
    while (offset < line.length() && line.charAt(offset) == ' ') {
      offset++;
    }
    if (offset < line.length() && line.charAt(offset) == '/') {
      offset++;
    }
    String text = line.substring(offset);
    boolean midWord = !text.isEmpty() && !text.endsWith(" ");
    if (text.isBlank()) {
      return new Analysis(List.of(), "", false);
    }
    List<Span> spans = new ArrayList<>();
    int space = text.indexOf(' ');
    String name = space < 0 ? text : text.substring(0, space);
    if (!commands.hasCommand(name)) {
      boolean typing = space < 0 && startsAnyCommand(commands.root().getChildren(), name);
      if (!typing) {
        spans.add(new Span(offset, offset + name.length(), Style.BAD));
      }
      return new Analysis(spans, "", midWord);
    }

    ParseResults<CommandSource> parse = commands.parse(source, text);
    CommandNode<CommandSource> last = commands.root();
    int lastEnd = 0;
    for (CommandContextBuilder<CommandSource> context = parse.getContext();
        context != null;
        context = context.getChild()) {
      for (ParsedCommandNode<CommandSource> parsed : context.getNodes()) {
        int start = parsed.getRange().getStart();
        int end = Math.min(parsed.getRange().getEnd(), text.length());
        Style style =
            parsed.getNode() instanceof LiteralCommandNode ? Style.ACCENT : Style.ARGUMENT;
        if (end > start) {
          spans.add(new Span(offset + start, offset + end, style));
        }
        last = parsed.getNode();
        lastEnd = end;
      }
    }

    int consumed = Math.min(parse.getReader().getCursor(), text.length());
    String remaining = text.substring(consumed);
    String pending = remaining.stripLeading();
    if (!pending.isEmpty()) {
      boolean typingLiteral =
          midWord && pending.indexOf(' ') < 0 && startsAnyCommand(last.getChildren(), pending);
      if (!typingLiteral) {
        int start = consumed + (remaining.length() - pending.length());
        spans.add(new Span(offset + start, offset + text.length(), Style.BAD));
      }
      return new Analysis(spans, "", midWord);
    }
    // The hint follows the node of the last word: the word being typed, or the one before the
    // space.
    boolean lastWordParsed = midWord ? lastEnd == text.length() : true;
    String hint = lastWordParsed ? usageAfter(commands, source, last) : "";
    return new Analysis(spans, hint, midWord);
  }

  /**
   * Returns the usage of a command line that stops before its command does, such as {@code warp
   * antibot (lockdown|status|trust)} for {@code warp antibot}: what the console replies, rather
   * than Brigadier's "unknown command at position 12".
   *
   * @param commands the commands
   * @param source who runs it
   * @param line the line, without {@code /}
   * @return the usage, or {@code null} when the line is complete, or wrong rather than short
   */
  static @Nullable String incompleteUsage(
      WarpCommandManager commands, CommandSource source, String line) {
    ParseResults<CommandSource> parse = commands.parse(source, line);
    CommandContextBuilder<CommandSource> last = parse.getContext().getLastChild();
    if (parse.getReader().canRead() || last.getCommand() != null || last.getNodes().isEmpty()) {
      return null;
    }
    String usage = usageAfter(commands, source, last.getNodes().getLast().getNode());
    return usage.isEmpty() ? null : line.strip() + " " + usage;
  }

  /**
   * Returns the tail tip that makes JLine show {@code hint} after the line, given how JLine treats
   * a tail tip ({@code LineReaderImpl#getDisplayedBufferWithPrompts}): after a space it shows it as
   * it is; in a word it drops the tip's first word, unless the word being typed has a dash, in
   * which case it adds a space in front.
   *
   * @param line the line being typed
   * @param analysis its analysis
   * @return the tail tip
   */
  static String tailTip(String line, Analysis analysis) {
    String hint = analysis.hint();
    if (hint.isEmpty()) {
      return "";
    }
    if (!analysis.midWord()) {
      return hint;
    }
    int lastSpace = line.lastIndexOf(' ');
    int lastDash = line.lastIndexOf('-');
    boolean dropsFirstWord = lastSpace == -1 ? lastDash == -1 : lastSpace > lastDash;
    boolean addsSpace = lastSpace >= 0 && lastSpace < lastDash;
    if (dropsFirstWord) {
      return "_ " + hint;
    }
    return addsSpace ? hint : " " + hint;
  }

  /** {@code <player> <server>} after {@code send}; {@code (on|off)} for literal choices. */
  private static String usageAfter(
      WarpCommandManager commands, CommandSource source, CommandNode<CommandSource> node) {
    Collection<String> usages = commands.usage(node, source).values();
    if (usages.isEmpty()) {
      return "";
    }
    String hint;
    if (usages.size() == 1) {
      hint = usages.iterator().next();
    } else {
      // Brigadier writes [child] when the node runs alone too: the choices are then optional.
      boolean optional = usages.iterator().next().startsWith("[");
      StringJoiner choices = new StringJoiner("|", optional ? "[" : "(", optional ? "]" : ")");
      for (String usage : usages) {
        String first = usage.replaceAll("^[\\[(]+", "");
        int space = first.indexOf(' ');
        choices.add((space < 0 ? first : first.substring(0, space)).replaceAll("[\\])]+$", ""));
      }
      hint = choices.toString();
    }
    return hint.length() > MAX_HINT ? hint.substring(0, MAX_HINT - 1) + "…" : hint;
  }

  private static boolean startsAnyCommand(
      Collection<? extends CommandNode<CommandSource>> children, String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    for (CommandNode<CommandSource> child : children) {
      if (child instanceof LiteralCommandNode<CommandSource> literal
          && literal.getLiteral().toLowerCase(Locale.ROOT).startsWith(lower)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the closest command name to an unknown one, for a "did you mean" hint.
   *
   * @param names the known names
   * @param unknown what was typed
   * @return the closest name within two edits, or {@code null}
   */
  static @Nullable String closest(Collection<String> names, String unknown) {
    String best = null;
    int bestDistance = 3;
    String lower = unknown.toLowerCase(Locale.ROOT);
    for (String name : names) {
      int distance = distance(lower, name);
      if (distance < bestDistance) {
        best = name;
        bestDistance = distance;
      }
    }
    return best;
  }

  /** Levenshtein distance. */
  private static int distance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
