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

/** What the attack detector counts, each second. */
public enum Signal {

  /** New connections the proxy accepted. Reported, never a trigger on its own. */
  CONNECTIONS,

  /** Connections not logged in yet, at the end of each second: a gauge, not a rate. */
  PENDING_CONNECTIONS,

  /** Login Starts from identities the proxy does not know. */
  UNKNOWN_LOGINS,

  /** Failed authentications: wrong verify tokens or secrets, sessions the server denies. */
  AUTHENTICATION_FAILURES,

  /** Connections the proxy refused or closed before login, for any reason. Reported only. */
  REFUSALS
}
