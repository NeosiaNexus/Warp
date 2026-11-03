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

import dev.warp.proxy.config.ConfigurationException;
import dev.warp.proxy.config.ConfigurationException.Problem;
import dev.warp.proxy.config.Suggestions;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

/**
 * Reads the operator's message overrides: {@code messages/messages.properties} for every language,
 * {@code messages/messages_<language>.properties} for one. Every problem is reported at once, with
 * its file and line, and Warp refuses to start on any: an override that silently does nothing is
 * worse than none.
 */
final class MessageOverrides {

  /** The directory of the overrides, in the data directory. */
  static final String DIRECTORY = "messages";

  /** The file whose overrides apply to every language. */
  static final String EVERY_LANGUAGE = "messages.properties";

  private static final Pattern FILE_NAME = Pattern.compile("messages(?:_(.+))?\\.properties");

  /** The key of a {@code key = value} line, as {@link Properties} reads it. */
  private static final Pattern KEY_LINE = Pattern.compile("^\\s*([^#!\\s=:][^\\s=:]*)");

  /** The placeholders of a vanilla message, which takes its arguments in order. */
  private static final Set<String> VANILLA_PLACEHOLDERS = Set.of("arg", "argument");

  /** The tags every message may use: MiniMessage's own and the shared styles. */
  private static final TagResolver STANDARD =
      TagResolver.resolver(TagResolver.standard(), PlayerMessages.STYLES);

  private MessageOverrides() {}

  /**
   * Reads the overrides of a directory, writing the commented template there if it does not exist.
   *
   * @param directory the overrides' directory
   * @return the overriding texts by language, {@link Locale#ROOT} for every language
   * @throws ConfigurationException if the directory or a file cannot be read, or is invalid
   */
  static Map<Locale, Map<String, String>> load(Path directory) throws ConfigurationException {
    if (!Files.exists(directory)) {
      writeTemplate(directory);
      return Map.of();
    }
    if (!Files.isDirectory(directory)) {
      throw problem(
          directory,
          "is a file, not a directory: Warp reads its message overrides from a directory of that"
              + " name. Move the file away.");
    }
    List<Path> files;
    try (Stream<Path> listed = Files.list(directory)) {
      files =
          listed
              .filter(file -> file.getFileName().toString().endsWith(".properties"))
              .sorted()
              .toList();
    } catch (IOException e) {
      throw problem(directory, "cannot be read (" + e + "): give the user running Warp access");
    }
    List<Problem> problems = new ArrayList<>();
    Map<Locale, Map<String, String>> overrides = new HashMap<>();
    for (Path file : files) {
      Matcher name = FILE_NAME.matcher(file.getFileName().toString());
      if (!name.matches()) {
        problems.add(
            new Problem(
                file,
                0,
                null,
                "not a messages file: name it messages.properties (every language) or"
                    + " messages_<language>.properties, such as messages_fr.properties"));
        continue;
      }
      String language = name.group(1);
      Locale locale = language == null ? Locale.ROOT : PlayerMessages.parseLocale(language);
      if (locale == null) {
        problems.add(
            new Problem(
                file,
                0,
                null,
                "'"
                    + language
                    + "' is not a language: use a code such as fr, de or pt_BR, as in"
                    + " messages_fr.properties"));
        continue;
      }
      overrides.put(locale, read(file, problems));
    }
    if (!problems.isEmpty()) {
      throw ConfigurationException.of(problems);
    }
    return overrides;
  }

