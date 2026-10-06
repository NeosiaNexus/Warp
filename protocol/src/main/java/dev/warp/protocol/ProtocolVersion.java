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
package dev.warp.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Immutable registry of all supported Minecraft protocol versions.
 *
 * <p>Each instance represents a unique game version string (e.g. "1.21.4") paired with its protocol
 * ID. Several game versions may share the same protocol ID (e.g. 1.20 and 1.20.1 are both 763);
 * they are distinct {@code ProtocolVersion} objects but compare as equal on the wire.
 *
 * <p>Ordering follows the protocol ID: a higher ID means a newer version.
 */
public final class ProtocolVersion implements Comparable<ProtocolVersion> {

  // ---------------------------------------------------------------------------
  // Registry internals
  // ---------------------------------------------------------------------------

  /** All registered versions in insertion (chronological) order. */
  private static final List<ProtocolVersion> VERSIONS = new ArrayList<>();

  /**
   * Protocol-ID to version lookup. When several game versions share the same protocol ID the
   * <em>first</em> registered one wins — callers that need the exact game version should iterate
   * {@link #values()} instead.
   */
  private static final Map<Integer, ProtocolVersion> BY_PROTOCOL = new HashMap<>();

  // ---------------------------------------------------------------------------
  // 1.7.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_7_2 = register(4, "1.7.2");
  public static final ProtocolVersion MINECRAFT_1_7_6 = register(5, "1.7.6");

  // ---------------------------------------------------------------------------
  // 1.8.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_8 = register(47, "1.8");

  // ---------------------------------------------------------------------------
  // 1.9.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_9 = register(107, "1.9");
  public static final ProtocolVersion MINECRAFT_1_9_1 = register(108, "1.9.1");
  public static final ProtocolVersion MINECRAFT_1_9_2 = register(109, "1.9.2");
  public static final ProtocolVersion MINECRAFT_1_9_4 = register(110, "1.9.4");

  // ---------------------------------------------------------------------------
  // 1.10.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_10 = register(210, "1.10");

  // ---------------------------------------------------------------------------
  // 1.11.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_11 = register(315, "1.11");
  public static final ProtocolVersion MINECRAFT_1_11_1 = register(316, "1.11.1");

  // ---------------------------------------------------------------------------
  // 1.12.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_12 = register(335, "1.12");
  public static final ProtocolVersion MINECRAFT_1_12_1 = register(338, "1.12.1");
  public static final ProtocolVersion MINECRAFT_1_12_2 = register(340, "1.12.2");

  // ---------------------------------------------------------------------------
  // 1.13.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_13 = register(393, "1.13");
  public static final ProtocolVersion MINECRAFT_1_13_1 = register(401, "1.13.1");
  public static final ProtocolVersion MINECRAFT_1_13_2 = register(404, "1.13.2");

  // ---------------------------------------------------------------------------
  // 1.14.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_14 = register(477, "1.14");
  public static final ProtocolVersion MINECRAFT_1_14_1 = register(480, "1.14.1");
  public static final ProtocolVersion MINECRAFT_1_14_2 = register(485, "1.14.2");
  public static final ProtocolVersion MINECRAFT_1_14_3 = register(490, "1.14.3");
  public static final ProtocolVersion MINECRAFT_1_14_4 = register(498, "1.14.4");

  // ---------------------------------------------------------------------------
  // 1.15.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_15 = register(573, "1.15");
  public static final ProtocolVersion MINECRAFT_1_15_1 = register(575, "1.15.1");
  public static final ProtocolVersion MINECRAFT_1_15_2 = register(578, "1.15.2");

  // ---------------------------------------------------------------------------
  // 1.16.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_16 = register(735, "1.16");
  public static final ProtocolVersion MINECRAFT_1_16_1 = register(736, "1.16.1");
  public static final ProtocolVersion MINECRAFT_1_16_2 = register(751, "1.16.2");
  public static final ProtocolVersion MINECRAFT_1_16_3 = register(753, "1.16.3");
  public static final ProtocolVersion MINECRAFT_1_16_4 = register(754, "1.16.4");

  // ---------------------------------------------------------------------------
  // 1.17.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_17 = register(755, "1.17");
  public static final ProtocolVersion MINECRAFT_1_17_1 = register(756, "1.17.1");

