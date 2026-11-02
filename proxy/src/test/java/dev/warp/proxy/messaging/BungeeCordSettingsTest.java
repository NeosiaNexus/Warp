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
package dev.warp.proxy.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BungeeCord settings")
class BungeeCordSettingsTest {

  @Nested
  @DisplayName("rule lists")
  class RuleLists {

    @Test
    @DisplayName("should read names in any case, * for all, and !Name to take one away")
    void parse() {
      Set<BungeeCordSubchannel> rule =
          BungeeCordSettings.parseRule(List.of("*", "!kickplayer", " !KickPlayerRaw "));

      assertEquals(BungeeCordSubchannel.values().length - 2, rule.size());
      assertEquals(
          Set.of(BungeeCordSubchannel.CONNECT, BungeeCordSubchannel.GET_SERVERS),
          BungeeCordSettings.parseRule(List.of("connect", "GetServers")));
      assertTrue(BungeeCordSettings.parseRule(List.of()).isEmpty());
    }

    @Test
    @DisplayName("should refuse a name that is no sub-channel")
    void unknown() {
      assertThrows(
          IllegalArgumentException.class, () -> BungeeCordSettings.parseRule(List.of("Teleport")));
    }
  }

  @Nested
  @DisplayName("matching")
  class Matching {

    private final BungeeCordSettings settings =
        new BungeeCordSettings(
            true,
            Map.of(
                "Lobby",
                EnumSet.of(BungeeCordSubchannel.CONNECT),
                "game-*",
                EnumSet.of(BungeeCordSubchannel.FORWARD),
                "game-1*",
                EnumSet.of(BungeeCordSubchannel.UUID),
                "*",
                EnumSet.of(BungeeCordSubchannel.GET_SERVER)),
            Duration.ofSeconds(30));

    @Test
    @DisplayName("should prefer the exact name, then the longest prefix, then *")
    void specificity() {
      assertEquals(Set.of(BungeeCordSubchannel.CONNECT), settings.allowed("lobby"));
      assertEquals(Set.of(BungeeCordSubchannel.UUID), settings.allowed("game-12"));
      assertEquals(Set.of(BungeeCordSubchannel.FORWARD), settings.allowed("game-2"));
      assertEquals(Set.of(BungeeCordSubchannel.GET_SERVER), settings.allowed("survival"));
    }

    @Test
    @DisplayName("should describe the rules, most specific first")
    void describe() {
      assertEquals(
          "lobby: Connect; game-1*: UUID; game-*: Forward; *: GetServer; forward-hold 30000ms",
          settings.describe());
      assertEquals("disabled", BungeeCordSettings.DISABLED.describe());
    }

    @Test
    @DisplayName("should refuse a negative hold and a misplaced *")
    void invalid() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new BungeeCordSettings(true, Map.of(), Duration.ofSeconds(-1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new BungeeCordSettings(
                  true, Map.of("a*b", EnumSet.noneOf(BungeeCordSubchannel.class)), Duration.ZERO));
    }
  }
}
