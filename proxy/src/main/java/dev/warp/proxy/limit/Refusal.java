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

  /**
   * A new connection while the proxy holds as many connections not logged in as it may, none of
   * which could be closed to make room.
   */
  PENDING_CONNECTIONS("connections over the cap on connections not logged in"),

  /**
   * A connection that sent nothing, or stalled before its login, closed to give its place to a new
   * one while the proxy held as many connections not logged in as it may.
   */
  PENDING_EVICTED("stalled connections closed to make room for new ones"),

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
  /** An unknown identity whose verification failed on a protocol impossibility. */
  VERIFICATION_FAILED("logins that failed verification"),
  /** An unknown identity that did not answer the verification in time. */
  VERIFICATION_TIMEOUT("verifications timed out"),
  /** An unknown identity whose fall in the world did not follow the gravity, after its retry. */
  WORLD_CHECK_FAILED("logins that failed the world check"),
  /** An unknown identity the proxy had no room to verify. */
  VERIFICATION_BUSY("logins refused as too many verifying"),
  /** A player verified before authentication, told to reconnect (before 1.20.5). */
  VERIFIED_RECONNECT("verified players asked to reconnect"),

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
  SERVER_FULL("logins refused as the server is full"),
  /**
   * A handshake whose Floodgate data did not verify: not encrypted with the proxy's key, replayed,
   * naming another address, or carrying an identity that cannot log in.
   */
  FLOODGATE_REFUSED("Bedrock logins whose Floodgate data did not verify");

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
    return this == INVALID_FRAME
        || this == INVALID_HANDSHAKE
        || this == UNEXPECTED_PACKET
        || this == VERIFICATION_FAILED;
  }
}
