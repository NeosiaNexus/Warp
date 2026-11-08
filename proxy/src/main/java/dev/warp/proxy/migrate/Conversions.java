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
package dev.warp.proxy.migrate;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Value conversions shared by the migrations: text formats and durations. */
final class Conversions {

  private static final LegacyComponentSerializer AMPERSAND =
      LegacyComponentSerializer.builder().character('&').hexColors().build();

  private static final LegacyComponentSerializer SECTION =
      LegacyComponentSerializer.builder().character('§').hexColors().build();

  private Conversions() {}

  /**
   * Converts a message of the day to Warp's format: MiniMessage, one string per line. Legacy codes
   * ({@code &a}, {@code §a}, {@code &#rrggbb}) become MiniMessage tags; a text already in
   * MiniMessage is kept as it is. Line breaks split the text into lines.
   *
   * @param text the source text
   * @param legacy whether the source read {@code &} codes, as BungeeCord and Velocity 1.x did; a
   *     MiniMessage source keeps its ampersands, since {@code R&B} is a name there, not a colour
   * @return the lines, in MiniMessage
   */
  static List<String> motd(String text, boolean legacy) {
    List<String> lines = new ArrayList<>();
    for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
      lines.add(toMiniMessage(line, legacy));
    }
    return List.copyOf(lines);
  }

  /**
   * Converts a text in legacy codes to MiniMessage, leaving a text without any as it is.
   *
   * <p>A {@code §} code is always a colour, so it is converted unless the text also holds a
   * MiniMessage tag, which the legacy deserializer would escape. An {@code &} is a colour only
   * where the source read it as one: in a MiniMessage source it is a plain character.
   *
   * @param text the source text
   * @param legacy whether the source read {@code &} codes
   * @return the MiniMessage text
   */
  static String toMiniMessage(String text, boolean legacy) {
    if (text.indexOf('§') >= 0 && (legacy || text.indexOf('<') < 0)) {
      return serialize(SECTION.deserialize(text));
    }
    if (legacy && hasLegacyCodes(text, '&')) {
      return serialize(AMPERSAND.deserialize(text));
    }
    return text;
  }

  /**
   * Converts milliseconds to a Warp duration, in the largest unit that keeps it exact.
   *
   * @param millis the duration in milliseconds
   * @return the duration, such as {@code 3s} or {@code 1500ms}
   */
  static HoconWriter.Raw duration(long millis) {
    if (millis == 0) {
      return new HoconWriter.Raw("0");
    }
    if (millis % 60_000 == 0) {
      return new HoconWriter.Raw(millis / 60_000 + "m");
    }
    if (millis % 1000 == 0) {
      return new HoconWriter.Raw(millis / 1000 + "s");
    }
    return new HoconWriter.Raw(millis + "ms");
  }

  private static String serialize(Component component) {
    return MiniMessage.miniMessage().serialize(component);
  }

  private static boolean hasLegacyCodes(String text, char marker) {
    for (int i = 0; i + 1 < text.length(); i++) {
      if (text.charAt(i) == marker) {
        char code = Character.toLowerCase(text.charAt(i + 1));
        if ((code >= '0' && code <= '9')
            || (code >= 'a' && code <= 'f')
            || (code >= 'k' && code <= 'o')
            || code == 'r'
            || code == '#') {
          return true;
        }
      }
    }
    return false;
  }
}
