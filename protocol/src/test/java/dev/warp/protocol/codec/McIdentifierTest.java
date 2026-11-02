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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("McIdentifier codec")
class McIdentifierTest {

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("validation")
  class Validation {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = {
          "minecraft:brand",
          "brand",
          ":brand",
          "primemc:session/token-v2.bin",
          "a_b.c-d:e_f.g-h/i",
          "0123456789:abc",
          "minecraft:",
          "..:path"
        })
    @DisplayName("should accept what vanilla's resource location parser accepts")
    void acceptsVanillaIdentifiers(String identifier) {
      assertTrue(McIdentifier.isValid(identifier));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = {
          "Minecraft:brand",
          "minecraft:Brand",
          "name/space:path",
          "minecraft:a:b",
          "minecraft:with space",
          "minecraft:é",
          "minecraft:tab\t"
        })
    @DisplayName("should refuse upper case, a slash in the namespace, a second colon and others")
    void refusesInvalidCharacters(String identifier) {
      assertFalse(McIdentifier.isValid(identifier));
    }

    @Test
    @DisplayName("should refuse an identifier longer than the protocol string limit")
    void refusesTooLong() {
      String longest = "a".repeat(McIdentifier.MAX_LENGTH);

      assertTrue(McIdentifier.isValid(longest));
      assertFalse(McIdentifier.isValid(longest + "a"));
    }

    @Test
    @DisplayName("should name the offending identifier, abbreviated, when refusing it")
    void namesRefusedIdentifier() {
      IllegalArgumentException refused =
          assertThrows(
              IllegalArgumentException.class, () -> McIdentifier.requireValid("A".repeat(100)));

      assertTrue(String.valueOf(refused.getMessage()).contains("'" + "A".repeat(64) + "...'"));
    }
  }

  // ---------------------------------------------------------------------------
  // Canonical form
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("canonical form")
  class Canonical {

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource({
      "brand, minecraft:brand",
      ":brand, minecraft:brand",
      "minecraft:brand, minecraft:brand",
      "primemc:session, primemc:session",
      "minecraft:, minecraft:"
    })
    @DisplayName("should add the minecraft namespace when there is none")
    void addsDefaultNamespace(String identifier, String canonical) {
      assertEquals(canonical, McIdentifier.canonical(identifier));
    }

    @Test
    @DisplayName("should return an identifier with its namespace as is")
    void keepsNamespacedIdentifier() {
      String identifier = "primemc:session";

      assertSame(identifier, McIdentifier.canonical(identifier));
    }

    @Test
    @DisplayName("should refuse to canonicalise an invalid identifier")
    void refusesInvalid() {
      assertThrows(IllegalArgumentException.class, () -> McIdentifier.canonical("Bad:Key"));
    }
  }

  // ---------------------------------------------------------------------------
  // Wire
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("wire")
  class Wire {

    @Test
    @DisplayName("should read an identifier exactly as written, without adding a namespace")
    void readsAsWritten() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "brand");

        assertEquals("brand", McIdentifier.read(buf));
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should write a protocol string: VarInt length, then UTF-8")
    void writesProtocolString() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McIdentifier.write(buf, "minecraft:brand");

        byte[] expected = "\u000fminecraft:brand".getBytes(StandardCharsets.UTF_8);
        assertEquals(ByteBufUtil.hexDump(expected), ByteBufUtil.hexDump(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should refuse to decode an invalid identifier")
    void refusesInvalidOnRead() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "Minecraft:Brand");

        assertThrows(DecoderException.class, () -> McIdentifier.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should refuse to decode an identifier longer than the protocol string limit")
    void refusesTooLongOnRead() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, McIdentifier.MAX_LENGTH * 3 + 1);
        buf.writeZero(McIdentifier.MAX_LENGTH * 3 + 1);

        assertThrows(DecoderException.class, () -> McIdentifier.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should refuse to encode an invalid identifier, writing nothing")
    void refusesInvalidOnWrite() {
      ByteBuf buf = Unpooled.buffer();
      try {
        assertThrows(IllegalArgumentException.class, () -> McIdentifier.write(buf, "a b"));
        assertFalse(buf.isReadable());
      } finally {
        buf.release();
      }
    }
  }
}
