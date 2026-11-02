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
package dev.warp.protocol.legacy;

import java.util.Objects;

/**
 * The legacy kick packet ({@code 0xFF}): how a server answers a {@link LegacyPing} or a {@link
 * LegacyLogin}. Written by {@link LegacyDisconnectEncoder}, outside the framed protocol: it is not
 * a {@link dev.warp.protocol.packet.Packet}, which the framed encoders would claim.
 *
 * @param reason the text: a kick reason, or a server list entry in the form {@link
 *     #ofStatus(LegacyPing.Format, int, String, String, int, int)} builds
 */
public record LegacyDisconnect(String reason) {

  /** The longest text the packet's length prefix (a signed short) can announce. */
  public static final int MAX_LENGTH = Short.MAX_VALUE;

  /** The color code character, which the 1.3 answer also uses as its field separator. */
  private static final char SECTION = '§';

  /** Truncates the reason to {@link #MAX_LENGTH} characters. */
  public LegacyDisconnect {
    Objects.requireNonNull(reason, "reason");
    if (reason.length() > MAX_LENGTH) {
      reason = reason.substring(0, MAX_LENGTH);
    }
  }

  /**
   * Builds the answer to a legacy ping, in the form its client reads, as vanilla 1.7+ servers and
   * Velocity build it.
   *
   * <ul>
   *   <li>1.3: {@code motd§online§max}, the description without colors (the section sign separates
   *       the fields), first line only.
   *   <li>1.4 to 1.6: {@code §1\0protocol\0version\0motd\0online\0max}, the description with its
   *       colors, first line only.
   * </ul>
   *
   * @param format the form of the ping
   * @param protocol the protocol announced, usually {@link LegacyPing#MODERN_SERVER_PROTOCOL}
   * @param versionName the version name a client shows when the protocol is not its own
   * @param description the description as legacy text: section sign color codes, lines separated by
   *     {@code \n}
   * @param online how many players are online
   * @param max the maximum shown
   * @return the answer
   */
  public static LegacyDisconnect ofStatus(
      LegacyPing.Format format,
      int protocol,
      String versionName,
      String description,
      int online,
      int max) {
    String firstLine = firstLine(description);
    return switch (format) {
      case MINECRAFT_1_3 ->
          new LegacyDisconnect(stripCodes(firstLine) + SECTION + online + SECTION + max);
      case MINECRAFT_1_4, MINECRAFT_1_6 ->
          new LegacyDisconnect(
              SECTION
                  + "1\0"
                  + protocol
                  + '\0'
                  + versionName.replace('\0', ' ')
                  + '\0'
                  + firstLine.replace('\0', ' ')
                  + '\0'
                  + online
                  + '\0'
                  + max);
    };
  }

  private static String firstLine(String text) {
    int newline = text.indexOf('\n');
    return newline < 0 ? text : text.substring(0, newline);
  }

  /** Removes the color codes, and every section sign: the 1.3 answer's separator. */
  private static String stripCodes(String text) {
    StringBuilder plain = new StringBuilder(text.length());
    boolean code = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (code) {
        code = false; // The code character after a section sign goes with it.
      } else if (c == SECTION) {
        code = true;
      } else {
        plain.append(c);
      }
    }
    return plain.toString();
  }
}
