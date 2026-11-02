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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameLimits;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.LimitsConfig;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.NetUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives connections that have not logged in through the pipeline {@link ServerChannelInitializer}
 * builds, on a frozen clock: the frame limits of each state, the deadlines of the handshake and of
 * the server list ping, the cap on connections not logged in, and the silence of all of it in the
 * logs.
 */
@DisplayName("hardening before login")
class PreLoginHardeningTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  /** The classes a flood of hostile connections could make log, one line per connection. */
  private final List<LogCapture> logs =
      List.of(
          new LogCapture(MinecraftConnection.class),
          new LogCapture(HandshakeSessionHandler.class),
          new LogCapture(StatusSessionHandler.class),
          new LogCapture(LoginSessionHandler.class));

  private final LogCapture limitsLog = new LogCapture(ClientLimits.class);

  @AfterEach
  void closeLogs() {
    logs.forEach(LogCapture::close);
    limitsLog.close();
  }

  // ---------------------------------------------------------------------------
  // Frame limits
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("frame limits")
  class Frames {

    @Test
    @DisplayName("should reset a first frame announcing 2 MiB before buffering any of it")
    void oversizedFirstFrame() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      EmbeddedChannel channel = connect(limits, 1);

      // The largest length the protocol allows, then the start of a handshake.
      channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) 0xFF, (byte) 0xFF, 0x7F, 0}));

      assertFalse(channel.isActive());
      assertEquals(1, limits.refusals(Refusal.INVALID_FRAME));
      assertNoLines();
    }

    @Test
    @DisplayName("should hold each state to its own limits, and none once logged in")
    void limitsFollowState() {
      EmbeddedChannel channel = connect(limits(LimitsConfig.DEFAULTS), 1);
      FrameDecoder frames = channel.pipeline().get(FrameDecoder.class);
      assertEquals(FrameLimits.HANDSHAKE, frames.limits());

      channel.writeInbound(handshake(2));
      assertEquals(FrameLimits.serverbound(ProtocolState.LOGIN, VERSION), frames.limits());

      channel.writeInbound(loginStart("Steve"));
      skipOutbound(channel); // Login Success
      channel.writeInbound(frame(0x03)); // Login Acknowledged

      assertNull(frames.limits(), "a logged-in client is framed by the protocol alone");
    }

    @Test
    @DisplayName("should read the frame after a handshake under the limits of the next state")
    void handshakeAndRequestInOneRead() {
      EmbeddedChannel channel = connect(limits(LimitsConfig.DEFAULTS), 1);
      ByteBuf both = Unpooled.buffer();
      both.writeBytes(handshake(1));
      both.writeBytes(frame(0x00));

      channel.writeInbound(both);

      assertNotNull(channel.readOutbound(), "the status response");
      assertTrue(channel.isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // Deadlines
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("deadlines")
  class Deadlines {

    @Test
    @DisplayName("should reset a connection that sends no handshake within the timeout")
    void noHandshake() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      EmbeddedChannel channel = connect(limits, 1);

      advance(channel, Duration.ofMillis(4999));
      assertTrue(channel.isActive(), "within the timeout");
      advance(channel, Duration.ofMillis(1));

      assertFalse(channel.isActive());
      assertEquals(1, limits.refusals(Refusal.HANDSHAKE_TIMEOUT));
      assertNoLines();
    }

    @Test
    @DisplayName("should not let bytes trickled into a handshake extend its deadline")
    void trickledHandshake() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      EmbeddedChannel channel = connect(limits, 1);
      ByteBuf handshake = handshake(2);

      for (int second = 0; second < 5 && channel.isActive(); second++) {
        channel.writeInbound(handshake.readRetainedSlice(1));
        advance(channel, Duration.ofSeconds(1));
      }
      handshake.release();

      assertFalse(channel.isActive());
      assertEquals(1, limits.refusals(Refusal.HANDSHAKE_TIMEOUT));
    }

    @Test
    @DisplayName("should reset a server list ping that never asks for the entry: a null ping")
    void nullPing() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      EmbeddedChannel channel = connect(limits, 1);
      channel.writeInbound(handshake(1));

      advance(channel, Duration.ofMillis(4999));
      assertTrue(channel.isActive(), "within the timeout");
      advance(channel, Duration.ofMillis(1));

      assertFalse(channel.isActive());
      assertEquals(1, limits.refusals(Refusal.STATUS_TIMEOUT));
      assertNoLines();
    }

    @Test
    @DisplayName("should hand a login over to the login timeout, longer than the handshake's")
    void loginNotUnderHandshakeTimeout() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      EmbeddedChannel channel = connect(limits, 1);
      channel.writeInbound(handshake(2));

      advance(channel, Duration.ofSeconds(29));

      assertTrue(channel.isActive());
      assertEquals(0, limits.refusals(Refusal.HANDSHAKE_TIMEOUT));
    }

    @Test
    @DisplayName("should reset the read timeout on complete frames only")
    void readTimeoutCountsFrames() {
      LimitsConfig noDeadlines = deadlines(Duration.ZERO, Duration.ZERO);
      ClientLimits limits = limits(noDeadlines);
      EmbeddedChannel channel = connect(limits, 1);
      channel.writeInbound(handshake(1));
      ByteBuf ping = frame(0x01, new byte[8]);

      // A byte every 10 s of a ping it never finishes: complete frames stop at the handshake.
      for (int i = 0; i < 3; i++) {
        advance(channel, Duration.ofSeconds(10));
        if (!channel.isActive()) {
          break;
        }
        channel.writeInbound(ping.readRetainedSlice(1));
      }
      ping.release();
      advance(channel, Duration.ofSeconds(1));

      assertFalse(channel.isActive());
      assertEquals(1, limits.refusals(Refusal.IDLE_TIMEOUT));
      assertNoLines();
    }
  }

  // ---------------------------------------------------------------------------
  // Connections not logged in
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("connections not logged in")
  class Pending {

    @Test
    @DisplayName("should reset new connections over the cap, whatever their address")
    void overCap() {
      ClientLimits limits = limits(pendingCap(2));
      connect(limits, 1);
      connect(limits, 2);

      EmbeddedChannel third = connect(limits, 3);

      assertFalse(third.isActive());
      assertNull(third.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER));
      assertEquals(2, limits.pendingConnections());
      assertEquals(1, limits.refusals(Refusal.PENDING_CONNECTIONS));
    }

    @Test
    @DisplayName("should free a place when a connection closes or its client logs in")
    void freedOnCloseAndLogin() {
      ClientLimits limits = limits(pendingCap(2));
      EmbeddedChannel closing = connect(limits, 1);
      EmbeddedChannel joining = connect(limits, 2);

      closing.close();
      joining.writeInbound(handshake(2), loginStart("Steve"));

      assertEquals(0, limits.pendingConnections());
      assertTrue(connect(limits, 3).isActive());
      assertTrue(connect(limits, 4).isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // Logs
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("should log a flood of hostile connections as one line of counts")
  void floodInOneLine() {
    ClientLimits limits = limits(LimitsConfig.DEFAULTS);
    List<EmbeddedChannel> channels = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      EmbeddedChannel nullPing = connect(limits, 10 + i);
      nullPing.writeInbound(handshake(1));
      channels.add(nullPing);
      EmbeddedChannel unknownIntent = connect(limits, 100 + i);
      unknownIntent.writeInbound(handshake(7));
      EmbeddedChannel outdated = connect(limits, 200 + i);
      outdated.writeInbound(handshake(2, 99_999));
    }
    channels.forEach(channel -> advance(channel, Duration.ofSeconds(5)));

    limits.report();

    assertNoLines();
    List<String> report =
        limitsLog.events().stream().map(event -> event.getMessage().getFormattedMessage()).toList();
    assertEquals(
        List.of(
            "Refused before login over the last 60 s: server list ping time-outs: 50, invalid"
                + " handshakes: 50, logins from unsupported versions: 50"),
        report);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void assertNoLines() {
    for (LogCapture log : logs) {
      List<String> lines =
          log.events().stream()
              .filter(event -> event.getLevel().isMoreSpecificThan(Level.INFO))
              .map(LogEvent::getMessage)
              .map(message -> message.getFormattedMessage())
              .toList();
      assertEquals(List.of(), lines, "logged one by one");
    }
  }

  private static ClientLimits limits(LimitsConfig config) {
    return new ClientLimits(config, CidrRanges.none());
  }

  /** The defaults with other handshake and status timeouts and no login timeout. */
  private static LimitsConfig deadlines(Duration handshake, Duration status) {
    LimitsConfig d = LimitsConfig.DEFAULTS;
    return new LimitsConfig(
        true,
        d.exempt(),
        d.connections(),
        d.statusPings(),
        d.logins(),
        d.maxConcurrentLoginsPerAddress(),
        d.maxConcurrentAuthentications(),
        Duration.ZERO,
        d.reportInterval(),
        d.maxPendingConnections(),
        handshake,
        status,
        d.authenticationQueue(),
        d.authenticationQueueWait(),
        d.loginsUnderAttack(),
        d.maxConcurrentLoginsPerAddressUnderAttack());
  }

  private static LimitsConfig pendingCap(int cap) {
    LimitsConfig d = LimitsConfig.DEFAULTS;
    return new LimitsConfig(
        true,
        d.exempt(),
        d.connections(),
        d.statusPings(),
        d.logins(),
        d.maxConcurrentLoginsPerAddress(),
        d.maxConcurrentAuthentications(),
        d.loginTimeout(),
        d.reportInterval(),
        cap,
        d.handshakeTimeout(),
        d.statusTimeout(),
        d.authenticationQueue(),
        d.authenticationQueueWait(),
        d.loginsUnderAttack(),
        d.maxConcurrentLoginsPerAddressUnderAttack());
  }

  /** Connects from its own address, so that no per-address limit interferes. */
  private static EmbeddedChannel connect(ClientLimits limits, int host) {
    EmbeddedChannel channel = new ProxyProtocolTest.PeerChannel(address(host));
    channel.freezeTime();
    channel
        .pipeline()
        .addLast(
            new ServerChannelInitializer(
                TestLoginContexts.limited(false, new MojangSessionService(), limits)));
    return channel;
  }

  private static void advance(EmbeddedChannel channel, Duration duration) {
    channel.advanceTimeBy(duration.toNanos(), TimeUnit.NANOSECONDS);
    channel.runPendingTasks();
    channel.runScheduledPendingTasks();
  }

  private static void skipOutbound(EmbeddedChannel channel) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame);
    frame.release();
  }

  private static ByteBuf handshake(int nextState) {
    return handshake(nextState, VERSION.protocol());
  }

  private static ByteBuf handshake(int nextState, int protocol) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, protocol);
    McString.write(body, "play.warp.dev");
    body.writeShort(25565);
    VarInt.write(body, nextState);
    return framed(body);
  }

  /** A 1.21.4 Login Start: the name, then the UUID the client claims. */
  private static ByteBuf loginStart(String name) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    body.writeLong(0).writeLong(0);
    return framed(body);
  }

  private static ByteBuf frame(int packetId) {
    return frame(packetId, new byte[0]);
  }

  private static ByteBuf frame(int packetId, byte[] payload) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, packetId);
    body.writeBytes(payload);
    return framed(body);
  }

  private static ByteBuf framed(ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  private static InetSocketAddress address(int host) {
    try {
      byte[] bytes = NetUtil.createByteArrayFromIpAddressString("198.51.100.0");
      bytes[3] = (byte) host;
      bytes[2] = (byte) (host >> 8);
      return new InetSocketAddress(InetAddress.getByAddress(bytes), 40000);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }
}
