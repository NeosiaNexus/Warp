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

import dev.warp.api.event.Subscribe;
import dev.warp.api.event.Subscription;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers events to handlers with the guarantees documented in {@link dev.warp.api.event}.
 *
 * <p>An event's handlers run one after another on a virtual thread, highest priority first. A
 * handler returning an incomplete stage suspends the delivery without holding a thread: the
 * delivery resumes when the stage completes, or when the handler's time-out expires. Events given
 * the same lane (the proxy uses the player's UUID) are delivered one after another, in the order
 * they were fired.
 *
 * <p>Handlers for each event class are resolved once (the class, its superclasses and interfaces)
 * and cached until the next registration change, so firing an event with no handler costs a map
 * lookup.
 */
public final class WarpEventBus implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(WarpEventBus.class);

  /** A handler holding an event longer than this is reported as slow. */
  private static final long SLOW_HANDLER_NANOS = TimeUnit.SECONDS.toNanos(1);

  /** How long shutdown waits for events being delivered. */
  private static final long SHUTDOWN_GRACE_SECONDS = 10;

  private static final Handler[] NO_HANDLERS = new Handler[0];

  private static final Comparator<Handler> ORDER =
      Comparator.comparingInt((Handler h) -> h.priority).reversed().thenComparingLong(h -> h.order);

  private static final CompletableFuture<@Nullable Void> DONE =
      CompletableFuture.completedFuture(null);

  private final ExecutorService executor;
  private final ScheduledExecutorService timer;
  private final Map<Class<?>, List<Handler>> registered = new ConcurrentHashMap<>();
  private final Map<Class<?>, Handler[]> resolved = new ConcurrentHashMap<>();
  private final AtomicLong generation = new AtomicLong();
  private final AtomicLong order = new AtomicLong();
  private final Map<Object, CompletableFuture<?>> lanes = new ConcurrentHashMap<>();

  /** Creates a bus delivering events on virtual threads. */
  public WarpEventBus() {
    this.executor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("warp-event-", 0).factory());
    this.timer =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("warp-event-timer").daemon().factory());
  }

  // ---------------------------------------------------------------------------
  // Firing
  // ---------------------------------------------------------------------------

  /**
   * Returns whether an event of this class has at least one handler. The proxy checks it before
   * building events that would cost something to build.
   *
   * @param eventClass the event's class
   * @return {@code true} if a handler would receive it
   */
  public boolean hasHandlers(Class<?> eventClass) {
    return handlersFor(eventClass).length > 0;
  }

  /**
   * Delivers an event.
   *
   * @param event the event
   * @param <E> the event type
   * @return completed with the event once every handler is done with it; never exceptionally
   */
  public <E> CompletableFuture<E> fire(E event) {
    Handler[] handlers = handlersFor(event.getClass());
    if (handlers.length == 0) {
      return CompletableFuture.completedFuture(event);
    }
    Delivery<E> delivery = new Delivery<>(event, handlers);
    executor.execute(delivery::run);
    return delivery.result;
  }

  /**
   * Delivers an event after every event fired earlier on the same lane was delivered.
   *
   * @param event the event
   * @param lane the lane, such as a player's UUID
   * @param <E> the event type
   * @return completed with the event once every handler is done with it; never exceptionally
   */
  public <E> CompletableFuture<E> fire(E event, Object lane) {
    CompletableFuture<E> result = new CompletableFuture<>();
    lanes.compute(
        lane,
        (_, tail) -> {
          CompletableFuture<?> previous = tail == null ? DONE : tail;
          // Asynchronously, so that nothing completes inside compute.
          var _ =
              previous.whenCompleteAsync(
                  (_, _) -> {
                    var _ = fire(event).thenAccept(result::complete);
                  },
                  executor);
          return result;
        });
    var _ = result.whenComplete((_, _) -> lanes.remove(lane, result));
    return result;
  }

  /** One event being delivered to its handlers, one after another. */
  private final class Delivery<E> {

    private final E event;
    private final Handler[] handlers;
    private final CompletableFuture<E> result = new CompletableFuture<>();
    private int next;

    Delivery(E event, Handler[] handlers) {
      this.event = event;
      this.handlers = handlers;
    }

    /** Runs handlers until one suspends the delivery or none is left. Runs on the executor. */
    void run() {
      while (next < handlers.length) {
        Handler handler = handlers[next++];
        if (!handler.isActive()) {
          continue;
        }
        long start = System.nanoTime();
        CompletionStage<?> stage;
        try {
          stage = handler.invoke(event);
        } catch (Throwable t) {
          failed(handler, t);
          continue;
        }
        if (stage == null) {
          reportIfSlow(handler, start);
          continue;
        }
        CompletableFuture<?> future = stage.toCompletableFuture();
        if (future.isDone()) {
          checkOutcome(handler, future);
          reportIfSlow(handler, start);
          continue;
        }
        suspend(handler, future, start);
        return;
      }
      result.complete(event);
    }

    private void suspend(Handler handler, CompletableFuture<?> future, long start) {
      AtomicBoolean resumed = new AtomicBoolean();
      ScheduledFuture<?> timeout =
          timer.schedule(
              () -> {
                if (resumed.compareAndSet(false, true)) {
                  logger.warn(
                      "{} held {} for more than {} s: skipped. Raise timeoutSeconds if it needs"
                          + " longer",
                      handler,
                      event.getClass().getSimpleName(),
                      handler.timeoutSeconds);
                  resume();
                }
              },
              handler.timeoutSeconds,
              TimeUnit.SECONDS);
      var _ =
          future.whenComplete(
              (_, error) -> {
                if (resumed.compareAndSet(false, true)) {
                  timeout.cancel(false);
                  if (error != null) {
                    failed(handler, unwrap(error));
                  }
                  reportIfSlow(handler, start);
                  resume();
                }
              });
    }

    private void resume() {
      try {
        executor.execute(this::run);
      } catch (RuntimeException rejected) {
        // Shutting down: complete rather than leave the proxy waiting forever.
        result.complete(event);
      }
    }

    private void checkOutcome(Handler handler, CompletableFuture<?> future) {
      if (future.isCompletedExceptionally()) {
        try {
          future.join();
        } catch (CompletionException | java.util.concurrent.CancellationException e) {
          failed(handler, unwrap(e));
        }
      }
    }

    private void failed(Handler handler, Throwable error) {
      logger.error("{} failed on {}", handler, event.getClass().getSimpleName(), error);
    }

    private void reportIfSlow(Handler handler, long start) {
      long elapsed = System.nanoTime() - start;
      if (elapsed > SLOW_HANDLER_NANOS) {
        logger.warn(
            "{} took {} ms on {}: later events of the same player waited",
            handler,
            TimeUnit.NANOSECONDS.toMillis(elapsed),
            event.getClass().getSimpleName());
      }
    }
  }

  private static Throwable unwrap(Throwable error) {
    Throwable cause = error;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  /** Calls a handler; returns the stage it holds the event with, or {@code null}. */
  @FunctionalInterface
  interface Invoker {
    @Nullable CompletionStage<?> invoke(Object event) throws Throwable;
  }

  /**
   * Registers a handler.
   *
   * @param type the event type it receives, with subtypes
   * @param priority higher runs first
   * @param timeoutSeconds how long it may hold an event
   * @param owner who registered it, for logs (a plugin identifier)
   * @param name what it is, for logs
   * @param contextLoader the context class loader it runs with, or {@code null} to keep the
   *     thread's
   * @param invoker calls it
   * @return the handler, which is its own subscription
   */
  Handler add(
      Class<?> type,
      int priority,
      int timeoutSeconds,
      String owner,
      String name,
      @Nullable ClassLoader contextLoader,
      Invoker invoker) {
    if (timeoutSeconds < 1) {
      throw new IllegalArgumentException(
          "timeoutSeconds must be at least 1, got " + timeoutSeconds);
    }
    Handler handler =
        new Handler(
            type,
            priority,
            order.getAndIncrement(),
            timeoutSeconds,
            owner,
            name,
            contextLoader,
            invoker);
    registered.computeIfAbsent(type, _ -> new CopyOnWriteArrayList<>()).add(handler);
    invalidate();
    return handler;
  }

  /**
   * Finds the {@link Subscribe} methods of a listener and makes handlers of them, not registered
   * yet.
   *
   * @param listener the listener
   * @return the handler specifications, in declaration order
   * @throws IllegalArgumentException if the listener has none, or one is invalid
   */
  static List<ListenerMethod> scan(Object listener) {
    List<ListenerMethod> methods = new ArrayList<>();
    Set<String> overridden = new HashSet<>();
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    for (Class<?> type = listener.getClass();
        type != null && type != Object.class;
        type = type.getSuperclass()) {
      Method[] declared = type.getDeclaredMethods();
      Arrays.sort(declared, Comparator.comparing(Method::getName));
      for (Method method : declared) {
        if (method.isSynthetic() || method.isBridge()) {
          continue;
        }
        String signature = method.getName() + Arrays.toString(method.getParameterTypes());
        boolean seen = !overridden.add(signature);
        Subscribe subscribe = method.getAnnotation(Subscribe.class);
        if (subscribe == null || seen) {
          continue;
        }
        methods.add(listenerMethod(lookup, listener, method, subscribe));
      }
    }
    if (methods.isEmpty()) {
      throw new IllegalArgumentException(
          listener.getClass().getName() + " has no @Subscribe method to register");
    }
    return List.copyOf(methods);
  }

  private static ListenerMethod listenerMethod(
      MethodHandles.Lookup lookup, Object listener, Method method, Subscribe subscribe) {
    String name = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    if (Modifier.isStatic(method.getModifiers())) {
      throw new IllegalArgumentException(name + ": an event handler cannot be static");
    }
    if (method.getParameterCount() != 1 || method.getParameterTypes()[0].isPrimitive()) {
      throw new IllegalArgumentException(
          name + ": an event handler takes exactly one parameter, the event");
    }
    Class<?> returnType = method.getReturnType();
    boolean async = CompletionStage.class.isAssignableFrom(returnType);
    if (returnType != void.class && !async) {
      throw new IllegalArgumentException(
          name
              + ": an event handler returns void or a CompletionStage, not "
              + returnType.getName());
    }
    if (subscribe.timeoutSeconds() < 1) {
      throw new IllegalArgumentException(name + ": timeoutSeconds must be at least 1");
    }
    MethodHandle handle;
    try {
      method.setAccessible(true);
      handle = lookup.unreflect(method).bindTo(listener);
    } catch (IllegalAccessException | RuntimeException e) {
      throw new IllegalArgumentException(name + ": cannot be called by the proxy", e);
    }
    Invoker invoker;
    if (async) {
      MethodHandle exact =
          handle.asType(MethodType.methodType(CompletionStage.class, Object.class));
      invoker = event -> (CompletionStage<?>) exact.invokeExact(event);
    } else {
      MethodHandle exact = handle.asType(MethodType.methodType(void.class, Object.class));
      invoker =
          event -> {
            exact.invokeExact(event);
            return null;
          };
    }
    return new ListenerMethod(
        method.getParameterTypes()[0],
        subscribe.priority(),
        subscribe.timeoutSeconds(),
        name,
        invoker);
  }

  /**
   * A handler method found on a listener.
   *
   * @param type the event type
   * @param priority its priority
   * @param timeoutSeconds its time-out
   * @param name its name, for logs
   * @param invoker calls it on the listener
   */
  record ListenerMethod(
      Class<?> type, int priority, int timeoutSeconds, String name, Invoker invoker) {}

  private void remove(Handler handler) {
    List<Handler> handlers = registered.get(handler.type);
    if (handlers != null) {
      handlers.remove(handler);
    }
    invalidate();
  }

  private void invalidate() {
    generation.incrementAndGet();
    resolved.clear();
  }

  private Handler[] handlersFor(Class<?> eventClass) {
    Handler[] cached = resolved.get(eventClass);
    if (cached != null) {
      return cached;
    }
    long before = generation.get();
    List<Handler> found = new ArrayList<>();
    for (Class<?> type : supertypes(eventClass)) {
      List<Handler> handlers = registered.get(type);
      if (handlers != null) {
        found.addAll(handlers);
      }
    }
    found.sort(ORDER);
    Handler[] array = found.isEmpty() ? NO_HANDLERS : found.toArray(NO_HANDLERS);
    // A registration change during the lookup may have been missed: cache only if there was none.
    if (generation.get() == before) {
      resolved.put(eventClass, array);
    }
    return array;
  }

  private static Set<Class<?>> supertypes(Class<?> eventClass) {
    Set<Class<?>> types = new LinkedHashSet<>();
    Deque<Class<?>> pending = new ArrayDeque<>();
    pending.add(eventClass);
    while (!pending.isEmpty()) {
      Class<?> type = pending.poll();
      if (!types.add(type)) {
        continue;
      }
      Class<?> superclass = type.getSuperclass();
      if (superclass != null) {
        pending.add(superclass);
      }
      pending.addAll(Arrays.asList(type.getInterfaces()));
    }
    return types;
  }

  // ---------------------------------------------------------------------------
  // Shutdown
  // ---------------------------------------------------------------------------

  /** Waits a bounded time for events being delivered, then stops the bus. */
  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        logger.warn("Events were still being delivered after {} s", SHUTDOWN_GRACE_SECONDS);
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
    timer.shutdownNow();
  }

  // ---------------------------------------------------------------------------
  // Handler
  // ---------------------------------------------------------------------------

  /** A registered handler; also its own subscription. */
  final class Handler implements Subscription {

    final Class<?> type;
    final int priority;
    final long order;
    final int timeoutSeconds;
    private final String owner;
    private final String name;
    private final @Nullable ClassLoader contextLoader;
    private final Invoker invoker;
    private volatile boolean active = true;

    Handler(
        Class<?> type,
        int priority,
        long order,
        int timeoutSeconds,
        String owner,
        String name,
        @Nullable ClassLoader contextLoader,
        Invoker invoker) {
      this.type = type;
      this.priority = priority;
      this.order = order;
      this.timeoutSeconds = timeoutSeconds;
      this.owner = owner;
      this.name = name;
      this.contextLoader = contextLoader;
      this.invoker = invoker;
    }

    @Nullable CompletionStage<?> invoke(Object event) throws Throwable {
      if (contextLoader == null) {
        return invoker.invoke(event);
      }
      Thread thread = Thread.currentThread();
      ClassLoader previous = thread.getContextClassLoader();
      thread.setContextClassLoader(contextLoader);
      try {
        return invoker.invoke(event);
      } finally {
        thread.setContextClassLoader(previous);
      }
    }

    @Override
    public boolean isActive() {
      return active;
    }

    @Override
    public void unsubscribe() {
      if (active) {
        active = false;
        remove(this);
      }
    }

    @Override
    public String toString() {
      return "Event handler " + name + " of " + owner;
    }
  }
}
