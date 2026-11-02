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

import java.util.ArrayDeque;
import java.util.Queue;

import io.netty.buffer.ByteBuf;

/**
 * Temporary session handler installed on the client during a server switch.
 *
 * <p>Active between the client entering CONFIGURATION state and the new backend reaching
 * CONFIGURATION. During this window, auto-read is paused on the client channel, so this handler
 * rarely sees traffic: what the client sends waits in the socket for the next server.
 *
 * <p>Reads resume early when the client is asked for a cookie meanwhile, by the proxy or by the
 * next server during its login ({@link #readAnswers}): its answer is routed at once. Any other
 * frame is held, in order, and forwarded to the next server when its configuration begins ({@link
 * #forwardHeldTo}), as if it had waited in the socket.
 */
final class SwitchWaitSessionHandler implements SessionHandler {

  /** Frames held before reads pause again; the rest of the read in progress is held too. */
  static final int MAX_HELD_FRAMES = 64;

  private final ConnectedPlayer player;

  /** Frames the client sent while waiting, for the next server. */
  private final Queue<ByteBuf> held = new ArrayDeque<>();

  SwitchWaitSessionHandler(ConnectedPlayer player) {
    this.player = player;
  }

  @Override
  public void handle(Packet packet) {
    if (packet instanceof ClientInformation clientInfo) {
      // Client sends settings proactively on CONFIG entry. Cache them for replay
      // to the new backend — no forwarding here since the backend isn't connected yet.
      player.cacheClientSettings(clientInfo);
    } else if (packet instanceof CookieResponse response) {
      player.cookies().answered(response); // an unasked answer is dropped
    }
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    held.add(buf);
    if (held.size() >= MAX_HELD_FRAMES) {
      player.clientConnection().setAutoRead(false);
    }
  }

  /**
   * Resumes reading the waiting client, so that the answer to a cookie request it was just sent
   * comes in now rather than once the next server configures it.
   */
  void readAnswers() {
    if (held.size() < MAX_HELD_FRAMES) {
      player.clientConnection().setAutoRead(true);
    }
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
  }
}
