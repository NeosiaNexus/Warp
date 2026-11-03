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
 * Tells player names and server names apart from other words, so that the console colours them in
 * log messages. Asked from the logging thread: lookups must be cheap and thread-safe.
 */
public interface NameDirectory {

  /** Knows no name: before the proxy starts. */
  NameDirectory NONE =
      new NameDirectory() {
        @Override
        public boolean isPlayer(String name) {
          return false;
        }

        @Override
        public boolean isServer(String name) {
          return false;
        }
      };

  /**
   * Returns whether a player of this name is connected.
   *
   * @param name a word of a log message
   * @return {@code true} for a connected player's name
   */
  boolean isPlayer(String name);

  /**
   * Returns whether a backend server of this name is registered.
   *
   * @param name a word of a log message
   * @return {@code true} for a registered server's name
   */
  boolean isServer(String name);
}
