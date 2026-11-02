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

import dev.warp.api.event.Subscription;

import java.util.Optional;

/**
 * The proxy's built-in anti-bot, from {@link dev.warp.api.Warp#antiBot()}.
 *
 * <p>Every connection goes through bounded admission before it logs in: per-address budgets, a cap
 * on connections not logged in, frame limits and deadlines per state, all counted rather than
 * logged. On top of that, the anti-bot measures the logins of identities it does not know against a
 * learned baseline to detect attacks ({@link #level()}), remembers the identities it trusts ({@link
 * #verified()}), lets only those in during a lockdown ({@link #lockdown(boolean)}), and, when the
 * proxy authenticates as many players as it may, gives trusted ones the next free authentication.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface AntiBot {

  /**
   * Returns whether the anti-bot runs ({@code anti-bot.enabled}). When it does not, the level stays
   * {@link AttackLevel#NORMAL}, no identity is remembered and lockdown cannot be turned on; the
   * per-connection limits still apply.
   *
   * @return {@code true} if it runs
   */
  boolean isEnabled();

  /**
   * Returns whether the proxy considers itself under attack.
   *
   * @return the level the detector measured
   */
  AttackLevel level();

  /**
   * Returns the attack in progress.
   *
   * @return its report so far, or empty at {@link AttackLevel#NORMAL}
   */
  Optional<AttackReport> currentAttack();

  /**
   * Returns whether lockdown is on.
   *
   * @return {@code true} while only trusted identities may log in
   */
  boolean isLockdown();

  /**
   * Turns lockdown on or off. While on, only identities the proxy trusts may log in, and addresses
   * in {@code anti-bot.exempt}; any other login is told the server only lets in players who have
   * played here before. In online mode an unknown name and network still authenticates, last in
   * line, since the account it proves may be a trusted one. Fires a {@link
   * dev.warp.api.event.antibot.LockdownChangedEvent} when it changes. Lockdown does not survive a
   * restart.
   *
   * @param enabled {@code true} to turn it on
   * @return {@code true} if it changed
   * @throws IllegalStateException if the anti-bot does not run
   */
  boolean lockdown(boolean enabled);

  /**
   * Returns the identities the proxy trusts.
   *
   * @return the store
   */
  VerifiedIdentities verified();

  /**
   * Shares the trusted identities with other proxies through {@code store}, which the proxy's own
   * store then caches (see {@link VerifiedStore}).
   *
   * @param store the shared store
   * @return the registration; unsubscribing stops sharing
   * @throws IllegalStateException if another store is registered, or the anti-bot does not run
   */
  Subscription shareVerifications(VerifiedStore store);

  /**
   * Adds a check of logins from identities the proxy does not trust.
   *
   * @param check the check
   * @return the registration; unsubscribing removes the check
   * @throws IllegalStateException if the anti-bot does not run
   */
  Subscription addCheck(VerificationCheck check);
}
