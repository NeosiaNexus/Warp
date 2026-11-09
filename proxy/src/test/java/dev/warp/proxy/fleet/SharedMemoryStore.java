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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * A store several fleets of one JVM share, as several proxies share Valkey: one {@link
 * SingleNodeStore} behind one handle per node, each with its own state and executor. {@link #kill}
 * drops a node's heartbeat, as the store's time-to-live would.
 */
final class SharedMemoryStore {

  private final SingleNodeStore shared = new SingleNodeStore();
  private final List<Handle> handles = new CopyOnWriteArrayList<>();

  /** Returns a handle for one node. */
  FleetStore handle() {
    Handle handle = new Handle();
    handles.add(handle);
    return handle;
  }

  /** Drops a node's heartbeat, as if it expired. */
  void kill(String nodeId) {
    var _ =
        shared.nodes().join().stream()
            .filter(node -> node.id().equals(nodeId))
            .findFirst()
            .map(node -> removeHeartbeat(node));
  }

  private boolean removeHeartbeat(NodeRecord node) {
    // Reaping with the node's own incarnation removes its heartbeat; its players stay for the
    // peers to reap, as a crash leaves them.
    List<PlayerRecord> players = new ArrayList<>(shared.players(node.id()).join());
    var _ = shared.reap(node.id(), node.incarnation()).join();
    for (PlayerRecord player : players) {
      var _ = shared.claim(player).join();
    }
    return true;
  }

  final class Handle implements FleetStore {
    private volatile StoreState state = StoreState.CONNECTED;
    private volatile Consumer<StoreState> listener = _ -> {};
    private final List<Subscription> subscriptions = new ArrayList<>();
    private volatile @Nullable FleetStoreContext context;

    /** Simulates the backend going away or coming back. */
    void become(StoreState now) {
      state = now;
      FleetStoreContext ctx = context;
      if (ctx != null) {
        ctx.executor().execute(() -> listener.accept(now));
      } else {
        listener.accept(now);
      }
    }

    @Override
    public CompletableFuture<Void> connect(FleetStoreContext context) {
      this.context = context;
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      for (Subscription subscription : subscriptions) {
        subscription.unsubscribe();
      }
    }

    private <T> CompletableFuture<T> guard(CompletableFuture<T> call) {
      if (state == StoreState.DEGRADED) {
        return CompletableFuture.failedFuture(new IllegalStateException("store down"));
      }
      return call;
    }

    @Override
    public CompletableFuture<Long> nextIncarnation(String nodeId) {
      return guard(shared.nextIncarnation(nodeId));
    }

    @Override
    public CompletableFuture<Void> heartbeat(NodeRecord node) {
      return guard(shared.heartbeat(node));
    }

    @Override
    public CompletableFuture<Collection<NodeRecord>> nodes() {
      return guard(shared.nodes());
    }

    @Override
    public CompletableFuture<Collection<PlayerRecord>> players(String nodeId) {
      return guard(shared.players(nodeId));
    }

    @Override
    public CompletableFuture<ClaimResult> claim(PlayerRecord player) {
      return guard(shared.claim(player));
    }

    @Override
    public CompletableFuture<Void> release(UUID player, String nodeId, long incarnation) {
      return guard(shared.release(player, nodeId, incarnation));
    }

    @Override
    public CompletableFuture<Void> move(
        UUID player, String nodeId, long incarnation, @Nullable String server) {
      return guard(shared.move(player, nodeId, incarnation, server));
    }

    @Override
    public CompletableFuture<Integer> reap(String nodeId, long incarnation) {
      return guard(shared.reap(nodeId, incarnation));
    }

    @Override
    public CompletableFuture<Void> publish(String channel, byte[] payload) {
      return guard(shared.publish(channel, payload));
    }

    @Override
    public Subscription subscribe(String channel, Consumer<byte[]> listener) {
      FleetStoreContext ctx = context;
      Subscription subscription =
          shared.subscribe(
              channel,
              payload -> {
                if (state == StoreState.DEGRADED) {
                  return; // a disconnected subscriber misses the message
                }
                if (ctx != null) {
                  ctx.executor().execute(() -> listener.accept(payload));
                } else {
                  listener.accept(payload);
                }
              });
      subscriptions.add(subscription);
      return subscription;
    }

    @Override
    public StoreState state() {
      return state;
    }

    @Override
    public void onStateChange(Consumer<StoreState> listener) {
      this.listener = listener;
    }
  }
}
