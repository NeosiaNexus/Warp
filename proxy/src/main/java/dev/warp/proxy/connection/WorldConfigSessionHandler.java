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
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.protocol.packet.config.RegistryData;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.proxy.world.WorldLayouts;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The configuration phase of the virtual world (1.20.2+): what a 1.20.2+ client must receive before
 * a Join Game of the proxy's own. From 1.20.5 the client is first asked to acknowledge its built-in
 * {@code minecraft:core} pack, so that every registry entry is sent as an id without data (section
 * 9.8.2 of the research); then the registries, then Finish Configuration. Once the client
 * acknowledges, it is in the play state and the {@link Listener} takes over.
 *
 * <p>A client that acknowledges none of the offered packs (a client of another release behind a
 * version-translating mod) cannot be given the world this way: the listener is told the world is
 * unsupported for it, and the client stays in the configuration phase for its first server.
 */
final class WorldConfigSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(WorldConfigSessionHandler.class);

  /** What follows the configuration; called once, on the connection's event loop. */
  interface Listener {

    /** The client acknowledged the configuration: it reads play packets now. */
    void play();

    /** The client cannot be given the world (no known pack in common); it stays configuring. */
    void unsupported(String detail);

    /** The client did not answer in time, or answered what no client does. */
    void failed(VerificationFailure reason, String detail);

    /** The client left. */
    void left();
  }

  private final MinecraftConnection connection;
  private final ProtocolVersion version;
  private final WorldLayouts.Layout layout;
  private final @Nullable ConnectedPlayer player;
  private final Duration stageTimeout;
  private final LongSupplier nanoClock;
  private final Listener listener;
  private @Nullable ScheduledFuture<?> deadline;
  private boolean awaitingPacks;
  private boolean ended;

  WorldConfigSessionHandler(
      MinecraftConnection connection,
      ProtocolVersion version,
      WorldLayouts.Layout layout,
      @Nullable ConnectedPlayer player,
      Duration stageTimeout,
      LongSupplier nanoClock,
      Listener listener) {
    this.connection = connection;
    this.version = version;
    this.layout = layout;
    this.player = player;
    this.stageTimeout = stageTimeout;
    this.nanoClock = nanoClock;
    this.listener = listener;
  }

  @Override
  public void activated() {
    connection.decoder().useRegistry(StateRegistry.world(ProtocolState.CONFIGURATION));
    connection.setAutoRead(true);
    KnownPacks offer = layout.knownPacks();
    if (offer != null) {
      awaitingPacks = true;
      connection.writeAndFlush(offer);
    } else {
      finishConfiguration();
    }
    scheduleDeadline();
  }

  private void finishConfiguration() {
    for (RegistryData registry : layout.registries()) {
      connection.write(registry);
    }
    connection.writeTerminal(new FinishConfiguration());
  }

  @Override
  public void handle(Packet packet) {
    if (ended) {
      return;
    }
    switch (packet) {
      case KnownPacks answer -> handleKnownPacks(answer);
      case AcknowledgeFinishConfiguration _ -> {
        if (awaitingPacks) {
          end(
              () ->
                  listener.failed(
                      VerificationFailure.ILLEGAL_PACKET,
                      "configuration acknowledged before it was finished"));
          return;
        }
        if (player != null) {
          player.clientStateChanged(ProtocolState.PLAY);
        } else {
          connection.setState(ProtocolState.PLAY);
        }
        end(listener::play);
      }
      case KeepAlive keepAlive -> {
        if (player != null) {
          player.keepAliveAnswered(keepAlive.id());
        }
      }
      case ClientInformation information -> {
        if (player != null) {
          player.cacheClientSettings(information);
        }
      }
      case CookieResponse response -> {
        if (player != null) {
          player.cookies().answered(response);
        }
      }
      case ResourcePackResponse response -> {
        if (player != null) {
          player.resourcePacks().clientAnswered(response);
        }
      }
      default -> {
        // Other decoded packets of the state answer servers, of which there is none.
      }
    }
  }

  private void handleKnownPacks(KnownPacks answer) {
    if (!awaitingPacks) {
      end(() -> listener.failed(VerificationFailure.ILLEGAL_PACKET, "known packs sent unasked"));
      return;
    }
    awaitingPacks = false;
    KnownPacks offer = layout.knownPacks();
    boolean core = offer != null && answer.packs().stream().anyMatch(offer.packs()::contains);
    if (!core) {
      logger.debug(
          "{} acknowledged no core pack of {} ({}): the virtual world is skipped",
          connection.remoteAddress(),
          version.name(),
          answer.packs());
      if (player != null) {
        // The first server asks the same question in this very phase: answered for the client.
        player.worldKnownPacksAnswered(answer);
      }
      end(() -> listener.unsupported("no known pack in common: " + answer.packs()));
      return;
    }
    cancelDeadline();
    finishConfiguration();
    scheduleDeadline();
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    buf.release(); // the client's brand and mod channels: it sends them again on its server
  }

  @Override
  public void deactivated() {
    cancelDeadline();
    if (!connection.decoder().usesOwnRegistry()) {
      return;
    }
    connection.decoder().useRegistry(null);
  }

  @Override
  public void disconnected() {
    cancelDeadline();
    if (!ended) {
      ended = true;
      listener.left();
    }
  }

  private void scheduleDeadline() {
    long delay = stageTimeout.toNanos() + (player == null ? 0 : player.clientLatencyNanos() * 4);
    deadline =
        connection
            .channel()
            .eventLoop()
            .schedule(
                () -> {
                  deadline = null;
                  if (!ended) {
                    end(
                        () ->
                            listener.failed(
                                VerificationFailure.TIMED_OUT,
                                awaitingPacks
                                    ? "no answer to the known packs"
                                    : "configuration not acknowledged"));
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

  private void end(Runnable next) {
    ended = true;
    cancelDeadline();
    next.run();
  }

  /** The clock, for the handler that follows. */
  LongSupplier nanoClock() {
    return nanoClock;
  }
}
