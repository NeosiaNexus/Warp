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
import dev.warp.protocol.legacy.LegacyDisconnectEncoder;
import dev.warp.protocol.legacy.LegacyPingDecoder;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.FrameLimits;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.proxy.config.ProxyProtocolConfig;
import dev.warp.proxy.limit.ClientLimits;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the Netty pipeline for each incoming client connection.
 *
 * <p>Pipeline layout (inbound top → bottom, outbound bottom → top):
 *
 * <pre>{@code
 * Inbound:  [legacy-ping-decoder] → [frame-decoder] → [read-timeout] → [minecraft-decoder]
 *           → [connection-handler]
 * Outbound: [connection-handler] → [minecraft-encoder] → [frame-encoder] → [legacy-ping-encoder]
 * }</pre>
 *
 * <p>The legacy ping handlers answer the server list ping of Minecraft 1.6 and older; on the first
 * bytes of any other connection they remove themselves, so that a session's packets never pass
 * through them.
 *
 * <h3>PROXY protocol</h3>
 *
 * <p>With {@code proxy-protocol} enabled, a connection from a peer outside the trusted proxies is
 * reset before any handler is installed, and the others start with {@code [proxy-protocol-decoder]
 * → [proxy-protocol]}, which apply the header and remove themselves (see {@link
 * ProxyProtocolHandler}).
 *
 * <h3>Connection limit</h3>
 *
 * <p>Every new connection counts against its client address's connection limit ({@link
 * dev.warp.proxy.limit.ClientLimits}) before anything is read from it: here, from the socket's
 * peer, or, behind the PROXY protocol, from the address of the header once it is applied. A
 * connection over the limit is reset with no handler installed. Before that, the connection takes a
 * place among the connections not logged in, for the whole proxy: over that cap, it is reset too. A
 * {@link ConnectionGuard} gives the place back once the client is logged in or gone, and holds the
 * connection to the deadlines of the states before login.
 *
 * <h3>Before login</h3>
 *
 * <p>Until the client is logged in, the frame decoder checks every frame against the {@link
 * FrameLimits} of its state, from its header: a connection that has not logged in never makes the
 * proxy buffer more than the largest packet it may send. The read timeout sits after the frame
 * decoder, so that bytes trickled into a frame never reset it.
 *
 * <p>Encryption handlers are inserted during login. Compression needs no extra inbound handler: the
 * {@link MinecraftDecoder} decompresses only the frames it inspects (see {@link
 * MinecraftConnection#enableCompression}), and the compression encoder replaces the frame encoder.
 *
 * <h3>Handler naming</h3>
 *
 * <p>All handler names are constants in this class to ensure type-safe pipeline manipulation
 * elsewhere. Names use lowercase-kebab convention consistent with Netty community practice.
 */
public final class ServerChannelInitializer extends ChannelInitializer<Channel> {

  private static final Logger logger = LoggerFactory.getLogger(ServerChannelInitializer.class);

  /** The minimum time between two warnings of a kind that peers can trigger at will. */
  private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

  // ---------------------------------------------------------------------------
  // Pipeline handler names
  // ---------------------------------------------------------------------------

  /** PROXY protocol decoder: reads the header, then removes itself. */
  public static final String PROXY_PROTOCOL_DECODER = "proxy-protocol-decoder";

  /** PROXY protocol handler: applies the header's client address, then removes itself. */
  public static final String PROXY_PROTOCOL_HANDLER = "proxy-protocol";

  /** Read timeout — closes idle connections. */
  public static final String READ_TIMEOUT = "read-timeout";

  /** Cipher decoder — AES/CFB8 stream decryption, installed during login. */
  public static final String CIPHER_DECODER = "cipher-decoder";

  /** Cipher encoder — AES/CFB8 stream encryption, installed during login. */
  public static final String CIPHER_ENCODER = "cipher-encoder";

  /** Legacy ping decoder: recognizes the legacy ping and login of 1.6 and older, then leaves. */
  public static final String LEGACY_PING_DECODER = "legacy-ping-decoder";

  /** Legacy ping encoder: writes the legacy kick packet answering them, then leaves. */
  public static final String LEGACY_PING_ENCODER = "legacy-ping-encoder";

  /** Frame decoder — splits TCP stream into VarInt-length-prefixed frames. */
  public static final String FRAME_DECODER = "frame-decoder";

  /** Frame encoder — prepends VarInt length prefix to outgoing frames. */
  public static final String FRAME_ENCODER = "frame-encoder";

  /** Compression encoder — compresses and frames outgoing data, replaces frame-encoder. */
  public static final String COMPRESSION_ENCODER = "compression-encoder";

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
  private final ProxyProtocolConfig proxyProtocol;

  /** Warnings about peers outside the trusted proxies, shared by every connection. */
  private final LogThrottle untrustedPeerWarnings;

  /** Warnings about trusted proxies that send no valid header, shared by every connection. */
  private final LogThrottle proxyHeaderWarnings;

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  /**
   * Creates an initializer for clients that connect directly, without the PROXY protocol.
   *
   * @param loginContext the server-wide login configuration
   */
  public ServerChannelInitializer(ServerLoginContext loginContext) {
    this(loginContext, ProxyProtocolConfig.DISABLED);
  }

  /**
   * Creates a new server channel initializer.
   *
   * @param loginContext the server-wide login configuration
   * @param proxyProtocol whether connections start with a PROXY protocol header, and from which
   *     peers
   */
  public ServerChannelInitializer(
      ServerLoginContext loginContext, ProxyProtocolConfig proxyProtocol) {
    this(loginContext, proxyProtocol, System::nanoTime);
  }

  /**
   * Creates a new server channel initializer whose warnings are throttled on {@code nanoClock}.
   *
   * @param loginContext the server-wide login configuration
   * @param proxyProtocol whether connections start with a PROXY protocol header, and from which
   *     peers
   * @param nanoClock a monotonic clock in nanoseconds
   */
  ServerChannelInitializer(
      ServerLoginContext loginContext, ProxyProtocolConfig proxyProtocol, LongSupplier nanoClock) {
    this.loginContext = loginContext;
    this.proxyProtocol = proxyProtocol;
    this.untrustedPeerWarnings = new LogThrottle(WARNING_INTERVAL, nanoClock);
    this.proxyHeaderWarnings = new LogThrottle(WARNING_INTERVAL, nanoClock);
  }

  // ---------------------------------------------------------------------------
  // Channel initialisation
  // ---------------------------------------------------------------------------

  @Override
  protected void initChannel(Channel ch) {
    ClientLimits limits = loginContext.limits();
    if (proxyProtocol.enabled() && !fromTrustedProxy(ch)) {
      return;
    }
    // First the cap on connections not logged in, a counter: a flood over it costs no lookup.
    if (!limits.tryStartPending()) {
      reset(ch);
      return;
    }
    // Behind the PROXY protocol, the connection limit applies to the client address of the
    // header, once it is known.
    if (!proxyProtocol.enabled() && !limits.admitConnection(ch.remoteAddress())) {
      limits.endPending();
      reset(ch);
      return;
    }

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
    // From now on the connection gives its place back when it closes, or once logged in.
    connection.guard(new ConnectionGuard(ch, limits));
    ChannelPipeline pipeline = ch.pipeline();

    if (proxyProtocol.enabled()) {
      // First of all: nothing reaches the Minecraft handlers before the header has been applied.
      pipeline
          .addLast(PROXY_PROTOCOL_DECODER, new ProxyProtocolDecoder())
          .addLast(
              PROXY_PROTOCOL_HANDLER,
              new ProxyProtocolHandler(connection, limits, proxyHeaderWarnings));
    }

    pipeline
        // Inbound: frame split → timeout → packet decode → handler. The read timeout comes after
        // the frame decoder, so that only complete frames reset it (BungeeCord PR #3066), and the
        // frames of a client that is not logged in are checked against the limits of its state.
        .addLast(LEGACY_PING_DECODER, new LegacyPingDecoder())
        .addLast(LEGACY_PING_ENCODER, LegacyDisconnectEncoder.INSTANCE)
        .addLast(FRAME_DECODER, new FrameDecoder(FrameLimits.HANDSHAKE))
        .addLast(
            READ_TIMEOUT,
            new ReadTimeoutHandler(MinecraftConnection.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .addLast(MINECRAFT_DECODER, decoder)
        // Outbound: handler → packet encode → frame prepend
        .addLast(FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(MINECRAFT_ENCODER, encoder)
        // Tail: inbound handler that dispatches to SessionHandler
        .addLast(CONNECTION_HANDLER, connection);

    // Start with the handshake handler.
    connection.setSessionHandler(new HandshakeSessionHandler(connection, loginContext));
  }

  /**
   * Returns whether the peer of {@code ch} is a trusted proxy, and otherwise resets the connection
   * without reading from it. Only a trusted proxy may send a PROXY protocol header: anyone else
   * could put any address in it.
   */
  private boolean fromTrustedProxy(Channel ch) {
    SocketAddress peer = ch.remoteAddress();
    if (peer instanceof InetSocketAddress address
        && proxyProtocol.trustedProxies().contains(address.getAddress())) {
      return true;
    }
    long suppressed = untrustedPeerWarnings.tryAcquire();
    if (suppressed >= 0) {
      logger.warn(
          "Refused a connection from {}: the PROXY protocol is on and this address is not in"
              + " proxy-protocol.trusted-proxies. If it is one of your load balancers, add it"
              + " there.{}",
          peer,
          LogThrottle.suffix(suppressed, "connections refused from untrusted addresses"));
    }
    reset(ch);
    return false;
  }

  /**
   * Closes a connection that Warp refuses before reading anything from it, with a TCP reset rather
   * than an orderly close: the proxy keeps no socket in TIME_WAIT for it, which matters when a scan
   * or a flood opens thousands of them.
   *
   * @param ch the connection to drop
   */
  static void reset(Channel ch) {
    // SO_LINGER 0 makes close() send a RST; transports without the option close normally.
    ch.config().setOption(ChannelOption.SO_LINGER, 0);
    var _ = ch.close();
  }
}
