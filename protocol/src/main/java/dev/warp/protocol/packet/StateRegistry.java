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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_18_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_7_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_8;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_9;
import static dev.warp.protocol.packet.VersionMapping.map;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.EncryptionResponse;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginPluginRequest;
import dev.warp.protocol.packet.login.LoginPluginResponse;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.login.SetCompression;
import dev.warp.protocol.packet.play.AcknowledgeConfiguration;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.PlayClientSettings;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabCompleteRequest;
import dev.warp.protocol.packet.play.TabCompleteResponse;
import dev.warp.protocol.packet.play.Transfer;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.PongResponse;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;

import java.util.EnumMap;
import java.util.Map;

/**
 * Central registry holding all packet-to-ID mappings for every (state, direction) pair.
 *
 * <p>This class is the single source of truth for packet registration. All mappings are populated
 * at class load time and are immutable afterward. Use {@link #get(ProtocolState, PacketDirection)}
 * to obtain the registry for a specific state and direction.
 *
 * <p>Handshake, Status, and Login packet IDs are stable across all supported versions.
 * Configuration and Play state IDs use version-range mappings to track changes across Minecraft
 * releases.
 */
public final class StateRegistry {

  private static final PacketRegistry EMPTY = PacketRegistry.builder().build();
  private static final Map<ProtocolState, Map<PacketDirection, PacketRegistry>> REGISTRIES;

  static {
    REGISTRIES = new EnumMap<>(ProtocolState.class);
    for (ProtocolState state : ProtocolState.values()) {
      REGISTRIES.put(state, new EnumMap<>(PacketDirection.class));
    }

    registerHandshake();
    registerStatus();
    registerLogin();
    registerConfiguration();
    registerPlay();

    // Freeze all inner maps — no further mutations after init
    for (ProtocolState state : ProtocolState.values()) {
      Map<PacketDirection, PacketRegistry> inner = REGISTRIES.get(state);
      if (inner != null) {
        REGISTRIES.put(state, Map.copyOf(inner));
      }
    }
  }

  private StateRegistry() {}

  /**
   * Returns the packet registry for the given state and direction.
   *
   * @param state the protocol state
   * @param direction the packet direction
   * @return the registry, or an empty registry if no packets are registered for this combination
   */
  public static PacketRegistry get(ProtocolState state, PacketDirection direction) {
    Map<PacketDirection, PacketRegistry> byDirection = REGISTRIES.get(state);
    if (byDirection == null) {
      return EMPTY;
    }
    PacketRegistry registry = byDirection.get(direction);
    if (registry == null) {
      return EMPTY;
    }
    return registry;
  }

  /** Returns the direction map for a state, creating it if absent. Never null after init. */
  private static Map<PacketDirection, PacketRegistry> directionMap(ProtocolState state) {
    Map<PacketDirection, PacketRegistry> map = REGISTRIES.get(state);
    if (map == null) {
      throw new IllegalStateException("State not initialized: " + state);
    }
    return map;
  }

  // ---------------------------------------------------------------------------
  // Handshake — single serverbound packet, stable since 1.7.2
  // ---------------------------------------------------------------------------

  private static void registerHandshake() {
    directionMap(ProtocolState.HANDSHAKE)
        .put(
            PacketDirection.SERVERBOUND,
            PacketRegistry.builder()
                .register(Handshake.class, Handshake.CODEC, map(0x00, MINECRAFT_1_7_2))
                .build());
  }

  // ---------------------------------------------------------------------------
  // Status — 4 packets, stable since 1.7.2
  // ---------------------------------------------------------------------------

  private static void registerStatus() {
    directionMap(ProtocolState.STATUS)
        .put(
            PacketDirection.SERVERBOUND,
            PacketRegistry.builder()
                .register(StatusRequest.class, StatusRequest.CODEC, map(0x00, MINECRAFT_1_7_2))
                .register(PingRequest.class, PingRequest.CODEC, map(0x01, MINECRAFT_1_7_2))
                .build());

    directionMap(ProtocolState.STATUS)
        .put(
            PacketDirection.CLIENTBOUND,
            PacketRegistry.builder()
                .register(StatusResponse.class, StatusResponse.CODEC, map(0x00, MINECRAFT_1_7_2))
                .register(PongResponse.class, PongResponse.CODEC, map(0x01, MINECRAFT_1_7_2))
                .build());
  }

