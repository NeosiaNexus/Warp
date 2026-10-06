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

import static dev.warp.protocol.ProtocolState.CONFIGURATION;
import static dev.warp.protocol.ProtocolState.HANDSHAKE;
import static dev.warp.protocol.ProtocolState.LOGIN;
import static dev.warp.protocol.ProtocolState.PLAY;
import static dev.warp.protocol.ProtocolState.STATUS;
import static dev.warp.protocol.packet.PacketDirection.CLIENTBOUND;
import static dev.warp.protocol.packet.PacketDirection.SERVERBOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.config.ConfigPacket;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.handshake.HandshakePacket;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.EncryptionResponse;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginPacket;
import dev.warp.protocol.packet.login.LoginPluginRequest;
import dev.warp.protocol.packet.login.LoginPluginResponse;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.login.SetCompression;
import dev.warp.protocol.packet.play.AcknowledgeConfiguration;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.PlayClientSettings;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabCompleteRequest;
import dev.warp.protocol.packet.play.TabCompleteResponse;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.protocol.packet.play.Transfer;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.PongResponse;
import dev.warp.protocol.packet.status.StatusPacket;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("StateRegistry")
class StateRegistryTest {

  // ---------------------------------------------------------------------------
  // Packet ids against the reference table
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("packet ids")
  class PacketIds {

    private static final PacketIdReference REFERENCE = PacketIdReference.load();

    /** Every packet type Warp defines, whatever state registers it. */
    private static final List<Class<? extends Packet>> PACKET_TYPES =
        Stream.of(
                HandshakePacket.class,
                StatusPacket.class,
                LoginPacket.class,
                ConfigPacket.class,
                PlayPacket.class)
            .flatMap(family -> Arrays.stream(family.getPermittedSubclasses()))
            .<Class<? extends Packet>>map(type -> type.asSubclass(Packet.class))
            .toList();

