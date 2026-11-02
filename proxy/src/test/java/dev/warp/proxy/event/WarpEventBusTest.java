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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscribe;
import dev.warp.api.event.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WarpEventBus")
class WarpEventBusTest {

  private final WarpEventBus bus = new WarpEventBus();
  private final List<Subscription> tracked = new CopyOnWriteArrayList<>();
  private final EventBus plugin = new PluginEventBus(bus, "test", null, tracked::add);

  @AfterEach
  void tearDown() {
    bus.close();
  }

  /** A mutable event recording which handlers saw it, in order. */
  static class Probe {
    final List<String> seen = Collections.synchronizedList(new ArrayList<>());
  }

  /** A subtype, to check that handlers of a supertype receive it. */
  static final class SpecialProbe extends Probe implements Marker {}

  /** An interface handlers can subscribe to. */
  interface Marker {}

  @Nested
  @DisplayName("order")
  class Order {

    @Test
    @DisplayName("should run handlers by priority, then registration order")
    void priorityThenRegistration() throws Exception {
      plugin.subscribe(Probe.class, 0, e -> e.seen.add("normal-1"));
      plugin.subscribe(Probe.class, Subscribe.LATE, e -> e.seen.add("late"));
      plugin.subscribe(Probe.class, Subscribe.EARLY, e -> e.seen.add("early"));
      plugin.subscribe(Probe.class, 0, e -> e.seen.add("normal-2"));

      Probe probe = bus.fire(new Probe()).get(5, TimeUnit.SECONDS);

      assertEquals(List.of("early", "normal-1", "normal-2", "late"), probe.seen);
    }

    @Test
    @DisplayName("should deliver an event to handlers of its supertypes and interfaces")
    void supertypes() throws Exception {
      plugin.subscribe(Probe.class, e -> e.seen.add("probe"));
      plugin.subscribe(Marker.class, e -> ((Probe) e).seen.add("marker"));
      plugin.subscribe(Object.class, Subscribe.LATE, e -> ((Probe) e).seen.add("object"));

      SpecialProbe probe = bus.fire(new SpecialProbe()).get(5, TimeUnit.SECONDS);

      assertEquals(List.of("probe", "marker", "object"), probe.seen);
    }

    @Test
    @DisplayName("should never run handlers on the thread that fires the event")
    void neverOnCaller() throws Exception {
      List<Thread> threads = new CopyOnWriteArrayList<>();
      plugin.subscribe(Probe.class, e -> threads.add(Thread.currentThread()));

      bus.fire(new Probe()).get(5, TimeUnit.SECONDS);

      assertFalse(threads.contains(Thread.currentThread()));
      assertTrue(threads.getFirst().isVirtual());
    }

    @Test
    @DisplayName("should complete at once when nothing listens")
    void noHandlers() {
      Probe probe = new Probe();

      CompletableFuture<Probe> result = bus.fire(probe);

      assertTrue(result.isDone());
      assertSame(probe, result.join());
      assertFalse(bus.hasHandlers(Probe.class));
    }
  }

  @Nested
  @DisplayName("asynchronous handlers")
  class Async {

    @Test
    @DisplayName("should hold the event until the stage completes")
    void holds() throws Exception {
      CompletableFuture<Void> gate = new CompletableFuture<>();
      plugin.subscribeAsync(
          Probe.class, Subscribe.EARLY, 30, e -> gate.thenRun(() -> e.seen.add("async")));
      plugin.subscribe(Probe.class, e -> e.seen.add("after"));

      CompletableFuture<Probe> result = bus.fire(new Probe());
      TimeUnit.MILLISECONDS.sleep(100);
      assertFalse(result.isDone());
      gate.complete(null);

      assertEquals(List.of("async", "after"), result.get(5, TimeUnit.SECONDS).seen);
    }

    @Test
    @DisplayName("should skip a handler that holds the event past its time-out")
    void timeout() throws Exception {
      plugin.subscribeAsync(Probe.class, Subscribe.EARLY, 1, e -> new CompletableFuture<>());
      plugin.subscribe(Probe.class, e -> e.seen.add("after"));

      Probe probe = bus.fire(new Probe()).get(5, TimeUnit.SECONDS);

      assertEquals(List.of("after"), probe.seen);
    }

