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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("/send")
class SendCommandTest {

  private final AdminFixture proxy = new AdminFixture();
  private final RegisteredServer lobby = proxy.server("lobby");
  private final RegisteredServer survival = proxy.server("survival");

  @Nested
  @DisplayName("one player")
  class OnePlayer {

    @Test
    @DisplayName("should move the player through its normal connection and say so")
    void sent() {
      Player steve = proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      assertTrue(proxy.run(console, "send Steve survival"));

      verify(steve).connect(survival);
      assertEquals(List.of("Sent Steve to survival."), console.told());
    }

    @Test
    @DisplayName("should give the server's reason when it refuses the player, who stays put")
    void refused() {
      Player steve = proxy.player("Steve", lobby);
      proxy.moves(
          steve, new ConnectionResult.Failed(survival, Component.text("The server is full")));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send Steve survival");

      assertEquals(
          "Could not send Steve to survival: refused: The server is full.", console.last());
      assertEquals(lobby, proxy.locations.get(steve));
    }

    @Test
    @DisplayName("should say when a plugin denies the move")
    void denied() {
      Player steve = proxy.player("Steve", lobby);
      proxy.moves(steve, new ConnectionResult.Denied(survival));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send Steve survival");

      assertEquals("Could not send Steve to survival: denied by a plugin.", console.last());
    }

    @Test
    @DisplayName("should not move a player already there")
    void alreadyThere() {
      Player steve = proxy.player("Steve", survival);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send steve survival");

      verify(steve, never()).connect(any());
      assertEquals("Nothing to do: 1 player is already on survival.", console.last());
    }

    @Test
    @DisplayName("should find the player whatever the case typed")
    void caseInsensitive() {
      Player steve = proxy.player("Steve", lobby);

      proxy.run(AdminFixture.console(), "send STEVE SURVIVAL");

      verify(steve).connect(survival);
    }
  }

  @Nested
  @DisplayName("several players")
  class SeveralPlayers {

    @Test
    @DisplayName("should move everyone on a server and sum up on one line")
    void server() {
      proxy.player("Alex", lobby);
      proxy.player("Steve", lobby);
      Player stays = proxy.player("Notch", survival);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send lobby survival");

      assertEquals(
          List.of(
              "Sending 2 players from lobby to survival...", "Sent 2 of 2 from lobby to survival"),
          console.told());
      assertEquals(List.of("Alex", "Notch", "Steve"), AdminFixture.names(survival.players()));
      verify(stays, never()).connect(any());
    }

    @Test
    @DisplayName("should group the players not sent by reason, their names on hover")
    void failures() {
      proxy.player("Alex", lobby);
      Player full1 = proxy.player("Full1", lobby);
      Player full2 = proxy.player("Full2", lobby);
      Player denied = proxy.player("Denied", lobby);
      Player switching = proxy.player("Busy", lobby);
      Player gone = proxy.player("Gone", lobby);
      Player down = proxy.player("Down", lobby);
      ConnectionResult full = new ConnectionResult.Failed(survival, Component.text("Full"));
      proxy.moves(full1, full);
      proxy.moves(full2, full);
      proxy.moves(denied, new ConnectionResult.Denied(survival));
      proxy.moves(switching, new ConnectionResult.InProgress(survival));
      proxy.moves(gone, new ConnectionResult.Disconnected());
      proxy.moves(down, new ConnectionResult.Failed(survival, null));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send all survival");

      assertEquals(
          "Sent 1 of 7 from every server to survival; not sent: 2 refused: Full, 1 denied by a"
              + " plugin, 1 already switching servers, 1 left the proxy, 1 server unreachable or"
              + " too slow",
          console.last());
      assertTrue(AdminFixture.hovers(console.lastComponent()).contains("Full1, Full2"));
    }

    @Test
    @DisplayName("should count the players already there without moving them")
    void someAlreadyThere() {
      proxy.player("Alex", lobby);
      Player there = proxy.player("Steve", survival);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send all survival");

      verify(there, never()).connect(any());
      assertEquals(
          "Sent 1 of 2 from every server to survival; not sent: 1 already there", console.last());
    }

    @Test
    @DisplayName("should move nobody when the destination does not answer its ping")
    void unreachable() {
      Player alex = proxy.player("Alex", lobby);
      proxy.player("Steve", lobby);
      when(survival.ping())
          .thenReturn(CompletableFuture.failedFuture(new IOException("Connection refused")));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send lobby survival");

      verify(alex, never()).connect(any());
      assertEquals(
          "survival does not answer (Connection refused): nobody was sent.", console.last());
    }

    @Test
    @DisplayName("should say so when the server named is empty")
    void empty() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send lobby survival");

      assertEquals("Nobody to send: lobby is empty.", console.last());
    }

    @Test
    @DisplayName("should report an error a move throws rather than stop the others")
    void throwing() {
      Player broken = proxy.player("Broken", lobby);
      proxy.player("Steve", lobby);
      doThrow(new IllegalArgumentException("Not a server")).when(broken).connect(any());
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send lobby survival");

      assertEquals(
          "Sent 1 of 2 from lobby to survival; not sent: 1 error: Not a server", console.last());
    }

