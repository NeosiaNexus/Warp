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

/** How an identity came to be trusted. */
public enum VerificationMethod {

  /**
   * The identity joined while the proxy was not under attack, before this proxy verified identities
   * with checks of its own: it is known from having played here.
   */
  JOINED,

  /** Staff marked it verified with {@code /warp antibot verify}. */
  COMMAND,

  /** A plugin marked it verified, with {@link VerifiedIdentities#add(Identity)}. */
  PLUGIN,

  /**
   * It passed the proxy's probe: the login and configuration packets a real client answers (a ping
   * and a keep-alive echoed, its brand and settings sent, its known packs and a cookie returned).
   */
  PROBE,

  /**
   * It came back from a verification before authentication with the signed token the proxy stored
   * in its client, and then authenticated.
   */
  TOKEN
}
