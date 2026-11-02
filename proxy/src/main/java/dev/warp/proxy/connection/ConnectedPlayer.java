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

import dev.warp.api.event.player.DisconnectEvent;
import dev.warp.api.event.player.KickedFromServerEvent;
import dev.warp.api.event.player.PlayerChooseInitialServerEvent;
import dev.warp.api.event.player.PostLoginEvent;
import dev.warp.api.event.player.ServerConnectedEvent;
import dev.warp.api.event.player.ServerPostConnectEvent;
import dev.warp.api.event.player.ServerPreConnectEvent;
import dev.warp.api.messaging.Messaging;
import dev.warp.api.messaging.PluginMessageEvent;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.TextComponent;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.config.ConfigPluginMessage;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.player.KickedFromServerEventImpl;
import dev.warp.proxy.event.player.PlayerChooseInitialServerEventImpl;
import dev.warp.proxy.event.player.ServerPreConnectEventImpl;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.messaging.PluginMessageEventImpl;
import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.text.Reasons;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import net.kyori.adventure.text.Component;
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

  // ---------------------------------------------------------------------------
  // API and session state
  // ---------------------------------------------------------------------------

  /** The reason a backend gave when it disconnected the player, kept on its channel. */
  static final AttributeKey<Component> KICK_REASON = AttributeKey.valueOf("warp-kick-reason");

  /** The player as plugins see it. */
  private final WarpPlayer api = new WarpPlayer(this);

  /** Distinct for every login, so that data keyed by session never mixes two logins. */
  private final UUID sessionId = UUID.randomUUID();

  /** Whether this player was authenticated with Mojang. */
  private volatile boolean onlineMode;

  /** Whether {@link PostLoginEvent} was fired: a {@link DisconnectEvent} must follow. */
  private volatile boolean sessionStarted;

  /** Set once the {@link DisconnectEvent} was fired, so that it fires exactly once. */
  private final AtomicBoolean sessionEnded = new AtomicBoolean();

  /** Why the session ends; the first cause set wins. */
  private volatile DisconnectEvent.Cause disconnectCause =
      DisconnectEvent.Cause.CLIENT_DISCONNECTED;

  /** The registered server the player is being connected to. */
  private volatile @Nullable BackendServer connectingTo;

  /** The outcome of the connection a plugin or command requested; client event loop only. */
  private @Nullable CompletableFuture<ConnectionResult> pendingResult;

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

  /**
   * The registered server the player plays on, which counts it; {@code null} before it plays on
   * one, or when the server it plays on was unregistered before it got there.
   */
  private volatile @Nullable BackendServer currentServer;

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
  private volatile long clientLatencyNanos;
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
    this.onlineMode = loginContext.onlineMode();
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
    BackendConnection current = this.backendConnection;
    if (backend != null && current != null && current.connection() == backend) {
      enterServer(current.server());
    }
  }

  /**
   * Counts the player on the registered server it now plays on, and no longer on the previous one.
   * Runs on the client's event loop, so membership changes of one player never interleave.
   *
   * @param server the server the player now plays on
   */
  private void enterServer(ServerInfo server) {
    if (disconnected.get()) {
      return;
    }
    BackendServer target = this.connectingTo;
    BackendServer next =
        target != null && target.info().equals(server)
            ? target
            : loginContext.serverRegistry().registered(server);
    BackendServer previous = this.currentServer;
    if (previous == next) {
      return;
    }
    if (previous != null) {
      previous.removePlayer(this);
    }
    if (next != null) {
      next.addPlayer(this);
    }
    this.currentServer = next;
    if (next != null) {
      notify(new ServerPostConnectEvent(api, next, previous));
    }
  }

  /**
   * Returns the registered server the player plays on.
   *
   * @return the server, or {@code null} before the player plays on one, or if the server it plays
   *     on is not registered
   */
  public @Nullable BackendServer currentServer() {
    return currentServer;
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
   * Starts the player's session right after its login, and connects it to its first server: the one
   * {@link PlayerChooseInitialServerEvent} settles on. A server that cannot be reached or refuses
   * the player sends it down the fallback order.
   */
  void join() {
    startSession();
    BackendServer proposed = loginContext.serverRegistry().initialServer();
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(PlayerChooseInitialServerEventImpl.class)) {
      joinServer(proposed);
      return;
    }
    var _ =
        events
            .fire(new PlayerChooseInitialServerEventImpl(api, proposed), uuid)
            .whenComplete(
                (event, _) ->
                    onEventLoop(() -> joinServer(asBackend(event.initialServer().orElse(null)))));
  }

  /** Starts the session: fires {@link PostLoginEvent}, after which a disconnect event is owed. */
  void startSession() {
    sessionStarted = true;
    notify(new PostLoginEvent(api));
  }

  private void joinServer(@Nullable BackendServer initial) {
    if (!clientConnection.channel().isActive()) {
      return;
    }
    if (initial == null) {
      logger.warn("No server available for {}, disconnecting", username);
      disconnectFor(
          DisconnectEvent.Cause.NO_SERVER_AVAILABLE,
          "No server is available right now. Try again later.");
      return;
    }
    preConnect(initial, true, null, false);
  }

  /**
   * Moves the player to a server at a plugin's or a command's request, through {@link
   * ServerPreConnectEvent}. Thread-safe.
   *
   * @param target the server
   * @param tell whether the player is told when nothing happens (a command it typed)
   * @return completed with the outcome
   */
  CompletableFuture<ConnectionResult> requestConnection(BackendServer target, boolean tell) {
    CompletableFuture<ConnectionResult> result = new CompletableFuture<>();
    onEventLoop(
        () -> {
          if (!clientConnection.channel().isActive()) {
            result.complete(new ConnectionResult.Disconnected());
            return;
          }
          preConnect(target, false, result, tell);
        });
    return result;
  }

  /** Fires {@link ServerPreConnectEvent} if anyone listens, then connects where it allows. */
  private void preConnect(
      BackendServer target,
      boolean initial,
      @Nullable CompletableFuture<ConnectionResult> result,
      boolean tell) {
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(ServerPreConnectEventImpl.class)) {
      connectAllowed(target, initial, result, tell);
      return;
    }
    var event = new ServerPreConnectEventImpl(api, target, initial ? null : currentServer);
    var _ =
        events
            .fire(event, uuid)
            .whenComplete(
                (done, _) ->
                    onEventLoop(
                        () -> {
                          if (!clientConnection.channel().isActive()) {
                            complete(result, new ConnectionResult.Disconnected());
                            return;
                          }
                          switch (done.result()) {
                            case ServerPreConnectEvent.Denied() -> {
                              if (initial) {
                                disconnectFor(
                                    DisconnectEvent.Cause.DISCONNECTED_BY_PROXY,
                                    "You cannot join this server right now.");
                              } else {
                                complete(result, new ConnectionResult.Denied(target));
                                if (tell) {
                                  sendSystemMessage(
                                      "You cannot join " + target.name() + " right now.");
                                }
                              }
                            }
                            case ServerPreConnectEvent.Allowed(RegisteredServer server) -> {
                              BackendServer chosen = asBackend(server);
                              connectAllowed(
                                  chosen != null ? chosen : target, initial, result, tell);
                            }
                          }
                        }));
  }

  private void connectAllowed(
      BackendServer target,
      boolean initial,
      @Nullable CompletableFuture<ConnectionResult> result,
      boolean tell) {
    if (initial) {
      connectingTo = target;
      currentServerName = target.name();
      logger.info("Connecting {} to server '{}'", username, target.name());
      connect(target.info());
      return;
    }
    if (switching) {
      BackendServer pending = connectingTo;
      complete(result, new ConnectionResult.InProgress(pending != null ? pending : target));
      if (tell) {
        sendSystemMessage("Already switching servers.");
      }
      return;
    }
    if (target.name().equals(currentServerName)) {
      complete(result, new ConnectionResult.AlreadyConnected(target));
      if (tell) {
        sendSystemMessage("Already connected to " + target.name() + ".");
      }
      return;
    }
    pendingResult = result;
    connectingTo = target;
    beginSwitch(target.info());
    logger.info("Switching {} from '{}' to '{}'", username, currentServerName, target.name());
    serverSwitch.start(target.info());
  }

  private static @Nullable BackendServer asBackend(@Nullable RegisteredServer server) {
    return server instanceof BackendServer backend ? backend : null;
  }

  private static void complete(
      @Nullable CompletableFuture<ConnectionResult> result, ConnectionResult outcome) {
    if (result != null) {
      result.complete(outcome);
    }
  }

  /**
   * Fires {@link ServerConnectedEvent} once a backend accepted the player. Called by the backend's
   * login handler, on the client event loop.
   *
   * @param backend the backend that logged the player in
   */
  void backendAccepted(MinecraftConnection backend) {
    BackendServer target = connectingTo;
    if (target != null) {
      notify(new ServerConnectedEvent(api, target, currentServer));
    }
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
        connector
            .connect(target)
            .whenComplete(
                (backend, ex) -> onEventLoop(() -> connectionAttempted(target, backend, ex)));
  }

  /** Opens backend connections for the player; replaced in tests. */
  @FunctionalInterface
  interface Connector {
    CompletableFuture<BackendConnection> connect(ServerInfo target);
  }

  private Connector connector = this::openBackend;

  private CompletableFuture<BackendConnection> openBackend(ServerInfo target) {
    return BackendConnection.connect(
        loginContext.channelClass(), this, target, loginContext.forwardingSecret());
  }

  /**
   * Replaces how the player opens backend connections (tests).
   *
   * @param connector the connector
   */
  void setConnector(Connector connector) {
    this.connector = connector;
  }

  /**
   * Ends a switch whose new server could not be reached or refused the player, which still plays on
   * its current server.
   *
   * @param target the server it could not switch to
   */
  void switchFailed(
      ServerInfo target, @Nullable MinecraftConnection backend, KickedFromServerEvent.Cause cause) {
    logger.info(
        "Could not switch {} to '{}' ({}), staying on '{}'",
        username,
        target.name(),
        cause,
        currentServerName);
    serverLost(target.name(), backend, cause, true, true);
  }

  /**
   * Same as {@link #switchFailed(ServerInfo, MinecraftConnection, KickedFromServerEvent.Cause)}.
   */
  void switchFailed(ServerInfo target) {
    switchFailed(target, null, KickedFromServerEvent.Cause.CONNECTION_LOST);
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
      BackendServer reached = connectingTo;
      if (reached != null) {
        complete(pendingResult, new ConnectionResult.Connected(reached));
      }
      pendingResult = null;
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
   * Sends a message from the proxy to the player's chat box, on any protocol version.
   *
   * <p>Must be called while the client is in PLAY state.
   *
   * @param message the plain text to show
   */
  void sendSystemMessage(String message) {
    byte[] raw = TextComponent.plainText(message, protocolVersion);
    clientConnection.writeAndFlush(new SystemChatMessage(raw, false));
  }

  /**
   * Sends a disconnect reason to the client and then disconnects.
   *
   * <p>Sends the disconnect packet of the state the client is in. A player exists from the moment
   * the proxy sends Login Success: a 1.20.2+ client then enters the configuration state at once,
   * while the proxy still reads it in the login state until its acknowledgement arrives, so the
   * reason goes out as a configuration disconnect.
   */
  void disconnectWithReason(String reason) {
    if (clientConnection.channel().isActive()) {
      switch (clientConnection.decoder().state()) {
        case LOGIN -> {
          clientConnection.encoder().setState(ProtocolState.CONFIGURATION);
          clientConnection.writeAndFlush(ConfigDisconnect.ofPlainText(reason, protocolVersion));
        }
        case CONFIGURATION ->
            clientConnection.writeAndFlush(ConfigDisconnect.ofPlainText(reason, protocolVersion));
        case PLAY ->
            clientConnection.writeAndFlush(PlayDisconnect.ofPlainText(reason, protocolVersion));
        case HANDSHAKE, STATUS -> {
          // No player exists in these states.
        }
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
   * Handles the loss of a server the player was playing on or being connected to, while it has no
   * other server to stay on: {@link KickedFromServerEvent} decides between the next fallback and a
   * disconnection.
   *
   * <p>Must be called on the client event loop.
   *
   * @param failedServerName the name of the server that failed
   * @param backend its connection, which carries the reason it gave, or {@code null}
   * @param cause how it was lost, when it gave no reason
   */
  void handleBackendFailure(
      String failedServerName,
      @Nullable MinecraftConnection backend,
      KickedFromServerEvent.Cause cause) {
    BackendServer playing = currentServer;
    boolean duringConnect = playing == null || !playing.name().equals(failedServerName);
    serverLost(failedServerName, backend, cause, duringConnect, false);
  }

  /**
   * Same as {@link #handleBackendFailure(String, MinecraftConnection,
   * KickedFromServerEvent.Cause)}.
   */
  void handleBackendFailure(String failedServerName) {
    handleBackendFailure(failedServerName, null, KickedFromServerEvent.Cause.CONNECTION_LOST);
  }

  /**
   * Records the reason a backend gave when it disconnected or refused the player.
   *
   * @param backend the backend
   * @param reason its reason
   */
  void recordKick(MinecraftConnection backend, Component reason) {
    backend.channel().attr(KICK_REASON).set(reason);
  }

  /**
   * Decides, through {@link KickedFromServerEvent}, what happens to a player that lost a server.
   *
   * <p>The proxy proposes: stay and be told, when the player was being moved and still has its
   * server; otherwise the next registered fallback not tried yet in this chain of failures;
   * otherwise a disconnection. Redirects to a server that already failed in this chain are refused,
   * so that two failing servers never bounce a player forever.
   */
  private void serverLost(
      String lostName,
      @Nullable MinecraftConnection backend,
      KickedFromServerEvent.Cause baseCause,
      boolean duringConnect,
      boolean stillOnCurrent) {
    if (failedFallbackServers == null) {
      failedFallbackServers = new HashSet<>();
    }
    Set<String> excluded = failedFallbackServers;
    excluded.add(lostName);
    // Also exclude the server the player was on before any switch started.
    if (!stillOnCurrent && currentServerName != null) {
      excluded.add(currentServerName);
    }

    Component reason = backend == null ? null : backend.channel().attr(KICK_REASON).get();
    KickedFromServerEvent.Cause cause =
        reason != null ? KickedFromServerEvent.Cause.KICKED : baseCause;
    BackendServer lost = lostServer(lostName);

    KickedFromServerEvent.Result proposed;
    BackendServer fallback = null;
    if (stillOnCurrent) {
      proposed = new KickedFromServerEvent.Notify(couldNotConnect(lostName, reason));
    } else {
      fallback = loginContext.serverRegistry().nextFallback(excluded);
      proposed =
          fallback != null
              ? new KickedFromServerEvent.Redirect(fallback, null)
              : new KickedFromServerEvent.DisconnectPlayer(
                  reason != null ? reason : Component.text(NO_SERVER_LEFT));
    }

    WarpEventBus events = loginContext.events();
    if (lost == null || !events.hasHandlers(KickedFromServerEventImpl.class)) {
      applyLoss(lostName, lost, reason, stillOnCurrent, proposed, proposed);
      return;
    }
    var event = new KickedFromServerEventImpl(api, lost, cause, reason, duringConnect, proposed);
    var _ =
        events
            .fire(event, uuid)
            .whenComplete(
                (done, _) ->
                    onEventLoop(
                        () ->
                            applyLoss(
                                lostName, lost, reason, stillOnCurrent, done.result(), proposed)));
  }

  private void applyLoss(
      String lostName,
      @Nullable BackendServer lost,
      @Nullable Component reason,
      boolean stillOnCurrent,
      KickedFromServerEvent.Result result,
      KickedFromServerEvent.Result proposed) {
    if (disconnected.get() || !clientConnection.channel().isActive()) {
      return;
    }
    CompletableFuture<ConnectionResult> requested = pendingResult;
    if (requested != null && lost != null && lost == connectingTo) {
      pendingResult = null;
      requested.complete(new ConnectionResult.Failed(lost, reason));
    }
    switch (result) {
      case KickedFromServerEvent.Notify(Component message) -> {
        if (stillOnCurrent) {
          cancelSwitch();
          sendSystemMessage(Reasons.plain(message));
        } else {
          disconnectFor(DisconnectEvent.Cause.DISCONNECTED_BY_PROXY, Reasons.plain(message));
        }
      }
      case KickedFromServerEvent.DisconnectPlayer(Component message) -> {
        failedFallbackServers = null;
        switching = false;
        disconnectFor(
            result.equals(proposed)
                ? DisconnectEvent.Cause.NO_SERVER_AVAILABLE
                : DisconnectEvent.Cause.DISCONNECTED_BY_PROXY,
            Reasons.plain(message));
      }
      case KickedFromServerEvent.Redirect(RegisteredServer server, Component message) -> {
        BackendServer target = asBackend(server);
        Set<String> excluded = failedFallbackServers;
        if (target == null
            || target.name().equals(lostName)
            || (excluded != null && excluded.contains(target.name()))) {
          logger.info(
              "Refusing to redirect {} to '{}', which already failed: disconnecting",
              username,
              server.name());
          disconnectFor(DisconnectEvent.Cause.NO_SERVER_AVAILABLE, NO_SERVER_LEFT);
          return;
        }
        if (message != null) {
          sendSystemMessage(Reasons.plain(message));
        }
        redirect(lostName, target);
      }
    }
  }

  /** Connects the player to a fallback, through {@link ServerPreConnectEvent}. */
  private void redirect(String lostName, BackendServer target) {
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(ServerPreConnectEventImpl.class)) {
      connectingTo = target;
      serverSwitch.fallBack(lostName, target.info());
      return;
    }
    var event = new ServerPreConnectEventImpl(api, target, currentServer);
    var _ =
        events
            .fire(event, uuid)
            .whenComplete(
                (done, _) ->
                    onEventLoop(
                        () -> {
                          if (!clientConnection.channel().isActive()) {
                            return;
                          }
                          if (done.result()
                              instanceof ServerPreConnectEvent.Allowed(RegisteredServer server)) {
                            BackendServer chosen = asBackend(server);
                            connectingTo = chosen != null ? chosen : target;
                            serverSwitch.fallBack(lostName, connectingTo.info());
                          } else {
                            disconnectFor(
                                DisconnectEvent.Cause.DISCONNECTED_BY_PROXY,
                                "You cannot join " + target.name() + " right now.");
                          }
                        }));
  }

  private @Nullable BackendServer lostServer(String name) {
    BackendServer target = connectingTo;
    if (target != null && target.name().equals(name)) {
      return target;
    }
    BackendServer playing = currentServer;
    if (playing != null && playing.name().equals(name)) {
      return playing;
    }
    return loginContext.serverRegistry().getServer(name);
  }

  private static Component couldNotConnect(String server, @Nullable Component reason) {
    String text = "Could not connect to " + server;
    if (reason == null || Reasons.plain(reason).isBlank()) {
      return Component.text(text + ".");
    }
    return Component.text(text + ": " + Reasons.plain(reason));
  }

  /** What a player is told when no server can take it. */
  private static final String NO_SERVER_LEFT = "Could not connect to any available server.";

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
        setDisconnectCause(DisconnectEvent.Cause.TIMED_OUT);
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

  /**
   * Disconnects the player from both client and backend, and removes it from the proxy's players at
   * once, so that the same account can log in again right away. Idempotent and thread-safe.
   */
  public void disconnect() {
    if (!disconnected.compareAndSet(false, true)) {
      return;
    }
    loginContext.playerRegistry().unregister(this);
    endSession();
    cancelKeepAliveTask();
    cancelSwitchTimeout();
    clientConnection.close();
    BackendConnection backend = this.backendConnection;
    if (backend != null) {
      backend.disconnect();
    }
    onEventLoop(
        () -> {
          serverSwitch.close();
          leaveServer();
          CompletableFuture<ConnectionResult> requested = pendingResult;
          pendingResult = null;
          complete(requested, new ConnectionResult.Disconnected());
        });
  }

  /**
   * Fires the session's {@link DisconnectEvent}, once, if the session started. Fired as soon as the
   * session ends, before the connection finishes closing, so that it is queued before the events of
   * the account's next session.
   */
  private void endSession() {
    if (sessionStarted && sessionEnded.compareAndSet(false, true)) {
      notify(new DisconnectEvent(api, disconnectCause));
    }
  }

  private void setDisconnectCause(DisconnectEvent.Cause cause) {
    if (disconnectCause == DisconnectEvent.Cause.CLIENT_DISCONNECTED) {
      disconnectCause = cause;
    }
  }

  /** Disconnects the player with a reason, recording why the session ends. */
  void disconnectFor(DisconnectEvent.Cause cause, String reason) {
    setDisconnectCause(cause);
    disconnectWithReason(reason);
  }

  /**
   * Disconnects the player at a plugin's request. Thread-safe.
   *
   * @param reason shown to the player
   */
  void disconnectByPlugin(Component reason) {
    onEventLoop(
        () -> disconnectFor(DisconnectEvent.Cause.DISCONNECTED_BY_PROXY, Reasons.plain(reason)));
  }

  /**
   * Disconnects the player because the same account logged in again and the duplicate login policy
   * keeps the newcomer. The player leaves the registry before this returns; the client is told why
   * on its own event loop. Thread-safe.
   */
  public void disconnectDuplicate() {
    setDisconnectCause(DisconnectEvent.Cause.CONFLICTING_LOGIN);
    loginContext.playerRegistry().unregister(this);
    endSession();
    logger.info("Disconnecting {}: the same account logged in again", username);
    onEventLoop(() -> disconnectWithReason("You logged in from another location"));
  }

  /** Disconnects the player because the proxy is shutting down. Thread-safe. */
  public void disconnectShutdown() {
    setDisconnectCause(DisconnectEvent.Cause.PROXY_SHUTDOWN);
    onEventLoop(() -> disconnectWithReason("The proxy is shutting down."));
  }

  /** Stops counting the player on its server. Runs on the client's event loop. */
  private void leaveServer() {
    BackendServer previous = this.currentServer;
    if (previous != null) {
      previous.removePlayer(this);
      this.currentServer = null;
    }
  }

  // ---------------------------------------------------------------------------
  // API views
  // ---------------------------------------------------------------------------

  /**
   * Returns the player as plugins see it.
   *
   * @return the API view
   */
  public WarpPlayer api() {
    return api;
  }

  /**
   * Returns the identifier of this session.
   *
   * @return the session identifier
   */
  public UUID sessionId() {
    return sessionId;
  }

  /**
   * Returns whether this player was authenticated with Mojang.
   *
   * @return {@code true} in online mode
   */
  public boolean isOnlineMode() {
    return onlineMode;
  }

  /**
   * Records whether this player was authenticated with Mojang (a plugin may force either mode).
   *
   * @param onlineMode {@code true} if authenticated
   */
  void setOnlineMode(boolean onlineMode) {
    this.onlineMode = onlineMode;
  }

  /**
   * Returns the player's profile as the API shows it.
   *
   * @return the profile
   */
  public dev.warp.api.player.GameProfile apiProfile() {
    return new GameProfile(uuid, username, profileProperties).toApi();
  }

  /**
   * Returns the server a switch moves the player to.
   *
   * @return the server, or {@code null} when not switching
   */
  public @Nullable BackendServer pendingServer() {
    return switching ? connectingTo : null;
  }

  /**
   * Returns the latency measured with keep-alives.
   *
   * @return milliseconds, or {@code -1} before the first measure
   */
  public long measuredLatencyMs() {
    return clientLatencyNanos == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(clientLatencyNanos);
  }

  // ---------------------------------------------------------------------------
  // Plugin messages
  // ---------------------------------------------------------------------------

  /**
   * Hands a plugin message on a channel a plugin owns to {@link PluginMessageEvent}. Runs on the
   * client event loop.
   *
   * @param message the message
   * @param fromBackend the backend that sent it, or {@code null} if the client did
   * @return {@code true} if the proxy took the message: the caller must not relay it
   */
  boolean interceptPluginMessage(
      PlayPluginMessage message, @Nullable MinecraftConnection fromBackend) {
    ChannelRegistry.Registration registration = loginContext.channels().lookup(message.channel());
    if (registration == null) {
      return false;
    }
    Messaging.Origin origin =
        fromBackend == null ? Messaging.Origin.PLAYER : Messaging.Origin.SERVER;
    if (!registration.accepts(origin)) {
      logger.debug(
          "Dropped a message on {} from the {} side of {}: plugin {} does not accept it",
          message.channel(),
          origin,
          username,
          registration.owner());
      return true;
    }
    PluginMessageEvent.Source source;
    if (fromBackend == null) {
      source = new PluginMessageEvent.FromPlayer(api);
    } else {
      BackendServer server = serverOf(fromBackend);
      if (server == null) {
        return true; // a backend the player already left
      }
      source = new PluginMessageEvent.FromServer(new WarpServerConnection(this, server));
    }
    var event = new PluginMessageEventImpl(registration.channel(), source, api, message.data());
    var _ =
        loginContext
            .events()
            .fire(event, uuid)
            .whenComplete(
                (done, _) -> {
                  if (done.result() instanceof PluginMessageEvent.Forward) {
                    onEventLoop(() -> relay(message, fromBackend));
                  }
                });
    return true;
  }

  private void relay(PlayPluginMessage message, @Nullable MinecraftConnection fromBackend) {
    if (fromBackend == null) {
      BackendConnection backend = backendConnection;
      if (backend != null && backend.isActive()) {
        backend.connection().writeAndFlush(message);
      }
    } else if (isPlayingOn(fromBackend)) {
      clientConnection.writeAndFlush(message);
    }
  }

  /** The registered server a backend connection of this player belongs to. */
  private @Nullable BackendServer serverOf(MinecraftConnection backend) {
    BackendConnection current = backendConnection;
    if (current == null || current.connection() != backend) {
      return null;
    }
    BackendServer playing = currentServer;
    if (playing != null && playing.info().equals(current.server())) {
      return playing;
    }
    BackendServer target = connectingTo;
    if (target != null && target.info().equals(current.server())) {
      return target;
    }
    return loginContext.serverRegistry().registered(current.server());
  }

  /**
   * Sends a plugin message to the client, in the packet of its state. Thread-safe.
   *
   * @param channel the channel's wire name
   * @param data the payload
   * @return {@code true} if the client was in a state where it can receive one
   */
  boolean sendPluginMessage(String channel, byte[] data) {
    if (!clientConnection.channel().isActive()) {
      return false;
    }
    ProtocolState state = clientConnection.encoder().state();
    if (state != ProtocolState.PLAY && state != ProtocolState.CONFIGURATION) {
      return false;
    }
    byte[] copy = data.clone();
    onEventLoop(
        () -> {
          switch (clientConnection.encoder().state()) {
            case PLAY -> clientConnection.writeAndFlush(new PlayPluginMessage(channel, copy));
            case CONFIGURATION ->
                clientConnection.writeAndFlush(new ConfigPluginMessage(channel, copy));
            default -> logger.debug("Dropped a plugin message to {}: it left the state", username);
          }
        });
    return true;
  }

  /**
   * Sends a plugin message to the server the player plays on. Thread-safe.
   *
   * @param server the server it must still be playing on
   * @param channel the channel's wire name
   * @param data the payload
   * @return {@code true} if the player still plays on that server
   */
  boolean sendPluginMessageToServer(BackendServer server, String channel, byte[] data) {
    BackendConnection backend = backendConnection;
    if (currentServer != server || backend == null || !backend.isActive()) {
      return false;
    }
    byte[] copy = data.clone();
    MinecraftConnection connection = backend.connection();
    onEventLoop(
        () -> {
          switch (connection.encoder().state()) {
            case PLAY -> connection.writeAndFlush(new PlayPluginMessage(channel, copy));
            case CONFIGURATION -> connection.writeAndFlush(new ConfigPluginMessage(channel, copy));
            default -> logger.debug("Dropped a plugin message to {}'s server", username);
          }
        });
    return true;
  }

  /** Fires a notification event on the player's lane, if anyone listens. */
  private void notify(Object event) {
    WarpEventBus events = loginContext.events();
    if (events.hasHandlers(event.getClass())) {
      var _ = events.fire(event, uuid);
    }
  }
}
