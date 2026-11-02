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
package dev.warp.api.event.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PlayerDrainEvent decisions")
class PlayerDrainEventTest {

  @Test
  @DisplayName("should transfer to the host and port given")
  void transfer() {
    PlayerDrainEvent.Transfer transfer = new PlayerDrainEvent.Transfer("play.example.net", 25565);

    assertEquals("play.example.net", transfer.host());
    assertEquals(25565, transfer.port());
  }

  @ParameterizedTest(name = "port {0}")
  @ValueSource(ints = {0, -1, 65_536})
  @DisplayName("should refuse a port out of range")
  void badPort(int port) {
    assertThrows(
        IllegalArgumentException.class, () -> new PlayerDrainEvent.Transfer("example.net", port));
  }

  @Test
  @DisplayName("should refuse a blank host")
  void blankHost() {
    assertThrows(IllegalArgumentException.class, () -> new PlayerDrainEvent.Transfer(" ", 25565));
  }

  @Test
  @DisplayName("should keep and kick, the kick with its reason")
  void keepAndKick() {
    assertEquals(new PlayerDrainEvent.Keep(), new PlayerDrainEvent.Keep());
    assertEquals(Component.text("Bye"), new PlayerDrainEvent.Kick(Component.text("Bye")).reason());
  }
}
