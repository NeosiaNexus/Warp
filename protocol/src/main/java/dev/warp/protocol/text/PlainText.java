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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.ScoreComponent;
import net.kyori.adventure.text.SelectorComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;

/**
 * Flattens a component to the text it shows without styles, where a client takes a plain string:
 * the name of an entity in a tooltip before 1.13.
 *
 * <p>The proxy cannot translate, so a translatable shows its fallback, or its key; a keybind its
 * name, a selector its pattern and a score its resolved value, as unresolved contents show.
 */
final class PlainText {

  private PlainText() {}

  /**
   * Returns the text {@code component} shows, its children included.
   *
   * @param component the component
   * @return the plain text
   */
  static String of(Component component) {
    StringBuilder text = new StringBuilder();
    append(component, text);
    return text.toString();
  }

  private static void append(Component component, StringBuilder text) {
    switch (component) {
      case TextComponent plain -> text.append(plain.content());
      case TranslatableComponent translatable -> {
        String fallback = translatable.fallback();
        text.append(fallback != null ? fallback : translatable.key());
      }
      case KeybindComponent keybind -> text.append(keybind.keybind());
      case SelectorComponent selector -> text.append(selector.pattern());
      case ScoreComponent score -> {
        @SuppressWarnings("deprecation") // the resolved value
        String value = score.value();
        if (value != null) {
          text.append(value);
        }
      }
      default -> {
        // NBT and object contents show nothing unresolved.
      }
    }
    for (Component child : component.children()) {
      append(child, text);
    }
  }
}
