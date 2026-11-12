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
package dev.warp.proxy.world;

import dev.warp.api.antibot.VerificationFailure;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.ClientMovement;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * The state machine of the anti-bot's world check (section 9.8 of the research): a client put on a
 * platform of the virtual world at a random height must confirm the teleport with its id (1.9+),
 * report a first position exactly at the teleport target and off the ground, fall with the vanilla
 * gravity ({@code v' = (v - 0.08) * 0.98}, one position per tick) and land exactly on the platform,
 * while echoing one ping (1.17+) or window transaction (older). Every random value is drawn per
 * session.
 *
 * <p>Verdicts follow the rules of section 10: a wrong id, a packet illegal in the state or a first
 * position elsewhere are protocol impossibilities and fail the check; a fall that does not follow
 * the gravity is a {@link Outcome.Mismatch}, which the caller answers with one retry from a new
 * height, then lets the client in untrusted ({@link Flag#PHYSICS_MISMATCH}): never a kick, never a
 * blocklist strike. Deadlines follow the measured round trip: {@code stageTimeout + 4 RTT} per
 * stage, {@value #MAX_SECONDS} s in all. The physics are tick-based, so latency changes nothing
 * else. Tick End (1.21.2+) and the number of flag-only packets are counted, never judged.
 *
 * <p>Two things a client does for reasons of its own are tolerated during the fall, and the fall
 * stays on the vanilla trajectory: a position repeated at the height the client already stands at
 * (its chunk arrived after the teleport, so it has not moved yet; the vanilla client reports its
 * position every second while idle), and a position that skips up to {@value #MAX_SKIPPED_TICKS}
 * ticks (a client that simulates several ticks between two reports, as mineflayer catching up does;
 * the vanilla client reports every tick it moves).
 *
 * <p>Not thread-safe: driven from the connection's event loop.
 */
public final class WorldCheck {

  /** The whole check never lasts longer than this. */
  public static final int MAX_SECONDS = 30;

  /** How far, over the platform, a landing is still a landing: the client clamps exactly. */
  static final double LANDING_TOLERANCE = 1e-7;

  /** How far a fall may stray from the formula; 1.8 multiplied by 0.98 as a double, later 0.98f. */
  static final double GRAVITY_TOLERANCE = 1e-6;

  private static final double GRAVITY = 0.08;
  private static final double DRAG = 0.98f;

  /** Ticks of fall, drawn per session. */
  private static final int MIN_TICKS = 8;

  private static final int MAX_TICKS = 12;

  /** Positions a client may send during its fall before the check gives up on the physics. */
  private static final int MAX_POSITIONS = 64;

  /** Ticks a position may be ahead of the previous one: the trajectory is still the vanilla one. */
  static final int MAX_SKIPPED_TICKS = 3;

  /** The platform's height: within the proxy's 256-block dimension and the vanilla overworld. */
  private static final int MIN_PLATFORM_Y = 40;

  private static final int MAX_PLATFORM_Y = 200;

  /** The block under the player, within the chunk at (0, 0), its 3x3 platform included. */
  private static final int MIN_BLOCK = 1;

  private static final int MAX_BLOCK = 14;

  /** Flags a pass carries: signals, never verdicts. Two or more and the pass is not trusted. */
  public enum Flag {
    /** A 1.21.2+ client sent no Tick End during its fall. */
    NO_TICK_END,
    /** The check needed its retry. */
    RETRIED,
    /** The echo came after the landing. */
    LATE_ECHO,
    /**
     * The fall did not follow the gravity twice, or never ended: the client is let in, never
     * trusted (rule 1 of section 10: a physics mismatch is not a protocol impossibility).
     */
    PHYSICS_MISMATCH
  }

  /** Where the check stands after an answer. */
  public sealed interface Outcome {
    /** Waiting for more. */
    record Pending() implements Outcome {}

    /**
     * The client did everything a vanilla client does.
     *
     * @param flags what was odd about it, if anything
     */
    record Passed(Set<Flag> flags) implements Outcome {
      /**
       * Copies the flags.
       *
       * @param flags what was odd about it, if anything
       */
      public Passed {
        flags = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
      }

      /**
       * Returns whether the pass is clean enough to trust the identity.
       *
       * @return {@code true} with fewer than two flags, none of them a physics mismatch
       */
      public boolean trusted() {
        return flags.size() < 2 && !flags.contains(Flag.PHYSICS_MISMATCH);
      }
    }

    /**
     * The fall did not follow the gravity, or never ended: not a protocol impossibility.
     *
     * @param detail what was seen
     */
    record Mismatch(String detail) implements Outcome {}

    /**
     * The client did what no client does.
     *
     * @param reason why
     * @param detail what was seen
     */
    record Failed(VerificationFailure reason, String detail) implements Outcome {}
  }

  private enum Stage {
    AWAIT_CONFIRM,
    AWAIT_FIRST_POSITION,
    FALLING,
    AWAIT_ECHO,
    DONE
  }

  private final ProtocolVersion version;
  private final RandomGenerator random;
  private final long stageTimeoutNanos;
  private final long roundTripNanos;
  private final long startedNanos;
  private final Set<Flag> flags = EnumSet.noneOf(Flag.class);

  private final int teleportId;
  private final int pingId;
  private final short transactionAction;
  private int blockX;
  private int blockZ;
  private int platformY;
  private double startY;

  private Stage stage;
  private long stageStartedNanos;
  private double expectedY;
  private double velocity;
  private boolean echoed;
  private boolean landed;
  private int positions;
  private int tickEnds;
  private int unknownPackets;
  private boolean retried;

  /**
   * Creates a check and draws its first placement.
   *
   * @param version the client's version
   * @param random the source of the ids and positions
   * @param stageTimeout the base deadline of each stage
   * @param roundTripNanos the client's measured round trip, from the probe
   * @param nowNanos the clock now
   */
  public WorldCheck(
      ProtocolVersion version,
      RandomGenerator random,
      Duration stageTimeout,
      long roundTripNanos,
      long nowNanos) {
    this.version = version;
    this.random = random;
    this.stageTimeoutNanos = stageTimeout.toNanos();
    this.roundTripNanos = Math.max(0, roundTripNanos);
    this.startedNanos = nowNanos;
    this.teleportId = 1 + random.nextInt(Integer.MAX_VALUE - 1);
    this.pingId = random.nextInt();
    this.transactionAction = (short) (1 + random.nextInt(Short.MAX_VALUE - 1));
    this.stage = Stage.AWAIT_CONFIRM;
    place(nowNanos);
  }

  // ---------------------------------------------------------------------------
  // The placement
  // ---------------------------------------------------------------------------

  /**
   * Draws the platform and the height of the fall, and restarts the stages. The echo is not asked
   * again: its id is the same, and a client that answered it already keeps its answer.
   */
  private void place(long nowNanos) {
    blockX = MIN_BLOCK + random.nextInt(MAX_BLOCK - MIN_BLOCK + 1);
    blockZ = MIN_BLOCK + random.nextInt(MAX_BLOCK - MIN_BLOCK + 1);
    platformY = MIN_PLATFORM_Y + random.nextInt(MAX_PLATFORM_Y - MIN_PLATFORM_Y + 1);
    int ticks = MIN_TICKS + random.nextInt(MAX_TICKS - MIN_TICKS + 1);
    // Land partway through the last tick, so that the landing is a clamped step.
    double fraction = 0.2 + 0.6 * random.nextDouble();
    double before = fallDistance(ticks - 1);
    double whole = fallDistance(ticks);
    startY = platformTop() + before + fraction * (whole - before);
    expectedY = startY;
    velocity = 0;
    landed = false;
    positions = 0;
    stage =
        version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)
            ? Stage.AWAIT_CONFIRM
            : Stage.AWAIT_FIRST_POSITION;
    stageStartedNanos = nowNanos;
  }

  /** How far a body at rest falls in {@code ticks} ticks under the vanilla gravity. */
  static double fallDistance(int ticks) {
    double v = 0;
    double distance = 0;
    for (int i = 0; i < ticks; i++) {
      v = (v - GRAVITY) * DRAG;
      distance -= v;
    }
    return distance;
  }

  /**
   * Returns the teleport id the client must confirm (1.9+).
   *
   * @return the id
   */
  public int teleportId() {
    return teleportId;
  }

  /**
   * Returns the id of the play ping to send (1.17+).
   *
   * @return the id
   */
  public int pingId() {
    return pingId;
  }

  /**
   * Returns the action of the window transaction to send (1.7 to 1.16.5).
   *
   * @return the action
   */
  public short transactionAction() {
    return transactionAction;
  }

  /**
   * Returns the block x of the platform's centre.
   *
   * @return the block x
   */
  public int blockX() {
    return blockX;
  }

  /**
   * Returns the block z of the platform's centre.
   *
   * @return the block z
   */
  public int blockZ() {
    return blockZ;
  }

  /**
   * Returns the block y of the platform.
   *
   * @return the block y
   */
  public int platformY() {
    return platformY;
  }

  /**
   * Returns the height the player stands at once landed: the top of the platform.
   *
   * @return the feet height
   */
  public double platformTop() {
    return platformY + 1;
  }

  /**
   * Returns the x the player is teleported to: the centre of the platform.
   *
   * @return the x coordinate
   */
  public double startX() {
    return blockX + 0.5;
  }

  /**
   * Returns the height the player is teleported to.
   *
   * @return the feet height
   */
  public double startY() {
    return startY;
  }

  /**
   * Returns the z the player is teleported to.
   *
   * @return the z coordinate
   */
  public double startZ() {
    return blockZ + 0.5;
  }

  /**
   * Draws a new height for the one retry a mismatch gets, keeping the ids; the caller teleports the
   * client again.
   *
   * @param nowNanos the clock now
   */
  public void retry(long nowNanos) {
    retried = true;
    flags.add(Flag.RETRIED);
    place(nowNanos);
  }

  /**
   * Returns whether the retry was used.
   *
   * @return {@code true} after {@link #retry}
   */
  public boolean retried() {
    return retried;
  }

  /**
   * Ends the check after a mismatch the retry did not resolve: the client is let in, never trusted.
   *
   * @return the flags of the pass, {@link Flag#PHYSICS_MISMATCH} among them
   */
  public Set<Flag> mismatched() {
    flags.add(Flag.PHYSICS_MISMATCH);
    stage = Stage.DONE;
    return EnumSet.copyOf(flags);
  }

  // ---------------------------------------------------------------------------
  // Answers
  // ---------------------------------------------------------------------------

  /**
   * The client confirmed a teleport.
   *
   * @param id the id it confirmed
   * @param nowNanos the clock now
   * @return where the check stands
   */
  public Outcome teleportConfirmed(int id, long nowNanos) {
    if (stage == Stage.DONE) {
      return new Outcome.Pending();
    }
    if (id != teleportId) {
      return new Outcome.Failed(
          VerificationFailure.WRONG_ECHO, "teleport " + id + " confirmed, " + teleportId + " sent");
    }
    if (stage != Stage.AWAIT_CONFIRM) {
      return new Outcome.Failed(VerificationFailure.ILLEGAL_PACKET, "teleport confirmed twice");
    }
    stage = Stage.AWAIT_FIRST_POSITION;
    stageStartedNanos = nowNanos;
    return new Outcome.Pending();
  }

  /**
   * The client reported a movement.
   *
   * @param movement what it sent
   * @param nowNanos the clock now
   * @return where the check stands
   */
  public Outcome moved(ClientMovement movement, long nowNanos) {
    if (stage == Stage.DONE || stage == Stage.AWAIT_ECHO) {
      return new Outcome.Pending();
    }
    if (!movement.hasPosition()) {
      return new Outcome.Pending(); // idle ticks and looks: counted by nobody
    }
    switch (stage) {
      case AWAIT_CONFIRM -> {
        return new Outcome.Failed(
            VerificationFailure.ILLEGAL_PACKET, "a position before the teleport was confirmed");
      }
      case AWAIT_FIRST_POSITION -> {
        if (movement.x() != startX()
            || movement.y() != startY
            || movement.z() != startZ()
            || movement.onGround()) {
          return new Outcome.Failed(
              VerificationFailure.WRONG_POSITION,
              "first position "
                  + describe(movement)
                  + ", expected "
                  + startX()
                  + " "
                  + startY
                  + " "
                  + startZ()
                  + " in the air");
        }
        stage = Stage.FALLING;
        stageStartedNanos = nowNanos;
        return new Outcome.Pending();
      }
      case FALLING -> {
        return fell(movement, nowNanos);
      }
      default -> {
        return new Outcome.Pending();
      }
    }
  }

  private Outcome fell(ClientMovement movement, long nowNanos) {
    if (++positions > MAX_POSITIONS) {
      return new Outcome.Mismatch("still falling after " + positions + " positions");
    }
    if (movement.y() == expectedY && !movement.onGround()) {
      // Not moved yet (its chunk came after the teleport): idle reports are not ticks.
      return new Outcome.Pending();
    }
    double top = platformTop();
    double v = velocity;
    double y = expectedY;
    for (int skipped = 0; skipped <= MAX_SKIPPED_TICKS; skipped++) {
      v = (v - GRAVITY) * DRAG;
      double predicted = y + v;
      if (predicted <= top + LANDING_TOLERANCE) {
        // The last step is clamped by the platform.
        if (Math.abs(movement.y() - top) > LANDING_TOLERANCE || !movement.onGround()) {
          return new Outcome.Mismatch(
              "landing at "
                  + movement.y()
                  + (movement.onGround() ? " on" : " off")
                  + " the ground, platform top at "
                  + top);
        }
        landed = true;
        return landedOrEcho(nowNanos);
      }
      if (Math.abs(movement.y() - predicted) <= GRAVITY_TOLERANCE && !movement.onGround()) {
        velocity = v;
        expectedY = predicted;
        return new Outcome.Pending();
      }
      y = predicted;
    }
    return new Outcome.Mismatch(
        "fell to "
            + movement.y()
            + (movement.onGround() ? " on the ground" : "")
            + ", gravity predicted "
            + (expectedY + (velocity - GRAVITY) * DRAG));
  }

  private Outcome landedOrEcho(long nowNanos) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2) && tickEnds == 0) {
      flags.add(Flag.NO_TICK_END);
    }
    if (echoed) {
      stage = Stage.DONE;
      return new Outcome.Passed(flags);
    }
    stage = Stage.AWAIT_ECHO;
    stageStartedNanos = nowNanos;
    return new Outcome.Pending();
  }

  /**
   * The client answered the ping (1.17+).
   *
   * @param id the id it answered
   * @return where the check stands
   */
  public Outcome pong(int id) {
    if (id != pingId) {
      return new Outcome.Failed(
          VerificationFailure.WRONG_ECHO, "pong " + id + ", ping " + pingId + " sent");
    }
    return echo();
  }

  /**
   * The client answered the window transaction (1.7 to 1.16.5).
   *
   * @param action the action it answered
   * @return where the check stands
   */
  public Outcome transaction(short action) {
    if (action != transactionAction) {
      return new Outcome.Failed(
          VerificationFailure.WRONG_ECHO,
          "transaction " + action + " answered, " + transactionAction + " sent");
    }
    return echo();
  }

  private Outcome echo() {
    if (echoed) {
      return new Outcome.Failed(VerificationFailure.ILLEGAL_PACKET, "the ping answered twice");
    }
    echoed = true;
    if (stage == Stage.AWAIT_ECHO) {
      flags.add(Flag.LATE_ECHO);
      stage = Stage.DONE;
      return new Outcome.Passed(flags);
    }
    return new Outcome.Pending();
  }

  /** A 1.21.2+ client ended a tick. */
  public void tickEnded() {
    tickEnds++;
  }

  /** The client sent something the check does not read: counted, never a verdict (rule 4). */
  public void unknownPacket() {
    unknownPackets++;
  }

  /**
   * Returns how many packets the check ignored.
   *
   * @return the count
   */
  public int unknownPackets() {
    return unknownPackets;
  }

  /**
   * Returns whether the client landed on the platform.
   *
   * @return {@code true} once it stands there
   */
  public boolean landed() {
    return landed;
  }

  // ---------------------------------------------------------------------------
  // Deadlines
  // ---------------------------------------------------------------------------

  /**
   * Returns when the current stage, or the whole check, expires.
   *
   * @return the clock value of the deadline
   */
  public long deadlineNanos() {
    long stageDeadline = stageStartedNanos + stageTimeoutNanos + 4 * roundTripNanos;
    long cap = startedNanos + TimeUnit.SECONDS.toNanos(MAX_SECONDS);
    return Math.min(stageDeadline, cap);
  }

  /**
   * The deadline passed.
   *
   * @param nowNanos the clock now
   * @return a failure, a mismatch of the fall, or pending when the deadline moved
   */
  public Outcome deadline(long nowNanos) {
    if (stage == Stage.DONE) {
      return new Outcome.Pending();
    }
    if (nowNanos < deadlineNanos()) {
      return new Outcome.Pending();
    }
    return switch (stage) {
      case AWAIT_CONFIRM, AWAIT_FIRST_POSITION ->
          new Outcome.Failed(
              VerificationFailure.TIMED_OUT,
              stage == Stage.AWAIT_CONFIRM
                  ? "no teleport confirmation"
                  : "no position after the teleport");
      case FALLING -> new Outcome.Mismatch("the fall stopped after " + positions + " positions");
      case AWAIT_ECHO -> new Outcome.Failed(VerificationFailure.TIMED_OUT, "no echo of the ping");
      case DONE -> new Outcome.Pending();
    };
  }

  private static String describe(ClientMovement movement) {
    return movement.x()
        + " "
        + movement.y()
        + " "
        + movement.z()
        + (movement.onGround() ? " on the ground" : "");
  }
}
