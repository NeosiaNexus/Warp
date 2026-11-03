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
package dev.warp.proxy.messaging;

import dev.warp.proxy.server.BackendServer;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The {@code Forward} messages waiting for a player to carry them to a server nobody plays on.
 *
 * <p>Plugin messages only travel over a player's connection, so a server without players cannot be
 * reached. BungeeCord keeps such messages until a player joins the server, however long that takes,
 * and delivers whatever piled up meanwhile; Velocity drops them at once. Warp keeps them for a
 * bounded time ({@code forward-hold}), in order, so that the common "send the data, then the
 * player" sequence works on an empty server, but a message never arrives stale: one that waited too
 * long is dropped and the drop is logged.
 *
 * <p>Bounded: at most {@value #MAX_PER_SERVER} messages per server and {@value #MAX_BYTES} bytes in
 * all, so that a backend flooding empty servers cannot exhaust memory. Thread-safe: messages are
 * added and a server's queue forgotten under that server's map entry, so that no message lands in a
 * queue that was just forgotten; delivery and expiry may run on any thread.
 */
final class HeldForwards {

  /** The most messages a server keeps. */
  static final int MAX_PER_SERVER = 64;

  /** The most bytes held in all, counted per message and server. */
  static final long MAX_BYTES = 4L * 1024 * 1024;

  /**
   * A message waiting.
   *
   * @param data the payload to deliver, shared, never modified
   * @param from the server that sent it, for the logs
   * @param deadline when it expires, on the hold's clock
   */
  @SuppressWarnings("ArrayRecordComponent") // data is shared read-only
  record Held(byte[] data, String from, long deadline) {}

  /**
   * One server's messages.
   *
   * @param messages the messages, oldest first
   * @param size how many there are
   */
  private record Pending(Queue<Held> messages, AtomicInteger size) {}

  /** Hears about the messages dropped, for the logs. */
  interface Drops {
    /**
     * Reports messages dropped.
     *
     * @param server the server they were held for
     * @param from the server that sent the last of them
     * @param count how many
     * @param why why, e.g. {@code "nobody played there in time"}
     */
    void dropped(String server, String from, int count, String why);
  }

  private final Map<BackendServer, Pending> servers = new ConcurrentHashMap<>();
  private final AtomicLong bytes = new AtomicLong();
  private final long holdNanos;
  private final LongSupplier clock;
  private final Drops drops;

  /**
   * Creates an empty hold.
   *
   * @param holdNanos how long a message waits
   * @param clock a monotonic clock in nanoseconds
   * @param drops told about every message dropped
   */
  HeldForwards(long holdNanos, LongSupplier clock, Drops drops) {
    this.holdNanos = holdNanos;
    this.clock = clock;
    this.drops = drops;
  }

  /**
   * Holds a message for a server.
   *
   * @param server the server nobody plays on
   * @param data the payload
   * @param from the server that sent it
   * @return {@code false} if it was refused: a limit is reached
   */
  boolean hold(BackendServer server, byte[] data, String from) {
    if (bytes.addAndGet(data.length) > MAX_BYTES) {
      bytes.addAndGet(-data.length);
      return false;
    }
    Held held = new Held(data, from, clock.getAsLong() + holdNanos);
    boolean[] added = new boolean[1];
    servers.compute(
        server,
        (_, existing) -> {
          Pending pending =
              existing != null
                  ? existing
                  : new Pending(new ConcurrentLinkedQueue<>(), new AtomicInteger());
          if (pending.size().get() < MAX_PER_SERVER) {
            pending.size().incrementAndGet();
            pending.messages().add(held);
            added[0] = true;
          }
          return pending;
        });
    if (!added[0]) {
      bytes.addAndGet(-data.length);
    }
    return added[0];
  }

  /**
   * Delivers a server's messages that have not expired, in order, now that a player can carry them.
   * A message the carrier refuses (it left meanwhile) is dropped.
   *
   * @param server the server
   * @param carrier sends one message, and says whether it could
   * @return how many were delivered
   */
  int deliver(BackendServer server, Predicate<byte[]> carrier) {
    Pending pending = servers.get(server);
    if (pending == null) {
      return 0;
    }
    long now = clock.getAsLong();
    int delivered = 0;
    int expired = 0;
    int refused = 0;
    String from = "";
    Held held;
    while ((held = pending.messages().poll()) != null) {
      release(pending, held);
      from = held.from();
      if (now - held.deadline() > 0) {
        expired++;
      } else if (carrier.test(held.data())) {
        delivered++;
      } else {
        refused++;
      }
    }
    forgetIfEmpty(server);
    if (expired > 0) {
      drops.dropped(server.name(), from, expired, "nobody played there in time");
    }
    if (refused > 0) {
      drops.dropped(server.name(), from, refused, "the player who arrived left at once");
    }
    return delivered;
  }

  /** Drops the messages that waited too long, and forgets the servers left with none. */
  void expire() {
    long now = clock.getAsLong();
    for (var entry : servers.entrySet()) {
      Pending pending = entry.getValue();
      int expired = 0;
      String from = "";
      Held head;
      while ((head = pending.messages().peek()) != null && now - head.deadline() > 0) {
        if (pending.messages().remove(head)) {
          release(pending, head);
          expired++;
          from = head.from();
        }
      }
      forgetIfEmpty(entry.getKey());
      if (expired > 0) {
        drops.dropped(entry.getKey().name(), from, expired, "nobody played there in time");
      }
    }
  }

  /**
   * Returns how many messages a server keeps.
   *
   * @param server the server
   * @return the count
   */
  int held(BackendServer server) {
    Pending pending = servers.get(server);
    return pending == null ? 0 : pending.size().get();
  }

  /**
   * Returns how many bytes are held in all.
   *
   * @return the count
   */
  long bytes() {
    return bytes.get();
  }

  private void forgetIfEmpty(BackendServer server) {
    servers.computeIfPresent(server, (_, pending) -> pending.messages().isEmpty() ? null : pending);
  }

  private void release(Pending pending, Held held) {
    pending.size().decrementAndGet();
    bytes.addAndGet(-held.data().length);
  }
}
