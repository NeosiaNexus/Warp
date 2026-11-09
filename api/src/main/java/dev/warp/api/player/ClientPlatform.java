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
package dev.warp.api.player;

/**
 * What kind of game client a connection is, as far as the proxy can tell: vanilla Java, a Forge
 * client of one of its three protocol eras, a NeoForge client, or a Bedrock player behind Geyser.
 *
 * <p>The platform is decided at the handshake from the markers a client appends to the host name it
 * connects with, and refined later when a platform leaves no mark there. It drives what the proxy
 * forwards to a backend on the player's behalf (the marker, login queries) and is never a trust
 * signal by itself: a marker is a string anyone can send.
 */
public sealed interface ClientPlatform {

  /** The vanilla client, or any client that leaves no mark the proxy recognizes. */
  ClientPlatform JAVA = new Java();

  /** A NeoForge client, which negotiates its mods in the configuration phase. */
  ClientPlatform NEOFORGE = new NeoForge();

  /**
   * Returns whether the client declared a mod loader, Forge or NeoForge, which negotiates its mods
   * with the server: such a client needs a modded backend, or a vanilla one it can fall back to.
   *
   * @return {@code true} for {@link Forge} and {@link NeoForge}
   */
  default boolean isModded() {
    return this instanceof Forge || this instanceof NeoForge;
  }

  /** The vanilla Java Edition client, or one the proxy cannot tell from it (Fabric, Quilt). */
  record Java() implements ClientPlatform {}

  /**
   * A Forge client.
   *
   * @param era the protocol era its handshake marker declared
   * @param netVersion the network version the marker carried, when it carries one: the number after
   *     {@code FORGE} in the modern marker, {@code 2} or {@code 3} for {@code FML2} and {@code
   *     FML3}, {@code 0} for the legacy {@code FML} marker, which has none
   */
  record Forge(ForgeEra era, int netVersion) implements ClientPlatform {}

  /**
   * A NeoForge client. NeoForge appends no marker to the host name, so the proxy learns of it from
   * the client's first configuration payload; until then the connection counts as {@link Java}.
   */
  record NeoForge() implements ClientPlatform {}

  /**
   * A Bedrock Edition player, connected through Geyser. The proxy sets this variant once it reads
   * and verifies Floodgate's data in the handshake, or when Geyser opens the session in process;
   * the Bedrock identity it then carries (XUID, device, linked Java account) is added by the
   * Bedrock milestone of the modded clients design.
   */
  record Bedrock() implements ClientPlatform {}

  /** The three protocols Forge has used to negotiate mods with a server. */
  enum ForgeEra {
    /**
     * Minecraft 1.7 to 1.12: marker {@code \0FML\0}, negotiation on the {@code FML|HS} plugin
     * channel once in the play state.
     */
    LEGACY,
    /**
     * Minecraft 1.13 to 1.20.1: marker {@code \0FML2\0} ({@code \0FML3\0} from 1.19), negotiation
     * with login plugin messages on {@code fml:loginwrapper} before Login Success.
     */
    FML2,
    /**
     * Minecraft 1.20.2 and newer: marker {@code \0FORGE} followed by an optional network version,
     * negotiation in the configuration phase.
     */
    MODERN
  }
}
