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

import dev.warp.proxy.network.CidrRanges;

/**
 * The {@code proxy-protocol} section of the configuration: whether connections arrive through a
 * load balancer that sends the PROXY protocol (v1 or v2), and which peers may send it.
 *
 * <p>When enabled, the header is mandatory and only trusted: a connection from a peer outside
 * {@code trustedProxies} is refused before anything is read from it, and a connection from a
 * trusted peer that does not start with a valid header is refused too. Warp never guesses per
 * connection whether a header is present, which would let a client reaching Warp directly choose
 * the address Warp sees.
 *
 * @param enabled whether the PROXY protocol header is expected
 * @param trustedProxies the addresses of the load balancers allowed to send it; never empty when
 *     {@code enabled}
 */
public record ProxyProtocolConfig(boolean enabled, CidrRanges trustedProxies) {

  /** PROXY protocol off: clients connect to Warp directly. */
  public static final ProxyProtocolConfig DISABLED =
      new ProxyProtocolConfig(false, CidrRanges.none());

  /**
   * Checks that an enabled PROXY protocol trusts at least one peer.
   *
   * @throws IllegalArgumentException if {@code enabled} and {@code trustedProxies} is empty
   */
  public ProxyProtocolConfig {
    if (enabled && trustedProxies.isEmpty()) {
      throw new IllegalArgumentException("an enabled PROXY protocol needs trusted proxies");
    }
  }
}
