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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Watched channels")
class WatchedChannelsTest {

  private final WatchedChannels watched = new WatchedChannels();

  @Nested
  @DisplayName("the set")
  class Set {

    @Test
    @DisplayName("should add and remove channels once each")
    void addRemove() {
      assertTrue(watched.isEmpty());
      assertTrue(watched.add("bungeecord:main"));
      assertFalse(watched.add("bungeecord:main"));
      assertTrue(watched.add("BungeeCord"));

      assertTrue(watched.contains("BungeeCord"));
      assertTrue(watched.remove("bungeecord:main"));
      assertFalse(watched.remove("bungeecord:main"));
      assertFalse(watched.contains("bungeecord:main"));
      assertFalse(watched.isEmpty());
    }
  }

  @Nested
  @DisplayName("matching in place")
  class Matching {

    @Test
    @DisplayName("should return the watched name's own instance, positioned after it")
    void match() {
      watched.add("BungeeCord");
      watched.add("bungeecord:main");
      ByteBuf buf = message("bungeecord:main", 42);
      try {
        String channel = watched.read(buf);

        assertSame(watchedInstance("bungeecord:main"), channel);
        assertEquals(42, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should not match a channel that differs by case, length or a byte")
    void noMatch() {
      watched.add("bungeecord:main");
      for (String other : new String[] {"BungeeCord:main", "bungeecord:mai", "bungeecord:mainx"}) {
        ByteBuf buf = message(other, 0);
        try {
          assertNull(watched.read(buf), other);
        } finally {
          buf.release();
        }
      }
    }

    @Test
    @DisplayName("should read nothing while no channel is watched")
    void idle() {
      ByteBuf buf = message("bungeecord:main", 0);
      try {
        assertNull(watched.read(buf));
        assertEquals(0, buf.readerIndex());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should not match a length longer than the message, or not positive")
    void malformedLength() {
      watched.add("a");
      ByteBuf truncated = Unpooled.buffer();
      ByteBuf empty = Unpooled.buffer();
      try {
        VarInt.write(truncated, 100);
        truncated.writeByte('a');
        VarInt.write(empty, 0);

        assertNull(watched.read(truncated));
        assertNull(watched.read(empty));
      } finally {
        truncated.release();
        empty.release();
      }
    }

    @Test
    @DisplayName("should reject a length that is not a VarInt, as the codec does")
    void badVarInt() {
      watched.add("a");
      ByteBuf buf = Unpooled.wrappedBuffer(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
      try {
        assertThrows(DecoderException.class, () -> watched.read(buf));
      } finally {
        buf.release();
      }
    }
  }

  private String watchedInstance(String channel) {
    ByteBuf buf = message(channel, 0);
    try {
      return java.util.Objects.requireNonNull(watched.read(buf));
    } finally {
      buf.release();
    }
  }

  private static ByteBuf message(String channel, int firstPayloadByte) {
    ByteBuf buf = Unpooled.buffer();
    McString.write(buf, channel);
    buf.writeByte(firstPayloadByte);
    return buf;
  }
}
