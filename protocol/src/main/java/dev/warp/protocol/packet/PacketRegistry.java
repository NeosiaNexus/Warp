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
 * pre-computes per-protocol arrays for O(1), allocation-free hot-path lookups.
 *
 * <p>A registration is either <b>decoded</b> ({@link Builder#register}) — the proxy inspects the
 * packet when it receives it — or <b>encode-only</b> ({@link Builder#registerEncodeOnly}) — the
 * proxy can send it but forwards received copies untouched. Encoding and decoding needs differ: the
 * proxy must be able to <em>write</em> a system chat message, but has no reason to <em>read</em>
 * the thousands a busy server sends. Keeping the decoded set minimal is what lets every other frame
 * be forwarded in its original (possibly compressed) form.
 *
 * <p>When {@link #lookup(ProtocolVersion, int)} returns {@code null}, the caller must treat the
 * frame as opaque bytes (blind forwarding).
 */
public final class PacketRegistry {

  /** Protocol number → codecs indexed by packet ID, decoded registrations only. */
  private final @Nullable PacketCodec<?>[] @Nullable [] decodeByProtocol;

  /** Protocol number → (packet class → packet ID), all registrations. */
  private final @Nullable Map<Class<? extends Packet>, Integer>[] idsByProtocol;

  private PacketRegistry(
      @Nullable PacketCodec<?>[] @Nullable [] decodeByProtocol,
      @Nullable Map<Class<? extends Packet>, Integer>[] idsByProtocol) {
    this.decodeByProtocol = decodeByProtocol;
    this.idsByProtocol = idsByProtocol;
  }

  // ---------------------------------------------------------------------------
  // Lookup (hot path)
  // ---------------------------------------------------------------------------

  /**
   * Looks up the codec used to decode a received packet ID at the given protocol version.
   *
   * <p>Returns {@code null} if the packet ID is not registered for decoding at this version —
   * either unknown or encode-only — which signals the caller to forward the frame untouched.
   *
   * @param version the protocol version of the connection
   * @param packetId the packet ID read from the wire
   * @return the codec, or {@code null} for frames the proxy does not inspect
   */
  public @Nullable PacketCodec<?> lookup(ProtocolVersion version, int packetId) {
    int protocol = version.protocol();
    if (protocol < 0 || protocol >= decodeByProtocol.length) {
      return null;
    }
    @Nullable PacketCodec<?>[] codecs = decodeByProtocol[protocol];
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
    int protocol = version.protocol();
    Map<Class<? extends Packet>, Integer> ids =
        protocol >= 0 && protocol < idsByProtocol.length ? idsByProtocol[protocol] : null;
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
     * Registers a packet the proxy decodes when it receives it, and can encode.
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
      return add(type, codec, mappings, true);
    }

    /**
     * Registers a packet the proxy can encode but never decodes on receipt: received copies are
     * forwarded as opaque frames, exactly like unregistered packets.
     *
     * @param type the packet class
     * @param codec the codec for writing this packet
     * @param mappings one or more version-to-ID mappings, in ascending version order
     * @param <T> the packet type
     * @return this builder
     */
    public <T extends Packet> Builder registerEncodeOnly(
        Class<T> type, PacketCodec<T> codec, VersionMapping... mappings) {
      return add(type, codec, mappings, false);
    }

    private <T extends Packet> Builder add(
        Class<T> type, PacketCodec<T> codec, VersionMapping[] mappings, boolean decoded) {
      if (mappings.length == 0) {
        throw new IllegalArgumentException("At least one VersionMapping is required");
      }
      for (int i = 1; i < mappings.length; i++) {
        if (mappings[i].minVersion().protocol() <= mappings[i - 1].minVersion().protocol()) {
          throw new IllegalArgumentException("VersionMappings must be in ascending version order");
        }
      }
      registrations.add(new Registration<>(type, codec, mappings, decoded));
      return this;
    }

    /**
     * Builds an immutable registry from all registered packets.
     *
     * @return the built registry
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // generic array creation
    public PacketRegistry build() {
      List<ProtocolVersion> allVersions = ProtocolVersion.values();
      int size = allVersions.stream().mapToInt(ProtocolVersion::protocol).max().orElse(-1) + 1;

      // Working maps: protocol → (packetId → Entry) and protocol → (class → packetId).
      Map<Integer, Map<Integer, Entry>> workingCodecs = new HashMap<>();
      Map<Integer, Map<Class<? extends Packet>, Integer>> workingIds = new HashMap<>();

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
          protocolCodecs.put(packetId, new Entry(reg.type(), reg.codec(), reg.decoded()));
          workingIds.computeIfAbsent(protocol, k -> new HashMap<>()).put(reg.type(), packetId);
        }
      }

      // Decode tables: array-indexed by protocol, then by packet ID. Encode-only entries stay null.
      @Nullable PacketCodec<?>[][] decodeByProtocol = new PacketCodec<?>[Math.max(size, 0)][];
      for (var entry : workingCodecs.entrySet()) {
        int maxId = entry.getValue().keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        @Nullable PacketCodec<?>[] codecs = new PacketCodec<?>[maxId + 1];
        for (var e : entry.getValue().entrySet()) {
          if (e.getValue().decoded()) {
            codecs[e.getKey()] = e.getValue().codec();
          }
        }
        decodeByProtocol[entry.getKey()] = codecs;
      }

      // Encode tables: unmodifiable maps, array-indexed by protocol.
      @Nullable Map<Class<? extends Packet>, Integer>[] idsByProtocol = new Map[Math.max(size, 0)];
      for (var entry : workingIds.entrySet()) {
        idsByProtocol[entry.getKey()] = Map.copyOf(entry.getValue());
      }

      return new PacketRegistry(decodeByProtocol, idsByProtocol);
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
      Class<T> type, PacketCodec<T> codec, VersionMapping[] mappings, boolean decoded) {}

  private record Entry(Class<? extends Packet> type, PacketCodec<?> codec, boolean decoded) {}
}
