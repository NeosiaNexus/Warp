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
package dev.warp.proxy.connection;

import dev.warp.api.player.ClientPlatform;
import dev.warp.api.player.ClientPlatform.Forge;
import dev.warp.api.player.ClientPlatform.ForgeEra;
import dev.warp.protocol.ProtocolVersion;

import org.jspecify.annotations.Nullable;

/**
 * Reads the markers a modded client appends to the host name of its handshake, and rebuilds the one
 * to send a backend.
 *
 * <p>Forge has marked its clients three ways: {@code \0FML\0} (1.7 to 1.12), {@code \0FML2\0} or
 * {@code \0FML3\0} (1.13 to 1.20.1, the 3 from 1.19), and {@code \0FORGE} followed by an optional
 * network version (1.20.2 and newer). A Forge server reads the marker to tell a modded client from
 * a vanilla one, and refuses or downgrades the connection when it is missing: a proxy that rebuilds
 * the handshake must carry it. NeoForge marks nothing. Other suffixes (a BungeeCord forwarding
 * payload, Floodgate's data) are not markers and are never forwarded by this class.
 *
 * <p>The marker is kept apart from the virtual host: routes match the host alone, the marker only
 * travels to the backend.
 */
final class HandshakeMarkers {

  /** The FML2 era's marker, as 1.13 to 1.18 send it. */
  static final String FML2 = "FML2";

  /** The FML2 era's marker since Forge 1.19, same mechanism, a newer network version. */
  static final String FML3 = "FML3";

  /** The legacy marker of 1.7 to 1.12. */
  static final String FML = "FML";

  /** The start of the modern marker, 1.20.2 and newer: {@code FORGE}, then an optional number. */
  static final String FORGE = "FORGE";

  /** Nothing to carry: a client without a marker. */
  static final Detected NONE = new Detected(ClientPlatform.JAVA, "");

  private HandshakeMarkers() {}

  /**
   * What a handshake's host name said of the client.
   *
   * @param platform the platform the marker declares, {@link ClientPlatform#JAVA} without one
   * @param marker the marker to append to the backend's host name, normalized ({@code \0FML2\0},
   *     {@code \0FORGE3} as Forge itself writes them), or an empty string without one
   */
  record Detected(ClientPlatform platform, String marker) {}

  /**
   * Reads the marker of a handshake's host name.
   *
   * <p>Every {@code \0}-separated part after the host is examined: the first that is a Forge marker
   * decides. A marker from the wrong era for the protocol (an {@code FML2} on 1.21, which no Forge
   * client sends) is ignored: the backend would not read it either, and a platform is only as
   * useful as it is right. The version is unknown during a status ping from an unsupported
   * protocol; the marker is then read without the era check.
   *
   * @param serverAddress the host name field of the handshake, as sent
   * @param version the client's protocol, or {@code null} when unsupported
   * @return the platform and the marker to forward
   */
  static Detected detect(String serverAddress, @Nullable ProtocolVersion version) {
    int nul = serverAddress.indexOf('\0');
    if (nul < 0) {
      return NONE;
    }
    String[] parts = serverAddress.substring(nul + 1).split("\0", -1);
    for (String part : parts) {
      Detected detected = detectPart(part, version);
      if (detected != null) {
        return detected;
      }
    }
    return NONE;
  }

  private static @Nullable Detected detectPart(String part, @Nullable ProtocolVersion version) {
    if (part.equals(FML)) {
      if (version == null || version.isOlderThan(ProtocolVersion.MINECRAFT_1_13)) {
        return new Detected(new Forge(ForgeEra.LEGACY, 0), "\0" + FML + "\0");
      }
      return null;
    }
    if (part.equals(FML2) || part.equals(FML3)) {
      if (version == null
          || version.isBetween(ProtocolVersion.MINECRAFT_1_13, ProtocolVersion.MINECRAFT_1_20_1)) {
        int netVersion = part.equals(FML2) ? 2 : 3;
        return new Detected(new Forge(ForgeEra.FML2, netVersion), "\0" + part + "\0");
      }
      return null;
    }
    if (part.startsWith(FORGE)) {
      if (version != null && version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        return null;
      }
      int netVersion = 0;
      String number = part.substring(FORGE.length());
      if (!number.isEmpty()) {
        try {
          netVersion = Integer.parseInt(number);
        } catch (NumberFormatException notForge) {
          return null; // FORGEXYZ is some other suffix, not a marker
        }
      }
      // Forge writes the marker last, with no closing separator.
      return new Detected(new Forge(ForgeEra.MODERN, netVersion), "\0" + part);
    }
    return null;
  }

  /**
   * Returns whether a platform negotiates with login plugin messages before Login Success, which
   * the proxy then has to relay while the client is still logging in.
   *
   * @param platform the client's platform
   * @param version the client's protocol
   * @return {@code true} for a Forge client of the FML2 era
   */
  static boolean hasLoginQueries(ClientPlatform platform, ProtocolVersion version) {
    return platform instanceof Forge(ForgeEra era, int _)
        && era == ForgeEra.FML2
        && version.isBetween(ProtocolVersion.MINECRAFT_1_13, ProtocolVersion.MINECRAFT_1_20_1);
  }
}
