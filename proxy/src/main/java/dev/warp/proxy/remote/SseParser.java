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
package dev.warp.proxy.remote;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Reads a Server-Sent Events stream line by line, as the WHATWG specification describes it: {@code
 * event}, {@code data} (joined by line feeds), {@code id} and {@code retry} fields, comments
 * skipped, an event dispatched at each blank line.
 */
final class SseParser {

  /**
   * An event.
   *
   * @param name its type, {@code message} when the stream names none
   * @param data its data
   * @param id the last event id seen, or {@code null}
   */
  record Event(String name, String data, @Nullable String id) {}

  private final Consumer<Event> events;
  private String name = "";
  private final StringBuilder data = new StringBuilder();
  private boolean hasData;
  private @Nullable String lastId;
  private long retryMillis = -1;

  /**
   * Creates a parser.
   *
   * @param events receives each event
   */
  SseParser(Consumer<Event> events) {
    this.events = events;
  }

  /**
   * Reads one line, without its line terminator.
   *
   * @param line the line
   */
  void line(String line) {
    if (line.isEmpty()) {
      dispatch();
      return;
    }
    if (line.startsWith(":")) {
      return;
    }
    int colon = line.indexOf(':');
    String field = colon < 0 ? line : line.substring(0, colon);
    String value = colon < 0 ? "" : line.substring(colon + 1);
    if (value.startsWith(" ")) {
      value = value.substring(1);
    }
    switch (field) {
      case "event" -> name = value;
      case "data" -> {
        if (hasData) {
          data.append('\n');
        }
        data.append(value);
        hasData = true;
      }
      case "id" -> {
        if (value.indexOf('\0') < 0) {
          lastId = value;
        }
      }
      case "retry" -> {
        try {
          retryMillis = Long.parseLong(value);
        } catch (NumberFormatException e) {
          // Ignored, as the specification says.
        }
      }
      default -> {
        // Unknown fields are ignored.
      }
    }
  }

  /**
   * Returns the last event id seen, which a reconnection sends as {@code Last-Event-ID}.
   *
   * @return the id, or {@code null}
   */
  @Nullable String lastId() {
    return lastId;
  }

  /**
   * Returns the reconnection delay the server asked for.
   *
   * @return milliseconds, or -1 if it asked for none
   */
  long retryMillis() {
    return retryMillis;
  }

  private void dispatch() {
    if (hasData) {
      events.accept(new Event(name.isEmpty() ? "message" : name, data.toString(), lastId));
    }
    name = "";
    data.setLength(0);
    hasData = false;
  }
}
