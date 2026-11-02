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
package dev.warp.protocol.legacy;

import dev.warp.protocol.packet.Packet;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A legacy server list ping ({@code 0xFE}), sent by clients of Minecraft 1.6 and older, by the
 * clients of later versions when their status request failed, and by tools and load balancer health
 * checks. Answered with a {@link LegacyDisconnect} carrying the server list entry, then the
 * connection closes.
 *
 * @param format the form of the ping, which decides the form of the answer
 * @param host the host name the client typed (1.6 pings only), or {@code null}
 * @param port the port the client typed (1.6 pings only), or {@code 0}
 */
public record LegacyPing(Format format, @Nullable String host, int port) implements Packet {

  /**
   * The protocol a server of Minecraft 1.7 or later announces to a legacy ping, as vanilla does:
   * newer than any legacy client, which then lists the server as incompatible and shows its version
   * name.
   */
  public static final int MODERN_SERVER_PROTOCOL = 127;

  /** Validates the ping. */
  public LegacyPing {
    Objects.requireNonNull(format, "format");
  }

  /** The forms of the legacy ping, each answered in its own way. */
  public enum Format {
    /** Beta 1.8 to 1.3: a lone {@code 0xFE}. The answer carries the description and counts. */
    MINECRAFT_1_3,
    /**
     * 1.4 and 1.5: {@code 0xFE 0x01}. The answer adds the protocol and version name, and colors.
     */
    MINECRAFT_1_4,
    /**
     * 1.6: {@code 0xFE 0x01} followed by an {@code MC|PingHost} plugin message carrying the host
     * name and port the client typed. Answered as 1.4.
     */
    MINECRAFT_1_6
  }
}
