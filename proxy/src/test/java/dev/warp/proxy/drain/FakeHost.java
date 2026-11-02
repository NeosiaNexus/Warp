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

import dev.warp.api.event.player.PlayerDrainEvent;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

/** The proxy as the drain sees it, with the players and plugin decisions a test sets. */
final class FakeHost implements DrainHost<FakePlayer> {

  /** A question the drain asked the plugins. */
  record Asked(FakePlayer player, PlayerDrainEvent.Reason reason) {}

  private final List<FakePlayer> players = new ArrayList<>();
  private final List<Asked> asked = new ArrayList<>();
  private final List<DrainStatus> phases = new ArrayList<>();
  private BiFunction<FakePlayer, PlayerDrainEvent.Decision, PlayerDrainEvent.@Nullable Decision>
      plugins = (player, fallback) -> null;
  private boolean decisionsHang;
  private @Nullable DrainStatus shutdown;
  private int shutdowns;

  /** Connects players. */
  FakeHost with(FakePlayer... connected) {
    players.addAll(List.of(connected));
    return this;
  }

  /**
   * Sets what the plugins decide: a decision, or {@code null} to leave the default.
   *
   * @param decisions from the player and the default decision
   */
  FakeHost deciding(
      BiFunction<FakePlayer, PlayerDrainEvent.Decision, PlayerDrainEvent.@Nullable Decision>
          decisions) {
    this.plugins = decisions;
    return this;
  }

  /** Makes the plugins never answer. */
  FakeHost hangingDecisions() {
    this.decisionsHang = true;
    return this;
  }

  List<Asked> asked() {
    return asked;
  }

  List<DrainPhase> phases() {
    return phases.stream().map(DrainStatus::phase).toList();
  }

  List<DrainStatus> statuses() {
    return phases;
  }

  @Nullable DrainStatus shutdownStatus() {
    return shutdown;
  }

  int shutdowns() {
    return shutdowns;
  }

  @Override
  public List<FakePlayer> players() {
    return players.stream().filter(FakePlayer::isActive).toList();
  }

  @Override
  public int playerCount() {
    return (int) players.stream().filter(FakePlayer::isActive).count();
  }

  @Override
  public CompletableFuture<PlayerDrainEvent.Decision> decide(
      FakePlayer player, PlayerDrainEvent.Reason reason, PlayerDrainEvent.Decision fallback) {
    asked.add(new Asked(player, reason));
    if (decisionsHang) {
      return new CompletableFuture<>();
    }
    PlayerDrainEvent.Decision decision = plugins.apply(player, fallback);
    return CompletableFuture.completedFuture(decision == null ? fallback : decision);
  }

  @Override
  public void phaseChanged(DrainStatus status) {
    phases.add(status);
  }

  @Override
  public void shutdown(DrainStatus status) {
    shutdown = status;
    shutdowns++;
  }
}
