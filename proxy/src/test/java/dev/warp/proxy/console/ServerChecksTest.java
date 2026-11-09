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
package dev.warp.proxy.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPing;
import dev.warp.api.server.ServerPingException;
import dev.warp.proxy.server.ServerHealth;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServerChecks")
class ServerChecksTest {

  /** A deadline longer than the former fixed 2 s, as an operator raises it for a slow server. */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final ServerHealth health = new ServerHealth();
  private final ServerChecks checks = new ServerChecks(health);

  @Test
  @DisplayName("should ping every server with the deadline it is given")
  void pingsWithGivenTimeout() {
    RegisteredServer lobby = server("lobby", CompletableFuture.completedFuture(ping()));
    RegisteredServer games = server("games", CompletableFuture.completedFuture(ping()));

    var _ = checks.check(List.of(lobby, games), TIMEOUT).join();

    verify(lobby).ping(TIMEOUT);
    verify(games).ping(TIMEOUT);
  }

  @Test
  @DisplayName("should close a server's breaker and keep its round trip when it answers")
  void answeredPingFeedsHealth() {
    health.pingFailed("lobby");
    RegisteredServer lobby = server("lobby", CompletableFuture.completedFuture(ping()));

    List<Summaries.Server> results = checks.check(List.of(lobby), TIMEOUT).join();

    assertTrue(health.allows("lobby"));
    assertTrue(health.latency("lobby").isPresent());
    assertEquals(1, checks.countUp(List.of(lobby)));
    assertEquals("1.21.4", results.getFirst().version());
  }

  @Test
  @DisplayName("should open a server's breaker when its ping fails")
  void failedPingFeedsHealth() {
    RegisteredServer lobby =
        server(
            "lobby",
            CompletableFuture.failedFuture(
                new ServerPingException(
                    new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25565)),
                    ServerPingException.Reason.TIMED_OUT,
                    "no answer",
                    null)));

    List<Summaries.Server> results = checks.check(List.of(lobby), TIMEOUT).join();

    assertFalse(health.allows("lobby"));
    assertEquals(0, checks.countUp(List.of(lobby)));
    assertEquals(-1, results.getFirst().latencyMillis());
  }

  private static RegisteredServer server(String name, CompletableFuture<ServerPing> answer) {
    RegisteredServer server = mock(RegisteredServer.class);
    when(server.name()).thenReturn(name);
    when(server.info()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
    when(server.ping(any(Duration.class))).thenReturn(answer);
    return server;
  }

  private static ServerPing ping() {
    return new ServerPing(new ServerPing.Version("1.21.4", 769), null, Component.text("hi"), null);
  }
}
