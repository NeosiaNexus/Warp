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
package dev.warp.proxy.connection;

import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.TextComponent;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a fully authenticated player connected to the proxy.
 *
 * <p>This is the central entity that survives server switches. It holds references to the client
 * connection (always alive) and the current backend connection (swapped during server switches).
 *
 * <h3>KeepAlive system</h3>
 *
 * <p>The proxy manages its own KeepAlive cycle with the client, independent of backend KeepAlives.
 * This prevents timeout cascades where backend latency causes client disconnections.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Most fields are accessed from the client event loop thread only. {@code backendConnection} is
 * volatile because it may be read during server-switch initiation from a different thread.
 */
public final class ConnectedPlayer {

  private static final Logger logger = LoggerFactory.getLogger(ConnectedPlayer.class);

  /** Interval between KeepAlive packets sent to the client. */
  private static final long KEEP_ALIVE_INTERVAL_MS = 15_000;

  /** Maximum time to wait for a KeepAlive response before disconnecting. */
  private static final long KEEP_ALIVE_TIMEOUT_MS = 30_000;

  /** Maximum time for a CONFIG phase transition (server switch) to complete. */
  private static final long SWITCH_TIMEOUT_MS = 30_000;

  /** Guards against concurrent or duplicate {@link #disconnect()} calls. */
  private final AtomicBoolean disconnected = new AtomicBoolean();

  /**
   * Guards against double-fire of {@link #scheduleBackendFailure()} (e.g. handleDisconnect +
   * channelInactive).
   */
  private final AtomicBoolean fallbackScheduled = new AtomicBoolean();

  // ---------------------------------------------------------------------------
  // Immutable identity
  // ---------------------------------------------------------------------------

  private final MinecraftConnection clientConnection;
  private final ProtocolVersion protocolVersion;
  private final UUID uuid;
  private final String username;
  private final List<dev.warp.protocol.packet.login.LoginSuccess.Property> profileProperties;
  private final InetSocketAddress remoteAddress;
  private final ServerLoginContext loginContext;

  // ---------------------------------------------------------------------------
  // Mutable state
  // ---------------------------------------------------------------------------

  private volatile @Nullable BackendConnection backendConnection;
  private volatile int entityId;
  private volatile @Nullable String currentServerName;
  private volatile boolean switching;
  private volatile @Nullable ServerInfo pendingSwitchTarget;
  private volatile @Nullable ClientInformation cachedClientSettings;

  /** Servers that failed during the current fallback chain. Reset on successful switch. */
  private @Nullable Set<String> failedFallbackServers;

  /**
   * Whether the client is inside a bundle session (between two {@link
   * dev.warp.protocol.packet.play.BundleDelimiter BundleDelimiter} packets). Terminal packets like
   * {@link StartConfiguration} must NOT be sent inside a bundle — the client will crash with
   * "Terminal message received in bundle".
   */
  private volatile boolean bundleInProgress;

  /** Timeout for CONFIG phase completion. Cancelled on {@link #switchComplete()}. */
  private volatile @Nullable ScheduledFuture<?> switchTimeoutTask;

  // KeepAlive state (accessed from client event loop only)
  private long keepAliveSentTime;
  private long pendingKeepAliveId;
  private boolean keepAliveOutstanding;
  private long clientLatencyNanos;
  private volatile @Nullable ScheduledFuture<?> keepAliveTask;

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  ConnectedPlayer(
      MinecraftConnection clientConnection,
      ProtocolVersion protocolVersion,
      GameProfile profile,
      InetSocketAddress remoteAddress,
      ServerLoginContext loginContext) {
    this.clientConnection = clientConnection;
    this.protocolVersion = protocolVersion;
    this.uuid = profile.uuid();
    this.username = profile.name();
    this.profileProperties = profile.properties();
    this.remoteAddress = remoteAddress;
    this.loginContext = loginContext;
  }

  // ---------------------------------------------------------------------------
  // Identity accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the client-side connection.
   *
   * @return the client connection
   */
  public MinecraftConnection clientConnection() {
    return clientConnection;
  }

  /**
   * Returns the negotiated protocol version.
   *
   * @return the protocol version
   */
  public ProtocolVersion protocolVersion() {
    return protocolVersion;
  }

  /**
   * Returns the player's UUID.
   *
   * @return the UUID
   */
  public UUID uuid() {
    return uuid;
  }

