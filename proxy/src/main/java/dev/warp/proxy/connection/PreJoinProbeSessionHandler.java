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

import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.proxy.antibot.Probe;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the anti-bot's pre-join probe (1.8 to 1.20.1) on a client connection: after Login Success,
 * in the play state, before the first Join Game, one keep-alive with a random id must be echoed.
 * Every client of those versions answers keep-alives while it waits for its world; a stateless
 * login script does not.
 *
 * <p>Once the probe passed, the player waits here for its first server, as it waited under its
 * login handler before the anti-bot: the proxy's keep-alives are answered, an answer about a pack a
 * plugin sent is routed, and the play packets the client has no reason to send are dropped. The
 * first Join Game replaces this handler ({@link RespawnSwitch}).
 */
final class PreJoinProbeSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(PreJoinProbeSessionHandler.class);

  private final MinecraftConnection connection;
  private final Probe probe;
  private final ProbeSessionHandler.Listener listener;
  private final LongSupplier nanoClock;
  private @Nullable ScheduledFuture<?> deadline;
  private boolean ended;

  /** The player the handler waits for a server with, once the probe passed. */
  private @Nullable ConnectedPlayer waiting;

  /**
   * Creates the handler; the probe starts when the handler is activated.
   *
   * @param connection the client connection, in the play state
   * @param probe the pre-join probe, with its id drawn
   * @param listener what follows
   * @param nanoClock the probe's clock
   */
  PreJoinProbeSessionHandler(
      MinecraftConnection connection,
      Probe probe,
      ProbeSessionHandler.Listener listener,
      LongSupplier nanoClock) {
    this.connection = connection;
    this.probe = probe;
    this.listener = listener;
    this.nanoClock = nanoClock;
  }

  /**
   * Keeps the handler in place after the pass, answering for {@code player} until its first server
   * replaces it. Called by the listener on {@link ProbeSessionHandler.Listener#passed}.
   *
   * @param player the player logged in
   */
  void waitFor(ConnectedPlayer player) {
    this.waiting = player;
  }

  @Override
  public void activated() {
    connection.writeAndFlush(new KeepAlive(probe.keepAliveId()));
    long delay = Math.max(0, probe.deadlineNanos() - nanoClock.getAsLong());
    deadline =
        connection
            .channel()
            .eventLoop()
            .schedule(this::deadlinePassed, delay, TimeUnit.NANOSECONDS);
  }

  @Override
  public void handle(Packet packet) {
    ConnectedPlayer player = waiting;
    if (player != null) {
      // Waiting for the first server: the proxy's own keep-alives, and packs plugins sent.
      if (packet instanceof KeepAlive keepAlive) {
        player.keepAliveAnswered(keepAlive.id());
      } else if (packet instanceof ResourcePackResponse response) {
        player.resourcePacks().clientAnswered(response);
      }
      return;
    }
    if (ended) {
      return;
    }
    if (packet instanceof KeepAlive keepAlive) {
      judge(probe.keepAlive(keepAlive.id()));
    } else {
      // Nothing else is expected before Join Game; what comes is counted, not judged.
      probe.unknownPacket();
    }
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    probe.unknownPacket();
    buf.release();
  }

  @Override
  public void deactivated() {
    cancelDeadline();
  }

  @Override
  public void disconnected() {
    cancelDeadline();
    ConnectedPlayer player = waiting;
    if (player != null) {
      player.disconnect();
      return;
    }
    if (!ended) {
      ended = true;
      listener.left();
    }
  }

  private void deadlinePassed() {
    deadline = null;
    if (!ended) {
      judge(probe.deadline());
    }
  }

  private void cancelDeadline() {
    ScheduledFuture<?> pending = deadline;
    if (pending != null) {
      pending.cancel(false);
      deadline = null;
    }
  }

  private void judge(Probe.Outcome outcome) {
    if (ended) {
      return;
    }
    switch (outcome) {
      case Probe.Outcome.Pending _ -> {
        // Waiting for the echo.
      }
      case Probe.Outcome.Passed passed -> {
        ended = true;
        cancelDeadline();
        listener.passed(passed, null);
      }
      case Probe.Outcome.Failed failed -> {
        ended = true;
        cancelDeadline();
        logger.debug(
            "Pre-join probe failed for {}: {}", connection.remoteAddress(), failed.detail());
        listener.failed(failed);
      }
    }
  }
}
