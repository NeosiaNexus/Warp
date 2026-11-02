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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.server.Favicon;
import dev.warp.api.server.ServerPing;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.legacy.LegacyPing;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.player.PlayerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ServerList")
class ServerListTest {

  private static final int LATEST = ProtocolVersion.latest().protocol();

  private final PlayerRegistry players = new PlayerRegistry();

  // ---------------------------------------------------------------------------
  // Description per version
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("description, in each client's text format")
  class Description {

    private final Component motd = MiniMessage.miniMessage().deserialize("<#38bdf8>A Warp Proxy");

    @ParameterizedTest(name = "protocol {0}")
    @ValueSource(ints = {4, 5, 47, 578}) // 1.7.2, 1.7.6, 1.8, 1.15.2
    @DisplayName("should downsample colors to the named ones before 1.16")
    void namedColorsBefore116(int protocol) {
      JsonObject description = description(motd, protocol);

      assertEquals("aqua", description.get("color").getAsString());
    }

    @ParameterizedTest(name = "protocol {0}")
    @ValueSource(ints = {735, 754, 769, 775, 777}) // 1.16, 1.16.4, 1.21.4, 26.1, 26.3
    @DisplayName("should keep RGB colors from 1.16")
    void rgbFrom116(int protocol) {
      JsonObject description = description(motd, protocol);

      assertEquals("#38bdf8", description.get("color").getAsString().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("should send shadow colors from 1.21.4 only")
    void shadowColor() {
      Component shadowed = MiniMessage.miniMessage().deserialize("<shadow:#ff0000>Warp");

      JsonObject before = description(shadowed, ProtocolVersion.MINECRAFT_1_21_2.protocol());
      JsonObject from = description(shadowed, ProtocolVersion.MINECRAFT_1_21_4.protocol());

      assertFalse(before.toString().contains("shadow_color"), before.toString());
      assertTrue(from.toString().contains("shadow_color"), from.toString());
    }

    @Test
    @DisplayName("should read the oldest format for a protocol below every known one")
    void unknownOld() {
      assertEquals("aqua", description(motd, 2).get("color").getAsString());
      assertEquals("aqua", description(motd, -1).get("color").getAsString());
    }

    @Test
    @DisplayName("should read the newest format for a protocol above every known one")
    void unknownNew() {
      String color = description(motd, 0x40000100).get("color").getAsString();

      assertEquals("#38bdf8", color.toLowerCase(Locale.ROOT));
    }

    private JsonObject description(Component description, int protocol) {
      JsonElement json = JsonParser.parseString(ServerList.descriptionJson(description, protocol));
      if (json.isJsonPrimitive()) {
        JsonObject text = new JsonObject();
        text.addProperty("text", json.getAsString());
        return text;
      }
      return json.getAsJsonObject();
    }
  }

  // ---------------------------------------------------------------------------
  // Answer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("answer")
  class Answer {

    @Test
    @DisplayName("should advertise a supported client's own protocol, and the latest to others")
    void advertisedProtocol() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);

      assertEquals(47, version(list.json(47)).get("protocol").getAsInt());
      assertEquals(LATEST, version(list.json(99999)).get("protocol").getAsInt());
      assertEquals(
          StatusSettings.defaultVersionName(), version(list.json(47)).get("name").getAsString());
    }

    @Test
    @DisplayName("should show the players online and the configured limit")
    void counts() {
      ServerList list = new ServerList(settings(100, true, 12, null), players);
      register(player("Steve", true));
      register(player("Alex", true));

      JsonObject counts = status(list.json(LATEST)).getAsJsonObject("players");

      assertEquals(2, counts.get("online").getAsInt());
      assertEquals(100, counts.get("max").getAsInt());
    }

    @Test
    @DisplayName("should show one more than the players online without a limit")
    void noLimit() {
      ServerList list = new ServerList(settings(StatusSettings.NO_LIMIT, true, 12, null), players);
      register(player("Steve", true));

      JsonObject counts = status(list.json(LATEST)).getAsJsonObject("players");

      assertEquals(1, counts.get("online").getAsInt());
      assertEquals(2, counts.get("max").getAsInt());
    }

    @Test
    @DisplayName("should leave the counts out when hidden, so that clients show ???")
    void hiddenCounts() {
      ServerList list = new ServerList(settings(500, false, 12, null), players);
      register(player("Steve", true));

      JsonObject status = status(list.json(LATEST));

      assertFalse(status.has("players"));
      assertTrue(list.proposal(LATEST).playerInfo().isEmpty());
    }

    @Test
    @DisplayName("should send the icon")
    void favicon() {
      Favicon icon = new Favicon(Favicon.URI_PREFIX + "AAAA");
      ServerList list = new ServerList(settings(500, true, 12, icon), players);

      assertEquals(icon.uri(), status(list.json(LATEST)).get("favicon").getAsString());
    }

    @Test
    @DisplayName("should reuse the answer while the players do not change, and rebuild it after")
    void cache() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      String first = list.json(LATEST);

      String again = list.json(LATEST);
      register(player("Steve", true));
      String afterJoin = list.json(LATEST);

      assertSame(first, again);
      assertNotSame(first, afterJoin);
      assertEquals(1, status(afterJoin).getAsJsonObject("players").get("online").getAsInt());
    }

    @Test
    @DisplayName("should answer an entry a plugin built like its own")
    void pluginEntry() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      ServerPing proposal = list.proposal(LATEST);

