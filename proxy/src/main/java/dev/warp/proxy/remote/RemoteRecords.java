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

import dev.warp.proxy.console.ComponentMessage;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.time.MutableInstant;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.jspecify.annotations.Nullable;

/**
 * Turns the records of a remote proxy back into Log4j events, which the client's console renders
 * exactly as the proxy's own console would: their time, level, tag (from their logger), the
 * highlighting of their parameters (from their format), a command's reply in colour (from its
 * component). With several proxies, each line starts with its proxy's name.
 */
final class RemoteRecords {

  private RemoteRecords() {}

  /**
   * Returns a record's event.
   *
   * @param record the record, as the stream sends it
   * @param prefix the proxy's name to show, or {@code null}
   * @return the event
   * @throws IllegalArgumentException if the record is malformed
   */
  static LogEvent event(JsonObject record, @Nullable String prefix) {
    String lead = prefix == null ? "" : "[" + prefix + "] ";
    String text = string(record, "message", "");
    String exception = optional(record, "exception");
    Message message;
    JsonElement component = record.get("component");
    if (component != null && component.isJsonObject() && exception == null) {
      Component rendered = GsonComponentSerializer.gson().deserializeFromTree(component);
      message =
          new ComponentMessage(
              prefix == null
                  ? rendered
                  : Component.text(lead, NamedTextColor.DARK_GRAY).append(rendered));
    } else if (exception != null) {
      message = new SimpleMessage(lead + text + "\n" + exception);
    } else {
      message = parameterized(record, lead, text);
    }
    Log4jLogEvent.Builder event =
        Log4jLogEvent.newBuilder()
            .setLoggerName(string(record, "logger", ""))
            .setLevel(Level.toLevel(string(record, "level", "INFO"), Level.INFO))
            .setThreadName(string(record, "thread", ""))
            .setMessage(message)
            .setInstant(instant(optional(record, "timestamp")));
    String marker = optional(record, "marker");
    if (marker != null) {
      event.setMarker(MarkerManager.getMarker(marker));
    }
    return event.build();
  }

  /**
   * Returns the session a record's output belongs to.
   *
   * @param record the record
   * @return the session, or {@code null}
   */
  static @Nullable String session(JsonObject record) {
    JsonElement context = record.get("context");
    if (context == null || !context.isJsonObject()) {
      return null;
    }
    return optional(context.getAsJsonObject(), "console.session");
  }

  /**
   * A parameterised message when the record's format and parameters give back its text, which the
   * console then highlights as the proxy's would; its text otherwise.
   */
  private static Message parameterized(JsonObject record, String lead, String text) {
    String format = optional(record, "format");
    JsonElement values = record.get("parameters");
    if (format != null && values != null && values.isJsonArray()) {
      JsonArray array = values.getAsJsonArray();
      Object[] parameters = new Object[array.size()];
      for (int i = 0; i < parameters.length; i++) {
        JsonElement value = array.get(i);
        parameters[i] = value.isJsonNull() ? "null" : value.getAsString();
      }
      ParameterizedMessage message = new ParameterizedMessage(lead + format, parameters);
      if (message.getFormattedMessage().equals(lead + text)) {
        return message;
      }
    }
    return new SimpleMessage(lead + text);
  }

  private static MutableInstant instant(@Nullable String timestamp) {
    Instant time = Instant.now();
    if (timestamp != null) {
      try {
        time = Instant.parse(timestamp);
      } catch (DateTimeParseException e) {
        // The time of arrival, then.
      }
    }
    MutableInstant instant = new MutableInstant();
    instant.initFromEpochSecond(time.getEpochSecond(), time.getNano());
    return instant;
  }

  private static String string(JsonObject json, String name, String fallback) {
    JsonElement value = json.get(name);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
  }

  private static @Nullable String optional(JsonObject json, String name) {
    JsonElement value = json.get(name);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
  }
}
