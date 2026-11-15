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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.QueryConfig;

import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("QuerySnapshot")
class QuerySnapshotTest {

  /** The session id of the wiki's examples, as the reply header echoes it. */
  static final byte[] SESSION = {0, 0, 0, 1};

  /** The wiki's basic stat example: 51 bytes with the header. */
  static final String WIKI_BASIC =
      "00 00 00 00 01 41 20 4D 69 6E 65 63 72 61 66 74 20 53 65 72 76 65 72 00 53 4D 50 00 77 6F"
          + " 72 6C 64 00 32 00 32 30 00 DD 63 31 32 37 2E 30 2E 30 2E 31 00";

  /** The wiki's full stat example: 219 bytes with the header, two players, no plugins. */
  static final String WIKI_FULL =
      "00 00 00 00 01 73 70 6C 69 74 6E 75 6D 00 80 00 68 6F 73 74 6E 61 6D 65 00 41 20 4D 69 6E"
          + " 65 63 72 61 66 74 20 53 65 72 76 65 72 00 67 61 6D 65 74 79 70 65 00 53 4D 50 00 67"
          + " 61 6D 65 5F 69 64 00 4D 49 4E 45 43 52 41 46 54 00 76 65 72 73 69 6F 6E 00 42 65 74"
          + " 61 20 31 2E 39 20 50 72 65 72 65 6C 65 61 73 65 20 34 00 70 6C 75 67 69 6E 73 00 00"
          + " 6D 61 70 00 77 6F 72 6C 64 00 6E 75 6D 70 6C 61 79 65 72 73 00 32 00 6D 61 78 70 6C"
          + " 61 79 65 72 73 00 32 30 00 68 6F 73 74 70 6F 72 74 00 32 35 35 36 35 00 68 6F 73 74"
          + " 69 70 00 31 32 37 2E 30 2E 30 2E 31 00 00 01 70 6C 61 79 65 72 5F 00 00 62 61 72 6E"
          + " 65 79 67 61 6C 65 00 56 69 76 61 6C 61 68 65 6C 76 69 67 00 00";

  static byte[] hex(String spaced) {
    return HexFormat.ofDelimiter(" ").parseHex(spaced);
  }

  static byte[] withHeader(byte type, byte[] body) {
    byte[] reply = new byte[QuerySnapshot.HEADER + body.length];
    reply[0] = type;
    System.arraycopy(SESSION, 0, reply, 1, 4);
    System.arraycopy(body, 0, reply, 5, body.length);
    return reply;
  }

  QuerySnapshot wikiExample() {
    return QuerySnapshot.of(
        "A Minecraft Server",
        "Beta 1.9 Prerelease 4",
        "",
        "world",
        2,
        20,
        25565,
        "127.0.0.1",
        List.of("barneygale", "Vivalahelvig"));
  }

  @Nested
  @DisplayName("against the wiki's examples")
  class Wiki {

    @Test
    @DisplayName("should encode the basic stat byte for byte, hostport little-endian")
    void basic() {
      byte[] reply = withHeader((byte) 0, wikiExample().basicBody());

      assertArrayEquals(hex(WIKI_BASIC), reply);
      assertEquals(51, reply.length);
    }

    @Test
    @DisplayName("should encode the full stat byte for byte, keys in the specified order")
    void full() {
      byte[] reply = withHeader((byte) 0, wikiExample().fullBody());

      assertArrayEquals(hex(WIKI_FULL), reply);
      assertEquals(219, reply.length);
    }
  }

  @Nested
  @DisplayName("size cap")
  class SizeCap {

    @Test
    @DisplayName("should list every name while they fit, and say so")
    void fits() {
      QuerySnapshot snapshot = snapshotWith(40);

      assertEquals(40, snapshot.listed());
      assertFalse(snapshot.truncated());
      assertTrue(snapshot.fullBody().length + QuerySnapshot.HEADER <= QuerySnapshot.MAX_REPLY);
    }

    @Test
    @DisplayName("should stop listing names at 1400 bytes and keep the terminators")
    void truncates() {
      QuerySnapshot snapshot = snapshotWith(QueryConfig.MAX_PLAYER_NAMES);

      byte[] body = snapshot.fullBody();
      assertTrue(snapshot.truncated());
      assertTrue(snapshot.listed() > 0);
      assertTrue(body.length + QuerySnapshot.HEADER <= QuerySnapshot.MAX_REPLY);
      // The list ends with the last name's terminator and the list's own.
      assertEquals(0, body[body.length - 1]);
      assertEquals(0, body[body.length - 2]);
      assertEquals(snapshot.listed(), countNames(body));
    }

