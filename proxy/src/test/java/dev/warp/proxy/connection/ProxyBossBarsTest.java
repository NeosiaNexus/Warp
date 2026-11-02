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
package dev.warp.proxy.connection;

import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.StartConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Boss bars plugins show")
class ProxyBossBarsTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion LEGACY = ProtocolVersion.MINECRAFT_1_12_2;

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  private static net.kyori.adventure.bossbar.BossBar bar() {
    return net.kyori.adventure.bossbar.BossBar.bossBar(
        Component.text("Event"),
        0.5f,
        net.kyori.adventure.bossbar.BossBar.Color.RED,
        net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);
  }

  private static List<BossBar> bossBars(List<SwitchHarness.Sent> sent) {
    return sent.stream()
        .filter(packet -> packet.type() == BossBar.class)
        .map(packet -> packet.as(BossBar.class))
        .toList();
  }

  /** A player of {@code version} playing on the lobby, in a game. */
  private SwitchHarness inGame(ProtocolVersion version) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    setup.player.joinedGame();
    setup.sentToClient();
    return setup;
  }

  @Nested
  @DisplayName("in a game")
  class InGame {

    @Test
    @DisplayName("should show a bar with its name, progress, color, overlay and flags")
    void show() {
      SwitchHarness setup = inGame(MODERN);
      net.kyori.adventure.bossbar.BossBar bar = bar();
      bar.addFlag(net.kyori.adventure.bossbar.BossBar.Flag.DARKEN_SCREEN);

      setup.player.api().showBossBar(bar);

      BossBar.Add add =
          assertInstanceOf(BossBar.Add.class, bossBars(setup.sentToClient()).getFirst().action());
      assertEquals(0.5f, add.progress());
      assertEquals(2, add.color());
      assertEquals(2, add.overlay());
      assertEquals(BossBar.DARKEN_SCREEN, add.flags());
    }

    @Test
    @DisplayName("should update the client live as the plugin changes the bar")
    void liveUpdates() {
      SwitchHarness setup = inGame(MODERN);
      net.kyori.adventure.bossbar.BossBar bar = bar();
      setup.player.api().showBossBar(bar);
      UUID uuid = bossBars(setup.sentToClient()).getFirst().uuid();

      bar.progress(0.25f);
      bar.name(Component.text("Renamed"));
      bar.color(net.kyori.adventure.bossbar.BossBar.Color.BLUE);
      bar.flags(Set.of(net.kyori.adventure.bossbar.BossBar.Flag.CREATE_WORLD_FOG));

      List<BossBar> updates = bossBars(setup.sentToClient());
      assertEquals(
          List.of(
              new BossBar.UpdateProgress(0.25f).id(),
              BossBar.UPDATE_NAME,
              BossBar.UPDATE_STYLE,
              BossBar.UPDATE_FLAGS),
          updates.stream().map(update -> update.action().id()).toList());
      assertTrue(updates.stream().allMatch(update -> update.uuid().equals(uuid)));
      assertEquals(new BossBar.UpdateProgress(0.25f), updates.getFirst().action());
      assertEquals(new BossBar.UpdateStyle(1, 2), updates.get(2).action());
      assertEquals(new BossBar.UpdateFlags(BossBar.CREATE_WORLD_FOG), updates.get(3).action());
    }

    @Test
    @DisplayName("should hide a bar, and stop following it")
    void hide() {
      SwitchHarness setup = inGame(MODERN);
      net.kyori.adventure.bossbar.BossBar bar = bar();
      setup.player.api().showBossBar(bar);
      UUID uuid = bossBars(setup.sentToClient()).getFirst().uuid();

      setup.player.api().hideBossBar(bar);
      bar.progress(0.1f);

      assertEquals(List.of(BossBar.remove(uuid)), bossBars(setup.sentToClient()));
      assertNull(setup.player.bossBars().uuidOf(bar));
    }

    @Test
    @DisplayName("should show a bar once however many times it is shown")
    void showOnce() {
      SwitchHarness setup = inGame(MODERN);
      net.kyori.adventure.bossbar.BossBar bar = bar();

      setup.player.api().showBossBar(bar);
      setup.player.api().showBossBar(bar);
      bar.progress(1);

      assertEquals(2, bossBars(setup.sentToClient()).size()); // the addition, one update
    }

    @Test
    @DisplayName("should forget every bar once the player is gone")
    void disconnect() {
      SwitchHarness setup = inGame(MODERN);
      net.kyori.adventure.bossbar.BossBar bar = bar();
      setup.player.api().showBossBar(bar);

      setup.player.disconnect();
      setup.runPendingTasks();

      assertNull(setup.player.bossBars().uuidOf(bar));
    }
  }

  @Nested
  @DisplayName("across the configuration phase (1.20.2+)")
  class Reconfiguration {

    @Test
    @DisplayName("should write nothing while the client cleared its bars, then show them again")
    void showAgainAfterConfiguration() {
      SwitchHarness setup = inGame(MODERN);
      net.kyori.adventure.bossbar.BossBar bar = bar();
      setup.player.api().showBossBar(bar);
      UUID uuid = bossBars(setup.sentToClient()).getFirst().uuid();

      setup.player.startConfiguration();
      bar.progress(0.9f);
      setup.player.api().hideBossBar(bar);
      setup.player.api().showBossBar(bar);
      List<SwitchHarness.Sent> duringConfiguration = setup.sentToClient();
      setup.client.setState(ProtocolState.PLAY); // the next server's configuration ended
      setup.player.joinedGame();

      assertEquals(List.of(StartConfiguration.class), types(duringConfiguration));
      List<BossBar> shownAgain = bossBars(setup.sentToClient());
      assertEquals(1, shownAgain.size());
      BossBar.Add add = assertInstanceOf(BossBar.Add.class, shownAgain.getFirst().action());
      assertEquals(0.9f, add.progress());
      assertNotNull(uuid);
    }

    @Test
    @DisplayName("should wait for the first Join Game before showing bars")
    void beforeFirstJoin() {
      SwitchHarness setup = new SwitchHarness(MODERN, List.of(), ProtocolState.CONFIGURATION);
      harnesses.add(setup);
      net.kyori.adventure.bossbar.BossBar bar = bar();

      setup.player.api().showBossBar(bar);
      bar.progress(0.75f);
      List<SwitchHarness.Sent> beforeJoin = setup.sentToClient();
      setup.client.setState(ProtocolState.PLAY);
      setup.player.joinedGame();

      assertEquals(List.of(), beforeJoin);
      BossBar.Add add =
          assertInstanceOf(BossBar.Add.class, bossBars(setup.sentToClient()).getFirst().action());
      assertEquals(0.75f, add.progress());
    }
  }

  @Nested
  @DisplayName("before 1.20.2")
  class BeforeConfiguration {

    @Test
    @DisplayName("should keep a bar across a switch, and remove only the server's own")
    void keptAcrossRespawnSwitch() {
      SwitchHarness setup = new SwitchHarness(LEGACY, List.of(), ProtocolState.PLAY);
      harnesses.add(setup);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToClient();
      net.kyori.adventure.bossbar.BossBar bar = bar();
      setup.player.api().showBossBar(bar);
      UUID proxyBar = bossBars(setup.sentToClient()).getFirst().uuid();
      UUID serverBar = UUID.randomUUID();
      setup.receive(
          lobby,
          frame(LEGACY, BossBar.add(serverBar, Component.text("Server"), 1, 0, 0, 0, LEGACY)));
      setup.sentToClient();

      setup.switchTo(SURVIVAL);

      List<BossBar> sent = bossBars(setup.sentToClient());
      assertEquals(List.of(BossBar.remove(serverBar)), sent, "the proxy's bar is not shown again");
      bar.progress(0.2f);
      BossBar update = bossBars(setup.sentToClient()).getFirst();
      assertEquals(proxyBar, update.uuid());
    }

    @Test
    @DisplayName("should send nothing to a client older than 1.9, which has no boss bar packet")
    void noBossBarBefore19() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_8);
      net.kyori.adventure.bossbar.BossBar bar = bar();

      setup.player.api().showBossBar(bar);
      bar.progress(0.1f);
      setup.player.api().hideBossBar(bar);

      assertEquals(List.of(), setup.sentToClient());
      assertNull(setup.player.bossBars().uuidOf(bar));
    }
  }

  @Test
  @DisplayName("should map every Adventure flag to its bit")
  void flags() {
    assertEquals(
        BossBar.DARKEN_SCREEN | BossBar.PLAY_BOSS_MUSIC | BossBar.CREATE_WORLD_FOG,
        ProxyBossBars.flags(Set.of(net.kyori.adventure.bossbar.BossBar.Flag.values())));
    assertEquals(0, ProxyBossBars.flags(Set.of()));
  }
}
