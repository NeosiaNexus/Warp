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
package dev.warp.protocol.command;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The parser of an argument node: an argument type, as Brigadier calls it, and its properties.
 *
 * <p>Every argument type whose packet form carries properties has a record of its own, with those
 * properties typed: the Brigadier numbers and strings, entity selectors, score holders, times,
 * registry-backed resources, Forge's enums. Every other vanilla type is a {@link PlainParser},
 * named by its identifier. A type Warp does not know cannot be decoded at all (its properties have
 * no length on the wire): {@link CommandTreeCodec} reports such a tree as opaque, never as one of
 * these.
 *
 * <p>The identifiers are the game's ({@code brigadier:integer}, {@code minecraft:entity}), on every
 * version: from 1.19 the packet carries a registry id instead, which {@link ArgumentTypes} maps per
 * version.
 */
public sealed interface ArgumentParser
    permits ArgumentParser.PlainParser,
        ArgumentParser.FloatParser,
        ArgumentParser.DoubleParser,
        ArgumentParser.IntegerParser,
        ArgumentParser.LongParser,
        ArgumentParser.StringParser,
        ArgumentParser.EntityParser,
        ArgumentParser.ScoreHolderParser,
        ArgumentParser.TimeParser,
        ArgumentParser.ResourceParser,
        ArgumentParser.ForgeEnumParser,
        ArgumentParser.WrappedParser {

  /**
   * Returns the argument type's identifier, as the game registers it.
   *
   * @return the identifier, {@code brigadier:integer} for instance
   */
  String identifier();

  // ---------------------------------------------------------------------------
  // Factories
  // ---------------------------------------------------------------------------

  /**
   * Returns the parser of an argument type without properties, {@code minecraft:block_pos} for
   * instance.
   *
   * @param identifier the argument type's identifier
   * @return its parser
   * @throws IllegalArgumentException if the type has properties: use its record
   */
  static ArgumentParser of(String identifier) {
    return new PlainParser(identifier);
  }

  /**
   * Returns Brigadier's single-word string parser, {@code brigadier:string} in {@link
   * StringMode#SINGLE_WORD} mode.
   *
   * @return the parser
   */
  static StringParser word() {
    return StringParser.WORD;
  }

  /**
   * Returns Brigadier's greedy string parser: the rest of the input, spaces included.
   *
   * @return the parser
   */
  static StringParser greedyString() {
    return StringParser.GREEDY;
  }

  // ---------------------------------------------------------------------------
  // Parsers
  // ---------------------------------------------------------------------------

  /**
   * An argument type whose packet form has no properties: {@code brigadier:bool}, {@code
   * minecraft:block_pos}, {@code minecraft:uuid}, and most others.
   *
   * @param identifier the argument type's identifier
   */
  record PlainParser(String identifier) implements ArgumentParser {

    // Checks that the type has no properties.
    public PlainParser {
      Objects.requireNonNull(identifier, "identifier");
      if (identifier.isEmpty()) {
        throw new IllegalArgumentException("Empty argument type identifier");
      }
      if (ArgumentTypes.hasProperties(identifier)) {
        throw new IllegalArgumentException(
            identifier + " has properties: use its own ArgumentParser record");
      }
    }
  }

  /**
   * {@code brigadier:float}: a float, with an optional minimum and maximum.
   *
   * @param min the smallest value accepted, or {@code null} for none
   * @param max the largest value accepted, or {@code null} for none
   */
  record FloatParser(@Nullable Float min, @Nullable Float max) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "brigadier:float";

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /**
   * {@code brigadier:double}: a double, with an optional minimum and maximum.
   *
   * @param min the smallest value accepted, or {@code null} for none
   * @param max the largest value accepted, or {@code null} for none
   */
  record DoubleParser(@Nullable Double min, @Nullable Double max) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "brigadier:double";

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /**
   * {@code brigadier:integer}: an int, with an optional minimum and maximum.
   *
   * @param min the smallest value accepted, or {@code null} for none
   * @param max the largest value accepted, or {@code null} for none
   */
  record IntegerParser(@Nullable Integer min, @Nullable Integer max) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "brigadier:integer";

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /**
   * {@code brigadier:long}: a long, with an optional minimum and maximum. Clients know it from
   * 1.14.4.
   *
   * @param min the smallest value accepted, or {@code null} for none
   * @param max the largest value accepted, or {@code null} for none
   */
  record LongParser(@Nullable Long min, @Nullable Long max) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "brigadier:long";

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /** How much of the input a {@link StringParser} reads, in the order the packet numbers them. */
  enum StringMode {
    /** One word: no spaces, no quotes. */
    SINGLE_WORD,
    /** One word, or a quoted phrase. */
    QUOTABLE_PHRASE,
    /** The rest of the input. */
    GREEDY_PHRASE
  }

  /**
   * {@code brigadier:string}: a string, read as {@code mode} says.
   *
   * @param mode how much of the input the argument takes
   */
  record StringParser(StringMode mode) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "brigadier:string";

    private static final StringParser WORD = new StringParser(StringMode.SINGLE_WORD);
    private static final StringParser QUOTABLE = new StringParser(StringMode.QUOTABLE_PHRASE);
    private static final StringParser GREEDY = new StringParser(StringMode.GREEDY_PHRASE);

    // Checks the mode.
    public StringParser {
      Objects.requireNonNull(mode, "mode");
    }

    /** The shared parser of a mode: decoding allocates no string parser. */
    static StringParser of(StringMode mode) {
      return switch (mode) {
        case SINGLE_WORD -> WORD;
        case QUOTABLE_PHRASE -> QUOTABLE;
        case GREEDY_PHRASE -> GREEDY;
      };
    }

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /**
   * {@code minecraft:entity}: an entity selector or name.
   *
   * @param single whether it selects at most one entity
   * @param playersOnly whether it selects players only
   */
  record EntityParser(boolean single, boolean playersOnly) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "minecraft:entity";

    private static final EntityParser[] SHARED = {
      new EntityParser(false, false),
      new EntityParser(true, false),
      new EntityParser(false, true),
      new EntityParser(true, true)
    };

    /** The shared parser of a flags byte (0x01 single, 0x02 players only), checked. */
    static EntityParser of(int flags) {
      return SHARED[flags];
    }

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /**
   * {@code minecraft:score_holder}: a score holder, by selector or name.
   *
   * @param multiple whether it may select several holders
   */
  record ScoreHolderParser(boolean multiple) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "minecraft:score_holder";

    private static final ScoreHolderParser ONE = new ScoreHolderParser(false);
    private static final ScoreHolderParser MANY = new ScoreHolderParser(true);

    /** The shared parser of a flag. */
    static ScoreHolderParser of(boolean multiple) {
      return multiple ? MANY : ONE;
    }

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /**
   * {@code minecraft:time}: a duration in ticks, days or seconds. Clients know it from 1.14; the
   * packet carries its minimum from 1.19.4 only (see {@link ArgumentTypes#TIME_MINIMUM_SINCE}), and
   * an encoder for an older version leaves it out.
   *
   * @param minimum the shortest duration accepted, in ticks
   */
  record TimeParser(int minimum) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "minecraft:time";

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /** The argument types that name an element (or tag) of a registry, sent with that registry. */
  enum ResourceKind {
    /** {@code minecraft:resource}: an element of the registry, from 1.18.2. */
    RESOURCE("minecraft:resource"),
    /** {@code minecraft:resource_or_tag}: an element or a tag, from 1.18.2. */
    RESOURCE_OR_TAG("minecraft:resource_or_tag"),
    /** {@code minecraft:resource_key}: the key of an element, from 1.19.3. */
    RESOURCE_KEY("minecraft:resource_key"),
    /** {@code minecraft:resource_or_tag_key}: the key of an element or a tag, from 1.19.3. */
    RESOURCE_OR_TAG_KEY("minecraft:resource_or_tag_key"),
    /** {@code minecraft:resource_selector}: elements matched by a pattern, from 1.21.5. */
    RESOURCE_SELECTOR("minecraft:resource_selector");

    private final String identifier;

    ResourceKind(String identifier) {
      this.identifier = identifier;
    }

    /**
     * Returns the argument type's identifier.
     *
     * @return the identifier, {@code minecraft:resource} for instance
     */
    public String identifier() {
      return identifier;
    }
  }

  /**
   * One of the argument types naming registry elements: {@code minecraft:resource} and its kin.
   *
   * @param kind which of them
   * @param registry the registry's identifier, {@code minecraft:entity_type} for instance
   */
  record ResourceParser(ResourceKind kind, String registry) implements ArgumentParser {

    // Checks the components.
    public ResourceParser {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String identifier() {
      return kind.identifier();
    }
  }

  /**
   * {@code forge:enum}: a constant of a Java enum, which Forge servers from 1.14 to 1.18.2 send
   * their own clients by name. From 1.19 Forge sends it under a registry id of its own, which no
   * vanilla table holds: such a tree is opaque.
   *
   * @param enumClass the binary name of the enum class
   */
  record ForgeEnumParser(String enumClass) implements ArgumentParser {
    /** The identifier of this argument type. */
    public static final String IDENTIFIER = "forge:enum";

    // Checks the class name.
    public ForgeEnumParser {
      Objects.requireNonNull(enumClass, "enumClass");
    }

    @Override
    public String identifier() {
      return IDENTIFIER;
    }
  }

  /**
   * A modded argument type a backend wrapped with the CrossStitch mod, so that a proxy can read
   * past it: its type, as the backend knows it, and its properties as raw bytes.
   *
   * <p>The wrapper exists for the proxy hop only: no client reads it. Encoding writes the type
   * unwrapped, as the backend would have without CrossStitch: its identifier (1.13 to 1.18.2) or
   * its registry id (from 1.19), then the properties untouched. Decoding then re-encoding a wrapped
   * tree therefore unwraps it.
   *
   * @param wrappedIdentifier the wrapped type's identifier from 1.13 to 1.18.2, else {@code null}
   * @param wrappedId the wrapped type's id in the backend's registry from 1.19, else -1
   * @param properties the type's properties, exactly as the backend wrote them
   */
  // The array is copied in and out, and equals, hashCode and toString read its content.
  @SuppressWarnings("ArrayRecordComponent")
  record WrappedParser(@Nullable String wrappedIdentifier, int wrappedId, byte[] properties)
      implements ArgumentParser {

    /** CrossStitch's identifier for a wrapped type, from 1.13 to 1.18.2. */
    public static final String IDENTIFIER = "crossstitch:mod_argument";

    /** CrossStitch's registry id for a wrapped type, from 1.19: a negative VarInt. */
    public static final int REGISTRY_ID = -256;

    // Checks that the type is named one way only (IllegalArgumentException if both or neither of
    // the identifier and the registry id are given), and copies the properties.
    public WrappedParser {
      if ((wrappedIdentifier == null) == (wrappedId < 0)) {
        throw new IllegalArgumentException(
            "A wrapped type has an identifier (1.13 to 1.18.2) or a registry id (1.19+), not both");
      }
      properties = properties.clone();
    }

    /**
     * Returns the wrapped type's identifier, or {@code #<id>} when the backend sent its registry
     * id.
     *
     * @return the wrapped type, as far as the packet names it
     */
    @Override
    public String identifier() {
      return wrappedIdentifier != null ? wrappedIdentifier : "#" + wrappedId;
    }

    /**
     * Returns a copy of the wrapped type's properties.
     *
     * @return the properties, as the backend wrote them
     */
    @Override
    public byte[] properties() {
      return properties.clone();
    }

    /** The properties themselves, for the encoder: never handed out. */
    byte[] propertiesUnsafe() {
      return properties;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof WrappedParser wrapped
          && Objects.equals(wrappedIdentifier, wrapped.wrappedIdentifier)
          && wrappedId == wrapped.wrappedId
          && Arrays.equals(properties, wrapped.properties);
    }

    @Override
    public int hashCode() {
      return Objects.hash(wrappedIdentifier, wrappedId, Arrays.hashCode(properties));
    }

    @Override
    public String toString() {
      return "WrappedParser["
          + identifier()
          + ", properties="
          + HexFormat.of().formatHex(properties)
          + "]";
    }
  }
}
