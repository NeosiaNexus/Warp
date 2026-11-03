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

import java.util.concurrent.CompletionStage;

/**
 * A handler that holds the event until the stage it returns completes.
 *
 * @param <E> the event type
 */
@FunctionalInterface
public interface AsyncEventHandler<E> {

  /**
   * Handles an event.
   *
   * @param event the event
   * @return a stage that completes when the handler is done, normally or not
   * @throws Exception logged with the plugin that registered the handler; the other handlers still
   *     run
   */
  CompletionStage<?> handle(E event) throws Exception;
}