  // ---------------------------------------------------------------------------
  // Login — stable IDs, version-gated packets
  // ---------------------------------------------------------------------------

  private static void registerLogin() {
    directionMap(ProtocolState.LOGIN)
        .put(
            PacketDirection.SERVERBOUND,
            PacketRegistry.builder()
                .register(LoginStart.class, LoginStart.CODEC, map(0x00, MINECRAFT_1_7_2))
                .register(
                    EncryptionResponse.class, EncryptionResponse.CODEC, map(0x01, MINECRAFT_1_7_2))
                .register(
                    LoginPluginResponse.class, LoginPluginResponse.CODEC, map(0x02, MINECRAFT_1_13))
                .register(
                    LoginAcknowledged.class, LoginAcknowledged.CODEC, map(0x03, MINECRAFT_1_20_2))
                .build());

    directionMap(ProtocolState.LOGIN)
        .put(
            PacketDirection.CLIENTBOUND,
            PacketRegistry.builder()
                .register(LoginDisconnect.class, LoginDisconnect.CODEC, map(0x00, MINECRAFT_1_7_2))
                .register(
                    EncryptionRequest.class, EncryptionRequest.CODEC, map(0x01, MINECRAFT_1_7_2))
                .register(LoginSuccess.class, LoginSuccess.CODEC, map(0x02, MINECRAFT_1_7_2))
                .register(SetCompression.class, SetCompression.CODEC, map(0x03, MINECRAFT_1_8))
                .register(
                    LoginPluginRequest.class, LoginPluginRequest.CODEC, map(0x04, MINECRAFT_1_13))
                .build());
  }

  // ---------------------------------------------------------------------------
  // Configuration — all since 1.20.2 (protocol 764)
  // ---------------------------------------------------------------------------

  /**
   * Registers only the CONFIG packets the proxy needs to intercept. Everything else is
   * blind-forwarded as raw bytes — faster, more resilient to protocol changes, and impossible to
   * corrupt through decode/re-encode cycles.
   *
   * <p>Intercepted clientbound: Disconnect, FinishConfiguration, KeepAlive. Intercepted
   * serverbound: AcknowledgeFinishConfiguration, KeepAlive. All other CONFIG packets (RegistryData,
   * KnownPacks, PluginMessage, etc.) pass through as opaque ByteBufs via {@code handleBlind()}.
   */
  private static void registerConfiguration() {
    directionMap(ProtocolState.CONFIGURATION)
        .put(
            PacketDirection.SERVERBOUND,
            PacketRegistry.builder()
                .register(
                    ClientInformation.class, ClientInformation.CODEC, map(0x00, MINECRAFT_1_20_2))
                .register(
                    AcknowledgeFinishConfiguration.class,
                    AcknowledgeFinishConfiguration.CODEC,
                    map(0x02, MINECRAFT_1_20_2),
                    map(0x03, MINECRAFT_1_20_5))
                .register(
                    KeepAlive.class,
                    KeepAlive.CODEC,
                    map(0x03, MINECRAFT_1_20_2),
                    map(0x04, MINECRAFT_1_20_5))
                .build());

    directionMap(ProtocolState.CONFIGURATION)
        .put(
            PacketDirection.CLIENTBOUND,
            PacketRegistry.builder()
                .register(
                    ConfigDisconnect.class,
                    ConfigDisconnect.CODEC,
                    map(0x01, MINECRAFT_1_20_2),
                    map(0x02, MINECRAFT_1_20_5))
                .register(
                    FinishConfiguration.class,
                    FinishConfiguration.CODEC,
                    map(0x02, MINECRAFT_1_20_2),
                    map(0x03, MINECRAFT_1_20_5))
                .register(
                    KeepAlive.class,
                    KeepAlive.CODEC,
                    map(0x03, MINECRAFT_1_20_2),
                    map(0x04, MINECRAFT_1_20_5))
                .build());
  }

