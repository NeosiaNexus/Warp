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
package dev.warp.proxy.fleet;

import dev.warp.api.event.Subscription;
import dev.warp.api.fleet.StoreState;
import dev.warp.api.fleet.spi.ClaimResult;
import dev.warp.api.fleet.spi.FleetStore;
import dev.warp.api.fleet.spi.FleetStoreContext;
import dev.warp.api.fleet.spi.NodeRecord;
import dev.warp.api.fleet.spi.PlayerRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The store of a proxy alone: everything in memory, always connected, zero configuration. The fleet
 * runs the same code on it as on a shared store, so that plugin code and the commands behave the
 * same with one proxy and with many; messages published here are delivered to the local
 * subscribers, as a shared store would deliver them.
 *
 * <p>Heartbeats never expire: the only node is this one, alive as long as the process is.
 */
public final class SingleNodeStore implements FleetStore {

  private final Map<String, NodeRecord> nodes = new ConcurrentHashMap<>();
  private final Map<UUID, PlayerRecord> players = new ConcurrentHashMap<>();
  private final Map<String, List<Consumer<byte[]>>> subscribers = new ConcurrentHashMap<>();
  private final AtomicLong incarnations = new AtomicLong();
  private volatile Executor executor = Runnable::run;

  @Override
  public CompletableFuture<Void> connect(FleetStoreContext context) {
    this.executor = context.executor();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void close() {
    subscribers.clear();
  }

  @Override
  public CompletableFuture<Long> nextIncarnation(String nodeId) {
    return CompletableFuture.completedFuture(incarnations.incrementAndGet());
  }

  @Override
  public CompletableFuture<Void> heartbeat(NodeRecord node) {
    nodes.put(node.id(), node);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Collection<NodeRecord>> nodes() {
    return CompletableFuture.completedFuture(List.copyOf(nodes.values()));
  }

  @Override
  public CompletableFuture<Collection<PlayerRecord>> players(String nodeId) {
    List<PlayerRecord> owned = new ArrayList<>();
    for (PlayerRecord player : players.values()) {
      if (player.nodeId().equals(nodeId)) {
        owned.add(player);
      }
    }
    return CompletableFuture.completedFuture(owned);
  }

  @Override
  public CompletableFuture<ClaimResult> claim(PlayerRecord player) {
    ClaimResult[] result = new ClaimResult[1];
    players.compute(
        player.uuid(),
        (_, existing) -> {
          if (existing == null || !alive(existing)) {
            result[0] = new ClaimResult.Claimed();
            return player;
          }
          result[0] = new ClaimResult.Owned(existing.nodeId(), existing.incarnation());
          return existing;
        });
    return CompletableFuture.completedFuture(result[0]);
  }

  /** Whether the record's owner heartbeats under the record's incarnation. */
  private boolean alive(PlayerRecord record) {
    NodeRecord owner = nodes.get(record.nodeId());
    return owner != null && owner.incarnation() == record.incarnation();
  }

  @Override
  public CompletableFuture<Void> release(UUID player, String nodeId, long incarnation) {
    players.computeIfPresent(
        player, (_, existing) -> existing.ownedBy(nodeId, incarnation) ? null : existing);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> move(
      UUID player, String nodeId, long incarnation, @Nullable String server) {
    players.computeIfPresent(
        player,
        (_, existing) ->
            existing.ownedBy(nodeId, incarnation)
                ? new PlayerRecord(
                    existing.uuid(),
                    existing.name(),
                    existing.nodeId(),
                    existing.incarnation(),
                    server,
                    existing.since())
                : existing);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Integer> reap(String nodeId, long incarnation) {
    int reaped = 0;
    for (PlayerRecord player : List.copyOf(players.values())) {
      if (player.ownedBy(nodeId, incarnation) && players.remove(player.uuid(), player)) {
        reaped++;
      }
    }
    nodes.computeIfPresent(
        nodeId, (_, existing) -> existing.incarnation() == incarnation ? null : existing);
    return CompletableFuture.completedFuture(reaped);
  }

  @Override
  public CompletableFuture<Void> publish(String channel, byte[] payload) {
    List<Consumer<byte[]>> listeners = subscribers.get(channel);
    if (listeners != null) {
      for (Consumer<byte[]> listener : listeners) {
        executor.execute(() -> listener.accept(payload));
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public Subscription subscribe(String channel, Consumer<byte[]> listener) {
    List<Consumer<byte[]>> listeners =
        subscribers.computeIfAbsent(channel, _ -> new CopyOnWriteArrayList<>());
    listeners.add(listener);
    return new Subscription() {
      private volatile boolean active = true;

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public void unsubscribe() {
        active = false;
        listeners.remove(listener);
      }
    };
  }

  @Override
  public StoreState state() {
    return StoreState.SINGLE_NODE;
  }

  @Override
  public void onStateChange(Consumer<StoreState> listener) {
    // The state never changes.
  }
}
