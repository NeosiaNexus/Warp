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
package dev.warp.proxy.limit;

import java.util.Locale;

/**
 * Why the proxy closed a connection before the client logged in. Each reason is a counter: in the
 * report line {@link ClientLimits#report()} logs, in the metrics, and in the anti-bot's signals.
 * None is ever logged one connection at a time, since a client can trigger any of them at will and
 * a flood of them would otherwise be a flood of log lines (Sonar #194: 40% of a CPU spent logging).
 */
public enum Refusal {

  // ---------------------------------------------------------------------------
  // Limits
  // ---------------------------------------------------------------------------

  /** A new connection over its address's connection rate. */
  CONNECTION_RATE("connections over their address's rate"),

  /** A new connection while the proxy holds as many connections not logged in as it may. */
  PENDING_CONNECTIONS("connections over the cap on connections not logged in"),

  /** A server list ping over its address's ping rate. */
  STATUS_RATE("server list pings over their address's rate"),

  /** A login over its address's login rate. */
  LOGIN_RATE("logins too fast"),

  /** A login while its address has as many in progress as it may. */
  LOGINS_IN_PROGRESS("logins over the logins in progress per address"),

  /**
   * An online login that found the authentication line full, or waited in it longer than its
   * longest wait.
   */
  AUTHENTICATION_BUSY("logins refused as too many authenticating"),

  /** A new connection from an address on the blocklist. */
  BLOCKLISTED("connections from blocklisted addresses"),

  /** A login of an identity the anti-bot does not trust, during a lockdown. */
  LOCKDOWN("unknown logins refused by the lockdown"),

  /** A login a plugin's check failed. */
  CHECK_FAILED("logins refused by plugins' checks"),

  // ---------------------------------------------------------------------------
  // Deadlines
  // ---------------------------------------------------------------------------

  /** No handshake within the handshake timeout: a null connection, a scan, a slow drip. */
  HANDSHAKE_TIMEOUT("handshake time-outs"),

  /** A server list ping not finished within the status timeout, such as a null ping. */
  STATUS_TIMEOUT("server list ping time-outs"),

  /** A login not finished within the login timeout. */
  LOGIN_TIMEOUT("logins timed out"),

  /** No complete frame for the read timeout, before the client logged in. */
  IDLE_TIMEOUT("idle time-outs before login"),

  // ---------------------------------------------------------------------------
  // Protocol
  // ---------------------------------------------------------------------------

  /** A frame the limits of its state refuse from its header, or one no packet can be read from. */
  INVALID_FRAME("invalid or oversized frames"),

  /** A packet the state does not expect, or one sent out of order. */
  UNEXPECTED_PACKET("unexpected packets"),

  /** A handshake with an unknown next state, or a transfer from a version without transfers. */
  INVALID_HANDSHAKE("invalid handshakes"),

  /** A login from a protocol version the proxy does not support, told so. */
  UNSUPPORTED_VERSION("logins from unsupported versions"),

  /** A transfer while {@code accepts-transfers} is off. */
  TRANSFER_REFUSED("transfers refused"),

  /** An invalid name, or a profile key that does not check. */
  INVALID_LOGIN("invalid names or profile keys"),

  /** A failed authentication: a wrong verify token or secret, or a session the server denies. */
  AUTHENTICATION_FAILED("failed authentications"),

  /** A login of a player already connected, refused by the duplicate login policy. */
  DUPLICATE_LOGIN("logins of players already connected"),

  /** A login a plugin denied. */
  DENIED_BY_PLUGIN("logins denied by plugins"),

  /** A login beyond the player limit. */
  SERVER_FULL("logins refused as the server is full");

  private final String description;

  Refusal(String description) {
    this.description = description;
  }

  /**
   * Describes the reason for the report line, in the plural: {@code "handshake time-outs"}.
   *
   * @return the description
   */
  public String description() {
    return description;
  }

  /**
   * Returns the reason's name as a metric label: {@code handshake_timeout}.
   *
   * @return the label
   */
  public String label() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Returns whether the reason is a protocol impossibility, which no game client produces however
   * bad its network: a strike against its address on the blocklist. Time-outs, rate limits and
   * failed authentications never are.
   *
   * @return {@code true} for an invalid frame, handshake or packet
   */
  public boolean strikes() {
    return this == INVALID_FRAME || this == INVALID_HANDSHAKE || this == UNEXPECTED_PACKET;
  }
}
