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
package dev.warp.api.event.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProxyDrainEvent")
class ProxyDrainEventTest {

  @Test
  @DisplayName("should give the phase the drain entered")
  void phase() {
    ProxyDrainEvent event =
        new ProxyDrainEvent(
            new DrainStatus(
                DrainPhase.TRANSFERRING,
                DrainTrigger.SIGNAL,
                Instant.EPOCH,
                Duration.ofSeconds(5),
                10,
                0,
                0,
                0,
                0,
                null));

    assertEquals(DrainPhase.TRANSFERRING, event.phase());
  }

  @Test
  @DisplayName("should refuse a proxy that is not draining")
  void notDraining() {
    assertThrows(
        IllegalArgumentException.class, () -> new ProxyDrainEvent(DrainStatus.notDraining(0)));
  }
}
