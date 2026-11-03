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

import java.util.concurrent.CompletableFuture;

/**
 * An extra check of logins from identities the proxy does not trust, which a plugin implements and
 * registers with {@link AntiBot#addCheck(VerificationCheck)}: IP reputation, VPN detection, GeoIP.
 *
 * <p>Checks run at the Login Start of an unknown identity, after the proxy's own limits and before
 * {@link dev.warp.api.event.player.PreLoginEvent}, all at once; a trusted identity, or an address
 * in {@code anti-bot.exempt}, never meets them. The proxy waits for their verdicts at most {@code
 * anti-bot.checks.timeout}: a check that answers later, or fails, counts as {@link
 * Verdict.Abstain}, never as a stall. One {@link Verdict.Fail} refuses the login; one {@link
 * Verdict.Escalate} makes the proxy treat it strictly.
 *
 * <p>Called from the proxy's threads: do not block, return a future and hand any I/O to a thread of
 * your own.
 */
@FunctionalInterface
public interface VerificationCheck {

  /**
   * Judges a login.
   *
   * @param attempt the login
   * @return completed with the verdict
   */
  CompletableFuture<Verdict> check(LoginAttempt attempt);
}