  /**
   * Returns the player's username.
   *
   * @return the username
   */
  public String username() {
    return username;
  }

  /**
   * Returns the player's profile properties (skin, cape).
   *
   * @return the profile properties
   */
  public List<dev.warp.protocol.packet.login.LoginSuccess.Property> profileProperties() {
    return profileProperties;
  }

  /**
   * Returns the player's remote address.
   *
   * @return the remote address
   */
  public InetSocketAddress remoteAddress() {
    return remoteAddress;
  }

  // ---------------------------------------------------------------------------
  // Backend connection
  // ---------------------------------------------------------------------------

  /**
   * Returns the current backend connection, or {@code null} during server switch.
   *
   * @return the backend connection, or {@code null}
   */
  public @Nullable BackendConnection backendConnection() {
    return backendConnection;
  }

  /**
   * Sets the current backend connection.
   *
   * @param backend the new backend connection, or {@code null}
   */
  public void setBackendConnection(@Nullable BackendConnection backend) {
    this.backendConnection = backend;
  }

  // ---------------------------------------------------------------------------
  // Entity ID
  // ---------------------------------------------------------------------------

  /**
   * Returns the player's entity ID from the last JoinGame packet.
   *
   * @return the entity ID
   */
  public int entityId() {
    return entityId;
  }

  /**
   * Updates the player's entity ID.
   *
   * @param entityId the new entity ID
   */
  public void setEntityId(int entityId) {
    this.entityId = entityId;
  }

  // ---------------------------------------------------------------------------
  // Current server
  // ---------------------------------------------------------------------------

  /**
   * Returns the name of the server the player is currently connected to.
   *
   * @return the current server name, or {@code null} during initial connection
   */
  public @Nullable String currentServerName() {
    return currentServerName;
  }

  /**
   * Sets the name of the server the player is connected to.
   *
   * @param name the server name
   */
  void setCurrentServerName(@Nullable String name) {
    this.currentServerName = name;
  }

  // ---------------------------------------------------------------------------
  // Client settings cache
  // ---------------------------------------------------------------------------

  /**
   * Caches the latest client settings for replay during server switches.
   *
   * <p>Called when the client sends {@link ClientInformation} during CONFIG or {@link
   * dev.warp.protocol.packet.play.PlayClientSettings PlayClientSettings} during PLAY. The cached
   * value is replayed to new backends in {@link BackendConfigSessionHandler#activated()} to ensure
   * the backend always receives the player's current settings, even if the client does not resend
   * them during a server switch.
   *
   * @param settings the client settings to cache
   */
  void cacheClientSettings(ClientInformation settings) {
    this.cachedClientSettings = settings;
  }

  /**
   * Returns the most recent client settings, or {@code null} if none have been received.
   *
   * @return the cached settings, or {@code null}
   */
  @Nullable ClientInformation cachedClientSettings() {
    return cachedClientSettings;
  }

  // ---------------------------------------------------------------------------
  // Switch state
  // ---------------------------------------------------------------------------

  /**
   * Returns whether a server switch is in progress.
   *
   * @return {@code true} if switching
   */
  public boolean isSwitching() {
    return switching;
  }

  /**
   * Sets the switching state. Used by handlers to clear the flag on failure.
   *
   * @param switching the new switching state
   */
  void setSwitching(boolean switching) {
    this.switching = switching;
  }

  /**
   * Returns the server login context (shared server-wide configuration).
   *
   * @return the login context
   */
  ServerLoginContext loginContext() {
    return loginContext;
  }

  /**
   * Toggles the bundle session state. Called by {@link BackendPlaySessionHandler} when a {@link
   * dev.warp.protocol.packet.play.BundleDelimiter BundleDelimiter} is forwarded to the client.
   */
  void toggleBundle() {
    this.bundleInProgress = !this.bundleInProgress;
  }

  // ---------------------------------------------------------------------------
  // Server switching
  // ---------------------------------------------------------------------------

