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

import dev.warp.api.lifecycle.ReadinessHold;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The holds plugins keep on the proxy's readiness. Lock-free: probes read them on the management
 * thread while plugins take and release them on theirs.
 */
public final class ReadinessHolds {

  private static final Logger logger = LoggerFactory.getLogger(ReadinessHolds.class);

  private final Set<Hold> holds = ConcurrentHashMap.newKeySet();

  /**
   * Takes a hold.
   *
   * @param reason why, as the readiness probe reports it
   * @return the hold
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  public ReadinessHold hold(String reason) {
    if (reason.isBlank()) {
      throw new IllegalArgumentException("A readiness hold needs a reason");
    }
    Hold hold = new Hold(reason);
    holds.add(hold);
    logger.info("Readiness held: {}", reason);
    return hold;
  }

  /**
   * Returns whether any hold is held.
   *
   * @return {@code true} while one is
   */
  public boolean isHeld() {
    return !holds.isEmpty();
  }

  /**
   * Returns the reasons of the holds held, sorted.
   *
   * @return the reasons
   */
  public List<String> reasons() {
    return holds.stream().map(Hold::reason).sorted().toList();
  }

  private final class Hold implements ReadinessHold {

    private final String reason;
    private final AtomicBoolean held = new AtomicBoolean(true);

    Hold(String reason) {
      this.reason = reason;
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public boolean isHeld() {
      return held.get();
    }

    @Override
    public void release() {
      if (held.compareAndSet(true, false)) {
        holds.remove(this);
        logger.info("Readiness released: {}", reason);
      }
    }

    @Override
    public String toString() {
      return "ReadinessHold[" + reason + (held.get() ? "" : ", released") + "]";
    }
  }
}
