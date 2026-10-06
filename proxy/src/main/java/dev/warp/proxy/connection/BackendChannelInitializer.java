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
 * <p>Compression is switched on during the backend login sequence ({@link
 * MinecraftConnection#enableCompression}). Encryption is not needed (backends run offline-mode with
 * forwarding).
 */
final class BackendChannelInitializer extends ChannelInitializer<Channel> {

  private final ConnectedPlayer player;

  BackendChannelInitializer(ConnectedPlayer player) {
    this.player = player;
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
            new ReadTimeoutHandler(MinecraftConnection.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(ServerChannelInitializer.MINECRAFT_DECODER, decoder)
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(ServerChannelInitializer.MINECRAFT_ENCODER, encoder)
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);

    // Do NOT set session handler here — the channel is not yet connected.
    // BackendConnection.connect() will set it after TCP connect succeeds.
  }
}
