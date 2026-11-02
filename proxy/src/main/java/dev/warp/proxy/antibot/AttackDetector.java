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

import dev.warp.api.antibot.AttackLevel;
import dev.warp.api.antibot.AttackReport;
import dev.warp.api.antibot.Signal;
import dev.warp.proxy.config.AntiBotConfig;

import java.net.InetAddress;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * Detects bot attacks from what identities the proxy does not know do, against a baseline it learns
 * (section 9.10 of the anti-bot research, decision D8).
 *
 * <p>Events are counted in a {@link LongAdder} per {@link Signal}: nothing is allocated per event,
 * unlike a cache entry per connection. Once a second, {@link #tick()} turns the counts into
 * per-second values, kept in a ring of {@value #RING} seconds, and decides the level:
 *
 * <ul>
 *   <li><b>Thresholds</b>: unknown logins and failed authentications go over {@code max(floor,
 *       factor × baseline)}, the baseline being an exponentially weighted mean over 30 minutes,
 *       learned only outside attacks and while no signal is over; connections not logged in go over
 *       a share of their cap. Counting only <em>unknown</em> identities is what keeps a restart,
 *       every player reconnecting at once, from looking like an attack, which a fixed join rate
 *       cannot.
 *   <li><b>Enter</b> {@link AttackLevel#ATTACK} after {@code sustain} seconds in a row with a
 *       signal over its threshold; <b>leave</b> it after {@code calm-for} seconds in a row with
 *       every signal under half its threshold. The hysteresis keeps a bursty attack from flapping.
 * </ul>
 *
 * <p>During an attack, the networks of hostile events are counted ({@link SourceCounter}) to name
 * its main sources. {@link #record} is called from any thread and is lock-free; {@link #tick()}
 * from one thread at a time; readers get immutable snapshots.
 */
public final class AttackDetector {

  /** Seconds of history kept per signal. */
  static final int RING = 120;

  /** Weight of a new second in a baseline: a mean over about 30 minutes. */
  static final double BASELINE_ALPHA = 1 - Math.exp(-1.0 / 1800);

  /** How many sources an attack report names. */
  static final int TOP_SOURCES = 5;

  private static final Signal[] SIGNALS = Signal.values();

  /** The signals that can start an attack. */
  private static final Signal[] TRIGGERS = {
    Signal.UNKNOWN_LOGINS, Signal.AUTHENTICATION_FAILURES, Signal.PENDING_CONNECTIONS
  };

  private final AntiBotConfig.Attack config;
  private final IntSupplier pending;
  private final int pendingCap;
  private final LongSupplier millisClock;
  private final Listener listener;
  private final EnumMap<Signal, LongAdder> counters = new EnumMap<>(Signal.class);
  private final SourceCounter sources;

  // Written by tick() only.
  private final long[][] ring = new long[SIGNALS.length][RING];
  private final double[] baselines = new double[SIGNALS.length];
  private int head;
  private int overStreak;
  private int calmStreak;
  private @Nullable Ongoing ongoing;
  private long lastSummaryMillis;

  private volatile AttackLevel level = AttackLevel.NORMAL;
  private volatile Snapshot snapshot;
  private volatile @Nullable AttackReport current;

  /** Hears of the attacks the detector measures, on the thread that calls {@link #tick()}. */
  public interface Listener {

    /**
     * An attack started.
     *
     * @param attack its report so far
     */
    void started(AttackReport attack);

    /**
     * An attack goes on: called every {@code anti-bot.attack.report-interval}.
     *
     * @param attack its report so far
     * @param now the detector's latest second
     */
    void ongoing(AttackReport attack, Snapshot now);

    /**
     * An attack ended.
     *
     * @param attack its final report
     */
    void ended(AttackReport attack);
  }

  /**
   * The detector's view of the last second, for status pages and metrics.
   *
   * @param lastSecond each signal's value over the last full second
   * @param averages each signal's mean over the last 10 seconds
   * @param baselines each signal's learned baseline, per second
   * @param thresholds each signal's threshold, or {@link Long#MAX_VALUE} if it starts no attack
   */
  public record Snapshot(
      Map<Signal, Long> lastSecond,
      Map<Signal, Double> averages,
      Map<Signal, Double> baselines,
      Map<Signal, Long> thresholds) {}

  /** The attack in progress, as tick() accumulates it. */
  private static final class Ongoing {
    final Instant startedAt;
    final Signal trigger;
    final long triggerRate;
    final long threshold;
    final long[] peaks = new long[SIGNALS.length];
    final long[] totals = new long[SIGNALS.length];

    Ongoing(Instant startedAt, Signal trigger, long triggerRate, long threshold) {
      this.startedAt = startedAt;
      this.trigger = trigger;
      this.triggerRate = triggerRate;
      this.threshold = threshold;
    }
  }

  /**
   * Creates a detector.
   *
   * @param config the {@code anti-bot.attack} section
   * @param pending the connections not logged in, read once a second
   * @param pendingCap their cap, or 0 for none
   * @param ipv4Prefix the bits of an IPv4 network, to group sources
   * @param ipv6Prefix the bits of an IPv6 network, to group sources
   * @param millisClock the wall clock, for the times of the reports
   * @param listener told of attacks
   */
  public AttackDetector(
      AntiBotConfig.Attack config,
      IntSupplier pending,
      int pendingCap,
      int ipv4Prefix,
      int ipv6Prefix,
      LongSupplier millisClock,
      Listener listener) {
    this.config = config;
    this.pending = pending;
    this.pendingCap = pendingCap;
    this.millisClock = millisClock;
    this.listener = listener;
    this.sources = new SourceCounter(ipv4Prefix, ipv6Prefix);
    for (Signal signal : SIGNALS) {
      counters.put(signal, new LongAdder());
    }
    this.snapshot = snapshot();
  }

  // ---------------------------------------------------------------------------
  // Events, from any thread
  // ---------------------------------------------------------------------------

  /**
   * Counts an event.
   *
   * @param signal what happened; not {@link Signal#PENDING_CONNECTIONS}, a gauge
   */
  public void record(Signal signal) {
    counter(signal).increment();
  }

  /**
   * Counts a hostile event of an address: during an attack its network is counted among the
   * sources.
   *
   * @param signal what happened
   * @param source the client's address, or {@code null} if unknown
   */
  public void record(Signal signal, @Nullable InetAddress source) {
    counter(signal).increment();
    if (source != null && level == AttackLevel.ATTACK) {
      sources.count(source);
    }
  }

  private LongAdder counter(Signal signal) {
    LongAdder counter = counters.get(signal);
    if (counter == null) {
      throw new AssertionError("every signal has a counter: " + signal);
    }
    return counter;
  }

  // ---------------------------------------------------------------------------
  // Once a second
  // ---------------------------------------------------------------------------

  /** Closes the current second: updates the history, the baselines and the level. */
  public void tick() {
    head = (head + 1) % RING;
    long[] now = new long[SIGNALS.length];
    for (Signal signal : SIGNALS) {
      long value =
          signal == Signal.PENDING_CONNECTIONS
              ? pending.getAsInt()
              : counter(signal).sumThenReset();
      now[signal.ordinal()] = value;
      ring[signal.ordinal()][head] = value;
    }

    Signal over = null;
    double worst = 1;
    boolean calm = true;
    for (Signal signal : TRIGGERS) {
      long threshold = threshold(signal);
      if (threshold == Long.MAX_VALUE) {
        continue;
      }
      long value = now[signal.ordinal()];
      if (value > threshold && (double) value / threshold > worst) {
        worst = (double) value / threshold;
        over = signal;
      }
      if (value > threshold / 2) {
        calm = false;
      }
    }

    long millis = millisClock.getAsLong();
    Ongoing attack = ongoing;
    if (attack == null) {
      if (over != null) {
        overStreak++;
        if (overStreak >= seconds(config.sustain().toSeconds())) {
          start(over, now[over.ordinal()], threshold(over), millis);
        }
      } else {
        overStreak = 0;
        learn(now);
      }
    } else {
      for (int i = 0; i < SIGNALS.length; i++) {
        attack.peaks[i] = Math.max(attack.peaks[i], now[i]);
        attack.totals[i] += now[i];
      }
      calmStreak = calm ? calmStreak + 1 : 0;
      if (calmStreak >= seconds(config.calmFor().toSeconds())) {
        end(attack, millis);
      } else {
        AttackReport report = report(attack, null);
        current = report;
        if (millis - lastSummaryMillis >= config.reportInterval().toMillis()) {
          lastSummaryMillis = millis;
          this.snapshot = snapshot();
          listener.ongoing(report, snapshot);
        }
      }
    }
    this.snapshot = snapshot();
  }

  private void learn(long[] now) {
    for (int i = 0; i < SIGNALS.length; i++) {
      baselines[i] += BASELINE_ALPHA * (now[i] - baselines[i]);
    }
  }

  private void start(Signal trigger, long rate, long threshold, long millis) {
    sources.clear();
    Ongoing attack = new Ongoing(Instant.ofEpochMilli(millis), trigger, rate, threshold);
    // The seconds that sustained it belong to it.
    int sustained = Math.min(RING, Math.max(1, overStreak));
    for (int back = 0; back < sustained; back++) {
      int slot = Math.floorMod(head - back, RING);
      for (int i = 0; i < SIGNALS.length; i++) {
        attack.peaks[i] = Math.max(attack.peaks[i], ring[i][slot]);
        attack.totals[i] += ring[i][slot];
      }
    }
    ongoing = attack;
    overStreak = 0;
    calmStreak = 0;
    lastSummaryMillis = millis;
    AttackReport report = report(attack, null);
    current = report;
    level = AttackLevel.ATTACK;
    listener.started(report);
  }

  private void end(Ongoing attack, long millis) {
    AttackReport report =
        report(attack, Instant.ofEpochMilli(Math.max(millis, attack.startedAt.toEpochMilli())));
    ongoing = null;
    current = null;
    calmStreak = 0;
    level = AttackLevel.NORMAL;
    listener.ended(report);
  }

  private AttackReport report(Ongoing attack, @Nullable Instant endedAt) {
    EnumMap<Signal, Long> peaks = new EnumMap<>(Signal.class);
    EnumMap<Signal, Long> totals = new EnumMap<>(Signal.class);
    for (Signal signal : SIGNALS) {
      peaks.put(signal, attack.peaks[signal.ordinal()]);
      if (signal != Signal.PENDING_CONNECTIONS) {
        totals.put(signal, attack.totals[signal.ordinal()]);
      }
    }
    return new AttackReport(
        attack.startedAt,
        endedAt,
        attack.trigger,
        attack.triggerRate,
        attack.threshold,
        peaks,
        totals,
        sources.top(TOP_SOURCES));
  }

  // ---------------------------------------------------------------------------
  // Thresholds
  // ---------------------------------------------------------------------------

  /**
   * Returns the threshold of a signal.
   *
   * @param signal the signal
   * @return its threshold per second (a count for connections not logged in), or {@link
   *     Long#MAX_VALUE} for a signal that starts no attack
   */
  public long threshold(Signal signal) {
    return switch (signal) {
      case UNKNOWN_LOGINS -> learned(signal, config.unknownLoginFloor());
      case AUTHENTICATION_FAILURES -> learned(signal, config.authenticationFailureFloor());
      case PENDING_CONNECTIONS ->
          pendingCap > 0 && config.pendingShare() > 0
              ? Math.max(1, (long) pendingCap * config.pendingShare() / 100)
              : Long.MAX_VALUE;
      case CONNECTIONS, REFUSALS -> Long.MAX_VALUE;
    };
  }

  private long learned(Signal signal, int floor) {
    return Math.max(floor, (long) Math.ceil(config.baselineFactor() * baselines[signal.ordinal()]));
  }

  private static int seconds(long seconds) {
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
  }

  // ---------------------------------------------------------------------------
  // Reads, from any thread
  // ---------------------------------------------------------------------------

  /**
   * Returns the level.
   *
   * @return {@link AttackLevel#ATTACK} during an attack
   */
  public AttackLevel level() {
    return level;
  }

  /**
   * Returns the attack in progress.
   *
   * @return its report so far, or empty
   */
  public Optional<AttackReport> currentAttack() {
    return Optional.ofNullable(current);
  }

  /**
   * Returns the detector's latest second.
   *
   * @return the snapshot
   */
  public Snapshot latest() {
    return snapshot;
  }

  private Snapshot snapshot() {
    EnumMap<Signal, Long> last = new EnumMap<>(Signal.class);
    EnumMap<Signal, Double> averages = new EnumMap<>(Signal.class);
    EnumMap<Signal, Double> learnedBaselines = new EnumMap<>(Signal.class);
    EnumMap<Signal, Long> thresholds = new EnumMap<>(Signal.class);
    for (Signal signal : SIGNALS) {
      int i = signal.ordinal();
      last.put(signal, ring[i][head]);
      long sum = 0;
      for (int back = 0; back < 10; back++) {
        sum += ring[i][Math.floorMod(head - back, RING)];
      }
      averages.put(signal, sum / 10.0);
      learnedBaselines.put(signal, baselines[i]);
      thresholds.put(signal, threshold(signal));
    }
    return new Snapshot(
        Map.copyOf(last),
        Map.copyOf(averages),
        Map.copyOf(learnedBaselines),
        Map.copyOf(thresholds));
  }
}
