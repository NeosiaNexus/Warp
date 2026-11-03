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
package dev.warp.proxy.event.player;

import dev.warp.api.event.player.LoginEvent;
import dev.warp.api.player.Player;

import java.util.Objects;

/** {@link LoginEvent} as the proxy fires it. */
public final class LoginEventImpl implements LoginEvent {

  private final Player player;
  private volatile Result result;

  /**
   * Creates the event, proposing to let the player in.
   *
   * @param player the player logging in
   */
  public LoginEventImpl(Player player) {
    this(player, new Allowed());
  }

  /**
   * Creates the event.
   *
   * @param player the player logging in
   * @param proposed the result handlers start from: {@link Denied} when the proxy is full
   */
  public LoginEventImpl(Player player, Result proposed) {
    this.player = player;
    this.result = Objects.requireNonNull(proposed, "proposed");
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public Result result() {
    return result;
  }

  @Override
  public void setResult(Result result) {
    this.result = Objects.requireNonNull(result, "result");
  }
}
