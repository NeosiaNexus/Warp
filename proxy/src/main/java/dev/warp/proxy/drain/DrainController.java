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
package dev.warp.proxy.drain;

import dev.warp.api.event.player.PlayerDrainEvent.Decision;
import dev.warp.api.event.player.PlayerDrainEvent.Keep;
import dev.warp.api.event.player.PlayerDrainEvent.Kick;
import dev.warp.api.event.player.PlayerDrainEvent.Reason;
import dev.warp.api.event.player.PlayerDrainEvent.Transfer;
import dev.warp.api.lifecycle.DrainOutcome;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.TransferResult;
import dev.warp.proxy.config.DrainConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The drain's state machine: phase 0 announces the drain and waits for load balancers to notice,
 * phase 1 deals with every player connected, one at a time at the configured rate, phase 2 waits
 * for the players kept to leave, and phase 3 hands over to {@link DrainHost#shutdown}.
 *
 * <p>Dealing with a player means asking the plugins ({@link DrainHost#decide}), then transferring,
 * keeping or kicking it. A player arriving on a server while the proxy drains is dealt with the
 * same way, in phase 1 or 2. Only transfers and kicks are paced: each makes a client connect
 * elsewhere, and the next one waits {@code drain.transfer-interval}.
 *
 * <p>Every step runs on the scheduler, one at a time; {@link #start}, {@link #playerArrived},
 * {@link #loginRefused}, {@link #isDraining} and {@link #status} may be called from any thread.
 * Nothing blocks: decisions and transfers complete asynchronously, each within a time-out so that
 * neither a slow plugin nor a client that stopped reading can stall the drain.
 *
 * @param <P> the players
 */
public final class DrainController<P extends DrainCandidate> {

  private static final Logger logger = LoggerFactory.getLogger(DrainController.class);

  /** How often phase 2 checks whether the proxy is empty. */
  static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  /** How often phase 2 logs how many players are left. */
  static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(30);

  /** How long the plugins get to decide about a player before the default decision applies. */
  static final Duration DECISION_TIMEOUT = Duration.ofSeconds(30);

  /** How long a transfer may take to be written before the drain moves on to the next player. */
  static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(10);

  private final DrainConfig config;
  private final DrainScheduler scheduler;
  private final DrainHost<P> host;

  private final AtomicBoolean started = new AtomicBoolean();
  private final CompletableFuture<DrainStatus> done = new CompletableFuture<>();
  private final AtomicInteger refused = new AtomicInteger();

  // Read by status() on any thread. Written once by start(), then on the scheduler only. A phase
  // is written after the fields it implies (trigger and start time, then outcome and end time).
  private volatile DrainPhase phase = DrainPhase.NONE;
  private volatile @Nullable DrainTrigger trigger;
  private volatile @Nullable Instant startedAt;
  private volatile long startNanos;
  private volatile long endNanos;
  private final AtomicInteger transferred = new AtomicInteger();
  private volatile int kept;
  private final AtomicInteger kicked = new AtomicInteger();
  private volatile @Nullable DrainOutcome outcome;
  private volatile @Nullable Component message;

  // Scheduler only.
  private final Deque<Pending<P>> queue = new ArrayDeque<>();
  private final Set<UUID> queued = new HashSet<>();
  private final Set<UUID> keptSessions = new HashSet<>();
  private final Set<UUID> sentAway = new HashSet<>();
  private int phaseOneLeft;
  private @Nullable Step step;
  private long nextActionNanos;
  private long lastProgressNanos;
  private DrainScheduler.@Nullable Cancellable pumpTask;
  private DrainScheduler.@Nullable Cancellable pollTask;
  private DrainScheduler.@Nullable Cancellable timeoutTask;

  /**
   * Creates the drain, idle until {@link #start}.
   *
   * @param config the {@code drain} section
   * @param scheduler runs the steps and gives the time
   * @param host the proxy
   */
  public DrainController(DrainConfig config, DrainScheduler scheduler, DrainHost<P> host) {
    this.config = config;
    this.scheduler = scheduler;
    this.host = host;
  }

  // ---------------------------------------------------------------------------
  // Any thread
  // ---------------------------------------------------------------------------

  /**
   * Starts the drain, if it has not started yet. Readiness reports the drain as soon as this
   * returns.
   *
   * @param by what starts it
   * @return completed with the final status once the proxy is empty or the time-out expired, before
   *     {@link DrainHost#shutdown}; the same future for every call
   */
  public CompletableFuture<DrainStatus> start(DrainTrigger by) {
    return start(by, null);
  }

  /**
   * Starts the drain with a message of its own, if it has not started yet: {@code /shutdown} with a
   * reason.
   *
   * @param by what starts it
   * @param reason shown in place of {@code drain.message} to the players kicked and the logins
   *     refused, or {@code null} for {@code drain.message}; ignored if the drain had started
   * @return completed with the final status once the proxy is empty or the time-out expired, before
   *     {@link DrainHost#shutdown}; the same future for every call
   */
  public CompletableFuture<DrainStatus> start(DrainTrigger by, @Nullable Component reason) {
    if (!started.compareAndSet(false, true)) {
      return done;
    }
    message = reason;
    trigger = by;
    startedAt = scheduler.now();
    startNanos = scheduler.nanoTime();
    nextActionNanos = startNanos;
    phase = DrainPhase.ANNOUNCING;
    submit(this::announce);
    return done;
  }

  /**
   * Returns whether the drain started.
   *
   * @return {@code true} from the first {@link #start} on
   */
  public boolean isDraining() {
    return started.get();
  }

  /**
   * Returns where the drain stands. Never blocks.
   *
   * @return a snapshot
   */
  public DrainStatus status() {
    DrainPhase current = phase;
    int players = host.playerCount();
    if (current == DrainPhase.NONE) {
      return DrainStatus.notDraining(players);
    }
    boolean stopping = current == DrainPhase.STOPPING;
    long end = stopping ? endNanos : scheduler.nanoTime();
    return new DrainStatus(
        current,
        trigger,
        startedAt,
        Duration.ofNanos(Math.max(0, end - startNanos)),
        players,
        transferred.get(),
        kept,
        kicked.get(),
        refused.get(),
        stopping ? outcome : null);
  }

  /**
   * Deals with a player that arrived on a server, if the proxy drains: back from a match it was
   * kept for, or just logged in.
   *
   * @param player the player
   */
  public void playerArrived(P player) {
    if (!started.get()) {
      return;
    }
    submit(
        () -> {
          enqueue(player, Reason.SERVER_CHANGE);
          pump();
        });
  }

  /**
   * Returns what the players the drain disconnects are told: the reason it started with, or {@code
   * drain.message}.
   *
   * @return the message
   */
  public Component message() {
    Component reason = message;
    return reason != null ? reason : config.message();
  }

  /** Counts a login refused because the proxy drains. */
  public void loginRefused() {
    refused.incrementAndGet();
  }

  /**
   * Returns the decision applied to a player when no plugin changes it.
   *
   * @param player the player
   * @return a transfer to {@code drain.transfer-to} when the client supports it and the address is
   *     set; otherwise what {@code drain.cannot-transfer} says
   */
  public Decision defaultDecision(DrainCandidate player) {
    DrainConfig.Target target = config.transferTo();
    if (target != null && player.supportsTransfers()) {
      return new Transfer(target.host(), target.port());
    }
    return config.cannotTransfer() == DrainConfig.CannotTransfer.KICK
        ? new Kick(message())
        : new Keep();
  }

  // ---------------------------------------------------------------------------
  // Phases
  // ---------------------------------------------------------------------------

  /** Phase 0: readiness already says drain; give load balancers time to notice. */
  private void announce() {
    logger.info(
        "Drain started by {}: {} players connected. Readiness and the agent check say drain;"
            + " transfers start in {}",
        describe(trigger),
        host.playerCount(),
        DrainConfig.format(config.propagationDelay()));
    host.phaseChanged(status());
    submitLater(this::beginTransfers, config.propagationDelay());
  }

  /** Phase 1: deals with every player connected, one at a time. */
  private void beginTransfers() {
    if (phase != DrainPhase.ANNOUNCING) {
      return;
    }
    List<P> players = host.players();
    phase = DrainPhase.TRANSFERRING;
    DrainConfig.Target target = config.transferTo();
    if (target != null) {
      logger.info(
          "Drain phase 1: transferring {} players to {}, one every {}",
          players.size(),
          target,
          DrainConfig.format(config.transferInterval()));
    } else {
      logger.info(
          "Drain phase 1: no drain.transfer-to, the {} players are {}",
          players.size(),
          config.cannotTransfer() == DrainConfig.CannotTransfer.KICK
              ? "kicked, one every " + DrainConfig.format(config.transferInterval())
              : "kept until they leave");
    }
    host.phaseChanged(status());
    for (P player : players) {
      enqueue(player, Reason.TRANSFER_PHASE);
    }
    if (phaseOneLeft == 0) {
      endTransfers();
    }
    // Players who arrived on a server meanwhile wait in the queue too.
    pump();
  }

  /** Phase 2: waits for the players kept to leave. */
  private void endTransfers() {
    phase = DrainPhase.WAITING;
    lastProgressNanos = scheduler.nanoTime();
    logger.info(
        "Drain phase 2: {} transferred, {} kept, {} kicked; waiting up to {} for the {} players"
            + " left",
        transferred.get(),
        kept,
        kicked.get(),
        DrainConfig.format(config.timeout()),
        host.playerCount());
    host.phaseChanged(status());
    timeoutTask = submitLater(this::timedOut, config.timeout());
    checkEmpty();
  }

  private void checkEmpty() {
    pollTask = null;
    if (phase != DrainPhase.WAITING) {
      return;
    }
    int left = host.playerCount();
    if (left == 0) {
      finish(DrainOutcome.DRAINED);
      return;
    }
    long now = scheduler.nanoTime();
    if (now - lastProgressNanos >= PROGRESS_INTERVAL.toNanos()) {
      lastProgressNanos = now;
      logger.info(
          "Drain: {} players left after {} ({} transferred, {} kept, {} kicked)",
          left,
          formatElapsed(now - startNanos),
          transferred.get(),
          kept,
          kicked.get());
    }
    pollTask = submitLater(this::checkEmpty, POLL_INTERVAL);
  }

  private void timedOut() {
    timeoutTask = null;
    if (phase == DrainPhase.WAITING) {
      finish(DrainOutcome.TIMED_OUT);
    }
  }

  /** Phase 3: the drain is over; the proxy shuts down. */
  private void finish(DrainOutcome result) {
    cancel(pumpTask);
    cancel(pollTask);
    cancel(timeoutTask);
    pumpTask = null;
    pollTask = null;
    timeoutTask = null;
    Step current = step;
    if (current != null) {
      current.cancelTimer();
      step = null;
    }
    queue.clear();
    queued.clear();
    endNanos = scheduler.nanoTime();
    outcome = result;
    phase = DrainPhase.STOPPING;
    DrainStatus status = status();
    String elapsed = formatElapsed(endNanos - startNanos);
    if (result == DrainOutcome.DRAINED) {
      logger.info(
          "Drain complete after {}: {} transferred, {} kept, {} kicked, {} logins refused",
          elapsed,
          status.transferred(),
          status.kept(),
          status.kicked(),
          status.refused());
    } else {
      logger.warn(
          "Drain timed out after {}: the {} players left are disconnected ({} transferred, {} kept,"
              + " {} kicked, {} logins refused)",
          elapsed,
          status.players(),
          status.transferred(),
          status.kept(),
          status.kicked(),
          status.refused());
    }
    host.phaseChanged(status);
    done.complete(status);
    host.shutdown(status);
  }

  // ---------------------------------------------------------------------------
  // Dealing with players
  // ---------------------------------------------------------------------------

  /** A player waiting to be dealt with, and why. */
  private record Pending<P extends DrainCandidate>(P player, Reason reason) {}

  /** Where the step of the player being dealt with stands. */
  private enum Stage {
    DECIDING,
    TRANSFERRING
  }

  /** The player being dealt with: at most one at a time. */
  private final class Step {

    private final Pending<P> pending;
    private Stage stage = Stage.DECIDING;
    private DrainScheduler.@Nullable Cancellable timer;

    Step(Pending<P> pending) {
      this.pending = pending;
    }

    /** Runs {@code onTimeout} after {@code timeout}, unless the step moved on first. */
    void watch(Duration timeout, Runnable onTimeout) {
      cancelTimer();
      timer =
          submitLater(
              () -> {
                if (step == this) {
                  onTimeout.run();
                }
              },
              timeout);
    }

    void cancelTimer() {
      cancel(timer);
      timer = null;
    }
  }

  private void enqueue(P player, Reason reason) {
    if (phase == DrainPhase.NONE || phase == DrainPhase.STOPPING) {
      return;
    }
    UUID session = player.sessionId();
    if (sentAway.contains(session) || !player.isActive() || !queued.add(session)) {
      return;
    }
    queue.add(new Pending<>(player, reason));
    if (reason == Reason.TRANSFER_PHASE) {
      phaseOneLeft++;
    }
  }

  /** Deals with the next player, unless one is being dealt with or the pace says to wait. */
  private void pump() {
    while (step == null && (phase == DrainPhase.TRANSFERRING || phase == DrainPhase.WAITING)) {
      Pending<P> next = queue.peek();
      if (next == null) {
        return;
      }
      if (!next.player().isActive()) {
        take();
        finished(next);
        continue;
      }
      long wait = nextActionNanos - scheduler.nanoTime();
      if (wait > 0) {
        if (pumpTask == null) {
          pumpTask =
              submitLater(
                  () -> {
                    pumpTask = null;
                    pump();
                  },
                  Duration.ofNanos(wait));
        }
        return;
      }
      take();
      deal(next);
    }
  }

  private void take() {
    Pending<P> taken = queue.poll();
    if (taken != null) {
      queued.remove(taken.player().sessionId());
    }
  }

  private void deal(Pending<P> pending) {
    Step current = new Step(pending);
    step = current;
    Decision fallback = defaultDecision(pending.player());
    current.watch(
        DECISION_TIMEOUT,
        () -> {
          logger.warn(
              "Drain: plugins took more than {} to decide about {}; applying the default",
              DrainConfig.format(DECISION_TIMEOUT),
              pending.player().username());
          apply(current, fallback);
        });
    CompletableFuture<Decision> decision;
    try {
      decision = host.decide(pending.player(), pending.reason(), fallback);
    } catch (RuntimeException e) {
      logger.error("Drain: could not ask plugins about {}", pending.player().username(), e);
      decision = CompletableFuture.completedFuture(fallback);
    }
    var _ =
        decision.whenComplete(
            (chosen, error) ->
                submit(() -> apply(current, chosen == null || error != null ? fallback : chosen)));
  }

  private void apply(Step current, Decision decision) {
    if (step != current || current.stage != Stage.DECIDING) {
      return; // decided after its time-out: the default applied already
    }
    current.cancelTimer();
    P player = current.pending.player();
    try {
      if (phase != DrainPhase.STOPPING && player.isActive()) {
        switch (decision) {
          case Transfer(String host, int port) when player.supportsTransfers() -> {
            transfer(current, host, port);
            return;
          }
          case Transfer(String host, int port) -> {
            logger.info(
                "Drain: {} cannot be transferred to {}:{}, its client predates 1.20.5",
                player.username(),
                host,
                port);
            cannotTransfer(player);
          }
          case Keep() -> keep(player);
          case Kick(Component reason) -> kick(player, reason);
        }
      }
    } catch (RuntimeException e) {
      logger.error("Drain: could not deal with {}", player.username(), e);
    }
    release(current);
  }

  private void transfer(Step current, String host, int port) {
    current.stage = Stage.TRANSFERRING;
    paceNextAction();
    P player = current.pending.player();
    current.watch(
        TRANSFER_TIMEOUT,
        () -> {
          logger.warn(
              "Drain: the transfer of {} was not written within {}; moving on",
              player.username(),
              DrainConfig.format(TRANSFER_TIMEOUT));
          release(current);
        });
    var _ =
        player
            .transfer(host, port)
            .whenComplete(
                (result, error) ->
                    submit(
                        () ->
                            transferred(
                                current, result == null ? TransferResult.DISCONNECTED : result)));
  }

  private void transferred(Step current, TransferResult result) {
    if (step != current) {
      return; // timed out already
    }
    P player = current.pending.player();
    if (phase != DrainPhase.STOPPING) {
      switch (result) {
        case SENT -> {
          sentAway.add(player.sessionId());
          transferred.incrementAndGet();
        }
        case NOT_READY ->
            logger.debug(
                "Drain: {} is still logging in; dealt with once on a server", player.username());
        case UNSUPPORTED -> cannotTransfer(player);
        case DISCONNECTED -> {}
      }
    }
    release(current);
  }

  private void release(Step current) {
    current.cancelTimer();
    step = null;
    finished(current.pending);
    pump();
  }

  /** Counts a phase 1 player as done, and ends phase 1 with the last one. */
  private void finished(Pending<P> pending) {
    if (pending.reason() == Reason.TRANSFER_PHASE) {
      phaseOneLeft--;
      if (phaseOneLeft == 0 && phase == DrainPhase.TRANSFERRING) {
        endTransfers();
      }
    }
  }

  private void cannotTransfer(P player) {
    if (config.cannotTransfer() == DrainConfig.CannotTransfer.KICK) {
      kick(player, message());
    } else {
      keep(player);
    }
  }

  private void keep(P player) {
    if (keptSessions.add(player.sessionId())) {
      kept = keptSessions.size();
    }
    logger.debug("Drain: keeping {}", player.username());
  }

  private void kick(P player, Component reason) {
    paceNextAction();
    kicked.incrementAndGet();
    logger.debug("Drain: kicking {}", player.username());
    player.kick(reason);
  }

  private void paceNextAction() {
    nextActionNanos = scheduler.nanoTime() + config.transferInterval().toNanos();
  }

  // ---------------------------------------------------------------------------
  // Scheduling
  // ---------------------------------------------------------------------------

  private void submit(Runnable task) {
    try {
      scheduler.execute(task);
    } catch (RejectedExecutionException e) {
      logger.debug("Drain step dropped: the proxy is stopping");
    }
  }

  private DrainScheduler.@Nullable Cancellable submitLater(Runnable task, Duration delay) {
    try {
      return scheduler.schedule(task, delay);
    } catch (RejectedExecutionException e) {
      logger.debug("Drain step dropped: the proxy is stopping");
      return null;
    }
  }

  private static void cancel(DrainScheduler.@Nullable Cancellable task) {
    if (task != null) {
      task.cancel();
    }
  }

  private static String describe(@Nullable DrainTrigger by) {
    if (by == null) {
      return "an unknown trigger";
    }
    return switch (by) {
      case SIGNAL -> "SIGTERM";
      case HTTP -> "POST /drain";
      case PLUGIN -> "a plugin";
      case COMMAND -> "/shutdown";
    };
  }

  private static String formatElapsed(long nanos) {
    return String.format(Locale.ROOT, "%.1fs", nanos / 1_000_000_000.0);
  }
}
