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
 * Fired once when the detector goes back to {@link dev.warp.api.antibot.AttackLevel#NORMAL}, with
 * the attack's totals, peaks and main sources. The proxy does not wait for the handlers.
 *
 * @param attack the attack, its end set
 */
public record AttackEndedEvent(AttackReport attack) {

  /**
   * Checks the report is that of an attack that ended.
   *
   * @throws NullPointerException if it is {@code null}
   * @throws IllegalArgumentException if it has no end
   */
  public AttackEndedEvent {
    Objects.requireNonNull(attack, "attack");
    if (attack.endedAt() == null) {
      throw new IllegalArgumentException("an ended attack has an end");
    }
  }
}
