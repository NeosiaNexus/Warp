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
package dev.warp.jni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ThreadContexts")
class ThreadContextsTest {

  @BeforeEach
  void requireNative() {
    NativeAssumptions.requireNativeCompression();
  }

  @Nested
  @DisplayName("sharing")
  class Sharing {

    @Test
    @DisplayName("should give every codec of a thread the same compressor for a level")
    void onePerThreadAndLevel() throws Exception {
      // given
      EventLoopGroup loop = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try {
        // when: a thousand connections' codecs compress on one event loop
        long[] compressors =
            loop.submit(
                    () -> {
                      long first = ThreadContexts.current().compressor(6);
                      for (int i = 0; i < 1000; i++) {
                        compress(LibdeflateCodec.create(6));
                      }
                      return new long[] {
                        first,
                        ThreadContexts.current().compressor(6),
                        ThreadContexts.current().compressor(1)
                      };
                    })
                .get(10, TimeUnit.SECONDS);

        // then
        assertEquals(compressors[0], compressors[1], "same context for every connection");
        assertNotEquals(compressors[0], compressors[2], "one context per level");
      } finally {
        loop.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
      }
    }

    @Test
    @DisplayName("should give each thread contexts of its own")
    void perThread() throws Exception {
      // given
      EventLoopGroup loops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
      try {
        // when
        long first = loops.next().submit(() -> ThreadContexts.current().decompressor()).get();
        long second = loops.next().submit(() -> ThreadContexts.current().decompressor()).get();

        // then
        assertNotEquals(first, second);
      } finally {
        loops.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
      }
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("should free an event loop's contexts when the event loop shuts down")
    void freedOnShutdown() throws Exception {
      // given
      int before = ThreadContexts.live();
      EventLoopGroup loops = new MultiThreadIoEventLoopGroup(4, NioIoHandler.newFactory());
      for (int i = 0; i < 4; i++) {
        loops
            .next()
            .submit(
                () -> {
                  compress(LibdeflateCodec.create(6));
                  decompress();
                  return null;
                })
            .get();
      }
      assertEquals(before + 8, ThreadContexts.live(), "a compressor and a decompressor per loop");

      // when
      loops.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
      loops.terminationFuture().sync();

      // then
      awaitLive(before, Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("should free a plain thread's contexts once the thread is gone")
    void freedAfterPlainThread() throws Exception {
      // given
      int before = ThreadContexts.live();
      Thread thread = new Thread(() -> compress(LibdeflateCodec.create(3)));
      thread.start();
      thread.join();
      assertEquals(before + 1, ThreadContexts.live());

      // when
      thread = null;

      // then: the cleaner frees them once the thread object is collected
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (ThreadContexts.live() != before && System.nanoTime() < deadline) {
        System.gc();
        Thread.sleep(50);
      }
      assertEquals(before, ThreadContexts.live());
    }
  }

  private static void compress(LibdeflateCodec codec) {
    ByteBuf source = Unpooled.directBuffer().writeBytes(new byte[1000]);
    ByteBuf destination = Unpooled.directBuffer();
    codec.deflate(source, destination);
    source.release();
    destination.release();
  }

  private static void decompress() throws java.util.zip.DataFormatException {
    LibdeflateCodec codec = LibdeflateCodec.create(6);
    ByteBuf source = Unpooled.directBuffer().writeBytes(new byte[1000]);
    ByteBuf compressed = Unpooled.directBuffer();
    codec.deflate(source, compressed);
    ByteBuf destination = Unpooled.directBuffer();
    codec.inflate(compressed, destination, 1000);
    source.release();
    compressed.release();
    destination.release();
  }

  private static void awaitLive(int expected, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (ThreadContexts.live() != expected && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(expected, ThreadContexts.live());
  }
}
