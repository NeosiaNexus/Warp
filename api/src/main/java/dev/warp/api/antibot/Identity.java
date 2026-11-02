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
package dev.warp.api.antibot;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * What the anti-bot verifies and remembers: what an attacker has to pay for to come back.
 *
 * <ul>
 *   <li>In online mode, the {@link Account}: a bot farm must buy one per concurrent bot.
 *   <li>In offline mode, where names are free, the {@link NameAtAddress}: a name together with the
 *       network it connects from (an IPv4 /24 or an IPv6 /48 by default), so that a player whose
 *       provider changes its address within its pool stays known, and a name taken from another
 *       network does not.
 * </ul>
 *
 * <p>The proxy keeps no identity in clear: it stores a keyed hash of each, under a secret of its
 * own, and computes it again to look one up.
 */
public sealed interface Identity permits Identity.Account, Identity.NameAtAddress {

  /**
   * A Mojang account, as authentication proved it in online mode.
   *
   * @param uuid the account's UUID
   */
  record Account(UUID uuid) implements Identity {

    /**
     * Checks the UUID is present.
     *
     * @param uuid the account's UUID
     * @throws NullPointerException if it is {@code null}
     */
    public Account {
      Objects.requireNonNull(uuid, "uuid");
    }
  }

  /**
   * A name together with the address it connects from. Only the address's network counts: the proxy
   * keys it by its IPv4 /24 or IPv6 /48 (configurable), an IPv4-mapped IPv6 address as IPv4. The
   * name ignores case, as the game does.
   *
   * @param name the player's name
   * @param address an address of the network
   */
  record NameAtAddress(String name, InetAddress address) implements Identity {

    /**
     * Checks the name and address are present, and lowercases the name.
     *
     * @param name the player's name
     * @param address an address of the network
     * @throws NullPointerException if either is {@code null}
     * @throws IllegalArgumentException if the name is blank
     */
    public NameAtAddress {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(address, "address");
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      name = name.toLowerCase(Locale.ROOT);
    }
  }
}
