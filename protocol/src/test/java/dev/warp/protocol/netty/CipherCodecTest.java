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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.SplittableRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CipherEncoder and CipherDecoder")
class CipherCodecTest {

  /** Message sizes around the 16-byte AES block, written one after the other. */
  private static final int[] SIZES = {1, 15, 16, 17, 300, 0, 4096};

  private final SecretKey key = randomKey();
  private final byte[] plaintext = randomBytes(Arrays.stream(SIZES).sum());

  @Nested
  @DisplayName("encryption")
  class Encryption {

    @Test
    @DisplayName("should encrypt the stream as AES/CFB8 keyed and seeded by the shared secret")
    void encryptsLikeTheProtocol() throws GeneralSecurityException {
      EmbeddedChannel ch = new EmbeddedChannel(new CipherEncoder(key));

      for (byte[] message : messages()) {
        ch.writeOutbound(Unpooled.wrappedBuffer(message));
      }

      assertArrayEquals(reference(Cipher.ENCRYPT_MODE, plaintext), drainOutbound(ch));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should encrypt a direct buffer the same way")
    void directBuffers() throws GeneralSecurityException {
      EmbeddedChannel ch = new EmbeddedChannel(new CipherEncoder(key));

      ch.writeOutbound(Unpooled.directBuffer().writeBytes(plaintext));

      assertArrayEquals(reference(Cipher.ENCRYPT_MODE, plaintext), drainOutbound(ch));
      assertFalse(ch.finish());
    }
  }

  @Nested
  @DisplayName("decryption")
  class Decryption {

    @Test
    @DisplayName("should decrypt the stream whatever the segment sizes")
    void decryptsLikeTheProtocol() throws GeneralSecurityException {
      byte[] ciphertext = reference(Cipher.ENCRYPT_MODE, plaintext);
      EmbeddedChannel ch = new EmbeddedChannel(new CipherDecoder(key));

      int offset = 0;
      for (int size : SIZES) {
        ch.writeInbound(Unpooled.wrappedBuffer(ciphertext, offset, size));
        offset += size;
      }

      assertArrayEquals(plaintext, drainInbound(ch));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should pass nothing on for an empty segment")
    void emptySegment() throws GeneralSecurityException {
      EmbeddedChannel ch = new EmbeddedChannel(new CipherDecoder(key));

      ch.writeInbound(Unpooled.EMPTY_BUFFER);

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }
  }

  @Test
  @DisplayName("should read back through a decoder what an encoder with the same secret wrote")
  void roundTrip() throws GeneralSecurityException {
    EmbeddedChannel encoder = new EmbeddedChannel(new CipherEncoder(key));
    EmbeddedChannel decoder = new EmbeddedChannel(new CipherDecoder(key));

    for (byte[] message : messages()) {
      encoder.writeOutbound(Unpooled.wrappedBuffer(message));
    }
    decoder.writeInbound(Unpooled.wrappedBuffer(drainOutbound(encoder)));

    assertArrayEquals(plaintext, drainInbound(decoder));
    assertFalse(encoder.finish());
    assertFalse(decoder.finish());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The plaintext cut into {@link #SIZES}. */
  private byte[][] messages() {
    byte[][] messages = new byte[SIZES.length][];
    int offset = 0;
    for (int i = 0; i < SIZES.length; i++) {
      messages[i] = Arrays.copyOfRange(plaintext, offset, offset + SIZES[i]);
      offset += SIZES[i];
    }
    return messages;
  }

  /** The JDK's AES/CFB8 over the whole stream, with the secret as IV as the protocol requires. */
  private byte[] reference(int mode, byte[] input) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    cipher.init(mode, key, new IvParameterSpec(key.getEncoded()));
    return cipher.update(input);
  }

  private static byte[] drainOutbound(EmbeddedChannel ch) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (ByteBuf buf; (buf = ch.readOutbound()) != null; ) {
      append(out, buf);
    }
    return out.toByteArray();
  }

  private static byte[] drainInbound(EmbeddedChannel ch) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (ByteBuf buf; (buf = ch.readInbound()) != null; ) {
      append(out, buf);
    }
    return out.toByteArray();
  }

  private static void append(ByteArrayOutputStream out, ByteBuf buf) {
    try {
      byte[] bytes = new byte[buf.readableBytes()];
      buf.readBytes(bytes);
      out.writeBytes(bytes);
    } finally {
      buf.release();
    }
  }

  private static SecretKey randomKey() {
    return new SecretKeySpec(randomBytes(16), "AES");
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    new SplittableRandom(length).nextBytes(bytes);
    return bytes;
  }
}
