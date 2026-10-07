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
package dev.warp.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.HeapAllocations;
import dev.warp.protocol.fuzz.Rejections;
import dev.warp.protocol.fuzz.Wire;
import dev.warp.protocol.fuzz.Wire.VarNum;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongToIntFunction;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes {@link VarInt} and {@link VarLong} against the reference decoder of {@link Wire}, on the
 * same bytes: every prefix length reaches a different branch of their unrolled readers.
 */
@DisplayName("VarInt and VarLong fuzzing")
class VarIntFuzzTest {

  @FuzzTest
  @DisplayName(
      "should read like the reference, nothing past the readable bytes, allocating nothing")
  void decode(FuzzedDataProvider data) {
    byte[] bytes = data.consumeRemainingAsBytes();
    for (Codec codec : List.of(Codec.VAR_INT, Codec.VAR_LONG)) {
      VarNum expected = Wire.varNum(bytes, 0, codec.maxBytes());
      for (ByteBuf buf : buffers(bytes)) {
        checkRead(codec, buf, expected);
        int start = buf.readerIndex();
        HeapAllocations.assertAtMost(0, () -> readOrReject(codec, buf, start));
      }
      if (expected != null) {
        checkWrite(codec, codec.truncate(expected.value()));
      }
    }
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    HexFormat hex = HexFormat.of();
    new FuzzSeeds(VarIntFuzzTest.class, "decode")
        .add("zero", hex.parseHex("00"))
        .add("two-bytes", hex.parseHex("8001"))
        .add("frame-length-max", hex.parseHex("ffff7f"))
        .add("int-max", hex.parseHex("ffffffff07"))
        .add("int-minus-one", hex.parseHex("ffffffff0f"))
        .add("int-ignored-high-bits", hex.parseHex("ffffffff7f"))
        .add("overlong-zero", hex.parseHex("8080808000"))
        .add("long-min", hex.parseHex("80808080808080808001"))
        .add("long-minus-one", hex.parseHex("ffffffffffffffffff01"))
        .add("too-long", hex.parseHex("ffffffffffffffffffff01"))
        .add("truncated", hex.parseHex("ffffff"))
        .verify();
  }

  // ---------------------------------------------------------------------------
  // Checks
  // ---------------------------------------------------------------------------

  /** Reading and skipping both consume exactly the reference's bytes, or reject without moving. */
  private static void checkRead(Codec codec, ByteBuf buf, @Nullable VarNum expected) {
    int start = buf.readerIndex();
    Supplier<String> input = () -> ByteBufUtil.hexDump(buf, start, buf.writerIndex() - start);
    if (expected == null) {
      Rejections.assertRejection(
          assertThrows(DecoderException.class, () -> codec.read().applyAsLong(buf), input));
      assertEquals(start, buf.readerIndex(), "a rejected read moved the reader index");
      Rejections.assertRejection(
          assertThrows(DecoderException.class, () -> codec.skip().accept(buf), input));
      assertEquals(start, buf.readerIndex(), "a rejected skip moved the reader index");
      return;
    }
    assertEquals(codec.truncate(expected.value()), codec.read().applyAsLong(buf), input);
    assertEquals(start + expected.length(), buf.readerIndex(), input);
    buf.readerIndex(start);
    codec.skip().accept(buf);
    assertEquals(start + expected.length(), buf.readerIndex(), input);
  }

  /** The shortest encoding: as long as the value's significant bits need, and read back whole. */
  private static void checkWrite(Codec codec, long value) {
    int shortest = Math.max(1, (codec.significantBits(value) + 6) / 7);
    ByteBuf buf = Unpooled.buffer();
    codec.write().accept(buf, value);
    byte[] encoded = ByteBufUtil.getBytes(buf);

    assertEquals(shortest, encoded.length, () -> "encoding of " + value);
    assertEquals(shortest, codec.size().applyAsInt(value), () -> "size of " + value);
    VarNum decoded = Wire.varNum(encoded, 0, codec.maxBytes());
    assertNotNull(decoded, () -> "encoding of " + value);
    assertEquals(value, codec.truncate(decoded.value()));
  }

  private static void readOrReject(Codec codec, ByteBuf buf, int start) {
    buf.readerIndex(start);
    try {
      var _ = codec.read().applyAsLong(buf);
    } catch (DecoderException rejected) {
      // Thrown pre-allocated: the error path allocates nothing either.
    }
  }

  /** The bytes alone, then between guard bytes that read as ends and as continuations. */
  private static List<ByteBuf> buffers(byte[] bytes) {
    return List.of(
        Unpooled.wrappedBuffer(bytes), Wire.guarded(bytes, 0x00), Wire.guarded(bytes, 0xFF));
  }

  /** VarInt or VarLong, through longs. */
  private record Codec(
      int maxBytes,
      int bits,
      ToLongFunction<ByteBuf> read,
      Consumer<ByteBuf> skip,
      ObjLongConsumer<ByteBuf> write,
      LongToIntFunction size) {

    static final Codec VAR_INT =
        new Codec(
            VarInt.MAX_BYTES,
            Integer.SIZE,
            VarInt::read,
            VarInt::skip,
            (buf, value) -> VarInt.write(buf, (int) value),
            value -> VarInt.size((int) value));

    static final Codec VAR_LONG =
        new Codec(
            VarLong.MAX_BYTES,
            Long.SIZE,
            VarLong::read,
            VarLong::skip,
            VarLong::write,
            VarLong::size);

    /** Returns the value this codec reads from a number: its low {@link #bits}, sign-extended. */
    long truncate(long value) {
      return bits == Integer.SIZE ? (int) value : value;
    }

    /** Returns how many low bits of the value this codec writes, up to its highest set bit. */
    int significantBits(long value) {
      return bits == Integer.SIZE
          ? Integer.SIZE - Integer.numberOfLeadingZeros((int) value)
          : Long.SIZE - Long.numberOfLeadingZeros(value);
    }
  }
}
