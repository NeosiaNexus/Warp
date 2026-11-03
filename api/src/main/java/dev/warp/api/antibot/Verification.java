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

import java.time.Instant;
import java.util.Objects;

/**
 * What the proxy remembers of a trusted identity. Times are kept to the minute.
 *
 * @param verifiedAt when the identity was first trusted
 * @param lastSeen when it last joined, or was last marked verified: the proxy forgets it {@code
 *     anti-bot.verified.expire-after-last-join} later (30 days by default)
 * @param method how it came to be trusted
 */
public record Verification(Instant verifiedAt, Instant lastSeen, VerificationMethod method) {

  /**
   * Checks the values.
   *
   * @throws NullPointerException if a value is {@code null}
   * @throws IllegalArgumentException if it was last seen before it was verified
   */
  public Verification {
    Objects.requireNonNull(verifiedAt, "verifiedAt");
    Objects.requireNonNull(lastSeen, "lastSeen");
    Objects.requireNonNull(method, "method");
    if (lastSeen.isBefore(verifiedAt)) {
      throw new IllegalArgumentException(
          "lastSeen " + lastSeen + " is before verifiedAt " + verifiedAt);
    }
  }
}
