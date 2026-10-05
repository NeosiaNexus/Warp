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
import dev.warp.protocol.compress.DeflatePeek;
import dev.warp.protocol.compress.FrameDecompressor;
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
 * Turns complete wire frames into typed {@link Packet}s for the few packets the proxy inspects, and
 * passes every other frame through <b>untouched — compressed or not</b>.
 *
 * <p>Sits directly after {@link FrameDecoder}, whose frames carry their length prefix. When the
 * connection uses compression ({@link #enableCompression}), this handler also owns decompression,
 * because the decision it exists to make — inspect or forward? — needs the packet ID, and a
 * compressed frame hides the ID inside its zlib stream.
 *
 * <h3>Per-frame decision</h3>
 *
 * <ol>
 *   <li>Read the packet ID: directly for uncompressed frames; with {@link DeflatePeek} for
 *       compressed ones (no inflation), or by inflating when the frame must be verified first.
 *   <li>If the registry has a decode codec for that ID in the current state and version, decode it
 *       (inflating if needed) and emit the {@link Packet}.
 *   <li>Otherwise emit the <b>original frame</b> as a {@link ByteBuf} — length prefix, Data Length
 *       and payload exactly as received — so the relay can write it to the other connection
 *       verbatim. No inflate, no deflate, no copy, no allocation.
 * </ol>
 *
 * <h3>Trusted vs. untrusted peers</h3>
 *
 * <p>Frames from a backend are peeked and forwarded without inflation (see {@link
 * #enableCompression}). Frames from a player are always inflated before being forwarded, so the
 * proxy — not the backend — absorbs malformed or lying streams, and the decompression budget
 * applies to them. Serverbound compressed traffic is rare and small, so this costs little; the
 * original compressed bytes are still forwarded when the backend accepts them, skipping the
 * re-deflate.
 *
 * <h3>Other guarantees</h3>
 *
 * <ul>
 *   <li><b>Boundary validation</b> — a decoded packet must consume its whole payload.
 *   <li><b>Dead-channel guard</b> — frames still queued after the channel closed are dropped.
 *   <li><b>O(1) lookup</b> — packet ID → codec is a plain array access, recomputed only on state or
 *       version changes.
 *   <li><b>Rich exception context</b> — packet ID, packet class and protocol state in every error.
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

  /** Registry for the current (state, direction) pair, recomputed on {@link #setState}. */
  private PacketRegistry registry;

  /** Compression state, or {@code null} while the connection is uncompressed. */
  private @Nullable FrameDecompressor decompressor;

  /** Whether compressed frames are peeked (trusted peer) rather than inflated before forwarding. */
  private boolean peekCompressedFrames;

  /**
   * The last frame forwarded after being inflated anyway, and its inflated packet bytes — kept
   * until the next frame so a relay that must re-encode it does not inflate it a second time.
   */
  private @Nullable ByteBuf inflatedFrame;

  private @Nullable ByteBuf inflatedPacket;

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
  protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
    releaseInflated(); // a frame that was not re-encoded by now never will be

    // Dead-channel guard: a closed connection may still have queued frames; drop them.
    if (!ctx.channel().isActive()) {
      return;
    }

    VarInt.skip(frame); // outer length prefix — the frame is complete by construction
    FrameDecompressor inflater = decompressor;
    if (inflater == null) {
      decodeUncompressed(frame, out);
      return;
    }

    int dataLength = VarInt.read(frame);
    if (dataLength == 0) {
      decodeUncompressed(frame, out);
      return;
    }

    inflater.checkHeader(dataLength, frame.readableBytes());
    if (peekCompressedFrames) {
      int packetId = inflater.peekPacketId(frame);
      if (packetId != DeflatePeek.UNKNOWN && registry.lookup(version, packetId) == null) {
        forward(frame, out);
        return;
      }
    }

    ByteBuf packet = inflater.inflate(ctx.alloc(), frame, dataLength);
    boolean handedOver = false;
    try {
      int packetId = readPacketId(packet);
      PacketCodec<?> codec = registry.lookup(version, packetId);
      if (codec == null) {
        // Verified, but not inspected: forward the original compressed bytes, and keep the
        // inflated ones in case the other connection cannot take the frame as it is.
        inflatedFrame = frame;
        inflatedPacket = packet.readerIndex(0);
        handedOver = true;
        forward(frame, out);
        return;
      }
      out.add(decodePacket(codec, packetId, packet));
    } finally {
      if (!handedOver) {
        packet.release();
      }
    }
  }

  /**
   * Hands over the inflated packet bytes of {@code frame}, if this decoder inflated that exact
   * frame before forwarding it. The identity check means a mismatch can only cost a second inflate,
   * never deliver another frame's bytes.
   *
   * <p>Only valid while {@code frame} is being dispatched: the bytes are dropped when the next
   * frame is decoded. The proxy forwards synchronously from its pipeline tail, so this always holds
   * there.
   *
   * @param frame a frame this decoder emitted
   * @return {@code [Packet ID][Payload]} owned by the caller, or {@code null}
   */
  public @Nullable ByteBuf takeInflatedPacket(ByteBuf frame) {
    ByteBuf packet = inflatedPacket;
    if (packet == null || frame != inflatedFrame) {
      return null;
    }
    inflatedFrame = null;
    inflatedPacket = null;
    return packet;
  }

  private void releaseInflated() {
    ByteBuf packet = inflatedPacket;
    if (packet != null) {
      inflatedFrame = null;
      inflatedPacket = null;
      packet.release();
    }
  }

  /** Decodes or forwards a frame whose packet bytes start at the reader index, uncompressed. */
  private void decodeUncompressed(ByteBuf frame, List<Object> out) {
    int packetId = readPacketId(frame);
    PacketCodec<?> codec = registry.lookup(version, packetId);
    if (codec == null) {
      forward(frame, out);
      return;
    }
    out.add(decodePacket(codec, packetId, frame));
  }

  /** Emits the complete original frame, length prefix included, for verbatim forwarding. */
  private static void forward(ByteBuf frame, List<Object> out) {
    frame.readerIndex(0);
    // The base class releases the input after decode(); the retain hands ownership downstream.
    out.add(frame.retain());
  }

  private static int readPacketId(ByteBuf buf) {
    int packetId = VarInt.read(buf);
    if (packetId < 0) {
      throw NEGATIVE_PACKET_ID;
    }
    return packetId;
  }

  private Packet decodePacket(PacketCodec<?> codec, int packetId, ByteBuf buf) {
    Packet packet;
    try {
      packet = codec.decode(buf, version);
    } catch (Exception e) {
      throw new DecoderException(
          "Failed to decode packet 0x" + Integer.toHexString(packetId) + " in state " + state, e);
    }
    // Boundary validation: trailing bytes mean a buggy codec or a malformed packet — continuing
    // would silently desync the stream. Velocity does not perform this check.
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
    return packet;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    releaseInflated();
    FrameDecompressor inflater = decompressor;
    if (inflater != null) {
      decompressor = null;
      inflater.close();
    }
    super.handlerRemoved(ctx);
  }

  // ---------------------------------------------------------------------------
  // State management — called from the event loop thread during transitions
  // ---------------------------------------------------------------------------

  /**
   * Switches this connection to compressed frames ({@code [Data Length][payload]} after the length
   * prefix), effective from the next frame.
   *
   * @param decompressor validates and inflates compressed payloads; closed with this handler
   * @param peekCompressedFrames {@code true} to forward uninspected compressed frames without
   *     inflating them — only for trusted peers (backends); {@code false} to inflate, and thereby
   *     verify, every compressed frame before forwarding it
   */
  public void enableCompression(FrameDecompressor decompressor, boolean peekCompressedFrames) {
    FrameDecompressor previous = this.decompressor;
    this.decompressor = decompressor;
    this.peekCompressedFrames = peekCompressedFrames;
    if (previous != null) {
      previous.close();
    }
  }

  /**
   * Returns the decompressor of this connection, if compression is enabled.
   *
   * @return the decompressor, or {@code null} while uncompressed
   */
  public @Nullable FrameDecompressor decompressor() {
    return decompressor;
  }

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