    @Test
    @DisplayName("should keep going after a stage that fails")
    void failedStage() throws Exception {
      plugin.subscribeAsync(
          Probe.class,
          Subscribe.EARLY,
          30,
          e -> CompletableFuture.failedFuture(new IllegalStateException("db down")));
      plugin.subscribe(Probe.class, e -> e.seen.add("after"));

      assertEquals(List.of("after"), bus.fire(new Probe()).get(5, TimeUnit.SECONDS).seen);
    }
  }

  @Nested
  @DisplayName("failures")
  class Failures {

    @Test
    @DisplayName("should run the other handlers when one throws")
    void contained() throws Exception {
      plugin.subscribe(
          Probe.class,
          Subscribe.EARLY,
          e -> {
            throw new IllegalStateException("boom");
          });
      plugin.subscribe(Probe.class, e -> e.seen.add("after"));

      CompletableFuture<Probe> result = bus.fire(new Probe());

      assertEquals(List.of("after"), result.get(5, TimeUnit.SECONDS).seen);
      assertFalse(result.isCompletedExceptionally());
    }
  }

  @Nested
  @DisplayName("lanes")
  class Lanes {

    @Test
    @DisplayName("should deliver the events of one lane one after another, in order")
    void ordered() throws Exception {
      CompletableFuture<Void> gate = new CompletableFuture<>();
      List<String> order = new CopyOnWriteArrayList<>();
      plugin.subscribeAsync(
          Probe.class,
          0,
          30,
          e -> {
            String name = e.seen.getFirst();
            order.add("start " + name);
            CompletionStage<Void> wait =
                name.equals("first") ? gate : CompletableFuture.completedFuture(null);
            return wait.thenRun(() -> order.add("end " + name));
          });

      CompletableFuture<Probe> first = bus.fire(named("first"), "steve");
      CompletableFuture<Probe> second = bus.fire(named("second"), "steve");
      TimeUnit.MILLISECONDS.sleep(100);
      assertEquals(List.of("start first"), order);
      gate.complete(null);
      second.get(5, TimeUnit.SECONDS);

      assertTrue(first.isDone());
      assertEquals(List.of("start first", "end first", "start second", "end second"), order);
    }

    @Test
    @DisplayName("should not hold events of other lanes")
    void independent() throws Exception {
      CompletableFuture<Void> gate = new CompletableFuture<>();
      plugin.subscribeAsync(
          Probe.class,
          0,
          30,
          e -> e.seen.getFirst().equals("held") ? gate : CompletableFuture.completedFuture(null));

      CompletableFuture<Probe> held = bus.fire(named("held"), "steve");
      Probe other = bus.fire(named("free"), "alex").get(5, TimeUnit.SECONDS);

      assertEquals("free", other.seen.getFirst());
      assertFalse(held.isDone());
      gate.complete(null);
      held.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("should keep lane order for events nobody listens to")
    void orderWithoutHandlers() throws Exception {
      CompletableFuture<Void> gate = new CompletableFuture<>();
      plugin.subscribeAsync(Probe.class, 0, 30, e -> gate);

      CompletableFuture<Probe> held = bus.fire(new Probe(), "steve");
      CompletableFuture<String> unheard = bus.fire("nobody listens", "steve");
      TimeUnit.MILLISECONDS.sleep(100);

      assertFalse(unheard.isDone());
      gate.complete(null);
      assertEquals("nobody listens", unheard.get(5, TimeUnit.SECONDS));
      assertTrue(held.isDone());
    }

    private static Probe named(String name) {
      Probe probe = new Probe();
      probe.seen.add(name);
      return probe;
    }
  }

  @Nested
  @DisplayName("subscriptions")
  class Subscriptions {

    @Test
    @DisplayName("should stop delivering to a handler once unsubscribed")
    void unsubscribe() throws Exception {
      Subscription subscription = plugin.subscribe(Probe.class, e -> e.seen.add("gone"));

      subscription.unsubscribe();

      assertFalse(subscription.isActive());
      assertTrue(bus.fire(new Probe()).get(5, TimeUnit.SECONDS).seen.isEmpty());
    }