      assertEquals(list.json(LATEST), list.json(proposal, LATEST));
    }
  }

  // ---------------------------------------------------------------------------
  // Sample
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("sample")
  class Sample {

    @Test
    @DisplayName("should list players by name, and those who turned listings off anonymously")
    void anonymous() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      ConnectedPlayer steve = register(player("Steve", true));
      register(player("Hidden", false));

      List<ServerPing.SamplePlayer> sample =
          list.proposal(LATEST).playerInfo().orElseThrow().sample();

      assertEquals(2, sample.size());
      assertTrue(sample.contains(new ServerPing.SamplePlayer("Steve", steve.uuid())));
      assertTrue(sample.contains(ServerPing.SamplePlayer.ANONYMOUS));
    }

    @Test
    @DisplayName("should list no more players than the sample size")
    void bounded() {
      ServerList list = new ServerList(settings(500, true, 5, null), players);
      for (int i = 0; i < 20; i++) {
        register(player("Player" + i, true));
      }

      JsonArray sample =
          status(list.json(LATEST)).getAsJsonObject("players").getAsJsonArray("sample");

      assertEquals(5, sample.size());
    }

    @Test
    @DisplayName("should list nobody with a sample size of 0")
    void none() {
      ServerList list = new ServerList(settings(500, true, 0, null), players);
      register(player("Steve", true));

      JsonObject counts = status(list.json(LATEST)).getAsJsonObject("players");

      assertFalse(counts.has("sample"));
      assertEquals(1, counts.get("online").getAsInt());
    }

    @Test
    @DisplayName("should list a player who just joined a server smaller than the sample at once")
    void smallServerUpToDate() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      register(player("Steve", true));
      list.json(LATEST);

      register(player("Alex", true));
      JsonArray sample =
          status(list.json(LATEST)).getAsJsonObject("players").getAsJsonArray("sample");

      assertEquals(2, sample.size());
    }
  }

  // ---------------------------------------------------------------------------
  // Size limit
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("size limit of 32767 characters")
  class SizeLimit {

    @Test
    @DisplayName("should answer a plugin entry too long for clients without its icon")
    void dropsIcon() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      ServerPing entry =
          list.proposal(LATEST).withFavicon(new Favicon(Favicon.URI_PREFIX + "A".repeat(40_000)));

      String json = list.json(entry, LATEST);

      assertTrue(json.length() <= StatusJson.MAX_LENGTH);
      assertFalse(status(json).has("favicon"));
    }

    @Test
    @DisplayName("should then leave out its sample")
    void dropsSample() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      List<ServerPing.SamplePlayer> sample = new ArrayList<>();
      for (int i = 0; i < 1_000; i++) {
        sample.add(new ServerPing.SamplePlayer("Player" + i, new UUID(0, i)));
      }
      ServerPing entry = list.proposal(LATEST).withPlayers(new ServerPing.Players(1, 2, sample));

      String json = list.json(entry, LATEST);

      assertTrue(json.length() <= StatusJson.MAX_LENGTH);
      assertFalse(status(json).getAsJsonObject("players").has("sample"));
      assertEquals(1, status(json).getAsJsonObject("players").get("online").getAsInt());
    }

    @Test
    @DisplayName("should accept the default settings at startup")
    void defaultsFit() {
      assertTrue(ServerList.checkFits(StatusSettings.defaults()) < StatusJson.MAX_LENGTH);
    }

    @Test
    @DisplayName("should refuse at startup an icon too big for the answer, saying how big")
    void iconTooBig() {
      Favicon icon = new Favicon(Favicon.URI_PREFIX + "A".repeat(32_000));
      StatusSettings settings = settings(500, true, 12, icon);

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> ServerList.checkFits(settings));

      assertTrue(
          String.valueOf(error.getMessage()).contains("favicon takes " + icon.uri().length()),
          error.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Legacy
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("legacy answer")
  class Legacy {

    @Test
    @DisplayName("should advertise protocol 127 with the counts and the colored description")
    void proposal() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      register(player("Steve", true));

      String reason =
          list.legacyAnswer(list.legacyProposal(), LegacyPing.Format.MINECRAFT_1_4).reason();

      assertEquals(
          "§1\u0000127\u0000"
              + StatusSettings.defaultVersionName()
              + "\u0000§bA Warp Proxy\u00001\u0000500",
          reason);
    }

    @Test
    @DisplayName("should show 0 and 0 when the counts are hidden")
    void hidden() {
      ServerList list = new ServerList(StatusSettings.defaults(), players);
      ServerPing entry =
          list.legacyProposal().withPlayers(null).withDescription(Component.text("Hi"));

      String reason = list.legacyAnswer(entry, LegacyPing.Format.MINECRAFT_1_3).reason();

      assertEquals("Hi§0§0", reason);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static StatusSettings settings(
      int maxPlayers, boolean showCount, int sampleSize, @Nullable Favicon favicon) {
    StatusSettings defaults = StatusSettings.defaults();
    return new StatusSettings(
        defaults.motd(), favicon, maxPlayers, showCount, sampleSize, defaults.versionName());
  }

  private ConnectedPlayer register(ConnectedPlayer player) {
    players.register(player, false);
    return player;
  }

  private static ConnectedPlayer player(String name, boolean listed) {
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.uuid()).thenReturn(UUID.randomUUID());
    when(player.username()).thenReturn(name);
    when(player.allowsServerListing()).thenReturn(listed);
    return player;
  }

  private static JsonObject status(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }

  private static JsonObject version(String json) {
    return status(json).getAsJsonObject("version");
  }
}
