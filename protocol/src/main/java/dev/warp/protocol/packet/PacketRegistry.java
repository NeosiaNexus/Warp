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
package dev.warp.protocol.packet;

import dev.warp.protocol.ProtocolVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Immutable lookup table mapping (protocol version, packet ID) to a codec and packet class.
 *
 * <p>A registry is scoped to a single (state, direction) pair — for example, "Login Serverbound."
 * Instances are created via the {@link Builder}, which accepts version-range registrations and
 * pre-computes per-protocol-ID arrays for O(1) hot-path lookups.
 *
 * <p>When {@link #lookup(ProtocolVersion, int)} returns {@code null}, the packet ID is unregistered
 * and the caller should treat the frame as opaque bytes (blind forwarding).
 */
public final class PacketRegistry {

  /** Protocol ID → array of codecs indexed by packet ID. Null entries = unknown packet. */
  private final Map<Integer, @Nullable PacketCodec<?>[]> codecsByProtocol;

  /** Protocol ID → (packet class → packet ID). */
  private final Map<Integer, Map<Class<? extends Packet>, Integer>> idsByProtocol;

  private PacketRegistry(
      Map<Integer, @Nullable PacketCodec<?>[]> codecsByProtocol,
      Map<Integer, Map<Class<? extends Packet>, Integer>> idsByProtocol) {
    this.codecsByProtocol = codecsByProtocol;
    this.idsByProtocol = idsByProtocol;
  }

  // ---------------------------------------------------------------------------
  // Lookup (hot path)
  // ---------------------------------------------------------------------------

  /**
   * Looks up the codec for a packet ID at the given protocol version.
   *
   * <p>Returns {@code null} if the packet ID is not registered for this version, which signals the
   * caller to use blind forwarding (raw {@code ByteBuf} passthrough).
   *
   * @param version the protocol version of the connection
   * @param packetId the packet ID read from the wire
   * @return the codec, or {@code null} for unregistered (blind-forward) packets
   */
  @SuppressWarnings("NullAway") // array elements are nullable by design
  public @Nullable PacketCodec<?> lookup(ProtocolVersion version, int packetId) {
    @Nullable PacketCodec<?>[] codecs = codecsByProtocol.get(version.protocol());
    if (codecs == null || packetId < 0 || packetId >= codecs.length) {
      return null;
    }
    return codecs[packetId];
  }

  /**
   * Returns the wire packet ID for the given packet class at the given protocol version.
   *
   * @param version the protocol version of the connection
   * @param type the packet class to look up
   * @return the packet ID
   * @throws IllegalArgumentException if the packet type is not registered for this version
   */
  public int packetId(ProtocolVersion version, Class<? extends Packet> type) {
    Map<Class<? extends Packet>, Integer> ids = idsByProtocol.get(version.protocol());
    if (ids == null) {
      throw new IllegalArgumentException(
          "No packets registered for protocol " + version.protocol());
    }
    Integer id = ids.get(type);
    if (id == null) {
      throw new IllegalArgumentException(
          type.getSimpleName() + " is not registered for " + version);
    }
    return id;
  }

  // ---------------------------------------------------------------------------
  // Builder
  // ---------------------------------------------------------------------------

  /**
   * Creates a new builder for constructing a {@link PacketRegistry}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Collects packet registrations and builds an immutable {@link PacketRegistry}.
   *
   * <p>Registrations use version-range mappings: each {@link VersionMapping} declares a packet ID
   * that applies from its {@code minVersion} until the next mapping overrides it. This range-based
   * approach (proven by Velocity and Gate) compactly handles ID changes across Minecraft versions.
   */
  public static final class Builder {

    private final List<Registration<?>> registrations = new ArrayList<>();

    Builder() {}

    /**
     * Registers a packet type with its codec and version mappings.
     *
     * <p>Mappings should be in ascending version order. For a given protocol version, the last
     * mapping whose {@code minVersion} is at or below that version determines the packet ID.
     *
     * @param type the packet class
     * @param codec the codec for reading/writing this packet
     * @param mappings one or more version-to-ID mappings, in ascending version order
     * @param <T> the packet type
     * @return this builder
     */
    public <T extends Packet> Builder register(
        Class<T> type, PacketCodec<T> codec, VersionMapping... mappings) {
      if (mappings.length == 0) {
        throw new IllegalArgumentException("At least one VersionMapping is required");
      }
      for (int i = 1; i < mappings.length; i++) {
        if (mappings[i].minVersion().protocol() <= mappings[i - 1].minVersion().protocol()) {
          throw new IllegalArgumentException("VersionMappings must be in ascending version order");
        }
      }
      registrations.add(new Registration<>(type, codec, mappings));
      return this;
    }

    /**
     * Builds an immutable registry from all registered packets.
     *
     * @return the built registry
     */
    public PacketRegistry build() {
      // Working maps: protocolId → (packetId → Entry)
      Map<Integer, Map<Integer, Entry>> workingCodecs = new HashMap<>();
      // Working maps: protocolId → (class → packetId)
      Map<Integer, Map<Class<? extends Packet>, Integer>> workingIds = new HashMap<>();

      List<ProtocolVersion> allVersions = ProtocolVersion.values();

      for (Registration<?> reg : registrations) {
        for (ProtocolVersion version : allVersions) {
          int packetId = resolvePacketId(version, reg.mappings());
          if (packetId < 0) {
            continue; // no mapping applies to this version
          }
          int protocol = version.protocol();
          Map<Integer, Entry> protocolCodecs =
              workingCodecs.computeIfAbsent(protocol, k -> new HashMap<>());
          Entry existing = protocolCodecs.get(packetId);
          if (existing != null && !existing.type().equals(reg.type())) {
            throw new IllegalStateException(
                "Packet ID 0x"
                    + Integer.toHexString(packetId)
                    + " already registered to "
                    + existing.type().getSimpleName()
                    + " for protocol "
                    + protocol
                    + ", cannot register "
                    + reg.type().getSimpleName());
          }
          protocolCodecs.put(packetId, new Entry(reg.type(), reg.codec()));
          workingIds.computeIfAbsent(protocol, k -> new HashMap<>()).put(reg.type(), packetId);
        }
      }

      // Convert working maps to array-indexed form
      Map<Integer, @Nullable PacketCodec<?>[]> codecArrays = new HashMap<>();
      for (var entry : workingCodecs.entrySet()) {
        int protocol = entry.getKey();
        Map<Integer, Entry> idToEntry = entry.getValue();
        int maxId = idToEntry.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        @Nullable PacketCodec<?>[] array = new PacketCodec<?>[maxId + 1];
        for (var e : idToEntry.entrySet()) {
          array[e.getKey()] = e.getValue().codec();
        }
        codecArrays.put(protocol, array);
      }

      // Make ID maps unmodifiable
      Map<Integer, Map<Class<? extends Packet>, Integer>> finalIds = new HashMap<>();
      for (var entry : workingIds.entrySet()) {
        finalIds.put(entry.getKey(), Map.copyOf(entry.getValue()));
      }

      return new PacketRegistry(Map.copyOf(codecArrays), Map.copyOf(finalIds));
    }

    /**
     * Finds the active packet ID for a given version from a sorted array of mappings. Returns -1 if
     * no mapping applies (the packet does not exist at this version).
     */
    private static int resolvePacketId(ProtocolVersion version, VersionMapping[] mappings) {
      int packetId = -1;
      for (VersionMapping mapping : mappings) {
        if (version.isAtLeast(mapping.minVersion())) {
          packetId = mapping.packetId();
        } else {
          break; // mappings are ascending — no further match possible
        }
      }
      return packetId;
    }
  }

  // ---------------------------------------------------------------------------
  // Internal records
  // ---------------------------------------------------------------------------

  @SuppressWarnings("ArrayRecordComponent")
  private record Registration<T extends Packet>(
      Class<T> type, PacketCodec<T> codec, VersionMapping[] mappings) {}

  private record Entry(Class<? extends Packet> type, PacketCodec<?> codec) {}
}
