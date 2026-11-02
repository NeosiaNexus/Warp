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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.proxy.config.ProxyProtocolConfig;
import dev.warp.proxy.network.CidrRanges;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives connections through the pipeline {@link ServerChannelInitializer} builds with the PROXY
 * protocol on, from a load balancer at {@code 10.0.0.1} that the configuration trusts.
 */
@DisplayName("PROXY protocol")
class ProxyProtocolTest {

  private static final InetSocketAddress LOAD_BALANCER = socket("10.0.0.1", 34567);
  private static final InetSocketAddress STRANGER = socket("198.51.100.66", 45678);

  private static final ProxyProtocolConfig TRUSTING_LOAD_BALANCER =
      new ProxyProtocolConfig(true, CidrRanges.parse(List.of("10.0.0.0/24", "2001:db8:1::/48")));

  private static final byte[] V2_SIGNATURE = {
    0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
  };

  private static final int V2_PROXY = 0x21;
  private static final int V2_LOCAL = 0x20;
  private static final int TCP4 = 0x11;
  private static final int UDP4 = 0x12;
  private static final int TCP6 = 0x21;
  private static final int UNIX_STREAM = 0x31;
  private static final int UNSPEC = 0x00;

  /** The clock of the warning throttles, in nanoseconds. */
  private final AtomicLong nanoTime = new AtomicLong();

  private final LogCapture initializerLog = new LogCapture(ServerChannelInitializer.class);
  private final LogCapture handlerLog = new LogCapture(ProxyProtocolHandler.class);

  @AfterEach
  void closeLogs() {
    initializerLog.close();
    handlerLog.close();
  }

  // ---------------------------------------------------------------------------
  // Header formats
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("from a trusted proxy")
  class TrustedProxy {

    @Test
    @DisplayName("should take the client's address from a v1 TCP4 header")
    void v1Tcp4() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"));

