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
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.i18n.PlayerMessages;

import java.time.Duration;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("The commands' messages")
class CommandMessagesTest {

  private static final PlayerMessages MESSAGES = PlayerMessages.bundled();

  private static Component render(Component message, Locale locale) {
    return MESSAGES.render(message, locale, ProtocolVersion.MINECRAFT_1_21_4);
  }

  private static String plain(Component message, Locale locale) {
    return AdminFixture.PLAIN.serialize(render(message, locale));
  }

  @Nested
  @DisplayName("in the translation system")
  class Catalog {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Message.class)
    @DisplayName("should render every message without leaving a style tag as text")
    void templates(Message message) {
      Object[] values = new Object[message.placeholders().size()];
      for (int i = 0; i < values.length; i++) {
        values[i] = message.placeholders().get(i).equals("n") ? Formatter.choice("n", 2) : "x";
      }
      for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.FRENCH}) {
        String text = plain(message.of(values), locale);
        for (String tag : new String[] {"<value>", "<good>", "<bad>", "<warn>", "<dim>", "<n"}) {
          assertFalse(text.contains(tag), message + " in " + locale + " renders " + text);
        }
      }
    }

    @Test
    @DisplayName("should refuse a message built without one of its placeholders")
    void missingPlaceholder() {
      assertThrows(
          IllegalArgumentException.class, () -> Message.GLIST_NOBODY.with(Message.count(2)));
    }
  }

  @Nested
  @DisplayName("style")
  class Style {

    @Test
    @DisplayName("should be gray and upright where a chat line sets no color, styled where it does")
    void chatLines() {
      Component plain =
          render(Message.GLIST_NOBODY.with(Message.text("server", "lobby")), Locale.ENGLISH);
      Component failure =
          render(Message.UNKNOWN_PLAYER.with(Message.text("player", "Steve")), Locale.ENGLISH);

      assertEquals(NamedTextColor.GRAY, plain.color());
      assertEquals(TextDecoration.State.FALSE, plain.decoration(TextDecoration.ITALIC));
      assertEquals(NamedTextColor.AQUA, plain.children().getFirst().color());
      assertEquals(NamedTextColor.RED, failure.color());
    }

    @Test
    @DisplayName("should leave a piece of a message the style around it")
    void parts() {
      Component reason = render(Message.SEND_REASON_DENIED.of(), Locale.ENGLISH);

      assertEquals(null, reason.color());
    }
  }

  @Nested
  @DisplayName("values")
  class Values {

    @Test
    @DisplayName("should insert typed text as it is, never as tags")
    void unparsed() {
      assertEquals(
          "No player named <red>Steve is connected.",
          plain(Message.UNKNOWN_PLAYER.with(Message.text("player", "<red>Steve")), Locale.ENGLISH));
    }

    @Test
    @DisplayName("should group thousands and agree in number, in English")
    void englishCounts() {
      assertEquals(
          "12,345 players online (click a server, or /glist <server|all>, for names)",
          plain(Message.GLIST_TOTAL.with(Message.count(12_345)), Locale.ENGLISH));
      assertEquals(
          "1 player online (click a server, or /glist <server|all>, for names)",
          plain(Message.GLIST_TOTAL.with(Message.count(1)), Locale.ENGLISH));
      assertEquals(
          "0 players online (click a server, or /glist <server|all>, for names)",
          plain(Message.GLIST_TOTAL.with(Message.count(0)), Locale.ENGLISH));
    }

    @Test
    @DisplayName("should group thousands with a plain space and agree in number, in French")
    void frenchCounts() {
      assertEquals(
          "Alerte envoyée à 12 345 joueurs.",
          plain(Message.ALERT_SENT.with(Message.count(12_345)), Locale.FRANCE));
      assertEquals(
          "Alerte envoyée à 1 joueur.",
          plain(Message.ALERT_SENT.with(Message.count(1)), Locale.FRANCE));
      assertEquals(
          "Alerte envoyée à 0 joueur.",
          plain(Message.ALERT_SENT.with(Message.count(0)), Locale.FRANCE));
    }

    @Test
    @DisplayName("should format durations for people, in their language")
    void durations() {
      assertEquals("5s", plain(CommandSupport.duration(Duration.ofSeconds(5)), Locale.ENGLISH));
      assertEquals(
          "2m 5s", plain(CommandSupport.duration(Duration.ofSeconds(125)), Locale.ENGLISH));
      assertEquals("1h", plain(CommandSupport.duration(Duration.ofHours(1)), Locale.ENGLISH));
      assertEquals("30m", plain(CommandSupport.duration(Duration.ofMinutes(30)), Locale.ENGLISH));
      assertEquals("3d 4h", plain(CommandSupport.duration(Duration.ofHours(76)), Locale.ENGLISH));
      assertEquals(
          "2 min 5 s", plain(CommandSupport.duration(Duration.ofSeconds(125)), Locale.FRENCH));
      assertEquals("3 j 4 h", plain(CommandSupport.duration(Duration.ofHours(76)), Locale.FRENCH));
    }

    @Test
    @DisplayName("should render a summary whose reasons hold a server's own reason")
    void nested() {
      Component reason =
          Message.SEND_REASON_REFUSED.with(
              Message.component("reason", Message.CONNECT_DENIED.of("survival")));
      Component group =
          Message.SEND_FAILURE_GROUP.with(
              Message.number("count", 2_000), Message.component("reason", reason));
      Component summary =
          Message.SEND_SUMMARY.with(
              Message.number("sent", 1),
              Message.number("count", 2_001),
              Message.component("target", Message.SEND_TARGET_ALL.of()),
              Message.text("server", "lobby"),
              Message.component(
                  "failures", Message.SEND_FAILURES.with(Message.component("groups", group))));

      assertEquals(
          "Sent 1 of 2,001 from every server to lobby; not sent: 2,000 refused: You cannot join"
              + " survival right now.",
          plain(summary, Locale.ENGLISH));
      assertEquals(
          "Envoyés : 1 sur 2 001, de l'ensemble des serveurs vers lobby ; non envoyés : 2 000"
              + " refus du serveur : Vous ne pouvez pas rejoindre survival pour le moment.",
          plain(summary, Locale.FRENCH));
    }
  }

  @Test
  @DisplayName("should quote a name Brigadier does not read unquoted")
  void quoting() {
    assertEquals("lobby-1", CommandSupport.quoteIfNeeded("lobby-1"));
    assertEquals("\"créatif\"", CommandSupport.quoteIfNeeded("créatif"));
    assertEquals("\"a \\\"b\\\"\"", CommandSupport.quoteIfNeeded("a \"b\""));
  }
}
