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
package dev.warp.api.logging;

/** The severity of a {@link LogRecord}, from the least to the most severe: SLF4J's levels. */
public enum LogLevel {
  /** Packet-level detail, off unless debugging the proxy. */
  TRACE,
  /** Detail useful to diagnose a problem. */
  DEBUG,
  /** A routine event: a player logged in, a plugin was enabled. */
  INFO,
  /** Something went wrong, and the proxy carried on. */
  WARN,
  /** Something failed: a plugin, a command, a connection in an unexpected way. */
  ERROR;

  /**
   * Returns whether this level is as severe as {@code threshold}, or more.
   *
   * @param threshold the least severe level accepted
   * @return {@code true} if a record of this level passes {@code threshold}
   */
  public boolean isAtLeast(LogLevel threshold) {
    return compareTo(threshold) >= 0;
  }
}
