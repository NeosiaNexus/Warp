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

import static dev.warp.proxy.connection.SwitchHarness.CREATIVE;
import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static dev.warp.proxy.connection.SwitchHarness.handlerOf;
import static dev.warp.proxy.connection.SwitchHarness.opaqueFrame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.LegacyPlayerInfo;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.proxy.connection.SwitchHarness.Sent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Server switches before 1.20.2: the packets the client receives, per version, and how failures
 * keep the player where it is or send it down the fallback order.
 */
@DisplayName("Respawn server switch (before 1.20.2)")
class RespawnSwitchTest {

  private static final UUID ALICE = UUID.fromString("5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01");
  private static final UUID BOSS = UUID.fromString("0f3e1b7c-7b5d-4b55-8e0a-2c3d4e5f6a7b");

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  static Stream<ProtocolVersion> versions() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_7_6,
        ProtocolVersion.MINECRAFT_1_8,
        ProtocolVersion.MINECRAFT_1_9,
        ProtocolVersion.MINECRAFT_1_12_2,
        ProtocolVersion.MINECRAFT_1_15_2,
        ProtocolVersion.MINECRAFT_1_16_1,
        ProtocolVersion.MINECRAFT_1_16_4,
        ProtocolVersion.MINECRAFT_1_18_2,
        ProtocolVersion.MINECRAFT_1_19_2,
        ProtocolVersion.MINECRAFT_1_19_4,
        ProtocolVersion.MINECRAFT_1_20_1);
  }

  private SwitchHarness harness(ProtocolVersion version, String... fallbackOrder) {
    SwitchHarness harness = new SwitchHarness(version, List.of(fallbackOrder), ProtocolState.PLAY);
    harnesses.add(harness);
    return harness;
  }

  // ---------------------------------------------------------------------------
  // Strategy choice
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("strategy")
  class Strategy {

    @Test
    @DisplayName("should switch through the configuration phase from 1.20.2 and respawn before")
    void choice() {
      assertInstanceOf(
          RespawnSwitch.class, harness(ProtocolVersion.MINECRAFT_1_20_1).player.serverSwitch());
      assertInstanceOf(
          ReconfigurationSwitch.class,
          harness(ProtocolVersion.MINECRAFT_1_20_2).player.serverSwitch());
    }
  }

  // ---------------------------------------------------------------------------
  // Joining and switching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("first server")
  class FirstServer {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.RespawnSwitchTest#versions")
    @DisplayName("should forward the first Join Game as it is and start playing")
    void firstJoinGame(ProtocolVersion version) {
      SwitchHarness setup = harness(version);

      MinecraftConnection lobby = setup.spawnOn(LOBBY);

      List<Sent> sent = setup.sentToClient();
      assertEquals(List.of(JoinGame.class), types(sent));
      assertEquals(0, dimensionOf(sent.getFirst().as(JoinGame.class)));
      assertInstanceOf(ClientPlaySessionHandler.class, setup.client.sessionHandler());
      assertSame(lobby, setup.current());
      assertTrue(setup.player.isPlayingOn(lobby));
      assertEquals("lobby", setup.player.currentServerName());
    }

    @Test
    @DisplayName("should keep the backend's packets from the client until its Join Game")
    void nothingBeforeJoinGame() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.login(LOBBY);

      setup.receive(lobby, opaqueFrame(0x22));

      assertEquals(List.of(), setup.sentToClient());
      assertInstanceOf(BackendJoinSessionHandler.class, lobby.sessionHandler());
    }

    @Test
    @DisplayName("should drop the backend still logging in when the player leaves")
    void playerLeaves() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_16_4);
      MinecraftConnection lobby = setup.login(LOBBY);

      setup.player.disconnect();
      setup.runPendingTasks();

      assertFalse(lobby.channel().isOpen());
    }
  }

  @Nested
  @DisplayName("switch")
  class Switch {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.RespawnSwitchTest#versions")
    @DisplayName("should send the leftovers' removal, the new Join Game, then a Respawn")
    void packetSequence(ProtocolVersion version) {
      SwitchHarness setup = harness(version);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      leaveLeftoversOn(setup, lobby);
      setup.sentToClient();

      setup.switchTo(SURVIVAL);
      List<Sent> sent = setup.sentToClient();

      List<Class<? extends Packet>> expected = new ArrayList<>();
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)) {
        expected.add(PlayerInfoRemove.class);
      } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
        expected.add(PlayerInfo.class);
      } else {
        expected.add(LegacyPlayerInfo.class);
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
        expected.add(BossBar.class);
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
        expected.add(TabListHeaderFooter.class);
        if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_17)) {
          expected.add(ClearTitles.class); // the Title packet's hide action, then its reset
        }
        expected.add(ClearTitles.class);
      }
      expected.add(JoinGame.class);
      expected.add(Respawn.class);
      assertEquals(expected, types(sent));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.RespawnSwitchTest#versions")
    @DisplayName("should join another dimension first before 1.16, then respawn in the real one")
    void dimensions(ProtocolVersion version) {
      SwitchHarness setup = harness(version);
      setup.spawnOn(LOBBY);
      setup.sentToClient();

      setup.switchTo(SURVIVAL);
      List<Sent> sent = setup.sentToClient();

      JoinGame joinGame = sent.get(sent.size() - 2).as(JoinGame.class);
      Respawn respawn = sent.getLast().as(Respawn.class);
      assertEquals(7, joinGame.entityId(), "the new server's entity ID");
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_16)) {
        assertEquals(-1, dimensionOf(joinGame), "the nether, to force a world reload");
        assertEquals(0, respawn.spawn().dimension(), "back to the overworld");
      } else {
        assertEquals("minecraft:overworld", respawn.spawn().worldName());
        assertEquals(
            ((JoinGame.Decoded) joinGame.body()).spawn().worldName(), respawn.spawn().worldName());
      }
      assertEquals(0, respawn.dataKept());
    }

    @Test
    @DisplayName("should remove exactly the tab list entries and boss bars the server left")
    void leftovers() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      leaveLeftoversOn(setup, lobby);
      setup.receive(lobby, frame(setup.version, PlayerInfo.remove(List.of(UUID.randomUUID()))));
      setup.sentToClient();

      setup.switchTo(SURVIVAL);
      List<Sent> sent = setup.sentToClient();

      PlayerInfo removal = sent.get(0).as(PlayerInfo.class);
      assertEquals(PlayerInfo.REMOVE_PLAYER, removal.action());
      assertEquals(List.of(ALICE), removal.profileIds());
      assertEquals(BOSS, sent.get(1).as(BossBar.class).uuid());
      assertEquals(BossBar.REMOVE, sent.get(1).as(BossBar.class).action());
      assertFalse(sent.get(3).as(ClearTitles.class).reset(), "the title hidden");
      assertTrue(sent.get(4).as(ClearTitles.class).reset(), "then its times reset");
    }

    @Test
    @DisplayName("should remove exactly the names a 1.7 server listed, one packet each")
    void legacyLeftovers() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_7_6);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToClient();
      setup.receive(lobby, frame(setup.version, new LegacyPlayerInfo("Alice", true, (short) 5)));
      setup.receive(lobby, frame(setup.version, new LegacyPlayerInfo("Bob", true, (short) 3)));
      setup.receive(lobby, frame(setup.version, new LegacyPlayerInfo("Bob", false, (short) 0)));
      List<Sent> forwarded = setup.sentToClient();

      setup.switchTo(SURVIVAL);
      List<Sent> sent = setup.sentToClient();

      assertEquals(
          List.of(LegacyPlayerInfo.class, LegacyPlayerInfo.class, LegacyPlayerInfo.class),
          types(forwarded),
          "the server's own tab list packets reach the client");
      assertEquals(
          List.of(LegacyPlayerInfo.class, JoinGame.class, Respawn.class), types(sent), "sequence");
      assertEquals(LegacyPlayerInfo.remove("Alice"), sent.getFirst().as(LegacyPlayerInfo.class));
    }

    @Test
    @DisplayName("should move the player to the new backend and close the previous one")
    void backends() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_16_4);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);

      MinecraftConnection survival = setup.switchTo(SURVIVAL);

      assertSame(survival, setup.current());
      assertTrue(setup.player.isPlayingOn(survival));
      assertFalse(lobby.channel().isOpen());
      assertEquals("survival", setup.player.currentServerName());
      assertFalse(setup.player.isSwitching());
      assertEquals(7, setup.player.entityId());
    }

    @Test
    @DisplayName("should read the client again when the server it leaves had paused it")
    void clientResumed() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      // The lobby stops reading: its back-pressure pauses the client.
      lobby.channel().unsafe().outboundBuffer().setUserDefinedWritability(1, false);
      setup.runPendingTasks();
      assertFalse(setup.client.channel().config().isAutoRead());

      setup.switchTo(SURVIVAL);

      assertTrue(setup.client.channel().config().isAutoRead());
    }

    @Test
    @DisplayName("should keep the player on its server while the new one logs in")
    void stayDuringLogin() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_18_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToClient();

      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);
      setup.receive(lobby, opaqueFrame(0x22));
      setup.receive(survival, opaqueFrame(0x22));

      assertEquals(1, setup.sentToClient().size(), "only the lobby's packet");
      assertTrue(setup.player.isPlayingOn(lobby));
      assertSame(lobby, setup.current());
    }

    @Test
    @DisplayName("should drop what the previous backend still sends after the switch")
    void previousBackendSilenced() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_19_4);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.switchTo(SURVIVAL);
      setup.sentToClient();

      handlerOf(lobby).handleBlind(opaqueFrame(0x22));
      setup.runPendingTasks();

      assertEquals(List.of(), setup.sentToClient());
      assertFalse(setup.player.isSwitching(), "its closed connection is not a failure");
      assertTrue(setup.client.channel().isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // Failures
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("failures")
  class Failures {

    @Test
    @DisplayName("should keep the player on its server when the new one refuses it")
    void refusedSwitch() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);

      setup.player.scheduleBackendFailure(survival);
      setup.runPendingTasks();

      assertFalse(setup.player.isSwitching());
      assertSame(lobby, setup.current());
      assertTrue(setup.player.isPlayingOn(lobby));
      assertFalse(survival.channel().isOpen());
      assertTrue(setup.client.channel().isActive());
    }

    @Test
    @DisplayName("should keep the player on its server when the new one cannot be reached")
    void unreachableSwitch() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_8);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);

      setup.player.switchServer(SURVIVAL);

      assertFalse(setup.player.isSwitching());
      assertSame(lobby, setup.current());
      assertTrue(setup.client.channel().isActive());
    }

    @Test
    @DisplayName("should keep the player on its server when the switch times out")
    void timedOutSwitch() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_16_4);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);

      setup.player.serverSwitch().timedOut();

      assertFalse(setup.player.isSwitching());
      assertFalse(survival.channel().isOpen());
      assertSame(lobby, setup.current());
    }

    @Test
    @DisplayName("should go down the fallback order when the first server refuses the player")
    void refusedFirstServer() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_18_2, SURVIVAL.name());
      MinecraftConnection lobby = setup.login(LOBBY);

      setup.player.scheduleBackendFailure(lobby);
      setup.runPendingTasks();

      // survival is tried (it cannot be reached from an embedded channel), then nothing is left.
      assertDisconnectedWith(setup, "Could not connect to any available server.");
    }

    @Test
    @DisplayName("should go down the fallback order when the player's server goes down")
    void serverDown() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_20_1, SURVIVAL.name());
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToClient();

      lobby.close();
      setup.runPendingTasks();

      assertNull(setup.player.backendConnection());
      assertDisconnectedWith(setup, "Could not connect to any available server.");
    }

    @Test
    @DisplayName("should let a switch in progress take a player whose server goes down")
    void serverDownDuringSwitch() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_12_2, CREATIVE.name());
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);

      lobby.close();
      setup.runPendingTasks();
      setup.joinGame(survival);

      assertSame(survival, setup.current());
      assertEquals("survival", setup.player.currentServerName());
      assertTrue(setup.client.channel().isActive());
    }

    @Test
    @DisplayName("should go down the fallback order when a switch fails after the server went down")
    void switchFailsAfterServerDown() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_12_2, CREATIVE.name());
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);
      setup.sentToClient();

      lobby.close();
      setup.player.scheduleBackendFailure(survival);
      setup.runPendingTasks();

      // creative is tried next (unreachable here), then nothing is left.
      assertDisconnectedWith(setup, "Could not connect to any available server.");
    }

    private static void assertDisconnectedWith(SwitchHarness setup, String reason) {
      List<Sent> sent = setup.sentToClient();
      assertEquals(PlayDisconnect.class, sent.getLast().type());
      String json =
          new String(sent.getLast().as(PlayDisconnect.class).rawReason(), StandardCharsets.UTF_8);
      assertTrue(json.contains(reason), json);
      assertFalse(setup.client.channel().isOpen());
    }
  }

  // ---------------------------------------------------------------------------
  // Commands before 1.19
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("/server before 1.19")
  class ServerCommand {

    @Test
    @DisplayName("should switch on /server typed in chat and keep it from the backend")
    void intercepted() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_8);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToBackend(lobby);

      handlerOf(setup.client).handle(new LegacyChatMessage("/server survival"));

      // survival cannot be reached from an embedded channel: the switch was tried and given up.
      assertFalse(setup.player.isSwitching());
      assertEquals(0, setup.sentToBackend(lobby));
    }

    @Test
    @DisplayName("should forward chat and other commands to the backend")
    void forwarded() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToBackend(lobby);

      handlerOf(setup.client).handle(new LegacyChatMessage("hello"));
      handlerOf(setup.client).handle(new LegacyChatMessage("/servers"));
      handlerOf(setup.client).readComplete();

      assertEquals(2, setup.sentToBackend(lobby));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The server adds a player to the tab list and shows a boss bar. */
  private static void leaveLeftoversOn(SwitchHarness setup, MinecraftConnection backend) {
    ProtocolVersion version = setup.version;
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)) {
      setup.receive(backend, frame(version, addPlayerUpdate()));
    } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
      setup.receive(backend, frame(version, addPlayer(version)));
    } else {
      setup.receive(backend, frame(version, new LegacyPlayerInfo("Alice", true, (short) 5)));
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
      setup.receive(backend, frame(version, new BossBar(BOSS, BossBar.ADD, bossBarAdd())));
    }
  }

  private static PlayerInfo addPlayer(ProtocolVersion version) {
    return new PlayerInfo(
        PlayerInfo.ADD_PLAYER,
        List.of(ALICE),
        bytes(
            buf -> {
              VarInt.write(buf, 1);
              writeUuid(buf, ALICE);
              writeString(buf, "Alice");
              VarInt.write(buf, 0); // properties
              VarInt.write(buf, 0); // game mode
              VarInt.write(buf, 5); // latency
              buf.writeBoolean(false); // display name
              if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
                buf.writeBoolean(false); // public key
              }
            }));
  }

  private static PlayerInfoUpdate addPlayerUpdate() {
    return new PlayerInfoUpdate(
        PlayerInfoUpdate.ADD_PLAYER | PlayerInfoUpdate.UPDATE_LISTED,
        List.of(ALICE),
        bytes(
            buf -> {
              VarInt.write(buf, 1);
              writeUuid(buf, ALICE);
              writeString(buf, "Alice");
              VarInt.write(buf, 0); // properties
              buf.writeBoolean(true); // listed
            }));
  }

  /** The fields of a boss bar's ADD action: title, health, color, division, flags. */
  private static byte[] bossBarAdd() {
    return bytes(
        buf -> {
          writeString(buf, "{\"text\":\"Boss\"}");
          buf.writeFloat(1);
          VarInt.write(buf, 0);
          VarInt.write(buf, 0);
          buf.writeByte(0);
        });
  }

  private static byte[] bytes(Consumer<ByteBuf> writer) {
    ByteBuf buf = Unpooled.buffer();
    try {
      writer.accept(buf);
      byte[] bytes = new byte[buf.readableBytes()];
      buf.readBytes(bytes);
      return bytes;
    } finally {
      buf.release();
    }
  }

  private static void writeUuid(ByteBuf buf, UUID uuid) {
    buf.writeLong(uuid.getMostSignificantBits());
    buf.writeLong(uuid.getLeastSignificantBits());
  }

  private static void writeString(ByteBuf buf, String value) {
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    VarInt.write(buf, utf8.length);
    buf.writeBytes(utf8);
  }

  private static int dimensionOf(JoinGame joinGame) {
    return ((JoinGame.Decoded) joinGame.body()).spawn().dimension();
  }
}
