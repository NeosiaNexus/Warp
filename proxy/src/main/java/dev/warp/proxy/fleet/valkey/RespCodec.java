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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.jspecify.annotations.Nullable;

/**
 * RESP3 on a {@link ByteBuf}: commands out, values in. Simpler than the Minecraft frame decoder:
 * every value is self-delimiting, and an incomplete one is left in the buffer until more arrives.
 */
public final class RespCodec {

  private static final byte[] CRLF = {'\r', '\n'};

  /** The largest aggregate or blob accepted, to bound a hostile or broken server. */
  static final int MAX_LENGTH = 64 * 1024 * 1024;

  private RespCodec() {}

  // ---------------------------------------------------------------------------
  // Encoding
  // ---------------------------------------------------------------------------

  /**
   * Writes a command as an array of blob strings.
   *
   * @param out the buffer
   * @param args the command and its arguments, each a {@link String} or a {@code byte[]}
   */
  public static void writeCommand(ByteBuf out, Object... args) {
    out.writeByte('*');
    writeAscii(out, Integer.toString(args.length));
    out.writeBytes(CRLF);
    for (Object arg : args) {
      byte[] bytes =
          switch (arg) {
            case byte[] raw -> raw;
            case String text -> text.getBytes(StandardCharsets.UTF_8);
            case Object other -> other.toString().getBytes(StandardCharsets.UTF_8);
          };
      out.writeByte('$');
      writeAscii(out, Integer.toString(bytes.length));
      out.writeBytes(CRLF);
      out.writeBytes(bytes);
      out.writeBytes(CRLF);
    }
  }

  private static void writeAscii(ByteBuf out, String text) {
    ByteBufUtil.writeAscii(out, text);
  }

  // ---------------------------------------------------------------------------
  // Decoding
  // ---------------------------------------------------------------------------

  /**
   * Reads one value, if the buffer holds it whole.
   *
   * @param in the buffer, its reader index at the value's first byte
   * @return the value, the reader index past it; or {@code null} with the reader index unchanged
   *     when the value is incomplete
   * @throws IllegalStateException if the bytes are not RESP
   */
  public static @Nullable RespValue read(ByteBuf in) {
    int start = in.readerIndex();
    RespValue value = readValue(in);
    if (value == null) {
      in.readerIndex(start);
    }
    return value;
  }

  private static @Nullable RespValue readValue(ByteBuf in) {
    if (!in.isReadable()) {
      return null;
    }
    byte type = in.readByte();
    switch (type) {
      case '+' -> {
        String line = readLine(in);
        return line == null ? null : new RespValue.Simple(line);
      }
      case '-' -> {
        String line = readLine(in);
        return line == null ? null : new RespValue.Err(line);
      }
      case ':' -> {
        String line = readLine(in);
        return line == null ? null : new RespValue.Int(parseLong(line));
      }
      case ',' -> {
        String line = readLine(in);
        return line == null ? null : new RespValue.Dbl(parseDouble(line));
      }
      case '#' -> {
        String line = readLine(in);
        return line == null ? null : new RespValue.Bool(line.equals("t"));
      }
      case '_' -> {
        String line = readLine(in);
        return line == null ? null : new RespValue.Null();
      }
      case '(' -> {
        String line = readLine(in);
        return line == null ? null : new RespValue.Big(line);
      }
      case '$', '=' -> {
        return readBlob(in, false);
      }
      case '!' -> {
        return readBlob(in, true);
      }
      case '*', '~', '>', '%', '|' -> {
        String line = readLine(in);
        if (line == null) {
          return null;
        }
        long count = parseLong(line);
        if (count < 0) {
          return new RespValue.Null(); // RESP2 null array
        }
        // Bounded before the doubling, so that a huge count cannot overflow past the check.
        if (count > MAX_LENGTH) {
          throw new IllegalStateException("aggregate of " + count + " elements");
        }
        boolean pairs = type == '%' || type == '|';
        long elements = pairs ? count * 2 : count;
        if (elements > MAX_LENGTH) {
          throw new IllegalStateException("aggregate of " + elements + " elements");
        }
        List<RespValue> items = new ArrayList<>((int) Math.min(elements, 1024));
        for (long i = 0; i < elements; i++) {
          RespValue item = readValue(in);
          if (item == null) {
            return null;
          }
          items.add(item);
        }
        return switch (type) {
          case '%' -> new RespValue.MapV(items);
          case '>' -> new RespValue.Push(items);
          case '|' -> readValue(in); // attributes decorate the value that follows: skipped
          default -> new RespValue.Arr(items);
        };
      }
      default -> throw new IllegalStateException("not RESP: type byte " + (type & 0xFF));
    }
  }

  private static @Nullable RespValue readBlob(ByteBuf in, boolean error) {
    String line = readLine(in);
    if (line == null) {
      return null;
    }
    long length = parseLong(line);
    if (length < 0) {
      return new RespValue.Null(); // RESP2 null blob
    }
    if (length > MAX_LENGTH) {
      throw new IllegalStateException("blob of " + length + " bytes");
    }
    if (in.readableBytes() < length + 2) {
      return null;
    }
    byte[] bytes = new byte[(int) length];
    in.readBytes(bytes);
    in.skipBytes(2);
    if (error) {
      return new RespValue.Err(new String(bytes, StandardCharsets.UTF_8));
    }
    return new RespValue.Blob(bytes);
  }

  /** Reads up to CRLF, exclusive; {@code null} when the line is not complete yet. */
  private static @Nullable String readLine(ByteBuf in) {
    int end = in.indexOf(in.readerIndex(), in.writerIndex(), (byte) '\n');
    if (end < 0) {
      return null;
    }
    int length = end - in.readerIndex() - 1;
    if (length < 0 || in.getByte(end - 1) != '\r') {
      throw new IllegalStateException("line without CRLF");
    }
    String line = in.toString(in.readerIndex(), length, StandardCharsets.US_ASCII);
    in.readerIndex(end + 1);
    return line;
  }

  /** Parses a RESP integer line; a malformed one is a protocol error, not a Java one. */
  private static long parseLong(String text) {
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("not a RESP integer: " + text, e);
    }
  }

  private static double parseDouble(String text) {
    return switch (text) {
      case "inf" -> Double.POSITIVE_INFINITY;
      case "-inf" -> Double.NEGATIVE_INFINITY;
      case "nan" -> Double.NaN;
      default -> Double.parseDouble(text);
    };
  }
}
