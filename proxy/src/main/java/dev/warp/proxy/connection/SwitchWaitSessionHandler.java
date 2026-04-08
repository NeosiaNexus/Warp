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

import io.netty.buffer.ByteBuf;

/**
 * Temporary session handler installed on the client during a server switch.
 *
 * <p>Active between the client entering CONFIGURATION state and the new backend reaching
 * CONFIGURATION. During this window, auto-read is paused on the client channel, so this handler
 * rarely sees traffic. Any packets that arrive are safely released.
 */
final class SwitchWaitSessionHandler implements SessionHandler {

  private final ConnectedPlayer player;

  SwitchWaitSessionHandler(ConnectedPlayer player) {
    this.player = player;
  }

  @Override
  public void handle(Packet packet) {
    // Client may send ClientInformation proactively during CONFIG wait.
    // The new backend will request fresh information after the switch.
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    buf.release();
  }

  @Override
  public void disconnected() {
    player.disconnect();
  }
}
