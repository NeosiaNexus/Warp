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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.security.GeneralSecurityException;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("AesCfb8")
class AesCfb8Test {

  /** Calls before measuring, so that class loading and first-call setup are not counted. */
  private static final int WARM_UP = 200;

  private static final int MEASURED = 1000;

  private final SecretKey key = new SecretKeySpec(randomBytes(16, 3), "AES");

  @Nested
  @DisplayName("between two buffers")
  class BetweenBuffers {

    @Test
    @DisplayName("should write at the destination's index, between two heap buffers")
    void heapToHeapAtAnIndex() throws GeneralSecurityException {
      byte[] plain = randomBytes(1000, 5);
      ByteBuf src = Unpooled.wrappedBuffer(plain.clone());
      ByteBuf dst = Unpooled.buffer(1100).writeZero(1100);

      new AesCfb8(Cipher.ENCRYPT_MODE, key).transform(src, 0, dst, 100, plain.length);

      assertArrayEquals(reference(plain), ByteBufUtil.getBytes(dst, 100, plain.length));
      assertArrayEquals(new byte[100], ByteBufUtil.getBytes(dst, 0, 100));
      src.release();
      dst.release();
    }

    @Test
    @DisplayName("should write a direct source chunk after chunk at the heap destination's index")
    void directToHeapAtAnIndex() throws GeneralSecurityException {
      byte[] plain = randomBytes(2 * AesCfb8.CHUNK_SIZE + 5, 7);
      ByteBuf src = Unpooled.directBuffer(plain.length).writeBytes(plain);
      ByteBuf dst = Unpooled.buffer(plain.length + 7).writeZero(plain.length + 7);

      new AesCfb8(Cipher.ENCRYPT_MODE, key).transform(src, 0, dst, 7, plain.length);

      assertArrayEquals(reference(plain), ByteBufUtil.getBytes(dst, 7, plain.length));
      src.release();
      dst.release();
    }

    @Test
    @DisplayName("should transform on a thread that never transformed before")
    void onAFreshThread() throws Exception {
      byte[] plain = randomBytes(300, 9);
      AesCfb8 cipher = new AesCfb8(Cipher.ENCRYPT_MODE, key);
      AtomicReference<Object> result = new AtomicReference<>();

      Thread thread =
          Thread.ofPlatform()
              .start(
                  () -> {
                    ByteBuf src = Unpooled.directBuffer(plain.length).writeBytes(plain);
                    ByteBuf dst = Unpooled.directBuffer(plain.length).writeZero(plain.length);
                    try {
                      cipher.transform(src, 0, dst, 0, plain.length);
                      result.set(ByteBufUtil.getBytes(dst));
                    } catch (RuntimeException | ShortBufferException e) {
                      result.set(e);
                    } finally {
                      src.release();
                      dst.release();
                    }
                  });
      thread.join();

      Object outcome = result.get();
      byte[] encrypted = assertInstanceOf(byte[].class, outcome, () -> "it failed: " + outcome);
      assertArrayEquals(reference(plain), encrypted);
    }
  }

  @Nested
  @DisplayName("in place")
  class InPlace {

    @ParameterizedTest(name = "{1} bytes at index {0}")
    @CsvSource({"0, 48", "2048, 1024"})
    @DisplayName("should transform a heap buffer in place without allocating")
    void withoutAllocating(int index, int length) throws GeneralSecurityException {
      ByteBuf buf = Unpooled.buffer(4096).writeZero(4096);
      AesCfb8 cipher = new AesCfb8(Cipher.ENCRYPT_MODE, key);
      byte[] plain = randomBytes(length, 11);
      buf.setBytes(index, plain);
      cipher.transform(buf, index, buf, index, length);
      byte[] first = ByteBufUtil.getBytes(buf, index, length);
      for (int i = 0; i < WARM_UP; i++) {
        cipher.transform(buf, index, buf, index, length);
      }

      long allocated = allocatedBy(() -> transform(cipher, buf, index, length, MEASURED));

      assertArrayEquals(reference(plain), first);
      // The JDK copies its input on every overlapping call: at least the frame's length each time.
      assertTrue(
          allocated < MEASURED * 8L,
          () -> allocated + " bytes allocated by " + MEASURED + " in-place calls");
      buf.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void transform(AesCfb8 cipher, ByteBuf buf, int index, int length, int times) {
    try {
      for (int i = 0; i < times; i++) {
        cipher.transform(buf, index, buf, index, length);
      }
    } catch (ShortBufferException e) {
      throw new AssertionError(e);
    }
  }

  /** The bytes this thread allocates while running {@code action}. */
  private static long allocatedBy(Runnable action) {
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long before = threads.getCurrentThreadAllocatedBytes();
    action.run();
    return threads.getCurrentThreadAllocatedBytes() - before;
  }

  /** The JDK's AES/CFB8 encryption of {@code input}, with the secret as IV. */
  private byte[] reference(byte[] input) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
    return cipher.update(input);
  }

  private static byte[] randomBytes(int length, long seed) {
    byte[] bytes = new byte[length];
    new SplittableRandom(seed).nextBytes(bytes);
    return bytes;
  }
}
