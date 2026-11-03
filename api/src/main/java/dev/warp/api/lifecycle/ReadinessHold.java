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
package dev.warp.api.lifecycle;

/**
 * A plugin's hold on the proxy's readiness, taken with {@link
 * dev.warp.api.plugin.PluginContext#holdReadiness(String)}. While any hold is held, the readiness
 * probe ({@code GET /readyz}) answers 503 and the HAProxy agent check answers {@code down}: load
 * balancers and Kubernetes send no new player here. Players already connected are not affected, nor
 * are the liveness and startup probes.
 *
 * <p>Hold readiness for as long as the proxy cannot serve a player as the plugin sees it: a network
 * whose lobbies are discovered at runtime holds it until it found one. Releasing twice does
 * nothing. A hold belongs to the plugin that took it: the proxy releases it when the plugin is
 * disabled or fails to load.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ReadinessHold extends AutoCloseable {

  /**
   * Returns why readiness is held, as the readiness probe reports it.
   *
   * @return the reason given when the hold was taken
   */
  String reason();

  /**
   * Returns whether this hold still holds readiness.
   *
   * @return {@code false} once released
   */
  boolean isHeld();

  /**
   * Releases this hold. The proxy is ready again once no hold is left and nothing else stops it.
   */
  void release();

  /** Releases this hold, as {@link #release()} does. */
  @Override
  default void close() {
    release();
  }
}
