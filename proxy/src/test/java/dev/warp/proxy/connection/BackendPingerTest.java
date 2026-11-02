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

import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPing;
import dev.warp.protocol.ProtocolVersion;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BackendPinger")
class BackendPingerTest {

  private static final String STATUS =
      "{\"version\":{\"name\":\"1.21.4\",\"protocol\":769},\"description\":{\"text\":\"hi\"}}";

  private final MultiThreadIoEventLoopGroup group =
      new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

  @AfterEach
  void tearDown() {
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
  }

  @Test
  @DisplayName("should announce the protocol asked for, -1 included, and read the server's own")
  void announcesRequestedProtocol() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<Integer> announced = answerOnce(socket);
      ServerInfo server =
          new ServerInfo(
              "lobby", new InetSocketAddress(socket.getInetAddress(), socket.getLocalPort()));

      ServerPing ping =
          new BackendPinger(group, NioSocketChannel.class)
              .ping(server, -1)
              .get(10, TimeUnit.SECONDS);

      assertEquals(-1, announced.get(10, TimeUnit.SECONDS));
      assertEquals(769, ping.version().protocol());
    }
  }

  @Test
  @DisplayName("should announce the newest protocol by default")
  void announcesLatestByDefault() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<Integer> announced = answerOnce(socket);
      ServerInfo server =
          new ServerInfo(
              "lobby", new InetSocketAddress(socket.getInetAddress(), socket.getLocalPort()));

      var _ =
          new BackendPinger(group, NioSocketChannel.class).ping(server).get(10, TimeUnit.SECONDS);

      assertEquals(ProtocolVersion.latest().protocol(), announced.get(10, TimeUnit.SECONDS));
    }
  }

  /** Plays a server answering one status ping; completes with the protocol of its handshake. */
  private static CompletableFuture<Integer> answerOnce(ServerSocket socket) {
    CompletableFuture<Integer> announced = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (Socket client = socket.accept()) {
                DataInputStream in = new DataInputStream(client.getInputStream());
                readVarInt(in); // handshake frame length
                readVarInt(in); // packet id 0
                announced.complete(readVarInt(in));
                in.readFully(new byte[readVarInt(in)]); // host
                in.readUnsignedShort(); // port
                readVarInt(in); // intent
                readVarInt(in); // status request frame length
                readVarInt(in); // packet id 0
                OutputStream out = client.getOutputStream();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] json = STATUS.getBytes(StandardCharsets.UTF_8);
                writeVarInt(body, 0);
                writeVarInt(body, json.length);
                body.write(json);
                writeVarInt(out, body.size());
                body.writeTo(out);
                out.flush();
                // Wait for the pinger to close the connection.
                while (in.read() >= 0) {
                  // drain
                }
              } catch (IOException e) {
                announced.completeExceptionally(e);
              }
            });
    return announced;
  }

  private static int readVarInt(InputStream in) throws IOException {
    int value = 0;
    for (int shift = 0; ; shift += 7) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("end of stream");
      }
      value |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return value;
      }
    }
  }

  private static void writeVarInt(OutputStream out, int value) throws IOException {
    while ((value & ~0x7F) != 0) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value);
  }
}
