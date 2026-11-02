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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.proxy.config.ProxyProtocolConfig;
import dev.warp.proxy.network.CidrRanges;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A player behind a load balancer that sends the PROXY protocol, through a real Warp listener on
 * loopback to a backend that asks for Velocity modern forwarding: the backend must receive the
 * address of the header, signed, and not the load balancer's.
 */
@DisplayName("PROXY protocol address in Velocity modern forwarding")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ProxyProtocolForwardingTest {

  private static final byte[] SECRET = "forwarding-secret".getBytes(StandardCharsets.UTF_8);
  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;
  private static final int HMAC_LENGTH = 32;

  private final MultiThreadIoEventLoopGroup group =
      new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

  private ServerSocket backend;
  private Channel listener;

  @BeforeEach
  void start() throws IOException, InterruptedException {
    InetAddress loopback = InetAddress.getLoopbackAddress();
    backend = new ServerSocket(0, 1, loopback);
    ServerLoginContext context =
        TestLoginContexts.velocityForwarding(
            new InetSocketAddress(loopback, backend.getLocalPort()), SECRET);
    // The client connects from loopback, which plays the trusted load balancer.
    ProxyProtocolConfig proxyProtocol =
        new ProxyProtocolConfig(true, CidrRanges.parse(List.of("127.0.0.0/8", "::1")));
    listener =
        new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ServerChannelInitializer(context, proxyProtocol))
            .bind(loopback, 0)
            .sync()
            .channel();
  }

  @AfterEach
  void stop() throws IOException {
    listener.close().awaitUninterruptibly();
    backend.close();
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
  }

  @Test
  @DisplayName("should forward the client address of the PROXY protocol header to the backend")
  void forwardsHeaderAddress() throws Exception {
    try (Socket client = new Socket()) {
      client.setSoTimeout(10_000);
      client.connect(listener.localAddress());

      // The load balancer's header, then the player's handshake, login start and acknowledgement,
      // which Warp answers with Login Success before it connects to the backend.
      OutputStream out = client.getOutputStream();
      out.write(
          "PROXY TCP4 203.0.113.7 192.0.2.1 40000 25565\r\n".getBytes(StandardCharsets.US_ASCII));
      out.write(frame(handshake()));
      out.write(frame(loginStart("Steve")));
      out.write(frame(new byte[] {0x03})); // Login Acknowledged
      out.flush();

      byte[] forwarding = forwardingDataSentTo(backend);

      byte[] signature = Arrays.copyOfRange(forwarding, 0, HMAC_LENGTH);
      byte[] data = Arrays.copyOfRange(forwarding, HMAC_LENGTH, forwarding.length);
      assertArrayEquals(hmacSha256(data), signature, "signed with the forwarding secret");
      ByteBuf fields = Unpooled.wrappedBuffer(data);
      assertEquals(1, VarInt.read(fields), "forwarding version");
      assertEquals("203.0.113.7", McString.read(fields), "the client's address");
    }
  }

  // ---------------------------------------------------------------------------
  // Backend side
  // ---------------------------------------------------------------------------

  /**
   * Accepts Warp's connection on the backend, reads its handshake and login start, asks for the
   * player's information on {@code velocity:player_info} and returns the payload of the answer.
   */
  private static byte[] forwardingDataSentTo(ServerSocket backend) throws IOException {
    try (Socket connection = backend.accept();
        DataInputStream in = new DataInputStream(connection.getInputStream())) {
      connection.setSoTimeout(10_000);
      readFrame(in); // Handshake
      readFrame(in); // Login Start

      ByteBuf request = Unpooled.buffer();
      VarInt.write(request, 0x04); // Login Plugin Request
      VarInt.write(request, 7); // message id
      McString.write(request, PlayerForwarding.VELOCITY_CHANNEL);
      request.writeByte(1); // the forwarding version the backend supports
      OutputStream out = connection.getOutputStream();
      out.write(frame(ByteBufUtil.getBytes(request)));
      out.flush();
      request.release();

      ByteBuf response = Unpooled.wrappedBuffer(readFrame(in));
      assertEquals(0x02, VarInt.read(response), "Login Plugin Response");
      assertEquals(7, VarInt.read(response), "message id");
      assertTrue(response.readBoolean(), "Warp understood the request");
      return ByteBufUtil.getBytes(response);
    }
  }

  private static byte[] readFrame(DataInputStream in) throws IOException {
    int length = readVarInt(in);
    byte[] frame = new byte[length];
    in.readFully(frame);
    return frame;
  }

  private static int readVarInt(InputStream in) throws IOException {
    int value = 0;
    for (int shift = 0; shift < 35; shift += 7) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("connection closed");
      }
      value |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    throw new IOException("VarInt too long");
  }

  // ---------------------------------------------------------------------------
  // Client side
  // ---------------------------------------------------------------------------

  private static byte[] handshake() {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, VERSION.protocol());
    McString.write(body, "play.warp.dev");
    body.writeShort(25565);
    VarInt.write(body, 2); // login
    return bytes(body);
  }

  private static byte[] loginStart(String name) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    body.writeLong(0).writeLong(0); // the UUID the client claims, unused offline
    return bytes(body);
  }

  private static byte[] frame(byte[] body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.length);
    frame.writeBytes(body);
    return bytes(frame);
  }

  private static byte[] bytes(ByteBuf buf) {
    byte[] bytes = ByteBufUtil.getBytes(buf);
    buf.release();
    return bytes;
  }

  private static byte[] hmacSha256(byte[] data) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
    return mac.doFinal(data);
  }
}