  // ---------------------------------------------------------------------------
  // 1.18.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_18 = register(757, "1.18");
  public static final ProtocolVersion MINECRAFT_1_18_2 = register(758, "1.18.2");

  // ---------------------------------------------------------------------------
  // 1.19.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_19 = register(759, "1.19");
  public static final ProtocolVersion MINECRAFT_1_19_1 = register(760, "1.19.1");
  public static final ProtocolVersion MINECRAFT_1_19_2 = register(760, "1.19.2");
  public static final ProtocolVersion MINECRAFT_1_19_3 = register(761, "1.19.3");
  public static final ProtocolVersion MINECRAFT_1_19_4 = register(762, "1.19.4");

  // ---------------------------------------------------------------------------
  // 1.20.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_20 = register(763, "1.20");
  public static final ProtocolVersion MINECRAFT_1_20_1 = register(763, "1.20.1");
  public static final ProtocolVersion MINECRAFT_1_20_2 = register(764, "1.20.2");
  public static final ProtocolVersion MINECRAFT_1_20_3 = register(765, "1.20.3");
  public static final ProtocolVersion MINECRAFT_1_20_4 = register(765, "1.20.4");
  public static final ProtocolVersion MINECRAFT_1_20_5 = register(766, "1.20.5");
  public static final ProtocolVersion MINECRAFT_1_20_6 = register(766, "1.20.6");

  // ---------------------------------------------------------------------------
  // 1.21.x
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_1_21 = register(767, "1.21");
  public static final ProtocolVersion MINECRAFT_1_21_1 = register(767, "1.21.1");
  public static final ProtocolVersion MINECRAFT_1_21_2 = register(768, "1.21.2");
  public static final ProtocolVersion MINECRAFT_1_21_3 = register(768, "1.21.3");
  public static final ProtocolVersion MINECRAFT_1_21_4 = register(769, "1.21.4");
  public static final ProtocolVersion MINECRAFT_1_21_5 = register(770, "1.21.5");
  public static final ProtocolVersion MINECRAFT_1_21_6 = register(771, "1.21.6");
  public static final ProtocolVersion MINECRAFT_1_21_7 = register(772, "1.21.7");
  public static final ProtocolVersion MINECRAFT_1_21_8 = register(772, "1.21.8");
  public static final ProtocolVersion MINECRAFT_1_21_9 = register(773, "1.21.9");
  public static final ProtocolVersion MINECRAFT_1_21_10 = register(773, "1.21.10");
  public static final ProtocolVersion MINECRAFT_1_21_11 = register(774, "1.21.11");

  // ---------------------------------------------------------------------------
  // 26.x (new Mojang versioning scheme — year.drop.hotfix)
  // ---------------------------------------------------------------------------
  public static final ProtocolVersion MINECRAFT_26_1 = register(775, "26.1");
  public static final ProtocolVersion MINECRAFT_26_1_1 = register(775, "26.1.1");
  public static final ProtocolVersion MINECRAFT_26_1_2 = register(775, "26.1.2");

  /** Cached unmodifiable view of all registered versions. */
  private static final List<ProtocolVersion> UNMODIFIABLE_VERSIONS =
      Collections.unmodifiableList(VERSIONS);

  /**
   * The minimum protocol ID that supports the {@link ProtocolState#CONFIGURATION} state, introduced
   * in Minecraft 1.20.2.
   */
  private static final int CONFIGURATION_MIN_PROTOCOL = 764;

  // ---------------------------------------------------------------------------
  // Instance fields
  // ---------------------------------------------------------------------------

  private final int protocol;
  private final String name;

  private ProtocolVersion(int protocol, String name) {
    this.protocol = protocol;
    this.name = name;
  }

  // ---------------------------------------------------------------------------
  // Factory / lookup
  // ---------------------------------------------------------------------------

  private static ProtocolVersion register(int protocol, String name) {
    ProtocolVersion version = new ProtocolVersion(protocol, name);
    VERSIONS.add(version);
    BY_PROTOCOL.putIfAbsent(protocol, version);
    return version;
  }

