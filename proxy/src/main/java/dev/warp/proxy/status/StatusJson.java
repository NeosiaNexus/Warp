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
package dev.warp.proxy.status;

import dev.warp.api.server.Favicon;
import dev.warp.api.server.ServerPing;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Writes a server list entry as the JSON of a status response:
 *
 * <pre>{@code
 * {"version":{"name":"Warp 1.7.2-26.3","protocol":769},
 *  "players":{"max":500,"online":2,"sample":[{"name":"Steve","id":"..."}]},
 *  "description":{"text":"A Warp Proxy","color":"#38bdf8"},
 *  "favicon":"data:image/png;base64,..."}
 * }</pre>
 *
 * <p>The members a vanilla server leaves out when empty are left out: {@code players} when the
 * counts are hidden, {@code sample} when empty, {@code favicon} without an icon.
 *
 * <p>A client reads the whole answer as a string of at most {@value #MAX_LENGTH} characters, and
 * fails the ping beyond it (then falls back to the legacy ping, and lists the server as
 * incompatible: SpigotMC/BungeeCord#828). {@link #fit} makes an entry fit.
 */
final class StatusJson {

  /** The longest status response a client reads, in UTF-16 code units. */
  static final int MAX_LENGTH = 32_767;

  /** The longest version name kept when nothing else is left to make an entry fit. */
  private static final int LAST_RESORT_NAME_LENGTH = 64;

  /** Room for the JSON around the description and the icon. */
  private static final int ENVELOPE_CAPACITY = 256;

  /** Room for one sample entry, a 16-character name and a UUID. */
  private static final int SAMPLE_ENTRY_CAPACITY = 72;

  private StatusJson() {}

  /**
   * Writes an entry.
   *
   * @param ping the entry
   * @param descriptionJson its description, already written for the client's format
   * @return the JSON, whatever its length
   */
  static String write(ServerPing ping, String descriptionJson) {
    return writeParts(ping.version(), ping.players(), descriptionJson, ping.favicon());
  }

  /**
   * Writes an entry, leaving out what makes it longer than a client reads: the icon first, then the
   * sample, and as a last resort the description, with the version name shortened.
   *
   * @param ping the entry
   * @param descriptionJson its description, already written for the client's format
   * @return the JSON and what was left out
   */
  static Fitted fit(ServerPing ping, String descriptionJson) {
    String json = write(ping, descriptionJson);
    if (json.length() <= MAX_LENGTH) {
      return new Fitted(json, null);
    }
    json = writeParts(ping.version(), ping.players(), descriptionJson, null);
    if (json.length() <= MAX_LENGTH) {
      return new Fitted(json, "its icon");
    }
    ServerPing.Players players = ping.players();
    ServerPing.Players withoutSample =
        players == null ? null : new ServerPing.Players(players.online(), players.max(), List.of());
    json = writeParts(ping.version(), withoutSample, descriptionJson, null);
    if (json.length() <= MAX_LENGTH) {
      return new Fitted(json, "its icon and sample");
    }
    String name = ping.version().name();
    ServerPing.Version shortVersion =
        new ServerPing.Version(
            name.substring(0, Math.min(name.length(), LAST_RESORT_NAME_LENGTH)),
            ping.version().protocol());
    return new Fitted(
        writeParts(shortVersion, withoutSample, "\"\"", null),
        "its icon, sample and description, and most of its version name");
  }

  private static String writeParts(
      ServerPing.Version version,
      ServerPing.@Nullable Players players,
      String descriptionJson,
      @Nullable Favicon favicon) {
    int capacity = ENVELOPE_CAPACITY + version.name().length() + descriptionJson.length();
    if (players != null) {
      capacity += players.sample().size() * SAMPLE_ENTRY_CAPACITY;
    }
    if (favicon != null) {
      capacity += favicon.uri().length();
    }
    StringBuilder json = new StringBuilder(capacity);
    json.append("{\"version\":{\"name\":");
    appendString(json, version.name());
    json.append(",\"protocol\":").append(version.protocol()).append('}');
    if (players != null) {
      json.append(",\"players\":{\"max\":")
          .append(players.max())
          .append(",\"online\":")
          .append(players.online());
      List<ServerPing.SamplePlayer> sample = players.sample();
      if (!sample.isEmpty()) {
        json.append(",\"sample\":[");
        for (int i = 0; i < sample.size(); i++) {
          ServerPing.SamplePlayer player = sample.get(i);
          if (i > 0) {
            json.append(',');
          }
          json.append("{\"name\":");
          appendString(json, player.name());
          json.append(",\"id\":\"").append(player.id()).append("\"}");
        }
        json.append(']');
      }
      json.append('}');
    }
    json.append(",\"description\":").append(descriptionJson);
    if (favicon != null) {
      json.append(",\"favicon\":");
      appendString(json, favicon.uri());
    }
    return json.append('}').toString();
  }

  /** Appends a JSON string: quoted, with quotes, backslashes and control characters escaped. */
  static void appendString(StringBuilder json, String value) {
    json.append('"');
    // Runs of characters that need no escape (all of an icon's base64) are copied in bulk.
    int run = 0;
    for (int i = 0; i < value.length(); i++) {
      String escape = escape(value.charAt(i));
      if (escape != null) {
        json.append(value, run, i).append(escape);
        run = i + 1;
      }
    }
    json.append(value, run, value.length()).append('"');
  }

  /** The escape of a character in a JSON string, or {@code null} if it stands as it is. */
  private static @Nullable String escape(char c) {
    return switch (c) {
      case '"' -> "\\\"";
      case '\\' -> "\\\\";
      case '\n' -> "\\n";
      case '\r' -> "\\r";
      case '\t' -> "\\t";
      default -> c < 0x20 || c == 0x2028 || c == 0x2029 ? String.format("\\u%04x", (int) c) : null;
    };
  }

  /**
   * An entry written to fit.
   *
   * @param json the JSON, at most {@value #MAX_LENGTH} characters
   * @param dropped what was left out to make it fit, for the log, or {@code null} if nothing
   */
  record Fitted(String json, @Nullable String dropped) {}
}
