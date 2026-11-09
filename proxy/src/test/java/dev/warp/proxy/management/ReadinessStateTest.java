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
package dev.warp.proxy.management;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.warp.api.lifecycle.DrainOutcome;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.metrics.Metrics;
import dev.warp.proxy.metrics.MetricRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReadinessState")
class ReadinessStateTest {

  /** A proxy in a fixed state. */
  private record State(
      boolean started,
      boolean stopping,
      int servers,
      List<String> holds,
      boolean draining,
      DrainPhase phase)
      implements ProxyHealth {

    @Override
    public boolean isStarted() {
      return started;
    }

    @Override
    public Metrics metrics() {
      return new MetricRegistry(0);
    }

    @Override
    public boolean isStopping() {
      return stopping;
    }

    @Override
    public int serverCount() {
      return servers;
    }

    @Override
    public List<String> readinessHolds() {
      return holds;
    }

    @Override
    public boolean isDraining() {
      return draining;
    }

    @Override
    public DrainStatus drainStatus() {
      if (phase == DrainPhase.NONE) {
        return DrainStatus.notDraining(0);
      }
      return new DrainStatus(
          phase,
          DrainTrigger.HTTP,
          Instant.EPOCH,
          Duration.ZERO,
          0,
          0,
          0,
          0,
          0,
          phase == DrainPhase.STOPPING ? DrainOutcome.DRAINED : null);
    }

    @Override
    public CompletableFuture<DrainStatus> drain(DrainTrigger trigger) {
      throw new UnsupportedOperationException();
    }
  }

  private static final State READY = new State(true, false, 1, List.of(), false, DrainPhase.NONE);

  @Test
  @DisplayName("should be ready once started, with a server, no hold and no drain")
  void ready() {
    assertEquals(ReadinessState.READY, ReadinessState.of(READY, true));
    assertEquals("up ready", ReadinessState.READY.agentReply());
    assertEquals("ready", ReadinessState.READY.label());
  }

  @Test
  @DisplayName("should say why it is not ready, the most final reason first")
  void reasons() {
    assertEquals(
        ReadinessState.STARTING,
        ReadinessState.of(new State(false, false, 0, List.of("x"), false, DrainPhase.NONE), true));
    assertEquals(
        ReadinessState.HELD,
        ReadinessState.of(new State(true, false, 0, List.of("x"), false, DrainPhase.NONE), true));
    assertEquals(
        ReadinessState.NO_SERVER,
        ReadinessState.of(new State(true, false, 0, List.of(), false, DrainPhase.NONE), true));
    assertEquals(
        ReadinessState.DRAINING,
        ReadinessState.of(
            new State(true, false, 0, List.of("x"), true, DrainPhase.TRANSFERRING), true));
    assertEquals(
        ReadinessState.STOPPING,
        ReadinessState.of(new State(true, true, 1, List.of(), true, DrainPhase.WAITING), true));
  }

  @Test
  @DisplayName("should stop saying drain once the drain is over, for HAProxy to mark it down")
  void drainOver() {
    State over = new State(true, false, 1, List.of(), true, DrainPhase.STOPPING);

    assertEquals(ReadinessState.STOPPING, ReadinessState.of(over, true));
    assertEquals("down", ReadinessState.STOPPING.agentReply());
    assertEquals("drain", ReadinessState.DRAINING.agentReply());
  }

  @Test
  @DisplayName("should ignore the server count when readiness does not require a server")
  void serverOptional() {
    State none = new State(true, false, 0, List.of(), false, DrainPhase.NONE);

    assertEquals(ReadinessState.READY, ReadinessState.of(none, false));
  }
}
