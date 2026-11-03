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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.config.ConfigurationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslator;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.jspecify.annotations.Nullable;

/**
 * Renders what Warp tells players in each player's language.
 *
 * <p>A {@link Message} is looked up, for a player whose game is in Canadian French ({@code fr_CA}):
 * in the operator's {@code messages/messages_fr_CA.properties}, then {@code
 * messages_fr.properties}, then {@code messages.properties} (every language), then in Warp's
 * bundled French, then in Warp's English, which has every message: a player never sees a raw key. A
 * vanilla message is left for the client to translate unless the operator overrides it, or the
 * client is too old to know it.
 *
 * <p>A player's language comes from its client settings. Before they arrive, at login, Warp uses
 * the language the same player last played in, remembered for 30 days in memory, or else the
 * configured default.
 *
 * <p>Thread-safe: immutable once loaded, but for the remembered languages, a concurrent cache.
 */
public final class PlayerMessages {

  /** The languages Warp ships translations for; English must be first. */
  public static final List<Locale> BUNDLED = List.of(Locale.ENGLISH, Locale.FRENCH);

  /** The language of players whose language is not known, unless configured otherwise. */
  public static final Locale DEFAULT_LOCALE = Locale.US;

  /** Most players whose language Warp remembers. */
  private static final int REMEMBERED_PLAYERS = 100_000;

  /** How long Warp remembers the language of a player who does not come back. */
  private static final Duration REMEMBERED_FOR = Duration.ofDays(30);

  private static final Pattern LOCALE =
      Pattern.compile("([a-zA-Z]{2,3})(?:[_-]([a-zA-Z]{2}|\\d{3}))?");

  /**
   * The style tags every message may use, the same as the commands' (PR #163): {@code <value>} for
   * names and numbers, {@code <good>} for success, {@code <bad>} for failures, {@code <warn>} for
   * what needs attention, {@code <dim>} for hints. An override keeps the theme by using them.
   */
  public static final TagResolver STYLES =
      TagResolver.resolver(
          TagResolver.resolver("value", Tag.styling(NamedTextColor.AQUA)),
          TagResolver.resolver("good", Tag.styling(NamedTextColor.GREEN)),
          TagResolver.resolver("bad", Tag.styling(NamedTextColor.RED)),
          TagResolver.resolver("warn", Tag.styling(NamedTextColor.GOLD)),
          TagResolver.resolver("dim", Tag.styling(NamedTextColor.DARK_GRAY)));

  /** MiniMessage with the standard tags and {@link #STYLES}. */
  static final MiniMessage MINI_MESSAGE =
      MiniMessage.builder().tags(TagResolver.resolver(TagResolver.standard(), STYLES)).build();

  private static final Map<Locale, Map<String, String>> BUNDLED_TEXTS = loadBundled();

  private final Map<Locale, Map<String, String>> overrides;
  private final Locale defaultLocale;
  private final Cache<String, Locale> remembered;
  private final WarpTranslator translator = new WarpTranslator();
  private final TranslatableComponentRenderer<Locale> renderer =
      TranslatableComponentRenderer.usingTranslationSource(translator);

  /**
   * Creates the messages from the operator's overrides.
   *
   * @param overrides the overriding texts by language, {@link Locale#ROOT} for every language
   * @param defaultLocale the language of players whose language is not known
   */
  PlayerMessages(Map<Locale, Map<String, String>> overrides, Locale defaultLocale) {
    Map<Locale, Map<String, String>> copy = new HashMap<>();
    overrides.forEach((locale, texts) -> copy.put(locale, Map.copyOf(texts)));
    this.overrides = Map.copyOf(copy);
    this.defaultLocale = defaultLocale;
    this.remembered =
        Caffeine.newBuilder()
            .maximumSize(REMEMBERED_PLAYERS)
            .expireAfterAccess(REMEMBERED_FOR)
            .build();
  }

  /**
   * Returns Warp's own messages, without overrides, for players of unknown language in American
   * English.
   *
   * @return the messages
   */
  public static PlayerMessages bundled() {
    return new PlayerMessages(Map.of(), DEFAULT_LOCALE);
  }

