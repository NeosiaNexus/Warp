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
package dev.warp.proxy.command;

import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.console.StartupTimings;
import dev.warp.proxy.server.HostRoutes;
import dev.warp.proxy.server.ServerHealth;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** What the administration commands need from the proxy beyond its public API. */
public interface ProxyControl {

  /**
   * Returns where the startup's time went, for /warp startup.
   *
   * @return the timings, or {@code null} when nothing records them (tests)
   */
  default @Nullable StartupTimings startup() {
    return null;
  }

  /**
   * Returns the servers' health: their circuit breakers and ping round trips, for {@code /warp
   * groups}.
   *
   * @return the health, or {@code null} when the proxy does not track it
   */
  default @Nullable ServerHealth health() {
    return null;
  }

  /**
   * Returns the routes of the configuration, for {@code /warp routes}.
   *
   * @return the routes
   */
  default HostRoutes routes() {
    return HostRoutes.EMPTY;
  }

  /**
   * Returns how long the proxy has been running.
   *
   * @return the uptime
   */
  Duration uptime();

  /**
   * Describes the implementation that compresses packets.
   *
   * @return such as {@code libdeflate 1.24 (native)}
   */
  String compression();

  /**
   * Returns the {@code drain} section, to tell what a shutdown will do to the players.
   *
   * @return the drain's configuration
   */
  DrainConfig drain();

  /**
   * Starts the drain for {@code /shutdown}, or returns the one in progress.
   *
   * @param reason shown to the players it disconnects in place of {@code
   *     warp.disconnect.restarting}, or {@code null} for {@code warp.disconnect.restarting}
   * @return completed with the drain's final status
   * @throws IllegalStateException if the proxy is not running
   */
  CompletableFuture<DrainStatus> shutdown(@Nullable Component reason);
}
