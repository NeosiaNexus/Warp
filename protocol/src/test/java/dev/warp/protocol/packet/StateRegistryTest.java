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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import dev.warp.protocol.packet.play.ChatAcknowledgement;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("StateRegistry")
class StateRegistryTest {

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

  // ---------------------------------------------------------------------------
  // Packet ids against Mojang's reports, from 1.21 on
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("packet ids against Mojang's reports")
  class MojangReportIds {

    /**
     * The protocols whose packet ids are known to be wrong, none today. Each must keep differing
     * from Mojang's report, so that fixing one fails the build until it leaves this set.
     */
    private static final Set<Integer> KNOWN_WRONG = Set.of();

    /**
     * Mojang's name of each packet Warp registers from 1.21 on, in each state and direction, as the
     * reports list it. A packet registered there without a name here fails the check.
     */
    private static final List<PacketName> NAMES =
        List.of(
            new PacketName(HANDSHAKE, SERVERBOUND, Handshake.class, "minecraft:intention"),
            new PacketName(STATUS, SERVERBOUND, StatusRequest.class, "minecraft:status_request"),
            new PacketName(STATUS, SERVERBOUND, PingRequest.class, "minecraft:ping_request"),
            new PacketName(STATUS, CLIENTBOUND, StatusResponse.class, "minecraft:status_response"),
            new PacketName(STATUS, CLIENTBOUND, PongResponse.class, "minecraft:pong_response"),
            new PacketName(LOGIN, SERVERBOUND, LoginStart.class, "minecraft:hello"),
            new PacketName(LOGIN, SERVERBOUND, EncryptionResponse.class, "minecraft:key"),
            new PacketName(
                LOGIN, SERVERBOUND, LoginPluginResponse.class, "minecraft:custom_query_answer"),
            new PacketName(
                LOGIN, SERVERBOUND, LoginAcknowledged.class, "minecraft:login_acknowledged"),
            new PacketName(LOGIN, CLIENTBOUND, LoginDisconnect.class, "minecraft:login_disconnect"),
            new PacketName(LOGIN, CLIENTBOUND, EncryptionRequest.class, "minecraft:hello"),
            new PacketName(LOGIN, CLIENTBOUND, LoginSuccess.class, "minecraft:game_profile"),
            new PacketName(
                LOGIN,
                CLIENTBOUND,
                LoginSuccess.class,
                "minecraft:login_finished",
                ProtocolVersion.MINECRAFT_1_21_2),
            new PacketName(LOGIN, CLIENTBOUND, SetCompression.class, "minecraft:login_compression"),
            new PacketName(LOGIN, CLIENTBOUND, LoginPluginRequest.class, "minecraft:custom_query"),
            new PacketName(
                CONFIGURATION,
                SERVERBOUND,
                ClientInformation.class,
                "minecraft:client_information"),
            new PacketName(
                CONFIGURATION,
                SERVERBOUND,
                AcknowledgeFinishConfiguration.class,
                "minecraft:finish_configuration"),
            new PacketName(CONFIGURATION, SERVERBOUND, KeepAlive.class, "minecraft:keep_alive"),
            new PacketName(
                CONFIGURATION, CLIENTBOUND, ConfigDisconnect.class, "minecraft:disconnect"),
            new PacketName(
                CONFIGURATION,
                CLIENTBOUND,
                FinishConfiguration.class,
                "minecraft:finish_configuration"),
            new PacketName(CONFIGURATION, CLIENTBOUND, KeepAlive.class, "minecraft:keep_alive"),
            new PacketName(PLAY, SERVERBOUND, KeepAlive.class, "minecraft:keep_alive"),
            new PacketName(PLAY, SERVERBOUND, PlayPluginMessage.class, "minecraft:custom_payload"),
            new PacketName(
                PLAY, SERVERBOUND, TabCompleteRequest.class, "minecraft:command_suggestion"),
            new PacketName(PLAY, SERVERBOUND, ChatCommand.class, "minecraft:chat_command"),
            new PacketName(
                PLAY,
                SERVERBOUND,
                AcknowledgeConfiguration.class,
                "minecraft:configuration_acknowledged"),
            new PacketName(
                PLAY, SERVERBOUND, PlayClientSettings.class, "minecraft:client_information"),
            new PacketName(
                PLAY, SERVERBOUND, ResourcePackResponse.class, "minecraft:resource_pack"),
            new PacketName(PLAY, CLIENTBOUND, BundleDelimiter.class, "minecraft:bundle_delimiter"),
            new PacketName(PLAY, CLIENTBOUND, PlayDisconnect.class, "minecraft:disconnect"),
            new PacketName(PLAY, CLIENTBOUND, KeepAlive.class, "minecraft:keep_alive"),
            new PacketName(PLAY, CLIENTBOUND, PlayPluginMessage.class, "minecraft:custom_payload"),
            new PacketName(PLAY, CLIENTBOUND, JoinGame.class, "minecraft:login"),
            new PacketName(PLAY, CLIENTBOUND, SystemChatMessage.class, "minecraft:system_chat"),
            new PacketName(
                PLAY, CLIENTBOUND, StartConfiguration.class, "minecraft:start_configuration"),
            new PacketName(PLAY, CLIENTBOUND, Transfer.class, "minecraft:transfer"),
            new PacketName(
                PLAY, CLIENTBOUND, TabCompleteResponse.class, "minecraft:command_suggestions"));

