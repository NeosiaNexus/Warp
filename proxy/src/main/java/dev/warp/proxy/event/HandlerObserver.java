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
package dev.warp.proxy.event;

/** Watches event handlers run, for metrics: how long each took, and which ones failed. */
public interface HandlerObserver {

  /** Watches nothing. */
  HandlerObserver NONE =
      new HandlerObserver() {
        @Override
        public void handled(Object event, long nanos) {}

        @Override
        public void failed(String owner) {}
      };

  /**
   * One handler finished with one event, whether it succeeded or failed.
   *
   * @param event the event
   * @param nanos how long the handler took, its asynchronous completion included
   */
  void handled(Object event, long nanos);

  /**
   * One handler threw, or completed its stage exceptionally.
   *
   * @param owner who registered the handler: a plugin id, or {@code warp}
   */
  void failed(String owner);
}
