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
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.handshake.Handshake;
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

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the backend login dance from the proxy's perspective (proxy acts as a client).
 *
 * <p>The proxy connects to the backend, sends Handshake + LoginStart, and handles the backend's
 * login sequence: optional SetCompression, optional LoginPluginRequest (Velocity forwarding),
 * LoginSuccess, and LoginAcknowledged.
 *
 * <p>This handler has no explicit state machine with crossed futures — it simply reacts to packets
 * as they arrive. The protocol guarantees ordering (SetCompression before LoginSuccess,
 * LoginPluginRequest before LoginSuccess), so no state tracking is needed.
 */
final class BackendLoginSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(BackendLoginSessionHandler.class);

  /**
   * Backends already warned about compression settings that defeat passthrough — logged once per
   * address for the lifetime of the proxy, not once per login.
   */
  private static final Set<InetSocketAddress> COMPRESSION_ADVICE_GIVEN =
      ConcurrentHashMap.newKeySet();

  private final ConnectedPlayer player;
  private final MinecraftConnection backendConnection;
  private final InetSocketAddress serverAddress;
  private final byte[] forwardingSecret;

  BackendLoginSessionHandler(
      ConnectedPlayer player,
      MinecraftConnection backendConnection,
      InetSocketAddress serverAddress,
      byte[] forwardingSecret) {
    this.player = player;
    this.backendConnection = backendConnection;
    this.serverAddress = serverAddress;
    this.forwardingSecret = forwardingSecret;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void activated() {
    // Send Handshake + LoginStart to initiate login with the backend.
    ProtocolVersion version = player.protocolVersion();
    String host = serverAddress.getHostString();
    int port = serverAddress.getPort();

    // Temporarily switch to HANDSHAKE to encode the Handshake packet, then back to LOGIN.
    backendConnection.setState(ProtocolState.HANDSHAKE);
    backendConnection.writeAndFlush(new Handshake(version.protocol(), host, port, 2));

    backendConnection.setState(ProtocolState.LOGIN);
    backendConnection.writeAndFlush(new LoginStart(player.username(), player.uuid()));
  }

  // ---------------------------------------------------------------------------
  // Packet handling
  // ---------------------------------------------------------------------------

  @Override
  public void handle(Packet packet) {
    if (!(packet instanceof LoginPacket loginPacket)) {
      logger.warn(
          "Unexpected packet in backend LOGIN state: {}", packet.getClass().getSimpleName());
      return;
    }

    switch (loginPacket) {
      case SetCompression setCompression -> handleSetCompression(setCompression);
      case LoginPluginRequest pluginRequest -> handleLoginPluginRequest(pluginRequest);
      case LoginSuccess loginSuccess -> handleLoginSuccess(loginSuccess);
      case LoginDisconnect loginDisconnect -> handleLoginDisconnect(loginDisconnect);
      // We should never receive these from a backend.
      case LoginStart ignored -> backendConnection.close();
      case EncryptionResponse ignored -> backendConnection.close();
      case LoginAcknowledged ignored -> backendConnection.close();
      case LoginPluginResponse ignored -> backendConnection.close();
      // Backend should not send EncryptionRequest (we connect offline-mode).
      case EncryptionRequest ignored -> {
        logger.warn("Backend sent EncryptionRequest — is it running in online-mode?");
        player.disconnect();
      }
    }
  }

  @Override
  public void disconnected() {
    logger.info("Backend disconnected during login for player {}", player.username());
    player.scheduleBackendFailure();
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  private void handleSetCompression(SetCompression packet) {
    int threshold = packet.threshold();
    if (threshold < 0) {
      return; // vanilla never sends a negative threshold; treat it as "stay uncompressed"
    }
    ServerLoginContext context = player.loginContext();
    backendConnection.enableCompression(
        threshold, Deflater.DEFAULT_COMPRESSION, true, context.compressionPassthrough());

    int playerThreshold = context.compressionThreshold();
    if (context.compressionPassthrough()
        && playerThreshold >= 0
        && threshold != playerThreshold
        && COMPRESSION_ADVICE_GIVEN.add(serverAddress)) {
      logger.warn(
          "Backend {} compresses packets from {} bytes but players use {}: packets between the two"
              + " thresholds are re-encoded instead of forwarded as-is. Set"
              + " network-compression-threshold={} on the backend for full compression"
              + " passthrough.",
          serverAddress,
          threshold,
          playerThreshold,
          playerThreshold);
    }
  }

  /**
   * Warns once per backend when it sends uncompressed packets to players who receive compressed
   * ones: the proxy then has to compress every large packet itself — the single most expensive
   * thing a proxy can do — instead of forwarding the backend's compressed bytes.
   */
  private void adviseIfBackendUncompressed() {
    ServerLoginContext context = player.loginContext();
    if (context.compressionPassthrough()
        && context.compressionThreshold() >= 0
        && backendConnection.decoder().decompressor() == null
        && COMPRESSION_ADVICE_GIVEN.add(serverAddress)) {
      logger.warn(
          "Backend {} has compression disabled, so Warp must compress its traffic for every player."
              + " Set network-compression-threshold={} on the backend: Warp then forwards its"
              + " compressed packets without recompressing them.",
          serverAddress,
          context.compressionThreshold());
    }
  }

  private void handleLoginPluginRequest(LoginPluginRequest request) {
    if (PlayerForwarding.VELOCITY_CHANNEL.equals(request.channel())
        && forwardingSecret.length > 0) {
      // Velocity modern forwarding — build and sign the forwarding payload.
      byte[] payload = PlayerForwarding.buildVelocityForwardingData(player, forwardingSecret);
      backendConnection.writeAndFlush(new LoginPluginResponse(request.messageId(), true, payload));
    } else {
      // Unknown plugin channel or forwarding disabled — respond with failure.
      backendConnection.writeAndFlush(new LoginPluginResponse(request.messageId(), false, null));
    }
  }

  @SuppressWarnings("UnusedVariable")
  private void handleLoginSuccess(LoginSuccess loginSuccess) {
    ProtocolVersion version = player.protocolVersion();
    adviseIfBackendUncompressed();

    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
      // Send LoginAcknowledged and transition to CONFIGURATION.
      backendConnection.writeAndFlush(new LoginAcknowledged());
      backendConnection.setState(ProtocolState.CONFIGURATION);
      backendConnection.setSessionHandler(
          new BackendConfigSessionHandler(player, backendConnection));
    } else {
      // Pre-1.20.2: transition directly to PLAY.
      backendConnection.setState(ProtocolState.PLAY);
      transitionToPlay();
    }
  }

  private void handleLoginDisconnect(LoginDisconnect packet) {
    logger.info(
        "Backend {} rejected login for player {}: {}",
        serverAddress,
        player.username(),
        packet.reason());
    player.scheduleBackendFailure();
  }

  // ---------------------------------------------------------------------------
  // Transition
  // ---------------------------------------------------------------------------

  private void transitionToPlay() {
    // Install backend play handler (we are on the backend event loop).
    backendConnection.setSessionHandler(new BackendPlaySessionHandler(player, backendConnection));

    // Install client play handler on the client's event loop.
    MinecraftConnection clientConn = player.clientConnection();
    clientConn
        .channel()
        .eventLoop()
        .execute(
            () -> {
              if (!clientConn.channel().isActive()) {
                return;
              }
              clientConn.setSessionHandler(new ClientPlaySessionHandler(player));
            });
  }
}
