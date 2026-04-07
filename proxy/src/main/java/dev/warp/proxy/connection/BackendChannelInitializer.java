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
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.PacketDirection;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.timeout.ReadTimeoutHandler;

/**
 * Builds the Netty pipeline for outbound proxy-to-backend connections.
 *
 * <p>Symmetric to {@link ServerChannelInitializer} but with reversed packet directions: the decoder
 * reads CLIENTBOUND packets (responses from the backend) and the encoder writes SERVERBOUND packets
 * (requests to the backend).
 *
 * <p>The pipeline starts in LOGIN state with the client's negotiated protocol version (not 1.7.2,
 * since the backend must use the same version the client connected with).
 *
 * <p>Compression and encryption handlers are inserted dynamically during the backend login
 * sequence. Encryption is not needed (backends run offline-mode with forwarding).
 */
final class BackendChannelInitializer extends ChannelInitializer<Channel> {

  /** Read timeout for backend connections. */
  private static final int READ_TIMEOUT_SECONDS = 30;

  private final ConnectedPlayer player;
  private final InetSocketAddress serverAddress;

  BackendChannelInitializer(ConnectedPlayer player, InetSocketAddress serverAddress) {
    this.player = player;
    this.serverAddress = serverAddress;
  }

  @Override
  protected void initChannel(Channel ch) {
    ProtocolVersion version = player.protocolVersion();

    // Decoder reads CLIENTBOUND packets (backend sends responses to proxy).
    MinecraftDecoder decoder =
        new MinecraftDecoder(PacketDirection.CLIENTBOUND, version, ProtocolState.LOGIN);

    // Encoder writes SERVERBOUND packets (proxy sends requests to backend).
    MinecraftEncoder encoder =
        new MinecraftEncoder(PacketDirection.SERVERBOUND, version, ProtocolState.LOGIN);

    MinecraftConnection connection = new MinecraftConnection(ch);

    ch.pipeline()
        .addLast(
            ServerChannelInitializer.READ_TIMEOUT,
            new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(ServerChannelInitializer.MINECRAFT_DECODER, decoder)
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(ServerChannelInitializer.MINECRAFT_ENCODER, encoder)
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);

    connection.setSessionHandler(new BackendLoginSessionHandler(player, connection, serverAddress));
  }
}
