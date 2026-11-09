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
package dev.warp.proxy.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.player.ClientPlatform;
import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.antibot.AntiBotService.Plan;
import dev.warp.proxy.bedrock.FloodgateCipher;
import dev.warp.proxy.bedrock.FloodgateData;
import dev.warp.proxy.bedrock.FloodgateService;
import dev.warp.proxy.config.BedrockConfig;
import dev.warp.proxy.config.LimitsConfig;
import dev.warp.proxy.limit.Blocklist;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.network.CidrRanges;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Drives a Bedrock player's login through the pipeline {@link ServerChannelInitializer} builds,
 * with the Floodgate data Geyser would send, from the handshake to Login Success.
 */
@DisplayName("Floodgate login")
class FloodgateLoginTest {

  private static final byte[] KEY = new byte[16];

  static {
    Arrays.fill(KEY, (byte) 0x42);
  }

  private static final ProtocolVersion VERSION = ProtocolVersion.latest();

  private static final long XUID = 2535428912345678L;

  /** What Geyser writes for Steve Jr on Android, without a linked account or a skin upload. */
  private static final String PLAINTEXT =
      "1.21.50\0Steve Jr\0"
          + XUID
          + "\0"
          + "1\0en_US\0"
          + "0\0"
          + "2\0"
          + "198.51.100.7\0"
          + "null\0"
          + "0\0"
          + "-1\0"
          + "null";

  /** The Java account Steve Jr linked through Floodgate's global linking. */
  private static final UUID LINKED_JAVA_ID =
      UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

  /**
   * What Geyser writes for Steve Jr once the account is linked: the host field runs past vanilla's
   * 255-character cap with this plaintext.
   */
  private static final String LINKED_PLAINTEXT =
      PLAINTEXT.replace("\0null\0", "\0Notch;" + LINKED_JAVA_ID + ";" + new UUID(0, XUID) + "\0");

  private final FloodgateCipher cipher = new FloodgateCipher(KEY);

  private ServerLoginContext context = TestLoginContexts.offline();

  private EmbeddedChannel channel() {
    return channel(".", ClientLimits.unlimited(), false, null);
  }

