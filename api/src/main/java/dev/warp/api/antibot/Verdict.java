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

import java.util.Objects;

import net.kyori.adventure.text.Component;

/** What a {@link VerificationCheck} makes of a login. */
public sealed interface Verdict
    permits Verdict.Pass, Verdict.Fail, Verdict.Escalate, Verdict.Abstain {

  /** The check found nothing against the login. */
  record Pass() implements Verdict {}

  /**
   * The login must not go on: it is refused, the client shown the reason, and counted.
   *
   * @param reason what the client is shown
   */
  record Fail(Component reason) implements Verdict {

    /**
     * Checks the reason is present.
     *
     * @param reason what the client is shown
     * @throws NullPointerException if it is {@code null}
     */
    public Fail {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * The login looks suspicious without being proven a bot: it goes on, but the proxy treats it as
   * strictly as it treats any login: it is refused during a lockdown, and its identity is not
   * learned from this join even outside an attack.
   */
  record Escalate() implements Verdict {}

  /** The check has no opinion, such as when its data source is unavailable. */
  record Abstain() implements Verdict {}
}
