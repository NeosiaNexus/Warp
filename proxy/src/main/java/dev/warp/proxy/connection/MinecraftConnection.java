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

import dev.warp.api.network.PipelinePosition;
import dev.warp.api.player.ClientPlatform;
import dev.warp.api.server.Route;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.netty.CompressionEncoder;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameForwarder;
import dev.warp.protocol.netty.FrameLimitException;
import dev.warp.protocol.netty.FrameLimits;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.PacketDecompressor;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.netty.TrafficObserver;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.proxy.bedrock.BedrockIdentity;
import dev.warp.proxy.compression.Compression;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.metrics.ProxyMetrics;
import dev.warp.proxy.pipeline.PipelineHost;
import dev.warp.proxy.server.HostRoutes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
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
public final class MinecraftConnection extends ChannelInboundHandlerAdapter
    implements PipelineHost {

  private static final Logger logger = LoggerFactory.getLogger(MinecraftConnection.class);

  /**
   * Seconds without receiving anything after which a client connection that has not logged in yet
   * is closed; {@code timeouts.read} replaces it once the player is logged in, and backend
   * connections use {@code timeouts.backend-read}. A live peer never stays that quiet: a client
   * sends a movement packet every tick and answers keep-alives, a server sends keep-alives.
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

  private volatile @Nullable Route route;

  /** The port the client's handshake named; 0 before it. */
  private volatile int virtualPort;

  private @Nullable MinecraftDecoder decoder;
  private @Nullable MinecraftEncoder encoder;

  /** Why the client connected, once its handshake is read; always {@code null} for a backend. */
  private @Nullable HandshakeIntent handshakeIntent;

  /** What the markers of the client's handshake said it is; {@link ClientPlatform#JAVA} until. */
  private volatile ClientPlatform clientPlatform = ClientPlatform.JAVA;

  /** The marker of the client's handshake to forward to a backend, normalized; empty without. */
  private volatile String handshakeMarker = "";

  /** The Bedrock identity Floodgate's data gave this connection, or {@code null} for a Java one. */
  private volatile @Nullable BedrockIdentity bedrock;

  /**
   * Whether a packet that moves the peer to another state was written ({@link #writeTerminal}) and
   * the peer has not confirmed the change yet ({@link #setState}).
   */
  private boolean stateChangePending;

  /** Packets of the proxy's own held until the pending state change completes. */
  private final Queue<Held> heldUntilStateChange = new ArrayDeque<>();

  /**
   * What the peer answered while {@link #acknowledgeStateChange} wrote its acknowledgement, kept
   * for the session handler of the new state; {@code null} outside that write.
   */
  private @Nullable Queue<Object> answeredDuringAcknowledgement;

  /**
   * Watches a client connection until its client is logged in; {@code null} afterwards, and for a
   * backend connection.
   */
  private @Nullable ConnectionGuard guard;

  /** Counts the connection's states and refused frames, or {@code null} on backend connections. */
  private @Nullable ProxyMetrics states;

  /** The frame decoder of a client connection, whose limits follow the state; looked up once. */
  private @Nullable FrameDecoder frameDecoder;

  /** Whether the proxy asked the client for a cookie during its login (1.20.5+). */
  private boolean cookieResponsesExpected;

  /**
   * Whether the client is logged in: its frames are bounded by the protocol alone from then on,
   * whatever state the proxy still reads it in (LOGIN until a 1.20.2+ client acknowledges).
   */
  private boolean frameLimitsLifted;

  /**
   * A packet {@link #writeWhenSettled} holds, with the promise its write completes.
   *
   * @param packet the packet
   * @param promise completed once it is written, or failed if it never is; possibly void
   */
  private record Held(Packet packet, ChannelPromise promise) {}

  /** Whether plugin handlers read this connection's packets: see {@link #usePacketLevel()}. */
  private boolean packetLevel;

  /** Whether this connection switched to compressed frames. */
  private boolean compressed;

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

  /**
   * Counts this connection in {@code warp_connections_active} by state, from its current state
   * until it closes, and its refused frames in {@code warp_packet_drops_total}. For client
   * connections.
   *
   * @param metrics the proxy's metrics
   */
  public void trackStates(ProxyMetrics metrics) {
    this.states = metrics;
    metrics.connectionMoved(null, decoder().state());
  }

  /**
   * Sets who counts this connection's frames: its decoder's and its forwarder's.
   *
   * @param observer the observer
   */
  public void observeTraffic(TrafficObserver observer) {
    decoder().observer(observer);
    forwarder.observer(observer);
  }

  // ---------------------------------------------------------------------------
  // Netty inbound handler
  // ---------------------------------------------------------------------------

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    Queue<Object> answered = answeredDuringAcknowledgement;
    if (answered != null) {
      // Read during the write of an acknowledgement: for the next state's session handler.
      answered.add(msg);
      return;
    }
    dispatch(msg);
  }

  /** Hands a decoded packet or an uninspected frame to the session handler. */
  private void dispatch(Object msg) {
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
    ProxyMetrics counting = states;
    if (counting != null) {
      states = null;
      counting.connectionMoved(decoder().state(), null);
    }
    ConnectionGuard watching = guard;
    if (watching != null) {
      guard = null;
      watching.end();
    }
    for (Held held = heldUntilStateChange.poll();
        held != null;
        held = heldUntilStateChange.poll()) {
      if (!held.promise().isVoid()) {
        held.promise().tryFailure(new ClosedChannelException());
      }
    }
    Queue<Object> answered = answeredDuringAcknowledgement;
    if (answered != null) {
      releaseAll(answered);
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
    ProxyMetrics counting = states;
    if (counting != null) {
      if (cause instanceof FrameLimitException limit) {
        if (limit.reason() == FrameLimitException.Reason.PACKET_NOT_ALLOWED) {
          counting.frameRefusedByState();
        } else {
          counting.frameOversized();
        }
      } else if (cause instanceof DecoderException) {
        counting.frameInvalid();
      }
    }
    ConnectionGuard watching = guard;
    Refusal refusal = watching == null ? null : watching.failed(cause);
    if (refusal != null) {
      // A client that is not logged in can cause this at will: counted, never logged one by one.
      logger.debug(
          "Closed {} before login ({}): {}", remoteAddress(), refusal.label(), cause.toString());
      if (ctx.channel().isActive()) {
        ServerChannelInitializer.reset(ctx.channel());
      }
      return;
    }
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
        // Everything else is logged in full, naming the plugin handlers it may come from.
        logger.error(
            LogCode.PIPELINE_EXCEPTION.marker(),
            "Exception in pipeline for {}{}",
            remoteAddress(),
            describePluginHandlers(ctx.pipeline().names()),
            cause);
      }
    }
    if (ctx.channel().isActive()) {
      var _ = ctx.close();
    }
  }

  /**
   * Describes a connection that received nothing for as long as its read timeout waits (for a
   * client {@value #READ_TIMEOUT_SECONDS} s before login, {@code timeouts.read} after; for a
   * backend {@code timeouts.backend-read}), as it stands: its protocol state, which side went
   * quiet, whether the proxy was reading it, and how much it still had to send it. Reads paused
   * means the proxy itself kept the peer's packets unread (back-pressure, or a client that sent too
   * much while waiting for its next server); bytes piling up mean the peer stopped reading too;
   * neither means the peer went silent while taking what it was sent. The queued bytes are Netty's
   * count: the packets plus a small overhead per message.
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
   * Names the plugin handlers of a pipeline, which are the first suspects of an exception in it.
   * Plugin handlers are the only ones whose names hold a colon ({@code plugin-id:name}).
   *
   * @param names the pipeline's handler names
   * @return e.g. {@code " (plugin handlers: viaversion:decoder, viaversion:encoder)"}, or an empty
   *     string when there are none
   */
  static String describePluginHandlers(List<String> names) {
    StringBuilder plugins = new StringBuilder();
    for (String name : names) {
      if (name.indexOf(':') > 0) {
        plugins.append(plugins.isEmpty() ? " (plugin handlers: " : ", ").append(name);
      }
    }
    return plugins.isEmpty() ? "" : plugins.append(')').toString();
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

  /**
   * Acknowledges the state change the peer announced (Login Success from 1.20.2, Finish
   * Configuration, Start Configuration), then moves this connection to {@code state} under {@code
   * handler}. Must be called on the event loop.
   *
   * <p>The acknowledgement is encoded in the state the peer leaves, and what the proxy writes after
   * it (from {@code handler}'s activation on) in {@code state}. The peer answers in {@code state},
   * and an answer may come back before the write returns: a plugin handler that plays the peer in
   * this pipeline, as ViaVersion does for a backend older than 1.20.2, takes the acknowledgement
   * and passes up, during the write, the packets it held for it (Join Game and the rest of the play
   * packets once the configuration it emulates is over). So the decoder reads the new state before
   * the write, and what it reads during the write waits for {@code handler}, which gets it in order
   * once active. A server only answers once it reads the acknowledgement: for it, nothing changes.
   *
   * @param acknowledgement the packet that confirms the change to the peer
   * @param state the state both directions move to
   * @param handler the session handler of that state
   */
  public void acknowledgeStateChange(
      Packet acknowledgement, ProtocolState state, SessionHandler handler) {
    decoder().setState(state);
    updateFrameLimits();
    Queue<Object> answered = new ArrayDeque<>();
    answeredDuringAcknowledgement = answered;
    try {
      writeAndFlush(acknowledgement);
    } finally {
      answeredDuringAcknowledgement = null;
    }
    encoder().setState(state);
    if (stateChangePending) {
      releaseHeldPackets();
    }
    setSessionHandler(handler);
    if (answered.isEmpty()) {
      return;
    }
    for (Object msg = answered.poll(); msg != null; msg = answered.poll()) {
      if (!channel.isActive()) {
        ReferenceCountUtil.release(msg);
        releaseAll(answered);
        return;
      }
      dispatch(msg);
    }
    // The end of a read, as Netty would signal it: what the handler forwarded goes out now.
    SessionHandler reading = sessionHandler;
    if (reading != null) {
      reading.readComplete();
    }
  }

  private static void releaseAll(Queue<Object> messages) {
    for (Object msg = messages.poll(); msg != null; msg = messages.poll()) {
      ReferenceCountUtil.release(msg);
    }
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
            LogCode.HELD_PACKET_DROPPED.marker(),
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

  /**
   * Closes the channel with a TCP reset, for a client refused before login: no answer is owed, and
   * the proxy keeps no socket in TIME_WAIT for it.
   */
  public void reset() {
    ServerChannelInitializer.reset(channel);
  }

  // ---------------------------------------------------------------------------
  // Before login
  // ---------------------------------------------------------------------------

  /**
   * Hands the connection the guard that watches it until its client is logged in.
   *
   * @param watching the guard
   */
  void guard(ConnectionGuard watching) {
    this.guard = watching;
  }

  /**
   * Returns the guard watching this client connection.
   *
   * @return the guard, or {@code null} once the client is logged in, and for a backend
   */
  @Nullable ConnectionGuard guard() {
    return guard;
  }

  /**
   * Marks the client logged in: it no longer counts among the connections not logged in, and no
   * deadline or limit of those applies to it any more, the frame limits included. Those must go now
   * rather than when the state changes: a 1.20.2+ client stays in the LOGIN state until it
   * acknowledges its Login Success, and does so in a compressed frame, whose first byte is the
   * uncompressed length rather than the packet identifier.
   */
  void loggedIn() {
    ConnectionGuard watching = guard;
    if (watching != null) {
      guard = null;
      watching.end();
    }
    liftFrameLimits();
  }

  /**
   * Lifts the frame limits of the login for good, while the connection stays among those not logged
   * in, under their deadlines and guard: for a client told its login succeeded without being logged
   * in (verified before authentication), which answers in compressed frames from then on, as {@link
   * #loggedIn} describes.
   */
  void liftFrameLimits() {
    frameLimitsLifted = true;
    FrameDecoder frames = frameDecoder();
    if (frames != null) {
      frames.limits(null);
    }
  }

  /**
   * Lets the client answer the cookie requests the proxy sends it during its login: Cookie
   * Responses, which the frame limits of the login state otherwise refuse, are now read.
   */
  void expectCookieResponses() {
    if (!cookieResponsesExpected) {
      cookieResponsesExpected = true;
      updateFrameLimits();
    }
  }

  /**
   * Sets the frame limits of the state a client is read in: those of the HANDSHAKE, STATUS and
   * LOGIN states, none from CONFIGURATION on. A backend's frames are not limited.
   */
  private void updateFrameLimits() {
    MinecraftDecoder dec = decoder();
    if (frameLimitsLifted || dec.direction() != PacketDirection.SERVERBOUND) {
      return;
    }
    FrameDecoder frames = frameDecoder();
    if (frames != null) {
      frames.limits(FrameLimits.serverbound(dec.state(), dec.version(), cookieResponsesExpected));
    }
  }

  /** The frame decoder, or {@code null} in a test pipeline without framing. */
  private @Nullable FrameDecoder frameDecoder() {
    FrameDecoder frames = frameDecoder;
    if (frames == null) {
      frames = channel.pipeline().get(FrameDecoder.class);
      frameDecoder = frames;
    }
    return frames;
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
   * #writeWhenSettled} held during the state change, if one was pending. On a client connection,
   * the frames that follow are checked against the limits of the new state.
   *
   * @param state the new protocol state
   */
  public void setState(ProtocolState state) {
    ProxyMetrics counting = states;
    if (counting != null) {
      counting.connectionMoved(decoder().state(), state);
    }
    decoder().setState(state);
    encoder().setState(state);
    updateFrameLimits();
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
    FrameDecompressor decompressor =
        new FrameDecompressor(
            threshold,
            !peerIsServer,
            FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
            Compression.create(level));
    if (packetLevel) {
      // Every frame is inflated ahead of the plugin packet handlers, the decoder reads bare
      // packets.
      packetDecompressor().enableCompression(decompressor);
    } else {
      decoder().enableCompression(decompressor, peerIsServer && passthrough);
    }
    // The compression encoder frames and compresses in one pass, replacing the frame encoder in
    // place: the plugin handlers on either side of it stay where they are.
    channel
        .pipeline()
        .replace(
            ServerChannelInitializer.FRAME_ENCODER,
            ServerChannelInitializer.COMPRESSION_ENCODER,
            new CompressionEncoder(threshold, Compression.create(level)));
    compressed = true;
    forwarder.compressionEnabled(threshold);
    forwarder.setVerbatimEnabled(passthrough && !packetLevel);
  }

  /**
   * Returns whether this connection switched to compressed frames.
   *
   * @return {@code true} once {@link #enableCompression} was called
   */
  public boolean isCompressed() {
    return compressed;
  }

  // ---------------------------------------------------------------------------
  // Packet level (plugin packet handlers)
  // ---------------------------------------------------------------------------

  /**
   * Hands this connection's packets decompressed to the plugin handlers placed between the framing
   * layer and the {@link MinecraftDecoder}, for good. Idempotent.
   *
   * <p>A {@link PacketDecompressor} goes right after the framing encoder, ahead of every plugin
   * packet handler: it unframes every frame and, once compressed, inflates it. The decoder then
   * reads, and forwards, bare packets. Frames forwarded to this connection are never written
   * verbatim, which would take them past the plugin handlers: they are re-encoded through the whole
   * outbound pipeline. Both cost what compression passthrough saves, on this connection only.
   */
  public void usePacketLevel() {
    if (packetLevel) {
      return;
    }
    packetLevel = true;
    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addAfter(
        framingEncoder(pipeline),
        ServerChannelInitializer.PACKET_DECOMPRESSOR,
        new PacketDecompressor(decoder().usePacketInput()));
    forwarder.setVerbatimEnabled(false);
  }

  /**
   * Returns whether plugin handlers read this connection's packets.
   *
   * @return {@code true} once {@link #usePacketLevel()} was called
   */
  @Override
  public boolean isPacketLevel() {
    return packetLevel;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Frame handlers go right before the framing encoder, packet handlers right before the {@link
   * MinecraftDecoder}: each after the plugin handlers already at its position (see {@link
   * ServerChannelInitializer} for the layout).
   */
  @Override
  public void addPluginHandler(PipelinePosition position, String name, ChannelHandler handler) {
    ChannelPipeline pipeline = channel.pipeline();
    if (pipeline.get(name) != null) {
      throw new IllegalArgumentException("The pipeline already has a handler named " + name);
    }
    switch (position) {
      case FRAME -> pipeline.addBefore(framingEncoder(pipeline), name, handler);
      case PACKET -> {
        usePacketLevel();
        pipeline.addBefore(ServerChannelInitializer.MINECRAFT_DECODER, name, handler);
      }
    }
  }

  @Override
  public List<String> pluginHandlers(PipelinePosition position) {
    ChannelPipeline pipeline = channel.pipeline();
    List<String> names = pipeline.names();
    String framing = framingEncoder(pipeline);
    int from =
        names.indexOf(
            switch (position) {
              case FRAME -> ServerChannelInitializer.FRAME_DECODER;
              case PACKET -> packetLevel ? ServerChannelInitializer.PACKET_DECOMPRESSOR : framing;
            });
    int to =
        names.indexOf(
            switch (position) {
              case FRAME -> framing;
              case PACKET -> ServerChannelInitializer.MINECRAFT_DECODER;
            });
    if (from < 0 || to < from) {
      return List.of();
    }
    return names.subList(from + 1, to).stream().filter(name -> name.indexOf(':') > 0).toList();
  }

  /** The name of the handler that frames outgoing packets: compression replaces the plain one. */
  private static String framingEncoder(ChannelPipeline pipeline) {
    return pipeline.get(ServerChannelInitializer.COMPRESSION_ENCODER) != null
        ? ServerChannelInitializer.COMPRESSION_ENCODER
        : ServerChannelInitializer.FRAME_ENCODER;
  }

  private PacketDecompressor packetDecompressor() {
    PacketDecompressor handler = channel.pipeline().get(PacketDecompressor.class);
    if (handler == null) {
      throw new IllegalStateException("PacketDecompressor not found in pipeline");
    }
    return handler;
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

  /**
   * Returns what kind of client this is, as the markers of its handshake declared.
   *
   * @return the platform, {@link ClientPlatform#JAVA} without a marker or before the handshake
   */
  public ClientPlatform clientPlatform() {
    return clientPlatform;
  }

  /**
   * Returns the handshake marker to append to a backend's host name on this client's behalf.
   *
   * @return the marker, normalized as {@link HandshakeMarkers} writes it, or an empty string
   */
  public String handshakeMarker() {
    return handshakeMarker;
  }

  /**
   * Records what the client's handshake said of its platform. Called once, by the handshake.
   *
   * @param detected the platform and the marker to forward
   */
  void setHandshakeMarker(HandshakeMarkers.Detected detected) {
    this.clientPlatform = detected.platform();
    this.handshakeMarker = detected.marker();
  }

  /**
   * Returns the Bedrock identity Floodgate's data gave this connection.
   *
   * @return the identity, or {@code null} for a Java connection
   */
  public @Nullable BedrockIdentity bedrock() {
    return bedrock;
  }

  /**
   * Makes this connection a verified Bedrock player's. Called once, by the handshake, after the
   * Floodgate data decrypted: the platform becomes {@link ClientPlatform.Bedrock}, and a Forge
   * marker, which Geyser never sends, is dropped.
   *
   * @param identity who the player is
   */
  void setBedrock(BedrockIdentity identity) {
    this.bedrock = identity;
    this.clientPlatform = identity.platform();
    this.handshakeMarker = "";
  }

  // ---------------------------------------------------------------------------
  // Pipeline accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the host name the client typed to connect, cleaned as {@link HostRoutes#clean}: in
   * lowercase, without the Forge markers or a BungeeCord payload, a trailing dot or a port.
   *
   * @return the host name, or {@code null} before the handshake or if it was empty
   */
  public @Nullable String virtualHost() {
    return virtualHost;
  }

  /**
   * Returns the port the client's handshake named: where the client believes it connected, which a
   * transfer back to this proxy uses.
   *
   * @return the port, or 0 before the handshake
   */
  public int virtualPort() {
    return virtualPort;
  }

  /**
   * Records the port the client's handshake named.
   *
   * @param port the handshake's port
   */
  public void setVirtualPort(int port) {
    this.virtualPort = port;
  }

  /**
   * Records the host name of the client's handshake.
   *
   * @param serverAddress the address field of the handshake, as sent
   */
  public void setVirtualHost(String serverAddress) {
    String host = HostRoutes.clean(serverAddress);
    this.virtualHost = host.isEmpty() ? null : host;
  }

  /**
   * Returns the route the client's host matched.
   *
   * @return the route, or {@code null} when none matched or before the handshake
   */
  public @Nullable Route route() {
    return route;
  }

  /**
   * Records the route the client's host matched.
   *
   * @param route the route, or {@code null} for none
   */
  public void setRoute(@Nullable Route route) {
    this.route = route;
  }

  /**
   * Returns the underlying Netty channel.
   *
   * <p>Its {@link Channel#remoteAddress()} is the socket's peer, which is a load balancer when the
   * PROXY protocol is on: use {@link #remoteAddress()} for the address of the client.
   *
   * @return the channel
   */
  @Override
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