    /**
     * minecraft-data's name for each packet Warp registers, in each state and direction. A packet
     * minecraft-data renamed has one entry per name, each from the version it took that name.
     */
    private static final List<ReferenceName> REFERENCE_NAMES =
        List.of(
            new ReferenceName(HANDSHAKE, SERVERBOUND, Handshake.class, "set_protocol"),
            new ReferenceName(STATUS, SERVERBOUND, StatusRequest.class, "ping_start"),
            new ReferenceName(STATUS, SERVERBOUND, PingRequest.class, "ping"),
            new ReferenceName(STATUS, CLIENTBOUND, StatusResponse.class, "server_info"),
            new ReferenceName(STATUS, CLIENTBOUND, PongResponse.class, "ping"),
            new ReferenceName(LOGIN, SERVERBOUND, LoginStart.class, "login_start"),
            new ReferenceName(LOGIN, SERVERBOUND, EncryptionResponse.class, "encryption_begin"),
            new ReferenceName(
                LOGIN, SERVERBOUND, LoginPluginResponse.class, "login_plugin_response"),
            new ReferenceName(LOGIN, SERVERBOUND, LoginAcknowledged.class, "login_acknowledged"),
            new ReferenceName(LOGIN, CLIENTBOUND, LoginDisconnect.class, "disconnect"),
            new ReferenceName(LOGIN, CLIENTBOUND, EncryptionRequest.class, "encryption_begin"),
            new ReferenceName(LOGIN, CLIENTBOUND, LoginSuccess.class, "success"),
            new ReferenceName(LOGIN, CLIENTBOUND, SetCompression.class, "compress"),
            new ReferenceName(LOGIN, CLIENTBOUND, LoginPluginRequest.class, "login_plugin_request"),
            new ReferenceName(CONFIGURATION, SERVERBOUND, ClientInformation.class, "settings"),
            new ReferenceName(
                CONFIGURATION,
                SERVERBOUND,
                AcknowledgeFinishConfiguration.class,
                "finish_configuration"),
            new ReferenceName(CONFIGURATION, SERVERBOUND, KeepAlive.class, "keep_alive"),
            new ReferenceName(CONFIGURATION, CLIENTBOUND, ConfigDisconnect.class, "disconnect"),
            new ReferenceName(
                CONFIGURATION, CLIENTBOUND, FinishConfiguration.class, "finish_configuration"),
            new ReferenceName(CONFIGURATION, CLIENTBOUND, KeepAlive.class, "keep_alive"),
            new ReferenceName(PLAY, SERVERBOUND, KeepAlive.class, "keep_alive"),
            new ReferenceName(PLAY, SERVERBOUND, PlayPluginMessage.class, "custom_payload"),
            new ReferenceName(PLAY, SERVERBOUND, TabCompleteRequest.class, "tab_complete"),
            new ReferenceName(PLAY, SERVERBOUND, LegacyChatMessage.class, "chat"),
            new ReferenceName(PLAY, SERVERBOUND, ChatCommand.class, "chat_command"),
            new ReferenceName(
                PLAY, SERVERBOUND, AcknowledgeConfiguration.class, "configuration_acknowledged"),
            new ReferenceName(PLAY, SERVERBOUND, PlayClientSettings.class, "settings"),
            new ReferenceName(
                PLAY, SERVERBOUND, ResourcePackResponse.class, "resource_pack_receive"),
            new ReferenceName(PLAY, CLIENTBOUND, BundleDelimiter.class, "bundle_delimiter"),
            new ReferenceName(PLAY, CLIENTBOUND, PlayDisconnect.class, "kick_disconnect"),
            new ReferenceName(PLAY, CLIENTBOUND, KeepAlive.class, "keep_alive"),
            new ReferenceName(PLAY, CLIENTBOUND, PlayPluginMessage.class, "custom_payload"),
            new ReferenceName(PLAY, CLIENTBOUND, JoinGame.class, "login"),
            new ReferenceName(PLAY, CLIENTBOUND, Respawn.class, "respawn"),
            new ReferenceName(PLAY, CLIENTBOUND, SystemChatMessage.class, "chat"),
            new ReferenceName(
                PLAY,
                CLIENTBOUND,
                SystemChatMessage.class,
                "system_chat",
                ProtocolVersion.MINECRAFT_1_19),
            new ReferenceName(PLAY, CLIENTBOUND, StartConfiguration.class, "start_configuration"),
            new ReferenceName(PLAY, CLIENTBOUND, Transfer.class, "transfer"),
            new ReferenceName(PLAY, CLIENTBOUND, TabCompleteResponse.class, "tab_complete"),
            new ReferenceName(PLAY, CLIENTBOUND, BossBar.class, "boss_bar"),
            new ReferenceName(PLAY, CLIENTBOUND, PlayerInfo.class, "player_info"),
            new ReferenceName(PLAY, CLIENTBOUND, PlayerInfoUpdate.class, "player_info"),
            new ReferenceName(PLAY, CLIENTBOUND, PlayerInfoRemove.class, "player_remove"),
            new ReferenceName(PLAY, CLIENTBOUND, TabListHeaderFooter.class, "playerlist_header"),
            new ReferenceName(PLAY, CLIENTBOUND, ClearTitles.class, "title"),
            new ReferenceName(
                PLAY,
                CLIENTBOUND,
                ClearTitles.class,
                "clear_titles",
                ProtocolVersion.MINECRAFT_1_17));

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocols")
    @DisplayName("should match minecraft-data for every registered packet")
    void matchReference(ProtocolVersion version) {
      assumeTrue(
          REFERENCE.checked().contains(version.protocol()),
          () -> "minecraft-data has no data for protocol " + version.protocol());
      assumeFalse(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_5),
          "packet ids for 1.21.5+ not audited yet (#48)");

      List<String> mismatches = new ArrayList<>();
      for (ProtocolState state : ProtocolState.values()) {
        for (PacketDirection direction : PacketDirection.values()) {
          PacketRegistry registry = StateRegistry.get(state, direction);
          for (Class<? extends Packet> type : PACKET_TYPES) {
            OptionalInt registered = registeredId(registry, version, type);
            if (registered.isEmpty()) {
              continue;
            }
            String where = state + " " + direction + " " + type.getSimpleName();
            String name = referenceName(state, direction, type, version);
            if (name == null) {
              mismatches.add(where + ": no minecraft-data name in REFERENCE_NAMES");
              continue;
            }
            OptionalInt expected = REFERENCE.id(state, direction, name, version.protocol());
            if (!expected.equals(registered)) {
              mismatches.add(
                  where + " (" + name + "): " + hex(registered) + ", expected " + hex(expected));
            }
          }
        }
      }

      assertTrue(mismatches.isEmpty(), () -> version + "\n" + String.join("\n", mismatches));
    }

