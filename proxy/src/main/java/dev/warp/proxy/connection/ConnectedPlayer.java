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

import dev.warp.api.command.CommandExecuteEvent;
import dev.warp.api.event.player.DisconnectEvent;
import dev.warp.api.event.player.KickedFromServerEvent;
import dev.warp.api.event.player.PlayerChooseInitialServerEvent;
import dev.warp.api.event.player.PlayerResourcePackStatusEvent;
import dev.warp.api.event.player.PostLoginEvent;
import dev.warp.api.event.player.ServerConnectedEvent;
import dev.warp.api.event.player.ServerPostConnectEvent;
import dev.warp.api.event.player.ServerPreConnectEvent;
import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.messaging.Messaging;
import dev.warp.api.messaging.PluginMessageEvent;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.TransferResult;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.command.CommandTree;
import dev.warp.protocol.command.CommandTreeDecoding;
import dev.warp.protocol.command.NameConflict;
import dev.warp.protocol.packet.ComponentPackets;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigPluginMessage;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ChatAcknowledgement;
import dev.warp.protocol.packet.play.ClearDialog;
import dev.warp.protocol.packet.play.DeclareCommands;
import dev.warp.protocol.packet.play.DeleteChat;
import dev.warp.protocol.packet.play.EntitySoundEffect;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.SoundEffect;
import dev.warp.protocol.packet.play.SoundEventIds;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.StopSound;
import dev.warp.protocol.packet.play.TabCompleteRequest;
import dev.warp.protocol.packet.play.TabCompleteResponse;
import dev.warp.protocol.packet.play.Transfer;
import dev.warp.proxy.antibot.AntiBotService.RoutineLine;
import dev.warp.proxy.command.CommandExecuteEventImpl;
import dev.warp.proxy.command.PlayerAvailableCommandsEventImpl;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.player.KickedFromServerEventImpl;
import dev.warp.proxy.event.player.PlayerChooseInitialServerEventImpl;
import dev.warp.proxy.event.player.ServerPreConnectEventImpl;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.i18n.PlayerMessages;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.messaging.BungeeCordChannel;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.messaging.PluginMessageEventImpl;
import dev.warp.proxy.metrics.ProxyMetrics;
import dev.warp.proxy.metrics.ServerConnectOutcome;
import dev.warp.proxy.server.BackendServer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

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
 * This prevents timeout cascades where backend latency causes client disconnections. It runs from
 * the moment the player joins until it leaves, in whatever state the client reads (configuration or
 * play) and whoever it waits for: its next server, or the plugins deciding where it goes. A vanilla
 * client drops a connection that sends it nothing for 30 s.
 *
 * <h3>Holds</h3>
 *
 * <p>Plugins may keep the player waiting while they decide which server it joins, whether it may,
 * or where it goes after losing one ({@link #holdFor}). The switch watchdog does not count that
 * time, up to the longest the handlers may take.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Most fields are accessed from the client event loop thread only, which is also the event loop
 * of every backend connection of the player. {@code backendConnection} is volatile because it may
 * be read during server-switch initiation from a different thread.
 */
public final class ConnectedPlayer {

  private static final Logger logger = LoggerFactory.getLogger(ConnectedPlayer.class);

  /** The context key of the player's name in the log records about it. */
  public static final String LOG_PLAYER = "player";

  /** The context key of the session identifier in the log records about a player. */
  public static final String LOG_SESSION = "session";

  /** Interval between KeepAlive packets sent to the client. */
  private static final long KEEP_ALIVE_INTERVAL_MS = 15_000;

  /** Maximum time to wait for a KeepAlive response before disconnecting. */
  private static final long KEEP_ALIVE_TIMEOUT_MS = 30_000;

  /**
   * Maximum time a server switch may take, from its start until the new server configures the
   * client (1.20.2+) or sends its Join Game (before), not counting the time plugins hold the
   * player.
   */
  private static final long SWITCH_TIMEOUT_MS = 30_000;

  /**
   * Time a plugin hold may last beyond the sum of its handlers' time-outs before the proxy stops
   * waiting for it and disconnects the player: the delivery of the event, and what the proxy does
   * around it.
   */
  static final long HOLD_GRACE_MS = 5_000;

  /** How long {@link #requestCookie(String)} waits for the client's answer. */
  static final long COOKIE_TIMEOUT_SECONDS = 10;

  /** Guards against concurrent or duplicate {@link #disconnect()} calls. */
  private final AtomicBoolean disconnected = new AtomicBoolean();

  /** Whether the player counts in {@code warp_client_protocol_version}. */
  private final AtomicBoolean versionCounted = new AtomicBoolean();

  /** When the current connection attempt to a backend began. */
  private long connectStartNanos;

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

  /** The boss bars plugins show the player. */
  private final ProxyBossBars bossBars;

  /** The resource packs the client holds, the proxy's and its servers'. */
  private final ResourcePacks resourcePacks;

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

  /** Whether the client arrived by a Transfer packet: its handshake declared intent 3. */
  private final boolean transferred;

  /** The client's cookie traffic, begun during its login (1.20.5+). */
  private final ClientCookies cookies;

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

  /** The server whose loss started the current fallback chain. Client event loop only. */
  private @Nullable String lossServer;

  /** The reason that server gave, if any: told to the player wherever the chain ends. */
  private @Nullable Component lossReason;

  /**
   * What the player is told once it is on the fallback it is moved to: why it left its server.
   * Client event loop only.
   */
  private @Nullable Component arrivalNotice;

  /**
   * Whether the client is inside a bundle session (between two {@link BundleDelimiter} packets).
   * Terminal packets like {@link dev.warp.protocol.packet.play.StartConfiguration
   * StartConfiguration} must not be sent inside a bundle: the client crashes with "Terminal message
   * received in bundle". See {@link #closeBundle()}.
   */
  private volatile boolean bundleInProgress;

  /**
   * The watchdog's next check: the switch in progress running out of time, or the plugin holds in
   * progress overrunning their handlers' time-outs. Cancelled on {@link #switchComplete()}.
   */
  private volatile @Nullable ScheduledFuture<?> switchTimeoutTask;

  // Switch watchdog and plugin holds (client event loop only)

  /** Whether the switch in progress is watched; a hold pauses its time. */
  private boolean switchWatched;

  /** The watchdog's time left, counted only while no plugin holds the player. */
  private long switchTimeLeftNanos;

  /** When the watchdog's time last started running, on the client event loop's clock. */
  private long switchRunningSince;

  /** Plugin decisions the player waits for ({@link #holdFor}). */
  private int holds;

  /** When the holds in progress end at the latest, on the client event loop's clock. */
  private long holdsDeadlineNanos;

  // KeepAlive state (accessed from client event loop only)
  private long keepAliveSentTime;
  private long pendingKeepAliveId;
  private boolean keepAliveOutstanding;

  /** Whether the pending keep-alive waits for the client to settle in a new state. */
  private boolean keepAliveHeld;

  /**
   * A keep-alive the client was sent in a state it has left since, without answering yet: its
   * answer is taken, but no longer waited for ({@link #clientStateChanged}).
   */
  private long staleKeepAliveId;

  private boolean keepAliveStale;
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
      ServerLoginContext loginContext,
      ClientCookies cookies) {
    this.clientConnection = clientConnection;
    this.protocolVersion = protocolVersion;
    this.uuid = profile.uuid();
    this.username = profile.name();
    this.profileProperties = profile.properties();
    this.remoteAddress = remoteAddress;
    this.loginContext = loginContext;
    this.serverSwitch = ServerSwitch.of(this);
    this.onlineMode = loginContext.onlineMode();
    this.bossBars = new ProxyBossBars(this);
    this.resourcePacks = new ResourcePacks(this);
    this.transferred = clientConnection.handshakeIntent() == HandshakeIntent.TRANSFER;
    this.cookies = cookies;
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
      // Forwards that waited for a player to carry them to this server go now.
      loginContext.channels().bungeeCord().arrived(this, next);
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
   * them during a server switch. The player's language is remembered for its next logins, whose
   * refusals come before its client says it again (see {@link PlayerMessages#localeOf}).
   *
   * @param settings the client settings to cache
   */
  void cacheClientSettings(ClientInformation settings) {
    ClientInformation previous = this.cachedClientSettings;
    this.cachedClientSettings = settings;
    if (previous == null || !previous.locale().equals(settings.locale())) {
      Locale locale = PlayerMessages.parseLocale(settings.locale());
      if (locale != null) {
        loginContext.messages().remember(username, locale);
      }
    }
  }

  /**
   * Returns the language to talk to the player in: its client's, else the one it last played in,
   * else the configured default.
   *
   * @return the language
   */
  public Locale locale() {
    ClientInformation settings = cachedClientSettings;
    Locale locale = settings == null ? null : PlayerMessages.parseLocale(settings.locale());
    return locale != null ? locale : loginContext.messages().localeOf(username);
  }

  /**
   * Returns the most recent client settings, or {@code null} if none have been received.
   *
   * @return the cached settings, or {@code null}
   */
  @Nullable ClientInformation cachedClientSettings() {
    return cachedClientSettings;
  }

  /**
   * Returns whether the player lets servers list its name, in the sample of the server list: the
   * "Allow Server Listings" option of 1.18+ clients, always on before. {@code false} until the
   * client sent its settings, as vanilla servers assume.
   *
   * @return {@code true} if the name may be listed
   */
  public boolean allowsServerListing() {
    ClientInformation settings = cachedClientSettings;
    return settings != null && settings.allowServerListings();
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
   *
   * <p>Called on the client event loop once the client reads configuration (1.20.2+) or play
   * packets: the proxy's keep-alives start, and from 1.20.2 the client waits for its first server
   * in a {@link SwitchWaitSessionHandler}, which keeps what it sends meanwhile (its brand, its
   * settings) for that server.
   */
  void join() {
    startSession();
    startKeepAliveTask();
    if (clientConnection.decoder().state() == ProtocolState.CONFIGURATION) {
      clientConnection.setSessionHandler(new SwitchWaitSessionHandler(this));
    }
    BackendServer proposed = loginContext.serverRegistry().initialServer();
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(PlayerChooseInitialServerEventImpl.class)) {
      joinServer(proposed);
      return;
    }
    holdFor(new PlayerChooseInitialServerEventImpl(api, proposed), this::joinChosen);
  }

  /** Applies the result {@link PlayerChooseInitialServerEvent} settled on. Client event loop. */
  private void joinChosen(PlayerChooseInitialServerEventImpl event) {
    switch (event.result()) {
      case PlayerChooseInitialServerEvent.Allowed(RegisteredServer server) ->
          joinServer(asBackend(server));
      case PlayerChooseInitialServerEvent.Denied(Component reason) -> {
        if (!clientConnection.channel().isActive()) {
          return;
        }
        if (reason.equals(PlayerChooseInitialServerEventImpl.NO_SERVER_AVAILABLE)) {
          logger.warn(
              LogCode.NO_SERVER_AVAILABLE.marker(),
              "No server available for {}, disconnecting",
              username);
        } else {
          logger.info("A plugin turned {} away before its first server", username);
        }
        disconnectFor(DisconnectEvent.Cause.NO_SERVER_AVAILABLE, reason);
      }
    }
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
      logger.warn(
          LogCode.NO_SERVER_AVAILABLE.marker(),
          "No server available for {}, disconnecting",
          username);
      disconnectFor(
          DisconnectEvent.Cause.NO_SERVER_AVAILABLE,
          PlayerChooseInitialServerEventImpl.NO_SERVER_AVAILABLE);
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
  public CompletableFuture<ConnectionResult> requestConnection(BackendServer target, boolean tell) {
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
    if (initial) {
      // The player waits for this decision to join any server.
      holdFor(event, done -> preConnected(done, target, true, result, tell));
      return;
    }
    // The player plays on its server meanwhile: nothing waits for the decision.
    var _ =
        events
            .fire(event, uuid)
            .whenComplete(
                (done, _) -> onEventLoop(() -> preConnected(done, target, false, result, tell)));
  }

  /** Connects where {@link ServerPreConnectEvent} allows, if anywhere. Client event loop. */
  private void preConnected(
      ServerPreConnectEventImpl done,
      BackendServer target,
      boolean initial,
      @Nullable CompletableFuture<ConnectionResult> result,
      boolean tell) {
    if (!clientConnection.channel().isActive()) {
      complete(result, new ConnectionResult.Disconnected());
      return;
    }
    switch (done.result()) {
      case ServerPreConnectEvent.Denied() -> {
        if (initial) {
          disconnectFor(
              DisconnectEvent.Cause.DISCONNECTED_BY_PROXY, Message.INITIAL_SERVER_DENIED.of());
        } else {
          loginContext.metrics().serverSwitch(ProxyMetrics.SwitchOutcome.CANCELLED);
          complete(result, new ConnectionResult.Denied(target));
          if (tell) {
            sendMessage(Message.CONNECT_DENIED.of(target.name()));
          }
        }
      }
      case ServerPreConnectEvent.Allowed(RegisteredServer server) -> {
        BackendServer chosen = asBackend(server);
        connectAllowed(chosen != null ? chosen : target, initial, result, tell);
      }
    }
  }

  private void connectAllowed(
      BackendServer target,
      boolean initial,
      @Nullable CompletableFuture<ConnectionResult> result,
      boolean tell) {
    if (initial) {
      connectingTo = target;
      currentServerName = target.name();
      logConnection("Connecting {} to server '{}'", username, target.name());
      connect(target.info());
      return;
    }
    if (switching) {
      BackendServer pending = connectingTo;
      complete(result, new ConnectionResult.InProgress(pending != null ? pending : target));
      if (tell) {
        sendMessage(Message.ALREADY_SWITCHING.of());
      }
      return;
    }
    if (target.name().equals(currentServerName)) {
      complete(result, new ConnectionResult.AlreadyConnected(target));
      if (tell) {
        sendMessage(Message.ALREADY_ON_SERVER.of(target.name()));
      }
      return;
    }
    pendingResult = result;
    connectingTo = target;
    beginSwitch(target.info());
    logConnection("Switching {} from '{}' to '{}'", username, currentServerName, target.name());
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
      loginContext
          .metrics()
          .serverConnect(
              target.name(),
              ServerConnectOutcome.SUCCESS,
              connectStartNanos == 0 ? -1 : System.nanoTime() - connectStartNanos);
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
      sendMessage(Message.ALREADY_SWITCHING.of());
      return;
    }
    if (target.name().equals(currentServerName)) {
      sendMessage(Message.ALREADY_ON_SERVER.of(target.name()));
      return;
    }
    beginSwitch(target);
    logConnection("Switching {} from '{}' to '{}'", username, currentServerName, target.name());
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
    connectStartNanos = System.nanoTime();
    var _ =
        connector
            .connect(target)
            .whenComplete(
                (backend, ex) -> onEventLoop(() -> connectionAttempted(target, backend, ex)));
  }

  /**
   * Returns the registered server a connection to {@code target} leads to: the one the player is
   * being connected to, or else the one registered with that name and address.
   *
   * @param target the server connected to
   * @return the server, or {@code null} if it is neither
   */
  @Nullable BackendServer resolveServer(ServerInfo target) {
    BackendServer pending = connectingTo;
    if (pending != null && pending.info().equals(target)) {
      return pending;
    }
    return loginContext.serverRegistry().registered(target);
  }

  /** Opens backend connections for the player; replaced in tests. */
  @FunctionalInterface
  interface Connector {
    CompletableFuture<BackendConnection> connect(ServerInfo target);
  }

  private Connector connector = this::openBackend;

  private CompletableFuture<BackendConnection> openBackend(ServerInfo target) {
    return BackendConnection.connect(loginContext, this, target);
  }

  /**
   * Replaces how the player opens backend connections (tests).
   *
   * @param connector the connector
   */
  void setConnector(Connector connector) {
    this.connector = connector;
  }

  /** The {@code outcome} of a connection attempt that got no backend connection. */
  static ServerConnectOutcome connectFailure(@Nullable Throwable ex) {
    Throwable cause = ex;
    while (cause != null) {
      // Netty's ConnectTimeoutException extends ConnectException: timeouts come first.
      if (cause instanceof io.netty.channel.ConnectTimeoutException
          || cause instanceof java.util.concurrent.TimeoutException
          || cause instanceof io.netty.handler.timeout.TimeoutException) {
        return ServerConnectOutcome.TIMEOUT;
      }
      if (cause instanceof java.net.ConnectException) {
        return ServerConnectOutcome.REFUSED;
      }
      if (cause instanceof java.nio.channels.ClosedChannelException
          || cause instanceof java.io.IOException) {
        return ServerConnectOutcome.RESET;
      }
      cause = cause.getCause();
    }
    return ServerConnectOutcome.OTHER;
  }

  /** Counts the player in {@code warp_client_protocol_version}, once registered. */
  void countVersion() {
    if (versionCounted.compareAndSet(false, true)) {
      loginContext.metrics().playerVersion(protocolVersion(), 1);
    }
  }

  /** Undoes {@link #countVersion()} for a player refused after registration, or disconnected. */
  void uncountVersion() {
    if (versionCounted.compareAndSet(true, false)) {
      loginContext.metrics().playerVersion(protocolVersion(), -1);
    }
  }

  /**
   * Ends a switch whose new server could not be reached or refused the player, which still plays on
   * its current server.
   *
   * @param target the server it could not switch to
   */
  void switchFailed(
      ServerInfo target, @Nullable MinecraftConnection backend, KickedFromServerEvent.Cause cause) {
    loginContext.metrics().serverSwitch(ProxyMetrics.SwitchOutcome.FAILED);
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
      loginContext.metrics().serverConnect(target.name(), connectFailure(ex), -1);
      logger.warn(
          LogCode.BACKEND_CONNECT_FAILED.marker(),
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
  void onEventLoop(Runnable task) {
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
      loginContext.metrics().serverSwitch(ProxyMetrics.SwitchOutcome.SUCCESS);
      cancelSwitchTimeout();
      ServerInfo target = pendingSwitchTarget;
      if (target != null) {
        currentServerName = target.name();
      }
      logConnection("Server switch complete: {} is now on '{}'", username, currentServerName);
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
   * Starts the switch watchdog for an attempt to reach a server: if the server has not taken the
   * player within {@value #SWITCH_TIMEOUT_MS} ms, the {@link ServerSwitch} gives up on it. Prevents
   * infinite "Reconfiguring..." hangs (Velocity #1741). Each attempt (a switch, each fallback) gets
   * the whole time again.
   *
   * <p>Time plugins hold the player does not count ({@link #holdFor}), and the watchdog stops once
   * the new server configures the client ({@link #configurationStarted()}).
   */
  void scheduleSwitchTimeout() {
    switchWatched = true;
    switchTimeLeftNanos = TimeUnit.MILLISECONDS.toNanos(SWITCH_TIMEOUT_MS);
    switchRunningSince = clock();
    armWatchdog();
  }

  /**
   * Stops the switch watchdog once the new server configures the client (1.20.2+). From then on
   * that server decides how long the configuration lasts: a resource pack to download, a dialog or
   * a code of conduct to read can keep a player there for minutes, as on a direct connection.
   * Nothing can hang unseen meanwhile: the proxy's keep-alives watch the client, the server's
   * keep-alives and the read time-out watch the server.
   */
  void configurationStarted() {
    cancelSwitchTimeout();
  }

  /**
   * Schedules the watchdog's next check: while plugins hold the player, when their holds overrun
   * the time-outs their handlers declared; otherwise, while a switch is watched, when its time runs
   * out. Nothing is scheduled when neither applies.
   */
  private void armWatchdog() {
    cancelWatchdogTask();
    long delay;
    if (holds > 0) {
      delay = holdsDeadlineNanos - clock();
    } else if (switchWatched) {
      delay = switchTimeLeftNanos;
    } else {
      return;
    }
    switchTimeoutTask =
        clientConnection
            .channel()
            .eventLoop()
            .schedule(this::watchdogExpired, Math.max(0, delay), TimeUnit.NANOSECONDS);
  }

  private void watchdogExpired() {
    switchTimeoutTask = null;
    if (disconnected.get() || !clientConnection.channel().isActive()) {
      return;
    }
    if (holds > 0) {
      // Asynchronous handlers are skipped at their time-out: only a synchronous one blocking past
      // its own gets here. The player cannot wait for ever.
      logger.warn(
          LogCode.PLAYER_HELD_TOO_LONG.marker(),
          "Plugins held {} longer than their handlers' time-outs allow: disconnecting",
          username);
      disconnectFor(DisconnectEvent.Cause.DISCONNECTED_BY_PROXY, Message.WAIT_TIMED_OUT.of());
      return;
    }
    if (switchWatched && switching) {
      switchWatched = false;
      logger.warn(
          LogCode.SERVER_SWITCH_TIMED_OUT.marker(),
          "Server switch timed out for {} after {}ms",
          username,
          SWITCH_TIMEOUT_MS);
      serverSwitch.timedOut();
    }
  }

  /** Stops watching the switch; a hold in progress stays bounded, unless the player is gone. */
  private void cancelSwitchTimeout() {
    switchWatched = false;
    if (holds == 0 || disconnected.get()) {
      cancelWatchdogTask();
    }
  }

  private void cancelWatchdogTask() {
    ScheduledFuture<?> task = switchTimeoutTask;
    if (task != null) {
      task.cancel(false);
      switchTimeoutTask = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Plugin holds
  // ---------------------------------------------------------------------------

  /**
   * Fires an event the player's next move waits for (which server it joins first, whether it may
   * join one, where it goes after losing one), then runs {@code then} with the event on the client
   * event loop. Called on the client event loop.
   *
   * <p>Plugins may hold such an event as long as their handlers' time-outs allow, and the player is
   * held meanwhile, in whatever state its client is. The proxy keeps the client alive: its
   * keep-alives go on in the client's state and their answers are read ({@link
   * SwitchWaitSessionHandler}, {@link ClientPlaySessionHandler}, before 1.20.2 the login handler
   * until the first Join Game). The switch watchdog does not count the hold.
   *
   * <p>The hold is bounded all the same: by the time-outs of the event's handlers, after those of
   * the events queued before it on the player's lane ({@link WarpEventBus#fireBounded}), plus
   * {@value #HOLD_GRACE_MS} ms. Asynchronous handlers are skipped at their time-out, so only a
   * synchronous handler blocking past its own can overrun it: the player is then disconnected
   * rather than kept waiting for ever.
   *
   * @param event the event
   * @param then what follows the plugins' decision
   * @param <E> the event type
   */
  private <E> void holdFor(E event, Consumer<E> then) {
    WarpEventBus.BoundedDelivery<E> delivery = loginContext.events().fireBounded(event, uuid);
    beginHold(delivery.limit());
    var _ =
        delivery
            .result()
            .whenComplete(
                (done, _) ->
                    onEventLoop(
                        () -> {
                          endHold();
                          then.accept(done);
                        }));
  }

  /** Counts a hold that ends within {@code limit}, and pauses the switch watchdog for it. */
  private void beginHold(Duration limit) {
    long now = clock();
    long deadline = now + limit.toNanos() + TimeUnit.MILLISECONDS.toNanos(HOLD_GRACE_MS);
    if (holds == 0 || deadline - holdsDeadlineNanos > 0) {
      holdsDeadlineNanos = deadline;
    }
    if (holds++ == 0 && switchWatched) {
      switchTimeLeftNanos -= now - switchRunningSince;
    }
    armWatchdog();
  }

  /** Ends a hold: once none is left, the switch watchdog's time runs again from where it was. */
  private void endHold() {
    if (holds == 0 || --holds > 0) {
      return;
    }
    switchRunningSince = clock();
    armWatchdog();
  }

  /**
   * Returns the client event loop's clock: {@link System#nanoTime()} on a real event loop, the
   * virtual time of an embedded one in tests.
   */
  private long clock() {
    return clientConnection.channel().eventLoop().ticker().nanoTime();
  }

  /**
   * Reads the client again once the server it played on is gone. That server may have paused it
   * (back-pressure, {@link BackendPlaySessionHandler#writabilityChanged}) and no longer resumes it,
   * while the client must be read for its keep-alive answers as it waits for its next server. A
   * client waiting in the configuration phase is read as far as {@link SwitchWaitSessionHandler}
   * allows.
   */
  void readClientWhileWaiting() {
    if (clientConnection.sessionHandler() instanceof SwitchWaitSessionHandler waiting) {
      waiting.readAnswers();
    } else {
      clientConnection.setAutoRead(true);
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
   * Sends a message from the proxy to the player's chat box, on any protocol version (see {@link
   * #sendMessage(Component)}).
   *
   * @param message the plain text to show
   */
  void sendSystemMessage(String message) {
    sendMessage(Component.text(message));
  }

  // ---------------------------------------------------------------------------
  // Components: what the plugin API's Player delegates to
  //
  // Every method is safe from any thread. The component is rendered for the player's locale
  // (GlobalTranslator) and encoded for its version on the calling thread, which keeps that work off
  // the event loop; the packets are then written from the client's event loop.
  //
  // disconnect(Component) belongs here too, and sits next to disconnect() below.
  //
  // During a server switch: a client from 1.20.2 spends it in the configuration phase, which has
  // none of these packets, and the client reads configuration packets from the moment the proxy
  // sends Start Configuration (BungeeCord #3519, Velocity #1723). So the proxy's chat messages and
  // titles wait until the client is in a game again, right after its next Join Game, as they do
  // between the login and the first Join Game; the action bar, which a plugin refreshes and which
  // is stale by then, is dropped. Before 1.20.2 a switch keeps the client in PLAY and they are sent
  // at once. The header and footer are kept and sent again after every Join Game: the
  // configuration phase blanks them, and so does the proxy before 1.20.2 (ServerLeftovers).
  // ---------------------------------------------------------------------------

  /** Most packets kept for a client that is not in a game; later ones are dropped. */
  private static final int MAX_PENDING_PACKETS = 128;

  /** Time the client has to receive its disconnect packet before the connection is closed. */
  private static final long DISCONNECT_FLUSH_TIMEOUT_MS = 5_000;

  /** Packets the proxy sent while the client was not in a game. Client event loop only. */
  private final ArrayDeque<PlayPacket> pendingPackets = new ArrayDeque<>();

  /**
   * Whether the client is in a game: it received a Join Game, and no Start Configuration since.
   * Client event loop only.
   */
  private boolean inGame;

  /** Whether pending packets were dropped since the client was last in a game. */
  private boolean droppedPending;

  private volatile Component playerListHeader = Component.empty();
  private volatile Component playerListFooter = Component.empty();

  /**
   * Shows a message in the player's chat box.
   *
   * @param message the message
   * @throws IllegalArgumentException if the message is too large for the player's client
   */
  public void sendMessage(Component message) {
    PlayPacket packet = chatPacket(message);
    if (packet != null) {
      sendPlay(List.of(packet), false);
    }
  }

  /**
   * Returns the chat packet of a message, rendered for the player.
   *
   * @return the packet, or {@code null} for one of the proxy's messages the operator emptied
   * @throws IllegalArgumentException if the message is too large for the player's client
   */
  private @Nullable PlayPacket chatPacket(Component message) {
    Component rendered = render(message);
    if (message instanceof TranslatableComponent translatable
        && Message.byKey(translatable.key()) != null
        && PlayerMessages.isEmpty(rendered)) {
      return null;
    }
    return ComponentPackets.chat(rendered, protocolVersion);
  }

  /**
   * Shows a message above the player's hotbar. Nothing happens on 1.7, which has no action bar, nor
   * while the player switches servers from 1.20.2.
   *
   * @param message the message
   * @throws IllegalArgumentException if the message is too large for the player's client
   */
  public void sendActionBar(Component message) {
    PlayPacket packet = ComponentPackets.actionBar(render(message), protocolVersion);
    if (packet != null) {
      sendPlay(List.of(packet), true);
    }
  }

  /**
   * Shows a title: its times, its subtitle, then the title. Nothing happens on 1.7.
   *
   * @param title the title
   * @throws IllegalArgumentException if a component is too large for the player's client
   */
  public void showTitle(Title title) {
    Title rendered = Title.title(render(title.title()), render(title.subtitle()), title.times());
    sendPlay(ComponentPackets.title(rendered, protocolVersion), false);
  }

  /**
   * Sets one part of the title: the title itself, which shows it, the subtitle or the times.
   * Nothing happens on 1.7.
   *
   * @param part the part
   * @param value its value
   * @param <T> the type of the part
   * @throws IllegalArgumentException if a component is too large for the player's client, or the
   *     part is unknown
   */
  public <T> void sendTitlePart(TitlePart<T> part, T value) {
    @SuppressWarnings("unchecked") // a Component part takes a Component
    T rendered = value instanceof Component component ? (T) render(component) : value;
    PlayPacket packet = ComponentPackets.titlePart(part, rendered, protocolVersion);
    if (packet != null) {
      sendPlay(List.of(packet), false);
    }
  }

  /** Hides the title on screen, keeping its times. Nothing happens on 1.7. */
  public void clearTitle() {
    sendPlay(ComponentPackets.clearTitle(protocolVersion), false);
  }

  /** Hides the title and resets its times to their defaults. Nothing happens on 1.7. */
  public void resetTitle() {
    sendPlay(ComponentPackets.resetTitle(protocolVersion), false);
  }

  /**
   * Sets the text above and below the player's tab list. They stay across server switches: the
   * proxy shows them again after each Join Game, until they are set again; a server may show its
   * own in the meantime. Nothing happens on 1.7.
   *
   * @param header the header, empty to hide it
   * @param footer the footer, empty to hide it
   * @throws IllegalArgumentException if a component is too large for the player's client
   */
  public void sendPlayerListHeaderAndFooter(Component header, Component footer) {
    PlayPacket packet =
        ComponentPackets.headerAndFooter(render(header), render(footer), protocolVersion);
    playerListHeader = header;
    playerListFooter = footer;
    if (packet != null) {
      sendPlay(List.of(packet), true);
    }
  }

  /**
   * Returns the header the proxy last set above the player's tab list.
   *
   * @return the header, empty if none
   */
  public Component playerListHeader() {
    return playerListHeader;
  }

  /**
   * Returns the footer the proxy last set below the player's tab list.
   *
   * @return the footer, empty if none
   */
  public Component playerListFooter() {
    return playerListFooter;
  }

  /**
   * Called on the client event loop once the client has received a Join Game: it is in a game, so
   * the packets kept for it are sent, then the header and footer the proxy set.
   */
  void joinedGame() {
    inGame = true;
    Component notice = arrivalNotice;
    arrivalNotice = null;
    if (notice != null) {
      try {
        PlayPacket packet = chatPacket(notice);
        if (packet != null) {
          pendingPackets.addFirst(packet);
        }
      } catch (IllegalArgumentException tooLarge) {
        logger.debug("Fallback notice to {} too large for its client: {}", username, tooLarge);
      }
    }
    if (droppedPending) {
      logger.debug("Dropped proxy messages to {} beyond {}", username, MAX_PENDING_PACKETS);
      droppedPending = false;
    }
    for (PlayPacket packet = pendingPackets.poll();
        packet != null;
        packet = pendingPackets.poll()) {
      clientConnection.write(packet);
    }
    Component header = playerListHeader;
    Component footer = playerListFooter;
    if (!header.equals(Component.empty()) || !footer.equals(Component.empty())) {
      PlayPacket packet = headerAndFooterOrNull(header, footer);
      if (packet != null) {
        clientConnection.write(packet);
      }
    }
    bossBars.joinedGame();
    resourcePacks.joinedGame();
    clientConnection.flush();
  }

  /**
   * Sends Start Configuration (1.20.2+), which takes the client out of its game into the
   * configuration phase: a bundle it is in is closed first ("Terminal message received in bundle",
   * Velocity #1384), and from then on the proxy writes configuration packets to it, as the client
   * reads them from the moment it receives this one. Called on the client event loop.
   */
  void startConfiguration() {
    closeBundle();
    inGame = false;
    // The client clears its boss bars there, and 1.20.2 unloads its server resource pack.
    bossBars.leftGame();
    resourcePacks.clientReconfiguring();
    // Terminal: the proxy's own cookie requests and transfers wait for the acknowledgement; the
    // encoder moves at once, so that a disconnect sent meanwhile is one the client can read.
    clientConnection.writeTerminal(new StartConfiguration());
    clientConnection.encoder().setState(ProtocolState.CONFIGURATION);
  }

  /**
   * Renders the proxy's messages and the translations plugins registered, in the player's language
   * (see {@link PlayerMessages#render}).
   *
   * @param component the component
   * @return it rendered
   */
  Component render(Component component) {
    return loginContext.messages().render(component, locale(), protocolVersion);
  }

  /**
   * Writes PLAY packets to the client from its event loop: at once while it is in a game, kept for
   * its next Join Game otherwise, unless {@code ephemeral}.
   */
  private void sendPlay(List<PlayPacket> packets, boolean ephemeral) {
    if (packets.isEmpty()) {
      return;
    }
    onEventLoop(
        () -> {
          if (disconnected.get() || !clientConnection.channel().isActive()) {
            return;
          }
          if (inGame) {
            packets.forEach(clientConnection::write);
            clientConnection.flush();
          } else if (!ephemeral) {
            for (PlayPacket packet : packets) {
              if (pendingPackets.size() < MAX_PENDING_PACKETS) {
                pendingPackets.add(packet);
              } else {
                droppedPending = true;
              }
            }
          }
        });
  }

  private @Nullable PlayPacket headerAndFooterOrNull(Component header, Component footer) {
    try {
      return ComponentPackets.headerAndFooter(render(header), render(footer), protocolVersion);
    } catch (IllegalArgumentException tooLarge) {
      return null; // refused when they were set
    }
  }

  /** Sends the disconnect packet of the client's state, then disconnects. Client event loop. */
  private void disconnectWith(Component reason) {
    if (disconnected.get()) {
      return;
    }
    if (!clientConnection.channel().isActive()) {
      disconnect();
      return;
    }
    // What the proxy writes is what the client reads: the encoder moves to the configuration state
    // as Login Success (1.20.2+) and Start Configuration go out, the decoder only when the client
    // acknowledges them.
    ProtocolState state = clientConnection.encoder().state();
    Packet packet;
    try {
      packet = ComponentPackets.disconnect(reason, state, protocolVersion);
    } catch (IllegalArgumentException tooLarge) {
      logger.warn(
          LogCode.DISCONNECT_REASON_TOO_LARGE.marker(),
          "Disconnect reason for {} too large for its client: {}",
          username,
          tooLarge);
      packet =
          ComponentPackets.disconnect(render(Message.REASON_TOO_LONG.of()), state, protocolVersion);
    }
    if (packet == null) {
      disconnect();
      return;
    }
    if (state == ProtocolState.PLAY) {
      closeBundle();
    }
    closeAfter(packet);
  }

  // ---------------------------------------------------------------------------
  // Sounds, boss bars, resource packs, chat deletion and dialogs: the rest of what the plugin API's
  // Player delegates to. Thread-safe. What the client's version lacks does nothing (see
  // dev.warp.api.player.AudienceFeature); sounds are dropped while the client is not in a game.
  // ---------------------------------------------------------------------------

  /**
   * Plays a sound at a position, in every version: named by its key, so that any sound plays,
   * whatever the client's sound registry (see {@link SoundEffect}).
   *
   * @param sound the sound
   * @param x the x coordinate
   * @param y the y coordinate
   * @param z the z coordinate
   * @throws IllegalArgumentException if the sound's key is longer than 256 characters
   */
  public void playSound(Sound sound, double x, double y, double z) {
    SoundEffect packet =
        new SoundEffect(
            soundKey(sound.name()),
            AdventureIds.source(sound.source(), protocolVersion),
            SoundEffect.fixed(x),
            SoundEffect.fixed(y),
            SoundEffect.fixed(z),
            sound.volume(),
            sound.pitch(),
            seed(sound));
    sendPlay(List.of(packet), true);
  }

  /**
   * Plays a sound that follows the player, or another player on the same server, from 1.14: by its
   * key from 1.19.3, by its id in the client's registry before ({@link SoundEventIds}), which only
   * vanilla sounds of the client's version have. Nothing happens before 1.14, for a sound the
   * client's registry lacks, nor when {@code emitter} plays on another server or is switching.
   *
   * @param sound the sound
   * @param emitter the player the sound follows, or {@code null} for this player
   * @throws IllegalArgumentException if the sound's key is longer than 256 characters
   */
  public void playSound(Sound sound, @Nullable ConnectedPlayer emitter) {
    String key = soundKey(sound.name());
    if (protocolVersion.isOlderThan(ProtocolVersion.MINECRAFT_1_14)) {
      return;
    }
    boolean named = protocolVersion.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3);
    int soundId = named ? -1 : SoundEventIds.id(key, protocolVersion);
    if (!named && soundId < 0) {
      return; // not a sound of the client's version: no id names it
    }
    int source = AdventureIds.source(sound.source(), protocolVersion);
    long seed = seed(sound);
    onEventLoop(
        () -> {
          if (!inGame || disconnected.get()) {
            return;
          }
          int entity;
          if (emitter == null || emitter == this) {
            entity = entityId;
          } else {
            BackendServer server = currentServer;
            if (server == null || emitter.currentServer() != server || emitter.isSwitching()) {
              return; // its entity id means nothing on this player's server
            }
            entity = emitter.entityId();
          }
          float volume = sound.volume();
          float pitch = sound.pitch();
          clientConnection.writeAndFlush(
              named
                  ? EntitySoundEffect.named(key, source, entity, volume, pitch, seed)
                  : EntitySoundEffect.registered(soundId, source, entity, volume, pitch, seed));
        });
  }

  /**
   * Stops sounds: a packet from 1.13, the {@code MC|StopSound} plugin message from 1.9.4 to 1.12.2,
   * nothing before.
   *
   * @param stop the sounds to stop
   */
  public void stopSound(SoundStop stop) {
    Key sound = stop.sound();
    Sound.Source source = stop.source();
    StopSound packet =
        new StopSound(
            source == null ? -1 : AdventureIds.source(source, protocolVersion),
            sound == null ? null : soundKey(sound));
    if (protocolVersion.isAtLeast(ProtocolVersion.MINECRAFT_1_13)) {
      sendPlay(List.of(packet), true);
    } else if (protocolVersion.isAtLeast(ProtocolVersion.MINECRAFT_1_9_4)) {
      sendPlay(List.of(packet.legacyPluginMessage()), true);
    }
  }

  private static String soundKey(Key key) {
    String sound = key.asString();
    if (sound.length() > SoundEffect.MAX_SOUND_LENGTH) {
      throw new IllegalArgumentException("A sound key has at most 256 characters: " + sound);
    }
    return sound;
  }

  private static long seed(Sound sound) {
    return sound.seed().orElseGet(() -> ThreadLocalRandom.current().nextLong());
  }

  /**
   * Shows a boss bar, from 1.9, kept on screen across server switches and updated as it changes
   * (see {@link ProxyBossBars}).
   *
   * @param bar the bar
   */
  public void showBossBar(BossBar bar) {
    if (ProxyBossBars.supported(protocolVersion)) {
      // On the caller's thread, which is the one likely to ask the bar for its viewers next.
      WarpBossBarImplementation.attach(bar);
      onEventLoop(
          () -> {
            if (!disconnected.get()) {
              bossBars.show(bar);
            }
          });
    }
  }

  /**
   * Returns the boss bars shown to the player (see {@link ProxyBossBars#active()}).
   *
   * @return an unmodifiable snapshot, in the order they were shown
   */
  public List<BossBar> activeBossBars() {
    return bossBars.active();
  }

  /**
   * Hides a boss bar shown with {@link #showBossBar}.
   *
   * @param bar the bar
   */
  public void hideBossBar(BossBar bar) {
    if (ProxyBossBars.supported(protocolVersion)) {
      onEventLoop(() -> bossBars.hide(bar));
    }
  }

  /**
   * Applies resource packs on the client (see {@link ResourcePacks}).
   *
   * @param request the packs
   * @throws IllegalArgumentException if a hash is longer than 40 characters, or the prompt too
   *     large for the client
   */
  public void sendResourcePacks(ResourcePackRequest request) {
    resourcePacks.send(request);
  }

  /**
   * Removes resource packs from the client, from 1.20.3.
   *
   * @param ids the packs
   */
  public void removeResourcePacks(Collection<UUID> ids) {
    List<UUID> copy = List.copyOf(ids);
    onEventLoop(() -> resourcePacks.remove(copy));
  }

  /** Removes the resource packs the proxy sent, from 1.20.3. */
  public void clearResourcePacks() {
    onEventLoop(resourcePacks::clear);
  }

  /**
   * Deletes a signed chat message from the client's chat, from 1.19.1, by its signature. Kept for
   * the next game while the player switches servers, like a chat message.
   *
   * @param signature the message's signature
   * @throws IllegalArgumentException if the signature is not 256 bytes
   */
  public void deleteMessage(byte[] signature) {
    DeleteChat packet = new DeleteChat(signature.clone());
    if (protocolVersion.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
      sendPlay(List.of(packet), false);
    }
  }

  /** Closes the dialog on the client's screen, from 1.21.6, in the play or configuration phase. */
  public void closeDialog() {
    if (protocolVersion.isOlderThan(ProtocolVersion.MINECRAFT_1_21_6)) {
      return;
    }
    onEventLoop(
        () -> {
          ProtocolState state = clientConnection.encoder().state();
          if (!disconnected.get()
              && (state == ProtocolState.CONFIGURATION
                  || (state == ProtocolState.PLAY && inGame))) {
            clientConnection.writeAndFlush(new ClearDialog());
          }
        });
  }

  /**
   * Reports what became of a resource pack: fires the event on the player's lane, then runs the
   * pack's callback, off the event loop and in the order the answers came.
   *
   * @param event the event
   * @param callback the callback of the request that sent the pack, or {@code null}
   */
  void resourcePackStatus(
      PlayerResourcePackStatusEvent event, @Nullable ResourcePackCallback callback) {
    WarpEventBus events = loginContext.events();
    boolean listened = events.hasHandlers(PlayerResourcePackStatusEvent.class);
    if (callback == null && !listened) {
      return;
    }
    // Each answer waits for the previous one's callback: completions of separate deliveries race,
    // and a callback must see ACCEPTED before SUCCESSFULLY_LOADED (Velocity #1892).
    packStatuses =
        packStatuses
            .handle((_, _) -> event) // a failure never stops the answers that follow
            .thenCompose(done -> listened ? events.fire(done, uuid) : completedFuture(done))
            .thenAcceptAsync(
                done -> {
                  if (callback == null) {
                    return;
                  }
                  try {
                    callback.packEventReceived(done.packId(), done.status(), api);
                  } catch (RuntimeException e) {
                    logger.error(
                        LogCode.PLUGIN_CALLBACK_FAILED.marker(),
                        "A resource pack callback failed for {}",
                        username,
                        e);
                  }
                },
                events.executor());
  }

  /** The last answer about a resource pack reported, which the next one waits for. */
  private CompletableFuture<?> packStatuses = CompletableFuture.completedFuture(null);

  private static <T> CompletableFuture<T> completedFuture(T value) {
    return CompletableFuture.completedFuture(value);
  }

  /**
   * Returns the boss bars plugins show the player.
   *
   * @return the boss bars
   */
  ProxyBossBars bossBars() {
    return bossBars;
  }

  /**
   * Returns the resource packs the client holds.
   *
   * @return the resource packs
   */
  ResourcePacks resourcePacks() {
    return resourcePacks;
  }

  /**
   * Returns whether the client is in a game: it received a Join Game, and no Start Configuration
   * since. Client event loop only.
   *
   * @return {@code true} in a game
   */
  boolean isInGame() {
    return inGame;
  }

  /**
   * Returns whether the player disconnected.
   *
   * @return {@code true} once it is gone
   */
  boolean isGone() {
    return disconnected.get();
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
    if (duringConnect && backend != null) {
      loginContext
          .metrics()
          .serverConnect(
              lostName,
              switch (baseCause) {
                case KICKED -> ServerConnectOutcome.KICKED;
                case CONNECTION_LOST -> ServerConnectOutcome.RESET;
                default -> ServerConnectOutcome.OTHER;
              },
              -1);
    }
    Component reason = backend == null ? null : backend.channel().attr(KICK_REASON).get();
    if (failedFallbackServers == null) {
      // The first loss of a chain of fallbacks: its reason is the one the player is told, even
      // when the fallbacks fail without one of their own.
      failedFallbackServers = new HashSet<>();
      lossServer = lostName;
      lossReason = reason;
    }
    Set<String> excluded = failedFallbackServers;
    excluded.add(lostName);
    // Also exclude the server the player was on before any switch started.
    if (!stillOnCurrent && currentServerName != null) {
      excluded.add(currentServerName);
    }

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
              ? new KickedFromServerEvent.Redirect(
                  fallback,
                  lossServer != null
                      ? movedTo(lossServer, fallback, lossReason)
                      : movedTo(lostName, fallback, reason))
              : new KickedFromServerEvent.DisconnectPlayer(
                  reason != null
                      ? reason
                      : lossReason != null ? lossReason : Message.NO_SERVER_LEFT.of());
    }

    if (!stillOnCurrent) {
      // No server left to read the client at its pace: it is read for its keep-alive answers.
      readClientWhileWaiting();
    }
    WarpEventBus events = loginContext.events();
    if (lost == null || !events.hasHandlers(KickedFromServerEventImpl.class)) {
      applyLoss(lostName, lost, reason, stillOnCurrent, proposed, proposed);
      return;
    }
    holdFor(
        new KickedFromServerEventImpl(api, lost, cause, reason, duringConnect, proposed),
        done -> applyLoss(lostName, lost, reason, stillOnCurrent, done.result(), proposed));
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
          sendMessage(message);
        } else {
          disconnectFor(DisconnectEvent.Cause.DISCONNECTED_BY_PROXY, message);
        }
      }
      case KickedFromServerEvent.DisconnectPlayer(Component message) -> {
        failedFallbackServers = null;
        switching = false;
        disconnectFor(
            result.equals(proposed)
                ? DisconnectEvent.Cause.NO_SERVER_AVAILABLE
                : DisconnectEvent.Cause.DISCONNECTED_BY_PROXY,
            message);
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
          disconnectFor(DisconnectEvent.Cause.NO_SERVER_AVAILABLE, Message.NO_SERVER_LEFT.of());
          return;
        }
        // Told once the player is on the fallback, where it explains why it is there.
        arrivalNotice = message;
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
    holdFor(
        new ServerPreConnectEventImpl(api, target, currentServer),
        done -> {
          if (!clientConnection.channel().isActive()) {
            return;
          }
          if (done.result() instanceof ServerPreConnectEvent.Allowed(RegisteredServer server)) {
            BackendServer chosen = asBackend(server);
            connectingTo = chosen != null ? chosen : target;
            serverSwitch.fallBack(lostName, connectingTo.info());
          } else {
            disconnectFor(
                DisconnectEvent.Cause.DISCONNECTED_BY_PROXY,
                Message.FALLBACK_DENIED.of(target.name()));
          }
        });
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
    if (reason == null || reason.equals(Component.empty())) {
      return Message.CONNECT_FAILED.of(server);
    }
    return Message.CONNECT_FAILED_REASON.of(server, reason);
  }

  /**
   * What a player moved to a fallback is told: why it left its server, and where it is now. Nothing
   * when it never reached a server, and its first one was down: it simply lands on the fallback.
   */
  private @Nullable Component movedTo(
      String lostName, BackendServer fallback, @Nullable Component reason) {
    if (reason != null && !reason.equals(Component.empty())) {
      return Message.FALLBACK_KICKED.of(lostName, fallback.name(), reason);
    }
    return currentServer == null ? null : Message.FALLBACK_LOST.of(lostName, fallback.name());
  }

  // ---------------------------------------------------------------------------
  // KeepAlive system
  // ---------------------------------------------------------------------------

  /**
   * Starts the periodic KeepAlive task on the client event loop, for the rest of the session: a
   * keep-alive every {@value #KEEP_ALIVE_INTERVAL_MS} ms once the previous one was answered, the
   * client timed out when an answer is more than {@value #KEEP_ALIVE_TIMEOUT_MS} ms late. Called
   * once the client reads configuration or play packets; does nothing if the task runs already.
   *
   * <p>The task runs whoever the client waits for, a server or plugins: a vanilla client drops a
   * connection that sent it nothing for 30 s ({@code ReadTimeoutHandler(30)} on its channel, in
   * every state), and no server sends it anything while it waits between two.
   */
  void startKeepAliveTask() {
    if (keepAliveTask != null || disconnected.get()) {
      return;
    }
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

  /**
   * Moves the client connection to the state the client just acknowledged entering (configuration
   * or play), the packets held meanwhile going out in it ({@link MinecraftConnection#setState}).
   *
   * <p>A keep-alive the client was sent in the state it left, and has not answered yet, is no
   * longer waited for: a vanilla server starts afresh with each state's listener, and a client that
   * deferred its answer (while its window was frozen) drops it with the listener it leaves. Its
   * answer is still taken if it comes. A keep-alive held for the change goes out now, in the new
   * state, and is timed from now.
   *
   * @param state the state the client entered
   */
  void clientStateChanged(ProtocolState state) {
    if (keepAliveOutstanding) {
      if (keepAliveHeld) {
        keepAliveSentTime = clock();
      } else {
        staleKeepAliveId = pendingKeepAliveId;
        keepAliveStale = true;
        keepAliveOutstanding = false;
      }
    }
    keepAliveHeld = false;
    clientConnection.setState(state);
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

    long now = clock();
    if (keepAliveOutstanding) {
      long elapsed = now - keepAliveSentTime;
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
    keepAliveSentTime = now;
    keepAliveOutstanding = true;
    // Between Start Configuration and its acknowledgement the client reads configuration packets
    // (play packets after Finish Configuration): the keep-alive waits, and goes out with the id of
    // the state the client settles in. Otherwise it goes out with the id of the client's state.
    keepAliveHeld = clientConnection.isStateChangePending();
    clientConnection.writeWhenSettled(new KeepAlive(id));
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
   * @return {@code true} if the ID was valid: that of the pending keep-alive, or of one sent before
   *     the client last changed state
   */
  boolean handleKeepAliveResponse(long id) {
    if (keepAliveOutstanding && id == pendingKeepAliveId) {
      clientLatencyNanos = clock() - keepAliveSentTime;
      keepAliveOutstanding = false;
      return true;
    }
    if (keepAliveStale && id == staleKeepAliveId) {
      keepAliveStale = false;
      return true;
    }
    return false;
  }

  /**
   * Takes a keep-alive answer from the client, in any state: called by every handler the client
   * connection has once the player joined. A wrong one is logged and otherwise ignored.
   *
   * @param id the KeepAlive ID from the client
   */
  void keepAliveAnswered(long id) {
    if (!handleKeepAliveResponse(id)) {
      logger.warn(
          LogCode.KEEPALIVE_INVALID.marker(), "Invalid KeepAlive ID from player {}", username);
    }
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
  // Transfers and cookies (1.20.5+)
  // ---------------------------------------------------------------------------

  /**
   * Returns whether the player arrived by a transfer: a server sent its client here with a Transfer
   * packet, and the client's handshake declared it (intent 3, 1.20.5+). The cookies that server
   * stored came along, and {@link #requestCookie} reads them.
   *
   * @return {@code true} if the player was transferred here
   */
  public boolean isTransferred() {
    return transferred;
  }

  /**
   * Sends the player to another server with a Transfer packet: the client disconnects from the
   * proxy and connects to {@code host:port} with a transfer handshake, taking the cookies stored on
   * it along. The target must accept transfers. Nothing is resolved here: the client looks the host
   * up itself.
   *
   * <p>Written once the client reads the proxy's state: in configuration or in play, after any
   * state change in progress. May be called from any thread; off the client event loop, a client
   * that can no longer receive it is logged rather than thrown at.
   *
   * @param host the host name or address the client connects to
   * @param port the port, 1 to 65535
   * @throws UnsupportedOperationException if the client predates 1.20.5, which has no transfers
   * @throws IllegalArgumentException if {@code host} is blank or longer than a protocol string, or
   *     {@code port} is out of range
   * @throws IllegalStateException on the client event loop, if the client is neither in
   *     configuration nor in play
   */
  public void transfer(String host, int port) {
    if (protocolVersion.isOlderThan(ProtocolVersion.MINECRAFT_1_20_5)) {
      throw new UnsupportedOperationException(
          "Cannot transfer "
              + username
              + ": transfers need Minecraft 1.20.5 or newer, the client plays "
              + protocolVersion.name());
    }
    if (host.isBlank() || host.length() > McString.DEFAULT_MAX_CHARS) {
      throw new IllegalArgumentException("Transfer host must be a non-blank protocol string");
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("Transfer port must be 1 to 65535, got " + port);
    }
    Transfer packet = new Transfer(host, port);
    EventLoop loop = clientConnection.channel().eventLoop();
    if (loop.inEventLoop()) {
      sendTransfer(packet);
      return;
    }
    loop.execute(
        () -> {
          try {
            sendTransfer(packet);
          } catch (IllegalStateException e) {
            logger.warn(
                LogCode.TRANSFER_FAILED.marker(),
                "Could not transfer {}: {}",
                username,
                e.getMessage());
          }
        });
  }

  private void sendTransfer(Transfer packet) {
    if (!clientConnection.channel().isActive()) {
      return;
    }
    ProtocolState state = clientConnection.encoder().state();
    if (state != ProtocolState.CONFIGURATION && state != ProtocolState.PLAY) {
      throw new IllegalStateException(
          "Transfers are sent in the configuration and play states, not in " + state);
    }
    writeTransfer(packet, clientConnection.channel().voidPromise());
  }

  /**
   * Returns whether the client can be transferred: Minecraft 1.20.5 and newer.
   *
   * @return {@code true} if the client supports transfers
   */
  public boolean supportsTransfers() {
    return protocolVersion.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);
  }

  /**
   * Sends the player to another server with a Transfer packet, as {@link #transfer} does, but tells
   * how it went rather than throwing: what {@link dev.warp.api.player.Player#transfer} returns.
   *
   * <p>A client still logging in can be transferred once it settles in the configuration state: a
   * transfer asked for between Login Success and the client's acknowledgement waits for it. Before
   * Login Success, it is {@link TransferResult#NOT_READY}.
   *
   * @param host the host name or address the client connects to
   * @param port the port, 1 to 65535
   * @return completed with the outcome, on the client event loop; never exceptionally
   * @throws IllegalArgumentException if {@code host} is blank or longer than a protocol string, or
   *     {@code port} is out of range
   */
  public CompletableFuture<TransferResult> requestTransfer(String host, int port) {
    if (host.isBlank() || host.length() > McString.DEFAULT_MAX_CHARS) {
      throw new IllegalArgumentException("Transfer host must be a non-blank protocol string");
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("Transfer port must be 1 to 65535, got " + port);
    }
    if (!supportsTransfers()) {
      return CompletableFuture.completedFuture(TransferResult.UNSUPPORTED);
    }
    CompletableFuture<TransferResult> result = new CompletableFuture<>();
    Transfer packet = new Transfer(host, port);
    try {
      onEventLoop(() -> trySendTransfer(packet, result));
    } catch (RejectedExecutionException e) {
      // The event loop is shutting down with the proxy.
      result.complete(TransferResult.DISCONNECTED);
    }
    return result;
  }

  private void trySendTransfer(Transfer packet, CompletableFuture<TransferResult> result) {
    Channel channel = clientConnection.channel();
    if (!channel.isActive() || disconnected.get()) {
      result.complete(TransferResult.DISCONNECTED);
      return;
    }
    ProtocolState state = clientConnection.encoder().state();
    boolean settling = clientConnection.isStateChangePending();
    boolean readable =
        state == ProtocolState.CONFIGURATION
            || state == ProtocolState.PLAY
            // Login Success written: the client reads configuration packets already.
            || (state == ProtocolState.LOGIN && settling);
    if (!readable) {
      result.complete(TransferResult.NOT_READY);
      return;
    }
    ChannelPromise promise = channel.newPromise();
    var _ =
        promise.addListener(
            future ->
                result.complete(
                    future.isSuccess() ? TransferResult.SENT : TransferResult.DISCONNECTED));
    writeTransfer(packet, promise);
  }

  /** Writes a Transfer packet once the client reads the proxy's state. On the client event loop. */
  private void writeTransfer(Transfer packet, ChannelPromise promise) {
    if (clientConnection.encoder().state() == ProtocolState.PLAY
        && !clientConnection.isStateChangePending()) {
      // Inside an open bundle, the client would only act on it once the bundle closes.
      closeBundle();
    }
    logger.info("Transferring {} to {}:{}", username, packet.host(), packet.port());
    clientConnection.writeWhenSettled(packet, promise);
  }

  /**
   * Stores a cookie on the player's client for the proxy: the client keeps it under {@code key},
   * replacing any cookie stored there, and carries it over when a Transfer sends it elsewhere.
   * Written once the client reads the proxy's state.
   *
   * <p>The client hands its cookies to any server it is transferred to, and a client can forge
   * them: sign a cookie whose content must be trusted.
   *
   * @param key the cookie's identifier, such as {@code primemc:session}
   * @param payload the cookie, at most 5 KiB; copied
   * @throws UnsupportedOperationException if the client predates 1.20.5
   * @throws IllegalArgumentException if {@code key} is not a valid identifier, or the payload is
   *     larger than 5 KiB
   * @throws IllegalStateException on the client event loop, if the client is neither in
   *     configuration nor in play
   */
  public void storeCookie(String key, byte[] payload) {
    cookies.store(key, payload);
  }

  /**
   * Asks the player's client for the cookie it holds under {@code key}, waiting at most {@value
   * #COOKIE_TIMEOUT_SECONDS} seconds for the answer.
   *
   * @param key the cookie's identifier, such as {@code primemc:session}
   * @return the cookie, empty if the client holds none; see {@link #requestCookie(String,
   *     Duration)} for how it fails
   * @throws UnsupportedOperationException if the client predates 1.20.5
   * @throws IllegalArgumentException if {@code key} is not a valid identifier
   */
  public CompletableFuture<Optional<byte[]>> requestCookie(String key) {
    return requestCookie(key, ClientCookies.DEFAULT_TIMEOUT);
  }

  /**
   * Asks the player's client for the cookie it holds under {@code key}. The answer goes to the
   * proxy alone, even while backends ask for cookies of their own, the same key included: each
   * answer goes to whoever asked, in order.
   *
   * <p>The future completes on the client event loop: with the cookie, empty if the client holds
   * none; with a {@link java.util.concurrent.TimeoutException} if the client does not answer within
   * {@code timeout}; with an {@link IllegalStateException} if the player disconnects first. The
   * client answers with whatever it holds, which it can forge.
   *
   * @param key the cookie's identifier, such as {@code primemc:session}
   * @param timeout how long to wait for the answer
   * @return the future cookie
   * @throws UnsupportedOperationException if the client predates 1.20.5
   * @throws IllegalArgumentException if {@code key} is not a valid identifier, or {@code timeout}
   *     is not positive
   */
  public CompletableFuture<Optional<byte[]>> requestCookie(String key, Duration timeout) {
    return cookies.request(key, timeout);
  }

  /**
   * Returns the client's cookie traffic, which the session handlers feed with the backends'
   * requests and the client's answers.
   *
   * @return the cookie traffic of the client connection
   */
  ClientCookies cookies() {
    return cookies;
  }

  // ---------------------------------------------------------------------------
  // Disconnect
  // ---------------------------------------------------------------------------

  /**
   * Disconnects the player from both client and backend, and removes it from the proxy's players at
   * once, so that the same account can log in again right away. Idempotent and thread-safe.
   */
  public void disconnect() {
    closeAfter(null);
  }

  /**
   * Disconnects the player with a reason, in whatever state its client is: a login disconnect, JSON
   * in every version, until the client has received its Login Success; a configuration disconnect
   * from then on in 1.20.2+, including the moment the proxy still reads the client in the login
   * state, and during a switch's configuration phase; a play disconnect otherwise, after closing an
   * open bundle (a disconnect inside one is "Terminal message received in bundle"). A reason too
   * large for the client is replaced by a short one: the player is disconnected regardless.
   *
   * @param reason the reason shown to the player
   */
  public void disconnect(Component reason) {
    Component rendered = render(reason);
    onEventLoop(() -> disconnectWith(rendered));
  }

  /**
   * Disconnects the player from both client and backend, the client after {@code last} when there
   * is one: its connection closes once the packet is flushed ({@link
   * MinecraftConnection#writeAndClose}), within {@value #DISCONNECT_FLUSH_TIMEOUT_MS} ms at most.
   * Idempotent and thread-safe.
   */
  private void closeAfter(@Nullable Packet last) {
    if (!disconnected.compareAndSet(false, true)) {
      return;
    }
    uncountVersion();
    loginContext.playerRegistry().unregister(this);
    if (sessionStarted) {
      logConnection(
          "Player {} ({}) left: {}",
          username,
          uuid,
          disconnectCause.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }
    endSession();
    cancelKeepAliveTask();
    cancelSwitchTimeout();
    if (last == null) {
      clientConnection.close();
    } else {
      clientConnection.writeAndClose(last);
      var _ =
          clientConnection
              .channel()
              .eventLoop()
              .schedule(
                  clientConnection::close, DISCONNECT_FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
    BackendConnection backend = this.backendConnection;
    if (backend != null) {
      backend.disconnect();
    }
    onEventLoop(
        () -> {
          serverSwitch.close();
          bossBars.clear();
          resourcePacks.close();
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
    disconnectFor(cause, Component.text(reason));
  }

  /** Disconnects the player with a reason, recording why the session ends. Thread-safe. */
  void disconnectFor(DisconnectEvent.Cause cause, Component reason) {
    setDisconnectCause(cause);
    disconnect(reason);
  }

  /**
   * Disconnects the player at a plugin's request. Thread-safe.
   *
   * @param reason shown to the player
   */
  void disconnectByPlugin(Component reason) {
    disconnectFor(DisconnectEvent.Cause.DISCONNECTED_BY_PROXY, reason);
  }

  /**
   * Disconnects the player because the same account logged in again and the duplicate login policy
   * keeps the newcomer. The player leaves the registry before this returns; the client is told why
   * on its own event loop. Thread-safe.
   */
  public void disconnectDuplicate() {
    setDisconnectCause(DisconnectEvent.Cause.CONFLICTING_LOGIN);
    uncountVersion();
    loginContext.playerRegistry().unregister(this);
    endSession();
    logger.info("Disconnecting {}: the same account logged in again", username);
    onEventLoop(() -> disconnect(Message.DUPLICATE_LOGIN.of()));
  }

  /** Disconnects the player because the proxy is shutting down. Thread-safe. */
  public void disconnectShutdown() {
    disconnectShutdown(Message.SERVER_SHUTDOWN.of());
  }

  /**
   * Disconnects the player because the proxy is shutting down, telling it why. Thread-safe.
   *
   * @param reason shown to the player
   */
  public void disconnectShutdown(String reason) {
    disconnectShutdown(Component.text(reason));
  }

  /**
   * Disconnects the player because the proxy is shutting down, telling it why. Thread-safe.
   *
   * @param reason shown to the player
   */
  public void disconnectShutdown(Component reason) {
    setDisconnectCause(DisconnectEvent.Cause.PROXY_SHUTDOWN);
    disconnect(reason);
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
   * Starts a log record about this player: its name and session go in the record's context, which
   * JSON logs and plugin log sinks carry as {@value #LOG_PLAYER} and {@value #LOG_SESSION}.
   *
   * @param logger the logger to write with
   * @param level the record's level
   * @return the record, to finish with {@link LoggingEventBuilder#log(String, Object...)}
   */
  public LoggingEventBuilder log(Logger logger, Level level) {
    return logger
        .atLevel(level)
        .addKeyValue(LOG_PLAYER, username)
        .addKeyValue(LOG_SESSION, sessionId.toString());
  }

  /**
   * Logs a player connection event at INFO, when {@code logging.connections} is on and no attack is
   * under way (counted in the attack's summaries then).
   */
  private void logConnection(String format, @Nullable Object... arguments) {
    if (loginContext.logging().connections()
        && loginContext.antiBot().logs(RoutineLine.CONNECTION)) {
      log(logger, Level.INFO).log(format, arguments);
    }
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
   * Returns the BungeeCord channel to register with a backend on the client's behalf, as BungeeCord
   * and Velocity do: Bukkit's {@code Player#sendPluginMessage} only sends on a channel the client
   * registered, which a vanilla client never does for this one. There is one to register when the
   * proxy answers the channel for that backend's server, or a plugin took the channel over.
   *
   * @param backend a backend connection of this player
   * @return {@code bungeecord:main} from 1.13, {@code BungeeCord} before; or {@code null} if nobody
   *     answers the channel for that server, or the connection is not the player's backend
   */
  @Nullable String bungeeCordChannelToRegister(MinecraftConnection backend) {
    BackendConnection current = backendConnection;
    if (current == null || current.connection() != backend) {
      return null;
    }
    ChannelRegistry channels = loginContext.channels();
    String channel = BungeeCordChannel.nameFor(protocolVersion);
    BungeeCordChannel bungeeCord = channels.bungeeCord();
    boolean answered = bungeeCord.enabled() && bungeeCord.speaksWith(current.server().name());
    return answered || channels.lookup(channel) != null ? channel : null;
  }

  /**
   * Hands a plugin message on a channel a plugin owns to {@link PluginMessageEvent}, and a
   * backend's message on the BungeeCord channel to the proxy's answer (through the event first,
   * when a plugin listens). A client's message on the BungeeCord channel is dropped, unless a
   * plugin registered the channel for players: only backends speak it, and a client must not reach
   * a backend's plugins under the proxy's name. Runs on the client event loop.
   *
   * @param message the message
   * @param fromBackend the backend that sent it, or {@code null} if the client did
   * @return {@code true} if the proxy took the message: the caller must not relay it
   */
  boolean interceptPluginMessage(
      PlayPluginMessage message, @Nullable MinecraftConnection fromBackend) {
    ChannelRegistry channels = loginContext.channels();
    String name = message.channel();
    ChannelRegistry.Registration registration = channels.lookup(name);
    boolean bungeeCordChannel = BungeeCordChannel.isChannel(name);
    BungeeCordChannel bungeeCord = channels.bungeeCord();
    if (registration == null) {
      if (!bungeeCordChannel) {
        return false;
      }
      if (fromBackend == null) {
        logger.debug("Dropped a message of {} on {}: only backends speak it", username, name);
        return true;
      }
      if (!bungeeCord.enabled()) {
        return false;
      }
    }
    Messaging.Origin origin =
        fromBackend == null ? Messaging.Origin.PLAYER : Messaging.Origin.SERVER;
    if (registration != null && !registration.accepts(origin)) {
      logger.debug(
          "Dropped a message on {} from the {} side of {}: plugin {} does not accept it",
          name,
          origin,
          username,
          registration.owner());
      return true;
    }
    BackendServer server = null;
    PluginMessageEvent.Source source;
    if (fromBackend == null) {
      source = new PluginMessageEvent.FromPlayer(api);
    } else {
      server = serverOf(fromBackend);
      if (server == null) {
        return true; // a backend the player already left
      }
      source = new PluginMessageEvent.FromServer(new WarpServerConnection(this, server));
    }
    WarpEventBus events = loginContext.events();
    if (registration == null
        && server != null
        && fromBackend != null
        && !events.hasHandlers(PluginMessageEventImpl.class)) {
      // The BungeeCord channel, which no plugin watches: the proxy answers at once.
      bungeeCord.handle(this, server, fromBackend, message.data());
      return true;
    }
    ChannelIdentifier channel =
        registration != null ? registration.channel() : BungeeCordChannel.identifier(name);
    PluginMessageEvent.Result initial =
        registration != null ? new PluginMessageEvent.Handled() : new PluginMessageEvent.Pass();
    var event = new PluginMessageEventImpl(channel, source, api, message.data(), initial);
    BackendServer sender = server;
    var _ =
        events
            .fire(event, uuid)
            .whenComplete(
                (done, _) ->
                    onEventLoop(() -> afterPluginMessageEvent(done, message, fromBackend, sender)));
    return true;
  }

  /** Does what {@link PluginMessageEvent}'s handlers decided. Client event loop. */
  private void afterPluginMessageEvent(
      PluginMessageEventImpl done,
      PlayPluginMessage message,
      @Nullable MinecraftConnection fromBackend,
      @Nullable BackendServer sender) {
    switch (done.result()) {
      case PluginMessageEvent.Handled() -> {}
      case PluginMessageEvent.Forward() -> relay(message, fromBackend);
      case PluginMessageEvent.Pass() -> {
        if (!BungeeCordChannel.isChannel(message.channel())) {
          relay(message, fromBackend);
        } else if (fromBackend != null && sender != null) {
          BungeeCordChannel bungeeCord = loginContext.channels().bungeeCord();
          if (bungeeCord.enabled()) {
            bungeeCord.handle(this, sender, fromBackend, message.data());
          } else {
            relay(message, fromBackend);
          }
        }
        // A client's message on the BungeeCord channel is dropped, as when no plugin sees it.
      }
    }
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

  /**
   * Returns the registered server a backend connection of this player belongs to.
   *
   * @param backend a backend connection
   * @return its server, or {@code null} if it is not the player's backend or not registered
   */
  @Nullable BackendServer serverOf(MinecraftConnection backend) {
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

  /**
   * Sends a plugin message over one of this player's backend connections, if it is still open and
   * in PLAY, such as the answer to a message that came over it. Thread-safe.
   *
   * @param backend the backend connection
   * @param channel the channel's wire name
   * @param data the payload, not copied: the caller hands it over
   * @return {@code false} if the connection is closed
   */
  public boolean sendPlayPluginMessage(MinecraftConnection backend, String channel, byte[] data) {
    if (!backend.channel().isActive()) {
      return false;
    }
    onEventLoop(
        () -> {
          if (backend.channel().isActive() && backend.encoder().state() == ProtocolState.PLAY) {
            backend.writeAndFlush(new PlayPluginMessage(channel, data));
          } else {
            logger.debug("Dropped a plugin message to {}'s server: it left PLAY", username);
          }
        });
    return true;
  }

  /**
   * Sends a plugin message to a server through this player's connection, if the player plays there
   * and its connection is in PLAY. Thread-safe.
   *
   * @param server the server
   * @param channel the channel's wire name
   * @param data the payload, not copied: the caller hands it over
   * @return {@code false} if the player does not play on that server right now
   */
  public boolean sendPlayPluginMessageToServer(BackendServer server, String channel, byte[] data) {
    BackendConnection backend = backendConnection;
    if (currentServer != server
        || backend == null
        || !backend.isActive()
        || backend.connection().encoder().state() != ProtocolState.PLAY) {
      return false;
    }
    return sendPlayPluginMessage(backend.connection(), channel, data);
  }

  // ---------------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------------

  /**
   * Handles a command the client typed: the proxy runs its own, {@link CommandExecuteEvent} may
   * block any, and the rest goes to the backend. Runs on the client event loop.
   *
   * @param commandLine the command line, without {@code /}
   * @param acknowledgements the chat messages the command acknowledges (1.19.3 to 1.20.4), which
   *     the backend must hear about when it does not get the command itself
   * @param forward sends the command to the backend as typed
   */
  void handleCommand(String commandLine, int acknowledgements, Runnable forward) {
    if (loginContext.logging().commands()) {
      log(logger, Level.INFO).log("{} ran the command /{}", username, commandLine);
    }
    WarpCommandManager commands = loginContext.commands();
    boolean proxyCommand = commands.owns(api, commandLine);
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(CommandExecuteEventImpl.class)) {
      if (proxyCommand) {
        consumeCommand(acknowledgements);
        var _ = commands.execute(api, commandLine);
      } else {
        forward.run();
      }
      return;
    }
    // Hold what the client sends next, so that nothing overtakes the command: signed chat requires
    // the backend to see the player's messages and commands in order.
    clientConnection.setAutoRead(false);
    var _ =
        events
            .fire(new CommandExecuteEventImpl(api, commandLine), uuid)
            .whenComplete(
                (event, _) ->
                    onEventLoop(
                        () -> {
                          clientConnection.setAutoRead(true);
                          if (!clientConnection.channel().isActive()) {
                            return;
                          }
                          if (event.result() instanceof CommandExecuteEvent.Denied) {
                            consumeCommand(acknowledgements);
                          } else if (proxyCommand) {
                            consumeCommand(acknowledgements);
                            var _ = commands.execute(api, commandLine);
                          } else {
                            forward.run();
                          }
                        }));
  }

  /**
   * Tells the backend about the chat messages a command it never sees acknowledges. From 1.19.3 to
   * 1.20.4 the client counts them as delivered: the backend must move its last-seen window by as
   * many, or the client's next message no longer lines up and the player is kicked (Velocity does
   * the same, SessionCommandHandler#consumeCommand).
   */
  private void consumeCommand(int acknowledgements) {
    BackendConnection backend = backendConnection;
    if (acknowledgements > 0 && backend != null && backend.isActive()) {
      backend.connection().writeAndFlush(new ChatAcknowledgement(acknowledgements));
    }
  }

  /**
   * Answers a suggestion request for a proxy command; forwards any other. Runs on the client event
   * loop.
   *
   * @param request the request
   * @param forward sends it to the backend
   */
  void handleSuggestions(TabCompleteRequest request, Runnable forward) {
    String text = request.text();
    if (protocolVersion.isOlderThan(ProtocolVersion.MINECRAFT_1_13)
        || !text.startsWith("/")
        || text.indexOf(' ') < 0
        || !loginContext.commands().owns(api, text.substring(1))) {
      forward.run();
      return;
    }
    var _ =
        loginContext
            .commands()
            .suggest(api, text.substring(1))
            .thenAccept(
                suggestions ->
                    onEventLoop(
                        () -> {
                          if (clientConnection.channel().isActive()) {
                            clientConnection.writeAndFlush(
                                new TabCompleteResponse(
                                    request.transactionId(), encodeSuggestions(suggestions, 1)));
                          }
                        }));
  }

  /**
   * Writes Brigadier suggestions in the 1.13+ layout after the transaction id: start, length,
   * count, then each match without tooltip.
   */
  private static byte[] encodeSuggestions(Suggestions suggestions, int offset) {
    ByteBuf buf = Unpooled.buffer();
    try {
      VarInt.write(buf, suggestions.getRange().getStart() + offset);
      VarInt.write(buf, suggestions.getRange().getLength());
      VarInt.write(buf, suggestions.getList().size());
      for (Suggestion suggestion : suggestions.getList()) {
        McString.write(buf, suggestion.getText());
        buf.writeBoolean(false);
      }
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Command list
  // ---------------------------------------------------------------------------

  /**
   * How long a refresh of the command list waits for more changes: a permission plugin applying an
   * edit changes many nodes, a plugin being enabled registers many commands.
   */
  static final Duration COMMAND_REFRESH_DELAY = Duration.ofMillis(100);

  /**
   * A server's last command list, kept to merge the proxy's commands into it again when they or the
   * player's permissions change. Kept as the packet's raw bytes (some 20 KiB for a vanilla
   * operator), not as a decoded tree (hundreds of KiB): it is decoded again only for a refresh.
   * Compared by identity: each packet the server sends is a list of its own, even if it is the
   * same.
   */
  private static final class ServerCommands {
    private final MinecraftConnection backend;
    private final DeclareCommands packet;

    private ServerCommands(MinecraftConnection backend, DeclareCommands packet) {
      this.backend = backend;
      this.packet = packet;
    }
  }

  /**
   * The current server's last command list, or {@code null} before it sent one, or when the proxy
   * could not read it.
   */
  private volatile @Nullable ServerCommands serverCommands;

  /** Whether a refresh of the command list is scheduled and has not started yet. */
  private final AtomicBoolean commandRefreshScheduled = new AtomicBoolean();

  /**
   * Sends the client its server's command list with the proxy's commands merged in, as {@link
   * PlayerAvailableCommandsEvent} leaves it. A list the proxy cannot read goes as it came. Runs on
   * the client event loop.
   *
   * @param packet the server's commands packet
   * @param backend the server's connection
   */
  void sendCommands(DeclareCommands packet, MinecraftConnection backend) {
    if (!isPlayingOn(backend)) {
      return;
    }
    CommandTreeDecoding decoding;
    try {
      decoding = packet.tree(protocolVersion);
    } catch (RuntimeException e) {
      // A tree the client would refuse too: let it judge, as it would without a proxy.
      serverCommands = null;
      clientConnection.writeAndFlush(packet);
      return;
    }
    switch (decoding) {
      case CommandTreeDecoding.Opaque opaque -> {
        // Nothing to refresh: the proxy's commands are not in it.
        serverCommands = null;
        if (!opaqueCommandsLogged) {
          opaqueCommandsLogged = true;
          logger.info("Proxy commands not shown to {}: {}", username, opaque.message());
        }
        clientConnection.writeAndFlush(packet);
      }
      case CommandTreeDecoding.Decoded(CommandTree tree) -> {
        ServerCommands origin = new ServerCommands(backend, packet);
        serverCommands = origin;
        sendMerged(tree, origin);
      }
    }
  }

  /**
   * Sends the player its command list again: the current server's last list, with the proxy's
   * commands it may use now. Debounced by {@link #COMMAND_REFRESH_DELAY}; the permission checks run
   * on the command executor, the write on the client event loop. Safe from any thread.
   *
   * <p>Nothing happens for a client older than 1.13 (no command list), before the server sent a
   * list, while the client is between two servers or in the configuration phase (the next server
   * sends its own), or when the server's list is opaque to the proxy (it shows no proxy commands).
   */
  public void refreshCommands() {
    if (protocolVersion.isOlderThan(ProtocolVersion.MINECRAFT_1_13)
        || !clientConnection.channel().isActive()
        || !commandRefreshScheduled.compareAndSet(false, true)) {
      return;
    }
    var _ =
        clientConnection
            .channel()
            .eventLoop()
            .schedule(
                this::startCommandRefresh, COMMAND_REFRESH_DELAY.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Starts a scheduled refresh: on the client event loop, once the delay is over. */
  private void startCommandRefresh() {
    // Cleared first: a change from here on may come after the checks read the permissions.
    commandRefreshScheduled.set(false);
    ServerCommands origin = serverCommands;
    if (origin == null || !canDeclareCommands(origin)) {
      return;
    }
    try {
      loginContext.commands().executor().execute(() -> rebuildCommands(origin));
    } catch (RejectedExecutionException e) {
      // The proxy is shutting down.
      logger.trace("Command list of {} not refreshed: shutting down", username);
    }
  }

  /** Merges the proxy's commands into the server's last list again. Off the event loop. */
  private void rebuildCommands(ServerCommands origin) {
    try {
      if (origin.packet.tree(protocolVersion)
          instanceof CommandTreeDecoding.Decoded(CommandTree tree)) {
        sendMerged(tree, origin);
      }
    } catch (RuntimeException e) {
      // A plugin's requirement that throws, typically: the client keeps the list it has.
      logger.warn(
          LogCode.COMMAND_LIST_REFRESH_FAILED.marker(),
          "Could not refresh the command list of {}",
          username,
          e);
    }
  }

  /**
   * Merges the proxy's commands the player may use into a server's tree, fires {@link
   * PlayerAvailableCommandsEvent} if anyone listens, and sends the result, unless a newer list or
   * another state replaced {@code origin} meanwhile.
   */
  private void sendMerged(CommandTree tree, ServerCommands origin) {
    CommandTree merged =
        tree.merge(loginContext.commands().treeFor(api, protocolVersion), NameConflict.PROXY_WINS);
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(PlayerAvailableCommandsEventImpl.class)) {
      declareCommands(DeclareCommands.of(merged, protocolVersion), origin);
      return;
    }
    var event = new PlayerAvailableCommandsEventImpl(api, merged.topLevelNames());
    var _ =
        events
            .fire(event, uuid)
            .whenComplete(
                (done, _) -> {
                  CommandTree shown =
                      merged.withoutTopLevel(node -> !done.keeps(topLevelName(node)));
                  declareCommands(DeclareCommands.of(shown, protocolVersion), origin);
                });
  }

  /** Writes a command list on the client event loop, if {@code origin} is still current. */
  private void declareCommands(DeclareCommands packet, ServerCommands origin) {
    onEventLoop(
        () -> {
          if (canDeclareCommands(origin)) {
            clientConnection.writeAndFlush(packet);
          }
        });
  }

  /**
   * Whether a list merged into {@code origin} may go to the client: it is the current server's last
   * list, and the client plays on that server, outside a state change.
   */
  private boolean canDeclareCommands(ServerCommands origin) {
    return serverCommands == origin
        && isPlayingOn(origin.backend)
        && clientConnection.channel().isActive()
        && clientConnection.encoder().state() == ProtocolState.PLAY
        && !clientConnection.isStateChangePending();
  }

  private static String topLevelName(dev.warp.protocol.command.CommandNode node) {
    return switch (node) {
      case dev.warp.protocol.command.CommandNode.Literal literal -> literal.name();
      case dev.warp.protocol.command.CommandNode.Argument argument -> argument.name();
      case dev.warp.protocol.command.CommandNode.Root _ -> "";
    };
  }

  /** Whether the proxy already logged that it cannot read this player's command lists. */
  private boolean opaqueCommandsLogged;

  /** Fires a notification event on the player's lane, if anyone listens. */
  private void notify(Object event) {
    WarpEventBus events = loginContext.events();
    if (events.hasHandlers(event.getClass())) {
      var _ = events.fire(event, uuid);
    }
  }
}