    @Test
    @DisplayName("should keep at most 16 moves in flight, and wait for every outcome")
    void bounded() throws Exception {
      List<CompletableFuture<ConnectionResult>> pending = new CopyOnWriteArrayList<>();
      List<Player> players = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        Player player = proxy.player("P" + i, lobby);
        doAnswer(
                invocation -> {
                  CompletableFuture<ConnectionResult> move = new CompletableFuture<>();
                  pending.add(move);
                  return move;
                })
            .when(player)
            .connect(any());
        players.add(player);
      }
      AtomicReference<List<SendCommand.Outcome>> outcomes = new AtomicReference<>();
      Thread sender =
          Thread.ofVirtual().start(() -> outcomes.set(SendCommand.moveAll(players, survival)));

      waitUntil(() -> pending.size() == SendCommand.MAX_IN_FLIGHT);
      Thread.sleep(50);
      assertEquals(SendCommand.MAX_IN_FLIGHT, pending.size());
      int completed = 0;
      while (completed < players.size()) {
        waitUntil(() -> !pending.isEmpty());
        assertTrue(pending.size() <= SendCommand.MAX_IN_FLIGHT, "in flight: " + pending.size());
        CompletableFuture<ConnectionResult> next = pending.removeFirst();
        next.complete(new ConnectionResult.Connected(survival));
        completed++;
      }
      sender.join(TimeUnit.SECONDS.toMillis(10));

      List<SendCommand.Outcome> done = java.util.Objects.requireNonNull(outcomes.get());
      assertEquals(40, done.size());
      assertTrue(done.stream().allMatch(outcome -> outcome.reason() == null));
    }
  }

  @Nested
  @DisplayName("targets")
  class Targets {

    @Test
    @DisplayName("should move the sender's server with current")
    void current() {
      Player sender = proxy.player("Admin", lobby);
      when(sender.hasPermission(SendCommand.PERMISSION)).thenReturn(true);
      proxy.player("Steve", lobby);
      Player elsewhere = proxy.player("Alex", proxy.server("creative"));

      proxy.run(sender, "send current survival");

      assertEquals(List.of("Admin", "Steve"), AdminFixture.names(survival.players()));
      verify(elsewhere, never()).connect(any());
    }

    @Test
    @DisplayName("should tell the console it has no current server")
    void currentFromConsole() {
      proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send current survival");

      assertEquals("current means your server, and you are on none.", console.last());
    }

    @Test
    @DisplayName("should refuse a name that is both a player and a server, moving nobody")
    void ambiguous() {
      Player named = proxy.player("lobby", survival);
      Player onLobby = proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send lobby survival");

      assertTrue(console.last().startsWith("lobby is both a player and a server"), console.last());
      verify(named, never()).connect(any());
      verify(onLobby, never()).connect(any());
    }

    @Test
    @DisplayName("should take player and server to say which a name is")
    void explicit() {
      RegisteredServer creative = proxy.server("creative");
      Player named = proxy.player("lobby", survival);
      Player onLobby = proxy.player("Steve", lobby);

      proxy.run(AdminFixture.console(), "send player lobby creative");
      proxy.run(AdminFixture.console(), "send server lobby creative");

      verify(named).connect(creative);
      verify(onLobby).connect(creative);
    }

    @Test
    @DisplayName("should name what it does not know")
    void unknown() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send nobody survival");
      proxy.run(console, "send all nowhere");
      proxy.run(console, "send player nobody survival");

      assertEquals(
          List.of(
              "No player or server is named nobody.",
              "Unknown server: nowhere. Type /server to list the servers.",
              "No player named nobody is connected."),
          console.told());
    }

    @Test
    @DisplayName("should read a quoted server name with characters a word cannot hold")
    void quoted() {
      RegisteredServer creative = proxy.server("créatif");
      Player steve = proxy.player("Steve", lobby);

      proxy.run(AdminFixture.console(), "send Steve \"créatif\"");

      verify(steve).connect(creative);
    }
  }

  @Nested
  @DisplayName("permissions and suggestions")
  class Permissions {

    @Test
    @DisplayName("should not be the proxy's for a source without warp.command.send")
    void denied() {
      Player steve = proxy.player("Steve", lobby);
      AdminFixture.Source source = AdminFixture.granted("warp.command.glist");

      assertFalse(proxy.commands.owns(source, "send Steve survival"));
      assertFalse(proxy.run(source, "send Steve survival"));

      verify(steve, never()).connect(any());
    }

    @Test
    @DisplayName("should suggest keywords, players and servers, then destinations")
    void suggestions() {
      proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      List<String> targets = proxy.suggest(console, "send ");
      List<String> destinations = proxy.suggest(console, "send Steve s");

      assertTrue(
          targets.containsAll(List.of("all", "current", "player", "server", "Steve", "lobby")),
          targets.toString());
      assertEquals(List.of("survival"), destinations);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out");
      }
      Thread.sleep(1);
    }
  }
}
