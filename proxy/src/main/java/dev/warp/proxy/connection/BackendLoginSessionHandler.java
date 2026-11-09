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
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.cookie.CookieRequest;
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
import dev.warp.proxy.bedrock.BedrockIdentity;
import dev.warp.proxy.bedrock.FloodgateService;
import dev.warp.proxy.logging.LogCode;

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
 * login sequence: optional SetCompression, optional LoginPluginRequest (Velocity forwarding,
 * answered here; anything else relayed to a client held in its login state for it, see {@link
 * LoginQueryRelay}, or refused), optional Cookie Requests (1.20.5+, relayed to the client, whose
 * answers come back), LoginSuccess, and LoginAcknowledged.
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
  private final ServerInfo server;
  private final InetSocketAddress serverAddress;
  private final byte[] forwardingSecret;

  /** Whether the backend's refusal was reported: a kick is followed by the connection closing. */
  private boolean refused;

  BackendLoginSessionHandler(
      ConnectedPlayer player,
      MinecraftConnection backendConnection,
      ServerInfo server,
      byte[] forwardingSecret) {
    this.player = player;
    this.backendConnection = backendConnection;
    this.server = server;
    this.serverAddress = server.address();
    this.forwardingSecret = forwardingSecret;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void activated() {
    // Send Handshake + LoginStart to initiate login with the backend.
    ProtocolVersion version = player.protocolVersion();
    String host = backendHost(player, server, serverAddress.getHostString());
    int port = serverAddress.getPort();

    if (forwardingSecret.length > 0 && backendConnection.isPacketLevel()) {
      // Plugin handlers (a translator) speak the player's version between this handler and the
      // backend: the forwarding query is answered below them, in the backend's own protocol.
      backendConnection
          .channel()
          .pipeline()
          .addAfter(
              ServerChannelInitializer.PACKET_DECOMPRESSOR,
              ForwardingResponder.NAME,
              new ForwardingResponder(
                  () -> PlayerForwarding.buildVelocityForwardingData(player, forwardingSecret)));
    }

    // Temporarily switch to HANDSHAKE to encode the Handshake packet, then back to LOGIN.
    backendConnection.setState(ProtocolState.HANDSHAKE);
    backendConnection.writeAndFlush(new Handshake(version.protocol(), host, port, 2));

    backendConnection.setState(ProtocolState.LOGIN);
    backendConnection.writeAndFlush(new LoginStart(player.username(), player.uuid()));
  }

  /**
   * Builds the host name of the backend's handshake: the address, then what travels with it. A
   * modded client's marker ({@code \0FML2\0}, {@code \0FORGE...}), since a Forge backend treats a
   * player without it as vanilla; or, for a Bedrock player and a backend that runs Floodgate, the
   * player's Floodgate data re-encrypted under a fresh IV, so that Floodgate on the backend sees
   * the player as Geyser presented it (device, language, linked account), with the from-proxy flag
   * set. The Velocity forwarding payload carries the profile either way.
   *
   * @param player the player logging in
   * @param server the backend
   * @param address the backend's host name or address
   * @return the host field of the handshake
   */
  static String backendHost(ConnectedPlayer player, ServerInfo server, String address) {
    BedrockIdentity bedrock = player.clientConnection().bedrock();
    FloodgateService floodgate = player.loginContext().floodgate();
    if (bedrock != null && floodgate.forwardsTo(server.name())) {
      return address + '\0' + floodgate.encodeForBackend(bedrock.data());
    }
    return address + player.clientConnection().handshakeMarker();
  }

  // ---------------------------------------------------------------------------
  // Packet handling
  // ---------------------------------------------------------------------------

  @Override
  public void handle(Packet packet) {
    if (packet instanceof CookieRequest request) {
      // The backend asks during its login, while the client, past its own, waits in
      // configuration: the client is asked in its state, and its answer comes back here.
      player.cookies().relay(backendConnection, request);
      return;
    }
    if (!(packet instanceof LoginPacket loginPacket)) {
      logger.warn(
          LogCode.BACKEND_UNEXPECTED_PACKET.marker(),
          "Unexpected packet in backend LOGIN state: {}",
          packet.getClass().getSimpleName());
      return;
    }

    switch (loginPacket) {
      case SetCompression setCompression -> handleSetCompression(setCompression);
      case LoginPluginRequest pluginRequest -> handleLoginPluginRequest(pluginRequest);
      case LoginSuccess _ -> handleLoginSuccess();
      case LoginDisconnect loginDisconnect -> handleLoginDisconnect(loginDisconnect);
      // We should never receive these from a backend.
      case LoginStart _, EncryptionResponse _, LoginAcknowledged _, LoginPluginResponse _ ->
          backendConnection.close();
      // Backend should not send EncryptionRequest (we connect offline-mode).
      case EncryptionRequest _ -> {
        logger.warn(
            LogCode.BACKEND_ONLINE_MODE.marker(),
            "Backend sent EncryptionRequest: is it running in online-mode?");
        player.disconnect();
      }
    }
  }

  @Override
  public void disconnected() {
    if (refused) {
      return;
    }
    refused = true;
    logger.info("Backend disconnected during login for player {}", player.username());
    player.loginQueries().backendGone(backendConnection);
    player.backendRefusedLogin(backendConnection, server, null);
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

    int playerThreshold = context.compressionThreshold(player.protocolVersion());
    if (context.compressionPassthrough()
        && playerThreshold >= 0
        && threshold != playerThreshold
        && COMPRESSION_ADVICE_GIVEN.add(serverAddress)) {
      logger.warn(
          LogCode.BACKEND_COMPRESSION_MISMATCH.marker(),
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
   * ones: the proxy then has to compress every large packet itself (the single most expensive thing
   * a proxy can do) instead of forwarding the backend's compressed bytes. Not for a 1.7 player:
   * neither side of its connection can compress.
   */
  private void adviseIfBackendUncompressed() {
    ServerLoginContext context = player.loginContext();
    int playerThreshold = context.compressionThreshold(player.protocolVersion());
    if (context.compressionPassthrough()
        && playerThreshold >= 0
        && !backendConnection.isCompressed()
        && COMPRESSION_ADVICE_GIVEN.add(serverAddress)) {
      logger.warn(
          LogCode.BACKEND_COMPRESSION_OFF.marker(),
          "Backend {} has compression disabled, so Warp must compress its traffic for every player."
              + " Set network-compression-threshold={} on the backend: Warp then forwards its"
              + " compressed packets without recompressing them.",
          serverAddress,
          playerThreshold);
    }
  }

  private void handleLoginPluginRequest(LoginPluginRequest request) {
    if (PlayerForwarding.VELOCITY_CHANNEL.equals(request.channel())
        && forwardingSecret.length > 0) {
      // Velocity modern forwarding — build and sign the forwarding payload.
      byte[] payload = PlayerForwarding.buildVelocityForwardingData(player, forwardingSecret);
      backendConnection.writeAndFlush(new LoginPluginResponse(request.messageId(), true, payload));
    } else if (!player.loginQueries().relay(backendConnection, request)) {
      // Nobody can answer: the client is past its login (a vanilla client, or a server switch), or
      // this login is one the switch abandoned for a later connect. "Not understood", as a vanilla
      // client would say. A replay of the first login's answers on a switch is a later milestone.
      backendConnection.writeAndFlush(new LoginPluginResponse(request.messageId(), false, null));
    }
  }

  private void handleLoginSuccess() {
    adviseIfBackendUncompressed();
    // A client held in its login state for the backend's queries gets its own Login Success now,
    // before anything of the play state reaches it.
    player.loginQueries().backendLoggedIn(backendConnection);
    // The configuration phase from 1.20.2, straight to PLAY before: the server switch strategy
    // knows which, and installs the next handler.
    player.backendAccepted(server);
    player.serverSwitch().loggedIn(backendConnection);
  }

  private void handleLoginDisconnect(LoginDisconnect packet) {
    player.loginQueries().backendGone(backendConnection);
    logger.info(
        "Backend {} rejected login for player {}: {}",
        serverAddress,
        player.username(),
        packet.reason());
    refused = true;
    player.backendRefusedLogin(backendConnection, server, packet.decodedReason());
  }
}
