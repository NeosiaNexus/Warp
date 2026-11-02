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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Identifier codec for the Minecraft protocol: a namespaced key such as {@code minecraft:brand},
 * which vanilla calls a resource location and Adventure a key.
 *
 * <p>On the wire an identifier is a protocol string of at most {@value #MAX_LENGTH} characters. It
 * is valid when it reads {@code namespace:path} or {@code path}, the namespace made of {@code
 * [a-z0-9_.-]} and the path of {@code [a-z0-9_.-/]}, as vanilla's {@code ResourceLocation.parse}
 * accepts it. Without a namespace, or with an empty one ({@code ":path"}), the namespace is {@code
 * minecraft}: {@code brand}, {@code :brand} and {@code minecraft:brand} name the same identifier,
 * whose {@linkplain #canonical canonical form} is the last one.
 *
 * <p>The codec keeps the string exactly as written, so a packet the proxy relays carries the bytes
 * its sender chose; compare identifiers through their canonical form.
 */
public final class McIdentifier {

  /** Longest identifier a vanilla peer reads, in UTF-16 code units: the protocol string limit. */
  public static final int MAX_LENGTH = McString.DEFAULT_MAX_CHARS;

  /** Namespace of an identifier written without one. */
  public static final String DEFAULT_NAMESPACE = "minecraft";

  private static final char SEPARATOR = ':';

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException INVALID =
      new DecoderException("Identifier has a character outside [a-z0-9_.-] or [a-z0-9_.-/]") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  private McIdentifier() {}

  // ---------------------------------------------------------------------------
  // Read and write
  // ---------------------------------------------------------------------------

  /**
   * Reads an identifier, exactly as written.
   *
   * @param buf the buffer to read from
   * @return the identifier as written by the peer, possibly without its namespace
   * @throws DecoderException if the string is malformed, longer than {@value #MAX_LENGTH}
   *     characters, or not a valid identifier
   */
  public static String read(ByteBuf buf) {
    String identifier = McString.read(buf, MAX_LENGTH);
    if (!isValid(identifier)) {
      throw INVALID;
    }
    return identifier;
  }

  /**
   * Writes an identifier exactly as given.
   *
   * @param buf the buffer to write to
   * @param identifier the identifier, with or without its namespace
   * @throws IllegalArgumentException if {@code identifier} is not a valid identifier, or is longer
   *     than {@value #MAX_LENGTH} characters
   */
  public static void write(ByteBuf buf, String identifier) {
    requireValid(identifier);
    McString.write(buf, identifier, MAX_LENGTH);
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  /**
   * Returns whether {@code identifier} is an identifier a vanilla peer accepts.
   *
   * @param identifier the string to check
   * @return {@code true} if it is at most {@value #MAX_LENGTH} characters of {@code namespace:path}
   *     or {@code path} with only allowed characters
   */
  public static boolean isValid(String identifier) {
    if (identifier.length() > MAX_LENGTH) {
      return false;
    }
    int separator = identifier.indexOf(SEPARATOR);
    for (int i = 0; i < identifier.length(); i++) {
      char c = identifier.charAt(i);
      boolean allowed = i < separator ? isNamespaceChar(c) : i == separator || isPathChar(c);
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns {@code identifier} if it is valid.
   *
   * @param identifier the string to check
   * @return {@code identifier}
   * @throws IllegalArgumentException if it is not a valid identifier
   */
  public static String requireValid(String identifier) {
    if (!isValid(identifier)) {
      throw new IllegalArgumentException(
          "Not a valid identifier (namespace [a-z0-9_.-], path [a-z0-9_.-/], at most "
              + MAX_LENGTH
              + " characters): '"
              + abbreviate(identifier)
              + "'");
    }
    return identifier;
  }

  /**
   * Returns the canonical form of a valid identifier: {@code namespace:path}, with the {@value
   * #DEFAULT_NAMESPACE} namespace when it has none. Two identifiers are the same when their
   * canonical forms are equal, as the client stores cookies and channels under them.
   *
   * @param identifier a valid identifier
   * @return {@code identifier} itself when it already names its namespace
   * @throws IllegalArgumentException if it is not a valid identifier
   */
  public static String canonical(String identifier) {
    requireValid(identifier);
    int separator = identifier.indexOf(SEPARATOR);
    if (separator > 0) {
      return identifier;
    }
    return DEFAULT_NAMESPACE + SEPARATOR + identifier.substring(separator + 1);
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private static boolean isNamespaceChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
  }

  private static boolean isPathChar(char c) {
    return isNamespaceChar(c) || c == '/';
  }

  /** Keeps error messages readable when the offending string is huge. */
  private static String abbreviate(String text) {
    int max = 64;
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }
}
