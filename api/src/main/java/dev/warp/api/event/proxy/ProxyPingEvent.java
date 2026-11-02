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
package dev.warp.api.event.proxy;

import dev.warp.api.event.ResultedEvent;
import dev.warp.api.player.InboundConnection;
import dev.warp.api.server.ServerPing;

/**
 * Fired when a client asks for the proxy's server list entry: a player's multiplayer screen, a
 * server list site, a monitoring tool. Handlers can replace the entry the proxy proposes, built
 * from the {@code status} block of {@code warp.conf} and the players online, or not answer at all.
 *
 * <p>Fired for the server list ping of every version: the status request of Minecraft 1.7 and
 * later, and the legacy ping ({@code 0xFE}) of 1.6 and older, which some tools and load balancer
 * health checks still send. A legacy ping only shows the first line of the description, as legacy
 * text, the counts and the version name; its connection's {@link
 * InboundConnection#protocolVersion()} is {@code -1}.
 *
 * <p>The proposed entry advertises the client's own protocol when the proxy supports it, so that
 * the client lists the proxy as compatible (the proxy translates every version it supports), and
 * the latest supported one otherwise. Its version name is only shown to clients the proxy does not
 * support.
 *
 * <p>Each handler sees the entry the previous handlers left: change the parts it cares about from
 * {@link #ping()} with the {@code with} methods of {@link ServerPing}, which keep the rest:
 *
 * <pre>{@code
 * @Subscribe
 * public void onPing(ProxyPingEvent event) {
 *   event.setPing(event.ping().withDescription(motd));
 * }
 * }</pre>
 *
 * <p>The client waits for the answer, so keep handlers quick. One that needs a backend's state
 * returns a stage, and holds the ping until it completes or its time-out expires:
 *
 * <pre>{@code
 * @Subscribe
 * public CompletableFuture<?> onPing(ProxyPingEvent event) {
 *   return lobby.ping().thenAccept(ping -> event.setPing(event.ping().withPlayers(ping.players())));
 * }
 * }</pre>
 *
 * <p>The proxy answers once every handler is done: a change made later, from a callback the handler
 * did not return, is ignored and logged.
 *
 * <p>Fired only when a handler is registered for it: without one, the proxy answers from a cached
 * entry and builds no event.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ProxyPingEvent extends ResultedEvent<ProxyPingEvent.Result> {

  /**
   * Returns the connection asking: its address, the host name it typed and its protocol version.
   *
   * @return the connection
   */
  InboundConnection connection();

  /**
   * Returns the entry the proxy will answer with.
   *
   * @return the entry: the proposed one, or the last one a handler set
   */
  ServerPing ping();

  /**
   * Replaces the entry the proxy will answer with. An entry too long for the client (the whole
   * answer is limited to 32,767 characters) is answered without its icon, then without its sample.
   *
   * @param ping the new entry
   */
  void setPing(ServerPing ping);

  /** Whether the proxy answers. The default is {@link Answer}. */
  sealed interface Result {}

  /** Answer with {@link #ping()}. */
  record Answer() implements Result {}

  /**
   * Close the connection without answering: the client lists the server as unreachable, as if
   * nothing listened. Hides the proxy from scanners, or from clients that typed a host name the
   * network does not serve.
   */
  record Ignore() implements Result {}
}
