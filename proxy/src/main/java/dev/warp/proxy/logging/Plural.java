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

/**
 * The word that agrees with a count, for log lines: "Disconnecting 1 player", "Disconnecting 3
 * players". It goes into the format, not its arguments, so that the console colours the count and
 * never the word.
 */
public final class Plural {

  private Plural() {}

  /**
   * Returns the singular for one, the plural for any other count ("0 players", as English says).
   *
   * @param count the count
   * @param one the word for one, such as {@code player}
   * @param many the word for any other count, such as {@code players}
   * @return one of them
   */
  public static String of(long count, String one, String many) {
    return count == 1 ? one : many;
  }
}
