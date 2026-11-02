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
 * Builds the Netty pipeline for each incoming client connection.
 *
 * <p>Pipeline layout (inbound top → bottom, outbound bottom → top):
 *
 * <pre>{@code
 * Inbound:  [read-timeout] → [frame-decoder] → [minecraft-decoder] → [connection-handler]
 * Outbound: [connection-handler] → [minecraft-encoder] → [frame-encoder]
 * }</pre>
 *
 * <p>In pipeline order, each encoder sits right after the decoder of its layer: {@code
 * read-timeout, frame-decoder, frame-encoder, minecraft-decoder, minecraft-encoder,
 * connection-handler}. A duplex handler placed between two layers then sees the same format both
 * ways, which is what makes the two positions of the plugin pipeline API ({@link
 * dev.warp.api.network.PipelinePosition}) possible:
 *
 * <pre>{@code
 * read-timeout
 * cipher-decoder, cipher-encoder      (online mode, inserted during login)
 * frame-decoder
 *   <plugin FRAME handlers>           frames, as on the wire
 * frame-encoder | compression-encoder
 * packet-decompressor                 (only once a plugin added a PACKET handler)
 *   <plugin PACKET handlers>          bare packets, decompressed
 * minecraft-decoder
 * minecraft-encoder
 * connection-handler
 * }</pre>
 *
 * <p>Encryption handlers are inserted during login. Compression needs no extra inbound handler: the
 * {@link MinecraftDecoder} decompresses only the frames it inspects (see {@link
 * MinecraftConnection#enableCompression}), and the compression encoder replaces the frame encoder
 * in place. A connection with plugin packet handlers is the exception: its {@code
 * packet-decompressor} unframes and inflates every frame (see {@link
 * MinecraftConnection#usePacketLevel()}).
 *
 * <h3>Handler naming</h3>
 *
 * <p>All handler names are constants in this class to ensure type-safe pipeline manipulation
 * elsewhere. Names use lowercase-kebab convention consistent with Netty community practice.
 */
public final class ServerChannelInitializer extends ChannelInitializer<Channel> {

  // ---------------------------------------------------------------------------
  // Pipeline handler names
  // ---------------------------------------------------------------------------

  /** Read timeout — closes idle connections. */
  public static final String READ_TIMEOUT = "read-timeout";

  /** Cipher decoder — AES/CFB8 stream decryption, installed during login. */
  public static final String CIPHER_DECODER = "cipher-decoder";

  /** Cipher encoder — AES/CFB8 stream encryption, installed during login. */
  public static final String CIPHER_ENCODER = "cipher-encoder";

  /** Frame decoder — splits TCP stream into VarInt-length-prefixed frames. */
  public static final String FRAME_DECODER = "frame-decoder";

  /** Frame encoder — prepends VarInt length prefix to outgoing frames. */
  public static final String FRAME_ENCODER = "frame-encoder";

  /** Compression encoder — compresses and frames outgoing data, replaces frame-encoder. */
  public static final String COMPRESSION_ENCODER = "compression-encoder";

  /**
   * Packet decompressor: unframes and inflates every frame, only on connections with plugin packet
   * handlers.
   */
  public static final String PACKET_DECOMPRESSOR = "packet-decompressor";

  /** Minecraft decoder — deserializes frames into typed packets or blind-forwards raw buffers. */
  public static final String MINECRAFT_DECODER = "minecraft-decoder";

  /** Minecraft encoder — serializes typed packets into wire format. */
  public static final String MINECRAFT_ENCODER = "minecraft-encoder";

  /**
   * Connection handler — dispatches decoded packets to the active {@link
   * dev.warp.protocol.netty.SessionHandler}.
   */
  public static final String CONNECTION_HANDLER = "connection-handler";

  private final ServerLoginContext loginContext;

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  /**
   * Creates a new server channel initializer.
   *
   * @param loginContext the server-wide login configuration
   */
  public ServerChannelInitializer(ServerLoginContext loginContext) {
    this.loginContext = loginContext;
  }

  // ---------------------------------------------------------------------------
  // Channel initialisation
  // ---------------------------------------------------------------------------

  @Override
  protected void initChannel(Channel ch) {
    // Every new connection starts in HANDSHAKE state with an unknown version.
    // The HandshakeSessionHandler will set the real version after decoding the
    // Handshake packet.
    MinecraftDecoder decoder =
        new MinecraftDecoder(
            PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_7_2, ProtocolState.HANDSHAKE);
    MinecraftEncoder encoder =
        new MinecraftEncoder(
            PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_7_2, ProtocolState.HANDSHAKE);

    MinecraftConnection connection = new MinecraftConnection(ch);

    ch.pipeline()
        // Inbound: timeout → frame split → packet decode → handler
        .addLast(
            READ_TIMEOUT,
            new ReadTimeoutHandler(MinecraftConnection.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        // Each layer's encoder right after its decoder (see the class documentation).
        .addLast(FRAME_DECODER, new FrameDecoder())
        .addLast(FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(MINECRAFT_DECODER, decoder)
        .addLast(MINECRAFT_ENCODER, encoder)
        // Tail: inbound handler that dispatches to SessionHandler
        .addLast(CONNECTION_HANDLER, connection);

    // Start with the handshake handler.
    connection.setSessionHandler(new HandshakeSessionHandler(connection, loginContext));
  }
}