  /**
   * Loads the operator's overrides from {@code messages} in the data directory, writing a commented
   * template there on first start.
   *
   * @param directory the proxy's data directory
   * @param defaultLocale the language of players whose language is not known
   * @return the messages
   * @throws ConfigurationException if an override file cannot be read or is invalid
   */
  public static PlayerMessages load(Path directory, Locale defaultLocale)
      throws ConfigurationException {
    return new PlayerMessages(
        MessageOverrides.load(directory.resolve(MessageOverrides.DIRECTORY)), defaultLocale);
  }

  // ---------------------------------------------------------------------------
  // Languages
  // ---------------------------------------------------------------------------

  /**
   * Parses a language as clients send it ({@code en_us}, {@code pt_BR}) or as an operator writes it
   * ({@code fr-FR}, {@code fr}).
   *
   * @param text the language
   * @return the locale, or {@code null} if it is not a language
   */
  public static @Nullable Locale parseLocale(String text) {
    var matcher = LOCALE.matcher(text.strip());
    if (!matcher.matches()) {
      return null;
    }
    try {
      Locale.Builder builder = new Locale.Builder().setLanguage(matcher.group(1));
      String region = matcher.group(2);
      if (region != null) {
        builder.setRegion(region);
      }
      return builder.build();
    } catch (IllformedLocaleException e) {
      return null;
    }
  }

  /**
   * Returns the language of players whose language is not known.
   *
   * @return the default language
   */
  public Locale defaultLocale() {
    return defaultLocale;
  }

  /**
   * Remembers the language a player's client announced, for its next logins.
   *
   * @param username the player's name
   * @param locale its language
   */
  public void remember(String username, Locale locale) {
    remembered.put(username.toLowerCase(Locale.ROOT), locale);
  }