    @Test
    @DisplayName(
        "should drop the plugins list rather than the key-value block when it alone is too long")
    void pluginsTooLong() {
      String plugins = "Warp 1.0: " + "plugin 1.0; ".repeat(200);

      QuerySnapshot snapshot =
          QuerySnapshot.of("motd", "1.21", plugins, "world", 0, 20, 25565, "h", List.of());

      byte[] body = snapshot.fullBody();
      assertTrue(body.length + QuerySnapshot.HEADER <= QuerySnapshot.MAX_REPLY);
      assertTrue(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains("plugins\0\0"));
    }

    @Test
    @DisplayName(
        "should cut a long MOTD, version and hostip so that both bodies stay under the cap")
    void longFields() {
      String motd = "m".repeat(1600);
      String version = "v".repeat(500);
      String host = "h".repeat(400);

      QuerySnapshot snapshot =
          QuerySnapshot.of(motd, version, "Warp 1.0", "world", 0, 20, 25565, host, List.of());

      assertTrue(snapshot.basicBody().length + QuerySnapshot.HEADER <= QuerySnapshot.MAX_REPLY);
      assertTrue(snapshot.fullBody().length + QuerySnapshot.HEADER <= QuerySnapshot.MAX_REPLY);
      assertEquals(QuerySnapshot.MAX_MOTD_BYTES, snapshot.motd().length());
      assertEquals(QuerySnapshot.MAX_VERSION_BYTES, snapshot.version().length());
      assertEquals(QuerySnapshot.MAX_HOST_BYTES, snapshot.hostIp().length());
      String text = new String(snapshot.fullBody(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(text.contains("hostname\0" + snapshot.motd() + "\0gametype"));
      assertTrue(text.contains("hostip\0" + snapshot.hostIp() + "\0"));
    }

    @Test
    @DisplayName("should stay under the cap with every field at its bound and 1000 names")
    void worstCase() {
      List<String> names = new java.util.ArrayList<>();
      for (int i = 0; i < QueryConfig.MAX_PLAYER_NAMES; i++) {
        names.add("Player_" + String.format("%05d", i) + "_xx");
      }

      QuerySnapshot snapshot =
          QuerySnapshot.of(
              "\u00e9".repeat(2000),
              "x".repeat(2000),
              "Warp 1.0: " + "plugin 1.0; ".repeat(200),
              "m".repeat(64),
              Integer.MIN_VALUE,
              Integer.MIN_VALUE,
              65535,
              "y".repeat(2000),
              names);

      assertTrue(snapshot.basicBody().length + QuerySnapshot.HEADER <= QuerySnapshot.MAX_REPLY);
      assertTrue(snapshot.fullBody().length + QuerySnapshot.HEADER <= QuerySnapshot.MAX_REPLY);
      assertTrue(snapshot.listed() > 0);
    }

    @Test
    @DisplayName("should cut a field on a character boundary, never inside a UTF-8 sequence")
    void characterBoundary() {
      // Two bytes each: the fifth byte would split the third character.
      assertEquals("\u00e9\u00e9", QuerySnapshot.bounded("\u00e9\u00e9\u00e9", 5));
      assertEquals("\u00e9\u00e9", QuerySnapshot.bounded("\u00e9\u00e9\u00e9", 4));
      // Four bytes: a cut anywhere inside drops the whole character.
      assertEquals("a", QuerySnapshot.bounded("a\uD83D\uDE00", 4));
      assertEquals("a\uD83D\uDE00", QuerySnapshot.bounded("a\uD83D\uDE00", 5));
      assertEquals("abc", QuerySnapshot.bounded("abc", 3));
      assertEquals("", QuerySnapshot.bounded("\u00e9", 1));
    }

    private QuerySnapshot snapshotWith(int names) {
      List<String> list = new java.util.ArrayList<>();
      for (int i = 0; i < names; i++) {
        list.add("Player_" + String.format("%05d", i) + "_xx");
      }
      return QuerySnapshot.of("motd", "1.21", "Warp 1.0", "world", names, 1000, 25565, "h", list);
    }

    /** Counts the names after the player padding: one terminator each, minus the list's own. */
    private int countNames(byte[] body) {
      String text = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
      int start = text.indexOf("\1player_\0\0") + 10;
      int count = 0;
      for (int i = start; i < body.length - 1; i++) {
        if (body[i] == 0) {
          count++;
        }
      }
      return count;
    }
  }
}