    @ParameterizedTest(name = "{0}")
    @MethodSource("auditedProtocols")
    @DisplayName("should register every packet at the id Mojang reports")
    void matchReport(ProtocolVersion version, PacketReport report) {
      List<String> mismatches = mismatches(version, report);

      assertTrue(
          mismatches.isEmpty(),
          () ->
              "%s (protocol %d), against Mojang's %s report:\n  %s"
                  .formatted(
                      version.name(),
                      version.protocol(),
                      report.version(),
                      String.join("\n  ", mismatches)));
    }

    // No invocation at all once KNOWN_WRONG is empty.
    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("knownWrongProtocols")
    @DisplayName("should still differ from Mojang's report where ids are known to be wrong")
    void stillKnownWrong(ProtocolVersion version, PacketReport report) {
      List<String> mismatches = mismatches(version, report);

      assertFalse(
          mismatches.isEmpty(),
          () ->
              "%s (protocol %d) matches Mojang's report now: remove it from KNOWN_WRONG"
                  .formatted(version.name(), version.protocol()));
    }

    @Test
    @DisplayName("should have Mojang's report of every protocol from 1.21 on")
    void coverEveryReportedProtocol() {
      List<String> missing =
          reportedProtocols()
              .filter(version -> PacketReport.of(version).isEmpty())
              .map(
                  version ->
                      "%s (protocol %d): no report, run npm run packet-reports -- %s in e2e/"
                          .formatted(version.name(), version.protocol(), version.name()))
              .toList();

      assertTrue(missing.isEmpty(), () -> String.join("\n", missing));
    }

    // Every version, not one per protocol: a version given its predecessor's protocol by mistake
    // shares that predecessor's report, and only its own report shows the protocol it really has.
    @Test
    @DisplayName("should register the protocol the server jar of each report declares")
    void registerDeclaredProtocol() {
      List<String> mismatches = new ArrayList<>();
      for (ProtocolVersion version : ProtocolVersion.values()) {
        PacketReport.generatedFrom(version)
            .filter(report -> report.protocol() != version.protocol())
            .ifPresent(
                report ->
                    mismatches.add(
                        "%s: ProtocolVersion registers protocol %d, its server jar declares %d"
                            .formatted(version.name(), version.protocol(), report.protocol())));
      }

      assertTrue(mismatches.isEmpty(), () -> String.join("\n", mismatches));
    }

    /** One version per protocol from 1.21 on, with Mojang's report of its protocol. */
    static Stream<Arguments> auditedProtocols() {
      return withReports(reportedProtocols().filter(v -> !KNOWN_WRONG.contains(v.protocol())));
    }

    /** The {@link #KNOWN_WRONG} protocols, with Mojang's report of each. */
    static Stream<Arguments> knownWrongProtocols() {
      return withReports(reportedProtocols().filter(v -> KNOWN_WRONG.contains(v.protocol())));
    }

    private static Stream<ProtocolVersion> reportedProtocols() {
      return oneVersionPerProtocol().filter(v -> v.isAtLeast(PacketReport.FIRST_REPORTED));
    }

