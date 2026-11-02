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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.ProtocolVersion;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Reference wire bytes from {@code audience-packets.txt}, written by node-minecraft-protocol: the
 * packets Warp writes or reads for Adventure audiences, per protocol version.
 */
final class AudiencePacketFixtures {

  private static final Map<Integer, Map<String, byte[]>> FIXTURES =
      SwitchPacketFixtures.load("audience-packets.txt");

  private AudiencePacketFixtures() {}

  /** Returns the versions that have a {@code packet} fixture, oldest first. */
  static Stream<ProtocolVersion> versionsWith(String packet) {
    return versions().stream()
        .filter(version -> FIXTURES.getOrDefault(version.protocol(), Map.of()).containsKey(packet));
  }

  /** Returns the protocol versions the fixtures cover, oldest first. */
  static List<ProtocolVersion> versions() {
    return FIXTURES.keySet().stream()
        .map(protocol -> Objects.requireNonNull(ProtocolVersion.byProtocolId(protocol)))
        .toList();
  }

  /**
   * Returns the body of {@code packet} at {@code version}, without its packet ID.
   *
   * @throws IllegalArgumentException if there is no such fixture
   */
  static byte[] bytes(ProtocolVersion version, String packet) {
    byte[] bytes = FIXTURES.getOrDefault(version.protocol(), Map.of()).get(packet);
    if (bytes == null) {
      throw new IllegalArgumentException("No fixture " + packet + " for " + version);
    }
    return bytes.clone();
  }
}