  /** Reads and checks one file, adding what is wrong with it to {@code problems}. */
  private static Map<String, String> read(Path file, List<Problem> problems) {
    String content;
    try {
      content =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(Files.readAllBytes(file)))
              .toString();
    } catch (CharacterCodingException e) {
      problems.add(new Problem(file, 0, null, "is not UTF-8 text: save it as UTF-8"));
      return Map.of();
    } catch (IOException e) {
      problems.add(
          new Problem(
              file, 0, null, "cannot be read (" + e + "): give the user running Warp access"));
      return Map.of();
    }
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(content));
    } catch (IOException | IllegalArgumentException e) {
      problems.add(
          new Problem(file, 0, null, "cannot be read as a properties file: " + e.getMessage()));
      return Map.of();
    }

    Map<String, Integer> lines = new HashMap<>();
    String[] rows = content.split("\\R", -1);
    boolean continued = false;
    for (int i = 0; i < rows.length; i++) {
      String row = rows[i];
      if (!continued) {
        Matcher key = KEY_LINE.matcher(row);
        if (key.find()) {
          Integer previous = lines.putIfAbsent(key.group(1), i + 1);
          if (previous != null) {
            problems.add(
                new Problem(
                    file,
                    i + 1,
                    key.group(1),
                    key.group(1)
                        + " is set twice, on lines "
                        + previous
                        + " and "
                        + (i + 1)
                        + ": keep one"));
          }
        }
      }
      continued = endsWithContinuation(row) && !row.stripLeading().startsWith("#");
    }

    Map<String, String> texts = new HashMap<>();
    for (String key : new TreeMap<>(stringMap(properties)).keySet()) {
      String text = properties.getProperty(key);
      int line = lines.getOrDefault(key, 0);
      String problem = check(key, text);
      if (problem != null) {
        problems.add(new Problem(file, line, key, problem));
      } else {
        texts.put(key, text);
      }
    }
    return texts;
  }

  /**
   * Checks one override.
   *
   * @return what is wrong with it, or {@code null} if nothing
   */
  static @Nullable String check(String key, String text) {
    Message message = Message.byKey(key);
    if (message == null) {
      String suggestion = Suggestions.closest(key, Message.keys());
      return key
          + " is not a message of Warp."
          + (suggestion != null
              ? " Did you mean " + suggestion + "?"
              : " Every message is listed in the template Warp writes to an empty messages"
                  + " directory.");
    }
    if (text.isBlank() && message.kind() == Message.Kind.DISCONNECT) {
      return key + ": a disconnect reason cannot be empty. Delete the line to keep Warp's message.";
    }
    Set<String> allowed =
        message.vanillaSince() != null ? VANILLA_PLACEHOLDERS : Set.copyOf(message.placeholders());
    List<String> unknown = unknownPlaceholders(text, allowed);
    if (!unknown.isEmpty()) {
      return key
          + ": unknown placeholder "
          + unknown.stream().map(name -> "<" + name + ">").collect(Collectors.joining(", "))
          + ". "
          + describePlaceholders(message);
    }
    return null;
  }

  private static String describePlaceholders(Message message) {
    if (message.vanillaSince() != null) {
      return "This vanilla message takes its arguments in order: <arg:0>, <arg:1>...";
    }
    if (message.placeholders().isEmpty()) {
      return "This message has no placeholders.";
    }
    return "This message has "
        + message.placeholders().stream()
            .map(name -> "<" + name + ">")
            .collect(Collectors.joining(", "))
        + ".";
  }

  /** The tags of a MiniMessage text that are neither standard tags nor allowed placeholders. */
  private static List<String> unknownPlaceholders(String text, Set<String> allowed) {
    Set<String> unknown = new LinkedHashSet<>();
    TagResolver everythingElse =
        new TagResolver() {
          @Override
          public @Nullable Tag resolve(String name, ArgumentQueue arguments, Context ctx) {
            return Tag.selfClosingInserting(Component.empty());
          }

          @Override
          public boolean has(String name) {
            if (STANDARD.has(name)) {
              return false; // a color, a decoration, a style tag: not a placeholder
            }
            if (!allowed.contains(name)) {
              unknown.add(name);
            }
            return true;
          }
        };
    var _ =
        MiniMessage.builder()
            .tags(
                TagResolver.resolver(TagResolver.standard(), PlayerMessages.STYLES, everythingElse))
            .build()
            .deserialize(text);
    return List.copyOf(unknown);
  }

  private static boolean endsWithContinuation(String row) {
    int backslashes = 0;
    for (int i = row.length() - 1; i >= 0 && row.charAt(i) == '\\'; i--) {
      backslashes++;
    }
    return backslashes % 2 == 1;
  }

  private static Map<String, String> stringMap(Properties properties) {
    Map<String, String> map = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      map.put(name, properties.getProperty(name));
    }
    return map;
  }

  private static ConfigurationException problem(Path where, String description) {
    return ConfigurationException.of(List.of(new Problem(where, 0, null, description)));
  }

  // ---------------------------------------------------------------------------
  // Template
  // ---------------------------------------------------------------------------

  /**
   * Writes the overrides directory with a template listing every message, commented out.
   *
   * @param directory the directory to create
   * @throws ConfigurationException if it cannot be written
   */
  static void writeTemplate(Path directory) throws ConfigurationException {
    try {
      Files.createDirectories(directory);
      Files.writeString(directory.resolve(EVERY_LANGUAGE), template(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw problem(
          directory, "cannot be created (" + e + "): give the user running Warp write access");
    }
  }

  /** The template: a header, then every message commented out, with its English text. */
  static String template() {
    StringBuilder text =
        new StringBuilder(
            """
            # Overrides of the messages Warp shows players. Every message below is commented out:
            # Warp uses its own, in each player's language (bundled: English, French).
            #
            # To change a message, remove the # in front of it and edit its text. In this file, the
            # change applies to every language. For one language only, write it in a file named
            # messages_<language>.properties next to this one: messages_fr.properties (French),
            # messages_pt_BR.properties (Brazilian Portuguese)... A player whose game is in Canadian
            # French gets the first text found in: messages_fr_CA.properties,
            # messages_fr.properties, this file, Warp's French, Warp's English.
            #
            # Texts are MiniMessage (https://docs.advntr.dev/minimessage/format): <red>, <bold>,
            # <#38bdf8>, <newline>... Keep each message's placeholders, such as <server>, and save the
            # files as UTF-8. A chat message left empty is not sent; a disconnect reason cannot be
            # empty. The multiplayer.* messages are vanilla's: unless overridden, each player's game
            # shows them in its own language.
            #
            # Warp reads these files when it starts, and refuses to start on an unknown message or
            # placeholder, saying which file and line.
            """);
    for (Message message : Message.values()) {
      text.append(System.lineSeparator());
      if (message.vanillaSince() != null) {
        text.append("# Vanilla, translated by the game itself.").append(System.lineSeparator());
      } else if (!message.placeholders().isEmpty()) {
        text.append("# Placeholders: ")
            .append(
                message.placeholders().stream()
                    .map(name -> "<" + name + ">")
                    .collect(Collectors.joining(", ")))
            .append(System.lineSeparator());
      }
      String english = PlayerMessages.bundledText(Locale.ENGLISH, message.key());
      text.append('#')
          .append(message.key())
          .append('=')
          .append(english == null ? "" : english)
          .append(System.lineSeparator());
    }
    return text.toString();
  }
}