    private static Stream<Arguments> withReports(Stream<ProtocolVersion> versions) {
      return versions.flatMap(
          v -> PacketReport.of(v).map(report -> Arguments.of(v, report)).stream());
    }

    /** Every packet Warp registers at {@code version} whose id is not the one Mojang reports. */
    private static List<String> mismatches(ProtocolVersion version, PacketReport report) {
      List<String> mismatches = new ArrayList<>();
      for (RegisteredPacket packet : registeredPackets(version)) {
        String name = PacketName.of(NAMES, packet, version);
        if (name == null) {
          mismatches.add(packet + ": no Mojang name in NAMES");
          continue;
        }
        OptionalInt expected = report.id(packet.state(), packet.direction(), name);
        if (!expected.equals(OptionalInt.of(packet.id()))) {
          mismatches.add(
              "%s (%s): expected %s, actual %s"
                  .formatted(packet, name, hex(expected), hex(packet.id())));
        }
      }
      return mismatches;
    }
  }

  // ---------------------------------------------------------------------------
  // Packet ids against minecraft-data, before 1.21
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("packet ids against minecraft-data")
  class MinecraftDataIds {

    private static final PacketIdReference REFERENCE = PacketIdReference.load();

    /**
     * minecraft-data's name for each packet Warp registers, in each state and direction. A packet
     * minecraft-data renamed has one entry per name, each from the version it took that name.
     */
    private static final List<PacketName> NAMES =
        List.of(
            new PacketName(HANDSHAKE, SERVERBOUND, Handshake.class, "set_protocol"),
            new PacketName(STATUS, SERVERBOUND, StatusRequest.class, "ping_start"),
            new PacketName(STATUS, SERVERBOUND, PingRequest.class, "ping"),
            new PacketName(STATUS, CLIENTBOUND, StatusResponse.class, "server_info"),
            new PacketName(STATUS, CLIENTBOUND, PongResponse.class, "ping"),
            new PacketName(LOGIN, SERVERBOUND, LoginStart.class, "login_start"),
            new PacketName(LOGIN, SERVERBOUND, EncryptionResponse.class, "encryption_begin"),
            new PacketName(LOGIN, SERVERBOUND, LoginPluginResponse.class, "login_plugin_response"),
            new PacketName(LOGIN, SERVERBOUND, LoginAcknowledged.class, "login_acknowledged"),
            new PacketName(LOGIN, CLIENTBOUND, LoginDisconnect.class, "disconnect"),
            new PacketName(LOGIN, CLIENTBOUND, EncryptionRequest.class, "encryption_begin"),
            new PacketName(LOGIN, CLIENTBOUND, LoginSuccess.class, "success"),
            new PacketName(LOGIN, CLIENTBOUND, SetCompression.class, "compress"),
            new PacketName(LOGIN, CLIENTBOUND, LoginPluginRequest.class, "login_plugin_request"),
            new PacketName(CONFIGURATION, SERVERBOUND, ClientInformation.class, "settings"),
            new PacketName(
                CONFIGURATION,
                SERVERBOUND,
                AcknowledgeFinishConfiguration.class,
                "finish_configuration"),
            new PacketName(CONFIGURATION, SERVERBOUND, KeepAlive.class, "keep_alive"),
            new PacketName(CONFIGURATION, CLIENTBOUND, ConfigDisconnect.class, "disconnect"),
            new PacketName(
                CONFIGURATION, CLIENTBOUND, FinishConfiguration.class, "finish_configuration"),
            new PacketName(CONFIGURATION, CLIENTBOUND, KeepAlive.class, "keep_alive"),
            new PacketName(PLAY, SERVERBOUND, KeepAlive.class, "keep_alive"),
            new PacketName(PLAY, SERVERBOUND, PlayPluginMessage.class, "custom_payload"),
            new PacketName(PLAY, SERVERBOUND, TabCompleteRequest.class, "tab_complete"),
            new PacketName(PLAY, SERVERBOUND, LegacyChatMessage.class, "chat"),
            new PacketName(PLAY, SERVERBOUND, ChatCommand.class, "chat_command"),
            new PacketName(PLAY, SERVERBOUND, ChatAcknowledgement.class, "message_acknowledgement"),
            new PacketName(
                PLAY, SERVERBOUND, AcknowledgeConfiguration.class, "configuration_acknowledged"),
            new PacketName(PLAY, SERVERBOUND, PlayClientSettings.class, "settings"),
            new PacketName(PLAY, SERVERBOUND, ResourcePackResponse.class, "resource_pack_receive"),
            new PacketName(PLAY, CLIENTBOUND, BundleDelimiter.class, "bundle_delimiter"),
            new PacketName(PLAY, CLIENTBOUND, PlayDisconnect.class, "kick_disconnect"),
            new PacketName(PLAY, CLIENTBOUND, KeepAlive.class, "keep_alive"),
            new PacketName(PLAY, CLIENTBOUND, PlayPluginMessage.class, "custom_payload"),
            new PacketName(PLAY, CLIENTBOUND, JoinGame.class, "login"),
            new PacketName(PLAY, CLIENTBOUND, Respawn.class, "respawn"),
            new PacketName(PLAY, CLIENTBOUND, SystemChatMessage.class, "chat"),
            new PacketName(
                PLAY,
                CLIENTBOUND,
                SystemChatMessage.class,
                "system_chat",
                ProtocolVersion.MINECRAFT_1_19),
            new PacketName(PLAY, CLIENTBOUND, StartConfiguration.class, "start_configuration"),
            new PacketName(PLAY, CLIENTBOUND, Transfer.class, "transfer"),
            new PacketName(PLAY, CLIENTBOUND, TabCompleteResponse.class, "tab_complete"),
            new PacketName(PLAY, CLIENTBOUND, BossBar.class, "boss_bar"),
            new PacketName(PLAY, CLIENTBOUND, PlayerInfo.class, "player_info"),
            new PacketName(PLAY, CLIENTBOUND, PlayerInfoUpdate.class, "player_info"),
            new PacketName(PLAY, CLIENTBOUND, PlayerInfoRemove.class, "player_remove"),
            new PacketName(PLAY, CLIENTBOUND, TabListHeaderFooter.class, "playerlist_header"),
            new PacketName(PLAY, CLIENTBOUND, ClearTitles.class, "title"),
            new PacketName(
                PLAY,
                CLIENTBOUND,
                ClearTitles.class,
                "clear_titles",
                ProtocolVersion.MINECRAFT_1_17));

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreportedProtocols")
    @DisplayName("should match minecraft-data for every registered packet")
    void matchReference(ProtocolVersion version) {
      assumeTrue(
          REFERENCE.checked().contains(version.protocol()),
          () -> "minecraft-data has no data for protocol " + version.protocol());

      List<String> mismatches = new ArrayList<>();
      for (RegisteredPacket packet : registeredPackets(version)) {
        String name = PacketName.of(NAMES, packet, version);
        if (name == null) {
          mismatches.add(packet + ": no minecraft-data name in NAMES");
          continue;
        }
        OptionalInt expected =
            REFERENCE.id(packet.state(), packet.direction(), name, version.protocol());
        if (!expected.equals(OptionalInt.of(packet.id()))) {
          mismatches.add(
              "%s (%s): expected %s, actual %s"
                  .formatted(packet, name, hex(expected), hex(packet.id())));
        }
      }

      assertTrue(mismatches.isEmpty(), () -> version + "\n" + String.join("\n", mismatches));
    }