    @Test
    @DisplayName("should skip a handler unsubscribed while the event is on its way")
    void unsubscribeDuringDelivery() throws Exception {
      List<Subscription> later = new ArrayList<>();
      plugin.subscribe(Probe.class, Subscribe.EARLY, e -> later.getFirst().unsubscribe());
      later.add(plugin.subscribe(Probe.class, e -> e.seen.add("unsubscribed")));

      assertTrue(bus.fire(new Probe()).get(5, TimeUnit.SECONDS).seen.isEmpty());
    }

    @Test
    @DisplayName("should hand every subscription to the plugin's tracker")
    void tracked() {
      Subscription one = plugin.subscribe(Probe.class, e -> {});
      Subscription two = plugin.register(new Listener());

      assertEquals(List.of(one, two), tracked);
    }

    @Test
    @DisplayName("should run plugin handlers with the plugin's class loader as context loader")
    void contextLoader() throws Exception {
      ClassLoader pluginLoader = new ClassLoader(null) {};
      EventBus scoped = new PluginEventBus(bus, "scoped", pluginLoader, s -> {});
      List<ClassLoader> seen = new CopyOnWriteArrayList<>();
      scoped.subscribe(Probe.class, e -> seen.add(Thread.currentThread().getContextClassLoader()));

      bus.fire(new Probe()).get(5, TimeUnit.SECONDS);

      assertEquals(List.of(pluginLoader), seen);
    }
  }

  @Nested
  @DisplayName("listeners")
  class Listeners {

    @Test
    @DisplayName("should register every @Subscribe method, whatever its visibility")
    void registersMethods() throws Exception {
      Listener listener = new Listener();
      plugin.register(listener);

      Probe probe = bus.fire(new Probe()).get(5, TimeUnit.SECONDS);

      assertEquals(List.of("early", "async", "private"), probe.seen);
    }

    @Test
    @DisplayName("should register an overridden handler once")
    void overridden() throws Exception {
      plugin.register(new SubListener());

      assertEquals(List.of("sub"), bus.fire(new Probe()).get(5, TimeUnit.SECONDS).seen);
    }

    @Test
    @DisplayName("should unsubscribe every handler of a listener at once")
    void unregisterListener() throws Exception {
      Subscription subscription = plugin.register(new Listener());

      subscription.close();

      assertTrue(bus.fire(new Probe()).get(5, TimeUnit.SECONDS).seen.isEmpty());
    }

    @Test
    @DisplayName("should explain an invalid handler")
    void invalid() {
      assertThrows(IllegalArgumentException.class, () -> plugin.register(new Object()));
      IllegalArgumentException twoParameters =
          assertThrows(IllegalArgumentException.class, () -> plugin.register(new TwoParameters()));
      assertTrue(String.valueOf(twoParameters.getMessage()).contains("TwoParameters#handle"));
      assertThrows(IllegalArgumentException.class, () -> plugin.register(new WrongReturn()));
    }
  }

  static class Listener {
    @Subscribe(priority = Subscribe.EARLY)
    public void early(Probe probe) {
      probe.seen.add("early");
    }

    @Subscribe
    public CompletableFuture<Void> async(Probe probe) {
      return CompletableFuture.runAsync(() -> probe.seen.add("async"));
    }

    @Subscribe(priority = Subscribe.LATE)
    private void hidden(Probe probe) {
      probe.seen.add("private");
    }
  }

  static class BaseListener {
    @Subscribe
    public void handle(Probe probe) {
      probe.seen.add("base");
    }
  }

  static final class SubListener extends BaseListener {
    @Override
    @Subscribe
    public void handle(Probe probe) {
      probe.seen.add("sub");
    }
  }

  static final class TwoParameters {
    @Subscribe
    public void handle(Probe probe, String extra) {}
  }

  static final class WrongReturn {
    @Subscribe
    public String handle(Probe probe) {
      return "";
    }
  }
}
