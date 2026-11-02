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
package dev.warp.api.event;

/**
 * A registered handler or listener. Closing it unsubscribes.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Subscription extends AutoCloseable {

  /**
   * Returns whether the subscription still receives events.
   *
   * @return {@code false} once unsubscribed, or once its plugin is disabled
   */
  boolean isActive();

  /**
   * Stops delivering events to the handler. An event already being delivered to it skips it from
   * now on. Does nothing if already unsubscribed.
   */
  void unsubscribe();

  /** Same as {@link #unsubscribe()}. */
  @Override
  default void close() {
    unsubscribe();
  }
}