    @Test
    @DisplayName("should cover every protocol Warp registers")
    void coverEveryProtocol() {
      Set<Integer> registered =
          ProtocolVersion.values().stream()
              .map(ProtocolVersion::protocol)
              .collect(Collectors.toCollection(TreeSet::new));

      Set<Integer> covered = new TreeSet<>(REFERENCE.checked());
      covered.addAll(REFERENCE.unchecked());

      assertEquals(registered, covered, "packet-ids.txt is stale: run npm run packet-ids in e2e/");
    }

    /** One version per protocol: game versions sharing a protocol share every id. */
    static Stream<ProtocolVersion> protocols() {
      Map<Integer, ProtocolVersion> byProtocol = new HashMap<>();
      ProtocolVersion.values().forEach(v -> byProtocol.putIfAbsent(v.protocol(), v));
      return byProtocol.values().stream().sorted();
    }

    private static OptionalInt registeredId(
        PacketRegistry registry, ProtocolVersion version, Class<? extends Packet> type) {
      try {
        return OptionalInt.of(registry.packetId(version, type));
      } catch (IllegalArgumentException notRegistered) {
        return OptionalInt.empty();
      }
    }

    /** The name minecraft-data gives {@code type} in {@code version}, if it has one. */
    private static @Nullable String referenceName(
        ProtocolState state,
        PacketDirection direction,
        Class<? extends Packet> type,
        ProtocolVersion version) {
      return REFERENCE_NAMES.stream()
          .filter(n -> n.state() == state && n.direction() == direction && n.type() == type)
          .filter(n -> version.isAtLeast(n.since()))
          .max(Comparator.comparing(ReferenceName::since))
          .map(ReferenceName::name)
          .orElse(null);
    }

    private static String hex(OptionalInt id) {
      return id.isPresent() ? "0x%02X".formatted(id.getAsInt()) : "absent";
    }

    /** minecraft-data's name of a packet type in one state and direction, from {@code since} on. */
    private record ReferenceName(
        ProtocolState state,
        PacketDirection direction,
        Class<? extends Packet> type,
        String name,
        ProtocolVersion since) {

      /** A name the packet has kept in every version. */
      ReferenceName(
          ProtocolState state,
          PacketDirection direction,
          Class<? extends Packet> type,
          String name) {
        this(state, direction, type, name, ProtocolVersion.oldest());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Chat command (#47)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("chat command")
  class ChatCommandIds {

    private final PacketRegistry serverbound = StateRegistry.get(PLAY, SERVERBOUND);

    @ParameterizedTest(name = "{0}")
    @MethodSource("signedCommandSplit")
    @DisplayName("should decode the unsigned command at 0x04 and forward the signed one at 0x05")
    void unsignedCommandAt0x04(ProtocolVersion version) {
      assertSame(ChatCommand.CODEC, serverbound.lookup(version, 0x04));
      assertNull(serverbound.lookup(version, 0x05));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothCommandsMoved")
    @DisplayName("should decode the unsigned command at 0x05 and forward the signed one at 0x06")
    void unsignedCommandAt0x05(ProtocolVersion version) {
      assertSame(ChatCommand.CODEC, serverbound.lookup(version, 0x05));
      assertNull(serverbound.lookup(version, 0x06));
    }

    /** 1.20.5 to 1.21.1: the unsigned command stayed at 0x04, the new signed one took 0x05. */
    static Stream<ProtocolVersion> signedCommandSplit() {
      return ProtocolVersion.values().stream()
          .filter(
              v -> v.isBetween(ProtocolVersion.MINECRAFT_1_20_5, ProtocolVersion.MINECRAFT_1_21_1));
    }

    /** 1.21.2 to 1.21.4: both moved up by one (1.21.5+ is audited in #48). */
    static Stream<ProtocolVersion> bothCommandsMoved() {
      return ProtocolVersion.values().stream()
          .filter(
              v -> v.isBetween(ProtocolVersion.MINECRAFT_1_21_2, ProtocolVersion.MINECRAFT_1_21_4));
    }
  }

  // ---------------------------------------------------------------------------
  // Tab completion
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("tab completion")
  class TabCompletion {

    @Test
    @DisplayName("should refuse to encode before 1.13, whose packets have another layout")
    void refuseBefore113() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_12_2;

      assertThrows(
          IllegalArgumentException.class,
          () -> StateRegistry.get(PLAY, SERVERBOUND).encoding(version, TabCompleteRequest.class));
      assertThrows(
          IllegalArgumentException.class,
          () -> StateRegistry.get(PLAY, CLIENTBOUND).encoding(version, TabCompleteResponse.class));
    }
  }
}
