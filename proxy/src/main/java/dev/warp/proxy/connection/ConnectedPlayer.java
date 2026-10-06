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

import io.netty.channel.EventLoop;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a fully authenticated player connected to the proxy.
 *
 * <p>This is the central entity that survives server switches. It holds references to the client
 * connection (always alive) and the current backend connection (swapped during server switches).
 *
 * <h3>Server switches</h3>
 *
 * <p>The player keeps the switch in progress, the fallback order and the switch watchdog. How the
 * client is moved depends on its version, and is left to a {@link ServerSwitch}: through the
 * configuration phase from 1.20.2, with a Join Game and a Respawn before.
 *
 * <h3>KeepAlive system</h3>
 *
 * <p>The proxy manages its own KeepAlive cycle with the client, independent of backend KeepAlives.
 * This prevents timeout cascades where backend latency causes client disconnections.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Most fields are accessed from the client event loop thread only, which is also the event loop
 * of every backend connection of the player. {@code backendConnection} is volatile because it may
 * be read during server-switch initiation from a different thread.
 */
public final class ConnectedPlayer {

  private static final Logger logger = LoggerFactory.getLogger(ConnectedPlayer.class);

  /** Interval between KeepAlive packets sent to the client. */
  private static final long KEEP_ALIVE_INTERVAL_MS = 15_000;

  /** Maximum time to wait for a KeepAlive response before disconnecting. */
  private static final long KEEP_ALIVE_TIMEOUT_MS = 30_000;

  /** Maximum time for a server switch to complete. */
  private static final long SWITCH_TIMEOUT_MS = 30_000;

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

  /** How this client is moved between servers, chosen from its version. */
  private final ServerSwitch serverSwitch;

  /** What the current server leaves on the client (followed before 1.20.2 only). */
  private final ServerLeftovers leftovers = new ServerLeftovers();

  // ---------------------------------------------------------------------------
  // Mutable state
  // ---------------------------------------------------------------------------

  private volatile @Nullable BackendConnection backendConnection;

  /**
   * The backend whose PLAY packets reach the client: set when a backend enters PLAY with the
   * client, cleared when the client leaves PLAY for a switch. Packets of any other backend (one
   * being left, or one still joining) are dropped.
   */
  private volatile @Nullable MinecraftConnection playingOn;

  private volatile int entityId;
  private volatile @Nullable String currentServerName;
  private volatile boolean switching;
  private volatile @Nullable ServerInfo pendingSwitchTarget;
  private volatile @Nullable ClientInformation cachedClientSettings;

  /** Servers that failed during the current fallback chain. Reset on successful switch. */
  private @Nullable Set<String> failedFallbackServers;

  /**
   * Whether the client is inside a bundle session (between two {@link BundleDelimiter} packets).
   * Terminal packets like {@link dev.warp.protocol.packet.play.StartConfiguration
   * StartConfiguration} must not be sent inside a bundle: the client crashes with "Terminal message
   * received in bundle". See {@link #closeBundle()}.
   */
  private volatile boolean bundleInProgress;

  /** Watchdog of the switch in progress. Cancelled on {@link #switchComplete()}. */
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
    this.serverSwitch = ServerSwitch.of(this);
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

  /**
   * Makes {@code backend} the backend whose PLAY packets reach the client, or none.
   *
   * @param backend the backend connection the client now plays on, or {@code null} while it plays
   *     on none
   */
  void playOn(@Nullable MinecraftConnection backend) {
    this.playingOn = backend;
  }

