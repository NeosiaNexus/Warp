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
package dev.warp.api.messaging;

import dev.warp.api.event.Subscription;

import java.util.Set;

/**
 * Registers the plugin message channels a plugin owns. Each plugin has its own, from {@link
 * dev.warp.api.plugin.PluginContext#messaging()}: its channels are released when it is disabled or
 * fails.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Messaging {

  /**
   * Takes ownership of a channel. From now on, a message on it from an accepted side fires {@link
   * PluginMessageEvent} and is only relayed if a handler asks; a message from another side is
   * dropped.
   *
   * <p>Registering a name of the BungeeCord channel ({@code BungeeCord} or {@code bungeecord:main},
   * which a proxy answers by default) takes it over from the proxy: every message a backend sends
   * on it goes to the plugin, and the proxy answers none unless a handler sets {@link
   * PluginMessageEvent.Pass}. The proxy answers the channel again once the registration is closed.
   * To take over only some sub-channels, listen to {@link PluginMessageEvent} without registering
   * the channel instead.
   *
   * @param channel the channel
   * @param accepted the sides the plugin accepts messages from
   * @return the registration; closing it releases the channel
   * @throws IllegalStateException if another plugin owns the channel (the message names it)
   * @throws IllegalArgumentException if no side is accepted
   */
  Subscription register(ChannelIdentifier channel, Set<Origin> accepted);

  /** Who sends a plugin message. */
  enum Origin {
    /** A player's client. */
    PLAYER,
    /** A backend server. */
    SERVER
  }
}
