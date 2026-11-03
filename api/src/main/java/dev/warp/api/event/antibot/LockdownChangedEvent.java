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

import java.util.Objects;

/**
 * Fired when lockdown is turned on or off, whoever did: a command, the management endpoint or a
 * plugin. While on, only trusted identities may log in. The proxy does not wait for the handlers.
 *
 * @param enabled whether lockdown is now on
 * @param source who changed it, such as {@code "command by Notch"}, {@code "management"} or {@code
 *     "plugin"}
 */
public record LockdownChangedEvent(boolean enabled, String source) {

  /**
   * Checks the source is present.
   *
   * @throws NullPointerException if it is {@code null}
   */
  public LockdownChangedEvent {
    Objects.requireNonNull(source, "source");
  }
}
