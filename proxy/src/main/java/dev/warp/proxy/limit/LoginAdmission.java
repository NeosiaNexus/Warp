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
package dev.warp.proxy.limit;

import java.time.Duration;

/** What {@link ClientLimits#admitLogin} decides about a login attempt. */
public sealed interface LoginAdmission {

  /**
   * The login may go on.
   *
   * @param slot the place the login takes among its address's logins in progress, to release when
   *     it ends, whichever way
   */
  record Admitted(LoginSlot slot) implements LoginAdmission {}

  /**
   * The address tried to log in too often: it must wait before its next attempt.
   *
   * @param retryAfter how long until the address may try again
   */
  record TooFast(Duration retryAfter) implements LoginAdmission {}

  /** The address already has as many logins in progress as it may. */
  record TooManyInProgress() implements LoginAdmission {}
}
