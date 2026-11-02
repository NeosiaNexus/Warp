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

import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.Verification;
import dev.warp.api.antibot.VerificationMethod;
import dev.warp.api.antibot.VerifiedIdentities;
import dev.warp.api.antibot.VerifiedKey;
import dev.warp.api.antibot.VerifiedStore;
import dev.warp.api.event.Subscription;
import dev.warp.api.event.antibot.IdentityVerifiedEvent;
import dev.warp.proxy.config.AntiBotConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The store of trusted identities (section 9.5 of the anti-bot research): keyed hashes in a bounded
 * in-memory table, kept on disk as a snapshot and a journal, forgotten a while after their last
 * join, and optionally shared with other proxies through a plugin's {@link VerifiedStore}.
 *
 * <p>Reads ({@link #find}) are lock-free and answer at once, from any thread: a login looks its
 * identity up on its event loop. Every change goes to the store's own thread, which applies it to
 * the table, appends it to the journal and tells the shared store; the disk is never touched from
 * an event loop. The changes waiting for that thread are bounded: beyond {@value
 * #MAX_PENDING_CHANGES}, a refresh of a last-seen time is dropped, and a new identity refused.
 *
 * <p>Every second the thread writes and forces the journal, and sweeps a slice of the table for
 * expired identities; once the journal holds more changes than the table holds identities (and at
 * least {@value #SNAPSHOT_AFTER_CHANGES}), it writes a snapshot and empties the journal, as it does
 * on shutdown.
 */
public final class IdentityStore implements VerifiedIdentities, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(IdentityStore.class);

  /** Changes waiting for the store's thread, at most. */
  static final int MAX_PENDING_CHANGES = 65_536;

  /** The journal holds at least this many changes before a snapshot replaces it. */
  static final long SNAPSHOT_AFTER_CHANGES = 100_000;

  /** The table is swept for expired identities in about this many seconds. */
  private static final int SWEEP_SECONDS = 600;

  private static final VerificationMethod[] METHODS = VerificationMethod.values();

  private final AntiBotConfig.Verified config;
  private final IdentityKeys keys;
  private final VerifiedTable table;
  private final @Nullable VerifiedFiles files;
  private final LongSupplier millisClock;
  private final Consumer<IdentityVerifiedEvent> verified;
  private final ScheduledExecutorService thread;
  private final AtomicInteger pendingChanges = new AtomicInteger();
  private final LongAdder droppedChanges = new LongAdder();
  private final AtomicReference<@Nullable VerifiedStore> shared = new AtomicReference<>();
  private final int expiryMinutes;
  private final int sweepSlotsPerSecond;
  private int sweepFrom;
  private boolean diskFailing;

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Opens a store, loading what is on disk.
   *
   * @param config the {@code anti-bot.verified} section
   * @param keys the keys identities are stored under
   * @param directory where the snapshot and the journal live, or {@code null} to keep the store in
   *     memory only
   * @param millisClock the wall clock, in milliseconds since the epoch
   * @param verified told of every identity the store starts trusting, on the store's thread
   * @param thread the store's own thread, which the store shuts down on {@link #close()}
   * @throws IOException if the files cannot be opened or read
   */
  IdentityStore(
      AntiBotConfig.Verified config,
      IdentityKeys keys,
      @Nullable Path directory,
      LongSupplier millisClock,
      Consumer<IdentityVerifiedEvent> verified,
      ScheduledExecutorService thread)
      throws IOException {
    this.config = config;
    this.keys = keys;
    this.table = new VerifiedTable(config.capacity());
    this.millisClock = millisClock;
    this.verified = verified;
    this.thread = thread;
    this.expiryMinutes =
        (int) Math.min(Integer.MAX_VALUE, config.expireAfterLastJoin().toMinutes());
    this.sweepSlotsPerSecond = Math.max(64, config.capacity() * 4 / 3 / SWEEP_SECONDS + 1);
    if (directory != null) {
      VerifiedFiles opened = new VerifiedFiles(directory);
      VerifiedFiles.Loaded loaded = opened.load(table, cutoff());
      logger.info(
          "Anti-bot: {} trusted identities loaded from {} ({} from the snapshot, {} changes from"
              + " the journal{})",
          table.size(),
          directory,
          loaded.snapshotEntries(),
          loaded.journalEntries(),
          loaded.damaged() ? ", the snapshot being damaged" : "");
      this.files = opened;
    } else {
      this.files = null;
    }
    var _ = thread.scheduleWithFixedDelay(this::everySecond, 1, 1, TimeUnit.SECONDS);
  }

  /**
   * Creates the store's own thread: a single daemon thread, {@code warp-antibot-store}.
   *
   * @return the thread
   */
  static ScheduledExecutorService newThread() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          Thread t = new Thread(task, "warp-antibot-store");
          t.setDaemon(true);
          return t;
        });
  }

  /** Writes the journal, then a snapshot, and stops the store's thread. Waits at most 10 s. */
  @Override
  public void close() {
    try {
      var _ =
          thread.submit(
              () -> {
                VerifiedFiles disk = files;
                if (disk == null) {
                  return;
                }
                try {
                  disk.snapshot(table, cutoff());
                  disk.close();
                } catch (IOException | RuntimeException e) {
                  logger.warn("Anti-bot: could not save the trusted identities on shutdown", e);
                }
              });
    } catch (RejectedExecutionException e) {
      return; // already closed
    }
    thread.shutdown();
    try {
      if (!thread.awaitTermination(10, TimeUnit.SECONDS)) {
        logger.warn("Anti-bot: the store did not finish saving within 10 s");
        thread.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      thread.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * Looks a key up in this proxy's store, from any thread, without blocking.
   *
   * @param key the key
   * @return what the store remembers of it, or {@code null} if it does not trust it, or forgot it
   */
  public @Nullable Verification find(VerifiedKey key) {
    VerifiedTable.Entry entry = table.find(key.high(), key.low());
    if (entry == null || entry.lastSeen() < cutoff()) {
      return null;
    }
    return verification(entry.verifiedAt(), entry.lastSeen(), entry.method());
  }

  /**
   * Returns whether this proxy's store trusts a key.
   *
   * @param key the key
   * @return {@code true} if it does
   */
  public boolean knows(VerifiedKey key) {
    return find(key) != null;
  }

  /**
   * Looks a key up in this proxy's store, then in the shared store if there is one, waiting for it
   * at most {@code anti-bot.verified.shared-store-timeout}. An identity found there is copied here.
   *
   * @param key the key
   * @return completed with what is known of it; never exceptionally
   */
  public CompletableFuture<Optional<Verification>> findEverywhere(VerifiedKey key) {
    Verification local = find(key);
    VerifiedStore remote = shared.get();
    if (local != null || remote == null) {
      return CompletableFuture.completedFuture(Optional.ofNullable(local));
    }
    CompletableFuture<Optional<Verification>> answer;
    try {
      answer = remote.load(key);
    } catch (RuntimeException e) {
      logger.debug("Anti-bot: the shared store failed to look up {}", key, e);
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return answer
        .orTimeout(config.sharedStoreTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .handle(
            (found, failure) -> {
              if (failure != null || found == null) {
                return Optional.<Verification>empty();
              }
              found.ifPresent(v -> submit(() -> copyShared(key, v)));
              return found;
            });
  }

  @Override
  public Optional<Verification> check(Identity identity) {
    return Optional.ofNullable(find(keys.key(identity)));
  }

  @Override
  public int size() {
    return table.size();
  }

  @Override
  public int capacity() {
    return table.capacity();
  }

  /**
   * Returns whether a plugin shares the store with other proxies.
   *
   * @return {@code true} if a shared store is registered
   */
  public boolean isShared() {
    return shared.get() != null;
  }

  /**
   * Returns how many changes were dropped because the store's thread was too far behind.
   *
   * @return the count since the start
   */
  public long droppedChanges() {
    return droppedChanges.sum();
  }

  /**
   * Returns the keys this store computes.
   *
   * @return the keys
   */
  public IdentityKeys keys() {
    return keys;
  }

  // ---------------------------------------------------------------------------
  // Changes
  // ---------------------------------------------------------------------------

  /**
   * Trusts a key now, or refreshes it if it is trusted already (its first trust and method kept).
   *
   * @param key the key
   * @param method how it came to be trusted, if it is new
   * @param identity the identity behind the key, for the event a new trust fires; {@code null} for
   *     none
   * @return completed with what the store remembers of it, once applied; exceptionally if the store
   *     is too far behind
   */
  public CompletableFuture<Verification> trust(
      VerifiedKey key, VerificationMethod method, @Nullable Identity identity) {
    CompletableFuture<Verification> done = new CompletableFuture<>();
    if (!submit(() -> done.complete(applyTrust(key, method, identity)))) {
      done.completeExceptionally(
          new IllegalStateException("the anti-bot store is too far behind to take more changes"));
    }
    return done;
  }

  /**
   * Refreshes the last-seen time of a key it trusts; does nothing for a key it does not.
   *
   * @param key the key
   */
  public void seen(VerifiedKey key) {
    var _ = submit(() -> applySeen(key));
  }

  /**
   * Stops trusting a key.
   *
   * @param key the key
   * @return completed with {@code true} if it was trusted, once applied
   */
  public CompletableFuture<Boolean> forget(VerifiedKey key) {
    CompletableFuture<Boolean> done = new CompletableFuture<>();
    if (!submit(() -> done.complete(applyForget(key)))) {
      done.completeExceptionally(
          new IllegalStateException("the anti-bot store is too far behind to take more changes"));
    }
    return done;
  }

  @Override
  public CompletableFuture<Verification> add(Identity identity) {
    return trust(keys.key(identity), VerificationMethod.PLUGIN, identity);
  }

  @Override
  public CompletableFuture<Boolean> remove(Identity identity) {
    return forget(keys.key(identity));
  }

  /**
   * Shares the store with other proxies through a plugin's store.
   *
   * @param store the shared store
   * @return the registration
   * @throws IllegalStateException if a store is shared already
   */
  public Subscription share(VerifiedStore store) {
    if (!shared.compareAndSet(null, store)) {
      throw new IllegalStateException("another plugin already shares the trusted identities");
    }
    return new Subscription() {
      @Override
      public boolean isActive() {
        return shared.get() == store;
      }

      @Override
      public void unsubscribe() {
        var _ = shared.compareAndSet(store, null);
      }
    };
  }

  // ---------------------------------------------------------------------------
  // On the store's thread
  // ---------------------------------------------------------------------------

  private boolean submit(Runnable change) {
    if (pendingChanges.incrementAndGet() > MAX_PENDING_CHANGES) {
      pendingChanges.decrementAndGet();
      droppedChanges.increment();
      return false;
    }
    try {
      thread.execute(
          () -> {
            pendingChanges.decrementAndGet();
            change.run();
          });
      return true;
    } catch (RejectedExecutionException e) {
      pendingChanges.decrementAndGet();
      return false;
    }
  }

  private Verification applyTrust(
      VerifiedKey key, VerificationMethod method, @Nullable Identity identity) {
    int now = nowMinute();
    VerifiedTable.Entry existing = table.find(key.high(), key.low());
    if (existing != null && existing.lastSeen() < cutoff()) {
      existing = null; // expired: trusted anew
    }
    boolean known = existing != null;
    int verifiedAt = existing != null ? existing.verifiedAt() : now;
    byte methodOrdinal = existing != null ? existing.method() : (byte) method.ordinal();
    int seen = Math.max(now, verifiedAt);
    table.put(key.high(), key.low(), verifiedAt, seen, methodOrdinal);
    journalPut(key, verifiedAt, seen, methodOrdinal);
    Verification result = verification(verifiedAt, seen, methodOrdinal);
    tellShared(store -> store.save(key, result));
    if (!known && identity != null) {
      verified.accept(new IdentityVerifiedEvent(identity, result));
    }
    return result;
  }

  private void applySeen(VerifiedKey key) {
    VerifiedTable.Entry existing = table.find(key.high(), key.low());
    if (existing == null || existing.lastSeen() < cutoff()) {
      return;
    }
    int now = Math.max(nowMinute(), existing.verifiedAt());
    if (now == existing.lastSeen()) {
      return; // already this minute: nothing to write
    }
    table.put(key.high(), key.low(), existing.verifiedAt(), now, existing.method());
    journalPut(key, existing.verifiedAt(), now, existing.method());
    Verification result = verification(existing.verifiedAt(), now, existing.method());
    tellShared(store -> store.save(key, result));
  }

  private boolean applyForget(VerifiedKey key) {
    boolean removed = table.remove(key.high(), key.low());
    VerifiedFiles disk = files;
    if (disk != null) {
      try {
        disk.appendRemove(key.high(), key.low());
      } catch (IOException e) {
        diskFailed(e);
      }
    }
    tellShared(store -> store.delete(key));
    return removed;
  }

  private void copyShared(VerifiedKey key, Verification found) {
    int verifiedAt = minute(found.verifiedAt());
    int seen = Math.max(minute(found.lastSeen()), verifiedAt);
    if (seen < cutoff() || verifiedAt < 1) {
      return;
    }
    byte method = (byte) found.method().ordinal();
    table.put(key.high(), key.low(), verifiedAt, seen, method);
    journalPut(key, verifiedAt, seen, method);
  }

  private void journalPut(VerifiedKey key, int verifiedAt, int seen, byte method) {
    VerifiedFiles disk = files;
    if (disk == null) {
      return;
    }
    try {
      disk.appendPut(key.high(), key.low(), verifiedAt, seen, method);
    } catch (IOException e) {
      diskFailed(e);
    }
  }

  private void tellShared(Consumer<VerifiedStore> call) {
    VerifiedStore store = shared.get();
    if (store == null) {
      return;
    }
    try {
      call.accept(store);
    } catch (RuntimeException e) {
      logger.debug("Anti-bot: the shared store failed", e);
    }
  }

  private void everySecond() {
    try {
      sweepFrom = table.sweep(sweepFrom, sweepSlotsPerSecond, cutoff());
      VerifiedFiles disk = files;
      if (disk == null) {
        return;
      }
      if (disk.journalEntries() >= Math.max(SNAPSHOT_AFTER_CHANGES, table.size())) {
        disk.snapshot(table, cutoff());
      } else {
        disk.flush();
      }
      if (diskFailing) {
        diskFailing = false;
        logger.info("Anti-bot: the trusted identities are saved to disk again");
      }
    } catch (IOException e) {
      diskFailed(e);
    } catch (RuntimeException e) {
      logger.error("Anti-bot: the store's periodic work failed", e);
    }
  }

  private void diskFailed(IOException e) {
    if (!diskFailing) {
      diskFailing = true;
      logger.warn(
          "Anti-bot: cannot save the trusted identities to disk; they stay in memory until it"
              + " works again",
          e);
    }
  }

  // ---------------------------------------------------------------------------
  // Time
  // ---------------------------------------------------------------------------

  private int nowMinute() {
    return (int) Math.max(1, millisClock.getAsLong() / 60_000);
  }

  private int cutoff() {
    return nowMinute() - expiryMinutes;
  }

  private static int minute(Instant instant) {
    return (int) Math.max(1, instant.toEpochMilli() / 60_000);
  }

  private static Verification verification(int verifiedAt, int lastSeen, byte method) {
    VerificationMethod known =
        method >= 0 && method < METHODS.length ? METHODS[method] : VerificationMethod.JOINED;
    return new Verification(
        Instant.ofEpochSecond(verifiedAt * 60L),
        Instant.ofEpochSecond(Math.max(lastSeen, verifiedAt) * 60L),
        known);
  }

  /**
   * Returns how long an identity stays trusted after its last join.
   *
   * @return the duration
   */
  public Duration expireAfterLastJoin() {
    return config.expireAfterLastJoin();
  }

  /**
   * Runs a task on the store's thread and waits for it, for tests.
   *
   * @param task the task
   */
  void drainForTests(Runnable task) {
    try {
      ExecutorService executor = thread;
      executor.submit(task).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
