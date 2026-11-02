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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.event.player.PlayerDrainEvent;
import dev.warp.api.lifecycle.DrainOutcome;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.TransferResult;
import dev.warp.proxy.config.DrainConfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DrainController")
class DrainControllerTest {

  private static final DrainConfig.Target TARGET =
      new DrainConfig.Target("play.example.net", 25565);
  private static final Duration PROPAGATION = Duration.ofSeconds(5);
  private static final Duration INTERVAL = Duration.ofMillis(100);
  private static final Duration TIMEOUT = Duration.ofMinutes(30);
  private static final String MESSAGE = "Restarting, reconnect";

  private final ManualScheduler clock = new ManualScheduler();
  private final FakeHost host = new FakeHost();

  private static DrainConfig config(
      DrainConfig.@Nullable Target target, DrainConfig.CannotTransfer cannotTransfer) {
    return new DrainConfig(
        target,
        true,
        PROPAGATION,
        INTERVAL,
        cannotTransfer,
        DrainConfig.NewPlayers.ACCEPT,
        TIMEOUT,
        MESSAGE);
  }

  private DrainController<FakePlayer> controller() {
    return controller(config(TARGET, DrainConfig.CannotTransfer.KEEP));
  }

  private DrainController<FakePlayer> controller(DrainConfig config) {
    return new DrainController<>(config, clock, host);
  }

  private FakePlayer modern(String name) {
    return new FakePlayer(name, true, clock);
  }

  private FakePlayer legacy(String name) {
    return new FakePlayer(name, false, clock);
  }

  private static Duration at(long millis) {
    return Duration.ofMillis(millis);
  }

  /**
   * The outcome of a drain that is over by now: the clock is fake, so the drain ends while the test
   * advances it, or never. A mutant that never ends it fails the test here, instead of hanging it
   * until PIT's time-out.
   */
  private static DrainStatus settled(CompletableFuture<DrainStatus> done) {
    assertTrue(done.isDone(), "the drain is over");
    return done.join();
  }

  // ---------------------------------------------------------------------------
  // Phase 0
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("announcing")
  class Announcing {

    @Test
    @DisplayName("should report the drain as soon as it is asked for, before any step runs")
    void drainingAtOnce() {
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);

      assertTrue(drain.isDraining());
      DrainStatus status = drain.status();
      assertEquals(DrainPhase.ANNOUNCING, status.phase());
      assertEquals(DrainTrigger.HTTP, status.trigger());
      assertEquals(ManualScheduler.ORIGIN, status.startedAt());
    }

    @Test
    @DisplayName("should not drain before it is asked to")
    void idle() {
      DrainController<FakePlayer> drain = controller();
      host.with(modern("Steve"));

      clock.advance(Duration.ofMinutes(1));

      assertFalse(drain.isDraining());
      assertEquals(DrainStatus.notDraining(1), drain.status());
      assertTrue(host.phases().isEmpty());
    }

    @Test
    @DisplayName("should move nobody until load balancers had the propagation delay to notice")
    void waitsForPropagation() {
      FakePlayer steve = modern("Steve");
      host.with(steve);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.SIGNAL);
      clock.advance(PROPAGATION.minusMillis(1));

      assertTrue(steve.transfers().isEmpty());
      assertEquals(List.of(DrainPhase.ANNOUNCING), host.phases());

      clock.advance(at(1));

