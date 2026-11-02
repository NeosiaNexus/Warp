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
package dev.warp.proxy.antibot;

import java.util.SplittableRandom;
import java.util.concurrent.locks.StampedLock;

import org.jspecify.annotations.Nullable;

/**
 * The trusted identities in memory: a hash table with open addressing and linear probing over
 * primitive arrays, about 35 bytes per identity and no object per entry, so that 250,000 of them
 * cost the garbage collector nothing.
 *
 * <p>One thread writes (the store's), under a {@link StampedLock} it never has to wait for; any
 * thread reads, with optimistic reads that take no lock and retry if a write raced them. A reader
 * falls back to the read lock only after several races in a row, which a write of a few
 * microseconds makes rare.
 *
 * <p>Times are minutes since the epoch; a slot whose last-seen minute is 0 is empty. When the table
 * holds its capacity, inserting evicts the least recently seen of 8 slots drawn at random (the
 * sampled LRU of Redis). Deletion shifts the following entries back, so that probing never meets a
 * tombstone.
 */
final class VerifiedTable {

  /** The slots sampled to find what to evict. */
  static final int EVICTION_SAMPLES = 8;

  /** Optimistic attempts before a reader takes the read lock. */
  private static final int OPTIMISTIC_ATTEMPTS = 8;

  private final int capacity;
  private final int slots;
  private final long[] high;
  private final long[] low;
  private final int[] verifiedAt;
  private final int[] lastSeen;
  private final byte[] method;
  private final StampedLock lock = new StampedLock();
  private final SplittableRandom random = new SplittableRandom();
  private volatile int size;

  /**
   * One identity, as read.
   *
   * @param verifiedAt when it was first trusted, in minutes since the epoch
   * @param lastSeen when it was last seen, in minutes since the epoch
   * @param method the ordinal of its {@link dev.warp.api.antibot.VerificationMethod}
   */
  record Entry(int verifiedAt, int lastSeen, byte method) {}

  /** Receives the entries of {@link #forEach}. */
  @FunctionalInterface
  interface EntryConsumer {

    /**
     * Called for one entry.
     *
     * @param high the key's high bits
     * @param low the key's low bits
     * @param verifiedAt when it was first trusted, in minutes
     * @param lastSeen when it was last seen, in minutes
     * @param method the ordinal of its method
     */
    void accept(long high, long low, int verifiedAt, int lastSeen, byte method);
  }

