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
package dev.warp.proxy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.server.Balancer;
import dev.warp.api.server.RegisteredServer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Balancers")
class BalancersTest {

  private record TestCandidate(
      RegisteredServer server, int weight, int connections, @Nullable Duration rtt)
      implements Balancer.Candidate {

    @Override
    public Optional<Duration> latency() {
      return Optional.ofNullable(rtt);
    }
  }

  private static RegisteredServer server(String name) {
    RegisteredServer server = mock(RegisteredServer.class);
    when(server.name()).thenReturn(name);
    return server;
  }

  private static TestCandidate candidate(String name, int connections) {
    return new TestCandidate(server(name), 1, connections, null);
  }

  private static String pick(Balancer balancer, List<Balancer.Candidate> candidates) {
    return balancer.choose(candidates).orElseThrow().name();
  }

  @Nested
  @DisplayName("round-robin")
  class RoundRobin {

    @Test
    @DisplayName("should cycle through the servers in the order of the group")
    void cycles() {
      Balancer balancer = Balancers.roundRobin();
      List<Balancer.Candidate> candidates =
          List.of(candidate("a", 0), candidate("b", 0), candidate("c", 0));

      assertEquals("a", pick(balancer, candidates));
      assertEquals("b", pick(balancer, candidates));
      assertEquals("c", pick(balancer, candidates));
      assertEquals("a", pick(balancer, candidates));
    }

    @Test
    @DisplayName("should keep going when the candidates change")
    void adaptsToCandidates() {
      Balancer balancer = Balancers.roundRobin();
      assertEquals("a", pick(balancer, List.of(candidate("a", 0), candidate("b", 0))));
      assertEquals("c", pick(balancer, List.of(candidate("c", 0))));
    }
  }

  @Nested
  @DisplayName("least-connections")
  class LeastConnections {

    @Test
    @DisplayName("should pick the server with the fewest players, the first among equals")
    void fewest() {
      Balancer balancer = Balancers.leastConnections();

      assertEquals(
          "b", pick(balancer, List.of(candidate("a", 5), candidate("b", 2), candidate("c", 2))));
      assertEquals("a", pick(balancer, List.of(candidate("a", 1), candidate("b", 1))));
    }
  }

  @Nested
  @DisplayName("lowest-latency")
  class LowestLatency {

    @Test
    @DisplayName("should pick the fastest ping, servers never pinged last")
    void fastest() {
      Balancer balancer = Balancers.lowestLatency();
      List<Balancer.Candidate> candidates =
          List.of(
              new TestCandidate(server("unknown"), 1, 0, null),
              new TestCandidate(server("slow"), 1, 0, Duration.ofMillis(50)),
              new TestCandidate(server("fast"), 1, 9, Duration.ofMillis(5)));

      assertEquals("fast", pick(balancer, candidates));
      assertEquals("unknown", pick(balancer, List.of(candidates.getFirst())));
    }

    @Test
    @DisplayName("should break a latency tie by player count")
    void tieByConnections() {
      Balancer balancer = Balancers.lowestLatency();
      List<Balancer.Candidate> candidates =
          List.of(
              new TestCandidate(server("busy"), 1, 10, Duration.ofMillis(5)),
              new TestCandidate(server("idle"), 1, 1, Duration.ofMillis(5)));

      assertEquals("idle", pick(balancer, candidates));
    }
  }

  @Nested
  @DisplayName("random and weighted")
  class RandomAndWeighted {

    @Test
    @DisplayName("random should only ever pick a candidate")
    void randomPicksCandidates() {
      Balancer balancer = Balancers.random();
      List<Balancer.Candidate> candidates = List.of(candidate("a", 0), candidate("b", 0));
      for (int i = 0; i < 100; i++) {
        assertTrue(Set.of("a", "b").contains(pick(balancer, candidates)));
      }
    }

    @Test
    @DisplayName("weighted should favour the heavier server and never pick a weight of zero share")
    void weightedFavoursHeavier() {
      Balancer balancer = Balancers.weighted();
      List<Balancer.Candidate> candidates =
          List.of(
              new TestCandidate(server("heavy"), 9, 0, null),
              new TestCandidate(server("light"), 1, 0, null));
      Map<String, Integer> picks = new HashMap<>();
      for (int i = 0; i < 2000; i++) {
        picks.merge(pick(balancer, candidates), 1, Integer::sum);
      }
      assertTrue(picks.getOrDefault("heavy", 0) > picks.getOrDefault("light", 0) * 3);
    }

    @Test
    @DisplayName("weighted should pick the only candidate")
    void weightedSingle() {
      assertEquals(
          "only",
          pick(Balancers.weighted(), List.of(new TestCandidate(server("only"), 3, 0, null))));
    }
  }

  @Nested
  @DisplayName("fill")
  class Fill {

    @Test
    @DisplayName("should fill the first server up to the capacity, then the next")
    void fillsInOrder() {
      Balancer balancer = Balancers.fill(10);

      assertEquals("a", pick(balancer, List.of(candidate("a", 9), candidate("b", 0))));
      assertEquals("b", pick(balancer, List.of(candidate("a", 10), candidate("b", 0))));
    }

    @Test
    @DisplayName("should pick the least filled server when every one is at capacity")
    void overflow() {
      Balancer balancer = Balancers.fill(10);

      assertEquals("b", pick(balancer, List.of(candidate("a", 12), candidate("b", 10))));
    }

    @Test
    @DisplayName("should reject a capacity below 1")
    void rejectsCapacity() {
      assertThrows(IllegalArgumentException.class, () -> Balancers.fill(0));
    }
  }

  @Nested
  @DisplayName("of()")
  class Of {

    @Test
    @DisplayName("should know every strategy but fill, in any case")
    void names() {
      assertNotNull(Balancers.of("Round-Robin"));
      assertNotNull(Balancers.of("least-connections"));
      assertNotNull(Balancers.of("lowest-latency"));
      assertNotNull(Balancers.of("random"));
      assertNotNull(Balancers.of("weighted"));
      assertNull(Balancers.of("fill"));
      assertNull(Balancers.of("sticky"));
      assertTrue(Balancers.isStrategy("FILL"));
      assertEquals(6, Balancers.NAMES.size());
    }
  }
}
