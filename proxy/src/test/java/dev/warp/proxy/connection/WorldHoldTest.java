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

import static dev.warp.proxy.connection.SwitchHarness.frame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.protocol.packet.play.AcknowledgeConfiguration;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.network.CidrRanges;
import dev.warp.proxy.server.BackendServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A player whose every server fails is held in the virtual world instead of disconnected, and
 * leaves it for a server through the regular switch: the configuration phase from 1.20.2, a respawn
 * before.
 */
@DisplayName("the hold in the virtual world")
class WorldHoldTest {

  @TempDir private Path directory;
  private @Nullable SwitchHarness setup;
  private @Nullable AntiBotService antiBot;
  private @Nullable MinecraftConnection backend;

  @AfterEach
  void tearDown() {
    if (setup != null) {
      setup.close();
    }
    if (antiBot != null) {
      antiBot.close();
    }
  }

  private SwitchHarness join(ProtocolVersion version, ProtocolState clientState)
      throws IOException {
    SwitchHarness harness = new SwitchHarness(version, List.of("lobby"), clientState);
    AntiBotConfig d = AntiBotConfig.DISABLED;
    AntiBotService bots =
        new AntiBotService(
            new AntiBotConfig(
                false,
                CidrRanges.none(),
                d.verified(),
                d.attack(),
                d.blocklist(),
                d.checkTimeout(),
                d.verification(),
                new AntiBotConfig.World(false, true, Duration.ofSeconds(5), Duration.ofMinutes(5))),
            false,
            ClientLimits.unlimited(),
            TestLoginContexts.EVENTS,
            LoggingConfig.DEFAULTS,
            directory,
            _ -> null,
            System::currentTimeMillis);
    when(harness.player.loginContext().antiBot()).thenReturn(bots);
    this.setup = harness;
    this.antiBot = bots;
    // Every connection fails at once (no event loop can register the channel): no server left.
    harness.player.join();
    harness.runPendingTasks();
    return harness;
  }

  private AntiBotService bots() {
    return Objects.requireNonNull(antiBot);
  }

  /** The lobby comes back: connections complete with a fresh backend in its login state. */
  private void lobbyBack(SwitchHarness harness) {
    harness.player.setConnector(
        target -> {
          MinecraftConnection fresh = harness.backend(ProtocolState.LOGIN);
          this.backend = fresh;
          return CompletableFuture.completedFuture(new BackendConnection(fresh, target));
        });
  }

  private BackendServer lobby(SwitchHarness harness) {
    return Objects.requireNonNull(
        harness.player.loginContext().serverRegistry().getServer("lobby"));
  }

  @Test
  @DisplayName("should hold a 1.20.2+ player in the world and release it through configuration")
  void reconfiguration() throws Exception {
    ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
    SwitchHarness harness = join(version, ProtocolState.CONFIGURATION);
    // The world's configuration: the known packs answered, the registries, Finish acknowledged.
    harness.fromClient(knownPacks(version));
    harness.fromClient(
        frame(
            version,
            ProtocolState.CONFIGURATION,
            PacketDirection.SERVERBOUND,
            new AcknowledgeFinishConfiguration()));
    List<Class<? extends Packet>> sent = types(harness.sentToClient());

    assertTrue(harness.player.isInWorld(), "held in the world");
    assertTrue(sent.contains(JoinGame.class), sent.toString());
    assertEquals(1, bots().world().status().holds());
    assertEquals(1, bots().world().status().holding());
    assertTrue(harness.player.clientConnection().decoder().usesOwnRegistry());

    lobbyBack(harness);
    bots().world().release(harness.player.api(), lobby(harness));
    harness.runPendingTasks();
    MinecraftConnection lobby = Objects.requireNonNull(backend, "connected to the lobby");
    harness.player.serverSwitch().loggedIn(lobby);
    assertTrue(types(harness.sentToClient()).contains(StartConfiguration.class));
    harness.fromClient(
        frame(
            version,
            ProtocolState.PLAY,
            PacketDirection.SERVERBOUND,
            new AcknowledgeConfiguration()));

    assertFalse(harness.player.isInWorld(), "left the world for the lobby's configuration");
    assertFalse(harness.player.clientConnection().decoder().usesOwnRegistry());
    assertEquals(0, bots().world().status().holding());
    assertNotNull(harness.player.backendConnection());
  }

  @Test
  @DisplayName("should hold a pre-1.20.2 player in the world and release it through a respawn")
  void respawn() throws Exception {
    ProtocolVersion version = ProtocolVersion.MINECRAFT_1_16_4;
    SwitchHarness harness = join(version, ProtocolState.PLAY);
    List<Class<? extends Packet>> sent = types(harness.sentToClient());

    assertTrue(harness.player.isInWorld(), "held in the world");
    assertTrue(sent.contains(JoinGame.class), sent.toString());

    lobbyBack(harness);
    bots().world().release(harness.player.api(), lobby(harness));
    harness.runPendingTasks();
    MinecraftConnection lobby = Objects.requireNonNull(backend, "connected to the lobby");
    harness.player.serverSwitch().loggedIn(lobby);
    harness.joinGame(lobby);
    List<Class<? extends Packet>> rejoin = types(harness.sentToClient());

    assertFalse(harness.player.isInWorld());
    assertEquals("lobby", harness.player.currentServerName());
    assertTrue(
        rejoin.contains(JoinGame.class) && rejoin.contains(Respawn.class), rejoin.toString());
    assertEquals(1, bots().world().status().holdsReleased());
    assertFalse(harness.player.clientConnection().decoder().usesOwnRegistry());
  }

  /** The client's Select Known Packs answer: read by the world's configuration registry only. */
  private static ByteBuf knownPacks(ProtocolVersion version) {
    KnownPacks answer =
        new KnownPacks(List.of(new KnownPacks.Pack("minecraft", "core", version.name())));
    int id = StateRegistry.world(ProtocolState.CONFIGURATION).packetId(version, KnownPacks.class);
    ByteBuf body = Unpooled.buffer();
    try {
      KnownPacks.CODEC.encode(answer, body, version);
      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, VarInt.size(id) + body.readableBytes());
      VarInt.write(frame, id);
      return frame.writeBytes(body);
    } finally {
      body.release();
    }
  }
}
