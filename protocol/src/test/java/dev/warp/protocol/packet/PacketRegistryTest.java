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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;

import java.util.List;

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
    @DisplayName("should reject two mappings starting at the same version")
    void sameStartMappings() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PacketRegistry.builder()
                  .register(
                      StatusRequest.class,
                      StatusRequest.CODEC,
                      VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_9),
                      VersionMapping.map(0x01, ProtocolVersion.MINECRAFT_1_9)));
    }

    @Test
    @DisplayName("should reject a negative packet ID")
    void negativePacketId() {
      assertThrows(
          IllegalArgumentException.class,
          () -> VersionMapping.map(-1, ProtocolVersion.MINECRAFT_1_7_2));
    }

    @Test
    @DisplayName("should reject a bounded mapping that is not the last one")
    void boundedMappingNotLast() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PacketRegistry.builder()
                  .register(
                      StatusRequest.class,
                      StatusRequest.CODEC,
                      VersionMapping.map(
                          0x00, ProtocolVersion.MINECRAFT_1_7_2, ProtocolVersion.MINECRAFT_1_8),
                      VersionMapping.map(0x01, ProtocolVersion.MINECRAFT_1_9)));
    }

    @Test
    @DisplayName("should reject a mapping whose last version precedes its first")
    void emptyRange() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              VersionMapping.map(
                  0x00, ProtocolVersion.MINECRAFT_1_12, ProtocolVersion.MINECRAFT_1_9));
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
    @DisplayName("should not decode encode-only packets, but still encode them")
    void encodeOnlyPacket() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .registerEncodeOnly(
                  Handshake.class,
                  Handshake.CODEC,
                  VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2))
              .build();

      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x00));
      PacketRegistry.Encoding encoding =
          registry.encoding(ProtocolVersion.MINECRAFT_1_21_4, Handshake.class);
      assertEquals(0x00, encoding.packetId());
      assertSame(Handshake.CODEC, encoding.codec());
    }

    @Test
    @DisplayName("should return null for IDs outside those registered, on either side")
    void idsOutsideTable() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .register(
                  Handshake.class,
                  Handshake.CODEC,
                  VersionMapping.map(0x02, ProtocolVersion.MINECRAFT_1_7_2))
              .build();

      assertNotNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x02));
      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x03));
      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_21_4, -1));
    }

    @Test
    @DisplayName("should watch watched packets instead of decoding them, and still encode them")
    void watchedPacket() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .registerWatched(
                  BossBar.class,
                  BossBar.CODEC,
                  BossBar.WATCH,
                  VersionMapping.map(0x0C, ProtocolVersion.MINECRAFT_1_9))
              .build();

      assertSame(BossBar.WATCH, registry.lookup(ProtocolVersion.MINECRAFT_1_12_2, 0x0C));
      PacketRegistry.Encoding encoding =
          registry.encoding(ProtocolVersion.MINECRAFT_1_12_2, BossBar.class);
      assertEquals(0x0C, encoding.packetId());
      assertSame(BossBar.CODEC, encoding.codec());
    }

    @Test
    @DisplayName("should watch a packet registered without a codec, and refuse to encode it")
    void watchedOnlyPacket() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .registerWatched(
                  PlayerInfoUpdate.class,
                  PlayerInfoUpdate.WATCH,
                  VersionMapping.map(0x3A, ProtocolVersion.MINECRAFT_1_19_4))
              .build();

      assertSame(PlayerInfoUpdate.WATCH, registry.lookup(ProtocolVersion.MINECRAFT_1_20_1, 0x3A));
      assertEquals(
          0x3A, registry.packetId(ProtocolVersion.MINECRAFT_1_20_1, PlayerInfoUpdate.class));
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.encoding(ProtocolVersion.MINECRAFT_1_20_1, PlayerInfoUpdate.class));
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
    @DisplayName("should not register a removed packet after the last version of its mapping")
    void afterBoundedMapping() {
      PacketRegistry registry =
          PacketRegistry.builder()
              .register(
                  StatusRequest.class,
                  StatusRequest.CODEC,
                  VersionMapping.map(0x00, ProtocolVersion.MINECRAFT_1_7_2),
                  VersionMapping.map(
                      0x05, ProtocolVersion.MINECRAFT_1_9, ProtocolVersion.MINECRAFT_1_12_2))
              .build();

      assertEquals(0x05, registry.packetId(ProtocolVersion.MINECRAFT_1_12_2, StatusRequest.class));
      assertNotNull(registry.lookup(ProtocolVersion.MINECRAFT_1_12_2, 0x05));
      assertNull(registry.lookup(ProtocolVersion.MINECRAFT_1_13, 0x05));
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.packetId(ProtocolVersion.MINECRAFT_1_13, StatusRequest.class));
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
    @DisplayName("should watch the tab list and boss bars before 1.20.2 only, never decode them")
    void switchTrackingBefore1202() {
      PacketRegistry clientbound =
          StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);

      assertSame(
          BossBar.WATCH,
          clientbound.lookup(
              ProtocolVersion.MINECRAFT_1_20_1,
              clientbound.packetId(ProtocolVersion.MINECRAFT_1_20_1, BossBar.class)));
      assertSame(
          PlayerInfo.WATCH,
          clientbound.lookup(
              ProtocolVersion.MINECRAFT_1_19_2,
              clientbound.packetId(ProtocolVersion.MINECRAFT_1_19_2, PlayerInfo.class)));
      assertSame(
          PlayerInfoUpdate.WATCH,
          clientbound.lookup(
              ProtocolVersion.MINECRAFT_1_20_1,
              clientbound.packetId(ProtocolVersion.MINECRAFT_1_20_1, PlayerInfoUpdate.class)));
      assertSame(
          PlayerInfoRemove.WATCH,
          clientbound.lookup(
              ProtocolVersion.MINECRAFT_1_20_1,
              clientbound.packetId(ProtocolVersion.MINECRAFT_1_20_1, PlayerInfoRemove.class)));
      for (var type :
          List.of(
              BossBar.class,
              PlayerInfoUpdate.class,
              PlayerInfoRemove.class,
              TabListHeaderFooter.class,
              ClearTitles.class,
              Respawn.class)) {
        assertThrows(
            IllegalArgumentException.class,
            () -> clientbound.packetId(ProtocolVersion.MINECRAFT_1_20_2, type),
            type.getSimpleName());
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> clientbound.packetId(ProtocolVersion.MINECRAFT_1_19_3, PlayerInfo.class));
      // From 1.20.2 the configuration phase resets the client: boss bars stay opaque.
      assertNull(clientbound.lookup(ProtocolVersion.MINECRAFT_1_20_2, 0x0A));
    }

    @Test
    @DisplayName("should decode chat lines up to 1.18.2 and chat commands from 1.19")
    void commandsAcrossVersions() {
      PacketRegistry serverbound =
          StateRegistry.get(ProtocolState.PLAY, PacketDirection.SERVERBOUND);

      assertEquals(
          0x01, serverbound.packetId(ProtocolVersion.MINECRAFT_1_8, LegacyChatMessage.class));
      assertEquals(
          0x02, serverbound.packetId(ProtocolVersion.MINECRAFT_1_12_2, LegacyChatMessage.class));
      assertEquals(
          0x03, serverbound.packetId(ProtocolVersion.MINECRAFT_1_18_2, LegacyChatMessage.class));
      assertThrows(
          IllegalArgumentException.class,
          () -> serverbound.packetId(ProtocolVersion.MINECRAFT_1_19, LegacyChatMessage.class));
      assertEquals(0x03, serverbound.packetId(ProtocolVersion.MINECRAFT_1_19, ChatCommand.class));
      assertEquals(0x04, serverbound.packetId(ProtocolVersion.MINECRAFT_1_19_2, ChatCommand.class));
      assertEquals(0x04, serverbound.packetId(ProtocolVersion.MINECRAFT_1_19_3, ChatCommand.class));
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
