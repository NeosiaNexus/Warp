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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.auth.ProfileKeys;
import dev.warp.proxy.bedrock.FloodgateService;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.config.DuplicateLoginPolicy;
import dev.warp.proxy.config.ForwardingMode;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.config.TimeoutsConfig;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.i18n.PlayerMessages;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.metrics.ProxyMetrics;
import dev.warp.proxy.permission.PermissionRegistry;
import dev.warp.proxy.pipeline.PipelineRegistry;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.ServerRegistry;
import dev.warp.proxy.status.ServerList;

import java.security.KeyPair;

import io.netty.channel.Channel;

/**
 * Server-wide configuration and state shared across all client connections.
 *
 * <p>Created once during server startup and injected into {@link ServerChannelInitializer}. Using a
 * record rather than injecting {@link dev.warp.proxy.WarpServer WarpServer} directly keeps session
 * handlers testable and avoids coupling to the server lifecycle.
 *
 * @param rsaKeyPair the RSA keypair for encryption handshake (generated once at server start)
 * @param onlineMode whether to authenticate players with Mojang
 * @param compressionThreshold the compression threshold in bytes, or {@code -1} to disable; see
 *     {@link #compressionThreshold(ProtocolVersion)} for the one a given client plays with
 * @param compressionLevel the zlib compression level (0–9 or {@code -1} for default)
 * @param compressionPassthrough whether uninspected packets keep their original compressed form
 * @param sessionService the Mojang session service for online-mode authentication
 * @param profileKeys the checker of the profile public keys 1.19 to 1.19.2 clients send, used in
 *     online mode
 * @param playSession the play session of the proxy, whose ID 26.2+ clients receive on login
 * @param serverRegistry the registry of backend servers
 * @param playerRegistry the registry of connected players
 * @param serverList the proxy's server list entry, whose settings also hold the player limit
 * @param duplicateLoginPolicy what happens when a player logs in while a session with its UUID is
 *     still connected
 * @param events the event bus plugins subscribe to
 * @param channels the plugin message channels plugins own
 * @param commands the proxy's commands
 * @param permissions the permission providers plugins registered
 * @param forwardingMode the player info forwarding mode
 * @param forwardingSecret the shared HMAC secret for Velocity modern forwarding
 * @param channelClass the socket channel class matching the detected transport
 * @param acceptsTransfers whether players a Transfer packet sends here (handshake intent 3,
 *     1.20.5+) may log in; refused with vanilla's reason otherwise
 * @param limits the limits on what one client address may do, applied to every connection
 * @param timeouts how long backend connections may take to open, and logged-in connections may stay
 *     silent
 * @param logging which routine events get a log line each
 * @param tcpFastOpen whether connections to backends request TCP Fast Open: only when configured
 *     and the transport supports it
 * @param antiBot the anti-bot: attack detection, trusted identities, lockdown, plugin checks
 * @param pipelines the pipeline initializers plugins registered
 * @param messages what the proxy tells players, rendered in each player's language
 * @param metrics the proxy's metrics, which the handlers update
 * @param floodgate reads and verifies the Floodgate data of Bedrock players sent through Geyser,
 *     and re-emits it to backends running Floodgate
 */
@SuppressWarnings("ArrayRecordComponent") // forwardingSecret is treated as immutable
public record ServerLoginContext(
    KeyPair rsaKeyPair,
    boolean onlineMode,
    int compressionThreshold,
    int compressionLevel,
    boolean compressionPassthrough,
    MojangSessionService sessionService,
    ProfileKeys profileKeys,
    PlaySession playSession,
    ServerRegistry serverRegistry,
    PlayerRegistry playerRegistry,
    ServerList serverList,
    DuplicateLoginPolicy duplicateLoginPolicy,
    WarpEventBus events,
    ChannelRegistry channels,
    WarpCommandManager commands,
    PermissionRegistry permissions,
    ForwardingMode forwardingMode,
    byte[] forwardingSecret,
    Class<? extends Channel> channelClass,
    boolean acceptsTransfers,
    ClientLimits limits,
    TimeoutsConfig timeouts,
    LoggingConfig logging,
    boolean tcpFastOpen,
    AntiBotService antiBot,
    PipelineRegistry pipelines,
    PlayerMessages messages,
    ProxyMetrics metrics,
    FloodgateService floodgate) {

  /** Validates configuration invariants. */
  public ServerLoginContext {
    if (compressionThreshold < -1) {
      throw new IllegalArgumentException(
          "compressionThreshold must be >= -1, got " + compressionThreshold);
    }
    if (compressionLevel < -1 || compressionLevel > 9) {
      throw new IllegalArgumentException(
          "compressionLevel must be -1 to 9, got " + compressionLevel);
    }
  }

  /**
   * Returns the compression threshold a client of {@code version} plays with: the configured one
   * from 1.8, {@code -1} before. Compression and its Set Compression packet appeared in 1.8; a 1.7
   * client has no such packet and reads every frame as uncompressed, as Velocity's {@code
   * AuthSessionHandler} assumes when it skips compression below 1.8.
   *
   * @param version the client's protocol version
   * @return the threshold in bytes, or {@code -1} when the client's packets are never compressed
   */
  public int compressionThreshold(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_8) ? compressionThreshold : -1;
  }
}
