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
package dev.warp.protocol.packet.play;

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_11;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * The actions of the single Title packet of 1.8 to 1.16.5, which 1.17 split into Set Title Text,
 * Set Subtitle Text, Set Titles Animation, Set Action Bar Text and Clear Titles.
 *
 * <p>The packet starts with a VarInt action. 1.11 inserted the action bar at 2, which moved the
 * others up by one: a client of the other range reads the wrong fields and fails the packet
 * (BungeeCord #1986). Each 1.17+ packet record writes its own action up to 1.16.5:
 *
 * <table>
 *   <caption>Title packet actions</caption>
 *   <tr><th>Action</th><th>1.8 to 1.10</th><th>1.11 to 1.16.5</th></tr>
 *   <tr><td>set title</td><td>0</td><td>0</td></tr>
 *   <tr><td>set subtitle</td><td>1</td><td>1</td></tr>
 *   <tr><td>set action bar</td><td>none</td><td>2</td></tr>
 *   <tr><td>set times</td><td>2</td><td>3</td></tr>
 *   <tr><td>hide</td><td>3</td><td>4</td></tr>
 *   <tr><td>reset</td><td>4</td><td>5</td></tr>
 * </table>
 *
 * <p>Sources: minecraft-data's {@code packet_title}, Velocity's {@code LegacyTitlePacket}.
 */
final class LegacyTitle {

  /** The set title action. */
  static final int SET_TITLE = 0;

  /** The set subtitle action. */
  static final int SET_SUBTITLE = 1;

  /** The set action bar action, from 1.11. */
  static final int SET_ACTION_BAR = 2;

  private LegacyTitle() {}

  /**
   * Returns the set times action of {@code version}.
   *
   * @param version a version from 1.8 to 1.16.5
   * @return 2 before 1.11, 3 from 1.11
   */
  static int setTimes(ProtocolVersion version) {
    return version.isAtLeast(MINECRAFT_1_11) ? 3 : 2;
  }

  /**
   * Returns the hide action of {@code version}; the reset action is the next one.
   *
   * @param version a version from 1.8 to 1.16.5
   * @return 3 before 1.11, 4 from 1.11
   */
  static int hide(ProtocolVersion version) {
    return version.isAtLeast(MINECRAFT_1_11) ? 4 : 3;
  }

  /**
   * Reads the action of a Title packet and checks that it is the one a record decodes.
   *
   * @param buf the packet body
   * @param expected the action the record decodes
   * @throws DecoderException if the packet carries another action
   */
  static void expect(ByteBuf buf, int expected) {
    int action = VarInt.read(buf);
    if (action != expected) {
      throw new DecoderException("Title action " + action + ", expected " + expected);
    }
  }
}
