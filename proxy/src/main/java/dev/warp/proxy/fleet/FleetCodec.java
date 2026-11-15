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
package dev.warp.proxy.fleet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The frames the fleet writes to its store's channels: notices, bus messages and requests. A frame
 * is a sequence of fields; strings are length-prefixed UTF-8, longs are big-endian. Off the hot
 * path: a frame travels once per login, move, leave or plugin message, never per packet.
 */
final class FleetCodec {

  private FleetCodec() {}

  /** Builds a frame. */
  static final class Writer {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
    private final DataOutputStream out = new DataOutputStream(bytes);

    Writer string(String value) {
      byte[] utf = value.getBytes(StandardCharsets.UTF_8);
      return bytes(utf);
    }

    Writer bytes(byte[] value) {
      try {
        out.writeInt(value.length);
        out.write(value);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return this;
    }

    Writer lng(long value) {
      try {
        out.writeLong(value);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return this;
    }

    Writer byt(int value) {
      try {
        out.write(value);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return this;
    }

    byte[] toBytes() {
      return bytes.toByteArray();
    }
  }

  /** Reads a frame; a malformed frame throws {@link IllegalArgumentException}. */
  static final class Reader {
    private final DataInputStream in;
    private final int length;

    Reader(byte[] frame) {
      this.in = new DataInputStream(new ByteArrayInputStream(frame));
      this.length = frame.length;
    }

    String string() {
      return new String(bytes(), StandardCharsets.UTF_8);
    }

    byte[] bytes() {
      try {
        int size = in.readInt();
        if (size < 0 || size > length) {
          throw new IllegalArgumentException("invalid field length " + size);
        }
        return in.readNBytes(size);
      } catch (IOException e) {
        throw new IllegalArgumentException("truncated frame", e);
      }
    }

    long lng() {
      try {
        return in.readLong();
      } catch (IOException e) {
        throw new IllegalArgumentException("truncated frame", e);
      }
    }

    int byt() {
      try {
        return in.readUnsignedByte();
      } catch (IOException e) {
        throw new IllegalArgumentException("truncated frame", e);
      }
    }
  }
}
