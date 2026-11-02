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
package dev.warp.api.logging;

import dev.warp.api.event.Subscription;

/**
 * A registered {@link LogSink}, with the counts of what became of its records. Closing it removes
 * the sink: the record it is handling, if any, is its last, and the records still queued are
 * dropped (and counted).
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface LogSinkSubscription extends Subscription {

  /**
   * Returns how many records the sink handled without throwing.
   *
   * @return the count since registration
   */
  long delivered();

  /**
   * Returns how many records the sink threw on.
   *
   * @return the count since registration
   */
  long failed();

  /**
   * Returns how many records the sink never received: logged while its queue was full, or still
   * queued when it was removed.
   *
   * @return the count since registration
   */
  long dropped();
}