  /**
   * Initiates a server switch to the given target server.
   *
   * <p>This sends {@link StartConfiguration} to the client, starting the PLAY → CONFIG → PLAY
   * transition. The actual reconnection happens in {@link #onSwitchAcknowledged(ServerInfo)} when
   * the client acknowledges the reconfiguration.
   *
   * <p>Must be called from the client event loop thread.
   *
   * @param target the target server to switch to
   */
  void switchServer(ServerInfo target) {
    if (switching) {
      sendSystemMessage("Already switching servers.");
      return;
    }
    if (target.name().equals(currentServerName)) {
      sendSystemMessage("Already connected to " + target.name() + ".");
      return;
    }

    switching = true;
    this.pendingSwitchTarget = target;
    logger.info("Switching {} from '{}' to '{}'", username, currentServerName, target.name());

    // Close any active bundle session before sending the terminal StartConfiguration.
    // Sending a terminal packet inside an open bundle crashes the client with
    // "Terminal message received in bundle" (Velocity #1384).
    if (bundleInProgress) {
      clientConnection.write(new BundleDelimiter());
      bundleInProgress = false;
    }

    // Send StartConfiguration to trigger PLAY → CONFIG transition on the client.
    clientConnection.writeAndFlush(new StartConfiguration());
  }

  /**
   * Called when the client acknowledges the reconfiguration request during a server switch.
   *
   * <p>Transitions the client to CONFIGURATION state, disconnects the old backend, and connects to
   * the new backend. The rest of the switch flows naturally through the existing handler chain:
   * backend login → CONFIG relay (blind-forwarded) → PLAY.
   *
   * @param target the target server
   */
  void onSwitchAcknowledged(ServerInfo target) {
    // Transition client to CONFIG state and pause reads until the new backend is ready.
    clientConnection.setState(ProtocolState.CONFIGURATION);
    clientConnection.setAutoRead(false);
    clientConnection.setSessionHandler(new SwitchWaitSessionHandler(this));

    scheduleSwitchTimeout();

    // Disconnect old backend. The old handler's disconnected() checks isSwitching()
    // and will not trigger a player disconnect.
    BackendConnection oldBackend = this.backendConnection;
    this.backendConnection = null;
    if (oldBackend != null) {
      oldBackend.disconnect();
    }

    // Connect to the new backend.
    connectToBackend(target);
  }

  /**
   * Connects to the given backend server.
   *
   * <p>Used by both initial server switches and fallback retries. On success, the natural handler
   * chain takes over (BackendLoginSessionHandler → BackendConfigSessionHandler → PLAY). On failure,
   * {@link #handleBackendFailure(String)} is called to try the next fallback.
   */
  private void connectToBackend(ServerInfo target) {
    var _ =
        BackendConnection.connect(
                loginContext.channelClass(),
                this,
                target.address(),
                loginContext.forwardingSecret())
            .whenComplete(
                (backend, ex) ->
                    clientConnection
                        .channel()
                        .eventLoop()
                        .execute(
                            () -> {
                              if (!clientConnection.channel().isActive()) {
                                switching = false;
                                failedFallbackServers = null;
                                if (backend != null) {
                                  backend.disconnect();
                                }
                                return;
                              }
                              if (ex != null) {
                                logger.error(
                                    "Failed to connect {} to server '{}'",
                                    username,
                                    target.name(),
                                    ex);
                                handleBackendFailure(target.name());
                                return;
                              }
                              this.backendConnection = backend;
                              // currentServerName is updated in switchComplete() when the
                              // CONFIG → PLAY transition finishes, not here — the player is
                              // not yet playing on the new server during login/config.
                              // switching remains true until ClientPlaySessionHandler.activated()
                              // fires after the CONFIG → PLAY transition completes.
                              // BackendLoginSessionHandler.activated() fires next, sending
                              // Handshake + LoginStart. The rest flows naturally through the
                              // existing handler chain.
                            }));
  }

  /**
   * Marks the server switch as complete.
   *
   * <p>Called by {@link ClientPlaySessionHandler#activated()} when the client enters PLAY state
   * after a switch (or initial connection — idempotent).
   */
  void switchComplete() {
    if (switching) {
      cancelSwitchTimeout();
      ServerInfo target = pendingSwitchTarget;
      if (target != null) {
        currentServerName = target.name();
      }
      logger.info("Server switch complete: {} is now on '{}'", username, currentServerName);
      switching = false;
      pendingSwitchTarget = null;
      failedFallbackServers = null;
      fallbackScheduled.set(false);
    }
  }

