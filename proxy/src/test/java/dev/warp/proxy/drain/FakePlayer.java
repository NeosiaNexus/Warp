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

import dev.warp.api.player.TransferResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** A player the drain deals with, recording what it does to it on the virtual clock. */
final class FakePlayer implements DrainCandidate {

  /** A transfer the drain asked for. */
  record Sent(String host, int port, Duration at) {}

  private final UUID session = UUID.randomUUID();
  private final String name;
  private final boolean modern;
  private final ManualScheduler clock;

  private boolean active = true;
  private TransferResult result = TransferResult.SENT;
  private boolean hangs;
  private boolean leavesWhenTransferred = true;
  private final List<Sent> transfers = new ArrayList<>();
  private @Nullable Component kickedWith;
  private @Nullable Duration kickedAt;

  /**
   * Creates a connected player.
   *
   * @param name its name
   * @param modern whether its client supports transfers (1.20.5+)
   * @param clock the drain's scheduler, whose time transfers and kicks are recorded at
   */
  FakePlayer(String name, boolean modern, ManualScheduler clock) {
    this.name = name;
    this.modern = modern;
    this.clock = clock;
  }

  /** Makes later transfers answer {@code next} instead of sending. */
  FakePlayer answering(TransferResult next) {
    this.result = next;
    return this;
  }

  /** Makes later transfers never complete, as for a client that stopped reading. */
  FakePlayer hanging() {
    this.hangs = true;
    return this;
  }

  /** Keeps the player connected once a transfer is sent, as a client ignoring it would. */
  FakePlayer staying() {
    this.leavesWhenTransferred = false;
    return this;
  }

  /** The player leaves by itself. */
  void leave() {
    active = false;
  }

  List<Sent> transfers() {
    return transfers;
  }

  @Nullable Component kickedWith() {
    return kickedWith;
  }

  @Nullable Duration kickedAt() {
    return kickedAt;
  }

  @Override
  public UUID sessionId() {
    return session;
  }

  @Override
  public String username() {
    return name;
  }

  @Override
  public boolean isActive() {
    return active;
  }

  @Override
  public boolean supportsTransfers() {
    return modern;
  }

  @Override
  public CompletableFuture<TransferResult> transfer(String host, int port) {
    transfers.add(new Sent(host, port, clock.elapsed()));
    if (hangs) {
      return new CompletableFuture<>();
    }
    if (result == TransferResult.SENT && leavesWhenTransferred) {
      active = false;
    }
    return CompletableFuture.completedFuture(result);
  }

  @Override
  public void kick(Component reason) {
    kickedWith = reason;
    kickedAt = clock.elapsed();
    active = false;
  }

  @Override
  public String toString() {
    return name;
  }
}
