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
package dev.warp.protocol.netty;

import java.security.GeneralSecurityException;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;

/**
 * One direction of a connection's AES/CFB8 stream, applied to {@link ByteBuf}s without allocating.
 *
 * <p>Two JDK behaviours allocate on every call, and this class avoids both:
 *
 * <ul>
 *   <li>{@link Cipher#update(java.nio.ByteBuffer, java.nio.ByteBuffer)} copies a direct buffer
 *       through temporary arrays: about 2 KiB for a 1 KiB frame. This class only calls the array
 *       form, {@link Cipher#update(byte[], int, int, byte[], int)}.
 *   <li>The array form copies its input to a new array when the output lands on the same bytes, in
 *       place included (JDK 25 {@code CipherCore.update}). So this class never hands it overlapping
 *       input and output, which also rules out transforming a buffer in place directly: Velocity's
 *       Java cipher does that, and pays the copy on every frame.
 * </ul>
 *
 * <p>Between two buffers backed by distinct arrays, the cipher reads one array and writes the
 * other, with no copy. Anything else, a direct or composite buffer on either side or a heap buffer
 * transformed in place, goes through a scratch array of two halves of {@value #CHUNK_SIZE} bytes,
 * one array per thread: the input half receives the source bytes when they have no array, the
 * output half receives the cipher's output when it cannot go straight into the destination's array.
 * A frame larger than a half takes several calls.
 *
 * <p>The scratch array belongs to the thread, not the connection, so its memory grows with the
 * number of event loops rather than the number of players, and it never has to grow or shrink. It
 * is safe to share: a call fills it and empties it before returning, and nothing in between runs
 * other code on the same thread.
 *
 * <p>CFB8 is a stream cipher with an 8-bit feedback unit: every call turns exactly as many bytes as
 * it is given and holds nothing back, so cutting a frame into chunks, or a stream into frames, at
 * any point gives the same bytes as one call over everything.
 */
final class AesCfb8 {

  /** The most bytes transformed in one cipher call when the scratch array is involved. */
  static final int CHUNK_SIZE = 8192;

  /**
   * How close two regions of one array may come before they count as overlapping. The JDK copies
   * its input when the output starts less than its internal buffer, two AES blocks (32 bytes),
   * before it; this leaves a margin.
   */
  private static final int OVERLAP_MARGIN = 64;

  private static final FastThreadLocal<byte[]> SCRATCH =
      new FastThreadLocal<>() {
        @Override
        protected byte[] initialValue() {
          return new byte[2 * CHUNK_SIZE];
        }
      };

  private final Cipher cipher;

  /**
   * Creates the stream of one direction.
   *
   * @param mode {@link Cipher#ENCRYPT_MODE} or {@link Cipher#DECRYPT_MODE}
   * @param key the shared secret (AES-128)
   * @throws GeneralSecurityException if the cipher cannot be initialised
   */
  AesCfb8(int mode, SecretKey key) throws GeneralSecurityException {
    this.cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    // The protocol fixes the IV to the shared secret itself, and every client does the same. The
    // secret is random and new for each connection, so no key and IV pair is ever reused.
    this.cipher.init(mode, key, new IvParameterSpec(key.getEncoded()));
  }

  /**
   * Transforms {@code length} bytes of {@code src} into {@code dst}, advancing the stream.
   *
   * <p>Neither buffer's indices move. {@code src} and {@code dst} may be the same buffer at the
   * same index, to transform in place; otherwise their bytes must not overlap.
   *
   * @param src the bytes to transform
   * @param srcIndex the index of the first byte in {@code src}
   * @param dst where to write the result, with room for {@code length} bytes
   * @param dstIndex the index of the first byte written in {@code dst}
   * @param length the number of bytes
   * @throws ShortBufferException if the cipher asks for more room than it is given, which AES/CFB8
   *     never does
   */
  void transform(ByteBuf src, int srcIndex, ByteBuf dst, int dstIndex, int length)
      throws ShortBufferException {
    Objects.checkFromIndexSize(srcIndex, length, src.capacity());
    Objects.checkFromIndexSize(dstIndex, length, dst.capacity());
    boolean srcArray = src.hasArray();
    boolean dstArray = dst.hasArray();
    if (srcArray && dstArray) {
      byte[] in = src.array();
      byte[] out = dst.array();
      int inOffset = src.arrayOffset() + srcIndex;
      int outOffset = dst.arrayOffset() + dstIndex;
      if (in != out
          || outOffset - inOffset >= length
          || inOffset - outOffset >= length + OVERLAP_MARGIN) {
        update(in, inOffset, length, out, outOffset);
        return;
      }
    }
    byte[] scratch = SCRATCH.get();
    for (int done = 0; done < length; ) {
      int chunk = Math.min(CHUNK_SIZE, length - done);
      byte[] in;
      int inOffset;
      if (srcArray) {
        in = src.array();
        inOffset = src.arrayOffset() + srcIndex + done;
      } else {
        src.getBytes(srcIndex + done, scratch, 0, chunk);
        in = scratch;
        inOffset = 0;
      }
      if (dstArray && !srcArray) {
        // The source went to the scratch array, which the destination's array cannot overlap.
        update(in, inOffset, chunk, dst.array(), dst.arrayOffset() + dstIndex + done);
      } else {
        update(in, inOffset, chunk, scratch, CHUNK_SIZE);
        dst.setBytes(dstIndex + done, scratch, CHUNK_SIZE, chunk);
      }
      done += chunk;
    }
  }

  private void update(byte[] in, int inOffset, int length, byte[] out, int outOffset)
      throws ShortBufferException {
    int written = cipher.update(in, inOffset, length, out, outOffset);
    if (written != length) {
      throw new IllegalStateException(
          "AES/CFB8 turned " + length + " bytes into " + written + ", it should keep the length");
    }
  }
}
