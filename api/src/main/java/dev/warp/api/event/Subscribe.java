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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a listener as an event handler. Register the listener with {@link
 * EventBus#register(Object)}.
 *
 * <p>The method takes exactly one parameter, the event, and returns either {@code void} or a {@link
 * java.util.concurrent.CompletionStage} that holds the event until it completes. It may have any
 * visibility. It receives events of its parameter's type and of every subtype.
 *
 * <pre>{@code
 * @Subscribe(priority = 100)
 * public void onLogin(LoginEvent event) { ... }
 *
 * @Subscribe
 * public CompletableFuture<Void> onLogin(LoginEvent event) {
 *   return bans.check(event.player().uniqueId()).thenAccept(ban -> ...);
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

  /** Priority of handlers that need to run before most others. */
  int EARLY = 1000;

  /** The default priority. */
  int NORMAL = 0;

  /** Priority of handlers that need to see what most others decided. */
  int LATE = -1000;

  /**
   * When the handler runs relative to the other handlers of the event: higher first. Handlers of
   * equal priority run in registration order.
   *
   * @return the priority
   */
  int priority() default NORMAL;

  /**
   * How long the handler may hold the event, in seconds, before it is reported and skipped.
   *
   * @return the time-out, at least 1
   */
  int timeoutSeconds() default 30;
}
