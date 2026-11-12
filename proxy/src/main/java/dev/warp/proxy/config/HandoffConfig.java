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
package dev.warp.proxy.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code transfers.handoff} section of {@code warp.conf}: the seamless handoff of a player
 * between Warp proxies. Before a transfer to a peer, the proxy stores a sealed ticket in the
 * player's client; the peer that receives it logs the player in without asking Mojang again, and
 * sends it to the server it was on.
 *
 * @param enabled whether tickets are written before transfers and read from transferred logins; off
 *     unless the operator turns it on: a ticket stands in for Mojang's authentication
 * @param secretFile the file the sealing key is read from, generated when absent, relative to the
 *     proxy's directory; {@value #ENV_SECRET} takes precedence
 * @param maxAge how long a ticket stays valid after it was written
 * @param bindAddress whether a ticket is only valid from the address its player had on the proxy
 *     that wrote it; turned off where the proxies do not see the same client address
 * @param peers where a transfer carries a ticket, besides {@code drain.transfer-to}: the other
 *     proxies of the network, as players reach them
 */
public record HandoffConfig(
    boolean enabled,
    String secretFile,
    Duration maxAge,
    boolean bindAddress,
    List<DrainConfig.Target> peers) {

  /** The environment variable that holds the key, ahead of {@code secret-file}. */
  public static final String ENV_SECRET = "WARP_TRANSFER_SECRET";

  /** The longest {@code max-age}: a ticket is a credential, and a transfer takes seconds. */
  public static final Duration MAX_MAX_AGE = Duration.ofSeconds(60);

  static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(15);
  static final String DEFAULT_SECRET_FILE = "transfer.secret";

  /** The defaults: off; once on, valid 15 seconds from the same address, the drain's peer only. */
  public static final HandoffConfig DEFAULTS =
      new HandoffConfig(false, DEFAULT_SECRET_FILE, DEFAULT_MAX_AGE, true, List.of());

  /** Copies the peers. */
  public HandoffConfig {
    peers = List.copyOf(peers);
  }

  /**
   * Reads the section.
   *
   * @param root the configuration's root node
   * @return the section's values
   * @throws ConfigurationException if a value is invalid
   */
  static HandoffConfig parse(CommentedConfigurationNode root) throws ConfigurationException {
    CommentedConfigurationNode node = root.node("transfers", "handoff");
    boolean enabled = WarpConfig.booleanValue(node.node("enabled"), false);
    String secretFile = WarpConfig.stringValue(node.node("secret-file"), DEFAULT_SECRET_FILE);
    if (secretFile.isBlank()) {
      throw ConfigurationException.invalid(
          "transfers.handoff.secret-file", "a file name, such as \"transfer.secret\"", "''");
    }
    Duration maxAge =
        Durations.parse(node.node("max-age"), "transfers.handoff.max-age", DEFAULT_MAX_AGE);
    if (maxAge.compareTo(Duration.ofSeconds(1)) < 0 || maxAge.compareTo(MAX_MAX_AGE) > 0) {
      throw ConfigurationException.invalid(
          "transfers.handoff.max-age", "a duration from 1s to 60s", DrainConfig.format(maxAge));
    }
    boolean bindAddress = WarpConfig.booleanValue(node.node("bind-address"), true);
    return new HandoffConfig(
        enabled, secretFile.strip(), maxAge, bindAddress, parsePeers(node.node("peers")));
  }

  private static List<DrainConfig.Target> parsePeers(CommentedConfigurationNode node)
      throws ConfigurationException {
    String key = "transfers.handoff.peers";
    if (!node.virtual() && !node.isList()) {
      throw new ConfigurationException(
          key + " must be a list of addresses, such as [\"play.example.com:25565\"]");
    }
    List<DrainConfig.Target> peers = new ArrayList<>();
    for (CommentedConfigurationNode child : node.childrenList()) {
      String value = child.getString();
      DrainConfig.Target peer = value == null ? null : DrainConfig.parseTarget(key, value);
      if (peer == null) {
        throw new ConfigurationException(key + " holds an empty entry: list host:port addresses");
      }
      peers.add(peer);
    }
    return peers;
  }

  @Override
  public String toString() {
    return enabled
        ? "on, max-age "
            + DrainConfig.format(maxAge)
            + (bindAddress
                ? ", bound to the client address, "
                : ", not bound to the client address, ")
            + peers.size()
            + " peers listed"
        : "off";
  }
}
