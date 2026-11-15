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
package dev.warp.api.fleet;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;

/**
 * The network: every proxy that shares this proxy's store, and every player on them.
 *
 * <p>Without a store configured ({@code fleet.store.type = none}, the default), the fleet is this
 * proxy alone: {@link #nodes()} holds the local node, {@link #players()} the local players, and a
 * {@link #send} to a player elsewhere answers {@link SendResult#UNKNOWN_PLAYER}. Plugin code
 * written against this interface runs unchanged on one proxy and on a fleet.
 *
 * <p>Every read answers from an in-memory replica, without I/O, so it is safe to call from an event
 * handler. The replica is eventually consistent with the store: a player seen by another proxy
 * appears here within one reconcile interval at the latest ({@code fleet.reconcile}, 30 s by
 * default), usually within milliseconds through the store's notices. A player this proxy itself
 * registered is visible at once. Liveness of a node is the store's time-to-live on its heartbeat:
 * no proxy ever compares its clock to another's.
 *
 * <p>Plugins receive the fleet by injection or through {@link dev.warp.api.Warp#fleet()}.
 */
public interface Fleet {

  /**
   * Returns this proxy, as the fleet sees it.
   *
   * @return the local node
   */
  ProxyNode local();

  /**
   * Returns the proxies of the fleet whose heartbeat is alive, this one included.
   *
   * @return the nodes, unmodifiable
   */
  Collection<ProxyNode> nodes();

  /**
   * Returns a node by id.
   *
   * @param id the node id
   * @return the node, or empty when no live node has that id
   */
  Optional<ProxyNode> node(String id);

  /**
   * Returns a snapshot of every player on the network.
   *
   * @return the players, unmodifiable
   */
  Collection<FleetPlayer> players();

  /**
   * Returns the player with a UUID, on any proxy.
   *
   * @param uuid the UUID
   * @return the player, or empty when nobody on the network has it
   */
  Optional<FleetPlayer> player(UUID uuid);

  /**
   * Returns the player with a name, on any proxy, ignoring case.
   *
   * @param name the name
   * @return the player, or empty when nobody on the network has it
   */
  Optional<FleetPlayer> player(String name);

  /**
   * Returns how many players are on the network: the sum of the live nodes' counts, each from its
   * own heartbeat, so that a proxy that died contributes nothing once its heartbeat expired.
   *
   * @return the player count
   */
  int playerCount();

  /**
   * Returns how many players of the network are on a backend, counting every proxy.
   *
   * @param server the server's name
   * @return the player count
   */
  int playerCount(String server);

  /**
   * Connects a player, on whichever proxy it is, to a backend of that proxy. A local player is
   * connected directly; a remote one through a request to its proxy.
   *
   * @param player the player's UUID
   * @param server the server's name, as the player's proxy registers it
   * @return completed with the outcome; never exceptionally
   */
  CompletableFuture<SendResult> send(UUID player, String server);

  /**
   * Disconnects a player, on whichever proxy it is.
   *
   * @param player the player's UUID
   * @param reason shown to the player
   * @return completed with {@code true} when its proxy disconnected it, {@code false} when nobody
   *     on the network has that UUID or its proxy did not answer; never exceptionally
   */
  CompletableFuture<Boolean> kick(UUID player, Component reason);

  /**
   * Sends a chat message to a player, on whichever proxy it is.
   *
   * @param player the player's UUID
   * @param message the message
   * @return completed with {@code true} when its proxy delivered it, {@code false} when nobody on
   *     the network has that UUID or its proxy did not answer; never exceptionally
   */
  CompletableFuture<Boolean> message(UUID player, Component message);

  /**
   * Returns the bus plugins exchange messages on.
   *
   * @return the bus
   */
  FleetBus bus();

  /**
   * Returns how the fleet reaches its store.
   *
   * @return the state
   */
  StoreState storeState();
}
