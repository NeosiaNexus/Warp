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
package dev.warp.api.event.player;

import dev.warp.api.event.ResultedEvent;
import dev.warp.api.player.InboundConnection;

import net.kyori.adventure.text.Component;

/**
 * Fired when a client starts logging in, before it is authenticated. Deny here what can be decided
 * from the name and address alone (bans by IP, maintenance), before spending a Mojang request.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PreLoginEvent extends ResultedEvent<PreLoginEvent.Result> {

  /**
   * Returns the connection logging in.
   *
   * @return the connection
   */
  InboundConnection connection();

  /**
   * Returns the name the client claims. Not verified yet, even in online mode.
   *
   * @return the name
   */
  String username();

  /** What the proxy does with the login. The default is {@link Allowed}. */
  sealed interface Result {}

  /** Go on with the login, authenticating as the proxy is configured to. */
  record Allowed() implements Result {}

  /**
   * Refuse the login.
   *
   * @param reason shown to the player
   */
  record Denied(Component reason) implements Result {}

  /** Go on, authenticating this player with Mojang even if the proxy runs in offline mode. */
  record ForceOnlineMode() implements Result {}

  /** Go on, without authenticating this player even if the proxy runs in online mode. */
  record ForceOfflineMode() implements Result {}
}
