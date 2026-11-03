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
package dev.warp.api.event;

import java.util.concurrent.CompletableFuture;

/**
 * Registers handlers and fires events. Each plugin has its own, from {@link
 * dev.warp.api.plugin.PluginContext#events()}: what it registers belongs to it, and is removed when
 * the plugin is disabled or fails. See the package documentation for the guarantees.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface EventBus {

  /**
   * Registers every {@link Subscribe} method of a listener.
   *
   * @param listener the listener
   * @return a subscription covering all of the listener's handlers
   * @throws IllegalArgumentException if the listener has no handler, or a handler's signature is
   *     invalid (the message names the method and what is wrong)
   */
  Subscription register(Object listener);

  /**
   * Registers a handler that is done with the event when it returns.
   *
   * @param type the event type; the handler also receives its subtypes
   * @param priority when the handler runs, see {@link Subscribe#priority()}
   * @param handler the handler
   * @param <E> the event type
   * @return the subscription
   */
  <E> Subscription subscribe(Class<E> type, int priority, EventHandler<? super E> handler);

  /**
   * Registers a handler at {@link Subscribe#NORMAL} priority.
   *
   * @param type the event type; the handler also receives its subtypes
   * @param handler the handler
   * @param <E> the event type
   * @return the subscription
   */
  default <E> Subscription subscribe(Class<E> type, EventHandler<? super E> handler) {
    return subscribe(type, Subscribe.NORMAL, handler);
  }

  /**
   * Registers a handler that holds the event until the stage it returns completes.
   *
   * @param type the event type; the handler also receives its subtypes
   * @param priority when the handler runs, see {@link Subscribe#priority()}
   * @param timeoutSeconds how long the handler may hold the event, see {@link
   *     Subscribe#timeoutSeconds()}
   * @param handler the handler
   * @param <E> the event type
   * @return the subscription
   */
  <E> Subscription subscribeAsync(
      Class<E> type, int priority, int timeoutSeconds, AsyncEventHandler<? super E> handler);

  /**
   * Delivers an event to its handlers. Plugins can fire events of their own types for other
   * plugins.
   *
   * @param event the event
   * @param <E> the event type
   * @return a future completed with the event once every handler is done with it
   */
  <E> CompletableFuture<E> fire(E event);
}
