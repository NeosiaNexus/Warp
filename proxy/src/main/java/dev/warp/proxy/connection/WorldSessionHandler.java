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

import dev.warp.api.antibot.VerificationFailure;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.play.AcknowledgeConfiguration;
import dev.warp.protocol.packet.play.BlockUpdate;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.ClientMovement;
import dev.warp.protocol.packet.play.EmptyChunk;
import dev.warp.protocol.packet.play.GameEvent;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.PlayClientSettings;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.PlayPing;
import dev.warp.protocol.packet.play.PlayPong;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.SetDefaultSpawnPosition;
import dev.warp.protocol.packet.play.SpawnInfo;
import dev.warp.protocol.packet.play.SyncPlayerPosition;
import dev.warp.protocol.packet.play.TabCompleteRequest;
import dev.warp.protocol.packet.play.TeleportConfirm;
import dev.warp.protocol.packet.play.TickEnd;
import dev.warp.protocol.packet.play.WindowConfirmation;
import dev.warp.proxy.world.ProxyWorld;
import dev.warp.proxy.world.WorldCheck;
import dev.warp.proxy.world.WorldLayouts;

import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's session handler while it stands in the virtual world: a void with one 3x3 stone
 * platform, in the chunk at (0, 0). On activation it gives the client the world (a Join Game, or a
 * respawn into it when the client already plays before 1.20.2), teleports it onto or over the
 * platform, and, when a {@link WorldCheck} is given, runs the check and reports to its {@link
 * Listener}. With or without a check, the handler then keeps the player in the world: keep-alives
 * answered, the proxy's commands available, movement dropped, until a server switch replaces it.
 *
 * <p>While active, the connection's decoder reads the world's own registry, which adds the movement
 * packets; it reads the regular one again as soon as this handler is replaced. Nothing of the world
 * remains in the pipeline for a player on a server.
 */