  /**
   * Creates an empty table.
   *
   * @param capacity the most identities it holds
   */
  VerifiedTable(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1");
    }
    this.capacity = capacity;
    // A load factor of 3/4 at most keeps linear probing short.
    this.slots = (int) Math.min(Integer.MAX_VALUE - 8, capacity * 4L / 3 + 1);
    this.high = new long[slots];
    this.low = new long[slots];
    this.verifiedAt = new int[slots];
    this.lastSeen = new int[slots];
    this.method = new byte[slots];
  }

  // ---------------------------------------------------------------------------
  // Reads, from any thread
  // ---------------------------------------------------------------------------

  /**
   * Looks a key up.
   *
   * @param keyHigh the key's high bits
   * @param keyLow the key's low bits
   * @return its entry, expired or not, or {@code null}
   */
  @Nullable Entry find(long keyHigh, long keyLow) {
    for (int attempt = 0; ; attempt++) {
      boolean locked = attempt >= OPTIMISTIC_ATTEMPTS;
      long stamp = locked ? lock.readLock() : lock.tryOptimisticRead();
      try {
        int found = -1;
        int foundVerified = 0;
        int foundSeen = 0;
        byte foundMethod = 0;
        int i = home(keyLow);
        for (int probes = 0; probes < slots; probes++) {
          int seen = lastSeen[i];
          if (seen == 0) {
            break;
          }
          if (low[i] == keyLow && high[i] == keyHigh) {
            found = i;
            foundVerified = verifiedAt[i];
            foundSeen = seen;
            foundMethod = method[i];
            break;
          }
          i = next(i);
        }
        if (locked || (stamp != 0 && lock.validate(stamp))) {
          return found < 0 ? null : new Entry(foundVerified, foundSeen, foundMethod);
        }
      } finally {
        if (locked) {
          lock.unlockRead(stamp);
        }
      }
      Thread.onSpinWait();
    }
  }

  /**
   * Returns how many identities the table holds, expired ones not yet swept included.
   *
   * @return the count
   */
  int size() {
    return size;
  }

  /**
   * Returns the most identities the table holds.
   *
   * @return the capacity
   */
  int capacity() {
    return capacity;
  }

  // ---------------------------------------------------------------------------
  // Writes, from the store's thread only
  // ---------------------------------------------------------------------------

  /**
   * Inserts or updates an identity, evicting one if the table is full.
   *
   * @param keyHigh the key's high bits
   * @param keyLow the key's low bits
   * @param verified when it was first trusted, in minutes; at least 1
   * @param seen when it was last seen, in minutes; at least 1
   * @param methodOrdinal the ordinal of its method
   * @return {@code true} if it was not in the table
   */
  boolean put(long keyHigh, long keyLow, int verified, int seen, byte methodOrdinal) {
    if (verified < 1 || seen < 1) {
      throw new IllegalArgumentException("minutes must be positive");
    }
    long stamp = lock.writeLock();
    try {
      int i = slotOf(keyHigh, keyLow);
      if (lastSeen[i] != 0) {
        verifiedAt[i] = verified;
        lastSeen[i] = seen;
        method[i] = methodOrdinal;
        return false;
      }
      if (size >= capacity) {
        deleteAt(leastRecentlySeenSample());
        i = slotOf(keyHigh, keyLow);
      }
      high[i] = keyHigh;
      low[i] = keyLow;
      verifiedAt[i] = verified;
      lastSeen[i] = seen;
      method[i] = methodOrdinal;
      size = size + 1;
      return true;
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * Removes an identity.
   *
   * @param keyHigh the key's high bits
   * @param keyLow the key's low bits
   * @return {@code true} if it was in the table
   */
  boolean remove(long keyHigh, long keyLow) {
    long stamp = lock.writeLock();
    try {
      int i = slotOf(keyHigh, keyLow);
      if (lastSeen[i] == 0) {
        return false;
      }
      deleteAt(i);
      return true;
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * Removes the identities last seen before {@code cutoff} among {@code count} slots from {@code
   * from}, so that a sweep spreads over many calls.
   *
   * @param from the first slot
   * @param count how many slots to look at
   * @param cutoff the first minute still valid
   * @return the slot to start the next sweep from
   */
  int sweep(int from, int count, int cutoff) {
    int start = Math.floorMod(from, slots);
    int end = (int) Math.min(slots, (long) start + count);
    long stamp = lock.writeLock();
    try {
      int i = start;
      while (i < end) {
        int seen = lastSeen[i];
        if (seen != 0 && seen < cutoff) {
          deleteAt(i); // the next entry may have shifted into i: look at it again
        } else {
          i++;
        }
      }
    } finally {
      lock.unlockWrite(stamp);
    }
    return end >= slots ? 0 : end;
  }

  /**
   * Gives every entry to {@code consumer}. Called from the store's thread, which no other writes,
   * so that the entries are consistent; readers go on meanwhile.
   *
   * @param consumer the consumer
   */
  void forEach(EntryConsumer consumer) {
    for (int i = 0; i < slots; i++) {
      int seen = lastSeen[i];
      if (seen != 0) {
        consumer.accept(high[i], low[i], verifiedAt[i], seen, method[i]);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** The slot holding the key, or the empty slot where it would go. */
  private int slotOf(long keyHigh, long keyLow) {
    int i = home(keyLow);
    while (lastSeen[i] != 0 && !(low[i] == keyLow && high[i] == keyHigh)) {
      i = next(i);
    }
    return i;
  }

  /** The first slot of a key: its low bits are uniformly random, being half an HMAC. */
  private int home(long keyLow) {
    return (int) (((keyLow >>> 32) * slots) >>> 32);
  }

  private int next(int i) {
    return i + 1 == slots ? 0 : i + 1;
  }

  /** The least recently seen of {@value #EVICTION_SAMPLES} occupied slots drawn at random. */
  private int leastRecentlySeenSample() {
    int victim = -1;
    for (int sample = 0; sample < EVICTION_SAMPLES; sample++) {
      int i = random.nextInt(slots);
      while (lastSeen[i] == 0) {
        i = next(i);
      }
      if (victim < 0 || lastSeen[i] < lastSeen[victim]) {
        victim = i;
      }
    }
    return victim;
  }

  /** Empties a slot, shifting back the entries that probed past it (no tombstones). */
  private void deleteAt(int slot) {
    int hole = slot;
    int i = slot;
    while (true) {
      i = next(i);
      if (lastSeen[i] == 0) {
        break;
      }
      int home = home(low[i]);
      // The entry at i stays if its home lies cyclically in (hole, i].
      boolean stays = hole <= i ? hole < home && home <= i : hole < home || home <= i;
      if (!stays) {
        high[hole] = high[i];
        low[hole] = low[i];
        verifiedAt[hole] = verifiedAt[i];
        lastSeen[hole] = lastSeen[i];
        method[hole] = method[i];
        hole = i;
      }
    }
    lastSeen[hole] = 0;
    high[hole] = 0;
    low[hole] = 0;
    verifiedAt[hole] = 0;
    method[hole] = 0;
    size = size - 1;
  }
}