  /**
   * Returns the language to talk to a player in before its client says: the one it last played in,
   * else the default.
   *
   * @param username the player's name, or {@code null} before the client sent it
   * @return the language
   */
  public Locale localeOf(@Nullable String username) {
    if (username == null) {
      return defaultLocale;
    }
    Locale locale = remembered.getIfPresent(username.toLowerCase(Locale.ROOT));
    return locale != null ? locale : defaultLocale;
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  /**
   * Renders a component for a player: Warp's messages in its language, then the translations
   * plugins registered with {@link GlobalTranslator}, then the vanilla messages its client is too
   * old to translate itself. Arguments are rendered too.
   *
   * @param component the component, which may hold messages anywhere
   * @param locale the player's language
   * @param version the player's protocol version
   * @return the rendered component
   */
  public Component render(Component component, Locale locale, ProtocolVersion version) {
    Component rendered = component;
    // A message's arguments may be messages themselves: a reason inside "could not connect".
    for (int pass = 0; pass < 3; pass++) {
      Component next = GlobalTranslator.render(renderer.render(rendered, locale), locale);
      if (next.equals(rendered)) {
        break;
      }
      rendered = next;
    }
    if (version.isOlderThan(Message.NEWEST_VANILLA)) {
      rendered =
          TranslatableComponentRenderer.usingTranslationSource(new LegacyTranslator(version))
              .render(rendered, locale);
    }
    return rendered;
  }

  /**
   * Renders a component in English with Warp's own texts, vanilla messages included: for a reader
   * that renders nothing itself, such as a console.
   *
   * @param component the component
   * @return it rendered
   */
  public static Component english(Component component) {
    return ENGLISH.render(component, Locale.ENGLISH, ProtocolVersion.oldest());
  }

  private static final PlayerMessages ENGLISH = bundled();

  /**
   * Renders a component in English as plain text, vanilla messages included: what the log says a
   * player was told.
   *
   * @param component the component
   * @return its text
   */
  public String plainEnglish(Component component) {
    return PlainTextComponentSerializer.plainText()
        .serialize(render(component, Locale.ENGLISH, ProtocolVersion.oldest()));
  }

  /**
   * Returns the MiniMessage text of a message in a language, through the lookup order described
   * above.
   *
   * @param key the message's key
   * @param locale the language
   * @param vanillaToo whether a vanilla message gets Warp's text too, for a client too old for it
   * @return the text, or {@code null} for a key that is not Warp's, or a vanilla message the client
   *     translates
   */
  @Nullable String text(String key, Locale locale, boolean vanillaToo) {
    Message message = Message.byKey(key);
    if (message == null) {
      return null;
    }
    List<Locale> candidates = candidates(locale);
    for (Locale candidate : candidates) {
      String text = lookup(overrides, candidate, key);
      if (text != null) {
        return text;
      }
    }
    String everyLanguage = lookup(overrides, Locale.ROOT, key);
    if (everyLanguage != null) {
      return everyLanguage;
    }
    if (message.vanillaSince() != null && !vanillaToo) {
      return null;
    }
    for (Locale candidate : candidates) {
      String text = lookup(BUNDLED_TEXTS, candidate, key);
      if (text != null) {
        return text;
      }
    }
    return lookup(BUNDLED_TEXTS, Locale.ENGLISH, key);
  }

  /**
   * Returns Warp's bundled text of a message in a language, without falling back.
   *
   * @param locale one of {@link #BUNDLED}
   * @param key the message's key
   * @return the text, or {@code null} if that language has none
   */
  static @Nullable String bundledText(Locale locale, String key) {
    return lookup(BUNDLED_TEXTS, locale, key);
  }

  /**
   * Returns the keys of Warp's bundled texts in a language.
   *
   * @param locale one of {@link #BUNDLED}
   * @return the keys, in no order
   */
  static List<String> bundledKeys(Locale locale) {
    Map<String, String> texts = BUNDLED_TEXTS.get(locale);
    return texts == null ? List.of() : List.copyOf(texts.keySet());
  }

  private static @Nullable String lookup(
      Map<Locale, Map<String, String>> texts, Locale locale, String key) {
    Map<String, String> language = texts.get(locale);
    return language == null ? null : language.get(key);
  }

  /** The language itself, then without its country. */
  private static List<Locale> candidates(Locale locale) {
    List<Locale> candidates = new ArrayList<>(2);
    Locale language = Locale.of(locale.getLanguage());
    candidates.add(locale);
    if (!language.equals(locale)) {
      candidates.add(language);
    }
    return candidates;
  }

  private static Map<Locale, Map<String, String>> loadBundled() {
    Map<Locale, Map<String, String>> texts = new LinkedHashMap<>();
    for (Locale locale : BUNDLED) {
      String resource = "messages_" + locale.toLanguageTag().replace('-', '_') + ".properties";
      try (InputStream in = PlayerMessages.class.getResourceAsStream(resource)) {
        if (in == null) {
          throw new IllegalStateException("Warp's " + resource + " is missing from its jar");
        }
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
          properties.load(reader);
        }
        Map<String, String> language = new HashMap<>();
        properties.forEach((key, value) -> language.put((String) key, (String) value));
        texts.put(locale, Map.copyOf(language));
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read Warp's " + resource, e);
      }
    }
    return Map.copyOf(texts);
  }

  /** Warp's messages, and the operator's overrides of vanilla ones. */
  private final class WarpTranslator extends MiniMessageTranslator {

    WarpTranslator() {
      super(MINI_MESSAGE);
    }

    @Override
    public Key name() {
      return Key.key("warp", "messages");
    }

    @Override
    protected @Nullable String getMiniMessageString(String key, Locale locale) {
      return text(key, locale, false);
    }
  }

  /** The vanilla messages a client is too old to translate itself. */
  private final class LegacyTranslator extends MiniMessageTranslator {

    private final ProtocolVersion version;

    LegacyTranslator(ProtocolVersion version) {
      super(MINI_MESSAGE);
      this.version = version;
    }

    @Override
    public Key name() {
      return Key.key("warp", "legacy-messages");
    }

    @Override
    protected @Nullable String getMiniMessageString(String key, Locale locale) {
      Message message = Message.byKey(key);
      ProtocolVersion since = message == null ? null : message.vanillaSince();
      return since != null && version.isOlderThan(since) ? text(key, locale, true) : null;
    }
  }
}
