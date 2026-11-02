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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.warp.api.server.ServerPing;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StatusJson")
class StatusJsonTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "plain",
        "quote \" and backslash \\",
        "lines\nand\ttabs\r",
        "control \u0001\u001f",
        "separators " + (char) 0x2028 + (char) 0x2029,
        "§aColored é 😀"
      })
  @DisplayName("should write any string so that JSON reads it back unchanged")
  void escapes(String name) {
    StringBuilder json = new StringBuilder();

    StatusJson.appendString(json, name);

    assertEquals(name, JsonParser.parseString(json.toString()).getAsString());
  }

  @Test
  @DisplayName("should write every member of an entry, the sample with names and UUIDs")
  void members() {
    UUID id = UUID.randomUUID();
    ServerPing ping =
        new ServerPing(
            new ServerPing.Version("Warp \"1.7.2\"", 769),
            new ServerPing.Players(3, 500, List.of(new ServerPing.SamplePlayer("Steve", id))),
            Component.empty(),
            null);

    JsonObject json =
        JsonParser.parseString(StatusJson.write(ping, "{\"text\":\"Hi\"}")).getAsJsonObject();

    assertEquals("Warp \"1.7.2\"", json.getAsJsonObject("version").get("name").getAsString());
    assertEquals(769, json.getAsJsonObject("version").get("protocol").getAsInt());
    JsonObject players = json.getAsJsonObject("players");
    assertEquals(500, players.get("max").getAsInt());
    assertEquals(3, players.get("online").getAsInt());
    JsonObject steve = players.getAsJsonArray("sample").get(0).getAsJsonObject();
    assertEquals("Steve", steve.get("name").getAsString());
    assertEquals(id.toString(), steve.get("id").getAsString());
    assertEquals("Hi", json.getAsJsonObject("description").get("text").getAsString());
    assertFalse(json.has("favicon"));
  }

  @Test
  @DisplayName("should leave out an empty sample, as vanilla does")
  void emptySample() {
    ServerPing ping =
        new ServerPing(
            new ServerPing.Version("Warp", 769),
            new ServerPing.Players(0, 500, List.of()),
            Component.empty(),
            null);

    JsonObject json = JsonParser.parseString(StatusJson.write(ping, "\"\"")).getAsJsonObject();

    assertFalse(json.getAsJsonObject("players").has("sample"));
  }

  @Test
  @DisplayName("should say nothing was left out of an entry that fits")
  void fits() {
    ServerPing ping =
        new ServerPing(new ServerPing.Version("Warp", 769), null, Component.empty(), null);

    StatusJson.Fitted fitted = StatusJson.fit(ping, "\"\"");

    assertNull(fitted.dropped());
  }
}
