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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPing;
import dev.warp.api.server.ServerPingException;
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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BackendPinger")
class BackendPingerTest {

  private final MultiThreadIoEventLoopGroup group =
      new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
  private final BackendPinger pinger = new BackendPinger(group, NioSocketChannel.class);

  @AfterEach
  void tearDown() {
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
  }

  @Nested
  @DisplayName("when the ping fails")
  class Failures {

    @Test
    @DisplayName("should time out a server that accepts the connection and never answers")
    void silentServer() throws IOException {
      try (ServerSocket silent = listen()) {
        Thread acceptor = Thread.ofVirtual().start(() -> acceptAndHold(silent));

        ServerPingException failure = failure(pinger.ping(info(silent), Duration.ofMillis(300)));

        // The ping's own deadline, not the connection's: it covers the whole ping.
        assertEquals(ServerPingException.Reason.TIMED_OUT, failure.reason());
        assertEquals(
            "Ping of backend (" + info(silent).address() + ") failed: no answer within 300 ms",
            failure.getMessage());
        acceptor.interrupt();
      }
    }

    @Test
    @DisplayName("should report a refused connection as UNREACHABLE")
    void refused() throws IOException {
      ServerInfo closed;
      try (ServerSocket socket = listen()) {
        closed = info(socket);
      }

      ServerPingException failure = failure(pinger.ping(closed, Duration.ofSeconds(5)));

      assertEquals(ServerPingException.Reason.UNREACHABLE, failure.reason());
      assertEquals(closed, failure.server());
    }

    @Test
    @DisplayName("should report a server that closes without answering as CLOSED")
    void closesWithoutAnswer() throws IOException {
      try (ServerSocket socket = listen()) {
        Thread.ofVirtual()
            .start(
                () -> {
                  try (Socket client = socket.accept()) {
                    client.getInputStream().read();
                  } catch (IOException ignored) {
                    // The test is over.
                  }
                });

        ServerPingException failure = failure(pinger.ping(info(socket), Duration.ofSeconds(5)));

        assertEquals(ServerPingException.Reason.CLOSED, failure.reason());
      }
    }

    @Test
    @DisplayName("should report an answer that is no server list entry as INVALID_RESPONSE")
    void invalidAnswer() throws IOException {
      try (ServerSocket socket = listen()) {
        var _ = answerWhenAsked(socket, "not json");

        ServerPingException failure = failure(pinger.ping(info(socket), Duration.ofSeconds(5)));

        assertEquals(ServerPingException.Reason.INVALID_RESPONSE, failure.reason());
      }
    }

