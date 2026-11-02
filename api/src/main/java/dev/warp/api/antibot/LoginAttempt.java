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

import java.net.InetSocketAddress;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A login a {@link VerificationCheck} judges: what the proxy knows of it at its Login Start, before
 * authentication.
 *
 * @param name the name the client claims
 * @param address the client's address, from the PROXY protocol header behind a load balancer
 * @param protocol the protocol version the client's handshake announced
 * @param virtualHost the host name the client connected to, or {@code null} if it sent none
 * @param onlineMode whether the login will authenticate with Mojang
 */
public record LoginAttempt(
    String name,
    InetSocketAddress address,
    int protocol,
    @Nullable String virtualHost,
    boolean onlineMode) {

  /**
   * Checks the values are present.
   *
   * @throws NullPointerException if the name or the address is {@code null}
   */
  public LoginAttempt {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(address, "address");
  }
}
