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
package dev.warp.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.Warp;
import dev.warp.api.server.ServerGroup;
import dev.warp.proxy.server.Balancers;
import dev.warp.proxy.server.HostRoutes;
import dev.warp.proxy.server.ServerHealth;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("/warp groups and /warp routes")
class WarpGroupsCommandTest {

  private final AtomicLong now = new AtomicLong();
  private final ServerHealth health = new ServerHealth(now::get, Duration.ofSeconds(10));
  private final ServerRegistry servers =
      new ServerRegistry(
          Map.of(
              "lobby1", new InetSocketAddress("localhost", 1),
              "lobby2", new InetSocketAddress("localhost", 2)),
          "lobby1",
          List.of(),
          health);
  private final Warp proxy = mock(Warp.class);

  WarpGroupsCommandTest() {
    when(proxy.servers()).thenReturn(servers);
    when(proxy.groups()).thenReturn(servers.groups());
  }

  @Test
  @DisplayName("should say when no group is configured")
  void noGroups() {
    String text = AdminFixture.plain(WarpCommand.groups(proxy, health));

    assertTrue(text.contains("No server group"), text);
  }

  @Test
  @DisplayName("should show each group with the balancer's view of its servers")
  void showsGroups() {
    var _ =
        servers
            .groups()
            .register(
                "lobbies",
                List.of(
                    ServerGroup.Member.of("lobby1"),
                    ServerGroup.Member.of("lobby2"),
                    ServerGroup.Member.of("lobby3")),
                Balancers.leastConnections(),
                "least-connections");
    health.pingSucceeded("lobby1", Duration.ofMillis(12));
    health.pingFailed("lobby2");

    String text = AdminFixture.plain(WarpCommand.groups(proxy, health));

    assertTrue(text.contains("lobbies (least-connections, 3 servers)"), text);
    assertTrue(text.contains("lobby1 0 players · 12 ms · takes players"), text);
    assertTrue(
        text.contains("lobby2 0 players · no ping yet · skipped (1 failures, in cool-down)"), text);
    assertTrue(text.contains("lobby3 not registered"), text);

    now.addAndGet(Duration.ofSeconds(10).toNanos());
    assertTrue(AdminFixture.plain(WarpCommand.groups(proxy, health)).contains("probing"), text);
  }

  @Test
  @DisplayName("should list the routes most specific first, or say there is none")
  void showsRoutes() {
    assertTrue(AdminFixture.plain(WarpCommand.routes(HostRoutes.EMPTY)).contains("No route"));

    HostRoutes routes =
        HostRoutes.compile(Map.of("*.example.com", "$1", "play.example.com", "lobbies"));
    String text = AdminFixture.plain(WarpCommand.routes(routes));

    assertEquals(
        "Routes 2 (most specific first)\n- play.example.com -> lobbies\n- *.example.com -> $1",
        text);
  }
}
