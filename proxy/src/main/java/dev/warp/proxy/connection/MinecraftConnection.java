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
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.netty.CompressionEncoder;
import dev.warp.protocol.netty.FrameForwarder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around a Netty {@link Channel} that bridges the protocol pipeline to a {@link
 * SessionHandler}.
 *
 * <p>This class sits at the tail of the inbound pipeline and dispatches decoded messages to the
 * active session handler. It also provides convenience methods for writing packets and managing
 * protocol state transitions.
 *
 * <h3>Design — not a god object</h3>
 *
 * <p>Unlike Velocity's {@code MinecraftConnection} which mixes I/O, state machine, read timeouts,
 * and packet dispatch, this class is deliberately thin:
 *
 * <ul>
 *   <li><b>I/O</b> — write, flush, close, and verbatim forwarding of frames from the player's other
 *       connection ({@link #forward})
 *   <li><b>Writes across a state change</b>: the proxy's own packets wait for the peer to
 *       acknowledge a state change ({@link #writeTerminal}, {@link #writeWhenSettled})
 *   <li><b>Pipeline access</b> — state, version and compression transitions on decoder/encoder
 *   <li><b>Handshake intent</b>: why the client connected ({@link #handshakeIntent})
 *   <li><b>Dispatch</b> — forward messages to the active {@link SessionHandler}
 * </ul>
 *
 * <p>All protocol logic lives in the session handler, not here.
 *
 * <h3>Thread safety</h3>
 *
 * <p>All methods must be called from the channel's event loop thread, which Netty guarantees for
 * pipeline handlers.
 */
public final class MinecraftConnection extends ChannelInboundHandlerAdapter {

  private static final Logger logger = LoggerFactory.getLogger(MinecraftConnection.class);

  /**
   * Seconds without receiving anything after which a connection, to a player or to a backend, is
   * closed. A live peer never stays that quiet: a client sends a movement packet every tick and
   * answers keep-alives, a server sends keep-alives.
   */
  static final int READ_TIMEOUT_SECONDS = 30;

  /**
   * How long a 1.7 client keeps its connection after a final packet (see {@link #writeAndClose}).
   */
  static final long LEGACY_CLOSE_DELAY_MS = 250;

  private final Channel channel;
  private final FrameForwarder forwarder;

  /**
   * The client's address as a PROXY protocol header gave it, or {@code null} to use the socket's
   * peer. Set once, on the event loop, before any session handler reads it.
   */
  private @Nullable InetSocketAddress proxiedAddress;

  private @Nullable SessionHandler sessionHandler;

  /** The host name the client connected to, from its handshake; {@code null} until known. */
  private volatile @Nullable String virtualHost;

  private @Nullable MinecraftDecoder decoder;
  private @Nullable MinecraftEncoder encoder;

  /** Why the client connected, once its handshake is read; always {@code null} for a backend. */
  private @Nullable HandshakeIntent handshakeIntent;

  /**
   * Whether a packet that moves the peer to another state was written ({@link #writeTerminal}) and
   * the peer has not confirmed the change yet ({@link #setState}).
   */
  private boolean stateChangePending;

  /** Packets of the proxy's own held until the pending state change completes. */
  private final Queue<Held> heldUntilStateChange = new ArrayDeque<>();

  /**
   * A packet {@link #writeWhenSettled} holds, with the promise its write completes.
   *
   * @param packet the packet
   * @param promise completed once it is written, or failed if it never is; possibly void
   */
  private record Held(Packet packet, ChannelPromise promise) {}

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  /**
   * Creates a new connection wrapper.
   *
   * @param channel the underlying Netty channel
   */
  public MinecraftConnection(Channel channel) {
    this.channel = channel;
    this.forwarder = new FrameForwarder(channel);
  }

  // ---------------------------------------------------------------------------
  // Netty inbound handler
  // ---------------------------------------------------------------------------

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (sessionHandler == null) {
      // No handler installed — release to prevent leaks.
      ReferenceCountUtil.release(msg);
      return;
    }

    if (msg instanceof Packet packet) {
      sessionHandler.handle(packet);
    } else if (msg instanceof ByteBuf buf) {
      // handleBlind takes ownership of the buffer.
      sessionHandler.handleBlind(buf);
    } else {
      // Unknown message type — release to prevent leaks.
      ReferenceCountUtil.release(msg);
    }
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    if (sessionHandler != null) {
      sessionHandler.readComplete();
    }
    ctx.fireChannelReadComplete();
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    for (Held held = heldUntilStateChange.poll();
        held != null;
        held = heldUntilStateChange.poll()) {
      if (!held.promise().isVoid()) {
        held.promise().tryFailure(new ClosedChannelException());
      }
    }
    if (sessionHandler != null) {
      sessionHandler.disconnected();
    }
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) {
    if (sessionHandler != null) {
      sessionHandler.writabilityChanged();
    }
    ctx.fireChannelWritabilityChanged();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (cause instanceof ReadTimeoutException) {
      // A peer that went quiet: it is gone or stuck, and closing is all the proxy can do. Worth a
      // line, unlike a reset connection: how the connection stood says which side stopped.
      logger.info(
          "Connection {} timed out {}", remoteAddress(), describeTimeout(channel, decoder()));
    } else {
      String routine = describeRoutine(cause, ctx.channel().isActive());
      if (routine != null) {
        logger.debug("{} ({}): {}", routine, remoteAddress(), cause.toString());
      } else {
        // Everything else is logged in full.
        logger.error("Exception in pipeline for {}", remoteAddress(), cause);
      }
    }
    if (ctx.channel().isActive()) {
      var _ = ctx.close();
    }
  }

  /**
   * Describes a connection that received nothing for as long as its read timeout waits ({@value
   * #READ_TIMEOUT_SECONDS} s before login, {@code timeouts.read} after), as it stands: its protocol
   * state, which side went quiet, whether the proxy was reading it, and how much it still had to
   * send it. Reads paused means the proxy itself kept the peer's packets unread (back-pressure, or
   * a client that sent too much while waiting for its next server); bytes piling up mean the peer
   * stopped reading too; neither means the peer went silent while taking what it was sent. The
   * queued bytes are Netty's count: the packets plus a small overhead per message.
   *
   * @param channel the connection that timed out
   * @param decoder the connection's decoder, which knows its state and which side the peer is
   * @return e.g. {@code "in PLAY: the client sent nothing for 30 s (reads on, 0 bytes queued to
   *     send)"}
   */
  static String describeTimeout(Channel channel, MinecraftDecoder decoder) {
    ChannelOutboundBuffer outbound = channel.unsafe().outboundBuffer();
    long queued = outbound == null ? 0 : outbound.totalPendingWriteBytes();
    return "in "
        + decoder.state()
        + ": the "
        + (decoder.direction() == PacketDirection.SERVERBOUND ? "client" : "backend")
        + " sent nothing for "
        + readTimeout(channel)
        + " (reads "
        + (channel.config().isAutoRead() ? "on" : "paused")
        + ", "
        + queued
        + " bytes queued to send)";
  }

  /** The read timeout of a connection, such as {@code 30 s} or {@code 1500 ms}. */
  private static String readTimeout(Channel channel) {
    long millis =
        channel.pipeline().get(ServerChannelInitializer.READ_TIMEOUT)
                instanceof IdleStateHandler idle
            ? idle.getReaderIdleTimeInMillis()
            : READ_TIMEOUT_SECONDS * 1000L;
    return millis % 1000 == 0 ? millis / 1000 + " s" : millis + " ms";
  }

  /**
   * Describes an exception that is part of normal operation, or returns {@code null} for one that
   * may reveal a bug and must be logged in full. Never decided on the channel state alone, which
   * would hide real bugs.
   *
   * @param cause the exception caught in the pipeline
   * @param active whether the channel is connected
   * @return a short description for the debug log, or {@code null}
   */
  static @Nullable String describeRoutine(Throwable cause, boolean active) {
    if (cause instanceof ClosedChannelException) {
      // A write raced with the peer closing the connection: expected, nothing went wrong.
      return "Write to closed connection";
    }
    if (cause instanceof IOException) {
      // The peer vanished (connection reset, broken pipe): routine for players, not a fault.
      return "Connection lost";
    }
    if (cause instanceof NotYetConnectedException && !active) {
      // A read on a backend socket that is still connecting. epoll reports readiness by
      // descriptor number: when a server switch closes the old backend and connects the new one
      // in the same batch of events, the new socket can reuse the old descriptor and receive its
      // pending read event. Nothing is read, and the connection completes normally.
      return "Stale read event before connect";
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Write operations
  // ---------------------------------------------------------------------------

  /**
   * Writes a packet to the channel without flushing.
   *
   * @param packet the packet to write
   */
  public void write(Packet packet) {
    var _ = channel.write(packet, channel.voidPromise());
  }

  /**
   * Writes a packet to the channel and flushes immediately.
   *
   * @param packet the packet to write
   */
  public void writeAndFlush(Packet packet) {
    var _ = channel.writeAndFlush(packet, channel.voidPromise());
  }

  /**
   * Writes a packet, flushes, and closes the channel after the write completes.
   *
   * <p>Uses {@link ChannelFutureListener#CLOSE} to guarantee the close happens <em>after</em> the
   * flush finishes at the socket level, not just after the pipeline processing. This is the
   * idiomatic Netty pattern for "send-then-close".
   *
   * <p>A 1.7 client handles packets on its main thread, after its network thread saw the connection
   * close: closed too soon, it shows "End of stream" instead of the reason it has just received.
   * Its connection closes {@value #LEGACY_CLOSE_DELAY_MS} ms after the packet, as Velocity's {@code
   * closeWith} does, and is no longer read meanwhile.
   *
   * @param packet the final packet to send before closing
   */
  public void writeAndClose(Packet packet) {
    if (encoder().version().isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
      setAutoRead(false);
      var _ = channel.writeAndFlush(packet, channel.voidPromise());
      var _ =
          channel.eventLoop().schedule(this::close, LEGACY_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
      return;
    }
    channel.writeAndFlush(packet).addListener(ChannelFutureListener.CLOSE);
  }

  /**
   * Forwards an uninspected frame received on the player's other connection, without flushing
   * (blind forwarding hot path).
   *
   * <p>The frame is written in its original form — compressed bytes included — whenever this
   * connection's peer accepts it, and re-encoded otherwise; see {@link FrameForwarder}. Both
   * connections of a player share one event loop, so this is a plain call into this pipeline.
   *
   * @param frame the complete frame from {@code source}'s decoder (ownership is transferred)
   * @param source the connection the frame was received on
   */
  public void forward(ByteBuf frame, MinecraftConnection source) {
    forwarder.forward(frame, source.decoder());
  }

  // ---------------------------------------------------------------------------
  // Writes across a state change
  // ---------------------------------------------------------------------------

  /**
   * Writes and flushes a packet that moves the peer to another protocol state as soon as it reads
   * it: Login Success (from 1.20.2), Finish Configuration, Start Configuration.
   *
   * <p>The peer reads whatever follows in the new state, while this connection's encoder keeps the
   * current one until the peer acknowledges and {@link #setState} follows. Until then, packets
   * written with {@link #writeWhenSettled} are held: encoded in the old state, the peer would read
   * them as other packets of the new one (in configuration, a play Cookie Request id is a Bundle
   * Delimiter).
   *
   * @param packet the packet that ends the current state for the peer
   */
  public void writeTerminal(Packet packet) {
    writeAndFlush(packet);
    stateChangePending = true;
  }

  /**
   * Writes and flushes a packet the proxy sends of its own accord, such as a cookie request or a
   * transfer, once the peer reads this connection's packets in the encoder's state: right away, or
   * when {@link #setState} completes a pending state change, in the order they were written.
   *
   * <p>A held packet that does not exist in the state the peer moved to is dropped and logged.
   *
   * @param packet the packet to write
   */
  public void writeWhenSettled(Packet packet) {
    writeWhenSettled(packet, channel.voidPromise());
  }

  /**
   * Writes and flushes a packet as {@link #writeWhenSettled(Packet)} does, completing {@code
   * promise} once it is written: right away, or when the pending state change completes. The
   * promise fails if the packet is dropped, or if the connection closes first.
   *
   * @param packet the packet to write
   * @param promise the promise of the write, from this connection's channel
   */
  public void writeWhenSettled(Packet packet, ChannelPromise promise) {
    if (stateChangePending) {
      heldUntilStateChange.add(new Held(packet, promise));
    } else {
      var _ = channel.writeAndFlush(packet, promise);
    }
  }

  /**
   * Returns whether a packet that moves the peer to another state was written and the peer has not
   * confirmed the change yet: {@link #writeWhenSettled} holds packets meanwhile.
   *
   * @return {@code true} between {@link #writeTerminal} and the next {@link #setState}
   */
  public boolean isStateChangePending() {
    return stateChangePending;
  }

  private void releaseHeldPackets() {
    stateChangePending = false;
    MinecraftEncoder enc = encoder();
    for (Held held = heldUntilStateChange.poll();
        held != null;
        held = heldUntilStateChange.poll()) {
      Packet packet = held.packet();
      if (enc.canEncode(packet.getClass())) {
        var _ = channel.writeAndFlush(packet, held.promise());
      } else {
        logger.warn(
            "Dropped {} held for {}: it does not exist in {}",
            packet.getClass().getSimpleName(),
            channel.remoteAddress(),
            enc.state());
        if (!held.promise().isVoid()) {
          held.promise()
              .tryFailure(
                  new IllegalStateException(
                      packet.getClass().getSimpleName() + " does not exist in " + enc.state()));
        }
      }
    }
  }

  /** Flushes the channel. Called from {@code readComplete()} for write batching. */
  public void flush() {
    channel.flush();
  }

  /**
   * Sets auto-read on the channel. Used for back-pressure: when the target channel becomes
   * unwritable, auto-read is disabled on the source channel to stop reading.
   *
   * @param autoRead {@code true} to enable, {@code false} to disable
   */
  public void setAutoRead(boolean autoRead) {
    channel.config().setAutoRead(autoRead);
  }

  /** Closes the channel gracefully. */
  public void close() {
    var _ = channel.close();
  }

  // ---------------------------------------------------------------------------
  // Session handler management
  // ---------------------------------------------------------------------------

  /**
   * Replaces the active session handler, notifying both the old and new handler of the transition.
   *
   * @param handler the new session handler
   */
  public void setSessionHandler(SessionHandler handler) {
    if (this.sessionHandler != null) {
      this.sessionHandler.deactivated();
    }
    this.sessionHandler = handler;
    handler.activated();
  }

  /**
   * Returns the active session handler.
   *
   * @return the current session handler, or {@code null} if none is set
   */
  public @Nullable SessionHandler sessionHandler() {
    return sessionHandler;
  }

  // ---------------------------------------------------------------------------
  // Protocol state management
  // ---------------------------------------------------------------------------

  /**
   * Updates the protocol state on both the decoder and encoder, then writes the packets {@link
   * #writeWhenSettled} held during the state change, if one was pending.
   *
   * @param state the new protocol state
   */
  public void setState(ProtocolState state) {
    decoder().setState(state);
    encoder().setState(state);
    if (stateChangePending) {
      releaseHeldPackets();
    }
  }

  /**
   * Updates the protocol version on both the decoder and encoder.
   *
   * @param version the negotiated protocol version
   */
  public void setVersion(ProtocolVersion version) {
    decoder().setVersion(version);
    encoder().setVersion(version);
  }

  /**
   * Switches both directions of this connection to compressed frames. Must be called right after
   * the {@code SetCompression} packet has been sent (to a player) or received (from a backend).
   *
   * <p>Frames from a backend are trusted: uninspected ones are forwarded without being inflated.
   * Frames from a player are inflated — and so verified — before being forwarded, and compressed
   * frames below the threshold are rejected, as a vanilla server does.
   *
   * @param threshold the compression threshold of this connection
   * @param level the zlib level for packets this connection compresses itself
   * @param peerIsServer {@code true} for a backend connection, {@code false} for a player's
   * @param passthrough whether uninspected frames may be forwarded in their original compressed
   *     form; {@code false} restores inflate-and-recompress forwarding
   */
  public void enableCompression(
      int threshold, int level, boolean peerIsServer, boolean passthrough) {
    decoder()
        .enableCompression(
            new FrameDecompressor(
                threshold,
                !peerIsServer,
                FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
                new JavaCompressor(level)),
            peerIsServer && passthrough);
    // The compression encoder frames and compresses in one pass, replacing the frame encoder.
    channel
        .pipeline()
        .replace(
            ServerChannelInitializer.FRAME_ENCODER,
            ServerChannelInitializer.COMPRESSION_ENCODER,
            new CompressionEncoder(threshold, new JavaCompressor(level)));
    forwarder.compressionEnabled(threshold);
    forwarder.setVerbatimEnabled(passthrough);
  }

  // ---------------------------------------------------------------------------
  // Handshake intent
  // ---------------------------------------------------------------------------

  /**
   * Returns why the client opened this connection, as its handshake declared.
   *
   * @return the intent, or {@code null} before the handshake and on a backend connection
   */
  public @Nullable HandshakeIntent handshakeIntent() {
    return handshakeIntent;
  }

  /**
   * Records why the client opened this connection. Called once, by the handshake.
   *
   * @param intent the intent its handshake declared
   */
  void setHandshakeIntent(HandshakeIntent intent) {
    this.handshakeIntent = intent;
  }

  // ---------------------------------------------------------------------------
  // Pipeline accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the host name the client typed to connect, cleaned of the suffixes some platforms
   * append (Forge's {@code \0FML\0} markers, a trailing dot).
   *
   * @return the host name, or {@code null} before the handshake or if it was empty
   */
  public @Nullable String virtualHost() {
    return virtualHost;
  }

  /**
   * Records the host name of the client's handshake.
   *
   * @param serverAddress the address field of the handshake, as sent
   */
  public void setVirtualHost(String serverAddress) {
    String host = serverAddress;
    int nul = host.indexOf('\0');
    if (nul >= 0) {
      host = host.substring(0, nul);
    }
    while (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    this.virtualHost = host.isEmpty() ? null : host;
  }

  /**
   * Returns the underlying Netty channel.
   *
   * <p>Its {@link Channel#remoteAddress()} is the socket's peer, which is a load balancer when the
   * PROXY protocol is on: use {@link #remoteAddress()} for the address of the client.
   *
   * @return the channel
   */
  public Channel channel() {
    return channel;
  }

  /**
   * Returns the address of the peer this connection serves: the client's real address when a
   * trusted load balancer sent it in a PROXY protocol header, the socket's peer otherwise (the
   * client itself, or the backend server of a backend connection).
   *
   * <p>Everything that names, limits or forwards a client uses this address, never the channel's.
   *
   * @return the peer's address
   */
  public SocketAddress remoteAddress() {
    InetSocketAddress proxied = proxiedAddress;
    return proxied != null ? proxied : channel.remoteAddress();
  }

  /**
   * Records the client's real address, read from the PROXY protocol header a trusted load balancer
   * sent at the start of the connection.
   *
   * @param address the client's address and port
   */
  void setProxiedAddress(InetSocketAddress address) {
    this.proxiedAddress = address;
  }

  /**
   * Returns the {@link MinecraftDecoder} from the pipeline.
   *
   * @return the decoder
   * @throws IllegalStateException if the decoder is not in the pipeline
   */
  public MinecraftDecoder decoder() {
    MinecraftDecoder cached = decoder;
    if (cached == null) {
      cached = channel.pipeline().get(MinecraftDecoder.class);
      if (cached == null) {
        throw new IllegalStateException("MinecraftDecoder not found in pipeline");
      }
      decoder = cached;
    }
    return cached;
  }

  /**
   * Returns the {@link MinecraftEncoder} from the pipeline.
   *
   * @return the encoder
   * @throws IllegalStateException if the encoder is not in the pipeline
   */
  public MinecraftEncoder encoder() {
    MinecraftEncoder cached = encoder;
    if (cached == null) {
      cached = channel.pipeline().get(MinecraftEncoder.class);
      if (cached == null) {
        throw new IllegalStateException("MinecraftEncoder not found in pipeline");
      }
      encoder = cached;
    }
    return cached;
  }
}
