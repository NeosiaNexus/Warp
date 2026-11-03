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
package dev.warp.protocol.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites a component for the action bar of 1.8 to 1.10, which only shows the formatting written
 * into its text.
 *
 * <p>Those clients show an action bar from the Chat packet's game info position, through {@code
 * IChatComponent#getUnformattedText}: the text of every component in the tree, without their styles
 * (MC-119145, fixed in 1.11 by the Title packet's action bar). The font renderer still reads
 * section sign codes in that text. So the styles become codes in text segments placed before what
 * they style, and the contents stay components: the client still translates a translatable and its
 * arguments, which BungeeCord and Velocity lose by sending flat legacy text.
 */
final class LegacyOverlay {

  private static final char SECTION = '§';

  /** The code of each named colour. */
  private static final Map<NamedTextColor, Character> COLOR_CODES =
      Map.ofEntries(
          Map.entry(NamedTextColor.BLACK, '0'),
          Map.entry(NamedTextColor.DARK_BLUE, '1'),
          Map.entry(NamedTextColor.DARK_GREEN, '2'),
          Map.entry(NamedTextColor.DARK_AQUA, '3'),
          Map.entry(NamedTextColor.DARK_RED, '4'),
          Map.entry(NamedTextColor.DARK_PURPLE, '5'),
          Map.entry(NamedTextColor.GOLD, '6'),
          Map.entry(NamedTextColor.GRAY, '7'),
          Map.entry(NamedTextColor.DARK_GRAY, '8'),
          Map.entry(NamedTextColor.BLUE, '9'),
          Map.entry(NamedTextColor.GREEN, 'a'),
          Map.entry(NamedTextColor.AQUA, 'b'),
          Map.entry(NamedTextColor.RED, 'c'),
          Map.entry(NamedTextColor.LIGHT_PURPLE, 'd'),
          Map.entry(NamedTextColor.YELLOW, 'e'),
          Map.entry(NamedTextColor.WHITE, 'f'));

  /** Decorations and their codes, in the order the legacy format writes them. */
  private static final TextDecoration[] DECORATIONS = {
    TextDecoration.OBFUSCATED,
    TextDecoration.BOLD,
    TextDecoration.STRIKETHROUGH,
    TextDecoration.UNDERLINED,
    TextDecoration.ITALIC
  };

  private static final char[] DECORATION_CODES = {'k', 'l', 'm', 'n', 'o'};

  private LegacyOverlay() {}

  /**
   * Returns {@code component} with its formatting moved into section sign codes.
   *
   * @param component the action bar text
   * @return an unstyled component that shows the same on a 1.8 to 1.10 action bar
   */
  static Component flatten(Component component) {
    List<Component> segments = new ArrayList<>();
    walk(component, Style.empty(), segments, new StringBuilder());
    return Component.text().append(segments).build();
  }

  /**
   * Appends the segments of {@code node} and its children: the codes of its style when they differ
   * from those in effect, then its contents without style.
   *
   * @param current the codes in effect, updated as codes are appended
   */
  private static void walk(
      Component node, Style inherited, List<Component> segments, StringBuilder current) {
    Style style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
    Component contents = contents(node);
    if (contents != null) {
      String codes = codes(style);
      if (!codes.contentEquals(current)) {
        segments.add(Component.text(SECTION + "r" + codes));
        current.setLength(0);
        current.append(codes);
      }
      segments.add(contents);
    }
    for (Component child : node.children()) {
      walk(child, style, segments, current);
    }
  }

  /** The contents of a node alone, or {@code null} for empty text. */
  private static @Nullable Component contents(Component node) {
    if (node instanceof TextComponent text) {
      return text.content().isEmpty() ? null : Component.text(text.content());
    }
    return unstyled(node).children(List.of());
  }

  /** A component and its children, translation arguments included, without any style. */
  private static Component unstyled(Component component) {
    List<Component> children = new ArrayList<>(component.children().size());
    for (Component child : component.children()) {
      children.add(unstyled(child));
    }
    Component bare = component.style(Style.empty()).children(children);
    if (bare instanceof TranslatableComponent translatable && !translatable.arguments().isEmpty()) {
      List<TranslationArgument> arguments = new ArrayList<>(translatable.arguments().size());
      for (TranslationArgument argument : translatable.arguments()) {
        arguments.add(
            argument.value() instanceof Component value
                ? TranslationArgument.component(unstyled(value))
                : argument);
      }
      return translatable.arguments(arguments);
    }
    return bare;
  }

  /** The codes of a style: its colour, nearest named one, then its decorations. */
  private static String codes(Style style) {
    StringBuilder codes = new StringBuilder();
    TextColor color = style.color();
    if (color != null) {
      codes.append(SECTION).append(COLOR_CODES.get(NamedTextColor.nearestTo(color)));
    }
    for (int i = 0; i < DECORATIONS.length; i++) {
      if (style.decoration(DECORATIONS[i]) == TextDecoration.State.TRUE) {
        codes.append(SECTION).append(DECORATION_CODES[i]);
      }
    }
    return codes.toString();
  }
}
