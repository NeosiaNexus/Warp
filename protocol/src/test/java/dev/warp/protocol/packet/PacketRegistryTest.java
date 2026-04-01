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
package dev.warp.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PacketRegistry")
class PacketRegistryTest {

  // ---------------------------------------------------------------------------
  // Builder
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("builder")
  class Builder {

    @Test
    @DisplayName("should reject registration with no version mappings")
    void noMappings() {
      assertThrows(
          IllegalArgumentException.class,
          () -> PacketRegistry.builder().register(Handshake.class, Handshake.CODEC));
    }

    @Test
    @DisplayName("should reject non-ascending version mappings")
    void nonAscendingMappings() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PacketRegistry.builder()
                  .register(
                      StatusRequest.class,
                      StatusRequest.CODEC,
                      VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_9),
                      VersionMapping.map(0x01, ProtocolVersion.MINECRAFT_1_7_2)));
    }

    @Test
    @DisplayName("should reject conflicting packet ID registrations")
    void conflictingIds() {
      assertThrows(
          IllegalStateException.class,
          () ->
              PacketRegistry.builder()
                  .register(
                      StatusRequest.class,
                      StatusRequest.CODEC,
                      VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2))
                  .register(
                      PingRequest.class,
                      PingRequest.CODEC,
                      VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2))
                  .build());
    }

    @Test
    @DisplayName("should accept same type at same ID")
    void sameTypeAtSameId() {
      // Bidirectional packets can be registered twice at the same ID — should NOT throw
      PacketRegistry registry =
          PacketRegistry.builder()
              .register(
                  StatusRequest.class,
                  StatusRequest.CODEC,
                  VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2))
              .register(
                  StatusRequest.class,
                  StatusRequest.CODEC,
                  VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2))
              .build();

      assertNotNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x00));
    }
  }

  // ---------------------------------------------------------------------------
  // Lookup
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("lookup")
  class Lookup {

    @Test
    @DisplayName("should return codec for registered packet ID")
    void registeredPacket() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .register(
                  Handshake.class,
                  Handshake.CODEC,
                  VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2))
              .build();

      assertNotNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x00));
    }

    @Test
    @DisplayName("should return null for unregistered packet ID (blind forwarding)")
    void unregisteredPacket() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .register(
                  Handshake.class,
                  Handshake.CODEC,
                  VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2))
              .build();

      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x42));
    }

    @Test
    @DisplayName("should return null for negative packet ID")
    void negativeId() {
      PacketRegistry registry = PacketRegistry.builder().build();
      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, -1));
    }
  }

  // ---------------------------------------------------------------------------
  // Version resolution
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("version resolution")
  class VersionResolution {

    @Test
    @DisplayName("should resolve version-range mappings correctly")
    void versionRange() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .register(
                  StatusRequest.class,
                  StatusRequest.CODEC,
                  VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2),
                  VersionMapping.map(0x05, ProtocolVersion.MINECRAFT_1_9))
              .build();

      // 1.8 should use ID 0x00
      assertEquals(0x00, registry.packetId(ProtocolVersion.MINECRAFT_1_8, StatusRequest.class));
      // 1.9+ should use ID 0x05
      assertEquals(0x05, registry.packetId(ProtocolVersion.MINECRAFT_1_9, StatusRequest.class));
      assertEquals(0x05, registry.packetId(ProtocolVersion.MINECRAFT_1_21_4, StatusRequest.class));
    }

    @Test
    @DisplayName("should not register packets for versions before the first mapping")
    void beforeFirstMapping() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .register(
                  LoginAcknowledged.class,
                  LoginAcknowledged.CODEC,
                  VersionMapping.map(0x03, ProtocolVersion.MINECRAFT_1_20_2))
              .build();

      // 1.19.4 is before 1.20.2, should not have LoginAcknowledged
      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_19_4, 0x03));
      // 1.20.2 should have it
      assertNotNull(registry.lookup(ProtocolVersion.MINECRAFT_1_20_2, 0x03));
    }
  }

  // ---------------------------------------------------------------------------
  // StateRegistry integration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("StateRegistry")
  class StateRegistryIntegration {

    @Test
    @DisplayName("should resolve handshake packet for all versions")
    void handshake() {
      PacketRegistry registry =
          StateRegistry.get(ProtocolState.HANDSHAKE, PacketDirection.SERVERBOUND);
      assertNotNull(registry.lookup(ProtocolVersion.MINECRAFT_1_7_2, 0x00));
      assertNotNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x00));
    }

    @Test
    @DisplayName("should resolve status packets for all versions")
    void status() {
      PacketRegistry serverbound =
          StateRegistry.get(ProtocolState.STATUS, PacketDirection.SERVERBOUND);
      PacketRegistry clientbound =
          StateRegistry.get(ProtocolState.STATUS, PacketDirection.CLIENTBOUND);

      assertEquals(
          0x00, serverbound.packetId(ProtocolVersion.MINECRAFT_1_21_4, StatusRequest.class));
      assertEquals(0x01, serverbound.packetId(ProtocolVersion.MINECRAFT_1_21_4, PingRequest.class));
      assertEquals(
          0x00, clientbound.packetId(ProtocolVersion.MINECRAFT_1_21_4, StatusResponse.class));
    }

    @Test
    @DisplayName("should resolve login packets with version gating")
    void loginVersionGating() {
      PacketRegistry serverbound =
          StateRegistry.get(ProtocolState.LOGIN, PacketDirection.SERVERBOUND);

      // LoginStart should exist for all versions
      assertEquals(0x00, serverbound.packetId(ProtocolVersion.MINECRAFT_1_7_2, LoginStart.class));

      // LoginAcknowledged should only exist from 1.20.2
      assertThrows(
          IllegalArgumentException.class,
          () -> serverbound.packetId(ProtocolVersion.MINECRAFT_1_19_4, LoginAcknowledged.class));
      assertEquals(
          0x03, serverbound.packetId(ProtocolVersion.MINECRAFT_1_20_2, LoginAcknowledged.class));
    }

    @Test
    @DisplayName("should return empty registry for non-existent direction")
    void emptyRegistry() {
      // HANDSHAKE has no clientbound packets
      PacketRegistry registry =
          StateRegistry.get(ProtocolState.HANDSHAKE, PacketDirection.CLIENTBOUND);
      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x00));
    }
  }
}
