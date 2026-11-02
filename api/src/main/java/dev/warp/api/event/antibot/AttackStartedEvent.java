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

import dev.warp.api.antibot.AttackReport;

import java.util.Objects;

/**
 * Fired once when the detector enters {@link dev.warp.api.antibot.AttackLevel#ATTACK}: a plugin can
 * alert staff, or turn lockdown on. The proxy does not wait for the handlers.
 *
 * @param attack the attack as it started: its trigger, no end yet
 */
public record AttackStartedEvent(AttackReport attack) {

  /**
   * Checks the report is present.
   *
   * @throws NullPointerException if it is {@code null}
   */
  public AttackStartedEvent {
    Objects.requireNonNull(attack, "attack");
  }
}
