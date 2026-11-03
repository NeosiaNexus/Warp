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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Ansi and SourceTags")
class AnsiAndTagsTest {

  @Nested
  @DisplayName("Ansi")
  class AnsiCodes {

    @Test
    @DisplayName("strips colours, cursor moves and window titles")
    void strip() {
      String text = "\u001B[38;2;1;2;3mred\u001B[0m \u001B[2K\u001B]0;title\u0007end\u001B[?25l";

      assertEquals("red end", Ansi.strip(text));
    }

    @Test
    @DisplayName("returns text without escape codes as it is")
    void untouched() {
      String text = "nothing to strip";

      assertSame(text, Ansi.strip(text));
      assertSame(text, Ansi.clean(text));
    }

    @Test
    @DisplayName("cleans control characters but tab and line feed")
    void clean() {
      assertEquals("ab\tc\nd", Ansi.clean("a\rb\tc\nd\u0007\u009B"));
    }

    @Test
    @DisplayName("maps colours to the xterm 256-colour palette")
    void xterm() {
      assertEquals(196, Ansi.xterm256(0xFF0000));
      assertEquals(16, Ansi.xterm256(0x000000));
      assertEquals(244, Ansi.xterm256(0x808080));
    }

    @Test
    @DisplayName("writes a colour at each depth")
    void depths() {
      assertEquals("", Ansi.foreground(0x112233, 31, ColorDepth.NONE));
      assertEquals("31", Ansi.foreground(0x112233, 31, ColorDepth.ANSI_16));
      assertEquals(
          "38;5;" + Ansi.xterm256(0x112233), Ansi.foreground(0x112233, 31, ColorDepth.ANSI_256));
      assertEquals("38;2;17;34;51", Ansi.foreground(0x112233, 31, ColorDepth.TRUE_COLOR));
    }
  }

  @Nested
  @DisplayName("SourceTags")
  class Tags {

    @Test
    @DisplayName("names Warp's packages")
    void warp() {
      assertEquals("antibot", SourceTags.of("dev.warp.proxy.antibot.AttackDetector"));
      assertEquals("drain", SourceTags.of("dev.warp.proxy.drain.ProxyDrain"));
      assertEquals("conn", SourceTags.of("dev.warp.proxy.connection.ConnectedPlayer"));
      assertEquals("warp", SourceTags.of("dev.warp.proxy.WarpServer"));
      assertEquals("natives", SourceTags.of("dev.warp.jni.Natives"));
      assertEquals("protocol", SourceTags.of("dev.warp.protocol.codec.Varint"));
    }

    @Test
    @DisplayName("names a plugin's logger and, once registered, its classes")
    void plugins() {
      assertEquals("plugin:primemc", SourceTags.of("primemc"));

      SourceTags.registerPlugin("net.primemc.proxy", "primemc");

      assertEquals("plugin:primemc", SourceTags.of("net.primemc.proxy.listener.Joins"));
    }

    @Test
    @DisplayName("names well-known libraries, and others by their organisation")
    void libraries() {
      assertEquals("netty", SourceTags.of("io.netty.util.ResourceLeakDetector"));
      assertEquals("via", SourceTags.of("com.viaversion.viaversion.ViaManager"));
      assertEquals("example", SourceTags.of("org.example.thing.Thing"));
      assertEquals("stderr", SourceTags.of("STDERR"));
    }
  }
}
