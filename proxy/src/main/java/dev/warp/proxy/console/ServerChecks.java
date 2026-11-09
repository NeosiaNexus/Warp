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

import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerPing;
import dev.warp.proxy.server.ServerHealth;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Pings the backend servers for the console: once at startup, for the summary, then every few
 * seconds while the status line shows, to count the servers up. Pings run on the proxy's event
 * loops; nothing here waits for them.
 */
public final class ServerChecks {

  /** The servers that answered their last ping, by name. */
  private final Map<String, Boolean> up = new ConcurrentHashMap<>();

  private final @Nullable ServerHealth health;

  /** Creates checks that only remember which servers are up. */
  public ServerChecks() {
    this(null);
  }

  /**
   * Creates checks that also feed the servers' health: a ping answered closes a server's circuit
   * breaker and records its round trip, one unanswered opens it.
   *
   * @param health the health to feed, or {@code null} for none
   */
  public ServerChecks(@Nullable ServerHealth health) {
    this.health = health;
  }

  /**
   * Pings servers.
   *
   * @param servers the servers
   * @param timeout how long each ping may take
   * @return each server's result, in the order given, once every ping is over
   */
  public CompletableFuture<List<Summaries.Server>> check(
      Collection<? extends RegisteredServer> servers, Duration timeout) {
    List<CompletableFuture<Summaries.Server>> pings = new ArrayList<>(servers.size());
    for (RegisteredServer server : servers) {
      long start = System.nanoTime();
      String name = server.name();
      String address = address(server.info().address());
      pings.add(
          server
              .ping(timeout)
              .handle(
                  (ping, error) -> {
                    boolean answered = error == null && ping != null;
                    up.put(name, answered);
                    long elapsed = Math.max(0, System.nanoTime() - start);
                    long millis = elapsed / 1_000_000;
                    if (health != null) {
                      if (answered) {
                        health.pingSucceeded(name, Duration.ofNanos(elapsed));
                      } else {
                        health.pingFailed(name);
                      }
                    }
                    return new Summaries.Server(
                        name, address, answered ? millis : -1, answered ? version(ping) : null);
                  }));
    }
    return CompletableFuture.allOf(pings.toArray(CompletableFuture[]::new))
        .thenApply(_ -> pings.stream().map(CompletableFuture::join).toList());
  }

  /**
   * Counts the servers that answered their last ping, among those registered.
   *
   * @param servers the servers registered
   * @return how many are up
   */
  public int countUp(Collection<? extends RegisteredServer> servers) {
    int count = 0;
    for (RegisteredServer server : servers) {
      if (Boolean.TRUE.equals(up.get(server.name()))) {
        count++;
      }
    }
    return count;
  }

  private static String version(ServerPing ping) {
    return ping.version().name();
  }

  private static String address(InetSocketAddress address) {
    String host = address.getHostString();
    return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + address.getPort();
  }
}
