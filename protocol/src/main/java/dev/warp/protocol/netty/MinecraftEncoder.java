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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import org.jspecify.annotations.Nullable;

/**
 * Encodes typed {@link Packet} objects into Minecraft protocol wire format: {@code [VarInt: Packet
 * ID][Payload]}.
 *
 * <p>This handler sits before {@link CompressionEncoder} (or {@link FrameEncoder}) in the outbound
 * pipeline. It only handles {@link Packet} instances — raw {@link ByteBuf} messages from blind
 * forwarding naturally bypass this handler via Netty's {@link
 * MessageToByteEncoder#acceptOutboundMessage type dispatch}, producing zero overhead on the hot
 * path.
 *
 * <h3>Blind forwarding bypass</h3>
 *
 * <p>When the inbound {@link MinecraftDecoder} encounters an unregistered packet, it fires the raw
 * {@link ByteBuf} through the pipeline. On the outbound side, this encoder's generic type parameter
 * ({@code Packet}) causes {@link #acceptOutboundMessage} to return {@code false} for {@link
 * ByteBuf} objects, so they skip encoding entirely and flow directly to the compression/framing
 * layer. No conditional check, no wrapper — pure Netty type dispatch.
 *
 * <h3>Improvements over Velocity</h3>
 *
 * <ul>
 *   <li><b>Cached registry</b> — same optimization as {@link MinecraftDecoder}; the {@link
 *       PacketRegistry} is cached and recomputed only on state transitions.
 *   <li><b>Rich exception context</b> — encoder errors include the packet class name, packet ID
 *       (hex), and current state, making production issues immediately diagnosable.
 *   <li><b>No size hints</b> — Velocity's optional {@code encodeSizeHint()} is often inaccurate.
 *       Warp relies on Netty's efficient buffer growth strategy. Per-codec hints can be added later
 *       if profiling justifies the API complexity.
 * </ul>
 */
public final class MinecraftEncoder extends MessageToByteEncoder<Packet> {

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
   * Creates a new encoder for the given direction, starting in the specified state and version.
   *
   * @param direction the packet direction this encoder handles (immutable for the connection
   *     lifetime — {@link PacketDirection#CLIENTBOUND CLIENTBOUND} for proxy-to-client, {@link
   *     PacketDirection#SERVERBOUND SERVERBOUND} for proxy-to-server)
   * @param version the initial protocol version (updated via {@link #setVersion} after handshake)
   * @param state the initial protocol state (updated via {@link #setState} during transitions)
   */
  public MinecraftEncoder(PacketDirection direction, ProtocolVersion version, ProtocolState state) {
    this.direction = direction;
    this.version = version;
    this.state = state;
    this.registry = StateRegistry.get(state, direction);
  }

  // ---------------------------------------------------------------------------
  // Encode
  // ---------------------------------------------------------------------------

  @Override
  protected void encode(ChannelHandlerContext ctx, Packet packet, ByteBuf out) {
    int packetId;
    try {
      packetId = registry.packetId(version, packet.getClass());
    } catch (IllegalArgumentException e) {
      throw new EncoderException(
          "Cannot encode "
              + packet.getClass().getSimpleName()
              + " in state "
              + state
              + " for "
              + version,
          e);
    }

    VarInt.write(out, packetId);

    // The codec is guaranteed non-null because packetId() succeeded — the registry
    // has a codec for every (version, class) pair it knows about.
    @SuppressWarnings("unchecked")
    PacketCodec<Packet> codec = (PacketCodec<Packet>) lookupCodecOrThrow(packetId);

    try {
      codec.encode(packet, out, version);
    } catch (Exception e) {
      throw new EncoderException(
          "Failed to encode "
              + packet.getClass().getSimpleName()
              + " (0x"
              + Integer.toHexString(packetId)
              + ") in state "
              + state,
          e);
    }
  }

  /**
   * Looks up the codec for a packet ID that was just resolved by {@link PacketRegistry#packetId}.
   * The codec must exist — if it does not, the registry is internally inconsistent.
   */
  private PacketCodec<?> lookupCodecOrThrow(int packetId) {
    @Nullable PacketCodec<?> codec = registry.lookup(version, packetId);
    if (codec == null) {
      throw new EncoderException(
          "Registry inconsistency: packetId() resolved 0x"
              + Integer.toHexString(packetId)
              + " but lookup() returned null for "
              + version
              + " in state "
              + state);
    }
    return codec;
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
   * PacketRegistry#lookup} and {@link PacketRegistry#packetId} at encode time.
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
   * Returns the packet direction this encoder handles.
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
