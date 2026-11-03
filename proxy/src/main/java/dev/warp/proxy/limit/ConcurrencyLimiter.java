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
package dev.warp.proxy.limit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how many operations each key may have in progress at once, such as logins per address.
 *
 * <p>A key is in the map only while it has operations in progress, so the map holds at most one
 * entry per open connection and needs no expiry. Each update is atomic for its key; counts are
 * small boxed integers, which Java caches, so acquiring and releasing allocate nothing beyond the
 * boxed key.
 */
final class ConcurrencyLimiter {

  private final int maxPerKey;
  private final ConcurrentHashMap<Long, Integer> inProgress = new ConcurrentHashMap<>();

  /**
   * Creates a limiter.
   *
   * @param maxPerKey the most operations a key may have in progress; at least 1
   */
  ConcurrencyLimiter(int maxPerKey) {
    if (maxPerKey < 1) {
      throw new IllegalArgumentException("maxPerKey must be at least 1, got " + maxPerKey);
    }
    this.maxPerKey = maxPerKey;
  }

  /**
   * Starts an operation of {@code key} if it has fewer than the maximum in progress. Every
   * successful call must be followed by exactly one {@link #release(long)}.
   *
   * @param key the key
   * @return {@code true} if the operation may start
   */
  boolean tryAcquire(long key) {
    return tryAcquire(key, maxPerKey);
  }

  /**
   * Starts an operation of {@code key} if it has fewer than {@code max} in progress: the maximum of
   * the moment, such as a stricter one during an attack. Every successful call must be followed by
   * exactly one {@link #release(long)}.
   *
   * @param key the key
   * @param max the most operations the key may have in progress now; at least 1
   * @return {@code true} if the operation may start
   */
  boolean tryAcquire(long key, int max) {
    int count = inProgress.merge(key, 1, Integer::sum);
    if (count > max) {
      release(key);
      return false;
    }
    return true;
  }

  /**
   * Ends an operation of {@code key} started by {@link #tryAcquire(long)}.
   *
   * @param key the key
   */
  void release(long key) {
    inProgress.computeIfPresent(key, (k, count) -> count == 1 ? null : count - 1);
  }

  /** Returns how many keys have operations in progress, for tests. */
  int keys() {
    return inProgress.size();
  }
}
