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
package dev.warp.via;

import dev.warp.api.Warp;
import dev.warp.api.player.Player;

import java.util.UUID;

import com.viaversion.viaversion.ViaAPIBase;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/**
 * ViaVersion's API ({@code Via.getAPI()}) for other plugins, on Warp.
 *
 * <p>A player's version comes from Warp, which never changes it: it is the client's real version,
 * also for players whose packets are translated (on Velocity, a player translated up to 1.13 for
 * modern forwarding is reported as 1.13, PaperMC/Velocity#1430). ViaVersion translates on backend
 * connections only, so there is no client-side ViaVersion connection: {@link #isInjected} is {@code
 * false} and {@link #sendRawPacket} refuses, as ViaVersion does for a player it does not control.
 */
final class WarpViaApi extends ViaAPIBase<Player> {

  private final Warp proxy;

  /**
   * Creates the API.
   *
   * @param proxy the proxy, which knows every player's version
   */
  WarpViaApi(Warp proxy) {
    this.proxy = proxy;
  }

  @Override
  public ProtocolVersion getPlayerProtocolVersion(Player player) {
    return ProtocolVersion.getProtocol(player.protocolVersion());
  }

  @Override
  public ProtocolVersion getPlayerProtocolVersion(UUID uuid) {
    return proxy.player(uuid).map(this::getPlayerProtocolVersion).orElse(ProtocolVersion.unknown);
  }

  @Override
  public void sendRawPacket(Player player, ByteBuf packet) {
    sendRawPacket(player.uniqueId(), packet);
  }
}