  /**
   * Returns the {@code ProtocolVersion} for the given wire protocol ID, or {@code null} if the
   * protocol ID is unknown.
   *
   * <p>When multiple game versions share a protocol ID (e.g. 1.20 / 1.20.1 = 763), the
   * <em>first</em> registered version is returned.
   *
   * @param protocolId the wire protocol ID to look up
   * @return the matching version, or {@code null} if unknown
   */
  public static @Nullable ProtocolVersion byProtocolId(int protocolId) {
    return BY_PROTOCOL.get(protocolId);
  }

  /**
   * Returns the newest supported version: the last one registered.
   *
   * @return the latest registered protocol version
   */
  public static ProtocolVersion latest() {
    return VERSIONS.getLast();
  }

  /**
   * Returns the oldest supported version: the first one registered.
   *
   * @return the earliest registered protocol version
   */
  public static ProtocolVersion oldest() {
    return VERSIONS.getFirst();
  }

  /**
   * Returns an unmodifiable list of all registered versions in chronological order.
   *
   * @return all registered versions
   */
  public static List<ProtocolVersion> values() {
    return UNMODIFIABLE_VERSIONS;
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the numeric protocol ID sent on the wire during handshake.
   *
   * @return the protocol ID
   */
  public int protocol() {
    return protocol;
  }

  /**
   * Returns the human-readable game version string (e.g. {@code "1.21.4"}).
   *
   * @return the game version name
   */
  public String name() {
    return name;
  }

  // ---------------------------------------------------------------------------
  // Comparison helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns {@code true} if this version's protocol ID is strictly greater than {@code other}'s.
   *
   * @param other the version to compare against
   * @return {@code true} if this version is newer
   */
  public boolean isNewerThan(ProtocolVersion other) {
    return this.protocol > other.protocol;
  }

  /**
   * Returns {@code true} if this version's protocol ID is strictly less than {@code other}'s.
   *
   * @param other the version to compare against
   * @return {@code true} if this version is older
   */
  public boolean isOlderThan(ProtocolVersion other) {
    return this.protocol < other.protocol;
  }

  /**
   * Returns {@code true} if this version's protocol ID falls within {@code [min, max]} inclusive.
   *
   * @param min the lower bound (inclusive)
   * @param max the upper bound (inclusive)
   * @return {@code true} if this version is between {@code min} and {@code max}
   */
  public boolean isBetween(ProtocolVersion min, ProtocolVersion max) {
    return this.protocol >= min.protocol && this.protocol <= max.protocol;
  }

  /**
   * Returns {@code true} if this version's protocol ID is greater than or equal to {@code other}'s.
   *
   * <p>Equivalent to {@code !isOlderThan(other)}, but reads more naturally in codec version
   * branching: {@code if (version.isAtLeast(MINECRAFT_1_19_3))}.
   *
   * @param other the version to compare against
   * @return {@code true} if this version is the same or newer
   */
  public boolean isAtLeast(ProtocolVersion other) {
    return this.protocol >= other.protocol;
  }

  /**
   * Returns {@code true} if this version's protocol ID is less than or equal to {@code other}'s.
   *
   * <p>Equivalent to {@code !isNewerThan(other)}, but reads more naturally in codec version
   * branching.
   *
   * @param other the version to compare against
   * @return {@code true} if this version is the same or older
   */
  public boolean isAtMost(ProtocolVersion other) {
    return this.protocol <= other.protocol;
  }

  /**
   * Returns {@code true} if this version supports the {@link ProtocolState#CONFIGURATION} state,
   * introduced in Minecraft 1.20.2 (protocol 764).
   *
   * @return {@code true} if the configuration state is supported
   */
  public boolean supportsConfigurationState() {
    return this.protocol >= CONFIGURATION_MIN_PROTOCOL;
  }

  // ---------------------------------------------------------------------------
  // Comparable / Object
  // ---------------------------------------------------------------------------

  @Override
  public int compareTo(ProtocolVersion other) {
    return Integer.compare(this.protocol, other.protocol);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof ProtocolVersion other)) return false;
    return this.protocol == other.protocol && this.name.equals(other.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(protocol, name);
  }

  @Override
  public String toString() {
    return "ProtocolVersion{" + name + " (protocol=" + protocol + ")}";
  }
}
