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
package dev.warp.proxy.metrics;

import dev.warp.proxy.limit.Refusal;

import java.util.Locale;

/** The {@code outcome} label of {@code warp_logins_total}: one value per login attempt. */
public enum LoginOutcome {
  /** The player joined. */
  SUCCESS,
  /** Refused by the anti-bot or the limits: rate, lockdown, blocklist, checks, queue. */
  REFUSED_ANTIBOT,
  /** An account already connected. */
  REFUSED_DUPLICATE,
  /** The proxy is full. */
  REFUSED_FULL,
  /** Mojang did not confirm the account, or the encryption response was invalid. */
  AUTH_FAILED,
  /** The client did not finish logging in within the login time-out. */
  AUTH_TIMEOUT,
  /** A client version the proxy does not speak. */
  PROTOCOL_UNSUPPORTED,
  /** A plugin denied the login. */
  CANCELLED_PLUGIN,
  /** The client left during the login. */
  DISCONNECTED,
  /** Anything else: an invalid name or handshake, an unexpected packet. */
  OTHER;

  private final String label = name().toLowerCase(Locale.ROOT);

  /**
   * Returns the label value.
   *
   * @return the value, such as {@code refused_full}
   */
  public String label() {
    return label;
  }

  /**
   * Maps a refusal counted by the limits to a login outcome.
   *
   * @param refusal why the login was refused
   * @return the outcome
   */
  public static LoginOutcome of(Refusal refusal) {
    return switch (refusal) {
      case LOGIN_RATE,
          LOGINS_IN_PROGRESS,
          AUTHENTICATION_BUSY,
          BLOCKLISTED,
          LOCKDOWN,
          CHECK_FAILED,
          WORLD_CHECK_FAILED,
          CONNECTION_RATE,
          PENDING_CONNECTIONS ->
          REFUSED_ANTIBOT;
      case DUPLICATE_LOGIN -> REFUSED_DUPLICATE;
      case SERVER_FULL -> REFUSED_FULL;
      case AUTHENTICATION_FAILED -> AUTH_FAILED;
      case LOGIN_TIMEOUT -> AUTH_TIMEOUT;
      case UNSUPPORTED_VERSION -> PROTOCOL_UNSUPPORTED;
      case DENIED_BY_PLUGIN -> CANCELLED_PLUGIN;
      default -> OTHER;
    };
  }
}
