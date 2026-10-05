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
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.netty.CipherDecoder;
import dev.warp.protocol.netty.CipherEncoder;
import dev.warp.protocol.netty.CompressionDecoder;
import dev.warp.protocol.netty.CompressionEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.EncryptionResponse;
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
import dev.warp.proxy.auth.ServerHash;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.netty.channel.ChannelPipeline;
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

  // ---------------------------------------------------------------------------
  // State machine
  // ---------------------------------------------------------------------------

  private enum LoginState {
    AWAITING_LOGIN_START,
    AWAITING_ENCRYPTION_RESPONSE,
    AUTHENTICATING,
    AWAITING_LOGIN_ACKNOWLEDGED,
    COMPLETE
  }

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  private final MinecraftConnection connection;
  private final ServerLoginContext loginContext;

  private LoginState state = LoginState.AWAITING_LOGIN_START;
  private @Nullable String username;
  private byte @Nullable [] verifyToken;
  private @Nullable GameProfile authenticatedProfile;

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
  public void handle(Packet packet) {
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
      case LoginAcknowledged ack -> handleLoginAcknowledged();
      case LoginPluginResponse response -> handleLoginPluginResponse(response);
      // Clientbound packets must never arrive from a client.
      case EncryptionRequest ignored -> connection.close();
      case LoginSuccess ignored -> connection.close();
      case SetCompression ignored -> connection.close();
      case LoginDisconnect ignored -> connection.close();
      case LoginPluginRequest ignored -> connection.close();
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

    if (loginContext.onlineMode()) {
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

    try {
      // Decrypt the verify token and shared secret with the RSA private key.
      Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      rsaCipher.init(Cipher.DECRYPT_MODE, loginContext.rsaKeyPair().getPrivate());

      byte[] decryptedVerifyToken = rsaCipher.doFinal(packet.verifyToken());
      byte[] decryptedSharedSecret = rsaCipher.doFinal(packet.sharedSecret());

      // Validate the verify token. The 1.19–1.19.2 alt crypto path sends an empty
      // verify token (handled by EncryptionResponse codec) — skip validation only for
      // that specific version range where the client uses message signatures instead.
      boolean altCryptoPath =
          clientVersion().isAtLeast(ProtocolVersion.MINECRAFT_1_19)
              && clientVersion().isOlderThan(ProtocolVersion.MINECRAFT_1_19_3)
              && packet.verifyToken().length == 0;

      if (!altCryptoPath
          && (verifyToken == null || !MessageDigest.isEqual(verifyToken, decryptedVerifyToken))) {
        disconnect("Verify token mismatch");
        return;
      }

      // Validate shared secret length.
      if (decryptedSharedSecret.length != SHARED_SECRET_LENGTH) {
        disconnect("Invalid shared secret length");
        return;
      }

      // Install cipher on the pipeline BEFORE async auth — secures the channel immediately.
      SecretKey sharedSecret = new SecretKeySpec(decryptedSharedSecret, "AES");
      enableEncryption(sharedSecret);

      // Authenticate with Mojang asynchronously on a virtual thread.
      authenticateAsync(decryptedSharedSecret);

    } catch (GeneralSecurityException e) {
      logger.warn(
          "Encryption error for {} from {}", username, connection.channel().remoteAddress(), e);
      disconnect("Encryption error");
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
  }

  @Override
  public void disconnected() {
    logger.info("Client disconnected during login: {} (state={})", username, state);
  }

  @SuppressWarnings("unused")
  private void handleLoginPluginResponse(LoginPluginResponse response) {
    // LoginPluginResponse from the client during proxy login — currently unused.
    // Will be used in the future for Warp-specific login channels.
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

  private void authenticateAsync(byte[] sharedSecret) {
    String playerName = this.username;
    if (playerName == null) {
      disconnect("Internal error");
      return;
    }

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
                              connection.channel().remoteAddress(),
                              e.getMessage());
                          disconnect("Authentication failed");
                        });
              }
            });
  }

  private void completeLogin(GameProfile profile) {
    this.authenticatedProfile = profile;

    // Enable compression (must send SetCompression BEFORE installing handlers).
    enableCompression();

    boolean strictErrorHandling = clientVersion().isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);
    LoginSuccess loginSuccess =
        new LoginSuccess(profile.uuid(), profile.name(), profile.properties(), strictErrorHandling);
    connection.writeAndFlush(loginSuccess);

    logger.info(
        "Player {} ({}) logged in from {}",
        profile.name(),
        profile.uuid(),
        connection.channel().remoteAddress());

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
    int threshold = loginContext.compressionThreshold();
    if (threshold < 0) {
      return; // Compression disabled.
    }

    // Send SetCompression BEFORE installing compression handlers — this packet is uncompressed.
    connection.writeAndFlush(new SetCompression(threshold));

    int level = loginContext.compressionLevel();
    ChannelPipeline pipeline = connection.channel().pipeline();

    // Inbound: decompression between frame-decoder and minecraft-decoder.
    pipeline.addBefore(
        ServerChannelInitializer.MINECRAFT_DECODER,
        ServerChannelInitializer.COMPRESSION_DECODER,
        new CompressionDecoder(threshold, new JavaCompressor(level)));

    // Outbound: compression-encoder replaces frame-encoder (combines compression + framing).
    pipeline.replace(
        ServerChannelInitializer.FRAME_ENCODER,
        ServerChannelInitializer.COMPRESSION_ENCODER,
        new CompressionEncoder(threshold, new JavaCompressor(level)));
  }

  // ---------------------------------------------------------------------------
  // Backend connection
  // ---------------------------------------------------------------------------

  /**
   * Creates a {@link ConnectedPlayer} and initiates a connection to the backend server.
   *
   * <p>The backend connection runs asynchronously. On success, the player is linked to the backend
   * and KeepAlive starts. On failure, the client is disconnected with an error message.
   */
  private void initiateBackendConnection(GameProfile profile) {
    InetSocketAddress remoteAddr = (InetSocketAddress) connection.channel().remoteAddress();
    ConnectedPlayer player =
        new ConnectedPlayer(connection, clientVersion(), profile, remoteAddr, loginContext);

    var defaultServer = loginContext.serverRegistry().defaultServer();
    logger.info("Connecting {} to server '{}'", profile.name(), defaultServer.name());

    var _ =
        BackendConnection.connect(
                loginContext.workerGroup(),
                loginContext.channelClass(),
                player,
                defaultServer.address(),
                loginContext.forwardingSecret())
            .whenComplete(
                (backend, ex) ->
                    connection
                        .channel()
                        .eventLoop()
                        .execute(
                            () -> {
                              if (!connection.channel().isActive()) {
                                // Client disconnected while we were connecting to backend.
                                if (backend != null) {
                                  backend.disconnect();
                                }
                                return;
                              }
                              if (ex != null) {
                                logger.error(
                                    "Failed to connect {} to server '{}'",
                                    profile.name(),
                                    defaultServer.name(),
                                    ex);
                                disconnect("Could not connect to backend server");
                                return;
                              }
                              player.setBackendConnection(backend);
                              player.setCurrentServerName(defaultServer.name());
                            }));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ProtocolVersion clientVersion() {
    return connection.decoder().version();
  }

  private void disconnect(String reason) {
    logger.warn(
        "Disconnecting {} from {}: {}",
        username != null ? username : "unknown",
        connection.channel().remoteAddress(),
        reason);
    byte[] rawReason = encodeTextComponent(reason, clientVersion());
    // Use the correct disconnect packet for the current protocol state.
    // After handleLoginAcknowledged, the decoder is in CONFIGURATION.
    // After completeLogin (pre-1.20.2), the decoder is in PLAY.
    // LoginDisconnect only encodes in LOGIN state.
    Packet disconnectPacket =
        switch (connection.decoder().state()) {
          case HANDSHAKE, STATUS, LOGIN -> new LoginDisconnect(rawReason);
          case CONFIGURATION -> new ConfigDisconnect(rawReason);
          case PLAY -> new PlayDisconnect(rawReason);
        };
    connection.writeAndClose(disconnectPacket);
  }

  /**
   * Encodes a plain text string as a Minecraft text component.
   *
   * <p>Pre-1.20.3: JSON {@code {"text":"reason"}}. Post-1.20.3: NBT string tag (TAG_String with the
   * JSON text). The NBT encoding for a plain string is: {@code 0x08} (TAG_String) + 2-byte
   * big-endian name length (0) + 2-byte big-endian value length + UTF-8 bytes.
   *
   * <p>Note: LoginDisconnect in the LOGIN state uses JSON for all versions. NBT is only used for
   * PlayDisconnect in PLAY state post-1.20.3. During LOGIN, JSON is always correct.
   */
  @SuppressWarnings("UnusedVariable") // version reserved for PLAY-state NBT disconnect format
  private static byte[] encodeTextComponent(String reason, ProtocolVersion version) {
    // During the LOGIN state, the disconnect reason is always JSON-encoded across all versions.
    // The NBT encoding applies only to PLAY-state disconnects (1.20.3+).
    String escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"");
    return ("{\"text\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
  }
}
