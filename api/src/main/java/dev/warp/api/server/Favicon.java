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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * A server icon, as the server list carries it: a {@code data:image/png;base64,} URI of a 64x64 PNG
 * image. Clients show no icon of another size or format.
 *
 * <p>{@link #toString()} gives the icon's length, not its data: an icon is several kilobytes, which
 * would flood any log line that prints a ping or an event.
 *
 * @param uri the {@code data:image/png;base64,} URI
 */
public record Favicon(String uri) {

  /** What every icon URI starts with. */
  public static final String URI_PREFIX = "data:image/png;base64,";

  /** The width and height of an icon, in pixels: clients refuse any other size. */
  public static final int SIDE = 64;

  /** The eight bytes every PNG file starts with. */
  private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

  /** Where the header chunk's type, then the image's width and height, sit in a PNG file. */
  private static final int HEADER_TYPE_OFFSET = 12;

  private static final int WIDTH_OFFSET = 16;
  private static final int HEIGHT_OFFSET = 20;
  private static final int HEADER_END = 24;

  /**
   * Validates the URI's form. The image itself is not decoded: icons received from backends are
   * kept as they came.
   *
   * @throws IllegalArgumentException if the URI does not start with {@value #URI_PREFIX}
   */
  public Favicon {
    Objects.requireNonNull(uri, "uri");
    if (!uri.startsWith(URI_PREFIX)) {
      throw new IllegalArgumentException("A favicon is a " + URI_PREFIX + " URI");
    }
  }

  /**
   * Makes an icon of a PNG file's content, checking that it is a 64x64 PNG image.
   *
   * @param png the content of the PNG file
   * @return the icon
   * @throws IllegalArgumentException if the content is not a PNG image, or not 64x64 pixels (the
   *     message says which, and the size found)
   */
  public static Favicon fromPng(byte[] png) {
    if (png.length < HEADER_END
        || !Arrays.equals(png, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)
        || !new String(png, HEADER_TYPE_OFFSET, 4, StandardCharsets.US_ASCII).equals("IHDR")) {
      throw new IllegalArgumentException("not a PNG image");
    }
    ByteBuffer header = ByteBuffer.wrap(png);
    long width = Integer.toUnsignedLong(header.getInt(WIDTH_OFFSET));
    long height = Integer.toUnsignedLong(header.getInt(HEIGHT_OFFSET));
    if (width != SIDE || height != SIDE) {
      throw new IllegalArgumentException(
          "the image is "
              + width
              + "x"
              + height
              + " pixels; clients only show a "
              + SIDE
              + "x"
              + SIDE
              + " icon");
    }
    return new Favicon(URI_PREFIX + Base64.getEncoder().encodeToString(png));
  }

  /**
   * Returns the icon's length rather than its data.
   *
   * @return a description such as {@code Favicon[5326 characters]}
   */
  @Override
  public String toString() {
    return "Favicon[" + uri.length() + " characters]";
  }
}
