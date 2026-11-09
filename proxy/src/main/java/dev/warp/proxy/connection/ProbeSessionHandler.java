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
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigPing;
import dev.warp.protocol.packet.config.ConfigPluginMessage;
import dev.warp.protocol.packet.config.ConfigPong;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.proxy.antibot.Probe;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the anti-bot's configuration probe (1.20.2+) on a client connection: the handler of the
 * client's first configuration phase, before any backend exists. It sends what the {@link Probe}
 * asks, feeds it what the client answers, and hands the outcome to its {@link Listener}, which
 * decides what follows (the player's first server, a transfer back, a disconnect). It exists only
 * while the probe runs: whatever replaces it, the client gets the pipeline it would have had
 * without the anti-bot (section 9.12 of the research).
 *
 * <p>The client's settings are kept for its first server, as the switch handlers keep them, and so
 * is everything else it sends that no probe reads (its brand, a mod's channel registrations): held,
 * in order, within the bounds of {@link SwitchWaitSessionHandler}, and handed to the listener with
 * the pass, for the handler that waits for the first server. A client that floods the proxy
 * meanwhile is no longer read, and times out.
 */
final class ProbeSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(ProbeSessionHandler.class);

  /** What follows the probe; called once, on the connection's event loop. */
  interface Listener {

    /**
     * The client answered as a real client does.
     *
     * @param outcome the pass, with its flags and the measured round trip
     * @param settings the client's settings, if it sent them (it did unless the probe is pre-join)
     * @param held the frames the client sent for its first server meanwhile, in order, which the
     *     listener now owns: forwarded or released by it
     */
    void passed(
        Probe.Outcome.Passed outcome, @Nullable ClientInformation settings, List<ByteBuf> held);

    /**
     * The client did what no real client does, or answered nothing.
     *
     * @param outcome why
     */
    void failed(Probe.Outcome.Failed outcome);

    /** The client left before the probe ended: not a failure (rule 2 of the research). */
    void left();
  }

  private final MinecraftConnection connection;
  private final ClientCookies cookies;
  private final Probe probe;
  private final Listener listener;
  private final LongSupplier nanoClock;
  private @Nullable ClientInformation settings;
  private @Nullable ScheduledFuture<?> deadline;

  /** The probe's deadline {@link #deadline} was scheduled for, to notice it moving closer. */
  private long deadlineAtNanos;

  private boolean ended;

  /** Frames the client sent for its first server, which no probe reads. */
  private final Queue<ByteBuf> held = new ArrayDeque<>();

  /** Bytes of {@link #held}. */
  private long heldBytes;

  /**
   * Whether the next frame is the one of a watched packet just answered to the probe (a pong),
   * which the decoder passes on after the packet: for no server, since none asked.
   */
  private boolean dropNextFrame;

  /**
   * Creates the handler; the probe starts when the handler is activated.
   *
   * @param connection the client connection, in the configuration state
   * @param cookies the client's cookie traffic, for the probe's cookie round trip
   * @param probe the probe, with its ids drawn
   * @param listener what follows
   * @param nanoClock the probe's clock
   */
  ProbeSessionHandler(
      MinecraftConnection connection,
      ClientCookies cookies,
      Probe probe,
      Listener listener,
      LongSupplier nanoClock) {
    this.connection = connection;
    this.cookies = cookies;
    this.probe = probe;
    this.listener = listener;
    this.nanoClock = nanoClock;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void activated() {
    // Everything at once: the client answers in this order, and the last answer closes the probe.
    connection.write(new ConfigPing(probe.pingId()));
    connection.write(new KeepAlive(probe.keepAliveId()));
    byte[] cookie = probe.cookiePayload();
    if (cookie != null) {
      connection.flush();
      cookies.store(Probe.COOKIE_KEY, cookie);
      Duration wait = Duration.ofSeconds(Probe.MAX_SECONDS);
      var _ =
          cookies
              .request(Probe.COOKIE_KEY, wait)
              .whenCompleteAsync(
                  (payload, failure) -> {
                    if (failure == null && payload != null) {
                      judge(probe.cookie(payload));
                    }
                    // A time-out is the probe's deadline's business.
                  },
                  connection.channel().eventLoop());
    } else {
      connection.flush();
    }
    scheduleDeadline();
  }

  @Override
  public void handle(Packet packet) {
    if (ended) {
      return;
    }
    switch (packet) {
      case ConfigPong pong -> {
        dropNextFrame = true; // watched: its frame follows, and answers the probe's ping only
        judge(probe.pong(pong.id()));
      }
      case KeepAlive keepAlive -> judge(probe.keepAlive(keepAlive.id()));
      case ClientInformation information -> {
        settings = information;
        judge(probe.settings(information));
      }
      case ConfigPluginMessage message -> {
        // The watch reports the brand message only.
        if (ConfigPluginMessage.BRAND_CHANNEL.equals(message.channel())) {
          judge(probe.brand(message.brand()));
        }
      }
      case CookieResponse response -> {
        if (!cookies.answered(response)) {
          judge(probe.illegalPacket("a cookie " + response.key() + " sent unasked"));
        }
      }
      case AcknowledgeFinishConfiguration _ ->
          judge(probe.illegalPacket("an acknowledgement of a configuration never finished"));
      case ResourcePackResponse _ -> judge(probe.illegalPacket("an answer to no resource pack"));
      default -> {
        // Other decoded packets of the state are answers to servers, of which there is none.
        probe.unknownPacket();
      }
    }
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    if (dropNextFrame) {
      dropNextFrame = false;
      buf.release();
      return;
    }
    // Counted, never a silent close (rule 4): modded clients send their own messages. Held for the
    // first server, as a client waiting for it has its frames held: nothing of a client that passes
    // is lost, its brand first of all.
    probe.unknownPacket();
    held.add(buf);
    heldBytes += buf.readableBytes();
    if (held.size() >= SwitchWaitSessionHandler.MAX_HELD_FRAMES
        || heldBytes >= SwitchWaitSessionHandler.MAX_HELD_BYTES) {
      if (connection.channel().config().isAutoRead()) {
        logger.debug(
            "{} sent {} frames ({} bytes) while probed: no longer read",
            connection.remoteAddress(),
            held.size(),
            heldBytes);
      }
      connection.setAutoRead(false);
    }
  }

  @Override
  public void deactivated() {
    cancelDeadline();
    releaseHeld();
  }

  @Override
  public void disconnected() {
    cancelDeadline();
    releaseHeld();
    if (!ended) {
      ended = true;
      listener.left();
    }
  }

  // ---------------------------------------------------------------------------
  // Deadline and outcome
  // ---------------------------------------------------------------------------

  private void scheduleDeadline() {
    deadlineAtNanos = probe.deadlineNanos();
    long delay = Math.max(0, deadlineAtNanos - nanoClock.getAsLong());
    deadline =
        connection
            .channel()
            .eventLoop()
            .schedule(this::deadlinePassed, delay, TimeUnit.NANOSECONDS);
  }

  private void deadlinePassed() {
    deadline = null;
    if (ended) {
      return;
    }
    Probe.Outcome outcome = probe.deadline();
    if (outcome instanceof Probe.Outcome.Pending) {
      scheduleDeadline(); // the deadline moved when the ping was answered
      return;
    }
    judge(outcome);
  }

  private void cancelDeadline() {
    ScheduledFuture<?> pending = deadline;
    if (pending != null) {
      pending.cancel(false);
      deadline = null;
    }
  }

  /** Hands the frames held over, in order; whatever replaces this handler finds none to release. */
  private List<ByteBuf> takeHeld() {
    List<ByteBuf> frames = new ArrayList<>(held);
    held.clear();
    heldBytes = 0;
    return frames;
  }

  private void releaseHeld() {
    for (ByteBuf frame = held.poll(); frame != null; frame = held.poll()) {
      frame.release();
    }
    heldBytes = 0;
  }

  private void judge(Probe.Outcome outcome) {
    if (ended) {
      return;
    }
    switch (outcome) {
      case Probe.Outcome.Pending _ -> {
        // Waiting for more; sooner when the answer moved the deadline closer (the cookie's grace).
        if (deadline != null && probe.deadlineNanos() - deadlineAtNanos < 0) {
          cancelDeadline();
          scheduleDeadline();
        }
      }
      case Probe.Outcome.Passed passed -> {
        ended = true;
        cancelDeadline();
        logger.trace("Probe passed for {}: {}", connection.remoteAddress(), passed);
        listener.passed(passed, settings, takeHeld());
      }
      case Probe.Outcome.Failed failed -> {
        ended = true;
        cancelDeadline();
        logger.debug(
            "Probe failed for {}: {} ({} unknown packets ignored)",
            connection.remoteAddress(),
            failed.detail(),
            probe.unknownPackets());
        listener.failed(failed);
      }
    }
  }
}