  /**
   * Starts the switch watchdog: if the player has not reached PLAY on the new server within {@value
   * #SWITCH_TIMEOUT_MS} ms, disconnect them. Prevents infinite "Reconfiguring..." hangs (Velocity
   * #1741).
   */
  private void scheduleSwitchTimeout() {
    cancelSwitchTimeout();
    switchTimeoutTask =
        clientConnection
            .channel()
            .eventLoop()
            .schedule(
                () -> {
                  if (switching) {
                    logger.warn(
                        "Server switch timed out for {} after {}ms", username, SWITCH_TIMEOUT_MS);
                    switching = false;
                    disconnectWithReason("Server switch timed out.");
                  }
                },
                SWITCH_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
  }

  private void cancelSwitchTimeout() {
    ScheduledFuture<?> task = switchTimeoutTask;
    if (task != null) {
      task.cancel(false);
      switchTimeoutTask = null;
    }
  }

  /**
   * Returns the pending switch target, or {@code null} if no switch is in progress.
   *
   * @return the pending switch target
   */
  @Nullable ServerInfo pendingSwitchTarget() {
    return pendingSwitchTarget;
  }

  private void sendSystemMessage(String message) {
    byte[] raw = TextComponent.plainText(message, protocolVersion);
    clientConnection.writeAndFlush(new SystemChatMessage(raw, false));
  }

  /**
   * Sends a disconnect reason to the client and then disconnects.
   *
   * <p>Sends the appropriate disconnect packet based on the current protocol state (CONFIG or
   * PLAY).
   */
  private void disconnectWithReason(String reason) {
    if (clientConnection.channel().isActive()) {
      if (clientConnection.decoder().state() == ProtocolState.CONFIGURATION) {
        clientConnection.writeAndFlush(ConfigDisconnect.ofPlainText(reason, protocolVersion));
      } else {
        clientConnection.writeAndFlush(PlayDisconnect.ofPlainText(reason, protocolVersion));
      }
    }
    disconnect();
  }

  // ---------------------------------------------------------------------------
  // Fallback handling
  // ---------------------------------------------------------------------------

  /**
   * Schedules fallback handling on the client event loop.
   *
   * <p>Safe to call from any thread. Resolves the failed server name from the current switch state
   * (volatile reads) and posts {@link #handleBackendFailure(String)} to the client event loop.
   */
  void scheduleBackendFailure() {
    if (!fallbackScheduled.compareAndSet(false, true)) {
      return;
    }
    ServerInfo target = pendingSwitchTarget;
    String failedName = target != null ? target.name() : currentServerName;
    clientConnection.channel().eventLoop().execute(() -> handleBackendFailure(failedName));
  }

  /**
   * Handles a backend failure by attempting to connect to the next fallback server.
   *
   * <p>If the client is in CONFIG state — during a server switch, or still joining for the first
   * time — the proxy connects it directly to the next fallback backend. A client in PLAY state is
   * moved with a normal server switch ({@link #switchServer(ServerInfo)}).
   *
   * <p>Must be called on the client event loop.
   *
   * @param failedServerName the name of the server that failed, or {@code null}
   */
  void handleBackendFailure(@Nullable String failedServerName) {
    if (failedFallbackServers == null) {
      failedFallbackServers = new HashSet<>();
    }
    if (failedServerName != null) {
      failedFallbackServers.add(failedServerName);
    }
    // Also exclude the server the player was on before any switch started.
    if (currentServerName != null) {
      failedFallbackServers.add(currentServerName);
    }

    ServerInfo fallback = loginContext.serverRegistry().nextFallback(failedFallbackServers);

    if (fallback == null) {
      logger.info("No fallback server available for {}, disconnecting", username);
      failedFallbackServers = null;
      switching = false;
      disconnectWithReason("Could not connect to any available server.");
      return;
    }

    if (switching || clientConnection.decoder().state() == ProtocolState.CONFIGURATION) {
      // The client waits in CONFIG state (a switch, or its initial join): connect it directly.
      logger.info(
          "Could not connect {} to '{}', trying fallback '{}'",
          username,
          failedServerName,
          fallback.name());
      if (!switching) {
        // Initial join: track the fallback as a switch, so the current server is updated and the
        // watchdog applies once it completes.
        switching = true;
        scheduleSwitchTimeout();
      }
      connectToFallbackDuringSwitch(fallback);
    } else {
      // In PLAY state — use the normal switch mechanism.
      logger.info("Backend failed for {}, switching to fallback '{}'", username, fallback.name());
      sendSystemMessage("Connecting to " + fallback.name() + "...");
      switchServer(fallback);
    }
  }

  /**
   * Connects to a fallback server during an active server switch.
   *
   * <p>The client is already in CONFIG state. This method ensures the client is in a clean wait
   * state, disconnects any lingering backend, and connects to the fallback. The natural handler
   * chain (login → CONFIG → PLAY) takes over from there.
   */
  private void connectToFallbackDuringSwitch(ServerInfo target) {
    pendingSwitchTarget = target;

    // Reset client to a clean wait state. Auto-read may have been re-enabled by
    // a prior BackendConfigSessionHandler.activated() before the backend died.
    clientConnection.setAutoRead(false);
    clientConnection.setSessionHandler(new SwitchWaitSessionHandler(this));

    // Disconnect any lingering backend connection.
    BackendConnection oldBackend = this.backendConnection;
    this.backendConnection = null;
    if (oldBackend != null) {
      oldBackend.disconnect();
    }

    connectToBackend(target);
  }

  // ---------------------------------------------------------------------------
  // KeepAlive system
  // ---------------------------------------------------------------------------

  /** Starts the periodic KeepAlive task on the client event loop. Idempotent. */
  void startKeepAliveTask() {
    if (keepAliveTask != null) {
      keepAliveTask.cancel(false);
    }
    // Reset keepalive state to prevent false timeouts after server switch.
    // An in-flight keepalive response may have been swallowed during the switch.
    keepAliveOutstanding = false;
    keepAliveTask =
        clientConnection
            .channel()
            .eventLoop()
            .scheduleAtFixedRate(
                this::sendKeepAlive,
                KEEP_ALIVE_INTERVAL_MS,
                KEEP_ALIVE_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
  }

  /** Cancels the KeepAlive task. */
  void cancelKeepAliveTask() {
    if (keepAliveTask != null) {
      keepAliveTask.cancel(false);
      keepAliveTask = null;
    }
  }

  private void sendKeepAlive() {
    if (!clientConnection.channel().isActive()) {
      cancelKeepAliveTask();
      return;
    }

    if (keepAliveOutstanding) {
      long elapsed = System.nanoTime() - keepAliveSentTime;
      if (elapsed > TimeUnit.MILLISECONDS.toNanos(KEEP_ALIVE_TIMEOUT_MS)) {
        logger.info(
            "Player {} timed out (no KeepAlive response in {}ms)",
            username,
            TimeUnit.NANOSECONDS.toMillis(elapsed));
        clientConnection.close();
        return;
      }
      // Still waiting but not yet timed out — skip this tick.
      return;
    }

    long id = nextKeepAliveId();
    pendingKeepAliveId = id;
    keepAliveSentTime = System.nanoTime();
    keepAliveOutstanding = true;
    clientConnection.writeAndFlush(new KeepAlive(id));
  }

  /**
   * Returns a random keep-alive ID that the client's version carries without loss: any {@code long}
   * from 1.12.2 on, a non-negative {@code int} before (the ID is an int in 1.7 and a VarInt up to
   * 1.12.1), so the client echoes exactly the ID that {@link #handleKeepAliveResponse} expects.
   */
  private long nextKeepAliveId() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return KeepAlive.hasLongId(protocolVersion)
        ? random.nextLong()
        : random.nextInt() & Integer.MAX_VALUE;
  }

  /**
   * Validates and processes a KeepAlive response from the client.
   *
   * @param id the KeepAlive ID from the client
   * @return {@code true} if the ID was valid
   */
  boolean handleKeepAliveResponse(long id) {
    if (!keepAliveOutstanding || id != pendingKeepAliveId) {
      return false;
    }
    clientLatencyNanos = System.nanoTime() - keepAliveSentTime;
    keepAliveOutstanding = false;
    return true;
  }

  /**
   * Returns the client's latency in milliseconds, measured from the last KeepAlive round-trip.
   *
   * @return latency in milliseconds
   */
  public long clientLatencyMs() {
    return TimeUnit.NANOSECONDS.toMillis(clientLatencyNanos);
  }

  // ---------------------------------------------------------------------------
  // Disconnect
  // ---------------------------------------------------------------------------

  /** Disconnects the player from both client and backend. Idempotent and thread-safe. */
  public void disconnect() {
    if (!disconnected.compareAndSet(false, true)) {
      return;
    }
    cancelKeepAliveTask();
    cancelSwitchTimeout();
    clientConnection.close();
    BackendConnection backend = this.backendConnection;
    if (backend != null) {
      backend.disconnect();
    }
  }
}
