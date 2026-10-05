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
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;

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
 *   <li><b>I/O</b> — write, flush, close
 *   <li><b>Pipeline access</b> — state and version transitions on decoder/encoder
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

  private @Nullable SessionHandler sessionHandler;

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
    // Always log — do not gate on isActive() which can swallow critical errors.
    logger.error("Exception in pipeline for {}", channel.remoteAddress(), cause);
    if (ctx.channel().isActive()) {
      var _ = ctx.close();
    }
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
   * Writes a raw {@link ByteBuf} to the channel without flushing (blind forwarding hot path).
   *
   * <p>The buffer bypasses {@link dev.warp.protocol.netty.MinecraftEncoder MinecraftEncoder} via
   * Netty's type dispatch — {@code MessageToByteEncoder<Packet>} only handles {@code Packet}
   * instances. Zero allocation, zero deserialization.
   *
   * @param buf the raw packet buffer (caller transfers ownership)
   */
  public void writeBlind(ByteBuf buf) {
    if (channel.isActive()) {
      var _ = channel.write(buf, channel.voidPromise());
    } else {
      buf.release();
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
    MinecraftDecoder decoder = channel.pipeline().get(MinecraftDecoder.class);
    if (decoder == null) {
      throw new IllegalStateException("MinecraftDecoder not found in pipeline");
    }
    return decoder;
  }

  /**
   * Returns the {@link MinecraftEncoder} from the pipeline.
   *
   * @return the encoder
   * @throws IllegalStateException if the encoder is not in the pipeline
   */
  public MinecraftEncoder encoder() {
    MinecraftEncoder encoder = channel.pipeline().get(MinecraftEncoder.class);
    if (encoder == null) {
      throw new IllegalStateException("MinecraftEncoder not found in pipeline");
    }
    return encoder;
  }
}
