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

import dev.warp.api.antibot.LoginAttempt;
import dev.warp.api.antibot.Verdict;
import dev.warp.api.event.player.LoginEvent;
import dev.warp.api.event.player.PreLoginEvent;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.CipherDecoder;
import dev.warp.protocol.netty.CipherEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.EncryptionResponse;
import dev.warp.protocol.packet.login.EncryptionResponse.EncryptedToken;
import dev.warp.protocol.packet.login.EncryptionResponse.SignedToken;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginPacket;
import dev.warp.protocol.packet.login.LoginPluginRequest;
import dev.warp.protocol.packet.login.LoginPluginResponse;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.login.SetCompression;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.antibot.AntiBotService.RoutineLine;
import dev.warp.proxy.auth.AuthenticationException;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.auth.Pkcs1Decryption;
import dev.warp.proxy.auth.ProfileKeys;
import dev.warp.proxy.auth.ServerHash;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.player.GameProfileRequestEventImpl;
import dev.warp.proxy.event.player.LoginEventImpl;
import dev.warp.proxy.event.player.PreLoginEventImpl;
import dev.warp.proxy.limit.AuthenticationLane;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.LoginAdmission;
import dev.warp.proxy.limit.LoginSlot;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.player.PlayerRegistry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Handles the client login sequence: encryption, Mojang authentication, and compression.
 *
 * <p>Supports both online mode (full Mojang authentication with RSA encryption) and offline mode
 * (UUID generated from username). After successful login, the handler transitions to either
 * CONFIGURATION (1.20.2+) or PLAY (older versions).
 *
 * <h3>State machine</h3>
 *
 * <p>The login progresses through a strict state machine. Any out-of-order packet immediately
 * disconnects the client.
 *
 * <pre>{@code
 * AWAITING_LOGIN_START
 *   → (online mode)  AWAITING_ENCRYPTION_RESPONSE → AUTHENTICATING → AWAITING_LOGIN_ACKNOWLEDGED → COMPLETE
 *   → (offline mode) AWAITING_LOGIN_ACKNOWLEDGED → COMPLETE
 * }</pre>
 *
 * <h3>Limits</h3>
 *
 * <p>The login counts against its address's login limits as soon as this handler is installed,
 * before anything else is read: an address over its rate, or with too many logins in progress, is
 * told so and closed. An admitted login holds its place among its address's logins in progress
 * until the player is logged in or the connection closes, and is closed if that takes longer than
 * the login timeout. In online mode, the RSA decryption and the session server request count
 * against the proxy's authentications in flight.
 *
 * <h3>Threading model</h3>
 *
 * <p>All state mutations and pipeline modifications happen on the Netty event loop thread. Mojang
 * authentication runs on a virtual thread and posts its result back to the event loop via {@link
 * io.netty.channel.EventLoop#execute(Runnable)}.
 */
final class LoginSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(LoginSessionHandler.class);

  /** Valid Minecraft username pattern: 3-16 alphanumeric characters or underscores. */
  private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

  /** AES shared secret length in bytes (128-bit). */
  private static final int SHARED_SECRET_LENGTH = 16;

  /** Verify token length in bytes. */
  private static final int VERIFY_TOKEN_LENGTH = 4;

  /**
   * The one reason given for an encryption response that cannot be used, whatever the cause: see
   * {@link #sharedSecret}.
   */
  private static final String ENCRYPTION_FAILED = "Encryption failed";

  /**
   * Vanilla's reason for refusing a client that answers a query it was not sent, such as a cookie
   * the proxy never requested, translated by the client.
   */
  static final String UNEXPECTED_QUERY_RESPONSE =
      "multiplayer.disconnect.unexpected_query_response";

  /** Why a player beyond the player limit ({@code status.max-players}) is refused. */
  static final String SERVER_FULL = "The server is full.";

  /** Told to a player over the limit of logins in progress from its address. */
  static final String TOO_MANY_LOGINS_REASON =
      "Too many logins in progress from your address. Please try again in a moment.";

  /** Told to a player who starts an online login while the proxy authenticates its most. */
  static final String BUSY_REASON =
      "The server is busy logging players in. Please try again in a moment.";

  /** Told to a player the anti-bot does not know, during a lockdown. */
  static final String LOCKDOWN_REASON =
      "This server only lets in players who have played here before, for now. Please try again"
          + " later.";

  /** Told to a player whose login took longer than the login timeout. */
  static final String TIMED_OUT_REASON = "Login timed out";

  /** Warnings that the session server fails, shared by every login: one a minute at most. */
  private static final LogThrottle SESSION_SERVER_WARNINGS = new LogThrottle(Duration.ofMinutes(1));

  // ---------------------------------------------------------------------------
  // State machine
  // ---------------------------------------------------------------------------

  private enum LoginState {
    AWAITING_LOGIN_START,
    PRE_LOGIN,
    /** Waiting in line for an authentication slot, before the encryption request. */
    QUEUED,
    AWAITING_ENCRYPTION_RESPONSE,
    AUTHENTICATING,
    AWAITING_LOGIN_ACKNOWLEDGED,
    COMPLETE,
    /**
     * Refused by the limits, or timed out: closing, and whatever the client still sends is dropped.
     */
    REFUSED
  }

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  private final MinecraftConnection connection;
  private final ServerLoginContext loginContext;

  /**
   * The client's cookie traffic from the start of its login, before any backend exists: the proxy
   * may ask for a cookie here (the session a transferred player carries, say), and the player it
   * logs in keeps it.
   */
  private final ClientCookies cookies;

  private LoginState state = LoginState.AWAITING_LOGIN_START;

  /** This login's place among its address's logins in progress, until logged in or closed. */
  private @Nullable LoginSlot slot;

  /** Closes the login if it takes longer than the login timeout. */
  private @Nullable ScheduledFuture<?> timeoutTask;

  /** The player once authenticated and registered, until the client enters its first server. */
  private @Nullable ConnectedPlayer player;

  private @Nullable String username;

  /** Whether this login authenticates with Mojang: the proxy's mode, unless a plugin forced it. */
  private boolean onlineMode;

  /** The connection as plugins see it before it becomes a player. */
  private final WarpInboundConnection inbound;

  private byte @Nullable [] verifyToken;

  /**
   * The profile key a 1.19 to 1.19.2 client sent, once accepted; {@code null} if it sent none, and
   * in offline mode.
   */
  private @Nullable PublicKey profileKey;

  /**
   * The UUID the profile key is signed for (1.19.1, 1.19.2); {@code null} before or without one.
   */
  private @Nullable UUID profileKeyHolder;

  /** What the anti-bot knows of this login's name and network, from its Login Start on. */
  private AntiBotService.@Nullable Recognition recognition;

  /** Whether a plugin's check escalated this login: its identity is not learned from it. */
  private boolean escalated;

  /** The login's place in the authentication line, while it waits. */
  private AuthenticationLane.@Nullable Waiter waiter;

  /** The authentication slot this login holds, until its session server request takes it over. */
  private AuthenticationLane.@Nullable Slot authSlot;

  /** Gives back a slot granted from the line if the encryption response does not come in time. */
  private @Nullable ScheduledFuture<?> slotDeadline;

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  LoginSessionHandler(MinecraftConnection connection, ServerLoginContext loginContext) {
    this.connection = connection;
    this.loginContext = loginContext;
    this.onlineMode = loginContext.onlineMode();
    this.inbound = new WarpInboundConnection(connection);
    this.cookies =
        new ClientCookies(
            connection, connection.decoder().version(), connection.channel().eventLoop());
  }

  /**
   * Returns the client's cookie traffic, through which the proxy asks the client for cookies during
   * its login (1.20.5+): {@link ClientCookies#request} works from the handshake on. In online mode,
   * asking once encryption is on keeps the cookie off the wire in clear.
   *
   * @return the cookie traffic of the client connection
   */
  ClientCookies cookies() {
    return cookies;
  }

  // ---------------------------------------------------------------------------
  // SessionHandler interface
  // ---------------------------------------------------------------------------

  @Override
  public void activated() {
    ClientLimits limits = loginContext.limits();
    switch (limits.admitLogin(connection.remoteAddress())) {
      case LoginAdmission.Admitted(LoginSlot admitted) -> this.slot = admitted;
      case LoginAdmission.TooFast(Duration retryAfter) -> {
        refuse(tooFastReason(retryAfter));
        return;
      }
      case LoginAdmission.TooManyInProgress() -> {
        refuse(TOO_MANY_LOGINS_REASON);
        return;
      }
    }
    Duration timeout = limits.loginTimeout();
    if (!timeout.isZero()) {
      timeoutTask =
          connection
              .channel()
              .eventLoop()
              .schedule(this::timedOut, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void handle(Packet packet) {
    if (state == LoginState.REFUSED) {
      return; // Packets that arrived with the refused handshake: closing, nothing to answer.
    }
    if (packet instanceof CookieResponse response) {
      handleCookieResponse(response);
      return;
    }
    ConnectedPlayer loggedIn = this.player;
    if (packet instanceof KeepAlive keepAlive && state == LoginState.COMPLETE && loggedIn != null) {
      // Before 1.20.2, the client waits for its first Join Game here, answering the proxy's
      // keep-alives, however long plugins take to choose its server.
      loggedIn.keepAliveAnswered(keepAlive.id());
      return;
    }
    if (!(packet instanceof LoginPacket loginPacket)) {
      if (state == LoginState.COMPLETE) {
        if (packet instanceof ResourcePackResponse response && loggedIn != null) {
          // A pack a plugin sent at PostLoginEvent, answered before the first backend is up.
          loggedIn.resourcePacks().clientAnswered(response);
          return;
        }
        // Before 1.20.2, a play packet ahead of the first Join Game, which the client has no
        // reason to send: dropped. From 1.20.2 the player's join replaces this handler at once
        // (SwitchWaitSessionHandler), which keeps the client's brand and settings for its server.
        return;
      }
      loginContext.limits().refused(Refusal.UNEXPECTED_PACKET, connection.remoteAddress());
      logger.debug(
          "Unexpected {} in LOGIN from {}",
          packet.getClass().getSimpleName(),
          connection.remoteAddress());
      state = LoginState.REFUSED;
      connection.reset();
      return;
    }

    // Exhaustive switch on the sealed LoginPacket hierarchy.
    switch (loginPacket) {
      case LoginStart start -> handleLoginStart(start);
      case EncryptionResponse response -> handleEncryptionResponse(response);
      case LoginAcknowledged _ -> handleLoginAcknowledged();
      // Warp sends the client no login plugin request (yet), so a response answers nothing.
      case LoginPluginResponse _ -> {}
      // Clientbound packets must never arrive from a client.
      case EncryptionRequest _,
          LoginSuccess _,
          SetCompression _,
          LoginDisconnect _,
          LoginPluginRequest _ ->
          connection.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Packet handlers
  // ---------------------------------------------------------------------------

  /**
   * Routes the client's answer to a cookie request: one of the proxy's own in the login state, or,
   * once the client is past its login and waits for its first server, possibly one of that
   * server's. In the login state the proxy is the server, and refuses an answer to nothing as
   * vanilla does.
   */
  private void handleCookieResponse(CookieResponse response) {
    if (!cookies.answered(response) && state != LoginState.COMPLETE) {
      loginContext.limits().refused(Refusal.UNEXPECTED_PACKET, connection.remoteAddress());
      logger.debug(
          "Disconnecting {} from {}: cookie {} sent unasked",
          username != null ? username : "unknown",
          connection.remoteAddress(),
          response.key());
      state = LoginState.REFUSED;
      connection.writeAndClose(LoginDisconnect.ofTranslation(UNEXPECTED_QUERY_RESPONSE));
    }
  }

  private void handleLoginStart(LoginStart packet) {
    if (state != LoginState.AWAITING_LOGIN_START) {
      deny(Refusal.UNEXPECTED_PACKET, "Unexpected LoginStart");
      return;
    }

    String name = packet.name();
    if (!USERNAME_PATTERN.matcher(name).matches()) {
      deny(Refusal.INVALID_LOGIN, "Invalid username");
      return;
    }

    this.username = name;
    state = LoginState.PRE_LOGIN;

    // The anti-bot first: no plugin hears of a login before the stages that apply to it.
    AntiBotService antiBot = loginContext.antiBot();
    InetSocketAddress address = (InetSocketAddress) connection.remoteAddress();
    whenOnLoop(
        antiBot.recognizeEverywhere(name, address),
        recognized -> {
          this.recognition = recognized;
          if (!loginContext.onlineMode() && antiBot.lockdownRefuses(recognized)) {
            deny(Refusal.LOCKDOWN, LOCKDOWN_REASON);
            return;
          }
          if (recognized.known() || !antiBot.hasChecks()) {
            preLogin(packet);
            return;
          }
          LoginAttempt attempt =
              new LoginAttempt(
                  name,
                  address,
                  clientVersion().protocol(),
                  connection.virtualHost(),
                  loginContext.onlineMode());
          whenOnLoop(
              antiBot.check(attempt),
              verdict -> {
                switch (verdict) {
                  case Verdict.Fail(Component reason) -> {
                    deny(Refusal.CHECK_FAILED, reason);
                    return;
                  }
                  case Verdict.Escalate() -> escalated = true;
                  case Verdict.Pass(), Verdict.Abstain() -> {
                    // No objection.
                  }
                }
                preLogin(packet);
              });
        });
  }

  /** Lets the pre-login handlers decide on a login the anti-bot let through. */
  private void preLogin(LoginStart packet) {
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(PreLoginEventImpl.class)) {
      state = LoginState.AWAITING_LOGIN_START;
      continueLogin(packet);
      return;
    }
    whenOnLoop(
        events.fire(new PreLoginEventImpl(inbound, packet.name())),
        event -> {
          switch (event.result()) {
            case PreLoginEvent.Denied(Component reason) -> {
              deny(Refusal.DENIED_BY_PLUGIN, reason);
              return;
            }
            case PreLoginEvent.ForceOnlineMode() -> onlineMode = true;
            case PreLoginEvent.ForceOfflineMode() -> onlineMode = false;
            case PreLoginEvent.Allowed() -> {
              // The proxy's mode.
            }
          }
          state = LoginState.AWAITING_LOGIN_START;
          continueLogin(packet);
        });
  }

  /**
   * Runs {@code next} on the event loop with the result of a step of the pre-login, unless the
   * client left or the login moved on meanwhile: at once if the step is already done, as it is when
   * no plugin is involved.
   */
  private <T> void whenOnLoop(CompletableFuture<T> step, Consumer<T> next) {
    if (step.isDone() && connection.channel().eventLoop().inEventLoop()) {
      if (connection.channel().isActive() && state == LoginState.PRE_LOGIN) {
        next.accept(step.join());
      }
      return;
    }
    var _ =
        step.whenCompleteAsync(
            (result, failure) -> {
              if (failure != null
                  || result == null
                  || !connection.channel().isActive()
                  || state != LoginState.PRE_LOGIN) {
                return;
              }
              next.accept(result);
            },
            connection.channel().eventLoop());
  }

  /** Goes on with a login the pre-login handlers let through. */
  private void continueLogin(LoginStart packet) {
    AntiBotService.Recognition recognized = recognition();
    if (!onlineMode && loginContext.antiBot().lockdownRefuses(recognized)) {
      // An online proxy a plugin made offline for this login: lockdown has no account to check.
      deny(Refusal.LOCKDOWN, LOCKDOWN_REASON);
      return;
    }
    // 1.19 to 1.19.2, online mode: the client will sign the verify token with its profile key, so
    // the key is checked on arrival, as Velocity's InitialLoginSessionHandler does. A client
    // without a key is accepted (Velocity's force-key-authentication off): Warp forwards no key, so
    // a backend has nothing to require one for. Offline, the key is ignored, as vanilla 1.19.1+
    // ignores it: Warp authenticates nobody and uses the key for nothing, so a check would only
    // refuse players whose key another authority signed (authlib-injector accounts).
    LoginStart.ProfilePublicKey key = packet.profileKey();
    if (key != null && onlineMode) {
      switch (loginContext.profileKeys().check(key, packet.playerUuid(), clientVersion())) {
        case ProfileKeys.Verdict.Refused(ProfileKeys.Problem problem) -> {
          refuseProfileKey(problem);
          return;
        }
        case ProfileKeys.Verdict.Accepted(PublicKey accepted) -> {
          this.profileKey = accepted;
          // From 1.19.1 the key is signed for this UUID: the player must authenticate as it.
          this.profileKeyHolder = packet.playerUuid();
        }
      }
    }

    if (onlineMode) {
      AuthenticationLane lane = loginContext.limits().lane();
      if (lane.mustWait()) {
        queue(lane, recognized.known());
        return;
      }
      beginOnlineMode();
    } else {
      beginOfflineMode();
    }
  }

  /**
   * Puts an online login in line for an authentication slot, while the client can still be told why
   * it waits too long: once it sends its encryption response, it expects encrypted packets. Trusted
   * players go first.
   */
  private void queue(AuthenticationLane lane, boolean trusted) {
    state = LoginState.QUEUED;
    AuthenticationLane.Waiter placed =
        lane.enqueue(
            trusted,
            connection.channel().eventLoop(),
            granted -> {
              if (!connection.channel().isActive() || state != LoginState.QUEUED) {
                granted.release();
                return;
              }
              this.waiter = null;
              this.authSlot = granted;
              // Held from now on: given back unless the encryption response comes in time.
              slotDeadline =
                  connection
                      .channel()
                      .eventLoop()
                      .schedule(
                          this::slotTimedOut, lane.maxWait().toMillis(), TimeUnit.MILLISECONDS);
              beginOnlineMode();
            },
            () -> {
              this.waiter = null;
              if (connection.channel().isActive() && state == LoginState.QUEUED) {
                refuseBusy(lane);
              }
            });
    if (placed == null) {
      refuseBusy(lane);
      return;
    }
    this.waiter = placed;
  }

  private void refuseBusy(AuthenticationLane lane) {
    loginContext.limits().refused(Refusal.AUTHENTICATION_BUSY, connection.remoteAddress());
    refuse(busyReason(lane.maxWait()));
  }

  /** A slot granted from the line, whose client did not answer the encryption request in time. */
  private void slotTimedOut() {
    slotDeadline = null;
    if (connection.channel().isActive() && state == LoginState.AWAITING_ENCRYPTION_RESPONSE) {
      loginContext.limits().refused(Refusal.LOGIN_TIMEOUT, connection.remoteAddress());
      refuse(TIMED_OUT_REASON);
    }
  }

  /** The reason shown to a player who waited in line too long, with when to try again. */
  static String busyReason(Duration retryAfter) {
    long seconds = Math.max(1, retryAfter.toSeconds());
    return "The server is busy logging players in. Please try again in "
        + seconds
        + (seconds == 1 ? " second." : " seconds.");
  }

  private AntiBotService.Recognition recognition() {
    AntiBotService.Recognition recognized = recognition;
    if (recognized == null) {
      throw new IllegalStateException("the login was not recognized before it went on");
    }
    return recognized;
  }

  private void handleEncryptionResponse(EncryptionResponse packet) {
    if (state != LoginState.AWAITING_ENCRYPTION_RESPONSE) {
      deny(Refusal.UNEXPECTED_PACKET, "Unexpected EncryptionResponse");
      return;
    }

    // Prevent duplicate processing.
    state = LoginState.AUTHENTICATING;
    ScheduledFuture<?> deadline = this.slotDeadline;
    if (deadline != null) {
      deadline.cancel(false);
      this.slotDeadline = null;
    }

    // Bounds the RSA work and the session server requests in flight, which a bot attack with
    // accounts, or a flood of fake responses, would otherwise drive without limit. A login that
    // waited in line holds its slot already; another takes one now, or waits in line silently:
    // the client expects encrypted packets from now on, so it cannot be told why it waits.
    if (authSlot == null) {
      AuthenticationLane lane = loginContext.limits().lane();
      AuthenticationLane.Slot free = lane.tryAcquire();
      if (free == null) {
        waitForSlot(lane, packet);
        return;
      }
      authSlot = free;
    }
    authenticate(packet);
  }

  /** Waits silently in line for a slot to authenticate an encryption response with. */
  private void waitForSlot(AuthenticationLane lane, EncryptionResponse packet) {
    AuthenticationLane.Waiter placed =
        lane.enqueue(
            recognition().known(),
            connection.channel().eventLoop(),
            granted -> {
              this.waiter = null;
              if (!connection.channel().isActive() || state != LoginState.AUTHENTICATING) {
                granted.release();
                return;
              }
              this.authSlot = granted;
              authenticate(packet);
            },
            () -> {
              this.waiter = null;
              if (connection.channel().isActive() && state == LoginState.AUTHENTICATING) {
                closeBusy();
              }
            });
    if (placed == null) {
      closeBusy();
      return;
    }
    this.waiter = placed;
  }

  private void closeBusy() {
    loginContext.limits().refused(Refusal.AUTHENTICATION_BUSY, connection.remoteAddress());
    state = LoginState.REFUSED;
    endOfLogin();
    connection.close();
  }

  /** Checks an encryption response with a slot held, then asks the session server. */
  private void authenticate(EncryptionResponse packet) {
    byte[] token = this.verifyToken;
    if (token == null) {
      disconnect("Internal error");
      return;
    }
    // The slot is given back here, unless the session server request is under way: it does.
    boolean requested = false;
    try {
      byte[] secret = sharedSecret(packet, token);
      if (secret == null) {
        deny(Refusal.AUTHENTICATION_FAILED, ENCRYPTION_FAILED);
        return;
      }

      // Install cipher on the pipeline BEFORE async auth: it secures the channel immediately.
      enableEncryption(new SecretKeySpec(secret, "AES"));

      // Authenticate with Mojang asynchronously on a virtual thread.
      requested = authenticateAsync(secret);

    } catch (GeneralSecurityException e) {
      // AES/CFB8 is in every JDK: a broken runtime, nothing a client can bring about.
      logger.error("Cannot enable encryption for {}", connection.remoteAddress(), e);
      connection.writeAndClose(plainDisconnect("Internal error"));
    } finally {
      if (!requested) {
        releaseAuthSlot();
      }
    }
  }

  /**
   * Returns the shared secret of an encryption response, or {@code null} if the response is
   * invalid, whatever the reason: a verify token proof that does not check, a value that is not a
   * valid ciphertext, or a secret that is not {@value #SHARED_SECRET_LENGTH} bytes.
   *
   * <p>RSA with PKCS#1 v1.5 padding, which the protocol fixes, falls to Bleichenbacher's attack
   * wherever a client can tell a well padded ciphertext from a badly padded one. So every invalid
   * response gets the same reply ({@value #ENCRYPTION_FAILED}), {@link Pkcs1Decryption} checks the
   * padding in time that does not depend on it, and, as the vanilla server does, the token is
   * checked before the secret is decrypted: the work a response costs depends on whether its token
   * checks, which the client knows, never on a padding.
   *
   * <p>The client proves it received the verify token: encrypted with the proxy's key, or, from a
   * 1.19 to 1.19.2 client that sent a profile key, signed with that key. A client with a key must
   * sign (vanilla and Velocity refuse the encrypted form from it), one without cannot.
   */
  private byte @Nullable [] sharedSecret(EncryptionResponse packet, byte[] token) {
    PrivateKey key = loginContext.rsaKeyPair().getPrivate();
    @Nullable PublicKey playerKey = this.profileKey;
    boolean proven =
        switch (packet.verifyToken()) {
          case EncryptedToken(byte[] encrypted) ->
              playerKey == null && Pkcs1Decryption.decryptsTo(key, encrypted, token);
          case SignedToken(long salt, byte[] signature) ->
              playerKey != null && ProfileKeys.signsVerifyToken(playerKey, token, salt, signature);
        };
    return proven
        ? Pkcs1Decryption.decrypt(key, packet.sharedSecret(), SHARED_SECRET_LENGTH)
        : null;
  }

  private void releaseAuthSlot() {
    AuthenticationLane.Slot held = authSlot;
    if (held != null) {
      authSlot = null;
      held.release();
    }
  }

  private void handleLoginAcknowledged() {
    logger.debug("LoginAcknowledged received from {}, state={}", username, state);
    if (state != LoginState.AWAITING_LOGIN_ACKNOWLEDGED) {
      deny(Refusal.UNEXPECTED_PACKET, "Unexpected LoginAcknowledged");
      return;
    }

    state = LoginState.COMPLETE;
    connection.setState(ProtocolState.CONFIGURATION);
    initiateBackendConnection();
  }

  @Override
  public void deactivated() {
    logger.debug("LoginSessionHandler replaced for: {} (state={})", username, state);
    endOfLogin();
  }

  @Override
  public void disconnected() {
    endOfLogin();
    if (state == LoginState.REFUSED) {
      return; // Counted by the limits: a flood must not write a line per connection.
    }
    ConnectedPlayer loggedIn = this.player;
    if (loggedIn == null) {
      // Not logged in: anyone can open logins and drop them, so never a line per connection.
      logger.debug("Client disconnected during login: {} (state={})", username, state);
      return;
    }
    if (loginContext.logging().connections()
        && loginContext.antiBot().logs(RoutineLine.CONNECTION)) {
      logger.info("Client disconnected during login: {} (state={})", username, state);
    }
    // Logged in but not on a server yet: drop the backend it is joining.
    loggedIn.disconnect();
  }

  // ---------------------------------------------------------------------------
  // Login flows
  // ---------------------------------------------------------------------------

  private void beginOnlineMode() {
    // Generate a random verify token.
    byte[] token = new byte[VERIFY_TOKEN_LENGTH];
    ThreadLocalRandom.current().nextBytes(token);
    this.verifyToken = token;

    byte[] publicKey = loginContext.rsaKeyPair().getPublic().getEncoded();
    boolean shouldAuthenticate = clientVersion().isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);

    EncryptionRequest encryptionRequest =
        new EncryptionRequest("", publicKey, token, shouldAuthenticate);
    connection.writeAndFlush(encryptionRequest);

    state = LoginState.AWAITING_ENCRYPTION_RESPONSE;
  }

  private void beginOfflineMode() {
    String name = this.username;
    if (name == null) {
      disconnect("Internal error");
      return;
    }
    UUID offlineUuid =
        UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    GameProfile profile = new GameProfile(offlineUuid, name, List.of());
    completeLogin(profile);
  }

  /**
   * Starts the session server request on a virtual thread.
   *
   * @return {@code true} once the request is under way: it ends the authentication with the limits
   *     when it returns
   */
  private boolean authenticateAsync(byte[] sharedSecret) {
    String playerName = this.username;
    if (playerName == null) {
      disconnect("Internal error");
      return false;
    }
    // The request owns the slot from now on, and gives it back when the session server answers,
    // even if the client left meanwhile.
    AuthenticationLane.Slot requestSlot = this.authSlot;
    this.authSlot = null;

    byte[] publicKey = loginContext.rsaKeyPair().getPublic().getEncoded();
    String serverHash = ServerHash.compute("", sharedSecret, publicKey);
    MojangSessionService sessionService = loginContext.sessionService();

    // Run the blocking HTTP call on a virtual thread — never blocks the event loop.
    Thread.ofVirtual()
        .name("warp-auth-" + playerName)
        .start(
            () -> {
              try {
                GameProfile profile = sessionService.hasJoined(playerName, serverHash);

                // Post back to the event loop for all state mutations.
                connection
                    .channel()
                    .eventLoop()
                    .execute(
                        () -> {
                          if (!connection.channel().isActive()
                              || state != LoginState.AUTHENTICATING) {
                            return; // Client disconnected or state changed during auth.
                          }
                          UUID holder = this.profileKeyHolder;
                          if (holder != null && !holder.equals(profile.uuid())) {
                            // The key belongs to another player (Velocity: internalAddHolder).
                            refuseProfileKey(ProfileKeys.Problem.OTHER_HOLDER);
                            return;
                          }
                          // Lockdown, online: an account trusted from another network gets in.
                          if (loginContext
                              .antiBot()
                              .lockdownRefuses(recognition(), profile.uuid())) {
                            deny(Refusal.LOCKDOWN, LOCKDOWN_REASON);
                            return;
                          }
                          completeLogin(profile);
                        });

              } catch (AuthenticationException e) {
                connection
                    .channel()
                    .eventLoop()
                    .execute(
                        () -> {
                          if (!connection.channel().isActive()) {
                            return;
                          }
                          if (e.serverFault()) {
                            // No player can log in while the session server fails: worth a
                            // warning, once a minute at most, whoever triggers it.
                            long more = SESSION_SERVER_WARNINGS.tryAcquire();
                            if (more >= 0) {
                              logger.warn(
                                  "The session server failed to authenticate {}: {}{}",
                                  playerName,
                                  e.getMessage(),
                                  LogThrottle.suffix(more, "session server failures"));
                            }
                          }
                          deny(Refusal.AUTHENTICATION_FAILED, "Authentication failed");
                        });
              } finally {
                if (requestSlot != null) {
                  requestSlot.release();
                }
              }
            });
    return true;
  }

  private void completeLogin(GameProfile authenticated) {
    // Logged in: the login gives its place back, and no longer times out.
    endOfLogin();
    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(GameProfileRequestEventImpl.class)) {
      registerPlayer(authenticated);
      return;
    }
    state = LoginState.AUTHENTICATING;
    var _ =
        events
            .fire(new GameProfileRequestEventImpl(inbound, onlineMode, authenticated.toApi()))
            .whenCompleteAsync(
                (event, _) -> {
                  if (connection.channel().isActive()) {
                    registerPlayer(GameProfile.fromApi(event.profile()));
                  }
                },
                connection.channel().eventLoop());
  }

  /**
   * Registers the player, settling a login of an account already connected, then asks the login
   * handlers whether it may join: they start from a denial when the proxy is full.
   */
  private void registerPlayer(GameProfile profile) {
    // Register before the client hears its login succeeded, so that a refused duplicate still gets
    // a login disconnect it can show.
    ConnectedPlayer joining =
        new ConnectedPlayer(
            connection,
            clientVersion(),
            profile,
            (InetSocketAddress) connection.remoteAddress(),
            loginContext,
            cookies);
    joining.setOnlineMode(onlineMode);
    boolean kickExisting = loginContext.duplicateLoginPolicy().kicksExisting(onlineMode);
    if (!(loginContext.playerRegistry().register(joining, kickExisting)
        instanceof PlayerRegistry.Registration.Registered(int place))) {
      // Counted, not logged: in offline mode anyone can claim the name of a player online.
      deny(Refusal.DUPLICATE_LOGIN, "You are already connected to this proxy.");
      return;
    }
    this.player = joining;

    // The place is taken atomically at registration: of two logins racing for the last place,
    // exactly one gets it. A session replacing a duplicate takes the place it frees.
    LoginEvent.Result proposed =
        loginContext.serverList().settings().isFull(place)
            ? new LoginEvent.Denied(Component.text(SERVER_FULL))
            : new LoginEvent.Allowed();

    WarpEventBus events = loginContext.events();
    if (!events.hasHandlers(LoginEventImpl.class)) {
      if (proposed instanceof LoginEvent.Denied) {
        refuseFull(joining);
        return;
      }
      succeedLogin(profile);
      return;
    }
    // On the account's lane: an old session kicked above has its disconnect handled first.
    var _ =
        events
            .fire(new LoginEventImpl(joining.api(), proposed), profile.uuid())
            .whenCompleteAsync(
                (event, _) -> {
                  if (!connection.channel().isActive()) {
                    joining.disconnect();
                    return;
                  }
                  if (event.result() instanceof LoginEvent.Denied(Component reason)) {
                    // No session started: no disconnect event for this player.
                    this.player = null;
                    loginContext.playerRegistry().unregister(joining);
                    deny(
                        proposed instanceof LoginEvent.Denied
                            ? Refusal.SERVER_FULL
                            : Refusal.DENIED_BY_PLUGIN,
                        reason);
                    return;
                  }
                  succeedLogin(profile);
                },
                connection.channel().eventLoop());
  }

  /** Refuses a player beyond the player limit, which no login handler let in. */
  private void refuseFull(ConnectedPlayer joining) {
    this.player = null;
    loginContext.playerRegistry().unregister(joining);
    deny(Refusal.SERVER_FULL, SERVER_FULL);
  }

  /** Tells the client its login succeeded. */
  private void succeedLogin(GameProfile profile) {
    // Enable compression (must send SetCompression BEFORE installing handlers).
    enableCompression();

    boolean strictErrorHandling = clientVersion().isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);
    LoginSuccess loginSuccess =
        new LoginSuccess(
            profile.uuid(),
            profile.name(),
            profile.properties(),
            strictErrorHandling,
            joinPlaySession());
    if (clientVersion().isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
      // The client reads what follows in the configuration state, before it acknowledges: what the
      // proxy sends of its own (a cookie request) waits for the acknowledgement.
      connection.writeTerminal(loginSuccess);
    } else {
      connection.writeAndFlush(loginSuccess);
    }

    ConnectedPlayer joined = this.player;
    if (joined != null
        && loginContext.logging().connections()
        && loginContext.antiBot().logs(RoutineLine.CONNECTION)) {
      joined.log(logger, Level.INFO).log(
          "Player {} ({}) logged in from {}{}",
          profile.name(),
          profile.uuid(),
          connection.remoteAddress(),
          connection.handshakeIntent() == HandshakeIntent.TRANSFER ? ", transferred" : "");
    }
    // No longer among the connections not logged in, nor under their deadlines and frame limits:
    // from now on it is read under the read timeout of logged-in connections.
    connection.loggedIn();
    useLoggedInReadTimeout();
    // Refreshes what the anti-bot knows of the player, or learns it outside attacks.
    loginContext.antiBot().joined(recognition(), onlineMode ? profile.uuid() : null, escalated);

    if (clientVersion().isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
      // The client reads configuration packets from Login Success on, though it is still read in
      // the login state until it acknowledges: anything sent to it from now on, a disconnect above
      // all, is a configuration packet (BungeeCord #3525).
      connection.encoder().setState(ProtocolState.CONFIGURATION);
      // Wait for LoginAcknowledged before transitioning.
      state = LoginState.AWAITING_LOGIN_ACKNOWLEDGED;
    } else {
      // Pre-1.20.2: transition directly to PLAY.
      state = LoginState.COMPLETE;
      connection.setState(ProtocolState.PLAY);
      initiateBackendConnection();
    }
  }

  /**
   * Gives the connection of the player now logged in the read timeout of logged-in connections,
   * {@code timeouts.read}, in place of the one it was accepted with: the time a player may stay
   * silent is a setting, the time a connection may take to log in is the limits' business.
   */
  private void useLoggedInReadTimeout() {
    ChannelPipeline pipeline = connection.channel().pipeline();
    if (pipeline.get(ServerChannelInitializer.READ_TIMEOUT) != null) {
      var _ =
          pipeline.replace(
              ServerChannelInitializer.READ_TIMEOUT,
              ServerChannelInitializer.READ_TIMEOUT,
              new ReadTimeoutHandler(
                  loginContext.timeouts().read().toMillis(), TimeUnit.MILLISECONDS));
    }
  }

  /**
   * Counts the player in the proxy's play session until its connection closes, and returns the
   * session's ID (sent to 26.2+ clients).
   */
  private UUID joinPlaySession() {
    PlaySession session = loginContext.playSession();
    UUID sessionId = session.join();
    connection.channel().closeFuture().addListener(_ -> session.leave());
    return sessionId;
  }

  // ---------------------------------------------------------------------------
  // Pipeline modifications
  // ---------------------------------------------------------------------------

  private void enableEncryption(SecretKey sharedSecret) throws GeneralSecurityException {
    ChannelPipeline pipeline = connection.channel().pipeline();

    // Inbound: cipher must sit before frame-decoder (encrypts entire TCP stream).
    pipeline.addBefore(
        ServerChannelInitializer.FRAME_DECODER,
        ServerChannelInitializer.CIPHER_DECODER,
        new CipherDecoder(sharedSecret));

    // Outbound: cipher must sit before the framing layer in pipeline order, so Netty's
    // tail-to-head outbound traversal hits frame-encoder (and any plugin frame handler) first,
    // then cipher-encoder.
    pipeline.addAfter(
        ServerChannelInitializer.CIPHER_DECODER,
        ServerChannelInitializer.CIPHER_ENCODER,
        new CipherEncoder(sharedSecret));
  }

  private void enableCompression() {
    int threshold = loginContext.compressionThreshold(clientVersion());
    if (threshold < 0) {
      return; // Compression disabled, or a 1.7 client, which predates it.
    }

    // Send SetCompression BEFORE switching the pipeline — this packet is uncompressed.
    connection.writeAndFlush(new SetCompression(threshold));
    connection.enableCompression(
        threshold, loginContext.compressionLevel(), false, loginContext.compressionPassthrough());
  }

  // ---------------------------------------------------------------------------
  // Backend connection
  // ---------------------------------------------------------------------------

  /**
   * Connects the logged-in player to its initial server.
   *
   * <p>The backend connection runs asynchronously. An initial server that cannot be reached or
   * refuses the player sends it down the fallback order, whatever its version.
   */
  private void initiateBackendConnection() {
    ConnectedPlayer joining = this.player;
    if (joining == null) {
      disconnect("Internal error");
      return;
    }
    joining.join();
  }

  // ---------------------------------------------------------------------------
  // Limits
  // ---------------------------------------------------------------------------

  /**
   * Releases the login's place among its address's logins in progress, its timeout, its place in
   * the authentication line and the slot it holds (unless its session server request took it).
   */
  private void endOfLogin() {
    LoginSlot held = this.slot;
    if (held != null) {
      held.release();
      this.slot = null;
    }
    ScheduledFuture<?> pending = this.timeoutTask;
    if (pending != null) {
      pending.cancel(false);
      this.timeoutTask = null;
    }
    AuthenticationLane.Waiter waiting = this.waiter;
    if (waiting != null) {
      this.waiter = null;
      waiting.cancel();
    }
    ScheduledFuture<?> deadline = this.slotDeadline;
    if (deadline != null) {
      deadline.cancel(false);
      this.slotDeadline = null;
    }
    releaseAuthSlot();
  }

  private void timedOut() {
    this.timeoutTask = null;
    if (!connection.channel().isActive()) {
      return;
    }
    loginContext.limits().refused(Refusal.LOGIN_TIMEOUT, connection.remoteAddress());
    logger.debug("Login of {} from {} timed out", username, connection.remoteAddress());
    refuse(TIMED_OUT_REASON);
  }

  /**
   * Closes a login the limits refuse, or that timed out, showing the client the reason. The client
   * is in the LOGIN state: refusals happen right after its handshake, and the timeout is cancelled
   * once it is logged in. Counted by the limits, not logged one by one, so that a flood cannot fill
   * the logs.
   */
  private void refuse(String reason) {
    state = LoginState.REFUSED;
    endOfLogin();
    connection.writeAndClose(LoginDisconnect.ofPlainText(reason));
  }

  /** The reason shown to a player who tries to log in too often, with how long to wait. */
  static String tooFastReason(Duration retryAfter) {
    long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    return "You are logging in too fast. Please try again in "
        + seconds
        + (seconds == 1 ? " second." : " seconds.");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ProtocolVersion clientVersion() {
    return connection.decoder().version();
  }

  /**
   * Refuses the login over its profile key: the client is shown {@link ProfileKeys#REFUSAL_REASON},
   * translated, and the log says what is wrong. Only called in the login state.
   */
  private void refuseProfileKey(ProfileKeys.Problem problem) {
    loginContext.limits().refused(Refusal.INVALID_LOGIN, connection.remoteAddress());
    logger.debug(
        "Disconnecting {} from {}: invalid profile public key, {}",
        username != null ? username : "unknown",
        connection.remoteAddress(),
        problem.description());
    state = LoginState.REFUSED;
    endOfLogin();
    connection.writeAndClose(LoginDisconnect.ofTranslation(ProfileKeys.REFUSAL_REASON));
  }

  /**
   * Refuses a login for a reason its client can bring about at will, telling the client: counted
   * under {@code reason} and logged at DEBUG only, so that a flood of them cannot fill the logs.
   */
  private void deny(Refusal reason, Component message) {
    countDenial(reason, PlainTextComponentSerializer.plainText().serialize(message));
    Packet disconnectPacket =
        switch (connection.encoder().state()) {
          case HANDSHAKE, STATUS, LOGIN -> LoginDisconnect.of(message, clientVersion());
          case CONFIGURATION -> ConfigDisconnect.of(message, clientVersion());
          case PLAY -> PlayDisconnect.of(message, clientVersion());
        };
    connection.writeAndClose(disconnectPacket);
  }

  /** Refuses a login as {@link #deny(Refusal, Component)} does, with a plain text reason. */
  private void deny(Refusal reason, String message) {
    countDenial(reason, message);
    connection.writeAndClose(plainDisconnect(message));
  }

  private void countDenial(Refusal reason, String message) {
    loginContext.limits().refused(reason, connection.remoteAddress());
    logger.debug(
        "Disconnecting {} from {} ({}): {}",
        username != null ? username : "unknown",
        connection.remoteAddress(),
        reason.label(),
        message);
    state = LoginState.REFUSED;
    endOfLogin();
  }

  /**
   * Disconnects the client over something that may reveal a bug in the proxy: logged at WARN.
   *
   * @param reason the reason, shown to the client
   */
  private void disconnect(String reason) {
    logger.warn(
        "Disconnecting {} from {}: {}",
        username != null ? username : "unknown",
        connection.remoteAddress(),
        reason);
    connection.writeAndClose(plainDisconnect(reason));
  }

  /**
   * The disconnect packet of the state the client reads: Login Success moves it to CONFIGURATION
   * from 1.20.2, to PLAY before. The login reason is JSON in every version; the configuration and
   * play reasons follow the client's version.
   */
  private Packet plainDisconnect(String reason) {
    return switch (connection.encoder().state()) {
      case HANDSHAKE, STATUS, LOGIN -> LoginDisconnect.ofPlainText(reason);
      case CONFIGURATION -> ConfigDisconnect.ofPlainText(reason, clientVersion());
      case PLAY -> PlayDisconnect.ofPlainText(reason, clientVersion());
    };
  }
}
