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
package dev.warp.api.fleet.spi;

import dev.warp.api.event.Subscription;
import dev.warp.api.fleet.StoreState;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Where the fleet keeps who is online and talks between proxies. Warp ships a single-node store and
 * a Valkey store; a plugin may provide another (NATS, etcd, Kubernetes Leases) and name it in
 * {@code fleet.store.type}.
 *
 * <p>The contract the fleet relies on:
 *
 * <ul>
 *   <li>Every method returns at once; the work completes asynchronously. The fleet never blocks on
 *       a store call without a time-out of its own.
 *   <li>A node is alive while its last {@link #heartbeat} has not expired, on the store's clock.
 *       The store decides expiry alone: {@link #nodes()} returns live nodes only.
 *   <li>{@link #claim}, {@link #release}, {@link #move} and {@link #reap} are atomic with respect
 *       to each other for one player, and ownership is the pair (node, incarnation): a late call
 *       from an earlier incarnation never touches a record a later one wrote.
 *   <li>{@link #publish} is at most once: a subscriber that is disconnected misses the message. The
 *       fleet repairs its view with a periodic reconcile, so a store need not persist messages.
 *   <li>A store that loses its backend reports {@link StoreState#DEGRADED} through {@link
 *       #onStateChange}, reconnects by itself and reports {@link StoreState#CONNECTED} again; the
 *       fleet then replays its presence and reconciles.
 * </ul>
 */
public interface FleetStore extends AutoCloseable {

  /**
   * Connects to the backend. The fleet calls it once, before any other method.
   *
   * @param context the network, the node and the executor for listeners
   * @return completed once the first connection is up, or exceptionally when the backend refuses
   *     (bad credentials, wrong address); an unreachable backend completes normally in {@link
   *     StoreState#DEGRADED} and keeps trying
   */
  CompletableFuture<Void> connect(FleetStoreContext context);

  /** Closes every connection. Idempotent. */
  @Override
  void close();

  /**
   * Returns the next incarnation of a node id: strictly greater than any the store handed out for
   * it before, so that a restarted proxy reaps the records of its previous life.
   *
   * @param nodeId the node id
   * @return completed with the incarnation
   */
  CompletableFuture<Long> nextIncarnation(String nodeId);

  /**
   * Writes a node's heartbeat and renews its time-to-live.
   *
   * @param node the node, as it stands now
   * @return completed once written
   */
  CompletableFuture<Void> heartbeat(NodeRecord node);

  /**
   * Returns the nodes whose heartbeat is alive.
   *
   * @return completed with the nodes, the caller's to keep
   */
  CompletableFuture<Collection<NodeRecord>> nodes();

  /**
   * Returns the players a node claimed and has not released, dead or alive.
   *
   * @param nodeId the node
   * @return completed with the players, the caller's to keep
   */
  CompletableFuture<Collection<PlayerRecord>> players(String nodeId);

  /**
   * Claims a player for a node: writes its record if nobody alive owns it. A record owned by a node
   * absent from {@link #nodes()}, or present with another incarnation, counts as unowned.
   *
   * @param player the record to write, its owner the claiming node and incarnation
   * @return completed with {@link ClaimResult.Claimed}, or {@link ClaimResult.Owned} naming the
   *     live owner, in which case nothing was written
   */
  CompletableFuture<ClaimResult> claim(PlayerRecord player);

  /**
   * Deletes a player's record, if the given node and incarnation own it.
   *
   * @param player the player
   * @param nodeId the node releasing it
   * @param incarnation the node's incarnation
   * @return completed once done, whether or not a record was deleted
   */
  CompletableFuture<Void> release(UUID player, String nodeId, long incarnation);

  /**
   * Changes the server of a player's record, if the given node and incarnation own it.
   *
   * @param player the player
   * @param nodeId the node moving it
   * @param incarnation the node's incarnation
   * @param server the backend, or {@code null} between servers
   * @return completed once done, whether or not a record was changed
   */
  CompletableFuture<Void> move(
      UUID player, String nodeId, long incarnation, @Nullable String server);

  /**
   * Removes a node and the players it still owns under one incarnation: its heartbeat, its player
   * set and every player record owned by exactly (node, incarnation). Idempotent: any number of
   * proxies may reap the same node, and a node reaps its own previous incarnations when it starts.
   *
   * @param nodeId the node
   * @param incarnation the incarnation whose records go
   * @return completed with how many player records were deleted
   */
  CompletableFuture<Integer> reap(String nodeId, long incarnation);

  /**
   * Publishes a message to the subscribers of a channel on every node, this one included.
   *
   * @param channel the channel, already namespaced by the fleet
   * @param payload the bytes
   * @return completed once handed to the backend
   */
  CompletableFuture<Void> publish(String channel, byte[] payload);

  /**
   * Subscribes to a channel. The store subscribes again by itself after a reconnection.
   *
   * @param channel the channel
   * @param listener receives each payload on the context's executor
   * @return the subscription
   */
  Subscription subscribe(String channel, Consumer<byte[]> listener);

  /**
   * Returns how the store reaches its backend.
   *
   * @return the state
   */
  StoreState state();

  /**
   * Registers who hears the state change. One listener; the fleet sets it before {@link #connect}.
   *
   * @param listener receives each new state on the context's executor
   */
  void onStateChange(Consumer<StoreState> listener);
}