  // ---------------------------------------------------------------------------
  // Play — proxy-critical subset with version-range ID mappings
  //
  // Decoded on receipt: only what the proxy acts on (keep-alives, its own commands, which arrive as
  // chat lines before 1.19, client settings, state transitions, bundles, disconnects, the entity
  // id). Encode-only: packets the proxy may send but forwards untouched when received, so they
  // keep their original compressed form end to end.
  // ---------------------------------------------------------------------------

  private static void registerPlay() {
    directionMap(ProtocolState.PLAY)
        .put(
            PacketDirection.SERVERBOUND,
            PacketRegistry.builder()
                .register(
                    KeepAlive.class,
                    KeepAlive.CODEC,
                    map(0x00, MINECRAFT_1_7_2),
                    map(0x0B, MINECRAFT_1_9),
                    map(0x0C, MINECRAFT_1_12),
                    map(0x0B, MINECRAFT_1_12_1),
                    map(0x0E, MINECRAFT_1_13),
                    map(0x0F, MINECRAFT_1_14),
                    map(0x10, MINECRAFT_1_16),
                    map(0x0F, MINECRAFT_1_17),
                    map(0x11, MINECRAFT_1_19),
                    map(0x12, MINECRAFT_1_19_1),
                    map(0x11, MINECRAFT_1_19_3),
                    map(0x12, MINECRAFT_1_19_4),
                    map(0x14, MINECRAFT_1_20_2),
                    map(0x15, MINECRAFT_1_20_3),
                    map(0x18, MINECRAFT_1_20_5),
                    map(0x1A, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    PlayPluginMessage.class,
                    PlayPluginMessage.CODEC,
                    map(0x17, MINECRAFT_1_7_2),
                    map(0x09, MINECRAFT_1_9),
                    map(0x0A, MINECRAFT_1_12),
                    map(0x09, MINECRAFT_1_12_1),
                    map(0x0A, MINECRAFT_1_13),
                    map(0x0B, MINECRAFT_1_14),
                    map(0x0A, MINECRAFT_1_17),
                    map(0x0C, MINECRAFT_1_19),
                    map(0x0D, MINECRAFT_1_19_1),
                    map(0x0C, MINECRAFT_1_19_3),
                    map(0x0D, MINECRAFT_1_19_4),
                    map(0x0F, MINECRAFT_1_20_2),
                    map(0x10, MINECRAFT_1_20_3),
                    map(0x12, MINECRAFT_1_20_5),
                    map(0x14, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    TabCompleteRequest.class,
                    TabCompleteRequest.CODEC,
                    map(0x05, MINECRAFT_1_13),
                    map(0x06, MINECRAFT_1_14),
                    map(0x08, MINECRAFT_1_19),
                    map(0x09, MINECRAFT_1_19_1),
                    map(0x08, MINECRAFT_1_19_3),
                    map(0x09, MINECRAFT_1_19_4),
                    map(0x0A, MINECRAFT_1_20_2),
                    map(0x0B, MINECRAFT_1_20_5),
                    map(0x0D, MINECRAFT_1_21_2))
                .register(
                    LegacyChatMessage.class,
                    LegacyChatMessage.CODEC,
                    map(0x01, MINECRAFT_1_7_2),
                    map(0x02, MINECRAFT_1_9),
                    map(0x03, MINECRAFT_1_12),
                    map(0x02, MINECRAFT_1_12_1),
                    map(0x03, MINECRAFT_1_14, MINECRAFT_1_18_2))
                // Commands from 1.19; from 1.20.5, only the unsigned ones. The signed command split
                // off in 1.20.5 (0x05, then 0x06 from 1.21.2) only carries commands whose declared
                // syntax has a message argument (/msg, /say), and is forwarded untouched.
                .register(
                    ChatCommand.class,
                    ChatCommand.CODEC,
                    map(0x03, MINECRAFT_1_19),
                    map(0x04, MINECRAFT_1_19_1),
                    map(0x05, MINECRAFT_1_21_2))
                .register(
                    AcknowledgeConfiguration.class,
                    AcknowledgeConfiguration.CODEC,
                    map(0x0B, MINECRAFT_1_20_2),
                    map(0x0C, MINECRAFT_1_20_5),
                    map(0x0E, MINECRAFT_1_21_2))
                .register(
                    PlayClientSettings.class,
                    PlayClientSettings.CODEC,
                    map(0x15, MINECRAFT_1_7_2),
                    map(0x04, MINECRAFT_1_9),
                    map(0x05, MINECRAFT_1_12),
                    map(0x04, MINECRAFT_1_12_1),
                    map(0x05, MINECRAFT_1_14),
                    map(0x07, MINECRAFT_1_19),
                    map(0x08, MINECRAFT_1_19_1),
                    map(0x07, MINECRAFT_1_19_3),
                    map(0x08, MINECRAFT_1_19_4),
                    map(0x09, MINECRAFT_1_20_2),
                    map(0x0A, MINECRAFT_1_20_5),
                    map(0x0C, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    ResourcePackResponse.class,
                    ResourcePackResponse.CODEC,
                    map(0x19, MINECRAFT_1_8),
                    map(0x16, MINECRAFT_1_9),
                    map(0x18, MINECRAFT_1_12),
                    map(0x1D, MINECRAFT_1_13),
                    map(0x1F, MINECRAFT_1_14),
                    map(0x20, MINECRAFT_1_16),
                    map(0x21, MINECRAFT_1_16_2),
                    map(0x23, MINECRAFT_1_19),
                    map(0x24, MINECRAFT_1_19_1),
                    map(0x27, MINECRAFT_1_20_2),
                    map(0x28, MINECRAFT_1_20_3),
                    map(0x2B, MINECRAFT_1_20_5),
                    map(0x2D, MINECRAFT_1_21_2),
                    map(0x2F, MINECRAFT_1_21_4))
                .build());

    directionMap(ProtocolState.PLAY)
        .put(
            PacketDirection.CLIENTBOUND,
            PacketRegistry.builder()
                .register(BundleDelimiter.class, BundleDelimiter.CODEC, map(0x00, MINECRAFT_1_19_4))
                .register(
                    PlayDisconnect.class,
                    PlayDisconnect.CODEC,
                    map(0x40, MINECRAFT_1_7_2),
                    map(0x1A, MINECRAFT_1_9),
                    map(0x1B, MINECRAFT_1_13),
                    map(0x1A, MINECRAFT_1_14),
                    map(0x1B, MINECRAFT_1_15),
                    map(0x1A, MINECRAFT_1_16),
                    map(0x19, MINECRAFT_1_16_2),
                    map(0x1A, MINECRAFT_1_17),
                    map(0x17, MINECRAFT_1_19),
                    map(0x19, MINECRAFT_1_19_1),
                    map(0x17, MINECRAFT_1_19_3),
                    map(0x1A, MINECRAFT_1_19_4),
                    map(0x1B, MINECRAFT_1_20_2),
                    map(0x1D, MINECRAFT_1_20_5))
                .register(
                    KeepAlive.class,
                    KeepAlive.CODEC,
                    map(0x00, MINECRAFT_1_7_2),
                    map(0x1F, MINECRAFT_1_9),
                    map(0x21, MINECRAFT_1_13),
                    map(0x20, MINECRAFT_1_14),
                    map(0x21, MINECRAFT_1_15),
                    map(0x20, MINECRAFT_1_16),
                    map(0x1F, MINECRAFT_1_16_2),
                    map(0x21, MINECRAFT_1_17),
                    map(0x1E, MINECRAFT_1_19),
                    map(0x20, MINECRAFT_1_19_1),
                    map(0x1F, MINECRAFT_1_19_3),
                    map(0x23, MINECRAFT_1_19_4),
                    map(0x24, MINECRAFT_1_20_2),
                    map(0x26, MINECRAFT_1_20_5),
                    map(0x27, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    PlayPluginMessage.class,
                    PlayPluginMessage.CODEC,
                    map(0x3F, MINECRAFT_1_7_2),
                    map(0x18, MINECRAFT_1_9),
                    map(0x19, MINECRAFT_1_13),
                    map(0x18, MINECRAFT_1_14),
                    map(0x19, MINECRAFT_1_15),
                    map(0x18, MINECRAFT_1_16),
                    map(0x17, MINECRAFT_1_16_2),
                    map(0x18, MINECRAFT_1_17),
                    map(0x15, MINECRAFT_1_19),
                    map(0x16, MINECRAFT_1_19_1),
                    map(0x15, MINECRAFT_1_19_3),
                    map(0x17, MINECRAFT_1_19_4),
                    map(0x18, MINECRAFT_1_20_2),
                    map(0x19, MINECRAFT_1_20_5))
                .register(
                    JoinGame.class,
                    JoinGame.CODEC,
                    map(0x01, MINECRAFT_1_7_2),
                    map(0x23, MINECRAFT_1_9),
                    map(0x25, MINECRAFT_1_13),
                    map(0x26, MINECRAFT_1_15),
                    map(0x25, MINECRAFT_1_16),
                    map(0x24, MINECRAFT_1_16_2),
                    map(0x26, MINECRAFT_1_17),
                    map(0x23, MINECRAFT_1_19),
                    map(0x25, MINECRAFT_1_19_1),
                    map(0x24, MINECRAFT_1_19_3),
                    map(0x28, MINECRAFT_1_19_4),
                    map(0x29, MINECRAFT_1_20_2),
                    map(0x2B, MINECRAFT_1_20_5),
                    map(0x2C, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    Respawn.class,
                    Respawn.CODEC,
                    map(0x07, MINECRAFT_1_7_2),
                    map(0x33, MINECRAFT_1_9),
                    map(0x34, MINECRAFT_1_12),
                    map(0x35, MINECRAFT_1_12_1),
                    map(0x38, MINECRAFT_1_13),
                    map(0x3A, MINECRAFT_1_14),
                    map(0x3B, MINECRAFT_1_15),
                    map(0x3A, MINECRAFT_1_16),
                    map(0x39, MINECRAFT_1_16_2),
                    map(0x3D, MINECRAFT_1_17),
                    map(0x3B, MINECRAFT_1_19),
                    map(0x3E, MINECRAFT_1_19_1),
                    map(0x3D, MINECRAFT_1_19_3),
                    map(0x41, MINECRAFT_1_19_4),
                    map(0x43, MINECRAFT_1_20_2),
                    map(0x45, MINECRAFT_1_20_3),
                    map(0x47, MINECRAFT_1_20_5),
                    map(0x4C, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    SystemChatMessage.class,
                    SystemChatMessage.CODEC,
                    // Chat Message until 1.18.2, System Chat Message from 1.19
                    map(0x02, MINECRAFT_1_7_2),
                    map(0x0F, MINECRAFT_1_9),
                    map(0x0E, MINECRAFT_1_13),
                    map(0x0F, MINECRAFT_1_15),
                    map(0x0E, MINECRAFT_1_16),
                    map(0x0F, MINECRAFT_1_17),
                    map(0x5F, MINECRAFT_1_19),
                    map(0x62, MINECRAFT_1_19_1),
                    map(0x60, MINECRAFT_1_19_3),
                    map(0x64, MINECRAFT_1_19_4),
                    map(0x67, MINECRAFT_1_20_2),
                    map(0x69, MINECRAFT_1_20_3),
                    map(0x6C, MINECRAFT_1_20_5),
                    map(0x73, MINECRAFT_1_21_2))
                .register(
                    StartConfiguration.class,
                    StartConfiguration.CODEC,
                    map(0x65, MINECRAFT_1_20_2),
                    map(0x67, MINECRAFT_1_20_3),
                    map(0x69, MINECRAFT_1_20_5),
                    map(0x70, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    Transfer.class,
                    Transfer.CODEC,
                    map(0x73, MINECRAFT_1_20_5),
                    map(0x7A, MINECRAFT_1_21_2))
                .registerEncodeOnly(
                    TabCompleteResponse.class,
                    TabCompleteResponse.CODEC,
                    map(0x10, MINECRAFT_1_13),
                    map(0x11, MINECRAFT_1_15),
                    map(0x10, MINECRAFT_1_16),
                    map(0x0F, MINECRAFT_1_16_2),
                    map(0x11, MINECRAFT_1_17),
                    map(0x0E, MINECRAFT_1_19),
                    map(0x0D, MINECRAFT_1_19_3),
                    map(0x0F, MINECRAFT_1_19_4),
                    map(0x10, MINECRAFT_1_20_2))
                .build());
  }
}
