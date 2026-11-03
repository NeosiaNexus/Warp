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
package dev.warp.api.antibot;

import java.util.HexFormat;

/**
 * The key an identity is stored under: the first 16 bytes of an HMAC-SHA256 of the identity, under
 * the proxy's secret ({@code anti-bot.verified.secret-file}). It reveals neither the name, nor the
 * address, nor the account; proxies that share a {@link VerifiedStore} must share the secret too,
 * so that they compute the same keys.
 *
 * @param high the first 8 bytes, big-endian
 * @param low the next 8 bytes, big-endian
 */
public record VerifiedKey(long high, long low) {

  /**
   * Returns the key as 32 lowercase hexadecimal digits, as a shared store may index it.
   *
   * @return the key in hexadecimal
   */
  public String hex() {
    HexFormat hex = HexFormat.of();
    return hex.toHexDigits(high) + hex.toHexDigits(low);
  }

  /**
   * Reads a key from its hexadecimal form.
   *
   * @param hex 32 hexadecimal digits, as {@link #hex()} writes them
   * @return the key
   * @throws IllegalArgumentException if the text is not 32 hexadecimal digits
   */
  public static VerifiedKey fromHex(String hex) {
    if (hex.length() != 32) {
      throw new IllegalArgumentException("a key is 32 hexadecimal digits, got " + hex.length());
    }
    try {
      return new VerifiedKey(
          HexFormat.fromHexDigitsToLong(hex, 0, 16), HexFormat.fromHexDigitsToLong(hex, 16, 32));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not hexadecimal: " + hex, e);
    }
  }

  @Override
  public String toString() {
    return hex();
  }
}