  /**
   * Returns whether the PLAY packets of {@code backend} reach the client. Checked for every packet
   * a backend forwards: one volatile read and a reference comparison.
   *
   * @param backend a backend connection
   * @return {@code true} if the client plays on {@code backend}
   */
  boolean isPlayingOn(MinecraftConnection backend) {
    return playingOn == backend;
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
   * Returns the name of the server the player is currently connected to, or the default server
   * while it joins its first server.
   *
   * @return the current server name, or {@code null} before the player joins
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

  /**
   * Closes the bundle the client is in, if any, before the proxy sends packets of its own that must
   * not land inside it: a terminal packet such as Start Configuration ("Terminal message received
   * in bundle", Velocity #1384), or the packets of a server switch.
   */
  void closeBundle() {
    if (bundleInProgress) {
      clientConnection.write(new BundleDelimiter());
      bundleInProgress = false;
    }
  }

  /**
   * Returns how this client is moved between servers.
   *
   * @return the server switch strategy for the client's version
   */
  ServerSwitch serverSwitch() {
    return serverSwitch;
  }

  /**
   * Returns what the current server leaves on the client, followed before 1.20.2.
   *
   * @return the leftovers tracker
   */
  ServerLeftovers leftovers() {
    return leftovers;
  }

  // ---------------------------------------------------------------------------
  // Server switching
  // ---------------------------------------------------------------------------

  /**
   * Connects the player to the default server, right after its login. A server that cannot be
   * reached or refuses the player sends it down the fallback order.
   */
  void join() {
    ServerInfo server = loginContext.serverRegistry().defaultServer();
    currentServerName = server.name();
    logger.info("Connecting {} to server '{}'", username, server.name());
    connect(server);
  }

  /**
   * Initiates a server switch to the given target server.
   *
   * <p>Records the switch and lets the {@link ServerSwitch} move the client: a Start Configuration
   * from 1.20.2, a login to the target while the player stays on its server before.
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
    beginSwitch(target);
    logger.info("Switching {} from '{}' to '{}'", username, currentServerName, target.name());
    serverSwitch.start(target);
  }

  /**
   * Records a switch to {@code target} as in progress.
   *
   * @param target the server the player is moving to
   */
  void beginSwitch(ServerInfo target) {
    switching = true;
    pendingSwitchTarget = target;
  }

  /**
   * Connects to the given backend server for the {@link ServerSwitch}, which takes the backend once
   * its connection is up and decides what a connection failure means.
   *
   * <p>Used for the first server, switches and fallbacks alike. On success, the handler chain takes
   * over from the backend login.
   *
   * @param target the server to connect to
   */
  void connect(ServerInfo target) {
    var _ =
        BackendConnection.connect(
                loginContext.channelClass(), this, target, loginContext.forwardingSecret())
            .whenComplete(
                (backend, ex) -> onEventLoop(() -> connectionAttempted(target, backend, ex)));
  }

  private void connectionAttempted(
      ServerInfo target, @Nullable BackendConnection backend, @Nullable Throwable ex) {
    if (!clientConnection.channel().isActive()) {
      switching = false;
      failedFallbackServers = null;
      if (backend != null) {
        backend.disconnect();
      }
      return;
    }
    if (backend == null) {
      logger.warn(
          "Could not connect {} to server '{}': {}",
          username,
          target.name(),
          ex != null ? ex.getMessage() : "no connection");
      serverSwitch.connectFailed(target);
      return;
    }
    serverSwitch.connected(backend);
  }

  /**
   * Runs {@code task} on the client event loop: right away when already on it, so that a backend is
   * taken by the switch before any of its packets is read.
   */
  private void onEventLoop(Runnable task) {
    EventLoop loop = clientConnection.channel().eventLoop();
    if (loop.inEventLoop()) {
      task.run();
    } else {
      loop.execute(task);
    }
  }

  /**
   * Marks the server switch as complete.
   *
   * <p>Called when the client plays on the new server: by {@link
   * ClientPlaySessionHandler#activated()} after the configuration phase, by {@link RespawnSwitch}
   * after the Join Game. Idempotent.
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
    }
  }

  /** Gives up the switch in progress: the player stays on its server. */
  void cancelSwitch() {
    cancelSwitchTimeout();
    switching = false;
    pendingSwitchTarget = null;
    failedFallbackServers = null;
  }

  /**
   * Starts the switch watchdog: if the switch has not completed within {@value #SWITCH_TIMEOUT_MS}
   * ms, the {@link ServerSwitch} gives up on it. Prevents infinite "Reconfiguring..." hangs
   * (Velocity #1741).
   */
  void scheduleSwitchTimeout() {
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
                    serverSwitch.timedOut();
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

  /**
   * Sends a chat message from the proxy to the player.
   *
   * <p>Warp has no chat packet for clients older than 1.19.3 yet (#45): for them the message is
   * only logged, at debug level, rather than failing the connection.
   *
   * @param message the plain text message
   */
  void sendSystemMessage(String message) {
    if (protocolVersion.isOlderThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      logger.debug("No chat packet for {} ({}), not sent: {}", username, protocolVersion, message);
      return;
    }
    byte[] raw = TextComponent.plainText(message, protocolVersion);
    clientConnection.writeAndFlush(new SystemChatMessage(raw, false));
  }

  /**
   * Sends a disconnect reason to the client and then disconnects.
   *
   * <p>Sends the appropriate disconnect packet based on the current protocol state (CONFIG or
   * PLAY).
   */
  void disconnectWithReason(String reason) {
    if (clientConnection.channel().isActive()) {
      byte[] raw = TextComponent.plainText(reason, protocolVersion);
      ProtocolState state = clientConnection.decoder().state();
      if (state == ProtocolState.CONFIGURATION) {
        clientConnection.writeAndFlush(new ConfigDisconnect(raw));
      } else {
        clientConnection.writeAndFlush(new PlayDisconnect(raw));
      }
    }
    disconnect();
  }

  // ---------------------------------------------------------------------------
  // Fallback handling
  // ---------------------------------------------------------------------------

  /**
   * Reports a backend that refused the player, kicked it or closed.
   *
   * <p>Safe to call from any thread: the {@link ServerSwitch} handles it on the client event loop,
   * where it ignores repeated reports and backends the player has already left. Nothing happens
   * once the player itself is gone.
   *
   * @param backend the backend connection that failed
   */
  void scheduleBackendFailure(MinecraftConnection backend) {
    clientConnection
        .channel()
        .eventLoop()
        .execute(
            () -> {
              if (!disconnected.get() && clientConnection.channel().isActive()) {
                serverSwitch.failed(backend);
              }
            });
  }

  /**
   * Moves the player to the next fallback server after {@code failedServerName} failed, or
   * disconnects it when every fallback server has failed.
   *
   * <p>The servers that failed since the last completed switch, and the server the player was on,
   * are skipped. The {@link ServerSwitch} moves the client to the fallback.
   *
   * <p>Must be called on the client event loop.
   *
   * @param failedServerName the name of the server that failed
   */
  void handleBackendFailure(String failedServerName) {
    if (failedFallbackServers == null) {
      failedFallbackServers = new HashSet<>();
    }
    failedFallbackServers.add(failedServerName);
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
    serverSwitch.fallBack(failedServerName, fallback);
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
    cancelSwitchTimeout();
    clientConnection.close();
    BackendConnection backend = this.backendConnection;
    if (backend != null) {
      backend.disconnect();
    }
    onEventLoop(serverSwitch::close);
  }
}
