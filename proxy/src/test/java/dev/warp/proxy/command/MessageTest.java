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

import java.util.HashSet;
import java.util.Set;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Message")
class MessageTest {

  @Test
  @DisplayName("should give every message a key of its own under warp.command")
  void keys() {
    Set<String> keys = new HashSet<>();
    for (Message message : Message.values()) {
      assertTrue(message.key().startsWith("warp.command."), message.key());
      assertTrue(keys.add(message.key()), "duplicate key " + message.key());
    }
  }

  @Test
  @DisplayName("should render every template without leaving a style tag as text")
  void templates() {
    for (Message message : Message.values()) {
      String text = AdminFixture.PLAIN.serialize(message.render(Message.count(2)));
      for (String tag : new String[] {"<value>", "<good>", "<bad>", "<warn>", "<dim>"}) {
        assertFalse(text.contains(tag), message + " renders " + text);
      }
      assertFalse(message.template().indexOf(0x2014) >= 0, message + " has an em dash");
    }
  }

  @Test
  @DisplayName("should be gray where the template sets no color, styled where it does")
  void styles() {
    Component plain = Message.GLIST_NOBODY.render(Message.text("server", "lobby"));
    Component failure = Message.UNKNOWN_SERVER.render(Message.text("server", "lobby"));

    assertEquals(NamedTextColor.GRAY, plain.color());
    assertEquals(NamedTextColor.AQUA, plain.children().getFirst().color());
    assertEquals(NamedTextColor.RED, failure.color());
  }

  @Test
  @DisplayName("should insert typed text as it is, never as tags")
  void unparsed() {
    Component rendered = Message.UNKNOWN_PLAYER.render(Message.text("player", "<red>Steve"));

    assertEquals(
        "No player named <red>Steve is connected.", AdminFixture.PLAIN.serialize(rendered));
  }

  @Test
  @DisplayName("should group thousands and agree in number")
  void counts() {
    assertEquals(
        "12,345 players online (click a server, or /glist <server|all>, for names)",
        AdminFixture.PLAIN.serialize(Message.GLIST_TOTAL.render(Message.count(12_345))));
    assertTrue(
        AdminFixture.PLAIN
            .serialize(Message.GLIST_TOTAL.render(Message.count(1)))
            .startsWith("1 player online"));
    assertTrue(
        AdminFixture.PLAIN
            .serialize(Message.GLIST_TOTAL.render(Message.count(0)))
            .startsWith("0 players online"));
  }

  @Test
  @DisplayName("should format durations for people")
  void durations() {
    assertEquals("5s", CommandSupport.duration(java.time.Duration.ofSeconds(5)));
    assertEquals("2m 5s", CommandSupport.duration(java.time.Duration.ofSeconds(125)));
    assertEquals("1h 0m", CommandSupport.duration(java.time.Duration.ofHours(1)));
    assertEquals("3d 4h", CommandSupport.duration(java.time.Duration.ofHours(76)));
  }

  @Test
  @DisplayName("should quote a name Brigadier does not read unquoted")
  void quoting() {
    assertEquals("lobby-1", CommandSupport.quoteIfNeeded("lobby-1"));
    assertEquals("\"créatif\"", CommandSupport.quoteIfNeeded("créatif"));
    assertEquals("\"a \\\"b\\\"\"", CommandSupport.quoteIfNeeded("a \"b\""));
  }
}
