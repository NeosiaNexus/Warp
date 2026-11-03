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
package dev.warp.proxy.connection;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * References for the errors of the proxy itself: a player is shown the reference rather than the
 * cause, which stays in the log next to the same reference, so that staff find the line a player
 * reports without the player ever seeing a stack trace.
 */
final class InternalErrors {

  private InternalErrors() {}

  /**
   * Returns a new reference: eight hexadecimal digits, short enough to read aloud.
   *
   * @return the reference, such as {@code 3F9A2C01}
   */
  static String reference() {
    return HexFormat.of().withUpperCase().toHexDigits(ThreadLocalRandom.current().nextInt());
  }
}