      assertEquals(
          List.of(new FakePlayer.Sent("play.example.net", 25565, PROPAGATION)), steve.transfers());
    }

    @Test
    @DisplayName("should hold players arriving on a server until the transfers start")
    void arrivalsWait() {
      DrainController<FakePlayer> drain = controller();
      var _ = drain.start(DrainTrigger.PLUGIN);
      FakePlayer newcomer = modern("Newcomer");
      host.with(newcomer);

      drain.playerArrived(newcomer);
      clock.advance(Duration.ofSeconds(1));

      assertTrue(newcomer.transfers().isEmpty());

      clock.advance(PROPAGATION);

      assertEquals(1, newcomer.transfers().size());
      assertEquals(PROPAGATION, newcomer.transfers().getFirst().at());
    }

    @Test
    @DisplayName("should run one drain, whoever asks and however often")
    void startsOnce() {
      DrainController<FakePlayer> drain = controller();

      CompletableFuture<DrainStatus> first = drain.start(DrainTrigger.HTTP);
      CompletableFuture<DrainStatus> second = drain.start(DrainTrigger.SIGNAL);
      clock.advance(Duration.ofMinutes(1));

      assertSame(first, second);
      assertEquals(DrainTrigger.HTTP, drain.status().trigger());
      assertEquals(1, host.shutdowns());
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 1
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("transferring")
  class Transferring {

    @Test
    @DisplayName("should transfer players one at a time, the configured interval apart")
    void paced() {
      FakePlayer a = modern("A");
      FakePlayer b = modern("B");
      FakePlayer c = modern("C");
      host.with(a, b, c);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertEquals(1, a.transfers().size());
      assertTrue(b.transfers().isEmpty(), "the second waits for the interval");

      clock.advance(INTERVAL);
      clock.advance(INTERVAL);

      assertEquals(PROPAGATION, a.transfers().getFirst().at());
      assertEquals(PROPAGATION.plus(INTERVAL), b.transfers().getFirst().at());
      assertEquals(PROPAGATION.plus(INTERVAL).plus(INTERVAL), c.transfers().getFirst().at());
      assertEquals(3, drain.status().transferred());
    }

    @Test
    @DisplayName("should transfer everyone at once when the interval is zero")
    void noPacing() {
      FakePlayer a = modern("A");
      FakePlayer b = modern("B");
      host.with(a, b);
      DrainController<FakePlayer> drain =
          controller(
              new DrainConfig(
                  TARGET,
                  false,
                  PROPAGATION,
                  Duration.ZERO,
                  DrainConfig.CannotTransfer.KEEP,
                  DrainConfig.NewPlayers.ACCEPT,
                  TIMEOUT,
                  MESSAGE));

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertEquals(PROPAGATION, a.transfers().getFirst().at());
      assertEquals(PROPAGATION, b.transfers().getFirst().at());
    }

    @Test
    @DisplayName("should keep clients older than 1.20.5 by default, without spending an interval")
    void keepsLegacy() {
      FakePlayer old = legacy("Old");
      FakePlayer steve = modern("Steve");
      host.with(old, steve);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertTrue(old.transfers().isEmpty());
      assertNull(old.kickedWith());
      assertEquals(PROPAGATION, steve.transfers().getFirst().at());
      assertEquals(1, drain.status().kept());
      assertTrue(old.isActive());
    }

    @Test
    @DisplayName(
        "should kick clients older than 1.20.5 with the message, paced, when so configured")
    void kicksLegacy() {
      FakePlayer first = legacy("First");
      FakePlayer second = legacy("Second");
      host.with(first, second);
      DrainController<FakePlayer> drain =
          controller(config(TARGET, DrainConfig.CannotTransfer.KICK));

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION.plus(INTERVAL));

      assertEquals(Component.text(MESSAGE), first.kickedWith());
      assertEquals(PROPAGATION, first.kickedAt());
      assertEquals(PROPAGATION.plus(INTERVAL), second.kickedAt());
      assertEquals(2, drain.status().kicked());
    }

    @Test
    @DisplayName("should keep everyone without a transfer address, and wait for them to leave")
    void noTarget() {
      FakePlayer steve = modern("Steve");
      host.with(steve);
      DrainController<FakePlayer> drain = controller(config(null, DrainConfig.CannotTransfer.KEEP));

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertTrue(steve.transfers().isEmpty());
      assertEquals(DrainPhase.WAITING, drain.status().phase());
      assertEquals(1, drain.status().kept());

      steve.leave();
      clock.advance(DrainController.POLL_INTERVAL);

      assertEquals(DrainOutcome.DRAINED, drain.status().outcome());
    }

    @Test
    @DisplayName("should skip players who left before their turn, without waiting an interval")
    void skipsLeavers() {
      FakePlayer a = modern("A");
      FakePlayer gone = modern("Gone");
      FakePlayer c = modern("C");
      host.with(a, gone, c);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);
      gone.leave();
      clock.advance(INTERVAL);

      assertTrue(gone.transfers().isEmpty());
      assertEquals(PROPAGATION.plus(INTERVAL), c.transfers().getFirst().at());
      assertEquals(2, drain.status().transferred());
    }

    @Test
    @DisplayName("should end phase 1 with its last player, and enter phase 2")
    void phases() {
      host.with(modern("A"), legacy("B"));
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION.plus(INTERVAL));

      assertEquals(
          List.of(DrainPhase.ANNOUNCING, DrainPhase.TRANSFERRING, DrainPhase.WAITING),
          host.phases());
      DrainStatus waiting = host.statuses().getLast();
      assertEquals(1, waiting.transferred());
      assertEquals(1, waiting.kept());
      assertEquals(1, waiting.players());
    }

    @Test
    @DisplayName("should not count a transfer the client could not read yet, and retry on arrival")
    void notReady() {
      FakePlayer joining = modern("Joining").answering(TransferResult.NOT_READY);
      host.with(joining);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertEquals(0, drain.status().transferred());
      assertTrue(joining.isActive());

      joining.answering(TransferResult.SENT);
      drain.playerArrived(joining);
      clock.advance(INTERVAL);

      assertEquals(2, joining.transfers().size());
      assertEquals(1, drain.status().transferred());
    }

    @Test
    @DisplayName("should move on when a transfer is not written in time")
    void transferTimeout() {
      FakePlayer stuck = modern("Stuck").hanging();
      FakePlayer next = modern("Next");
      host.with(stuck, next);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION.plus(DrainController.TRANSFER_TIMEOUT).minusMillis(1));

      assertTrue(next.transfers().isEmpty());

      clock.advance(at(1));

      assertEquals(1, next.transfers().size());
      assertEquals(1, drain.status().transferred());
    }

    @Test
    @DisplayName("should transfer a player once, even if it arrives on a server meanwhile")
    void onlyOnce() {
      FakePlayer steve = modern("Steve").staying();
      host.with(steve);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);
      drain.playerArrived(steve);
      clock.advance(Duration.ofSeconds(5));

      assertEquals(1, steve.transfers().size());
    }
  }

  // ---------------------------------------------------------------------------
  // Plugin decisions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("plugin decisions")
  class Decisions {

    @Test
    @DisplayName("should ask the plugins about each player, with the default as fallback")
    void asks() {
      FakePlayer steve = modern("Steve");
      host.with(steve);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertEquals(
          List.of(new FakeHost.Asked(steve, PlayerDrainEvent.Reason.TRANSFER_PHASE)), host.asked());
      assertEquals(
          new PlayerDrainEvent.Transfer("play.example.net", 25565), drain.defaultDecision(steve));
    }

    @Test
    @DisplayName("should keep a game player, then transfer it once it is back on a lobby")
    void keepsUntilLobby() {
      FakePlayer gamer = modern("Gamer");
      FakePlayer steve = modern("Steve");
      host.with(gamer, steve);
      boolean[] inGame = {true};
      host.deciding(
          (player, fallback) -> player == gamer && inGame[0] ? new PlayerDrainEvent.Keep() : null);
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.SIGNAL);
      clock.advance(PROPAGATION.plus(INTERVAL));

      assertTrue(gamer.transfers().isEmpty());
      assertEquals(1, steve.transfers().size());
      assertEquals(DrainPhase.WAITING, drain.status().phase());

      inGame[0] = false;
      drain.playerArrived(gamer);
      clock.advance(INTERVAL);

      assertEquals(1, gamer.transfers().size());
      assertEquals(PlayerDrainEvent.Reason.SERVER_CHANGE, host.asked().getLast().reason());
      clock.advance(DrainController.POLL_INTERVAL);
      DrainStatus done = host.shutdownStatus();
      assertNotNull(done);
      assertEquals(DrainOutcome.DRAINED, done.outcome());
      assertEquals(2, done.transferred());
      assertEquals(1, done.kept());
    }

    @Test
    @DisplayName("should transfer a player where a plugin says, not to the configured address")
    void customTarget() {
      FakePlayer steve = modern("Steve");
      host.with(steve)
          .deciding((player, fallback) -> new PlayerDrainEvent.Transfer("eu.example.net", 25570));
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertEquals(
          List.of(new FakePlayer.Sent("eu.example.net", 25570, PROPAGATION)), steve.transfers());
    }

    @Test
    @DisplayName("should kick a player with a plugin's reason")
    void kick() {
      FakePlayer steve = modern("Steve");
      host.with(steve)
          .deciding((player, fallback) -> new PlayerDrainEvent.Kick(Component.text("Bye")));
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertEquals(Component.text("Bye"), steve.kickedWith());
      assertEquals(1, drain.status().kicked());
    }

    @Test
    @DisplayName("should fall back to cannot-transfer when a plugin transfers an old client")
    void transferOldClient() {
      FakePlayer old = legacy("Old");
      host.with(old).deciding((player, fallback) -> new PlayerDrainEvent.Transfer("x.net", 1));
      DrainController<FakePlayer> drain =
          controller(config(TARGET, DrainConfig.CannotTransfer.KICK));

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertTrue(old.transfers().isEmpty());
      assertEquals(Component.text(MESSAGE), old.kickedWith());
    }

    @Test
    @DisplayName("should apply the default when the plugins do not decide in time")
    void decisionTimeout() {
      FakePlayer steve = modern("Steve");
      host.with(steve).hangingDecisions();
      DrainController<FakePlayer> drain = controller();

      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION.plus(DrainController.DECISION_TIMEOUT).minusMillis(1));

      assertTrue(steve.transfers().isEmpty());

      clock.advance(at(1));

      assertEquals(1, steve.transfers().size());
    }
  }

  // ---------------------------------------------------------------------------
  // Phases 2 and 3
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("waiting and stopping")
  class WaitingAndStopping {

    @Test
    @DisplayName("should stop as soon as the last player left, within a poll interval")
    void drained() {
      FakePlayer old = legacy("Old");
      host.with(old);
      DrainController<FakePlayer> drain = controller();
      CompletableFuture<DrainStatus> done = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION.plus(Duration.ofSeconds(10)));

      assertFalse(done.isDone());

      old.leave();
      clock.advance(DrainController.POLL_INTERVAL);

      DrainStatus status = settled(done);
      assertEquals(DrainPhase.STOPPING, status.phase());
      assertEquals(DrainOutcome.DRAINED, status.outcome());
      assertSame(status, host.shutdownStatus());
      assertEquals(
          List.of(
              DrainPhase.ANNOUNCING,
              DrainPhase.TRANSFERRING,
              DrainPhase.WAITING,
              DrainPhase.STOPPING),
          host.phases());
    }

    @Test
    @DisplayName("should stop at once when every player was transferred")
    void emptyAfterTransfers() {
      host.with(modern("A"));
      DrainController<FakePlayer> drain = controller();

      CompletableFuture<DrainStatus> done = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertEquals(DrainOutcome.DRAINED, settled(done).outcome());
      assertEquals(PROPAGATION, settled(done).elapsed());
    }

    @Test
    @DisplayName("should stop an empty proxy right after the propagation delay")
    void empty() {
      DrainController<FakePlayer> drain = controller();

      CompletableFuture<DrainStatus> done = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);

      assertTrue(done.isDone());
      assertEquals(0, settled(done).players());
    }

    @Test
    @DisplayName("should time out with players still connected, and stop anyway")
    void timesOut() {
      FakePlayer old = legacy("Old");
      host.with(old);
      DrainController<FakePlayer> drain = controller();

      CompletableFuture<DrainStatus> done = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION.plus(TIMEOUT).minusMillis(1));

      assertFalse(done.isDone());

      clock.advance(at(1));

      DrainStatus status = settled(done);
      assertEquals(DrainOutcome.TIMED_OUT, status.outcome());
      assertEquals(1, status.players(), "the proxy disconnects whoever is left");
      assertEquals(1, host.shutdowns());
    }

    @Test
    @DisplayName("should keep dealing with arrivals in phase 2")
    void arrivalsInPhaseTwo() {
      FakePlayer old = legacy("Old");
      host.with(old);
      DrainController<FakePlayer> drain = controller();
      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION.plus(Duration.ofMinutes(1)));
      FakePlayer late = modern("Late");
      host.with(late);

      drain.playerArrived(late);
      clock.runDue();

      assertEquals(1, late.transfers().size());
    }

    @Test
    @DisplayName("should freeze the elapsed time once stopping, and count refused logins")
    void finalStatus() {
      DrainController<FakePlayer> drain = controller();
      var _ = drain.start(DrainTrigger.HTTP);
      drain.loginRefused();
      drain.loginRefused();
      clock.advance(PROPAGATION);

      clock.advance(Duration.ofMinutes(5));

      DrainStatus status = drain.status();
      assertEquals(PROPAGATION, status.elapsed());
      assertEquals(2, status.refused());
    }

    @Test
    @DisplayName("should ignore arrivals once stopping")
    void noArrivalAfterStop() {
      DrainController<FakePlayer> drain = controller();
      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);
      FakePlayer late = modern("Late");
      host.with(late);

      drain.playerArrived(late);
      clock.advance(Duration.ofSeconds(1));

      assertTrue(late.transfers().isEmpty());
    }

    @Test
    @DisplayName("should drop its steps quietly once the scheduler is shut down")
    void schedulerShutDown() {
      DrainController<FakePlayer> drain = controller();
      var _ = drain.start(DrainTrigger.HTTP);
      clock.advance(PROPAGATION);
      clock.shutdown();

      drain.playerArrived(modern("Late"));

      assertEquals(DrainPhase.STOPPING, drain.status().phase());
    }
  }
}
