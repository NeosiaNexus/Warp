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
package dev.warp.proxy.fleet.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RespCodec")
class RespCodecTest {

  private static ByteBuf bytes(String resp) {
    return Unpooled.copiedBuffer(resp, StandardCharsets.UTF_8);
  }

  /** Reads a whole value, which the test expects to be complete. */
  private static RespValue read(String resp) {
    return java.util.Objects.requireNonNull(RespCodec.read(bytes(resp)), "incomplete");
  }

  @Nested
  @DisplayName("commands")
  class Commands {

    @Test
    @DisplayName("should write a command as an array of blob strings, bytes as they are")
    void write() {
      ByteBuf out = Unpooled.buffer();

      RespCodec.writeCommand(out, "SET", "k", new byte[] {1, 2, 3});

      assertEquals(
          "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$3\r\n\u0001\u0002\u0003\r\n",
          out.toString(StandardCharsets.ISO_8859_1));
    }
  }

  @Nested
  @DisplayName("values")
  class Values {

    @Test
    @DisplayName("should read every scalar type")
    void scalars() {
      assertEquals(new RespValue.Simple("OK"), read("+OK\r\n"));
      assertEquals(new RespValue.Err("ERR bad"), read("-ERR bad\r\n"));
      assertEquals(new RespValue.Int(42), read(":42\r\n"));
      assertEquals(new RespValue.Dbl(1.5), read(",1.5\r\n"));
      assertEquals(new RespValue.Dbl(Double.POSITIVE_INFINITY), read(",inf\r\n"));
      assertEquals(new RespValue.Bool(true), read("#t\r\n"));
      assertEquals(new RespValue.Null(), read("_\r\n"));
      assertEquals(new RespValue.Big("12345678901234567890"), read("(12345678901234567890\r\n"));
      assertEquals(
          "hello", String.valueOf(read("$5\r\nhello\r\n")).contains("Blob") ? "hello" : "");
      assertEquals("hello", read("$5\r\nhello\r\n").text());
      assertEquals("txt:txt", String.valueOf(read("=7\r\ntxt:txt\r\n").text()));
      assertEquals(new RespValue.Err("SYNTAX x"), read("!8\r\nSYNTAX x\r\n"));
    }

    @Test
    @DisplayName("should read aggregates: arrays, sets, maps, pushes, and skip attributes")
    void aggregates() {
      RespValue array = read("*2\r\n:1\r\n$1\r\na\r\n");
      assertEquals(
          List.of(new RespValue.Int(1), new RespValue.Blob("a".getBytes()).text()),
          List.of(array.items().get(0), array.items().get(1).text()));

      RespValue set = read("~1\r\n+x\r\n");
      assertInstanceOf(RespValue.Arr.class, set);

      RespValue map = read("%1\r\n+k\r\n:2\r\n");
      assertInstanceOf(RespValue.MapV.class, map);
      assertEquals(2, map.items().size());

      RespValue push = read(">3\r\n$7\r\nmessage\r\n$2\r\nch\r\n$2\r\nhi\r\n");
      assertInstanceOf(RespValue.Push.class, push);
      assertEquals("message", push.items().getFirst().text());

      RespValue attributed = read("|1\r\n+ttl\r\n:3\r\n:7\r\n");
      assertEquals(new RespValue.Int(7), attributed);
    }

    @Test
    @DisplayName("should read the RESP2 null blob and null array as null")
    void resp2Nulls() {
      assertEquals(new RespValue.Null(), read("$-1\r\n"));
      assertEquals(new RespValue.Null(), read("*-1\r\n"));
    }

    @Test
    @DisplayName("should leave an incomplete value in the buffer, untouched")
    void incomplete() {
      ByteBuf in = bytes("*2\r\n:1\r\n$5\r\nhel");

      assertNull(RespCodec.read(in));
      assertEquals(0, in.readerIndex());

      in.writeBytes("lo\r\n".getBytes(StandardCharsets.UTF_8));
      RespValue whole = java.util.Objects.requireNonNull(RespCodec.read(in));
      assertEquals(2, whole.items().size());
      assertEquals(0, in.readableBytes());
    }

    @Test
    @DisplayName("should reject bytes that are not RESP")
    void garbage() {
      assertThrows(IllegalStateException.class, () -> read("hello\r\n"));
      assertThrows(IllegalStateException.class, () -> read("+OK\n"));
    }

    @Test
    @DisplayName("should read the error's code from its first word")
    void errorCode() {
      assertEquals("NOSCRIPT", new RespValue.Err("NOSCRIPT No matching script").code());
      assertEquals("ERR", new RespValue.Err("ERR").code());
    }
  }
}