    @Test
    @DisplayName("should fail at once, without hanging, once the event loops are shut down")
    void shutDown() throws IOException {
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS).awaitUninterruptibly();
      try (ServerSocket socket = listen()) {

        ServerPingException failure = failure(pinger.ping(info(socket), Duration.ofSeconds(5)));

        assertEquals(ServerPingException.Reason.UNREACHABLE, failure.reason());
      }
    }
  }

  @Nested
  @DisplayName("when the server answers")
  class Answers {

    @Test
    @DisplayName("should read the answer, completing off the event loop")
    void answers() throws Exception {
      try (ServerSocket socket = listen()) {
        var _ =
            answerWhenAsked(
                socket,
                "{\"version\":{\"name\":\"Paper 1.21.4\",\"protocol\":769},"
                    + "\"players\":{\"online\":3,\"max\":100},\"description\":\"Lobby\"}");
        AtomicBoolean onVirtualThread = new AtomicBoolean();

        ServerPing ping =
            pinger
                .ping(info(socket), Duration.ofSeconds(5))
                .whenComplete((p, e) -> onVirtualThread.set(Thread.currentThread().isVirtual()))
                .get(10, TimeUnit.SECONDS);

        assertEquals(769, ping.version().protocol());
        assertEquals(3, ping.playerInfo().orElseThrow().online());
        assertTrue(onVirtualThread.get(), "a blocking callback must not stall an event loop");
      }
    }

    @Test
    @DisplayName("should read an answer the server sends before it is asked")
    void answerBeforeTheRequest() throws Exception {
      try (ServerSocket socket = listen()) {
        Thread.ofVirtual().start(() -> answerUnasked(socket, STATUS));

        ServerPing ping =
            pinger.ping(info(socket), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);

        assertEquals(769, ping.version().protocol());
      }
    }

    @Test
    @DisplayName("should announce the protocol asked for, -1 included, and read the server's own")
    void announcesRequestedProtocol() throws Exception {
      try (ServerSocket socket = listen()) {
        CompletableFuture<Integer> announced = answerWhenAsked(socket, STATUS);

        ServerPing ping =
            pinger.ping(info(socket), -1, Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);

        assertEquals(-1, announced.get(10, TimeUnit.SECONDS));
        assertEquals(769, ping.version().protocol());
      }
    }

    @Test
    @DisplayName("should announce the newest protocol by default")
    void announcesLatestByDefault() throws Exception {
      try (ServerSocket socket = listen()) {
        CompletableFuture<Integer> announced = answerWhenAsked(socket, STATUS);

        var _ = pinger.ping(info(socket), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);

        assertEquals(ProtocolVersion.latest().protocol(), announced.get(10, TimeUnit.SECONDS));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** A server list entry of protocol 769. */
  private static final String STATUS =
      "{\"version\":{\"name\":\"1.21.4\",\"protocol\":769},\"description\":{\"text\":\"hi\"}}";

  private static ServerSocket listen() throws IOException {
    return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
  }

  private static ServerInfo info(ServerSocket socket) {
    return new ServerInfo(
        "backend", new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort()));
  }

  private static ServerPingException failure(CompletableFuture<ServerPing> ping) {
    ExecutionException thrown =
        assertThrows(ExecutionException.class, () -> ping.get(10, TimeUnit.SECONDS));
    return assertInstanceOf(ServerPingException.class, thrown.getCause());
  }

  private static void acceptAndHold(ServerSocket socket) {
    try (Socket client = socket.accept()) {
      client.getInputStream().readAllBytes();
    } catch (IOException ignored) {
      // The test is over.
    }
  }

  /**
   * Plays a server that answers before it is asked: on accepting the connection, it sends {@code
   * json} as its status response, then reads until the pinger closes the connection.
   */
  private static void answerUnasked(ServerSocket socket, String json) {
    try (Socket client = socket.accept()) {
      OutputStream out = client.getOutputStream();
      out.write(statusResponse(json));
      out.flush();
      client.getInputStream().readAllBytes();
    } catch (IOException ignored) {
      // The pinger closed the connection once answered.
    }
  }

  /** The frame of a status response carrying {@code json}, unchecked. */
  private static byte[] statusResponse(String json) {
    byte[] text = json.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeVarInt(body, 0); // Status Response
    writeVarInt(body, text.length);
    body.write(text, 0, text.length);
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    writeVarInt(frame, body.size());
    frame.write(body.toByteArray(), 0, body.size());
    return frame.toByteArray();
  }

  private static void writeVarInt(ByteArrayOutputStream out, int value) {
    int remaining = value;
    while ((remaining & ~0x7F) != 0) {
      out.write((remaining & 0x7F) | 0x80);
      remaining >>>= 7;
    }
    out.write(remaining);
  }

  /**
   * Plays a server answering one status ping as servers do: once it has read the handshake and the
   * status request, it answers with {@code json}, unchecked, then reads until the pinger closes the
   * connection. Completes with the protocol the handshake announced.
   */
  private static CompletableFuture<Integer> answerWhenAsked(ServerSocket socket, String json) {
    CompletableFuture<Integer> announced = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (Socket client = socket.accept();
                  DataInputStream in = new DataInputStream(client.getInputStream())) {
                readVarInt(in); // handshake frame length
                readVarInt(in); // packet id 0
                int protocol = readVarInt(in);
                in.readFully(new byte[readVarInt(in)]); // host
                in.readUnsignedShort(); // port
                readVarInt(in); // intent
                readVarInt(in); // status request frame length
                readVarInt(in); // packet id 0
                announced.complete(protocol);
                OutputStream out = client.getOutputStream();
                out.write(statusResponse(json));
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
}
