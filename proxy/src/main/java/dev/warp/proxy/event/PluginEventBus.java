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
package dev.warp.proxy.event;

import dev.warp.api.event.AsyncEventHandler;
import dev.warp.api.event.EventBus;
import dev.warp.api.event.EventHandler;
import dev.warp.api.event.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * One plugin's view of the event bus: every subscription it makes is owned by the plugin, named
 * after it in logs, runs with its class loader, and is handed to the plugin's registration tracker
 * so it disappears with the plugin.
 */
public final class PluginEventBus implements EventBus {

  /** Time-out of handlers registered without one. */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  private final WarpEventBus bus;
  private final String owner;
  private final @Nullable ClassLoader classLoader;
  private final Consumer<Subscription> tracker;

  /**
   * Creates a plugin's bus.
   *
   * @param bus the proxy's bus
   * @param owner the plugin's identifier
   * @param classLoader the plugin's class loader, the context class loader of its handlers
   * @param tracker receives every subscription, to close it when the plugin goes away
   */
  public PluginEventBus(
      WarpEventBus bus,
      String owner,
      @Nullable ClassLoader classLoader,
      Consumer<Subscription> tracker) {
    this.bus = bus;
    this.owner = owner;
    this.classLoader = classLoader;
    this.tracker = tracker;
  }

  @Override
  public Subscription register(Object listener) {
    List<WarpEventBus.ListenerMethod> methods = WarpEventBus.scan(listener);
    List<Subscription> handlers = new ArrayList<>(methods.size());
    for (WarpEventBus.ListenerMethod method : methods) {
      handlers.add(
          bus.add(
              method.type(),
              method.priority(),
              method.timeoutSeconds(),
              owner,
              method.name(),
              classLoader,
              method.invoker()));
    }
    Subscription subscription = new ListenerSubscription(handlers);
    tracker.accept(subscription);
    return subscription;
  }

  @Override
  @SuppressWarnings("unchecked") // the bus only delivers events of the subscribed type
  public <E> Subscription subscribe(Class<E> type, int priority, EventHandler<? super E> handler) {
    Subscription subscription =
        bus.add(
            type,
            priority,
            DEFAULT_TIMEOUT_SECONDS,
            owner,
            handler.getClass().getName(),
            classLoader,
            event -> {
              handler.handle((E) event);
              return null;
            });
    tracker.accept(subscription);
    return subscription;
  }

  @Override
  @SuppressWarnings("unchecked") // the bus only delivers events of the subscribed type
  public <E> Subscription subscribeAsync(
      Class<E> type, int priority, int timeoutSeconds, AsyncEventHandler<? super E> handler) {
    Subscription subscription =
        bus.add(
            type,
            priority,
            timeoutSeconds,
            owner,
            handler.getClass().getName(),
            classLoader,
            event -> handler.handle((E) event));
    tracker.accept(subscription);
    return subscription;
  }

  @Override
  public <E> CompletableFuture<E> fire(E event) {
    return bus.fire(event);
  }

  /** The handlers of one listener, unsubscribed together. */
  private static final class ListenerSubscription implements Subscription {

    private final List<Subscription> handlers;

    ListenerSubscription(List<Subscription> handlers) {
      this.handlers = List.copyOf(handlers);
    }

    @Override
    public boolean isActive() {
      return handlers.stream().anyMatch(Subscription::isActive);
    }

    @Override
    public void unsubscribe() {
      handlers.forEach(Subscription::unsubscribe);
    }
  }
}