      assertEquals(socket("203.0.113.7", 40000), connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should take the client's address from a v1 TCP6 header")
    void v1Tcp6() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(ascii("PROXY TCP6 2001:db8::7 2001:db8::1 40000 25577\r\n"));

      assertEquals(socket("2001:db8::7", 40000), connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should take the client's address from a v2 TCP over IPv4 header")
    void v2Tcp4() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(v2(V2_PROXY, TCP4, tcp4("203.0.113.7", "10.0.0.1", 40000, 25577)));

      assertEquals(socket("203.0.113.7", 40000), connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should take the client's address from a v2 TCP over IPv6 header")
    void v2Tcp6() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(
          v2(V2_PROXY, TCP6, tcp6("2001:db8:ffff::7", "2001:db8::1", 40000, 25577)));

      assertEquals(socket("2001:db8:ffff::7", 40000), connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should give an IPv4-mapped IPv6 client its IPv4 address")
    void mappedClient() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(ascii("PROXY TCP6 ::ffff:203.0.113.7 ::ffff:10.0.0.1 40000 25577\r\n"));

      InetSocketAddress client = (InetSocketAddress) connection(channel).remoteAddress();
      assertEquals(socket("203.0.113.7", 40000), client);
      assertEquals(4, client.getAddress().getAddress().length);
    }

    @Test
    @DisplayName("should ignore the TLVs of a v2 header")
    void v2Tlvs() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      ByteBuf tlvs = Unpooled.buffer();
      // PP2_TYPE_AUTHORITY, then an AWS VPC endpoint ID (0xEA, sub-type 0x01), then a NOOP.
      tlvs.writeByte(0x02).writeShort(11).writeBytes(bytes("example.com"));
      tlvs.writeByte(0xEA).writeShort(6).writeByte(0x01).writeBytes(bytes("vpce1"));
      tlvs.writeByte(0x04).writeShort(3).writeZero(3);

      channel.writeInbound(
          v2(
              V2_PROXY,
              TCP4,
              Unpooled.wrappedBuffer(
                  Unpooled.wrappedBuffer(tcp4("203.0.113.7", "10.0.0.1", 1, 2)), tlvs)));

      assertEquals(socket("203.0.113.7", 1), connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should accept a load balancer that connects from an IPv4-mapped IPv6 address")
    void mappedLoadBalancer() throws UnknownHostException {
      byte[] mapped = NetUtil.createByteArrayFromIpAddressString("::ffff:10.0.0.1");
      EmbeddedChannel channel =
          connect(new InetSocketAddress(Inet6Address.getByAddress(null, mapped, -1), 34567));

      channel.writeInbound(ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"));

      assertEquals(socket("203.0.113.7", 40000), connection(channel).remoteAddress());
    }

    @Test
    @DisplayName("should accept a load balancer in a trusted IPv6 range")
    void ipv6LoadBalancer() {
      EmbeddedChannel channel = connect(socket("2001:db8:1::10", 34567));

      channel.writeInbound(ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"));

      assertEquals(socket("203.0.113.7", 40000), connection(channel).remoteAddress());
    }

    @Test
    @DisplayName("should take itself out of the pipeline once the header is applied")
    void removesItself() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      assertNotNull(channel.pipeline().get(ServerChannelInitializer.PROXY_PROTOCOL_DECODER));
      assertNotNull(channel.pipeline().get(ServerChannelInitializer.PROXY_PROTOCOL_HANDLER));

      channel.writeInbound(ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"));

      assertNull(channel.pipeline().get(ServerChannelInitializer.PROXY_PROTOCOL_DECODER));
      assertNull(channel.pipeline().get(ServerChannelInitializer.PROXY_PROTOCOL_HANDLER));
      assertEquals(ServerChannelInitializer.READ_TIMEOUT, channel.pipeline().names().getFirst());
      assertTrue(channel.isActive());
    }

    @Test
    @DisplayName("should hand the Minecraft packets that arrive with the header to the pipeline")
    void packetsInTheSameRead() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(
          Unpooled.wrappedBuffer(
              ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"),
              statusHandshake(),
              statusRequest()));

      assertStatusResponse(channel);
    }

    @Test
    @DisplayName("should keep the client's address for the whole connection")
    void addressForLogin() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      try (LogCapture loginLog = new LogCapture(LoginSessionHandler.class)) {
        channel.writeInbound(
            Unpooled.wrappedBuffer(
                v2(V2_PROXY, TCP4, tcp4("203.0.113.7", "10.0.0.1", 40000, 25577)),
                handshake(ProtocolVersion.MINECRAFT_1_21_4, 2),
                loginStart("Steve")));

        assertTrue(
            loginLog.events().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .anyMatch(
                    line ->
                        line.equals(
                            "Player Steve ("
                                + offlineUuid("Steve")
                                + ") logged in from /203.0.113.7:40000")),
            () -> String.valueOf(loginLog.events()));
      }
      channel.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Health checks and unknown sources
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("without a client address")
  class WithoutClientAddress {

    @Test
    @DisplayName("should keep the load balancer's address for a v2 LOCAL health check")
    void v2Local() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(v2(V2_LOCAL, UNSPEC, new byte[0]));

      assertEquals(LOAD_BALANCER, connection(channel).remoteAddress());
      // A layer-7 health check may go on with a server list ping: it gets its answer.
      assertStatusPingAnswered(channel);
      assertTrue(
          handlerLog.events().stream().noneMatch(e -> e.getLevel().isMoreSpecificThan(Level.WARN)));
    }

    @Test
    @DisplayName("should ignore the addresses a v2 LOCAL header carries anyway")
    void v2LocalWithAddresses() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(v2(V2_LOCAL, TCP4, tcp4("203.0.113.7", "10.0.0.1", 40000, 25577)));

      assertEquals(LOAD_BALANCER, connection(channel).remoteAddress());
      assertTrue(channel.isActive());
    }

    @Test
    @DisplayName("should keep the load balancer's address for a v1 UNKNOWN header")
    void v1Unknown() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(ascii("PROXY UNKNOWN\r\n"));

      assertEquals(LOAD_BALANCER, connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should keep the load balancer's address for a v2 UNSPEC header")
    void v2Unspec() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(v2(V2_PROXY, UNSPEC, new byte[0]));

      assertEquals(LOAD_BALANCER, connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }
  }

  // ---------------------------------------------------------------------------
  // Refusals
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("refusals")
  class Refusals {

    @Test
    @DisplayName("should reset a connection from an untrusted peer before reading it")
    void untrustedPeer() {
      EmbeddedChannel channel = connect(STRANGER);

      assertFalse(channel.isActive());
      assertNull(channel.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER));
      assertEquals(
          List.of(
              "Refused a connection from /198.51.100.66:45678: the PROXY protocol is on and this"
                  + " address is not in proxy-protocol.trusted-proxies. If it is one of your load"
                  + " balancers, add it there."),
          warnings(initializerLog));
    }

    @Test
    @DisplayName("should refuse a trusted proxy that sends a Minecraft handshake without a header")
    void missingHeader() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      MinecraftConnection connection = connection(channel);

      channel.writeInbound(Unpooled.wrappedBuffer(statusHandshake(), statusRequest()));

      assertNull(channel.readOutbound(), "no status response");
      assertInstanceOf(HandshakeSessionHandler.class, connection.sessionHandler());
      assertRefused(channel, "the connection does not start with a PROXY protocol header");
    }

    @Test
    @DisplayName("should refuse a connection at the first byte that cannot start a header")
    void firstWrongByte() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(ascii("PROXZ"));

      assertRefused(channel, "does not start with a PROXY protocol header");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
        strings = {
          "PROXY TCP4 999.0.0.1 10.0.0.1 40000 25577\r\n",
          "PROXY TCP4 203.0.113.7 10.0.0.1 40000\r\n",
          "PROXY TCP4 203.0.113.7 10.0.0.1 70000 25577\r\n",
          "PROXY TCP4 2001:db8::7 10.0.0.1 40000 25577\r\n",
          "PROXY UDP4 203.0.113.7 10.0.0.1 40000 25577\r\n",
          "PROXY TCP4  203.0.113.7 10.0.0.1 40000 25577\r\n"
        })
    @DisplayName("should refuse a malformed v1 header")
    void malformedV1(String header) {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(
          Unpooled.wrappedBuffer(ascii(header), statusHandshake(), statusRequest()));

      assertRefused(channel, "invalid PROXY protocol header");
    }

    @Test
    @DisplayName("should refuse a v1 line longer than the protocol allows")
    void v1TooLong() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(ascii("PROXY TCP4 " + "1".repeat(120)));

      assertRefused(channel, "invalid PROXY protocol header");
    }

    @Test
    @DisplayName("should refuse a v2 header of an unknown version")
    void v2BadVersion() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(v2(0x31, TCP4, tcp4("203.0.113.7", "10.0.0.1", 1, 2)));

      assertRefused(channel, "invalid PROXY protocol header");
    }

