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
package dev.warp.proxy.world;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal NBT writer for the registries the virtual world declares to 1.16 to 1.20.4 clients:
 * compounds, lists of compounds or strings, and the scalar tags those registries use. Written once
 * at startup, never on a hot path.
 */
final class Nbt {

  private static final int TAG_END = 0;
  private static final int TAG_BYTE = 1;
  private static final int TAG_INT = 3;
  private static final int TAG_LONG = 4;
  private static final int TAG_FLOAT = 5;
  private static final int TAG_DOUBLE = 6;
  private static final int TAG_STRING = 8;
  private static final int TAG_LIST = 9;
  private static final int TAG_COMPOUND = 10;

  private Nbt() {}

  /** A compound tag under construction: entries keep their insertion order. */
  static final class Compound {

    private record Entry(String name, int type, Object value) {}

    private final List<Entry> entries = new ArrayList<>();

    Compound put(String name, boolean value) {
      entries.add(new Entry(name, TAG_BYTE, (byte) (value ? 1 : 0)));
      return this;
    }

    Compound put(String name, int value) {
      entries.add(new Entry(name, TAG_INT, value));
      return this;
    }

    Compound put(String name, long value) {
      entries.add(new Entry(name, TAG_LONG, value));
      return this;
    }

    Compound put(String name, float value) {
      entries.add(new Entry(name, TAG_FLOAT, value));
      return this;
    }

    Compound put(String name, double value) {
      entries.add(new Entry(name, TAG_DOUBLE, value));
      return this;
    }

    Compound put(String name, String value) {
      entries.add(new Entry(name, TAG_STRING, value));
      return this;
    }

    Compound put(String name, Compound value) {
      entries.add(new Entry(name, TAG_COMPOUND, value));
      return this;
    }

    /**
     * Puts a list of compounds (an empty list is written as a list of end tags, as vanilla does).
     */
    Compound putCompounds(String name, List<Compound> values) {
      entries.add(new Entry(name, TAG_LIST, List.copyOf(values)));
      return this;
    }

    /** Puts a list of strings. */
    Compound putStrings(String name, List<String> values) {
      entries.add(new Entry(name, TAG_LIST, List.copyOf(values)));
      return this;
    }

    /**
     * Returns the compound as a named root tag (an empty name), the network form until 1.20.1.
     *
     * @return the bytes
     */
    byte[] named() {
      return write(true);
    }

    /**
     * Returns the compound as a nameless root tag, the network form from 1.20.2.
     *
     * @return the bytes
     */
    byte[] nameless() {
      return write(false);
    }

    private byte[] write(boolean named) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeByte(TAG_COMPOUND);
        if (named) {
          out.writeUTF("");
        }
        writePayload(out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return bytes.toByteArray();
    }

    private void writePayload(DataOutputStream out) throws IOException {
      for (Entry entry : entries) {
        out.writeByte(entry.type());
        out.writeUTF(entry.name());
        writeValue(out, entry.type(), entry.value());
      }
      out.writeByte(TAG_END);
    }

    private static void writeValue(DataOutputStream out, int type, Object value)
        throws IOException {
      switch (type) {
        case TAG_BYTE -> out.writeByte((Byte) value);
        case TAG_INT -> out.writeInt((Integer) value);
        case TAG_LONG -> out.writeLong((Long) value);
        case TAG_FLOAT -> out.writeFloat((Float) value);
        case TAG_DOUBLE -> out.writeDouble((Double) value);
        case TAG_STRING -> out.writeUTF((String) value);
        case TAG_COMPOUND -> ((Compound) value).writePayload(out);
        case TAG_LIST -> {
          List<?> list = (List<?>) value;
          if (list.isEmpty()) {
            out.writeByte(TAG_END);
            out.writeInt(0);
          } else if (list.getFirst() instanceof Compound) {
            out.writeByte(TAG_COMPOUND);
            out.writeInt(list.size());
            for (Object element : list) {
              ((Compound) element).writePayload(out);
            }
          } else {
            out.writeByte(TAG_STRING);
            out.writeInt(list.size());
            for (Object element : list) {
              out.writeUTF((String) element);
            }
          }
        }
        default -> throw new IllegalStateException("unknown tag type " + type);
      }
    }
  }
}
