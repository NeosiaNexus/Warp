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
package dev.warp.api.fleet;

import dev.warp.api.event.Subscription;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Messages between the proxies of the fleet, for plugins: parties, friends, cross-proxy chat,
 * anything a plugin needs every proxy to hear. Payloads are opaque bytes, at most {@value
 * #MAX_PAYLOAD} of them.
 *
 * <p>Delivery is at most once: a proxy whose store connection is down while a message is published
 * never receives it. A plugin that needs certainty keeps its own truth in its own store and uses
 * the bus to tell the others to look again. On a single node, a message is delivered to the local
 * subscribers of its channel, so plugin code behaves the same with one proxy and with fifty.
 *
 * <p>Handlers run on fleet threads, never on a connection's event loop; they may block.
 */
public interface FleetBus {

  /** The largest payload, in bytes. */
  int MAX_PAYLOAD = 32 * 1024;

  /**
   * Publishes a message to every proxy subscribed to a channel, this one included.
   *
   * @param channel the channel, a plugin-chosen name such as {@code myplugin:party}
   * @param payload the message, copied before this returns
   * @throws IllegalArgumentException if the payload exceeds {@link #MAX_PAYLOAD}
   */
  void publish(String channel, byte[] payload);

  /**
   * Subscribes to a channel.
   *
   * @param channel the channel
   * @param listener receives each message, on a fleet thread
   * @return the subscription, to close when the plugin no longer listens
   */
  Subscription subscribe(String channel, Consumer<FleetMessage> listener);

  /**
   * Sends a request to one proxy and awaits its reply from the {@link FleetResponder} it registered
   * for the channel with {@link #respond}. The request is at most once: if the proxy is alive, one
   * retry goes out after half the time-out; then the future fails.
   *
   * @param proxyId the id of the proxy asked
   * @param channel the channel its responder listens on
   * @param payload the request
   * @param timeout how long to wait for the reply
   * @return completed with the reply, or exceptionally with a {@link
   *     java.util.concurrent.TimeoutException} when none came in time, or another exception when
   *     the proxy is unknown or answered with a failure
   */
  CompletableFuture<byte[]> request(
      String proxyId, String channel, byte[] payload, Duration timeout);

  /**
   * Registers who answers the requests other proxies send this one on a channel. One responder per
   * channel: registering another replaces it.
   *
   * @param channel the channel
   * @param responder answers each request
   * @return the subscription, to close when the plugin no longer answers
   */
  Subscription respond(String channel, FleetResponder responder);
}
