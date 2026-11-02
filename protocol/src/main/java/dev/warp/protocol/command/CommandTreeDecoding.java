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
package dev.warp.protocol.command;

import dev.warp.protocol.ProtocolVersion;

import java.util.Objects;

/**
 * What decoding a commands packet gave: the tree, or the reason it is left opaque.
 *
 * <p>Opaque is not an error. The packet is valid as far as Warp can tell, but holds something Warp
 * cannot represent exactly, most often an argument type of a modded server (a Fabric or Forge mod's
 * own): its properties have no length on the wire, so nothing after it can be read. The caller
 * forwards the original bytes untouched, exactly as if it had never looked, and adds no proxy
 * command to that tree. Where Velocity disconnects the player (PaperMC/Velocity#1912,
 * PaperMC/Velocity#1714), the player stays, with the backend's commands.
 */
public sealed interface CommandTreeDecoding
    permits CommandTreeDecoding.Decoded, CommandTreeDecoding.Opaque {

  /**
   * The packet decoded, to a tree that encodes back to the same bytes.
   *
   * @param tree the command tree
   */
  record Decoded(CommandTree tree) implements CommandTreeDecoding {

    // Checks the tree.
    public Decoded {
      Objects.requireNonNull(tree, "tree");
    }
  }

  /**
   * The packet holds something Warp cannot represent exactly: forward it untouched.
   *
   * @param version the protocol version the packet was decoded for
   * @param node the index of the node Warp stopped at
   * @param reason what Warp could not represent there, for the log
   */
  record Opaque(ProtocolVersion version, int node, String reason) implements CommandTreeDecoding {

    // Checks the components.
    public Opaque {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns a one-line explanation for the proxy's log, which it should write once per backend
     * rather than for every packet.
     *
     * @return the explanation
     */
    public String message() {
      return "Commands sent for "
          + version.name()
          + " forwarded untouched, without proxy commands: node "
          + node
          + " has "
          + reason
          + " (a modded server's own argument type is the usual cause; installing CrossStitch on"
          + " the server lets Warp read past it)";
    }
  }
}
