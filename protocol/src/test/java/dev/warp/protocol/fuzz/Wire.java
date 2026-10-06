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

import dev.warp.protocol.compress.ZlibStreams;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;

/**
 * Reference decoders of the Minecraft wire format, written for clarity rather than speed: the
 * oracles the fuzz tests hold Warp's decoders to. Also builds the bytes the fuzz tests feed them.
 */
public final class Wire {

  /** The widest frame length prefix: three bytes, for frames of up to 2 MiB. */
  public static final int MAX_FRAME_LENGTH_BYTES = 3;

  /** Bytes on each side of the readable ones of a {@link #guarded} buffer. */
  public static final int GUARD = 16;

  /**
   * A way to frame a compressed packet ({@link #compressedBody}): its size declared, its whole zlib
   * stream sent.
   */
  public static final int TRUTHFUL = 0;

  /** A way to frame a compressed packet: one byte more declared than the packet holds. */
  public static final int ONE_MORE = 1;

  /** A way to frame a compressed packet: one byte less declared, the stream going on past it. */
  public static final int ONE_LESS = 2;

  /** A way to frame a compressed packet: more declared than DEFLATE can expand the stream to. */
  public static final int BEYOND_DEFLATE = 3;

  /** A way to frame a compressed packet: its size declared, one byte of its stream inverted. */
  public static final int CORRUPT = 4;

  /** A way to frame a compressed packet: its size declared, its stream cut short. */
  public static final int TRUNCATED = 5;

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

  // ---------------------------------------------------------------------------
  // Reference decoders
  // ---------------------------------------------------------------------------

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
   * What Warp makes of the body of a frame on a compressed connection, {@code [Data Length]
   * [payload]}: the packet it carries, or {@code null} if it rejects the frame.
   *
   * <p>The rule is Warp's ({@code FrameDecompressor}), and it is stricter than vanilla's. Both
   * reject a declared size above the cap or, from a client, below the threshold, and a stream that
   * inflates to fewer bytes than declared. Warp also requires the stream to end at the declared
   * size, where vanilla inflates that many bytes and ignores the rest. Warp rejects a size DEFLATE
   * cannot reach before inflating anything; this reference inflates whatever is declared within the
   * cap, so that such a size fails here too, on its own.
   *
   * @param body the frame after its length prefix
   * @param threshold the compression threshold of the connection
   * @param validateThreshold whether a compressed packet declaring less than the threshold is
   *     rejected, as when the peer is a client
   * @param maxSize the largest declared size accepted
   * @return the packet, {@code [Packet ID][Payload]}, or {@code null}
   */
  public static byte @Nullable [] decompressed(
      byte[] body, int threshold, boolean validateThreshold, int maxSize) {
    VarNum dataLength = varNum(body, 0, 5);
    if (dataLength == null) {
      return null;
    }
    byte[] payload = Arrays.copyOfRange(body, dataLength.length(), body.length);
    int declared = (int) dataLength.value();
    if (declared == 0) {
      return payload;
    }
    if (declared < 0 || (validateThreshold && declared < threshold) || declared > maxSize) {
      return null;
    }
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(payload);
      ByteArrayOutputStream packet = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      while (!inflater.finished() && packet.size() <= declared) {
        int inflated = inflater.inflate(chunk);
        packet.write(chunk, 0, inflated);
        if (inflated == 0 && !inflater.finished()) {
          return null; // truncated, or needs a preset dictionary
        }
      }
      return inflater.finished() && packet.size() == declared ? packet.toByteArray() : null;
    } catch (DataFormatException malformed) {
      return null;
    } finally {
      inflater.end();
    }
  }

  // ---------------------------------------------------------------------------
  // Inputs
  // ---------------------------------------------------------------------------

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

  /**
   * Returns the frame body a peer sends for a packet on a compressed connection, {@code [Data
   * Length][payload]}: the packet behind a Data Length of 0 when it is smaller than the threshold
   * and the peer does not compress every packet, else its zlib stream behind its size, which {@code
   * framing} may turn into a lie or damage.
   *
   * @param packet {@code [Packet ID][Payload]}
   * @param threshold the compression threshold of the connection
   * @param compressAll whether the peer compresses the packets below the threshold too
   * @param framing how a compressed packet is framed: {@link #TRUTHFUL}, {@link #ONE_MORE}, {@link
   *     #ONE_LESS}, {@link #BEYOND_DEFLATE}, {@link #CORRUPT} or {@link #TRUNCATED}
   * @param at where {@link #CORRUPT} inverts a byte of the stream, or {@link #TRUNCATED} cuts it,
   *     modulo the length of the stream
   * @return the frame body, without its length prefix
   */
  public static byte[] compressedBody(
      byte[] packet, int threshold, boolean compressAll, int framing, int at) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    if (packet.length < threshold && !compressAll) {
      body.write(0);
      body.writeBytes(packet);
      return body.toByteArray();
    }
    byte[] zlib = ZlibStreams.zlib(packet, 6, Deflater.DEFAULT_STRATEGY);
    int declared =
        switch (framing) {
          case ONE_MORE -> packet.length + 1;
          case ONE_LESS -> packet.length - 1;
          case BEYOND_DEFLATE -> zlib.length * 1033 + 259;
          default -> packet.length;
        };
    if (framing == CORRUPT) {
      zlib[at % zlib.length] ^= (byte) 0xFF;
    } else if (framing == TRUNCATED) {
      zlib = Arrays.copyOf(zlib, at % zlib.length);
    }
    body.writeBytes(varInt(declared));
    body.writeBytes(zlib);
    return body.toByteArray();
  }

  /**
   * Returns a packet: its id bytes, then a compressible body.
   *
   * @param id the bytes of the packet id, well formed or not
   * @param size the number of bytes after the id
   * @return the packet
   */
  public static byte[] packet(byte[] id, int size) {
    byte[] packet = Arrays.copyOf(id, id.length + size);
    for (int i = id.length; i < packet.length; i++) {
      packet[i] = (byte) (i % 13 * 7);
    }
    return packet;
  }

  /**
   * Returns a heap buffer whose readable bytes are {@code bytes}, between {@value #GUARD} bytes of
   * {@code guard} on each side, which a reader straying past the readable bytes reads. Guards of
   * {@code 0x00} end a VarInt read past its bytes, and guards of {@code 0xFF} continue it.
   *
   * @param bytes the readable bytes
   * @param guard the value of the bytes around them
   * @return the buffer, its reader index at {@value #GUARD}
   */
  public static ByteBuf guarded(byte[] bytes, int guard) {
    byte[] array = new byte[GUARD + bytes.length + GUARD];
    Arrays.fill(array, (byte) guard);
    System.arraycopy(bytes, 0, array, GUARD, bytes.length);
    return Unpooled.wrappedBuffer(array).setIndex(GUARD, GUARD + bytes.length);
  }
}
