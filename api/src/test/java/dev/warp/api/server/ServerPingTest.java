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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServerPing")
class ServerPingTest {

  private static final ServerPing.SamplePlayer STEVE =
      new ServerPing.SamplePlayer("Steve", UUID.randomUUID());

  private final Favicon favicon = Favicon.fromPng(FaviconTest.png(64, 64));

  private final ServerPing ping =
      new ServerPing(
          new ServerPing.Version("Warp", 769),
          new ServerPing.Players(3, 500, List.of(STEVE)),
          Component.text("A Warp Proxy"),
          favicon);

  @Nested
  @DisplayName("with methods")
  class WithMethods {

    @Test
    @DisplayName("should change the description and keep the rest")
    void description() {
      ServerPing changed = ping.withDescription(Component.text("Maintenance"));

      assertEquals(Component.text("Maintenance"), changed.description());
      assertEquals(ping.version(), changed.version());
      assertEquals(ping.players(), changed.players());
      assertSame(ping.favicon(), changed.favicon());
    }

    @Test
    @DisplayName("should change the version and keep the rest")
    void version() {
      ServerPing changed = ping.withVersion(new ServerPing.Version("1.21.4", 769));

      assertEquals("1.21.4", changed.version().name());
      assertEquals(ping.description(), changed.description());
    }

    @Test
    @DisplayName("should hide the counts with null players")
    void hidePlayers() {
      ServerPing changed = ping.withPlayers(null);

      assertTrue(changed.playerInfo().isEmpty());
      assertEquals(ping.description(), changed.description());
    }

    @Test
    @DisplayName("should drop the icon with a null favicon")
    void dropIcon() {
      ServerPing changed = ping.withFavicon(null);

      assertTrue(changed.icon().isEmpty());
      assertNull(changed.favicon());
    }

    @Test
    @DisplayName("should change one count of the players and keep the others")
    void counts() {
      ServerPing.Players players = ping.playerInfo().orElseThrow();

      ServerPing.Players changed = players.withOnline(4).withMax(100);

      assertEquals(new ServerPing.Players(4, 100, List.of(STEVE)), changed);
      assertEquals(List.of(), players.withSample(List.of()).sample());
    }
  }

  @Test
  @DisplayName("should keep its sample unmodifiable, copied from what it was given")
  void sampleCopied() {
    List<ServerPing.SamplePlayer> sample = new ArrayList<>(List.of(STEVE));
    ServerPing.Players players = new ServerPing.Players(1, 1, sample);

    sample.clear();

    assertEquals(List.of(STEVE), players.sample());
    assertThrows(UnsupportedOperationException.class, () -> players.sample().add(STEVE));
  }

  @Test
  @DisplayName("should not print its icon's data")
  void toStringWithoutIcon() {
    String text = ping.toString();

    assertFalse(text.contains("base64"), text);
    assertTrue(text.contains("Favicon["), text);
  }

  @Test
  @DisplayName("should list anonymous players as vanilla does")
  void anonymous() {
    assertEquals("Anonymous Player", ServerPing.SamplePlayer.ANONYMOUS.name());
    assertEquals(new UUID(0, 0), ServerPing.SamplePlayer.ANONYMOUS.id());
  }
}
