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
package dev.warp.proxy.migrate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict parser for the subset of TOML that {@code velocity.toml} uses: comments, bare and quoted
 * keys, dotted keys, tables, basic and literal strings, integers, floats, booleans, arrays and
 * inline tables. Dates and arrays of tables are refused with their line. The result holds {@link
 * String}, {@link Long}, {@link Double}, {@link Boolean}, {@link List} and {@link Map} values, in
 * the order of the file.
 *
 * <p>A dependency would bring a grammar runtime of several hundred kilobytes into the proxy's jar
 * for one file read once; this parser is a few hundred lines, and tested on Velocity's own default
 * file.
 */
final class Toml {

  /** A syntax error, with its line. */
  static final class TomlException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int line;

    TomlException(int line, String message) {
      super("line " + line + ": " + message);
      this.line = line;
    }

    /**
     * Returns the line of the error.
     *
     * @return the line, from 1
     */
    int line() {
      return line;
    }
  }

  private final String text;
  private int position;
  private int line = 1;

  private Toml(String text) {
    this.text = text;
  }

  /**
   * Parses a TOML document.
   *
   * @param text the document
   * @return its root table
   * @throws TomlException if the document is not valid, or uses a construct outside the subset
   */
  static Map<String, Object> parse(String text) throws TomlException {
    return new Toml(text).document();
  }

  // ---------------------------------------------------------------------------
  // Document structure
  // ---------------------------------------------------------------------------

  private Map<String, Object> document() throws TomlException {
    Map<String, Object> root = new LinkedHashMap<>();
    Map<String, Object> current = root;
    while (true) {
      skipBlankAndComments();
      if (atEnd()) {
        return root;
      }
      if (peek() == '[') {
        if (peekAt(1) == '[') {
          throw error("arrays of tables ([[name]]) are not supported");
        }
        position++;
        skipSpaces();
        List<String> path = key();
        skipSpaces();
        expect(']', "']' after the table name");
        current = table(root, path);
        endOfLine();
        continue;
      }
      List<String> path = key();
      skipSpaces();
      expect('=', "'=' after the key");
      skipSpaces();
      Object value = value();
      put(current, path, value);
      endOfLine();
    }
  }

  /** Walks to (and creates) the table of a header, so that later keys land in it. */
  private Map<String, Object> table(Map<String, Object> root, List<String> path)
      throws TomlException {
    Map<String, Object> current = root;
    for (String part : path) {
      Object existing = current.get(part);
      if (existing == null) {
        Map<String, Object> created = new LinkedHashMap<>();
        current.put(part, created);
        current = created;
      } else if (existing instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) map;
        current = cast;
      } else {
        throw error("'" + part + "' is a value, not a table");
      }
    }
    return current;
  }

  private void put(Map<String, Object> table, List<String> path, Object value)
      throws TomlException {
    Map<String, Object> current = table(table, path.subList(0, path.size() - 1));
    String last = path.getLast();
    if (current.containsKey(last)) {
      throw error("'" + last + "' is defined twice");
    }
    current.put(last, value);
  }

  // ---------------------------------------------------------------------------
  // Keys
  // ---------------------------------------------------------------------------

  private List<String> key() throws TomlException {
    List<String> parts = new ArrayList<>();
    while (true) {
      parts.add(keyPart());
      skipSpaces();
      if (peek() != '.') {
        return parts;
      }
      position++;
      skipSpaces();
    }
  }

  private String keyPart() throws TomlException {
    char c = peek();
    if (c == '"') {
      return basicString();
    }
    if (c == '\'') {
      return literalString();
    }
    int start = position;
    while (!atEnd() && isBareKeyChar(peek())) {
      position++;
    }
    if (start == position) {
      throw error(atEnd() ? "unexpected end of file, expected a key" : "unexpected '" + c + "'");
    }
    return text.substring(start, position);
  }

  private static boolean isBareKeyChar(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '_';
  }

  // ---------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------

  private Object value() throws TomlException {
    if (atEnd()) {
      throw error("unexpected end of file, expected a value");
    }
    char c = peek();
    return switch (c) {
      case '"' -> startsWith("\"\"\"") ? multilineBasicString() : basicString();
      case '\'' -> startsWith("'''") ? multilineLiteralString() : literalString();
      case '[' -> array();
      case '{' -> inlineTable();
      default -> scalar();
    };
  }

  private Object scalar() throws TomlException {
    int start = position;
    while (!atEnd() && isScalarChar(peek())) {
      position++;
    }
    String token = text.substring(start, position);
    return switch (token) {
      case "true" -> Boolean.TRUE;
      case "false" -> Boolean.FALSE;
      case "" -> throw error("unexpected '" + peek() + "', expected a value");
      default -> number(token);
    };
  }

  private static boolean isScalarChar(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '+'
        || c == '_'
        || c == '.'
        || c == ':';
  }

  private Object number(String token) throws TomlException {
    if (token.contains(":") || token.chars().filter(ch -> ch == '-').count() > 1) {
      throw error("dates and times are not supported (" + token + ")");
    }
    String digits = token.replace("_", "");
    try {
      if (digits.contains(".") || digits.contains("e") || digits.contains("E")) {
        return Double.valueOf(digits);
      }
      if (digits.startsWith("0x") || digits.startsWith("0o") || digits.startsWith("0b")) {
        int radix = digits.charAt(1) == 'x' ? 16 : digits.charAt(1) == 'o' ? 8 : 2;
        return Long.valueOf(digits.substring(2), radix);
      }
      return Long.valueOf(digits);
    } catch (NumberFormatException e) {
      throw error("'" + token + "' is not a value TOML knows");
    }
  }

  private List<Object> array() throws TomlException {
    expect('[', "'['");
    List<Object> values = new ArrayList<>();
    while (true) {
      skipBlankAndComments();
      if (atEnd()) {
        throw error("unterminated array");
      }
      if (peek() == ']') {
        position++;
        return values;
      }
      values.add(value());
      skipBlankAndComments();
      if (atEnd()) {
        throw error("unterminated array");
      }
      if (peek() == ',') {
        position++;
      } else if (peek() != ']') {
        throw error("expected ',' or ']' in an array");
      }
    }
  }

  private Map<String, Object> inlineTable() throws TomlException {
    expect('{', "'{'");
    Map<String, Object> table = new LinkedHashMap<>();
    skipSpaces();
    if (peek() == '}') {
      position++;
      return table;
    }
    while (true) {
      skipSpaces();
      List<String> path = key();
      skipSpaces();
      expect('=', "'=' after the key");
      skipSpaces();
      put(table, path, value());
      skipSpaces();
      if (atEnd()) {
        throw error("unterminated inline table");
      }
      char c = peek();
      position++;
      if (c == '}') {
        return table;
      }
      if (c != ',') {
        throw error("expected ',' or '}' in an inline table");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Strings
  // ---------------------------------------------------------------------------

  private String basicString() throws TomlException {
    expect('"', "'\"'");
    StringBuilder out = new StringBuilder();
    while (true) {
      if (atEnd() || peek() == '\n') {
        throw error("unterminated string");
      }
      char c = text.charAt(position++);
      if (c == '"') {
        return out.toString();
      }
      if (c == '\\') {
        out.append(escape());
      } else {
        out.append(c);
      }
    }
  }

  private String multilineBasicString() throws TomlException {
    position += 3;
    skipFirstNewline();
    StringBuilder out = new StringBuilder();
    while (true) {
      if (atEnd()) {
        throw error("unterminated string");
      }
      if (startsWith("\"\"\"")) {
        position += 3;
        return out.toString();
      }
      char c = text.charAt(position++);
      if (c == '\n') {
        line++;
        out.append(c);
      } else if (c == '\\') {
        if (peek() == '\n' || peek() == ' ' || peek() == '\t' || peek() == '\r') {
          skipBlank();
        } else {
          out.append(escape());
        }
      } else {
        out.append(c);
      }
    }
  }

  private String literalString() throws TomlException {
    expect('\'', "'''");
    int start = position;
    while (!atEnd() && peek() != '\'' && peek() != '\n') {
      position++;
    }
    if (atEnd() || peek() != '\'') {
      throw error("unterminated string");
    }
    String value = text.substring(start, position);
    position++;
    return value;
  }

  private String multilineLiteralString() throws TomlException {
    position += 3;
    skipFirstNewline();
    int end = text.indexOf("'''", position);
    if (end < 0) {
      throw error("unterminated string");
    }
    String value = text.substring(position, end);
    line += (int) value.chars().filter(c -> c == '\n').count();
    position = end + 3;
    return value;
  }

  private String escape() throws TomlException {
    if (atEnd()) {
      throw error("unterminated string");
    }
    char c = text.charAt(position++);
    return switch (c) {
      case 'b' -> "\b";
      case 't' -> "\t";
      case 'n' -> "\n";
      case 'f' -> "\f";
      case 'r' -> "\r";
      case '"' -> "\"";
      case '\\' -> "\\";
      case 'u' -> unicode(4);
      case 'U' -> unicode(8);
      default -> throw error("unknown escape '\\" + c + "'");
    };
  }

  private String unicode(int digits) throws TomlException {
    if (position + digits > text.length()) {
      throw error("unterminated unicode escape");
    }
    String hex = text.substring(position, position + digits);
    position += digits;
    try {
      return Character.toString(Integer.parseInt(hex, 16));
    } catch (IllegalArgumentException e) {
      throw error("invalid unicode escape '\\u" + hex + "'");
    }
  }

  // ---------------------------------------------------------------------------
  // Whitespace
  // ---------------------------------------------------------------------------

  private void skipSpaces() {
    while (!atEnd() && (peek() == ' ' || peek() == '\t')) {
      position++;
    }
  }

  private void skipBlank() {
    while (!atEnd() && Character.isWhitespace(peek())) {
      if (peek() == '\n') {
        line++;
      }
      position++;
    }
  }

  private void skipBlankAndComments() {
    while (true) {
      skipBlank();
      if (!atEnd() && peek() == '#') {
        while (!atEnd() && peek() != '\n') {
          position++;
        }
      } else {
        return;
      }
    }
  }

  private void skipFirstNewline() {
    if (startsWith("\r\n")) {
      position += 2;
      line++;
    } else if (!atEnd() && peek() == '\n') {
      position++;
      line++;
    }
  }

  /** After a key-value pair or a header: only spaces and a comment may follow on the line. */
  private void endOfLine() throws TomlException {
    skipSpaces();
    if (atEnd()) {
      return;
    }
    char c = peek();
    if (c == '#') {
      while (!atEnd() && peek() != '\n') {
        position++;
      }
      return;
    }
    if (c == '\r' || c == '\n') {
      return;
    }
    throw error("unexpected '" + c + "' after the value");
  }

  // ---------------------------------------------------------------------------
  // Low-level
  // ---------------------------------------------------------------------------

  private boolean atEnd() {
    return position >= text.length();
  }

  private char peek() {
    return atEnd() ? '\0' : text.charAt(position);
  }

  private char peekAt(int offset) {
    return position + offset >= text.length() ? '\0' : text.charAt(position + offset);
  }

  private boolean startsWith(String prefix) {
    return text.startsWith(prefix, position);
  }

  private void expect(char c, String what) throws TomlException {
    if (peek() != c) {
      throw error(atEnd() ? "unexpected end of file, expected " + what : "expected " + what);
    }
    position++;
  }

  private TomlException error(String message) {
    return new TomlException(line, message);
  }
}
