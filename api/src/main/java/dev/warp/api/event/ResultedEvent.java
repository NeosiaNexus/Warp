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
 * An event whose handlers decide an outcome that the proxy then applies.
 *
 * <p>Each handler sees the result left by the handlers before it, and may replace it. The proxy
 * applies the result left by the last handler.
 *
 * @param <R> the result type
 */
public interface ResultedEvent<R> {

  /**
   * Returns the current result.
   *
   * @return the result
   */
  R result();

  /**
   * Replaces the result.
   *
   * @param result the new result
   */
  void setResult(R result);
}
