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
package dev.warp.proxy.transfer;

/**
 * Why a transferred client's handoff ticket was not used. The login goes on without it, through
 * Mojang, except for {@link #BAD_PROOF}: a client told to skip Mojang cannot be authenticated
 * afterwards, and is refused.
 */
public enum HandoffRejection {
  /** The cookie is not a ticket: too short, or its content does not parse. */
  MALFORMED("malformed"),
  /** A format this proxy does not read: a newer Warp wrote it. */
  UNKNOWN_VERSION("version"),
  /** Sealed under another key: the proxies do not share {@code transfers.handoff}'s key. */
  FOREIGN_KEY("key"),
  /** Sealed under this proxy's key, but changed since: forged or corrupted. */
  TAMPERED("tampered"),
  /** Older than its expiry: the transfer took too long, or the cookie was kept. */
  EXPIRED("expired"),
  /** Written further in the future than the clock skew tolerated between proxies. */
  CLOCK("clock"),
  /** Written for another address than the one the client connected to. */
  WRONG_TARGET("target"),
  /** Written for another name than the one the client logs in with. */
  NAME_MISMATCH("name"),
  /** Written for another account than the one the client logs in with. */
  UUID_MISMATCH("uuid"),
  /** Already used: a replay. */
  REPLAYED("replayed"),
  /** Too many tickets used within their lifetime to remember another one. */
  REPLAY_CACHE_FULL("busy"),
  /** The proof that travels encrypted is missing or wrong: the ticket was captured in transit. */
  BAD_PROOF("proof");

  private final String label;

  HandoffRejection(String label) {
    this.label = label;
  }

  /**
   * Returns the reason's label in {@code warp_transfer_handoffs_rejected_total}.
   *
   * @return the label, stable
   */
  public String label() {
    return label;
  }
}
