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
package dev.warp.proxy.management.console;

import dev.warp.proxy.console.Ansi;
import dev.warp.proxy.console.ComponentMessage;
import dev.warp.proxy.console.SourceTags;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.jspecify.annotations.Nullable;

/**
 * A log line as the remote console streams it: what the JSON log format holds (time, level, logger,
 * thread, message, exception, context), plus its source tag, its sequence number, and what the
 * client needs to render it as the local console does (the message's format and parameters, which
 * it highlights, or the component a command sent, in colour). Secrets are masked when the record is
 * made; its JSON is written once, the first time a client needs it.
 */
final class ConsoleRecord {

  /** The context keys that name the remote session a command's output belongs to. */
  static final String USER_KEY = "console.user";

  static final String SESSION_KEY = "console.session";

  private final long id;
  private final Instant instant;
  private final Level level;
  private final String logger;
  private final String thread;
  private final String message;
  private final @Nullable String format;
  private final List<String> parameters;
  private final @Nullable Component component;
  private final @Nullable String exception;
  private final Map<String, String> context;
  private final @Nullable String marker;
  private volatile byte @Nullable [] frame;

  private ConsoleRecord(
      long id,
      Instant instant,
      Level level,
      String logger,
      String thread,
      String message,
      @Nullable String format,
      List<String> parameters,
      @Nullable Component component,
      @Nullable String exception,
      Map<String, String> context,
      @Nullable String marker) {
    this.id = id;
    this.instant = instant;
    this.level = level;
    this.logger = logger;
    this.thread = thread;
    this.message = message;
    this.format = format;
    this.parameters = parameters;
    this.component = component;
    this.exception = exception;
    this.context = context;
    this.marker = marker;
  }

  /**
   * Copies a Log4j event, which Log4j may reuse once this returns, masking its secrets.
   *
   * @param id the record's sequence number
   * @param event the event
   * @param redactor masks secrets
   * @return the record
   */
  // Message#getFormat is deprecated, but it is the only way to a parameterised message's format.
  @SuppressWarnings("deprecation")
  static ConsoleRecord of(long id, LogEvent event, Redactor redactor) {
    var time = event.getInstant();
    Message source = event.getMessage();
    String formatted = source == null ? "" : source.getFormattedMessage();
    String plain = Ansi.clean(formatted == null ? "" : formatted);
    String text = redactor.clean(plain);
    // A component that held a secret goes as its masked text only.
    Component component =
        source instanceof ComponentMessage message && text.equals(plain)
            ? message.component()
            : null;
    String format = null;
    List<String> parameters = List.of();
    if (component == null && source != null && text.equals(formatted)) {
      String raw = source.getFormat();
      Object[] values = source.getParameters();
      if (raw != null && raw.contains("{}") && values != null && values.length > 0) {
        List<String> copied = new ArrayList<>(values.length);
        boolean clean = true;
        for (Object value : values) {
          if (value instanceof Throwable) {
            continue;
          }
          String string = String.valueOf(value);
          String masked = redactor.clean(string);
          clean &= masked.equals(string);
          copied.add(masked);
        }
        if (clean) {
          format = raw;
          parameters = List.copyOf(copied);
        }
      }
    }
    Throwable thrown = event.getThrown();
    Map<String, String> context = new LinkedHashMap<>();
    event
        .getContextData()
        .forEach((key, value) -> context.put(key, redactor.clean(String.valueOf(value))));
    Marker marker = event.getMarker();
    String loggerName = event.getLoggerName();
    String threadName = event.getThreadName();
    return new ConsoleRecord(
        id,
        Instant.ofEpochSecond(time.getEpochSecond(), time.getNanoOfSecond()),
        event.getLevel(),
        loggerName == null ? "" : loggerName,
        threadName == null ? "" : threadName,
        text,
        format,
        parameters,
        component,
        thrown == null ? null : redactor.clean(Ansi.clean(stackTrace(thrown))),
        Map.copyOf(context),
        marker == null ? null : marker.getName());
  }

  /**
   * Returns the sequence number.
   *
   * @return the number, starting at 1
   */
  long id() {
    return id;
  }

  /**
   * Returns the level.
   *
   * @return the level
   */
  Level level() {
    return level;
  }

  /**
   * Returns the source tag, as the console shows it.
   *
   * @return such as {@code proxy} or {@code plugin:primemc}
   */
  String tag() {
    return SourceTags.of(logger);
  }

  /**
   * Returns the message, without escape sequences or secrets.
   *
   * @return the message
   */
  String message() {
    return message;
  }

  /**
   * Returns a context value.
   *
   * @param key the key
   * @return its value, or {@code null}
   */
  @Nullable String context(String key) {
    return context.get(key);
  }

  /**
   * Returns the record as an SSE frame: its {@code id}, the {@code log} event and its JSON.
   *
   * @param instance the identifier of this run of the proxy, which prefixes the event's id
   * @return the frame's bytes, in UTF-8
   */
  byte[] frame(String instance) {
    byte[] bytes = frame;
    if (bytes == null) {
      bytes =
          ("id: " + instance + "-" + id + "\nevent: log\ndata: " + json() + "\n\n")
              .getBytes(StandardCharsets.UTF_8);
      frame = bytes;
    }
    return bytes;
  }

  /**
   * Returns the record as JSON, one line.
   *
   * @return the JSON
   */
  JsonObject json() {
    JsonObject json = new JsonObject();
    json.addProperty("id", id);
    json.addProperty("timestamp", instant.toString());
    json.addProperty("level", level.name());
    json.addProperty("logger", logger);
    json.addProperty("tag", tag());
    json.addProperty("thread", thread);
    json.addProperty("message", message);
    if (format != null) {
      json.addProperty("format", format);
      JsonArray values = new JsonArray(parameters.size());
      parameters.forEach(values::add);
      json.add("parameters", values);
    }
    if (component != null) {
      json.add("component", GsonComponentSerializer.gson().serializeToTree(component));
    }
    if (exception != null) {
      json.addProperty("exception", exception);
    }
    if (marker != null) {
      json.addProperty("marker", marker);
    }
    if (!context.isEmpty()) {
      JsonObject values = new JsonObject();
      context.forEach(values::addProperty);
      json.add("context", values);
    }
    return json;
  }

  private static String stackTrace(Throwable thrown) {
    StringWriter text = new StringWriter();
    try (PrintWriter out = new PrintWriter(text)) {
      thrown.printStackTrace(out);
    }
    return text.toString().stripTrailing();
  }
}
