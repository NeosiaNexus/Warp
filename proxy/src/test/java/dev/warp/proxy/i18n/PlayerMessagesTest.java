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
package dev.warp.proxy.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Player messages")
class PlayerMessagesTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion LEGACY = ProtocolVersion.MINECRAFT_1_8;

  private static String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private static String render(PlayerMessages messages, Component message, Locale locale) {
    return plain(messages.render(message, locale, MODERN));
  }

  @Nested
  @DisplayName("languages")
  class Languages {

    private final PlayerMessages messages = PlayerMessages.bundled();

    @Test
    @DisplayName("should render a message in the player's language")
    void french() {
      assertEquals(
          "Pour le moment, ce serveur n'accepte que les joueurs qui y ont déjà joué. Veuillez"
              + " réessayer plus tard.",
          render(messages, Message.LOCKDOWN.of(), Locale.FRANCE));
      assertTrue(render(messages, Message.LOCKDOWN.of(), Locale.US).startsWith("This server only"));
    }

    @Test
    @DisplayName("should fall back from a country's variant to its language (fr_CA to fr)")
    void variant() {
      assertEquals(
          render(messages, Message.LOCKDOWN.of(), Locale.FRENCH),
          render(messages, Message.LOCKDOWN.of(), Locale.CANADA_FRENCH));
    }

    @Test
    @DisplayName("should fall back to English for a language Warp has no translation for")
    void missingLanguage() {
      assertEquals(
          render(messages, Message.LOCKDOWN.of(), Locale.ENGLISH),
          render(messages, Message.LOCKDOWN.of(), Locale.GERMANY));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Message.class)
    @DisplayName("should never show a raw key, in any language, on any client")
    void neverRawKey(Message message) {
      Object[] values = new Object[message.placeholders().size()];
      java.util.Arrays.fill(values, "x");
      for (Locale locale : new Locale[] {Locale.US, Locale.FRANCE, Locale.JAPAN}) {
        String text = plain(messages.render(message.of(values), locale, LEGACY));
        assertFalse(text.isBlank(), message + " in " + locale);
        assertFalse(text.contains(message.key()), message + " in " + locale + ": " + text);
      }
    }

    @Test
    @DisplayName(
        "should leave a vanilla key to a client that translates it, and render it for one too old")
    void vanillaKeys() {
      Component modern = messages.render(Message.SERVER_FULL.of(), Locale.FRANCE, MODERN);
      Component legacy = messages.render(Message.SERVER_FULL.of(), Locale.FRANCE, LEGACY);

      assertEquals(
          "multiplayer.disconnect.server_full",
          assertInstanceOf(TranslatableComponent.class, modern).key());
      assertEquals("Le serveur est complet.", plain(legacy));
    }

    @Test
    @DisplayName("should render a 1.12 key for a 1.12 client, which knows it, only before 1.12")
    void vanillaKeyBoundary() {
      assertInstanceOf(
          TranslatableComponent.class,
          messages.render(
              Message.DUPLICATE_LOGIN.of(), Locale.FRANCE, ProtocolVersion.MINECRAFT_1_12));
      assertEquals(
          "Vous vous êtes connecté depuis un autre emplacement.",
          plain(
              messages.render(
                  Message.DUPLICATE_LOGIN.of(), Locale.FRANCE, ProtocolVersion.MINECRAFT_1_11)));
    }

    @Test
    @DisplayName("should fill placeholders, apostrophes included, and render a message inside one")
    void placeholders() {
      Component reason = Message.SERVER_FULL.of();
      String text =
          plain(
              messages.render(
                  Message.CONNECT_FAILED_REASON.of("survie", reason), Locale.FRANCE, LEGACY));

      assertEquals("Impossible de se connecter à survie : Le serveur est complet.", text);
    }

    @Test
    @DisplayName("should insert a typed value as text, never as MiniMessage")
    void typedValuesUnparsed() {
      assertEquals(
          "Unknown server: <red>x. Type /server to list the servers.",
          render(messages, Message.UNKNOWN_SERVER.of("<red>x"), Locale.US));
    }

    @Test
    @DisplayName("should color failures with the commands' <bad> style")
    void styles() {
      Component rendered = messages.render(Message.CONNECT_FAILED.of("lobby"), Locale.US, MODERN);
      assertEquals(NamedTextColor.RED, rendered.color());
    }

    @Test
    @DisplayName("should refuse a message given the wrong number of values")
    void wrongArity() {
      assertThrows(IllegalArgumentException.class, () -> Message.CONNECT_FAILED.of());
    }
  }

  @Nested
  @DisplayName("remembered languages")
  class Remembered {

    @Test
    @DisplayName("should use the language a player last played in, whatever the case of its name")
    void remembered() {
      PlayerMessages messages = PlayerMessages.bundled();
      messages.remember("Steve", Locale.FRANCE);

      assertEquals(Locale.FRANCE, messages.localeOf("steve"));
    }

    @Test
    @DisplayName("should use the configured default for a player never seen, or not named yet")
    void defaultLocale() {
      PlayerMessages messages = new PlayerMessages(Map.of(), Locale.GERMANY);

      assertEquals(Locale.GERMANY, messages.localeOf("Alex"));
      assertEquals(Locale.GERMANY, messages.localeOf(null));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"en_us, en_US", "fr_FR, fr_FR", "pt-br, pt_BR", "de, de", "es_419, es_419"})
    @DisplayName("should read the languages clients send and operators write")
    void parse(String text, String expected) {
      Locale locale = PlayerMessages.parseLocale(text);
      assertEquals(expected, String.valueOf(locale));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource({"''", "english", "e", "fr_FRA_x", "12"})
    @DisplayName("should refuse what is not a language")
    void parseInvalid(String text) {
      assertNull(PlayerMessages.parseLocale(text));
    }
  }

  @Nested
  @DisplayName("overrides")
  class Overrides {

    @Test
    @DisplayName(
        "should prefer the player's language file, then the every-language one, then Warp's")
    void precedence() {
      PlayerMessages messages =
          new PlayerMessages(
              Map.of(
                  Locale.ROOT, Map.of(Message.LOCKDOWN.key(), "<gold>Everyone"),
                  Locale.FRENCH, Map.of(Message.LOCKDOWN.key(), "Les Français")),
              Locale.US);

      assertEquals("Les Français", render(messages, Message.LOCKDOWN.of(), Locale.CANADA_FRENCH));
      assertEquals("Everyone", render(messages, Message.LOCKDOWN.of(), Locale.US));
      assertInstanceOf(
          TranslatableComponent.class, messages.render(Message.SLOW_LOGIN.of(), Locale.US, MODERN));
    }

    @Test
    @DisplayName("should render an overridden vanilla message even for a client that knows the key")
    void vanillaOverride() {
      PlayerMessages messages =
          new PlayerMessages(
              Map.of(Locale.ROOT, Map.of(Message.SERVER_FULL.key(), "Full, see discord.gg/x")),
              Locale.US);

      assertEquals(
          "Full, see discord.gg/x", render(messages, Message.SERVER_FULL.of(), Locale.FRANCE));
    }

    @Test
    @DisplayName("should log in English what the player was told, vanilla messages included")
    void plainEnglish() {
      assertEquals(
          "The server is full.", PlayerMessages.bundled().plainEnglish(Message.SERVER_FULL.of()));
    }
  }

  @Nested
  @DisplayName("bundled translations")
  class Bundled {

    private static final Pattern TAG = Pattern.compile("<([a-z][a-z0-9_-]*)");

    @ParameterizedTest(name = "{0}")
    @CsvSource({"en", "fr"})
    @DisplayName("should translate every message, and nothing else")
    void complete(String language) {
      Locale locale = Locale.of(language);
      assertEquals(Message.keys(), new HashSet<>(PlayerMessages.bundledKeys(locale)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"en", "fr"})
    @DisplayName("should keep each message's placeholders, and pass the operators' own checks")
    void placeholders(String language) {
      Locale locale = Locale.of(language);
      for (Message message : Message.values()) {
        String text = PlayerMessages.bundledText(locale, message.key());
        assertEquals(null, MessageOverrides.check(message.key(), String.valueOf(text)), language);
        Set<String> used = new HashSet<>();
        Matcher tags = TAG.matcher(String.valueOf(text));
        while (tags.find()) {
          if (message.placeholders().contains(tags.group(1))) {
            used.add(tags.group(1));
          }
        }
        assertEquals(Set.copyOf(message.placeholders()), used, message + " in " + language);
      }
    }

    @Test
    @DisplayName("should write French with plain spaces, never a non-breaking one (Velocity #595)")
    void plainSpaces() {
      for (Message message : Message.values()) {
        String text = String.valueOf(PlayerMessages.bundledText(Locale.FRENCH, message.key()));
        assertFalse(text.contains(" ") || text.contains(" "), message.key());
      }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"en", "fr"})
    @DisplayName("should never write an em dash")
    void noEmDash(String language) {
      for (Message message : Message.values()) {
        String text =
            String.valueOf(PlayerMessages.bundledText(Locale.of(language), message.key()));
        assertFalse(text.indexOf(0x2014) >= 0, message.key() + " in " + language);
      }
    }
  }
}
