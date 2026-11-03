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
package dev.warp.api.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Favicon")
class FaviconTest {

  @Nested
  @DisplayName("fromPng()")
  class FromPng {

    @Test
    @DisplayName("should make a data URI of a 64x64 PNG image")
    void valid() {
      byte[] png = png(64, 64);

      Favicon favicon = Favicon.fromPng(png);

      assertTrue(favicon.uri().startsWith("data:image/png;base64,"));
      assertArrayEquals(
          png, Base64.getDecoder().decode(favicon.uri().substring(Favicon.URI_PREFIX.length())));
    }

    @Test
    @DisplayName("should refuse another size, naming the size found")
    void wrongSize() {
      byte[] png = png(128, 64);

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> Favicon.fromPng(png));

      assertTrue(String.valueOf(error.getMessage()).contains("128x64"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse what is not a PNG image")
    void notPng() {
      byte[] jpeg = image(64, 64, "jpg");

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> Favicon.fromPng(jpeg));

      assertEquals("not a PNG image", error.getMessage());
    }

    @Test
    @DisplayName("should refuse a file too short to hold a PNG header")
    void truncated() {
      assertThrows(IllegalArgumentException.class, () -> Favicon.fromPng(new byte[10]));
    }
  }

  @Test
  @DisplayName("should refuse a URI that is not a PNG data URI")
  void wrongUri() {
    assertThrows(IllegalArgumentException.class, () -> new Favicon("https://example.net/a.png"));
  }

  @Test
  @DisplayName("should describe itself by length, not by its data")
  void shortToString() {
    Favicon favicon = Favicon.fromPng(png(64, 64));

    String text = favicon.toString();

    assertEquals("Favicon[" + favicon.uri().length() + " characters]", text);
    assertFalse(text.contains("base64"));
  }

  /** A PNG image of the given size. */
  static byte[] png(int width, int height) {
    return image(width, height, "png");
  }

  private static byte[] image(int width, int height, String format) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0x38bdf8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, format, out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
