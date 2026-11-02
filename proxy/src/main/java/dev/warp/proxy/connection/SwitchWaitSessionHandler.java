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
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.play.KeepAlive;

import java.util.ArrayDeque;
import java.util.Queue;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session handler of a client that waits in the configuration phase for its next server (1.20.2+):
 * its first one while it is chosen and logs the player in, or the next one during a switch or a
 * fallback, plugins holding the player included.
 *
 * <p>The client is read all along: the proxy keeps it alive with keep-alives of the configuration
 * state, and its answers are matched as they come ({@link ConnectedPlayer#keepAliveAnswered}), as
 * are its answers to cookie requests. Anything else it sends is for the next server: held, in
 * order, and forwarded when that server's configuration begins ({@link #forwardHeldTo}), as if it
 * had waited in the socket, so that nothing the client sends overtakes it. Its settings are cached
 * for that server too.
 *
 * <p>A vanilla client sends two frames here (its brand and its settings, on entering the phase),
 * then only answers. Reads pause once {@value #MAX_HELD_FRAMES} frames or {@value #MAX_HELD_BYTES}
 * bytes are held: a client that floods the proxy meanwhile no longer has its answers read, and is
 * timed out.
 */
final class SwitchWaitSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(SwitchWaitSessionHandler.class);

  /** Frames held before reads pause; the rest of the read in progress is held too. */
  static final int MAX_HELD_FRAMES = 64;

  /** Bytes held before reads pause; the rest of the read in progress is held too. */
  static final int MAX_HELD_BYTES = 1 << 20;

  private final ConnectedPlayer player;

  /** Frames the client sent while waiting, for the next server. */
  private final Queue<ByteBuf> held = new ArrayDeque<>();

  /** Bytes of {@link #held}. */
  private long heldBytes;

  SwitchWaitSessionHandler(ConnectedPlayer player) {
    this.player = player;
  }

  @Override
  public void activated() {
    // The server the client leaves may have paused it (back-pressure), and the switch must read it
    // for its keep-alive answers.
    readAnswers();
  }

  @Override
  public void handle(Packet packet) {
    switch (packet) {
      case KeepAlive keepAlive -> player.keepAliveAnswered(keepAlive.id());
      // Sent on entering the configuration phase: cached for the next server, which receives it
      // when its configuration begins (BackendConfigSessionHandler#activated).
      case ClientInformation clientInfo -> player.cacheClientSettings(clientInfo);
      case CookieResponse response -> player.cookies().answered(response); // unasked: dropped
      default -> {
        // The other packets decoded in this state are answers to servers, which no server asked.
      }
    }
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    held.add(buf);
    heldBytes += buf.readableBytes();
    if (isFull()) {
      MinecraftConnection client = player.clientConnection();
      if (client.channel().config().isAutoRead()) {
        logger.info(
            "{} sent {} frames ({} bytes) while waiting for its next server: no longer read",
            player.username(),
            held.size(),
            heldBytes);
      }
      client.setAutoRead(false);
    }
  }

  /**
   * Reads the waiting client, unless it already sent as much as the next server can be held: called
   * when the wait begins, and when the client is asked for a cookie, whose answer must not wait for
   * the next server.
   */
  void readAnswers() {
    if (!isFull()) {
      player.clientConnection().setAutoRead(true);
    }
  }

  private boolean isFull() {
    return held.size() >= MAX_HELD_FRAMES || heldBytes >= MAX_HELD_BYTES;
  }

  /**
   * Forwards the frames held while waiting to the backend whose configuration the client enters, in
   * the order they arrived. Called just before this handler is replaced.
   *
   * @param backend the next server, in its configuration state
   */
  void forwardHeldTo(MinecraftConnection backend) {
    MinecraftConnection client = player.clientConnection();
    for (ByteBuf frame = held.poll(); frame != null; frame = held.poll()) {
      backend.forward(frame, client);
    }
    heldBytes = 0;
    backend.flush();
  }

  @Override
  public void deactivated() {
    releaseHeld();
  }

  @Override
  public void disconnected() {
    releaseHeld();
    player.disconnect();
  }

  private void releaseHeld() {
    for (ByteBuf frame = held.poll(); frame != null; frame = held.poll()) {
      frame.release();
    }
    heldBytes = 0;
  }
}