    @Test
    @DisplayName("should cover every protocol before Mojang's reports")
    void coverEveryProtocol() {
      Set<Integer> registered =
          unreportedProtocols()
              .map(ProtocolVersion::protocol)
              .collect(Collectors.toCollection(TreeSet::new));

      Set<Integer> covered = new TreeSet<>(REFERENCE.checked());
      covered.addAll(REFERENCE.unchecked());

      assertTrue(
          covered.containsAll(registered),
          () -> "packet-ids.txt is stale: run npm run packet-ids in e2e/");
    }

    /** One version per protocol before 1.21, the first one Mojang's reports cover. */
    static Stream<ProtocolVersion> unreportedProtocols() {
      return oneVersionPerProtocol().filter(v -> v.isOlderThan(PacketReport.FIRST_REPORTED));
    }
  }

  // ---------------------------------------------------------------------------
  // Chat command (#47)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("chat command")
  class ChatCommandIds {

    private final PacketRegistry serverbound = StateRegistry.get(PLAY, SERVERBOUND);

    @ParameterizedTest(name = "{0}: unsigned at {1}, signed at {2}")
    @MethodSource("commandIds")
    @DisplayName("should decode the unsigned command and forward the signed one untouched")
    void decodeOnlyUnsignedCommand(ProtocolVersion version, int unsigned, int signed) {
      assertSame(ChatCommand.CODEC, serverbound.lookup(version, unsigned));
      assertNull(serverbound.lookup(version, signed));
    }