    @Test
    @DisplayName("should refuse a v2 header whose addresses are cut short")
    void v2Truncated() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(v2(V2_PROXY, TCP6, tcp4("203.0.113.7", "10.0.0.1", 1, 2)));

      assertRefused(channel, "invalid PROXY protocol header");
    }

    @Test
    @DisplayName("should refuse a v2 header for UDP")
    void v2Udp() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      channel.writeInbound(v2(V2_PROXY, UDP4, tcp4("203.0.113.7", "10.0.0.1", 1, 2)));

      assertRefused(channel, "for a UDP4 connection, and Warp accepts TCP over IPv4 or IPv6 only");
    }

    @Test
    @DisplayName("should refuse a v2 header for a UNIX socket")
    void v2Unix() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      byte[] addresses = new byte[216];
      byte[] path = bytes("/run/haproxy.sock");
      System.arraycopy(path, 0, addresses, 0, path.length);
      System.arraycopy(path, 0, addresses, 108, path.length);

      channel.writeInbound(v2(V2_PROXY, UNIX_STREAM, addresses));

      assertRefused(channel, "for a UNIX_STREAM connection");
    }

    @Test
    @DisplayName("should let nothing that follows a refused header reach the Minecraft pipeline")
    void nothingAfterRefusal() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      MinecraftConnection connection = connection(channel);

      channel.writeInbound(
          Unpooled.wrappedBuffer(
              v2(V2_PROXY, UDP4, tcp4("203.0.113.7", "10.0.0.1", 1, 2)),
              statusHandshake(),
              statusRequest()));

      assertFalse(channel.isActive());
      assertNull(channel.readOutbound(), "no status response");
      assertInstanceOf(HandshakeSessionHandler.class, connection.sessionHandler());
      channel.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Reads and timing
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("reads and timing")
  class ReadsAndTiming {

    @Test
    @DisplayName("should assemble a v1 header that arrives one byte at a time")
    void v1ByteByByte() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      writeByteByByte(channel, ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"));

      assertEquals(socket("203.0.113.7", 40000), connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should assemble a v2 header that arrives one byte at a time")
    void v2ByteByByte() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      writeByteByByte(channel, v2(V2_PROXY, TCP4, tcp4("203.0.113.7", "10.0.0.1", 40000, 1)));

      assertEquals(socket("203.0.113.7", 40000), connection(channel).remoteAddress());
      assertStatusPingAnswered(channel);
    }

    @Test
    @DisplayName("should assemble a header split in the middle of its signature and its addresses")
    void splitHeader() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      ByteBuf header =
          Unpooled.wrappedBuffer(
              v2(V2_PROXY, TCP4, tcp4("203.0.113.7", "10.0.0.1", 40000, 1)), statusHandshake());

      channel.writeInbound(header.readRetainedSlice(5));
      channel.writeInbound(header.readRetainedSlice(17));
      channel.writeInbound(header);
      channel.writeInbound(statusRequest());

      assertEquals(socket("203.0.113.7", 40000), connection(channel).remoteAddress());
      assertStatusResponse(channel);
    }

    @Test
    @DisplayName("should close a trusted connection that sends no header in time")
    void headerTimeout() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      channel.writeInbound(ascii("PROXY TCP4 203.0.113.7"));

      channel.advanceTimeBy(
          ProxyProtocolHandler.HEADER_TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
      channel.runPendingTasks();
      assertTrue(channel.isActive(), "still within the header timeout");

      channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
      channel.runPendingTasks();
      assertRefused(channel, "it sent no complete PROXY protocol header within 5 s");
    }

    @Test
    @DisplayName("should not time out a connection whose header arrived")
    void noTimeoutAfterHeader() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);
      channel.writeInbound(ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"));

      channel.advanceTimeBy(ProxyProtocolHandler.HEADER_TIMEOUT.toSeconds() * 2, TimeUnit.SECONDS);
      channel.runPendingTasks();

      assertTrue(channel.isActive());
      assertTrue(warnings(handlerLog).isEmpty());
    }

    @Test
    @DisplayName("should close quietly a health check that disconnects without a header")
    void closedWithoutHeader() {
      EmbeddedChannel channel = connect(LOAD_BALANCER);

      var _ = channel.close();
      channel.advanceTimeBy(ProxyProtocolHandler.HEADER_TIMEOUT.toSeconds() * 2, TimeUnit.SECONDS);
      channel.runPendingTasks();

      assertTrue(warnings(handlerLog).isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // Warnings
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("warnings")
  class Warnings {

    @Test
    @DisplayName("should log one warning a minute for untrusted peers, then count the others")
    void throttledUntrustedWarnings() {
      ServerChannelInitializer initializer = initializer(TRUSTING_LOAD_BALANCER);

      for (int i = 0; i < 100; i++) {
        connect(socket("198.51.100." + (i % 250), 50000 + i), initializer);
      }
      assertEquals(1, warnings(initializerLog).size());

      nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(60));
      connect(STRANGER, initializer);

      List<String> warnings = warnings(initializerLog);
      assertEquals(2, warnings.size());
      assertTrue(
          warnings
              .get(1)
              .endsWith(
                  " (99 more connections refused from untrusted addresses since"
                      + " the last warning)"),
          warnings.get(1));
    }

    @Test
    @DisplayName("should log one warning a minute for bad headers from trusted proxies")
    void throttledHeaderWarnings() {
      ServerChannelInitializer initializer = initializer(TRUSTING_LOAD_BALANCER);

      for (int i = 0; i < 20; i++) {
        connect(LOAD_BALANCER, initializer).writeInbound(statusHandshake());
      }

      assertEquals(1, warnings(handlerLog).size());
    }
  }

  // ---------------------------------------------------------------------------
  // Disabled
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("should install nothing for the PROXY protocol when it is disabled")
  void disabled() {
    EmbeddedChannel channel = new PeerChannel(STRANGER);
    channel.pipeline().addLast(new ServerChannelInitializer(TestLoginContexts.offline()));

    assertNull(channel.pipeline().get(ServerChannelInitializer.PROXY_PROTOCOL_DECODER));
    assertNull(channel.pipeline().get(ServerChannelInitializer.PROXY_PROTOCOL_HANDLER));
    assertEquals(STRANGER, connection(channel).remoteAddress());
    assertStatusPingAnswered(channel);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ServerChannelInitializer initializer(ProxyProtocolConfig config) {
    return new ServerChannelInitializer(TestLoginContexts.offline(), config, nanoTime::get);
  }

  private EmbeddedChannel connect(InetSocketAddress peer) {
    return connect(peer, initializer(TRUSTING_LOAD_BALANCER));
  }

  private static EmbeddedChannel connect(
      InetSocketAddress peer, ServerChannelInitializer initializer) {
    EmbeddedChannel channel = new PeerChannel(peer);
    // Scheduled tasks, the header timeout among them, run only when a test advances the time.
    channel.freezeTime();
    channel.pipeline().addLast(initializer);
    return channel;
  }

  private static MinecraftConnection connection(EmbeddedChannel channel) {
    MinecraftConnection connection =
        (MinecraftConnection) channel.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER);
    assertNotNull(connection, "no connection handler");
    return connection;
  }

  private void assertRefused(EmbeddedChannel channel, String problem) {
    assertFalse(channel.isActive(), "the connection should be closed");
    List<String> warnings = warnings(handlerLog);
    assertEquals(1, warnings.size(), () -> String.valueOf(warnings));
    assertTrue(
        warnings.getFirst().startsWith("Refused a connection from trusted proxy /10.0.0.1:34567: "),
        warnings.getFirst());
    assertTrue(warnings.getFirst().contains(problem), warnings.getFirst());
    channel.finishAndReleaseAll();
  }

  private static List<String> warnings(LogCapture log) {
    return log.events().stream()
        .filter(event -> Level.WARN.equals(event.getLevel()))
        .map(LogEvent::getMessage)
        .map(message -> message.getFormattedMessage())
        .toList();
  }

  /** Sends a status handshake and request, and checks that a status response comes back. */
  private static void assertStatusPingAnswered(EmbeddedChannel channel) {
    channel.writeInbound(Unpooled.wrappedBuffer(statusHandshake(), statusRequest()));
    assertStatusResponse(channel);
  }

  private static void assertStatusResponse(EmbeddedChannel channel) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a status response");
    try {
      VarInt.read(frame);
      assertEquals(0x00, VarInt.read(frame), "status response packet id");
      assertTrue(McString.read(frame).contains("\"description\""));
    } finally {
      frame.release();
    }
    assertTrue(channel.isActive());
    channel.finishAndReleaseAll();
  }

  private static void writeByteByByte(EmbeddedChannel channel, ByteBuf bytes) {
    while (bytes.isReadable()) {
      channel.writeInbound(bytes.readRetainedSlice(1));
    }
    bytes.release();
  }

  private static ByteBuf statusHandshake() {
    return handshake(ProtocolVersion.MINECRAFT_1_21_4, 1);
  }

  private static ByteBuf handshake(ProtocolVersion version, int nextState) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, version.protocol());
    McString.write(body, "play.warp.dev");
    body.writeShort(25565);
    VarInt.write(body, nextState);
    return frame(body);
  }

  private static ByteBuf statusRequest() {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    return frame(body);
  }

  /** A 1.21.4 Login Start: the name, then the UUID the client claims. */
  private static ByteBuf loginStart(String name) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    body.writeLong(0).writeLong(0);
    return frame(body);
  }

  private static ByteBuf frame(ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  private static ByteBuf v2(int versionCommand, int familyProtocol, byte[] addresses) {
    return v2(versionCommand, familyProtocol, Unpooled.wrappedBuffer(addresses));
  }

  private static ByteBuf v2(int versionCommand, int familyProtocol, ByteBuf addresses) {
    ByteBuf header = Unpooled.buffer();
    header.writeBytes(V2_SIGNATURE);
    header.writeByte(versionCommand);
    header.writeByte(familyProtocol);
    header.writeShort(addresses.readableBytes());
    header.writeBytes(addresses);
    addresses.release();
    return header;
  }

  private static byte[] tcp4(String source, String destination, int sourcePort, int destPort) {
    return addresses(source, destination, sourcePort, destPort, 4);
  }

  private static byte[] tcp6(String source, String destination, int sourcePort, int destPort) {
    return addresses(source, destination, sourcePort, destPort, 16);
  }

  private static byte[] addresses(
      String source, String destination, int sourcePort, int destPort, int length) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeBytes(NetUtil.createByteArrayFromIpAddressString(source), 0, length);
    buf.writeBytes(NetUtil.createByteArrayFromIpAddressString(destination), 0, length);
    buf.writeShort(sourcePort);
    buf.writeShort(destPort);
    byte[] bytes = new byte[buf.readableBytes()];
    buf.readBytes(bytes);
    buf.release();
    return bytes;
  }

  private static ByteBuf ascii(String text) {
    return Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static String offlineUuid(String name) {
    return java.util
        .UUID
        .nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static InetSocketAddress socket(String literal, int port) {
    try {
      return new InetSocketAddress(
          InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal)), port);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  /** An embedded channel whose peer is a real socket address. */
  static final class PeerChannel extends EmbeddedChannel {

    private final SocketAddress peer;

    PeerChannel(SocketAddress peer) {
      this.peer = peer;
    }

    @Override
    protected SocketAddress remoteAddress0() {
      return peer;
    }
  }
}
