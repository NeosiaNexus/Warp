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
package dev.warp.proxy.management;

import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.proxy.antibot.AntiBotService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * What the management server reads from the proxy. Every method returns at once and runs no plugin
 * code: a probe must answer even while plugins misbehave.
 */
public interface ProxyHealth {

  /**
   * Returns whether the proxy listens and its plugins are enabled.
   *
   * @return {@code true} once started
   */
  boolean isStarted();

  /**
   * Returns whether the proxy is shutting down.
   *
   * @return {@code true} once its shutdown began
   */
  boolean isStopping();

  /**
   * Returns how many backend servers are registered.
   *
   * @return the count
   */
  int serverCount();

  /**
   * Returns why plugins hold readiness.
   *
   * @return the reasons, empty when none holds it
   */
  List<String> readinessHolds();

  /**
   * Returns whether the proxy drains.
   *
   * @return {@code true} once a drain started
   */
  boolean isDraining();

  /**
   * Returns where the drain stands.
   *
   * @return a snapshot
   */
  DrainStatus drainStatus();

  /**
   * Starts the drain, or returns the one in progress.
   *
   * @param trigger what starts it
   * @return completed with the final status once the proxy is empty or the time-out expired
   * @throws IllegalStateException if the proxy is not running
   */
  CompletableFuture<DrainStatus> drain(DrainTrigger trigger);

  /**
   * Returns the anti-bot, for its status, blocklist and lockdown endpoints.
   *
   * @return the anti-bot, or {@code null} before it loads
   */
  default @Nullable AntiBotService antiBot() {
    return null;
  }
}
