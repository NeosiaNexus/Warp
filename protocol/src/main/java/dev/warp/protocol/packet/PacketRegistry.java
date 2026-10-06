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
 * <p>A registration says how received copies of a packet are read, and whether the proxy can write
 * it:
 *
 * <ul>
 *   <li><b>decoded</b> ({@link Builder#register}): the proxy inspects the packet when it receives
 *       it, and can encode it;
 *   <li><b>watched</b> ({@link Builder#registerWatched}): the proxy reads a few fields of each
 *       received copy in place and forwards the frame untouched; it can encode the packet when the
 *       registration has a codec;
 *   <li><b>encode-only</b> ({@link Builder#registerEncodeOnly}): the proxy can send the packet but
 *       forwards received copies untouched, without reading them.
 * </ul>
 *
 * <p>Encoding and decoding needs differ: the proxy must be able to <em>write</em> a system chat
 * message, but has no reason to <em>read</em> the thousands a busy server sends. Keeping the
 * decoded set minimal is what lets every other frame be forwarded in its original (possibly
 * compressed) form.
 *
 * <p>When {@link #lookup(ProtocolVersion, int)} returns {@code null}, the caller must treat the
 * frame as opaque bytes (blind forwarding).
 */
public final class PacketRegistry {

  /** Protocol number → readers indexed by packet ID, decoded and watched registrations only. */
  private final @Nullable PacketReader[] @Nullable [] readByProtocol;

  /**
   * Protocol number → (packet class → id, and codec if the proxy writes it), every registration.
   */
  private final @Nullable Map<Class<? extends Packet>, Registered>[] typeByProtocol;

  private PacketRegistry(
      @Nullable PacketReader[] @Nullable [] readByProtocol,
      @Nullable Map<Class<? extends Packet>, Registered>[] typeByProtocol) {
    this.readByProtocol = readByProtocol;
    this.typeByProtocol = typeByProtocol;
  }

  /**
   * How a packet type is written at one protocol version.
   *
   * @param packetId the wire packet ID
   * @param codec the codec that writes the packet body
   */
  public record Encoding(int packetId, PacketCodec<?> codec) {}

  // ---------------------------------------------------------------------------
  // Lookup (hot path)
  // ---------------------------------------------------------------------------

  /**
   * Looks up how a received packet ID is read at the given protocol version: the codec that decodes
   * it, or the watch that reads it in place.
   *
   * <p>Returns {@code null} if the packet ID is neither decoded nor watched at this version
   * (unknown, or encode-only), which signals the caller to forward the frame untouched.
   *
   * @param version the protocol version of the connection
   * @param packetId the packet ID read from the wire
   * @return the reader, or {@code null} for frames the proxy does not read
   */
  public @Nullable PacketReader lookup(ProtocolVersion version, int packetId) {
    int protocol = version.protocol();
    if (protocol < 0 || protocol >= readByProtocol.length) {
      return null;
    }
    @Nullable PacketReader[] readers = readByProtocol[protocol];
    if (readers == null || packetId < 0 || packetId >= readers.length) {
      return null;
    }
    return readers[packetId];
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
    return registered(version, type).packetId();
  }

  /**
   * Returns how to write the given packet type at the given protocol version. Works for every
   * registration with a codec: decoded, encode-only, and watched with a codec.
   *
   * @param version the protocol version of the connection
   * @param type the packet class to write
   * @return its packet ID and codec
   * @throws IllegalArgumentException if the packet type is not registered for this version, or is
   *     only watched
   */
  public Encoding encoding(ProtocolVersion version, Class<? extends Packet> type) {
    Encoding encoding = registered(version, type).encoding();
    if (encoding == null) {
      throw new IllegalArgumentException(
          type.getSimpleName() + " is only watched, never written, at " + version);
    }
    return encoding;
  }

  private Registered registered(ProtocolVersion version, Class<? extends Packet> type) {
    int protocol = version.protocol();
    Map<Class<? extends Packet>, Registered> types =
        protocol >= 0 && protocol < typeByProtocol.length ? typeByProtocol[protocol] : null;
    if (types == null) {
      throw new IllegalArgumentException(
          "No packets registered for protocol " + version.protocol());
    }
    Registered registered = types.get(type);
    if (registered == null) {
      throw new IllegalArgumentException(
          type.getSimpleName() + " is not registered for " + version);
    }
    return registered;
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
   * that applies from its {@code minVersion} until the next mapping overrides it, or until the
   * {@code maxVersion} of a packet's last mapping when it has one. This range-based approach
   * (proven by Velocity and Gate) compactly handles ID changes across Minecraft versions.
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
      return add(type, mappings, codec, codec);
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
      return add(type, mappings, codec, null);
    }

    /**
     * Registers a packet the proxy watches when it receives it, and can encode: each received copy
     * is read in place by {@code watch} and forwarded untouched (see {@link PacketWatch}).
     *
     * @param type the packet class
     * @param codec the codec for writing this packet
     * @param watch reads what the proxy follows from received copies
     * @param mappings one or more version-to-ID mappings, in ascending version order
     * @param <T> the packet type
     * @return this builder
     */
    public <T extends Packet> Builder registerWatched(
        Class<T> type, PacketCodec<T> codec, PacketWatch<T> watch, VersionMapping... mappings) {
      return add(type, mappings, codec, watch);
    }

    /**
     * Registers a packet the proxy watches when it receives it but never writes: each received copy
     * is read in place by {@code watch} and forwarded untouched (see {@link PacketWatch}).
     *
     * @param type the packet class
     * @param watch reads what the proxy follows from received copies
     * @param mappings one or more version-to-ID mappings, in ascending version order
     * @param <T> the packet type
     * @return this builder
     */
    public <T extends Packet> Builder registerWatched(
        Class<T> type, PacketWatch<T> watch, VersionMapping... mappings) {
      return add(type, mappings, null, watch);
    }

    private <T extends Packet> Builder add(
        Class<T> type,
        VersionMapping[] mappings,
        @Nullable PacketCodec<T> codec,
        @Nullable PacketReader reader) {
      if (mappings.length == 0) {
        throw new IllegalArgumentException("At least one VersionMapping is required");
      }
      for (int i = 1; i < mappings.length; i++) {
        if (mappings[i].minVersion().protocol() <= mappings[i - 1].minVersion().protocol()) {
          throw new IllegalArgumentException("VersionMappings must be in ascending version order");
        }
        if (mappings[i - 1].maxVersion() != null) {
          throw new IllegalArgumentException("Only the last VersionMapping may have a maxVersion");
        }
      }
      registrations.add(new Registration<>(type, mappings, codec, reader));
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

      // Working maps: protocol → (packetId → Entry) and protocol → (class → Registered).
      Map<Integer, Map<Integer, Entry>> workingEntries = new HashMap<>();
      Map<Integer, Map<Class<? extends Packet>, Registered>> workingTypes = new HashMap<>();

      for (Registration<?> reg : registrations) {
        for (ProtocolVersion version : allVersions) {
          int packetId = resolvePacketId(version, reg.mappings());
          if (packetId < 0) {
            continue; // no mapping applies to this version
          }
          int protocol = version.protocol();
          Map<Integer, Entry> protocolEntries =
              workingEntries.computeIfAbsent(protocol, k -> new HashMap<>());
          Entry existing = protocolEntries.get(packetId);
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
          protocolEntries.put(packetId, new Entry(reg.type(), reg.reader()));
          PacketCodec<?> codec = reg.codec();
          workingTypes
              .computeIfAbsent(protocol, k -> new HashMap<>())
              .put(
                  reg.type(),
                  new Registered(packetId, codec == null ? null : new Encoding(packetId, codec)));
        }
      }

      // Read tables: array-indexed by protocol, then by packet ID. Encode-only entries stay null.
      @Nullable PacketReader[][] readByProtocol = new PacketReader[Math.max(size, 0)][];
      for (var entry : workingEntries.entrySet()) {
        int maxId = entry.getValue().keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        @Nullable PacketReader[] readers = new PacketReader[maxId + 1];
        for (var e : entry.getValue().entrySet()) {
          readers[e.getKey()] = e.getValue().reader();
        }
        readByProtocol[entry.getKey()] = readers;
      }

      // Type tables: unmodifiable maps, array-indexed by protocol.
      @Nullable Map<Class<? extends Packet>, Registered>[] typeByProtocol =
          new Map[Math.max(size, 0)];
      for (var entry : workingTypes.entrySet()) {
        typeByProtocol[entry.getKey()] = Map.copyOf(entry.getValue());
      }

      return new PacketRegistry(readByProtocol, typeByProtocol);
    }

    /**
     * Finds the active packet ID for a given version from a sorted array of mappings. Returns -1 if
     * no mapping applies (the packet does not exist at this version).
     */
    private static int resolvePacketId(ProtocolVersion version, VersionMapping[] mappings) {
      ProtocolVersion lastVersion = mappings[mappings.length - 1].maxVersion();
      if (lastVersion != null && version.isNewerThan(lastVersion)) {
        return -1; // not registered after its last version
      }
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

  /**
   * One registration: {@code codec} writes the packet ({@code null} if the proxy never does), and
   * {@code reader} reads received copies ({@code null} to forward them without reading).
   */
  @SuppressWarnings("ArrayRecordComponent")
  private record Registration<T extends Packet>(
      Class<T> type,
      VersionMapping[] mappings,
      @Nullable PacketCodec<T> codec,
      @Nullable PacketReader reader) {}

  private record Entry(Class<? extends Packet> type, @Nullable PacketReader reader) {}

  /** A packet type's ID at one protocol version, and how to write it unless it is only watched. */
  private record Registered(int packetId, @Nullable Encoding encoding) {}
}
