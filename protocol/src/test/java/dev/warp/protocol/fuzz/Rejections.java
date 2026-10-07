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

import java.util.List;

import io.netty.handler.codec.DecoderException;

/**
 * Tells a decoder rejecting malformed input from a decoder failing on it.
 *
 * <p>A Netty decoder rejects malformed input with a {@link DecoderException}. Code that detects the
 * problem through another exception may translate it into one, keeping it as the cause: that is a
 * rejection too. A decoder's catch-all, such as Netty's around {@code decode} or {@code
 * MinecraftDecoder}'s around each codec, also wraps whatever else escapes: an {@link
 * ArrayIndexOutOfBoundsException}, a {@link NullPointerException}, an {@link
 * IllegalArgumentException} from a negative size. Those are bugs, unless the decoder relies on them
 * to report malformed input, as on Netty's {@link IndexOutOfBoundsException} for a read past the
 * end of a buffer.
 */
public final class Rejections {

  private Rejections() {}

  /**
   * Asserts that {@code thrown} is a rejection: a {@link DecoderException}, possibly wrapping more
   * of them, whose first other cause, if any, was either translated on purpose (a nested {@link
   * DecoderException} wraps it) or is an instance of exactly one of {@code expectedCauses}.
   *
   * @param thrown what the decoder threw
   * @param expectedCauses the classes of the exceptions the decoder relies on to report malformed
   *     input; their subclasses do not match
   * @throws AssertionError caused by {@code thrown} if it is not a rejection
   */
  public static void assertRejection(Throwable thrown, Class<?>... expectedCauses) {
    if (!(thrown instanceof DecoderException)) {
      throw new AssertionError("Expected a DecoderException, got " + thrown, thrown);
    }
    Throwable wrapper = thrown;
    Throwable cause = thrown.getCause();
    while (cause instanceof DecoderException) {
      wrapper = cause;
      cause = cause.getCause();
    }
    if (cause != null && wrapper == thrown && !List.of(expectedCauses).contains(cause.getClass())) {
      throw new AssertionError("A decoder's catch-all wrapped an unexpected " + cause, thrown);
    }
  }
}
