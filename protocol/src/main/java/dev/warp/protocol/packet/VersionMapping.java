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

import java.util.Objects;

/**
 * Binds a packet ID to a protocol version range for registry registration.
 *
 * <p>A mapping declares: "this packet uses ID {@code packetId} starting from {@code minVersion}."
 * When multiple mappings are provided for a single packet, each overrides the previous one for
 * protocol versions at or above its {@code minVersion}. This range-based approach (proven by
 * Velocity and Gate) compactly represents ID changes across Minecraft versions.
 *
 * <p>Example registration for a packet whose ID changes across versions:
 *
 * <pre>{@code
 * VersionMapping.map(0x00, MINECRAFT_1_7_2),  // ID 0 from 1.7.2
 * VersionMapping.map(0x0B, MINECRAFT_1_9),    // ID 11 from 1.9 (overrides 0)
 * VersionMapping.map(0x0C, MINECRAFT_1_12)    // ID 12 from 1.12 (overrides 11)
 * }</pre>
 *
 * @param packetId the numeric packet ID on the wire
 * @param minVersion the first protocol version this ID applies to (inclusive)
 */
public record VersionMapping(int packetId, ProtocolVersion minVersion) {

  /** Validates that packetId is non-negative and minVersion is non-null. */
  public VersionMapping {
    if (packetId < 0) {
      throw new IllegalArgumentException("packetId must be non-negative: " + packetId);
    }
    Objects.requireNonNull(minVersion, "minVersion");
  }

  /**
   * Creates a version mapping.
   *
   * @param packetId the numeric packet ID on the wire
   * @param minVersion the first protocol version this ID applies to
   * @return a new mapping
   */
  public static VersionMapping map(int packetId, ProtocolVersion minVersion) {
    return new VersionMapping(packetId, minVersion);
  }
}