    /**
     * Every version from 1.20.5, with the ids of its two command packets: the unsigned command
     * stayed at 0x04 when the signed one split off at 0x05, and both moved together since (Mojang's
     * reports, from 1.21 on).
     */
    static Stream<Arguments> commandIds() {
      return ProtocolVersion.values().stream()
          .filter(v -> v.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5))
          .map(v -> Arguments.of(v, unsignedCommandId(v), unsignedCommandId(v) + 1));
    }

    private static int unsignedCommandId(ProtocolVersion version) {
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_26_1)) {
        return 0x07;
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_6)) {
        return 0x06;
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
        return 0x05;
      }
      return 0x04;
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

  // ---------------------------------------------------------------------------
  // Registered packets and their names
  // ---------------------------------------------------------------------------

  /** One version per protocol: game versions sharing a protocol share every id. */
  private static Stream<ProtocolVersion> oneVersionPerProtocol() {
    Map<Integer, ProtocolVersion> byProtocol = new HashMap<>();
    ProtocolVersion.values().forEach(v -> byProtocol.putIfAbsent(v.protocol(), v));
    return byProtocol.values().stream().sorted();
  }

  /** Every packet Warp registers at {@code version}, in every state and direction. */
  private static List<RegisteredPacket> registeredPackets(ProtocolVersion version) {
    List<RegisteredPacket> packets = new ArrayList<>();
    for (ProtocolState state : ProtocolState.values()) {
      for (PacketDirection direction : PacketDirection.values()) {
        PacketRegistry registry = StateRegistry.get(state, direction);
        for (Class<? extends Packet> type : PACKET_TYPES) {
          try {
            int id = registry.packetId(version, type);
            packets.add(new RegisteredPacket(state, direction, type, id));
          } catch (IllegalArgumentException notRegistered) {
            // registered in another state, direction or range of versions
          }
        }
      }
    }
    return packets;
  }

  private static String hex(OptionalInt id) {
    return id.isPresent() ? hex(id.getAsInt()) : "absent";
  }

  private static String hex(int id) {
    return "0x%02X".formatted(id);
  }

  /** A packet type Warp registers in one state and direction, at the id it has in one version. */
  private record RegisteredPacket(
      ProtocolState state, PacketDirection direction, Class<? extends Packet> type, int id) {

    @Override
    public String toString() {
      return state + " " + direction + " " + type.getSimpleName();
    }
  }

  /**
   * A reference's name for a packet type in one state and direction, from {@code since} on.
   *
   * @param state the protocol state
   * @param direction the packet direction
   * @param type the Warp packet type
   * @param name the reference's name of the packet
   * @param since the first version the packet has this name in
   */
  private record PacketName(
      ProtocolState state,
      PacketDirection direction,
      Class<? extends Packet> type,
      String name,
      ProtocolVersion since) {

    /** A name the packet has kept in every version. */
    PacketName(
        ProtocolState state, PacketDirection direction, Class<? extends Packet> type, String name) {
      this(state, direction, type, name, ProtocolVersion.oldest());
    }

    /** The name {@code names} give {@code packet} in {@code version}, if any. */
    static @Nullable String of(
        List<PacketName> names, RegisteredPacket packet, ProtocolVersion version) {
      return names.stream()
          .filter(n -> n.state() == packet.state() && n.direction() == packet.direction())
          .filter(n -> n.type() == packet.type() && version.isAtLeast(n.since()))
          .max(Comparator.comparing(PacketName::since))
          .map(PacketName::name)
          .orElse(null);
    }
  }
}
