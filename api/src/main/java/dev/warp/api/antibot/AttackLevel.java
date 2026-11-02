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

/**
 * Whether the proxy considers itself under a bot attack, as its detector measures it. Lockdown is
 * separate ({@link AntiBot#isLockdown()}): staff turn it on and off, and the detector keeps
 * measuring meanwhile.
 */
public enum AttackLevel {

  /** No attack: the signals of unknown identities are within their thresholds. */
  NORMAL,

  /**
   * An attack: for a few seconds in a row, logins from unknown identities, failed authentications
   * or connections not logged in went over their thresholds (a floor, or a multiple of their
   * learned baseline). The proxy stops learning identities from joins and logs one summary per
   * interval instead of anything per connection. It goes back to {@link #NORMAL} once every signal
   * stayed under half its threshold for {@code anti-bot.attack.calm-for}.
   */
  ATTACK
}
