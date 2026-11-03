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

/**
 * The roles of the console's text. Their colours come from the {@link Theme} (dark, light,
 * colour-blind), and their escape sequences at each depth from the {@link Palette}. Greys are
 * colours rather than the "faint" attribute, which some Windows consoles ignore.
 */
enum Style {
  /** Times, sources, addresses, library stack frames: present, but out of the way. */
  MUTED(false),
  /** Labels of the summaries. */
  LABEL(false),
  /** Text that must stand out without meaning anything: headings, Warp's own stack frames. */
  STRONG(true),
  /** The ERROR badge, exception class names. */
  ERROR(true),
  /** The WARN badge. */
  WARN(true),
  /** The INFO badge: calm, it is most lines. */
  INFO(false),
  /** The DEBUG and TRACE badges. */
  DEBUG(false),
  /** Player names. */
  PLAYER(false),
  /** Backend server names. */
  SERVER(false),
  /** Numbers, durations, sizes. */
  NUMBER(false),
  /** Warp's brand colour: the banner, the prompt, known commands. */
  ACCENT(true),
  /** The second brand colour, for the banner's gradient. */
  ACCENT_2(true),
  /** Something that works: a reachable server. */
  GOOD(false),
  /** Something that does not: an unreachable server, an unknown command, an attack. */
  BAD(false),
  /** Something to watch: lockdown, a drain in progress. */
  CAUTION(false),
  /** Arguments of a command being typed. */
  ARGUMENT(false);

  private final boolean bold;

  Style(boolean bold) {
    this.bold = bold;
  }

  /**
   * Returns whether this style is bold, whatever the theme.
   *
   * @return {@code true} for bold
   */
  boolean bold() {
    return bold;
  }
}
