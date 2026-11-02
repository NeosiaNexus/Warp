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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.CipherDecoder;
import dev.warp.protocol.netty.CipherEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.config.ConfigDisconnect;
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
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.proxy.auth.AuthenticationException;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.auth.ProfileKeys;
import dev.warp.proxy.auth.ServerHash;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.LoginAdmission;
import dev.warp.proxy.limit.LoginSlot;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  /** Told to a player over the limit of logins in progress from its address. */
  static final String TOO_MANY_LOGINS_REASON =
      "Too many logins in progress from your address. Please try again in a moment.";

  /** Told to a player who starts an online login while the proxy authenticates its most. */
  static final String BUSY_REASON =
      "The server is busy logging players in. Please try again in a moment.";

  /** Told to a player whose login took longer than the login timeout. */
  static final String TIMED_OUT_REASON = "Login timed out";

  // ---------------------------------------------------------------------------
  // State machine
  // ---------------------------------------------------------------------------

  private enum LoginState {
    AWAITING_LOGIN_START,
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

  private LoginState state = LoginState.AWAITING_LOGIN_START;

  /** This login's place among its address's logins in progress, until logged in or closed. */
  private @Nullable LoginSlot slot;

  /** Closes the login if it takes longer than the login timeout. */
  private @Nullable ScheduledFuture<?> timeoutTask;

  /** The player once logged in, until the client enters its first server. */
  private @Nullable ConnectedPlayer player;

  private @Nullable String username;
  private byte @Nullable [] verifyToken;
  private @Nullable GameProfile authenticatedProfile;

  /**
   * The profile key a 1.19 to 1.19.2 client sent, once accepted; {@code null} if it sent none, and
   * in offline mode.
   */
  private @Nullable PublicKey profileKey;

  /**
   * The UUID the profile key is signed for (1.19.1, 1.19.2); {@code null} before or without one.
   */
  private @Nullable UUID profileKeyHolder;

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  LoginSessionHandler(MinecraftConnection connection, ServerLoginContext loginContext) {
    this.connection = connection;
    this.loginContext = loginContext;
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
    if (!(packet instanceof LoginPacket loginPacket)) {
      if (state == LoginState.COMPLETE) {
        // Client may send CONFIG packets (e.g., brand) before the backend handler is installed.
        // Silently drop them — the backend will request them during configuration.
        return;
      }
      logger.warn("Unexpected packet in LOGIN state: {}", packet.getClass().getSimpleName());
      connection.close();
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

  private void handleLoginStart(LoginStart packet) {
    if (state != LoginState.AWAITING_LOGIN_START) {
      disconnect("Unexpected LoginStart");
      return;
    }

    String name = packet.name();
    if (!USERNAME_PATTERN.matcher(name).matches()) {
      disconnect("Invalid username");
      return;
    }

    this.username = name;

    // 1.19 to 1.19.2, online mode: the client will sign the verify token with its profile key, so
    // the key is checked on arrival, as Velocity's InitialLoginSessionHandler does. A client
    // without a key is accepted (Velocity's force-key-authentication off): Warp forwards no key, so
    // a backend has nothing to require one for. Offline, the key is ignored, as vanilla 1.19.1+
    // ignores it: Warp authenticates nobody and uses the key for nothing, so a check would only
    // refuse players whose key another authority signed (authlib-injector accounts).
    LoginStart.ProfilePublicKey key = packet.profileKey();
    if (key != null && loginContext.onlineMode()) {
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

    if (loginContext.onlineMode()) {
      // Told now, while the client can still read it: once it sends its encryption response, it
      // expects encrypted packets.
      if (!loginContext.limits().admitOnlineLogin()) {
        refuse(BUSY_REASON);
        return;
      }
      beginOnlineMode();
    } else {
      beginOfflineMode();
    }
  }

  private void handleEncryptionResponse(EncryptionResponse packet) {
    if (state != LoginState.AWAITING_ENCRYPTION_RESPONSE) {
      disconnect("Unexpected EncryptionResponse");
      return;
    }

    // Prevent duplicate processing.
    state = LoginState.AUTHENTICATING;

    byte[] token = this.verifyToken;
    if (token == null) {
      disconnect("Internal error");
      return;
    }

    // Bounds the RSA work and the session server requests in flight, which a bot attack with
    // accounts, or a flood of fake responses, would otherwise drive without limit. Full since the
    // login started: the client now expects encrypted packets, so it cannot be told why.
    ClientLimits limits = loginContext.limits();
    if (!limits.tryStartAuthentication()) {
      state = LoginState.REFUSED;
      endOfLogin();
      connection.close();
      return;
    }
    // Ended here, unless the session server request is under way: it ends it.
    boolean requested = false;
    try {
      Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      rsaCipher.init(Cipher.DECRYPT_MODE, loginContext.rsaKeyPair().getPrivate());

      // The client proves it received the verify token: encrypted with the proxy's key, or, from a
      // 1.19 to 1.19.2 client that sent a profile key, signed with that key. A client with a key
      // must sign (vanilla and Velocity refuse the encrypted form from it), one without cannot.
      @Nullable PublicKey playerKey = this.profileKey;
      switch (packet.verifyToken()) {
        case EncryptedToken(byte[] encrypted) -> {
          if (playerKey != null || !MessageDigest.isEqual(token, rsaCipher.doFinal(encrypted))) {
            disconnect("Verify token mismatch");
            return;
          }
        }
        case SignedToken(long salt, byte[] signature) -> {
          if (playerKey == null
              || !ProfileKeys.signsVerifyToken(playerKey, token, salt, signature)) {
            disconnect("Invalid verify token signature");
            return;
          }
        }
      }

      byte[] decryptedSharedSecret = rsaCipher.doFinal(packet.sharedSecret());

      // Validate shared secret length.
      if (decryptedSharedSecret.length != SHARED_SECRET_LENGTH) {
        disconnect("Invalid shared secret length");
        return;
      }

      // Install cipher on the pipeline BEFORE async auth — secures the channel immediately.
      SecretKey sharedSecret = new SecretKeySpec(decryptedSharedSecret, "AES");
      enableEncryption(sharedSecret);

      // Authenticate with Mojang asynchronously on a virtual thread.
      requested = authenticateAsync(decryptedSharedSecret);

    } catch (GeneralSecurityException e) {
      logger.warn("Encryption error for {} from {}", username, connection.remoteAddress(), e);
      disconnect("Encryption error");
    } finally {
      if (!requested) {
        limits.endAuthentication();
      }
    }
  }

  private void handleLoginAcknowledged() {
    logger.info("LoginAcknowledged received from {}, state={}", username, state);
    if (state != LoginState.AWAITING_LOGIN_ACKNOWLEDGED) {
      disconnect("Unexpected LoginAcknowledged");
      return;
    }

    state = LoginState.COMPLETE;
    connection.setState(ProtocolState.CONFIGURATION);

    GameProfile profile = this.authenticatedProfile;
    if (profile == null) {
      disconnect("Internal error");
      return;
    }
    initiateBackendConnection(profile);
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
    logger.info("Client disconnected during login: {} (state={})", username, state);
    ConnectedPlayer loggedIn = this.player;
    if (loggedIn != null) {
      // Logged in but not on a server yet: drop the backend it is joining.
      loggedIn.disconnect();
    }
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
    ClientLimits limits = loginContext.limits();

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
                          logger.info(
                              "Authentication failed for {} from {}: {}",
                              playerName,
                              connection.remoteAddress(),
                              e.getMessage());
                          disconnect("Authentication failed");
                        });
              } finally {
                limits.endAuthentication();
              }
            });
    return true;
  }

  private void completeLogin(GameProfile profile) {
    this.authenticatedProfile = profile;
    // Logged in: the login gives its place back, and no longer times out.
    endOfLogin();

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
    connection.writeAndFlush(loginSuccess);

    logger.info(
        "Player {} ({}) logged in from {}",
        profile.name(),
        profile.uuid(),
        connection.remoteAddress());

    if (clientVersion().isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
      // Wait for LoginAcknowledged before transitioning.
      state = LoginState.AWAITING_LOGIN_ACKNOWLEDGED;
    } else {
      // Pre-1.20.2: transition directly to PLAY.
      state = LoginState.COMPLETE;
      connection.setState(ProtocolState.PLAY);
      initiateBackendConnection(profile);
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

    // Outbound: cipher must sit before frame-encoder in pipeline order, so Netty's
    // tail-to-head outbound traversal hits frame-encoder first, then cipher-encoder.
    pipeline.addBefore(
        ServerChannelInitializer.FRAME_ENCODER,
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
   * Creates the {@link ConnectedPlayer} and connects it to the default server.
   *
   * <p>The backend connection runs asynchronously. A default server that cannot be reached or
   * refuses the player sends it down the fallback order, whatever its version.
   */
  private void initiateBackendConnection(GameProfile profile) {
    InetSocketAddress remoteAddr = (InetSocketAddress) connection.remoteAddress();
    ConnectedPlayer joining =
        new ConnectedPlayer(connection, clientVersion(), profile, remoteAddr, loginContext);
    this.player = joining;
    joining.join();
  }

  // ---------------------------------------------------------------------------
  // Limits
  // ---------------------------------------------------------------------------

  /** Releases the login's place among its address's logins in progress, and its timeout. */
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
  }

  private void timedOut() {
    this.timeoutTask = null;
    if (!connection.channel().isActive()) {
      return;
    }
    loginContext.limits().loginTimedOut();
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
    logger.warn(
        "Disconnecting {} from {}: invalid profile public key, {}",
        username != null ? username : "unknown",
        connection.remoteAddress(),
        problem.description());
    connection.writeAndClose(LoginDisconnect.ofTranslation(ProfileKeys.REFUSAL_REASON));
  }

  private void disconnect(String reason) {
    logger.warn(
        "Disconnecting {} from {}: {}",
        username != null ? username : "unknown",
        connection.remoteAddress(),
        reason);
    // The disconnect packet of the client's current state: LoginAcknowledged moves it to
    // CONFIGURATION, completeLogin (before 1.20.2) to PLAY. The login reason is JSON in every
    // version; the configuration and play reasons follow the client's version.
    Packet disconnectPacket =
        switch (connection.decoder().state()) {
          case HANDSHAKE, STATUS, LOGIN -> LoginDisconnect.ofPlainText(reason);
          case CONFIGURATION -> ConfigDisconnect.ofPlainText(reason, clientVersion());
          case PLAY -> PlayDisconnect.ofPlainText(reason, clientVersion());
        };
    connection.writeAndClose(disconnectPacket);
  }
}
