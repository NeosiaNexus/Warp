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
package dev.warp.api.event.antibot;

import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.Verification;

import java.util.Objects;

/**
 * Fired when the proxy starts trusting an identity it did not: learned from a join outside an
 * attack, marked by staff or by a plugin. Not fired when a trusted identity joins again. The proxy
 * does not wait for the handlers.
 *
 * @param identity the identity: an {@link Identity.Account} in online mode, a {@link
 *     Identity.NameAtAddress} in offline mode or for an address given by staff
 * @param verification what the proxy now remembers of it
 */
public record IdentityVerifiedEvent(Identity identity, Verification verification) {

  /**
   * Checks the values are present.
   *
   * @throws NullPointerException if one is {@code null}
   */
  public IdentityVerifiedEvent {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(verification, "verification");
  }
}
