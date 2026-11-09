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

import dev.warp.api.antibot.LoginAttempt;
import dev.warp.api.antibot.VerificationFailure;

import java.util.Objects;

/**
 * Fired when an unknown identity failed the proxy's verification and was turned away. The client is
 * gone by then: a plugin can count, alert, or feed its own reputation. The proxy does not wait for
 * the handlers.
 *
 * <p>Fired once per failed verification, which is bounded by the proxy's verification capacity,
 * never once per connection of a flood: refusals before verification (rates, deadlines, frames) are
 * counted, not announced.
 *
 * @param attempt the login that failed
 * @param reason why
 */
public record VerificationFailedEvent(LoginAttempt attempt, VerificationFailure reason) {

  /**
   * Checks the values are present.
   *
   * @throws NullPointerException if one is {@code null}
   */
  public VerificationFailedEvent {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(reason, "reason");
  }
}