  /**
   * Opens a connection from the Geyser host to a proxy whose Bedrock names carry {@code prefix}.
   *
   * @param prefix the Bedrock name prefix
   * @param limits the limits
   * @param onlineMode whether Java players authenticate with Mojang
   * @param antiBot the anti-bot, or {@code null} for a disabled one sharing {@code limits}
   */
  private EmbeddedChannel channel(
      String prefix, ClientLimits limits, boolean onlineMode, @Nullable AntiBotService antiBot) {
    BedrockConfig config =
        new BedrockConfig(
            new BedrockConfig.Floodgate(true, KEY, "key.pem", prefix, false),
            CidrRanges.parse(List.of("127.0.0.1/32", "::1/128")),
            Map.of("survival", true));
    ServerRegistry registry =
        new ServerRegistry(
            Map.of(
                "lobby",
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 1),
                "survival",
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 2)),
            "lobby",
            List.of("lobby"));
    context =
        TestLoginContexts.floodgate(
            onlineMode,
            new FloodgateService(config),
            registry,
            limits,
            antiBot != null ? antiBot : AntiBotService.disabled(limits, TestLoginContexts.EVENTS));
    EmbeddedChannel channel = new ClientChannel();
    channel.pipeline().addLast(new ServerChannelInitializer(context));
    channel.runPendingTasks();
    return channel;
  }

  private MinecraftConnection connection(EmbeddedChannel channel) {
    MinecraftConnection connection = channel.pipeline().get(MinecraftConnection.class);
    assertNotNull(connection);
    return connection;
  }

  private String hostname(String plaintext) {
    return "play.example.com\0" + cipher.encrypt(plaintext);
  }

  @Nested
  @DisplayName("a Bedrock player")
  class BedrockPlayer {

    @Test
    @DisplayName("should log in under Floodgate's UUID and prefixed name, as a Bedrock platform")
    void logsIn() {
      EmbeddedChannel channel = channel();
      try {
        handshake(channel, hostname(PLAINTEXT));
        // Geyser sends the gamertag as the login name, spaces included.
        loginStart(channel, "Steve Jr");

        ByteBuf success =
            Unpooled.wrappedBuffer(nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class));
        try {
          assertEquals(new UUID(0, XUID), McUuid.read(success));
          assertEquals(".Steve_Jr", McString.read(success));
        } finally {
          success.release();
        }
        MinecraftConnection connection = connection(channel);
        ClientPlatform.Bedrock platform =
            assertInstanceOf(ClientPlatform.Bedrock.class, connection.clientPlatform());
        assertEquals("Steve Jr", platform.bedrockName());
        assertEquals(ClientPlatform.DeviceOs.ANDROID, platform.deviceOs());
        // Geyser connects from the loopback, which is trusted: the player's own address counts.
        assertEquals(
            InetAddress.ofLiteral("198.51.100.7"),
            ((InetSocketAddress) connection.remoteAddress()).getAddress());
        assertEquals("play.example.com", connection.virtualHost());
        assertEquals(0, context.limits().refusals(Refusal.FLOODGATE_REFUSED));
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should log in a linked account, whose host field runs past 255 characters")
    void linked() {
      String host = hostname(LINKED_PLAINTEXT);
      assertTrue(
          host.length() > 255, "the linked host must exceed vanilla's cap: " + host.length());
      EmbeddedChannel channel = channel();
      try {
        handshake(channel, host);
        loginStart(channel, "Steve Jr");

        ByteBuf success =
            Unpooled.wrappedBuffer(nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class));
        try {
          assertEquals(LINKED_JAVA_ID, McUuid.read(success));
          assertEquals("Notch", McString.read(success));
        } finally {
          success.release();
        }
        assertInstanceOf(ClientPlatform.Bedrock.class, connection(channel).clientPlatform());
        assertEquals(0, context.limits().refusals(Refusal.FLOODGATE_REFUSED));
        assertEquals(0, context.limits().refusals(Refusal.INVALID_FRAME));
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should plan the anti-bot for an offline login, even on an online proxy")
    void plannedOffline() {
      ClientLimits limits = ClientLimits.unlimited();
      AntiBotService antiBot =
          Mockito.spy(AntiBotService.disabled(limits, TestLoginContexts.EVENTS));
      // What an online proxy under attack would plan for an unknown Java login: a verification
      // before authentication, which has no authentication to protect for a Bedrock player.
      Mockito.doReturn(Plan.BEFORE_AUTHENTICATION)
          .when(antiBot)
          .plan(Mockito.any(), Mockito.eq(true), Mockito.any(), Mockito.any());
      EmbeddedChannel channel = channel(".", limits, true, antiBot);
      try {
        handshake(channel, hostname(PLAINTEXT));
        loginStart(channel, "Steve Jr");

        ByteBuf success =
            Unpooled.wrappedBuffer(nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class));
        try {
          assertEquals(new UUID(0, XUID), McUuid.read(success), "Floodgate's UUID, no detour");
        } finally {
          success.release();
        }
        Mockito.verify(antiBot, Mockito.atLeastOnce())
            .plan(Mockito.any(), Mockito.eq(false), Mockito.any(), Mockito.any());
        Mockito.verify(antiBot, Mockito.never())
            .plan(Mockito.any(), Mockito.eq(true), Mockito.any(), Mockito.any());
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should re-emit the data to a backend running Floodgate, and only to it")
    void forwards() throws Exception {
      EmbeddedChannel channel = channel();
      try {
        handshake(channel, hostname(PLAINTEXT));
        loginStart(channel, "Steve Jr");
        nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class);
        ConnectedPlayer player = context.playerRegistry().get(".Steve_Jr");
        assertNotNull(player);
        InetSocketAddress backend = new InetSocketAddress(InetAddress.getLoopbackAddress(), 2);

        String survival =
            BackendLoginSessionHandler.backendHost(
                player, new ServerInfo("survival", backend), "10.0.0.2");
        String lobby =
            BackendLoginSessionHandler.backendHost(
                player, new ServerInfo("lobby", backend), "10.0.0.1");

        assertEquals("10.0.0.1", lobby);
        assertTrue(survival.startsWith("10.0.0.2\0^Floodgate^>"), survival);
        FloodgateData forwarded =
            FloodgateData.parse(
                cipher.decrypt(survival.substring("10.0.0.2\0".length())).plaintext());
        assertEquals(FloodgateData.parse(PLAINTEXT).fromProxy(true), forwarded);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should re-emit a linked account's data in a handshake the codec accepts")
    void forwardsLinked() throws Exception {
      EmbeddedChannel channel = channel();
      try {
        handshake(channel, hostname(LINKED_PLAINTEXT));
        loginStart(channel, "Steve Jr");
        nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class);
        ConnectedPlayer player = context.playerRegistry().get("Notch");
        assertNotNull(player);
        InetSocketAddress backend = new InetSocketAddress(InetAddress.getLoopbackAddress(), 2);

        String host =
            BackendLoginSessionHandler.backendHost(
                player, new ServerInfo("survival", backend), "survival.internal.example.com");
        assertTrue(
            host.length() > 255, "the re-emitted host exceeds vanilla's cap: " + host.length());
        Handshake handshake = new Handshake(VERSION.protocol(), host, 25565, 2);
        ByteBuf wire = Unpooled.buffer();
        try {
          Handshake.CODEC.encode(handshake, wire, VERSION);
          assertEquals(handshake, Handshake.CODEC.decode(wire, VERSION));
        } finally {
          wire.release();
        }
        FloodgateData forwarded =
            FloodgateData.parse(
                cipher
                    .decrypt(host.substring("survival.internal.example.com\0".length()))
                    .plaintext());
        assertEquals(FloodgateData.parse(LINKED_PLAINTEXT).fromProxy(true), forwarded);
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Nested
  @DisplayName("refusals")
  class Refusals {

    @Test
    @DisplayName("should refuse data under another key with a login disconnect, counted")
    void wrongKey() {
      EmbeddedChannel channel = channel();
      try {
        byte[] otherKey = new byte[16];
        Arrays.fill(otherKey, (byte) 0x13);
        handshake(channel, "play.example.com\0" + new FloodgateCipher(otherKey).encrypt(PLAINTEXT));

        String reason = reason(channel);
        assertTrue(reason.contains("Bedrock session"), reason);
        assertClosed(channel);
        assertEquals(1, context.limits().refusals(Refusal.FLOODGATE_REFUSED));
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should refuse the same data a second time")
    void replay() {
      EmbeddedChannel first = channel();
      ServerLoginContext shared = context;
      String hostname = hostname(PLAINTEXT);
      try {
        handshake(first, hostname);
        loginStart(first, "Steve Jr");
        nextPacket(first, ProtocolState.LOGIN, LoginSuccess.class);
      } finally {
        first.finishAndReleaseAll();
      }
      EmbeddedChannel second = new ClientChannel();
      second.pipeline().addLast(new ServerChannelInitializer(shared));
      second.runPendingTasks();
      try {
        handshake(second, hostname);

        reason(second);
        assertClosed(second);
        assertEquals(1, shared.limits().refusals(Refusal.FLOODGATE_REFUSED));
      } finally {
        second.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should refuse a Java client using the Bedrock prefix without data")
    void javaWithPrefix() throws Exception {
      // A prefix of name characters: "_Steve" is a valid Java name, so only the reservation of the
      // prefix for Bedrock players can turn it away.
      EmbeddedChannel channel = channel("_", ClientLimits.unlimited(), false, null);
      try (LogCapture log = new LogCapture(LoginSessionHandler.class)) {
        handshake(channel, "play.example.com");
        loginStart(channel, "_Steve");

        nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class);
        assertClosed(channel);
        assertEquals(1, context.limits().refusals(Refusal.INVALID_LOGIN));
        assertTrue(
            log.events().stream()
                .anyMatch(
                    event ->
                        event
                            .getMessage()
                            .getFormattedMessage()
                            .contains("Bedrock name prefix without Floodgate data")),
            "refused for the prefix, not the name");
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should let a Java client in under a name the Bedrock prefix does not reserve")
    void javaWithoutPrefix() {
      EmbeddedChannel channel = channel("_", ClientLimits.unlimited(), false, null);
      try {
        handshake(channel, "play.example.com");
        loginStart(channel, "Steve_");

        nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class);
        assertEquals(0, context.limits().refusals(Refusal.INVALID_LOGIN));
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should refuse a blocklisted Bedrock address a trusted Geyser names, counted")
    void blocklistedRealAddress() {
      ClientLimits limits =
          new ClientLimits(
              LimitsConfig.DEFAULTS,
              CidrRanges.none(),
              new Blocklist(1, Duration.ofMinutes(10), System::nanoTime));
      InetSocketAddress bedrock = new InetSocketAddress(InetAddress.ofLiteral("198.51.100.7"), 1);
      limits.refused(Refusal.INVALID_HANDSHAKE, bedrock);
      EmbeddedChannel channel = channel(".", limits, false, null);
      try {
        handshake(channel, hostname(PLAINTEXT));

        assertClosed(channel);
        assertEquals(1, limits.refusals(Refusal.BLOCKLISTED));
        assertEquals(0, limits.refusals(Refusal.FLOODGATE_REFUSED));
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should still let a plain Java player in")
    void javaPlayer() {
      EmbeddedChannel channel = channel();
      try {
        handshake(channel, "play.example.com");
        loginStart(channel, "Steve");

        ByteBuf success =
            Unpooled.wrappedBuffer(nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class));
        try {
          McUuid.read(success);
          assertEquals("Steve", McString.read(success));
        } finally {
          success.release();
        }
        assertEquals(ClientPlatform.JAVA, connection(channel).clientPlatform());
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Test
  @DisplayName("Platforms.describe() should name the platform for staff")
  void describes() {
    assertEquals("", Platforms.describe(ClientPlatform.JAVA));
    assertEquals(", NeoForge", Platforms.describe(ClientPlatform.NEOFORGE));
    assertEquals(
        ", Forge", Platforms.describe(new ClientPlatform.Forge(ClientPlatform.ForgeEra.LEGACY, 0)));
    assertEquals(
        ", Bedrock on Xbox",
        Platforms.describe(
            new ClientPlatform.Bedrock(
                1L,
                "Steve",
                ClientPlatform.DeviceOs.XBOX,
                ClientPlatform.InputMode.CONTROLLER,
                ClientPlatform.UiProfile.CLASSIC,
                "en_US",
                java.util.Optional.empty(),
                InetAddress.getLoopbackAddress())));
  }

  // ---------------------------------------------------------------------------
  // Wire helpers
  // ---------------------------------------------------------------------------

  /** Writes a Handshake as Geyser does: id 0, protocol, host with the data, port, login. */
  private void handshake(EmbeddedChannel channel, String host) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, VERSION.protocol());
    McString.write(body, host);
    body.writeShort(25565);
    VarInt.write(body, 2);
    writeFrame(channel, body);
  }

  /** Writes a Login Start as a 1.20.2+ client does: the name, then the player's UUID. */
  private void loginStart(EmbeddedChannel channel, String name) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    McUuid.write(body, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    writeFrame(channel, body);
  }

  private void writeFrame(EmbeddedChannel channel, ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    channel.writeInbound(frame);
  }

  /** Reads the next frame, checks that it holds one {@code type} packet, and returns its body. */
  private byte[] nextPacket(
      EmbeddedChannel channel, ProtocolState state, Class<? extends Packet> type) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a " + type.getSimpleName());
    try {
      assertEquals(VarInt.read(frame), frame.readableBytes(), "frame length");
      int expectedId =
          StateRegistry.get(state, PacketDirection.CLIENTBOUND).packetId(VERSION, type);
      assertEquals(expectedId, VarInt.read(frame), type.getSimpleName() + " packet id");
      return ByteBufUtil.getBytes(frame);
    } finally {
      frame.release();
    }
  }

  /** Reads the Login Disconnect the proxy wrote and returns its JSON reason. */
  private String reason(EmbeddedChannel channel) {
    ByteBuf reason =
        Unpooled.wrappedBuffer(nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class));
    try {
      return McString.read(reason);
    } finally {
      reason.release();
    }
  }

  private void assertClosed(EmbeddedChannel channel) {
    channel.runPendingTasks();
    assertFalse(channel.isOpen(), "the connection must be closed");
  }

  /** An embedded channel with the socket address of a Geyser running beside the proxy. */
  private static final class ClientChannel extends EmbeddedChannel {

    private static final InetSocketAddress GEYSER =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000);

    @Override
    protected SocketAddress remoteAddress0() {
      return GEYSER;
    }
  }
}
