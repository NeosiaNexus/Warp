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
package dev.warp.proxy.config;

/**
 * What the proxy does when a player logs in while a session with the same UUID is still connected.
 *
 * <p>Vanilla servers kick the existing session; Velocity refuses the newcomer unless told
 * otherwise. Neither is right everywhere: in online mode the newcomer just proved it owns the
 * account, so the old session (often a dead connection the client gave up on) should go; in offline
 * mode anyone can claim any name, so kicking the existing session would let anyone kick anyone.
 * {@link #AUTO} picks the safe behaviour for the proxy's mode.
 */
public enum DuplicateLoginPolicy {

  /** {@link #KICK_EXISTING} in online mode, {@link #REFUSE_NEW} in offline mode. */
  AUTO,

  /** Disconnect the existing session and let the newcomer in, as a vanilla server does. */
  KICK_EXISTING,

  /** Refuse the newcomer and keep the existing session. */
  REFUSE_NEW;

  /**
   * Returns whether the existing session is kicked, for a proxy in the given mode.
   *
   * @param onlineMode whether players are authenticated with Mojang
   * @return {@code true} to kick the existing session, {@code false} to refuse the newcomer
   */
  public boolean kicksExisting(boolean onlineMode) {
    return switch (this) {
      case AUTO -> onlineMode;
      case KICK_EXISTING -> true;
      case REFUSE_NEW -> false;
    };
  }
}
