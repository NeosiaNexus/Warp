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
package dev.warp.proxy.query;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What the query listener answers, encoded once: the basic and the full stat bodies, without the
 * type byte and the session id a reply starts with.
 *
 * <p>A reply is capped at {@value #MAX_REPLY} bytes, under a typical MTU: a full stat lists as many
 * of the player names as fit, then stops. The cap also bounds what a spoofed source can make the
 * proxy send to a victim once an attacker holds a token for it, which is already the hard part. The
 * free-form fields a plugin or the configuration controls are cut first so that the cap holds
 * whatever they carry: the MOTD at {@value #MAX_MOTD_BYTES} bytes, the version at {@value
 * #MAX_VERSION_BYTES} and {@code hostip} at {@value #MAX_HOST_BYTES}, on a character boundary; the
 * {@code map} is bounded by the configuration and the plugins list is dropped when it alone does
 * not fit. What remains is always under the cap, which {@link #of} checks.
 *
 * <p>Strings are UTF-8, as the vanilla server writes them since 1.14; names of Java players are
 * ASCII anyway.
 *
 * @param motd the message of the day as plain text, as written: {@code hostname} in a full stat
 * @param version the version shown, as written
 * @param plugins the {@code plugins} field: {@code Warp <version>: <plugin> <version>; ...}, or
 *     empty when the plugins are hidden
 * @param map the {@code map} field
 * @param numPlayers the players online, as the server list shows them
 * @param maxPlayers the maximum, as the server list shows it
 * @param hostPort the port players connect to
 * @param hostIp the host players connect to, as written
 * @param players the names a full stat lists, in order, before the size cap
 * @param basicBody the encoded basic stat, after the type byte and the session id
 * @param fullBody the encoded full stat, after the type byte and the session id
 * @param listed how many of {@code players} the full stat lists
 */
record QuerySnapshot(
    String motd,
    String version,
    String plugins,
    String map,
    int numPlayers,
    int maxPlayers,
    int hostPort,
    String hostIp,
    List<String> players,
    byte[] basicBody,
    byte[] fullBody,
    int listed) {

  /** The most bytes a reply takes, header included. */
  static final int MAX_REPLY = 1400;

  /** The bytes before a body: the type byte and the session id. */
  static final int HEADER = 1 + 4;

  /** The most bytes of UTF-8 the MOTD is written with: more than two full lines of the client. */
  static final int MAX_MOTD_BYTES = 256;

  /** The most bytes of UTF-8 the version is written with. */
  static final int MAX_VERSION_BYTES = 64;

  /** The most bytes of UTF-8 {@code hostip} is written with: a host name is at most 253. */
  static final int MAX_HOST_BYTES = 255;

  /** The {@code gametype} field: always SMP, as every implementation writes it. */
  static final String GAME_TYPE = "SMP";

  /** The {@code game_id} field: always MINECRAFT. */
  static final String GAME_ID = "MINECRAFT";

  /** The constant that opens the key-value block of a full stat. */
  private static final byte[] KEY_VALUE_PADDING = {
    's', 'p', 'l', 'i', 't', 'n', 'u', 'm', 0, (byte) 0x80, 0
  };

  /** The constant that opens the player block of a full stat. */
  private static final byte[] PLAYER_PADDING = {1, 'p', 'l', 'a', 'y', 'e', 'r', '_', 0, 0};

  /**
   * Encodes a snapshot.
   *
   * @param motd the message of the day as plain text
   * @param version the version shown
   * @param plugins the {@code plugins} field
   * @param map the {@code map} field
   * @param numPlayers the players online
   * @param maxPlayers the maximum shown
   * @param hostPort the port players connect to
   * @param hostIp the host players connect to
   * @param players the names to list, in order; as many as fit are written
   * @return the snapshot, with both bodies encoded
   * @throws IllegalStateException if a body exceeds the cap, which the field bounds rule out
   */
  static QuerySnapshot of(
      String motd,
      String version,
      String plugins,
      String map,
      int numPlayers,
      int maxPlayers,
      int hostPort,
      String hostIp,
      List<String> players) {
    List<String> names = List.copyOf(players);
    motd = bounded(motd, MAX_MOTD_BYTES);
    version = bounded(version, MAX_VERSION_BYTES);
    hostIp = bounded(hostIp, MAX_HOST_BYTES);
    byte[] basic = encodeBasic(motd, map, numPlayers, maxPlayers, hostPort, hostIp);
    ByteArrayOutputStream full = new ByteArrayOutputStream(512);
    full.writeBytes(KEY_VALUE_PADDING);
    writeKeyValues(full, motd, version, plugins, map, numPlayers, maxPlayers, hostPort, hostIp);
    if (full.size() + PLAYER_PADDING.length + 1 > MAX_REPLY - HEADER) {
      // A plugins list too long for the reply: the version alone, as a server without plugins.
      full.reset();
      full.writeBytes(KEY_VALUE_PADDING);
      writeKeyValues(full, motd, version, "", map, numPlayers, maxPlayers, hostPort, hostIp);
    }
    full.writeBytes(PLAYER_PADDING);
    int listed = 0;
    for (String name : names) {
      byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
      // This name, its terminator, and the final terminator of the list.
      if (full.size() + encoded.length + 1 + 1 > MAX_REPLY - HEADER) {
        break;
      }
      full.writeBytes(encoded);
      full.write(0);
      listed++;
    }
    full.write(0);
    byte[] fullBody = full.toByteArray();
    if (basic.length + HEADER > MAX_REPLY || fullBody.length + HEADER > MAX_REPLY) {
      throw new IllegalStateException(
          "A query reply exceeds "
              + MAX_REPLY
              + " bytes: basic "
              + basic.length
              + ", full "
              + fullBody.length);
    }
    return new QuerySnapshot(
        motd,
        version,
        plugins,
        map,
        numPlayers,
        maxPlayers,
        hostPort,
        hostIp,
        names,
        basic,
        fullBody,
        listed);
  }

  /**
   * Returns a string whose UTF-8 encoding takes at most {@code maxBytes}, cut on a character
   * boundary so that no sequence is left half written.
   */
  static String bounded(String value, int maxBytes) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length <= maxBytes) {
      return value;
    }
    int end = maxBytes;
    // Step back over the continuation bytes (10xxxxxx) of a sequence the cut would split.
    while (end > 0 && (encoded[end] & 0xC0) == 0x80) {
      end--;
    }
    return new String(encoded, 0, end, StandardCharsets.UTF_8);
  }

  /** Whether the full stat lists fewer names than it was given. */
  boolean truncated() {
    return listed < players.size();
  }

  private static byte[] encodeBasic(
      String motd, String map, int numPlayers, int maxPlayers, int hostPort, String hostIp) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(128);
    writeString(out, motd);
    writeString(out, GAME_TYPE);
    writeString(out, map);
    writeString(out, Integer.toString(numPlayers));
    writeString(out, Integer.toString(maxPlayers));
    // The one little-endian value of the protocol.
    out.write(hostPort & 0xFF);
    out.write((hostPort >>> 8) & 0xFF);
    writeString(out, hostIp);
    return out.toByteArray();
  }

  private static void writeKeyValues(
      ByteArrayOutputStream out,
      String motd,
      String version,
      String plugins,
      String map,
      int numPlayers,
      int maxPlayers,
      int hostPort,
      String hostIp) {
    writePair(out, "hostname", motd);
    writePair(out, "gametype", GAME_TYPE);
    writePair(out, "game_id", GAME_ID);
    writePair(out, "version", version);
    writePair(out, "plugins", plugins);
    writePair(out, "map", map);
    writePair(out, "numplayers", Integer.toString(numPlayers));
    writePair(out, "maxplayers", Integer.toString(maxPlayers));
    writePair(out, "hostport", Integer.toString(hostPort));
    writePair(out, "hostip", hostIp);
    // A key of length zero ends the block.
    out.write(0);
  }

  private static void writePair(ByteArrayOutputStream out, String key, String value) {
    writeString(out, key);
    writeString(out, value);
  }

  private static void writeString(ByteArrayOutputStream out, String value) {
    out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    out.write(0);
  }

  /**
   * Returns the names to list, for a server that lists at most {@code limit} of them.
   *
   * @param names the names, in the order to list them
   * @param limit the most to keep
   * @return the first {@code limit} names
   */
  static List<String> cap(List<String> names, int limit) {
    if (names.size() <= limit) {
      return names;
    }
    return new ArrayList<>(names.subList(0, limit));
  }
}