final class WorldSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(WorldSessionHandler.class);

  /** Keep-alives of a connection without a player (verification before authentication). */
  private static final long OWN_KEEP_ALIVE_SECONDS = 10;

  /** How the client enters the world. */
  enum Entry {
    /** The client has had no Join Game in this session (or just left a configuration phase). */
    FRESH,
    /** A client before 1.20.2 that plays on a server: a respawn into the world. */
    REJOIN
  }

  /** The outcome of the world check; called once, on the connection's event loop. */
  interface Listener {

    /**
     * The client fell and landed as a vanilla client does.
     *
     * @param flags what was odd about it, if anything (see {@link WorldCheck.Outcome.Passed})
     */
    void passed(Set<WorldCheck.Flag> flags);

    /**
     * The client did what no client does, or its fall did not follow the gravity twice.
     *
     * @param reason why
     * @param detail what was seen
     */
    void failed(VerificationFailure reason, String detail);

    /** The client left before the check ended: not a failure (rule 2 of the research). */
    void left();
  }

  private final MinecraftConnection connection;
  private final ProtocolVersion version;
  private final ProxyWorld world;
  private final @Nullable ConnectedPlayer player;
  private final @Nullable WorldCheck check;
  private final @Nullable Listener listener;
  private final LongSupplier nanoClock;
  private final Entry entry;
  private final int entityId = 1 + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE - 1);
  private @Nullable ScheduledFuture<?> deadline;
  private @Nullable ScheduledFuture<?> ownKeepAlive;
  private boolean checking;
  private boolean ended;

  /**
   * Creates the handler.
   *
   * @param connection the client connection, in the play state
   * @param version the client's version
   * @param world the world
   * @param player the player, or {@code null} for a client verified before authentication
   * @param check the check to run, or {@code null} to hold the client only
   * @param listener the check's listener, required with a check
   * @param nanoClock the clock
   * @param entry how the client enters
   */
  WorldSessionHandler(
      MinecraftConnection connection,
      ProtocolVersion version,
      ProxyWorld world,
      @Nullable ConnectedPlayer player,
      @Nullable WorldCheck check,
      @Nullable Listener listener,
      LongSupplier nanoClock,
      Entry entry) {
    this.connection = connection;
    this.version = version;
    this.world = world;
    this.player = player;
    this.check = check;
    this.listener = listener;
    this.nanoClock = nanoClock;
    this.entry = entry;
    this.checking = check != null;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void activated() {
    connection.decoder().useRegistry(StateRegistry.world(ProtocolState.PLAY));
    connection.setAutoRead(true);
    WorldLayouts.Layout layout = world.layouts().of(version);
    JoinGame joinGame = layout.joinGame(entityId);
    if (player != null) {
      player.setEntityId(entityId);
    }
    if (entry == Entry.REJOIN && player != null) {
      player.closeBundle();
      for (PlayPacket leftover : player.leftovers().clear(version)) {
        connection.write(leftover);
      }
      writeRejoin(joinGame);
    } else {
      connection.write(joinGame);
    }
    WorldCheck running = check;
    int blockX;
    int blockY;
    int blockZ;
    double y;
    if (running != null) {
      blockX = running.blockX();
      blockY = running.platformY();
      blockZ = running.blockZ();
      y = running.startY();
    } else {
      blockX = 8;
      blockY = 64;
      blockZ = 8;
      y = blockY + 1;
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)) {
      connection.write(new SetDefaultSpawnPosition(blockX, blockY + 1, blockZ));
    }
    connection.write(
        new SyncPlayerPosition(
            running != null ? running.teleportId() : 1, blockX + 0.5, y, blockZ + 0.5, 0, 0));
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
      connection.write(new GameEvent(GameEvent.START_WAITING_FOR_CHUNKS, 0));
    }
    connection.write(new EmptyChunk(0, 0));
    writePlatform(blockX, blockY, blockZ);
    if (running != null) {
      writeEcho(running);
    }
    connection.flush();
    if (player != null) {
      player.worldEntered();
      player.joinedGame(); // the proxy's messages kept for a world follow it
    } else {
      ownKeepAlive =
          connection
              .channel()
              .eventLoop()
              .scheduleAtFixedRate(
                  () -> connection.writeAndFlush(new KeepAlive(nanoClock.getAsLong() & 0xFFFFFFL)),
                  OWN_KEEP_ALIVE_SECONDS,
                  OWN_KEEP_ALIVE_SECONDS,
                  TimeUnit.SECONDS);
    }
    if (running != null) {
      scheduleDeadline(running);
    }
  }

  /** Moves a client that plays before 1.20.2 into the world, as a server switch does. */
  private void writeRejoin(JoinGame joinGame) {
    if (!(joinGame.body() instanceof JoinGame.Decoded body)) {
      throw new IllegalStateException("A world Join Game is decoded before 1.20.2");
    }
    SpawnInfo spawn = body.spawn();
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_16)) {
      // These clients reload the world only on a Respawn into another dimension.
      SpawnInfo elsewhere = spawn.withDimension(spawn.dimension() == 0 ? -1 : 0);
      connection.write(
          new JoinGame(joinGame.entityId(), joinGame.hardcore(), body.withSpawn(elsewhere)));
    } else {
      connection.write(joinGame);
    }
    connection.write(Respawn.of(spawn));
  }

  private void writePlatform(int blockX, int blockY, int blockZ) {
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        connection.write(new BlockUpdate(blockX + dx, blockY, blockZ + dz, BlockUpdate.STONE));
      }
    }
  }

  private void writeEcho(WorldCheck running) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)) {
      connection.write(new PlayPing(running.pingId()));
    } else {
      connection.write(new WindowConfirmation(0, running.transactionAction(), false));
    }
  }

  @Override
  public void handle(Packet packet) {
    switch (packet) {
      case KeepAlive keepAlive -> {
        if (player != null) {
          player.keepAliveAnswered(keepAlive.id());
        }
      }
      case TeleportConfirm confirm -> {
        if (checking && check != null) {
          judge(check.teleportConfirmed(confirm.teleportId(), nanoClock.getAsLong()));
        }
      }
      case ClientMovement movement -> {
        if (checking && check != null) {
          judge(check.moved(movement, nanoClock.getAsLong()));
        }
      }
      case PlayPong pong -> {
        if (checking && check != null) {
          judge(check.pong(pong.id()));
        }
      }
      case WindowConfirmation confirmation -> {
        if (checking && check != null) {
          judge(check.transaction(confirmation.action()));
        }
      }
      case TickEnd _ -> {
        if (check != null) {
          check.tickEnded();
        }
      }
      case AcknowledgeConfiguration _ -> handleAcknowledgeConfiguration();
      case PlayClientSettings settings -> {
        if (player != null) {
          player.cacheClientSettings(
              new ClientInformation(
                  settings.locale(),
                  settings.viewDistance(),
                  settings.chatMode(),
                  settings.chatColors(),
                  settings.displayedSkinParts(),
                  settings.mainHand(),
                  settings.enableTextFiltering(),
                  settings.allowServerListings(),
                  settings.particleStatus()));
        }
      }
      case ChatCommand command -> {
        if (player != null && !checking) {
          player.handleCommand(command.command(), command.lastSeenOffset(), () -> {});
        }
      }
      case LegacyChatMessage chat -> {
        if (player != null && !checking && chat.message().startsWith("/")) {
          player.handleCommand(chat.message().substring(1), 0, () -> {});
        }
      }
      case TabCompleteRequest request -> {
        if (player != null && !checking) {
          player.handleSuggestions(request, () -> {});
        }
      }
      case ResourcePackResponse response -> {
        if (player != null) {
          player.resourcePacks().clientAnswered(response);
        }
      }
      case CookieResponse response -> {
        if (player != null) {
          player.cookies().answered(response);
        }
      }
      default -> {
        if (check != null) {
          check.unknownPacket();
        }
      }
    }
  }

  private void handleAcknowledgeConfiguration() {
    if (player == null) {
      return;
    }
    var target = player.pendingSwitchTarget();
    if (target != null
        && player.serverSwitch() instanceof ReconfigurationSwitch reconfiguration
        && reconfiguration.awaitingAcknowledgement()) {
      reconfiguration.acknowledged(target);
    }
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    // Counted, never a verdict (rule 4): a modded client sends its own messages.
    if (check != null) {
      check.unknownPacket();
    }
    buf.release();
  }

  @Override
  public void deactivated() {
    cancelDeadline();
    cancelOwnKeepAlive();
    if (connection.decoder().usesOwnRegistry()) {
      connection.decoder().useRegistry(null);
    }
    if (player != null) {
      player.worldLeft();
    }
  }

  @Override
  public void disconnected() {
    cancelDeadline();
    cancelOwnKeepAlive();
    if (player != null) {
      player.worldLeft();
      player.disconnect();
      return;
    }
    if (!ended && listener != null) {
      ended = true;
      listener.left();
    }
  }

  // ---------------------------------------------------------------------------
  // The check
  // ---------------------------------------------------------------------------

  private void scheduleDeadline(WorldCheck running) {
    cancelDeadline();
    long delay = Math.max(0, running.deadlineNanos() - nanoClock.getAsLong());
    deadline =
        connection
            .channel()
            .eventLoop()
            .schedule(
                () -> {
                  deadline = null;
                  if (checking) {
                    judge(running.deadline(nanoClock.getAsLong()));
                  }
                },
                delay,
                TimeUnit.NANOSECONDS);
  }

  private void cancelDeadline() {
    ScheduledFuture<?> pending = deadline;
    if (pending != null) {
      pending.cancel(false);
      deadline = null;
    }
  }

  private void cancelOwnKeepAlive() {
    ScheduledFuture<?> pending = ownKeepAlive;
    if (pending != null) {
      pending.cancel(false);
      ownKeepAlive = null;
    }
  }

  private void judge(WorldCheck.Outcome outcome) {
    WorldCheck running = check;
    Listener told = listener;
    if (!checking || running == null || told == null) {
      return;
    }
    switch (outcome) {
      case WorldCheck.Outcome.Pending _ -> {
        // The stage may have moved: the deadline follows.
        ScheduledFuture<?> pending = deadline;
        if (pending == null || running.deadlineNanos() - nanoClock.getAsLong() > 0) {
          scheduleDeadline(running);
        }
      }
      case WorldCheck.Outcome.Passed passed -> {
        checking = false;
        ended = true;
        cancelDeadline();
        logger.trace("World check passed for {}: {}", connection.remoteAddress(), passed);
        told.passed(passed.flags());
      }
      case WorldCheck.Outcome.Mismatch mismatch -> {
        if (!running.retried()) {
          logger.debug(
              "World check mismatch for {}, retrying from a new height: {}",
              connection.remoteAddress(),
              mismatch.detail());
          world.checkRetried();
          running.retry(nanoClock.getAsLong());
          connection.write(
              new SyncPlayerPosition(
                  running.teleportId(),
                  running.startX(),
                  running.startY(),
                  running.startZ(),
                  0,
                  0));
          writePlatform(running.blockX(), running.platformY(), running.blockZ());
          connection.flush();
          scheduleDeadline(running);
          return;
        }
        checking = false;
        ended = true;
        cancelDeadline();
        logger.debug(
            "World check failed for {} after its retry: {} ({} unknown packets ignored)",
            connection.remoteAddress(),
            mismatch.detail(),
            running.unknownPackets());
        told.failed(VerificationFailure.PHYSICS, mismatch.detail());
      }
      case WorldCheck.Outcome.Failed failed -> {
        checking = false;
        ended = true;
        cancelDeadline();
        logger.debug(
            "World check failed for {}: {} ({} unknown packets ignored)",
            connection.remoteAddress(),
            failed.detail(),
            running.unknownPackets());
        told.failed(failed.reason(), failed.detail());
      }
    }
  }
}
