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
package dev.warp.proxy.fleet.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ValkeyConnection")
class ValkeyConnectionTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final long IDLE_MILLIS = 150;

  private final EventLoopGroup loops =
      new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
  private final List<Boolean> states = new CopyOnWriteArrayList<>();
  private final List<Throwable> failures = new CopyOnWriteArrayList<>();
  private final List<AutoCloseable> open = new CopyOnWriteArrayList<>();

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable closeable : open) {
      closeable.close();
    }
    var _ = loops.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
  }

  private ValkeyConnection connect(FakeValkey server) {
    ValkeyConnection connection =
        new ValkeyConnection(
            "test",
            server.address(),
            null,
            null,
            null,
            0,
            () -> loops,
            server::clientChannel,
            _ -> {},
            _ -> CompletableFuture.completedFuture(null),
            states::add,
            failures::add,
            IDLE_MILLIS);
    open.add(connection::close);
    connection.start();
    return connection;
  }

  static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + TIMEOUT);
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Nested
  @DisplayName("watchdog")
  class Watchdog {

    @Test
    @DisplayName(
        "should ping a silent server, then drop the connection when the ping goes unanswered")
    void halfOpen() {
      FakeValkey server =
          new FakeValkey(command -> command.getFirst().equals("HELLO") ? "%0\r\n" : null);
      open.add(server);
      ValkeyConnection connection = connect(server);
      await(() -> states.equals(List.of(true)));

      // The server went away without a reset: a heartbeat waits for a reply that never comes.
      CompletableFuture<RespValue> stuck = connection.command("HSET", "k", "f", "v");

      await(() -> server.received("PING"));
      await(() -> states.size() >= 2);
      assertEquals(false, states.get(1), "the connection reported itself down");
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> stuck.get(1, TimeUnit.SECONDS));
      ValkeyConnection.ValkeyException down =
          assertInstanceOf(ValkeyConnection.ValkeyException.class, failure.getCause());
      assertEquals("DOWN", down.code());

      // And it reconnects by itself.
      await(() -> states.size() >= 3 && states.get(2));
      assertTrue(connection.isReady());
    }

    @Test
    @DisplayName("should stay connected while the server answers the pings")
    void pinged() throws InterruptedException {
      FakeValkey server = new FakeValkey(FakeValkey.ANSWERING);
      open.add(server);
      ValkeyConnection connection = connect(server);
      await(() -> states.equals(List.of(true)));

      Thread.sleep(IDLE_MILLIS * 5);

      assertTrue(server.received("PING"), "pinged while idle");
      assertEquals(List.of(true), states, "never dropped");
      assertEquals(List.of(), failures, "never failed");
      assertTrue(connection.isReady());
      assertEquals("PONG", connection.command("PING").join().text());
    }
  }

  @Nested
  @DisplayName("handshake")
  class Handshake {

    @Test
    @DisplayName("should take commands between HELLO and readiness, so a SUBSCRIBE is not lost")
    void acceptsWhileHandshaking() {
      FakeValkey server = new FakeValkey(_ -> null); // HELLO goes unanswered for now
      open.add(server);
      ValkeyConnection connection = connect(server);
      await(() -> server.received("HELLO"));

      assertFalse(connection.isReady());
      assertTrue(connection.acceptsCommands(), "handshaking");
      connection.fireAndForget("SUBSCRIBE", "x");
      await(() -> server.received("SUBSCRIBE"));

      var _ =
          server
              .clients
              .getFirst()
              .writeAndFlush(Unpooled.copiedBuffer("%0\r\n", StandardCharsets.UTF_8));
      await(connection::isReady);
      assertTrue(connection.acceptsCommands());
    }

    @Test
    @DisplayName("should take no command before it is started")
    void refusesWhenDown() {
      FakeValkey server = new FakeValkey(FakeValkey.ANSWERING);
      open.add(server);
      ValkeyConnection connection =
          new ValkeyConnection(
              "idle",
              server.address(),
              null,
              null,
              null,
              0,
              () -> loops,
              server::clientChannel,
              _ -> {},
              _ -> CompletableFuture.completedFuture(null),
              states::add,
              failures::add,
              IDLE_MILLIS);

      assertFalse(connection.acceptsCommands());
      ExecutionException failure =
          assertThrows(
              ExecutionException.class, () -> connection.command("PING").get(1, TimeUnit.SECONDS));
      ValkeyConnection.ValkeyException down =
          assertInstanceOf(ValkeyConnection.ValkeyException.class, failure.getCause());
      assertEquals("DOWN", down.code());
    }
  }
}
