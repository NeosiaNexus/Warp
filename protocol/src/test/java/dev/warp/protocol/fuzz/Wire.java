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
package dev.warp.protocol.fuzz;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Reference decoders of the Minecraft wire format, written for clarity rather than speed: the
 * oracles the fuzz tests hold Warp's decoders to.
 */
public final class Wire {

  /** The widest frame length prefix: three bytes, for frames of up to 2 MiB. */
  public static final int MAX_FRAME_LENGTH_BYTES = 3;

  private Wire() {}

  /**
   * A decoded VarInt or VarLong.
   *
   * @param value the value; a VarInt is its low 32 bits
   * @param length the number of bytes it takes
   */
  public record VarNum(long value, int length) {}

  /**
   * How a stream splits into frames.
   *
   * @param frames the complete, non-empty frames, length prefix included
   * @param malformed whether the stream stops at a length prefix wider than three bytes, rather
   *     than at its end or an incomplete frame
   */
  public record Framing(List<byte[]> frames, boolean malformed) {

    /**
     * Returns the payload of each frame: its bytes after the length prefix.
     *
     * @return the payloads, in order
     */
    public List<byte[]> payloads() {
      return frames.stream()
          .map(
              frame -> {
                VarNum length = Objects.requireNonNull(varNum(frame, 0, MAX_FRAME_LENGTH_BYTES));
                return Arrays.copyOfRange(frame, length.length(), frame.length);
              })
          .toList();
    }
  }

  /**
   * Decodes the VarInt or VarLong at {@code offset} the obvious way: seven bits per byte, low bits
   * first, while the high bit is set.
   *
   * @param bytes the bytes to decode
   * @param offset where the number starts
   * @param maxBytes the widest number accepted: 5 for a VarInt, 10 for a VarLong
   * @return the number, or {@code null} if the bytes end before it does or it is wider
   */
  public static @Nullable VarNum varNum(byte[] bytes, int offset, int maxBytes) {
    long value = 0;
    for (int i = 0; i < maxBytes && offset + i < bytes.length; i++) {
      byte b = bytes[offset + i];
      value |= (long) (b & 0x7F) << (7 * i);
      if (b >= 0) {
        return new VarNum(value, i + 1);
      }
    }
    return null;
  }

  /**
   * Splits a stream into frames, {@code [Length][Length bytes]}, as the protocol defines them: the
   * length is a VarInt of at most three bytes, and frames of length zero are skipped.
   *
   * @param stream the bytes received on a connection
   * @return its frames, up to the first incomplete or malformed one
   */
  public static Framing frames(byte[] stream) {
    List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset < stream.length) {
      VarNum length = varNum(stream, offset, MAX_FRAME_LENGTH_BYTES);
      if (length == null) {
        return new Framing(frames, stream.length - offset >= MAX_FRAME_LENGTH_BYTES);
      }
      int end = offset + length.length() + (int) length.value();
      if (end > stream.length) {
        break;
      }
      if (length.value() > 0) {
        frames.add(Arrays.copyOfRange(stream, offset, end));
      }
      offset = end;
    }
    return new Framing(frames, false);
  }

  /**
   * Encodes a VarInt in as few bytes as possible.
   *
   * @param value the value
   * @return its one to five bytes
   */
  public static byte[] varInt(int value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(5);
    int rest = value;
    while ((rest & ~0x7F) != 0) {
      out.write((rest & 0x7F) | 0x80);
      rest >>>= 7;
    }
    out.write(rest);
    return out.toByteArray();
  }

  /**
   * Prepends its length prefix to a frame body.
   *
   * @param body the bytes after the prefix
   * @return the complete frame
   */
  public static byte[] frame(byte[] body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 3);
    out.writeBytes(varInt(body.length));
    out.writeBytes(body);
    return out.toByteArray();
  }
}
