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
package dev.warp.proxy.transfer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The tickets used on this proxy within their lifetime, so that each is used once. Bounded: past
 * {@code capacity} live entries, a new ticket is refused rather than an old one forgotten, which
 * would let it be replayed. Entries go once their ticket expired, swept at most once a second.
 * Thread-safe and lock-free: logins run on every event loop.
 */
final class SingleUse {

  /** What using a ticket did. */
  enum Outcome {
    /** First use: the ticket is now spent. */
    FIRST,
    /** Spent already. */
    REPLAYED,
    /** No room to remember it: refused. */
    FULL
  }

  private record Nonce(long high, long low) {}

  private final ConcurrentHashMap<Nonce, Long> spent = new ConcurrentHashMap<>();
  private final int capacity;
  private final AtomicLong nextSweep = new AtomicLong();

  SingleUse(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Spends a ticket.
   *
   * @param high the first half of its nonce
   * @param low the second half of its nonce
   * @param forgetAfterMillis when it can no longer be used anyway: its expiry, skew included
   * @param nowMillis the wall clock
   * @return whether it was spent now, before, or cannot be remembered
   */
  Outcome use(long high, long low, long forgetAfterMillis, long nowMillis) {
    long sweep = nextSweep.get();
    if ((nowMillis >= sweep || spent.size() >= capacity)
        && nextSweep.compareAndSet(sweep, nowMillis + 1_000)) {
      spent.values().removeIf(expiry -> expiry < nowMillis);
    }
    if (spent.size() >= capacity) {
      return Outcome.FULL;
    }
    return spent.putIfAbsent(new Nonce(high, low), forgetAfterMillis) == null
        ? Outcome.FIRST
        : Outcome.REPLAYED;
  }

  /**
   * Returns how many tickets are remembered.
   *
   * @return the count, expired ones not yet swept included
   */
  int size() {
    return spent.size();
  }
}
