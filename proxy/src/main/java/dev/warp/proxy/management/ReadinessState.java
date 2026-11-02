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

import dev.warp.api.lifecycle.DrainPhase;

/**
 * Whether the proxy takes new players, and if not, why: what {@code GET /readyz} and the HAProxy
 * agent check report.
 */
public enum ReadinessState {

  /** Shutting down, or the drain is over and the proxy is about to. */
  STOPPING("stopping", "down"),

  /**
   * Draining: load balancers must send no new player, but the players connected stay. HAProxy's
   * {@code drain} does exactly that.
   */
  DRAINING("draining", "drain"),

  /** Not listening yet, or plugins still loading. */
  STARTING("starting", "down"),

  /** A plugin holds readiness. */
  HELD("held", "down"),

  /** No backend server is registered, and readiness requires one. */
  NO_SERVER("no-server", "down"),

  /**
   * Ready. The agent says {@code up ready}: {@code ready} also clears the administrative drain an
   * earlier {@code drain} left on the HAProxy server slot (a previous pod at the same address),
   * which {@code up} alone never does.
   */
  READY("ready", "up ready");

  private final String label;
  private final String agentReply;

  ReadinessState(String label, String agentReply) {
    this.label = label;
    this.agentReply = agentReply;
  }

  /**
   * Evaluates the proxy's readiness. Never blocks.
   *
   * @param health the proxy
   * @param requireServer whether readiness needs a registered backend server
   * @return the state
   */
  public static ReadinessState of(ProxyHealth health, boolean requireServer) {
    if (health.isStopping()) {
      return STOPPING;
    }
    if (health.isDraining()) {
      return health.drainStatus().phase() == DrainPhase.STOPPING ? STOPPING : DRAINING;
    }
    if (!health.isStarted()) {
      return STARTING;
    }
    if (!health.readinessHolds().isEmpty()) {
      return HELD;
    }
    if (requireServer && health.serverCount() == 0) {
      return NO_SERVER;
    }
    return READY;
  }

  /**
   * Returns the state as {@code GET /readyz} reports it.
   *
   * @return the label, such as {@code no-server}
   */
  public String label() {
    return label;
  }

  /**
   * Returns the line the HAProxy agent check sends, without its line feed.
   *
   * @return {@code up ready}, {@code drain} or {@code down}
   */
  public String agentReply() {
    return agentReply;
  }
}
