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

import org.jspecify.annotations.Nullable;

/**
 * The place a login in progress takes among the logins of its address. Released once, when the
 * player is logged in or the connection closes; later calls do nothing.
 *
 * <p>Used from the event loop of the login's connection only.
 */
public final class LoginSlot {

  /** A slot that counts nowhere: for addresses no limit applies to. */
  static final LoginSlot NONE = new LoginSlot(null, 0);

  private final @Nullable ConcurrencyLimiter limiter;
  private final long key;
  private boolean released;

  LoginSlot(@Nullable ConcurrencyLimiter limiter, long key) {
    this.limiter = limiter;
    this.key = key;
  }

  /** Gives the place back to the address. Idempotent. */
  public void release() {
    ConcurrencyLimiter owner = limiter;
    if (released || owner == null) {
      return;
    }
    released = true;
    owner.release(key);
  }
}
