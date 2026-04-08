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
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;

import java.net.InetSocketAddress;
import java.util.List;
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

  /** Guards against concurrent or duplicate {@link #disconnect()} calls. */
  private final AtomicBoolean disconnected = new AtomicBoolean();

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

    // Disconnect old backend. The old handler's disconnected() checks isSwitching()
    // and will not trigger a player disconnect.
    BackendConnection oldBackend = this.backendConnection;
    this.backendConnection = null;
    if (oldBackend != null) {
      oldBackend.disconnect();
    }

    // Connect to the new backend.
    var unused =
        BackendConnection.connect(
                loginContext.workerGroup(),
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
                                if (backend != null) {
                                  backend.disconnect();
                                }
                                return;
                              }
                              if (ex != null) {
                                logger.error(
                                    "Failed to switch {} to server '{}'",
                                    username,
                                    target.name(),
                                    ex);
                                switching = false;
                                disconnect();
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
      ServerInfo target = pendingSwitchTarget;
      if (target != null) {
        currentServerName = target.name();
      }
      logger.info("Server switch complete: {} is now on '{}'", username, currentServerName);
      switching = false;
      pendingSwitchTarget = null;
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

    long id = ThreadLocalRandom.current().nextLong();
    pendingKeepAliveId = id;
    keepAliveSentTime = System.nanoTime();
    keepAliveOutstanding = true;
    clientConnection.writeAndFlush(new KeepAlive(id));
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
    clientConnection.close();
    BackendConnection backend = this.backendConnection;
    if (backend != null) {
      backend.disconnect();
    }
  }
}
