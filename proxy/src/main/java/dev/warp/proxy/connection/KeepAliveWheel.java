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
package dev.warp.proxy.connection;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.channel.EventLoop;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the keep-alive tasks of every player of one event loop from a single timer: one tick every
 * {@value #TICK_MS} ms that serves the players whose turn it is, each of them once every {@value
 * #INTERVAL_MS} ms.
 *
 * <p>A scheduled task per player wakes the event loop once per player and per interval, at a moment
 * of its own: with thousands of connections at rest the loop does little else than wake up, send
 * one keep-alive, and go back to sleep, and the answers come back one by one as well. The wheel
 * wakes the loop once a second whatever the number of players, sends the keep-alives due in one
 * pass, and the answers arrive together. A player costs a {@link Seat} instead of a scheduled task
 * in the loop's timer queue.
 *
 * <p>The wheel has {@value #SLOTS} slots, one per tick of an interval. A player joins the slot that
 * was served last, so its first turn comes one interval later at most (and one tick less at least),
 * and the next ones exactly every interval. The timer only runs while the wheel has players.
 *
 * <p>Not thread-safe: a wheel belongs to its event loop, and every method but {@link #of} is called
 * from it.
 */
final class KeepAliveWheel {

  private static final Logger logger = LoggerFactory.getLogger(KeepAliveWheel.class);

  /** Time between two ticks. */
  static final long TICK_MS = 1_000;

  /** Slots of the wheel: the ticks between two turns of a player. */
  static final int SLOTS = 15;

  /** Time between two turns of a player. */
  static final long INTERVAL_MS = SLOTS * TICK_MS;

  /**
   * The wheel of each event loop that had players. Neither the loop nor its wheel is kept alive by
   * this cache: a wheel with players is held by its timer, in the loop's own queue.
   */
  private static final Cache<EventLoop, KeepAliveWheel> WHEELS =
      Caffeine.newBuilder().weakKeys().weakValues().build();

  private final EventLoop loop;

  /** The first seat of each slot: seats of a slot form a doubly linked list. */
  private final @Nullable Seat[] heads = new Seat[SLOTS];

  /** The slot the next tick serves. */
  private int cursor;

  private int seats;

  /** The seat the running tick serves next, kept right when that seat leaves meanwhile. */
  private @Nullable Seat upcoming;

  private @Nullable ScheduledFuture<?> timer;

  private KeepAliveWheel(EventLoop loop) {
    this.loop = loop;
  }

  /**
   * Returns the wheel of an event loop, creating it on first use. Safe from any thread.
   *
   * @param loop the event loop whose players the wheel serves
   * @return its wheel
   */
  static KeepAliveWheel of(EventLoop loop) {
    return WHEELS.get(loop, KeepAliveWheel::new);
  }

  /**
   * Gives a task a seat: the task runs on the event loop every {@value #INTERVAL_MS} ms, for the
   * first time within that delay, until the seat is {@linkplain Seat#leave() left}.
   *
   * @param task the task, which may leave its own seat or any other, and take new ones
   * @return the seat
   */
  Seat join(Runnable task) {
    int slot = (cursor + SLOTS - 1) % SLOTS;
    Seat seat = new Seat(this, task, slot);
    Seat head = heads[slot];
    seat.next = head;
    if (head != null) {
      head.previous = seat;
    }
    heads[slot] = seat;
    if (seats++ == 0) {
      timer = loop.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }
    return seat;
  }

  /** How many seats are taken. */
  int seats() {
    return seats;
  }

  private void leave(Seat seat) {
    if (seat.slot < 0) {
      return;
    }
    if (upcoming == seat) {
      upcoming = seat.next;
    }
    Seat previous = seat.previous;
    Seat next = seat.next;
    if (previous != null) {
      previous.next = next;
    } else {
      heads[seat.slot] = next;
    }
    if (next != null) {
      next.previous = previous;
    }
    seat.previous = null;
    seat.next = null;
    seat.slot = -1;
    ScheduledFuture<?> running = timer;
    if (--seats == 0 && running != null) {
      running.cancel(false);
      timer = null;
    }
  }

  /**
   * Serves one slot. Seats taken during the tick go to the head of the slot being served, behind
   * the pass, and wait for their turn; a seat left during the tick is not served.
   */
  private void tick() {
    int slot = cursor;
    cursor = (slot + 1) % SLOTS;
    Seat seat = heads[slot];
    while (seat != null) {
      upcoming = seat.next;
      try {
        seat.task.run();
      } catch (RuntimeException e) {
        // One player's failure must not stop the keep-alives of every other player of the loop.
        logger.error("A keep-alive task failed", e);
      }
      seat = upcoming;
    }
    upcoming = null;
  }

  /** A task's place in a wheel. */
  static final class Seat {

    private final KeepAliveWheel wheel;
    private final Runnable task;

    /** The slot of the seat, negative once it was left. */
    private int slot;

    private @Nullable Seat previous;
    private @Nullable Seat next;

    private Seat(KeepAliveWheel wheel, Runnable task, int slot) {
      this.wheel = wheel;
      this.task = task;
      this.slot = slot;
    }

    /** Leaves the seat: the task no longer runs. Does nothing the second time. */
    void leave() {
      wheel.leave(this);
    }
  }
}
