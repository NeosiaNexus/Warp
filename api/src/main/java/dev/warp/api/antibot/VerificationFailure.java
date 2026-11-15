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
 * Why a verification of an unknown identity failed. A bounded set, so that metrics and summaries
 * can count by reason.
 *
 * <p>Only the first three are judgements on the client: protocol facts no real client gets wrong. A
 * time-out says nothing certain (a slow or frozen client, a lost connection), and {@link #BUSY} is
 * the proxy's own limit. Neither counts against the client's address.
 */
public enum VerificationFailure {

  /** The client echoed a ping or a keep-alive with an id the proxy never sent. */
  WRONG_ECHO,

  /** The client sent a packet that is illegal in its state: an answer to nothing, say. */
  ILLEGAL_PACKET,

  /** The client returned a cookie the proxy stored with other contents. */
  COOKIE_MISMATCH,

  /** The client's first position after the world's teleport was not the teleport target. */
  WRONG_POSITION,

  /**
   * The client's fall in the world did not follow the gravity, twice: never a strike against its
   * address, since real clients have stumbled on it at version edges before.
   */
  PHYSICS,

  /** The client did not answer, or did not send its brand and settings, before the deadline. */
  TIMED_OUT,

  /**
   * The proxy had no room to verify it: its verification sessions were full and the client waited
   * in line longer than allowed, or the line itself was full.
   */
  BUSY
}
