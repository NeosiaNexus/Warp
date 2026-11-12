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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
   * The pings of one check, to read before they are all over.
   *
   * @param results each server's result, in the order given, once its ping is over
   * @param pending each server as it shows while its ping is under way, in the same order
   */
  public record Pings(
      List<CompletableFuture<Summaries.Server>> results, List<Summaries.Server> pending) {

    /**
     * Returns every result once every ping is over.
     *
     * @return the results, in the order the servers were given
     */
    public CompletableFuture<List<Summaries.Server>> all() {
      return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
          .thenApply(_ -> results.stream().map(CompletableFuture::join).toList());
    }

    /**
     * Returns what the pings answered within a budget, without stopping the pings still under way:
     * they go on and feed the servers' health. A server that has not answered yet is returned
     * {@linkplain Summaries.Server#pending() pending}.
     *
     * @param budget how long to wait for the pings at most
     * @return the results, in the order the servers were given
     * @throws InterruptedException if interrupted while waiting
     */
    public List<Summaries.Server> within(Duration budget) throws InterruptedException {
      try {
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
            .get(Math.max(0, budget.toMillis()), TimeUnit.MILLISECONDS);
      } catch (ExecutionException | TimeoutException e) {
        // A ping that failed answered (handled below); the others are still under way.
      }
      List<Summaries.Server> servers = new ArrayList<>(results.size());
      for (int i = 0; i < results.size(); i++) {
        CompletableFuture<Summaries.Server> result = results.get(i);
        servers.add(
            result.isDone() && !result.isCompletedExceptionally() ? result.join() : pending.get(i));
      }
      return List.copyOf(servers);
    }
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
    return start(servers, timeout).all();
  }

  /**
   * Starts pinging servers. The pings run on; the caller reads what answered within a budget with
   * {@link Pings#within}, or waits for all of them with {@link Pings#all}.
   *
   * @param servers the servers
   * @param timeout how long each ping may take
   * @return the pings
   */
  public Pings start(Collection<? extends RegisteredServer> servers, Duration timeout) {
    List<CompletableFuture<Summaries.Server>> pings = new ArrayList<>(servers.size());
    List<Summaries.Server> pending = new ArrayList<>(servers.size());
    for (RegisteredServer server : servers) {
      long start = System.nanoTime();
      String name = server.name();
      String address = address(server.info().address());
      pending.add(Summaries.Server.pending(name, address));
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
    return new Pings(List.copyOf(pings), List.copyOf(pending));
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
