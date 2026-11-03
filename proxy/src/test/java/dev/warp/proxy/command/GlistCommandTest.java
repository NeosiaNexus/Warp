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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.server.RegisteredServer;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("/glist")
class GlistCommandTest {

  private final AdminFixture proxy = new AdminFixture();
  private final RegisteredServer lobby = proxy.server("lobby");
  private final RegisteredServer survival = proxy.server("survival");

  @Nested
  @DisplayName("counts")
  class Counts {

    @Test
    @DisplayName("should give the total, then each server with players, the empty ones together")
    void overview() {
      proxy.server("creative");
      proxy.server("events");
      proxy.player("Steve", lobby);
      proxy.player("Alex", lobby);
      proxy.player("Notch", survival);
      proxy.player("Joining", null);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist");

      assertEquals(
          """
          4 players online (click a server, or /glist <server|all>, for names)
          - lobby 2
          - survival 1
          - empty: creative, events
          - not on a server yet: 1\
          """,
          console.last());
    }

    @Test
    @DisplayName("should make each server a link to its names")
    void links() {
      proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist");

      ClickEvent<?> click = clickOf(console.lastComponent(), "lobby");
      assertNotNull(click);
      assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
      assertEquals("/glist lobby", ((ClickEvent.Payload.Text) click.payload()).value());
    }

    @Test
    @DisplayName("should agree in number with a single player")
    void singular() {
      proxy.player("Steve", lobby);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist");

      assertTrue(console.last().startsWith("1 player online"), console.last());
    }
  }

  @Nested
  @DisplayName("names")
  class Names {

    @Test
    @DisplayName("should list a server's players alphabetically")
    void server() {
      proxy.player("steve", lobby);
      proxy.player("Alex", lobby);
      proxy.player("Notch", survival);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist LOBBY");

      assertEquals("lobby · 2 players\nAlex, steve", console.last());
    }

    @Test
    @DisplayName("should cut a crowd into pages with links between them")
    void pages() {
      for (int i = 0; i < 130; i++) {
        proxy.player(String.format("P%03d", i), lobby);
      }
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist lobby");
      String first = console.last();
      Component firstPage = console.lastComponent();
      proxy.run(console, "glist lobby 3");
      String last = console.last();
      proxy.run(console, "glist lobby 4");

      assertTrue(first.contains("P000, P001"), first);
      assertTrue(first.contains("P059") && !first.contains("P060"), first);
      assertTrue(first.endsWith("page 1/3 next »"), first);
      ClickEvent<?> next = clickOf(firstPage, "next »");
      assertNotNull(next);
      assertEquals("/glist lobby 2", ((ClickEvent.Payload.Text) next.payload()).value());
      assertTrue(last.contains("P129") && last.endsWith("« previous page 3/3"), last);
      assertEquals("There is no page 4: 3 in all.", console.last());
    }

    @Test
    @DisplayName("should list every server's players with all, grouped by server")
    void all() {
      proxy.player("Steve", lobby);
      proxy.player("Notch", survival);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist all");

      assertEquals("All servers · 2 players\nlobby: Steve\nsurvival: Notch", console.last());
    }

    @Test
    @DisplayName("should say when a server is empty, or unknown")
    void emptyOrUnknown() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist lobby");
      proxy.run(console, "glist all");
      proxy.run(console, "glist nowhere");

      assertEquals(
          List.of("Nobody is on lobby.", "Nobody is online.", "Unknown server: nowhere"),
          console.told());
    }
  }

  @Nested
  @DisplayName("permissions and suggestions")
  class Permissions {

    @Test
    @DisplayName("should not be the proxy's for a source without warp.command.glist")
    void denied() {
      AdminFixture.Source source = AdminFixture.granted("warp.command.send");

      assertFalse(proxy.run(source, "glist"));
      assertTrue(source.received.isEmpty());
    }

    @Test
    @DisplayName("should work for a source granted warp.command.glist alone")
    void granted() {
      AdminFixture.Source source = AdminFixture.granted(GlistCommand.PERMISSION);

      assertTrue(proxy.run(source, "glist"));
      assertTrue(source.last().startsWith("0 players online"), source.last());
    }

    @Test
    @DisplayName("should suggest all and the servers")
    void suggestions() {
      assertEquals(
          List.of("all", "lobby", "survival"), proxy.suggest(AdminFixture.console(), "glist "));
    }
  }

  /** The click event of the first component reading {@code text}, or {@code null}. */
  static @Nullable ClickEvent<?> clickOf(Component component, String text) {
    ClickEvent<?> click = component.clickEvent();
    if (click != null && AdminFixture.PLAIN.serialize(component).equals(text)) {
      return click;
    }
    for (Component child : component.children()) {
      ClickEvent<?> found = clickOf(child, text);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
