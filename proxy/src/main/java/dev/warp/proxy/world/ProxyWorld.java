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

import dev.warp.api.player.ClientPlatform;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.world.VirtualWorld;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.antibot.AntiBotStatus;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.connection.WarpPlayer;
import dev.warp.proxy.server.BackendServer;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * The virtual world as the proxy runs it: its settings, the layouts per version, who is in it, and
 * the counters of its checks and holds. The connection handlers do the work on the client's event
 * loop; this class is the registry they report to, and the plugin API over it.
 */
public final class ProxyWorld implements VirtualWorld {

  private final AntiBotConfig.World config;
  private final WorldLayouts layouts;
  private final LongSupplier nanoClock;
  private final Set<ConnectedPlayer> inWorld = ConcurrentHashMap.newKeySet();
  private final LongAdder checks = new LongAdder();
  private final LongAdder checksPassed = new LongAdder();
  private final LongAdder checksRetried = new LongAdder();
  private final LongAdder checksMismatched = new LongAdder();
  private final LongAdder checksFailed = new LongAdder();
  private final LongAdder holds = new LongAdder();
  private final LongAdder holdsReleased = new LongAdder();
  private final LongAdder holdsExpired = new LongAdder();

  /**
   * Creates the world.
   *
   * @param config its settings
   * @param layouts what it declares to each version
   * @param nanoClock the clock of its deadlines
   */
  public ProxyWorld(AntiBotConfig.World config, WorldLayouts layouts, LongSupplier nanoClock) {
    this.config = config;
    this.layouts = layouts;
    this.nanoClock = nanoClock;
  }

  /**
   * Returns the settings.
   *
   * @return the world's section of the configuration
   */
  public AntiBotConfig.World config() {
    return config;
  }

  /**
   * Returns the layouts per version.
   *
   * @return the layouts
   */
  public WorldLayouts layouts() {
    return layouts;
  }

  /**
   * Returns the clock the world's deadlines follow.
   *
   * @return the clock, in nanoseconds
   */
  public LongSupplier nanoClock() {
    return nanoClock;
  }

  /**
   * Returns whether a client can stand in the world: every version from 1.7.2, unless its platform
   * is modded (Forge and NeoForge clients refuse a vanilla world) or Bedrock (Geyser translates
   * movement that is not Java physics).
   *
   * @param version the client's version
   * @param platform the client's platform
   * @return {@code true} when the world has a layout the client accepts
   */
  public boolean accepts(ProtocolVersion version, ClientPlatform platform) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_7_2)
        && !platform.isModded()
        && !(platform instanceof ClientPlatform.Bedrock);
  }

  /**
   * Returns whether a client that passed the probe goes through the world check.
   *
   * @param version the client's version
   * @param platform the client's platform
   * @return {@code true} when the check is on and the client can stand in the world
   */
  public boolean checks(ProtocolVersion version, ClientPlatform platform) {
    return config.check()
        && version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)
        && accepts(version, platform);
  }

  /**
   * Returns whether a player without a server is held in the world rather than disconnected.
   *
   * @param version the client's version
   * @param platform the client's platform
   * @return {@code true} when the hold is on and the client can stand in the world
   */
  public boolean holdsFor(ProtocolVersion version, ClientPlatform platform) {
    return config.hold() && accepts(version, platform);
  }

  // ---------------------------------------------------------------------------
  // Bookkeeping, from the connection handlers
  // ---------------------------------------------------------------------------

  /**
   * A player stands in the world.
   *
   * @param player the player
   */
  public void entered(ConnectedPlayer player) {
    inWorld.add(player);
  }

  /**
   * A player left the world: for a server, or the proxy.
   *
   * @param player the player
   */
  public void left(ConnectedPlayer player) {
    inWorld.remove(player);
  }

  /** A world check began. */
  public void checkStarted() {
    checks.increment();
  }

  /** A world check needed its retry. */
  public void checkRetried() {
    checksRetried.increment();
  }

  /** A world check passed. */
  public void checkPassed() {
    checksPassed.increment();
  }

  /** A world check failed, for any reason. */
  public void checkFailed() {
    checksFailed.increment();
  }

  /** A check's fall did not follow the gravity after its retry: the client was let in untrusted. */
  public void checkMismatched() {
    checksMismatched.increment();
  }

  /** A player is held for want of a server. */
  public void holdStarted() {
    holds.increment();
  }

  /** A held player reached a server. */
  public void holdReleased() {
    holdsReleased.increment();
  }

  /** A held player ran out of time. */
  public void holdExpired() {
    holdsExpired.increment();
  }

  /**
   * Returns the counters, for the status and the metrics.
   *
   * @return a snapshot
   */
  public AntiBotStatus.World status() {
    return new AntiBotStatus.World(
        inWorld.size(),
        checks.sum(),
        checksPassed.sum(),
        checksRetried.sum(),
        checksMismatched.sum(),
        checksFailed.sum(),
        holds.sum(),
        holdsReleased.sum(),
        holdsExpired.sum());
  }

  // ---------------------------------------------------------------------------
  // The plugin API
  // ---------------------------------------------------------------------------

  @Override
  public boolean supports(Player player) {
    ConnectedPlayer connected = connected(player);
    return connected != null
        && accepts(connected.protocolVersion(), connected.clientConnection().clientPlatform());
  }

  @Override
  public CompletableFuture<Void> hold(Player player) {
    ConnectedPlayer connected = connected(player);
    if (connected == null || !supports(player)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("The virtual world cannot hold " + player.username()));
    }
    return connected.holdInWorld();
  }

  @Override
  public boolean holds(Player player) {
    ConnectedPlayer connected = connected(player);
    return connected != null && inWorld.contains(connected);
  }

  @Override
  public Collection<Player> players() {
    return List.copyOf(inWorld.stream().<Player>map(ConnectedPlayer::api).toList());
  }

  @Override
  public CompletableFuture<ConnectionResult> release(Player player, RegisteredServer server) {
    ConnectedPlayer connected = connected(player);
    if (connected == null) {
      return CompletableFuture.completedFuture(new ConnectionResult.Disconnected());
    }
    if (!(server instanceof BackendServer backend)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Not a server of this proxy: " + server.name()));
    }
    return connected.requestConnection(backend, false);
  }

  @Override
  public CompletableFuture<ConnectionResult> release(Player player) {
    ConnectedPlayer connected = connected(player);
    if (connected == null) {
      return CompletableFuture.completedFuture(new ConnectionResult.Disconnected());
    }
    return connected.releaseFromWorld();
  }

  private static @Nullable ConnectedPlayer connected(Player player) {
    return player instanceof WarpPlayer warp ? warp.connected() : null;
  }
}
