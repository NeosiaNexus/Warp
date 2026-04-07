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
package dev.warp.protocol.netty;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.jspecify.annotations.Nullable;

/**
 * Decodes framed Minecraft protocol buffers into typed {@link Packet} objects, or passes
 * unregistered packets through as raw {@link ByteBuf} for blind forwarding.
 *
 * <p>This handler sits after {@link FrameDecoder} and (optionally) {@link CompressionDecoder} in
 * the inbound pipeline. Each input {@link ByteBuf} contains a complete, decompressed packet: {@code
 * [VarInt: Packet ID][Payload]}.
 *
 * <h3>Blind forwarding (zero allocation)</h3>
 *
 * <p>When a packet ID has no registered codec (i.e. {@link PacketRegistry#lookup} returns {@code
 * null}), the entire buffer — including the packet ID bytes — is forwarded as a retained slice. No
 * wrapper object is allocated. Downstream, {@link MinecraftEncoder} extends {@link
 * io.netty.handler.codec.MessageToByteEncoder MessageToByteEncoder&lt;Packet&gt;} and will
 * naturally bypass raw {@link ByteBuf} messages via Netty's type dispatch. This gives zero-cost
 * passthrough for the ~90% of PLAY-state packets the proxy does not inspect.
 *
 * <h3>Improvements over Velocity</h3>
 *
 * <ul>
 *   <li><b>Boundary validation</b> — after every successful decode, the handler verifies that the
 *       codec consumed <em>all</em> bytes in the buffer. Trailing bytes indicate a codec bug or
 *       malformed packet and throw immediately, preventing silent stream corruption.
 *   <li><b>Dead-channel guard</b> — skips decode if the channel is no longer active, avoiding
 *       wasted CPU on stale connections (same defense as {@link FrameDecoder}).
 *   <li><b>Zero-allocation blind forwarding</b> — Velocity wraps unknown packets in an object; Warp
 *       fires the raw {@link ByteBuf} directly, producing zero garbage on the hot path.
 *   <li><b>Cached registry</b> — the {@link PacketRegistry} reference is cached and recomputed only
 *       on state transitions, eliminating two {@code HashMap.get()} calls per packet.
 *   <li><b>Rich exception context</b> — every error includes the packet ID (hex), packet class
 *       name, and current protocol state for fast production debugging.
 * </ul>
 */
public final class MinecraftDecoder extends MessageToMessageDecoder<ByteBuf> {

  // ---------------------------------------------------------------------------
  // Cached error — zero-alloc on the hot error path
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NEGATIVE_PACKET_ID =
      new DecoderException("Packet ID decoded to a negative value") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  // ---------------------------------------------------------------------------
  // Connection state — mutated only from the event loop thread
  // ---------------------------------------------------------------------------

  private final PacketDirection direction;
  private ProtocolState state;
  private ProtocolVersion version;

  /**
   * Cached registry for the current (state, direction) pair. Recomputed on {@link #setState} to
   * avoid {@link StateRegistry#get} + direction lookup on every packet.
   */
  private PacketRegistry registry;

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  /**
   * Creates a new decoder for the given direction, starting in the specified state and version.
   *
   * @param direction the packet direction this decoder handles (immutable for the connection
   *     lifetime — {@link PacketDirection#SERVERBOUND SERVERBOUND} for client-to-proxy, {@link
   *     PacketDirection#CLIENTBOUND CLIENTBOUND} for server-to-proxy)
   * @param version the initial protocol version (updated via {@link #setVersion} after handshake)
   * @param state the initial protocol state (updated via {@link #setState} during transitions)
   */
  public MinecraftDecoder(PacketDirection direction, ProtocolVersion version, ProtocolState state) {
    this.direction = direction;
    this.version = version;
    this.state = state;
    this.registry = StateRegistry.get(state, direction);
  }

  // ---------------------------------------------------------------------------
  // Decode
  // ---------------------------------------------------------------------------

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) {
    // Dead-channel guard: short-circuit if the connection closed but a queued buffer
    // is still being drained. Unlike FrameDecoder (which must skip cumulated bytes),
    // MessageToMessageDecoder receives discrete messages — simply returning is safe;
    // the framework releases the input buffer.
    if (!ctx.channel().isActive()) {
      return;
    }

    int readerStart = buf.readerIndex();
    int packetId = VarInt.read(buf);

    if (packetId < 0) {
      throw NEGATIVE_PACKET_ID;
    }

    // O(1) lookup — registry is cached, version comparison is array-indexed.
    @Nullable PacketCodec<?> codec = registry.lookup(version, packetId);

    if (codec == null) {
      // Blind forwarding: rewind to include the packet ID bytes, then forward the
      // entire buffer as a zero-copy retained slice. No wrapper object is allocated.
      buf.readerIndex(readerStart);
      out.add(buf.readRetainedSlice(buf.readableBytes()));
      return;
    }

    // Deserialize the known packet.
    Packet packet;
    try {
      packet = codec.decode(buf, version);
    } catch (Exception e) {
      throw new DecoderException(
          "Failed to decode packet 0x" + Integer.toHexString(packetId) + " in state " + state, e);
    }

    // Boundary validation: verify the codec consumed every byte. Trailing bytes mean
    // either a buggy codec or a malformed packet — continuing would silently desync
    // the stream. Velocity does not perform this check.
    if (buf.isReadable()) {
      throw new DecoderException(
          "Packet 0x"
              + Integer.toHexString(packetId)
              + " ("
              + packet.getClass().getSimpleName()
              + ") has "
              + buf.readableBytes()
              + " trailing bytes in state "
              + state);
    }

    out.add(packet);
  }

  // ---------------------------------------------------------------------------
  // State management — called from the event loop thread during transitions
  // ---------------------------------------------------------------------------

  /**
   * Updates the protocol state and recomputes the cached registry.
   *
   * <p>Must be called from the channel's event loop thread (guaranteed by Netty's threading model
   * for all pipeline operations).
   *
   * @param state the new protocol state
   */
  public void setState(ProtocolState state) {
    this.state = state;
    this.registry = StateRegistry.get(state, direction);
  }

  /**
   * Updates the protocol version.
   *
   * <p>The cached registry is <em>not</em> recomputed because {@link PacketRegistry} spans all
   * versions for a given (state, direction) pair — the version is passed to {@link
   * PacketRegistry#lookup} at decode time.
   *
   * @param version the negotiated protocol version
   */
  public void setVersion(ProtocolVersion version) {
    this.version = version;
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the packet direction this decoder handles.
   *
   * @return the packet direction
   */
  public PacketDirection direction() {
    return direction;
  }

  /**
   * Returns the current protocol state.
   *
   * @return the current state
   */
  public ProtocolState state() {
    return state;
  }

  /**
   * Returns the current protocol version.
   *
   * @return the current version
   */
  public ProtocolVersion version() {
    return version;
  }
}
