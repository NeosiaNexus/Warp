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

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
 *   <li><b>Pipeline access</b> — state, version and compression transitions on decoder/encoder
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

  private final Channel channel;
  private final FrameForwarder forwarder;

  private @Nullable SessionHandler sessionHandler;
  private @Nullable MinecraftDecoder decoder;
  private @Nullable MinecraftEncoder encoder;

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
    String routine = describeRoutine(cause, ctx.channel().isActive());
    if (routine != null) {
      logger.debug("{} ({}): {}", routine, channel.remoteAddress(), cause.toString());
    } else {
      // Everything else is logged in full.
      logger.error("Exception in pipeline for {}", channel.remoteAddress(), cause);
    }
    if (ctx.channel().isActive()) {
      var _ = ctx.close();
    }
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
   * @param packet the final packet to send before closing
   */
  public void writeAndClose(Packet packet) {
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
   * Updates the protocol state on both the decoder and encoder.
   *
   * @param state the new protocol state
   */
  public void setState(ProtocolState state) {
    decoder().setState(state);
    encoder().setState(state);
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
  // Pipeline accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the underlying Netty channel.
   *
   * @return the channel
   */
  public Channel channel() {
    return channel;
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
