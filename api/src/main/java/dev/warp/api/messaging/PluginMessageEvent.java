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

import dev.warp.api.event.ResultedEvent;
import dev.warp.api.player.Player;
import dev.warp.api.server.ServerConnection;

/**
 * Fired for a plugin message on a channel a plugin registered with {@link Messaging}, from a side
 * it accepts. The message is not relayed to the other side unless a handler sets {@link Forward}.
 *
 * <p>Also fired, when any handler listens, for a message a backend sends on a channel the proxy
 * speaks itself and no plugin registered: the BungeeCord channel ({@code BungeeCord} before
 * Minecraft 1.13, {@code bungeecord:main} from 1.13). The result then starts as {@link Pass}: the
 * proxy answers the message once the handlers are done, unless one of them takes it with {@link
 * Handled}. A plugin can so watch every request the backends make, and take over the sub-channels
 * it wants to answer itself, such as {@code PlayerCount} counted across several proxies, while the
 * proxy answers the others. A client's message on the BungeeCord channel never fires it: only
 * backends speak it, and the proxy drops what a client sends there.
 *
 * <p>Delivered on the player's lane, in the order the messages arrived.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PluginMessageEvent extends ResultedEvent<PluginMessageEvent.Result> {

  /**
   * Returns the channel.
   *
   * @return the channel
   */
  ChannelIdentifier channel();

  /**
   * Returns who sent the message.
   *
   * @return the source
   */
  Source source();

  /**
   * Returns the player the message travels for: its sender, or the player whose server sent it.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns a copy of the message's payload.
   *
   * @return the payload
   */
  byte[] data();

  /** Who sent a message. */
  sealed interface Source {}

  /**
   * The player's client sent it.
   *
   * @param player the player
   */
  record FromPlayer(Player player) implements Source {}

  /**
   * A backend server sent it, over a player's connection.
   *
   * @param connection the connection it came over
   */
  record FromServer(ServerConnection connection) implements Source {}

  /**
   * What happens to the message. The default is {@link Handled} on a channel a plugin registered,
   * {@link Pass} on a channel the proxy speaks itself (see above).
   */
  sealed interface Result {}

  /** The plugin consumed it: it goes no further. */
  record Handled() implements Result {}

  /** Relay it to the other side: the backend for a player's message, the client for a server's. */
  record Forward() implements Result {}

  /**
   * Let the message go on as if no plugin had seen it: the proxy answers it on a channel it speaks
   * itself (the BungeeCord channel), and relays it to the other side, as {@link Forward}, on any
   * other channel. A plugin that registered the BungeeCord channel to take it over sets this to
   * hand the proxy back the sub-channels it does not answer itself.
   */
  record Pass() implements Result {}
}
