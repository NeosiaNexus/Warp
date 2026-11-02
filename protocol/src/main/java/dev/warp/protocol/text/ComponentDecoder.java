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
package dev.warp.protocol.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.NumberBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import org.jspecify.annotations.Nullable;

/**
 * Reads a component a backend sent, in any version's form, without ever failing.
 *
 * <p>JSON is read leniently, as the vanilla client reads it; NBT is turned into the same JSON tree,
 * its bytes back into booleans where the style holds booleans and its boxed list elements ({@code
 * {"": ...}}) unboxed. The tree is then normalized before Adventure reads it: tooltips of every era
 * (a {@code value} with SNBT before 1.16, {@code contents} from 1.16, inlined from 1.21.5) become
 * one form, and what Adventure would refuse is left out (an unknown action, a malformed key, UUID
 * or SNBT). Adventure's own reader takes both the camel and the snake case names of the events.
 *
 * <p>What cannot be read as a component at all falls back to its text: the plain text found in the
 * tree, or the input itself. An empty reason stays empty: no bytes, a lone {@code TAG_End}, an
 * empty string or a component of empty text all read as {@link Component#empty()}, and anything
 * else reads as something else.
 */
final class ComponentDecoder {

  private static final GsonComponentSerializer GSON =
      GsonComponentSerializer.builder()
          .editOptions(options -> options.value(JSONOptions.VALIDATE_STRICT_EVENTS, false))
          .build();

  private static final TagStringIO SNBT = TagStringIO.tagStringIO();

  /** Style and contents fields that hold booleans, which NBT writes as bytes. */
  private static final Set<String> BOOLEAN_KEYS =
      Set.of(
          "bold",
          "italic",
          "underlined",
          "strikethrough",
          "obfuscated",
          "interpret",
          "hat",
          "plain");

  private static final Set<String> HOVER_ACTIONS =
      Set.of("show_text", "show_item", "show_entity", "show_achievement");

  private static final Set<String> CLICK_ACTIONS =
      Set.of(
          "open_url",
          "open_file",
          "run_command",
          "suggest_command",
          "change_page",
          "copy_to_clipboard",
          "show_dialog",
          "custom");

  private ComponentDecoder() {}

  /**
   * Reads a JSON component.
   *
   * @param json the JSON text
   * @return the component, its text when it is not JSON, empty when it is empty
   */
  static Component json(String json) {
    JsonElement tree;
    try {
      tree = JsonParser.parseString(json);
    } catch (RuntimeException notJson) {
      return Component.text(json);
    }
    return tree(tree, json);
  }

  /**
   * Reads an NBT component.
   *
   * @param tag the tag, {@link EndBinaryTag} for none
   * @return the component, empty when the tag is
   */
  static Component nbt(BinaryTag tag) {
    return tree(toJson(tag, null), null);
  }

  private static Component tree(JsonElement tree, @Nullable String raw) {
    if (tree.isJsonNull()) {
      return Component.empty();
    }
    Component component;
    try {
      component = GSON.deserializeFromTree(normalize(tree));
    } catch (RuntimeException unreadable) {
      StringBuilder text = new StringBuilder();
      plainText(tree, text);
      component = Component.text(text.isEmpty() && raw != null ? raw : text.toString());
    }
    return component.equals(Component.empty()) ? Component.empty() : component;
  }

  // ---------------------------------------------------------------------------
  // NBT to JSON
  // ---------------------------------------------------------------------------

  /**
   * Converts NBT to the JSON vanilla reads the same: a byte of a boolean field becomes a boolean, a
   * boxed list element its value, a custom click payload its SNBT, which is how Adventure holds it.
   */
  static JsonElement toJson(BinaryTag tag, @Nullable String key) {
    return switch (tag) {
      case StringBinaryTag string -> new JsonPrimitive(string.value());
      case ByteBinaryTag b ->
          key != null && BOOLEAN_KEYS.contains(key)
              ? new JsonPrimitive(b.value() != 0)
              : new JsonPrimitive(b.value());
      case NumberBinaryTag number -> new JsonPrimitive(number.numberValue());
      // Indexed loops: the iterators of adventure-nbt 4.26.1's int and long arrays skip their last
      // element.
      case ByteArrayBinaryTag bytes -> {
        JsonArray array = new JsonArray(bytes.size());
        for (int i = 0; i < bytes.size(); i++) {
          array.add(bytes.get(i));
        }
        yield array;
      }
      case IntArrayBinaryTag ints -> {
        JsonArray array = new JsonArray(ints.size());
        for (int i = 0; i < ints.size(); i++) {
          array.add(ints.get(i));
        }
        yield array;
      }
      case LongArrayBinaryTag longs -> {
        JsonArray array = new JsonArray(longs.size());
        for (int i = 0; i < longs.size(); i++) {
          array.add(longs.get(i));
        }
        yield array;
      }
      case ListBinaryTag list -> {
        JsonArray array = new JsonArray(list.size());
        for (BinaryTag element : list) {
          array.add(toJson(unbox(element), null));
        }
        yield array;
      }
      case CompoundBinaryTag compound -> {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, ? extends BinaryTag> entry : compound) {
          String name = entry.getKey();
          object.add(
              name,
              name.equals("payload") ? snbt(entry.getValue()) : toJson(entry.getValue(), name));
        }
        yield object;
      }
      default -> JsonNull.INSTANCE; // no tag
    };
  }

  /** A list element boxed in a compound of one empty-named entry, as vanilla mixes types. */
  private static BinaryTag unbox(BinaryTag element) {
    if (element instanceof CompoundBinaryTag compound && compound.size() == 1) {
      BinaryTag boxed = compound.get("");
      if (boxed != null) {
        return boxed;
      }
    }
    return element;
  }

  private static JsonElement snbt(BinaryTag tag) {
    try {
      return new JsonPrimitive(SNBT.asString(tag));
    } catch (IOException unprintable) {
      throw new UncheckedIOException(unprintable); // written to a string, never happens
    }
  }

  // ---------------------------------------------------------------------------
  // Normalization
  // ---------------------------------------------------------------------------

  /**
   * A copy of a component tree with its events in one form, and what Adventure refuses left out.
   * Its depth is bounded by what produced it: Gson's nesting limit, or {@link NbtReader}'s.
   */
  private static JsonElement normalize(JsonElement element) {
    if (element.isJsonArray()) {
      JsonArray array = new JsonArray();
      for (JsonElement item : element.getAsJsonArray()) {
        array.add(normalize(item));
      }
      return array;
    }
    if (!element.isJsonObject()) {
      return element;
    }
    JsonObject object = new JsonObject();
    for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
      String key = entry.getKey();
      JsonElement value = entry.getValue();
      switch (key) {
        case "extra", "with", "separator" -> object.add(key, normalize(value));
        case "hoverEvent", "hover_event" -> {
          JsonObject hover = hover(value);
          if (hover != null) {
            object.add("hoverEvent", hover);
          }
        }
        case "clickEvent", "click_event" -> {
          JsonObject click = click(value);
          if (click != null) {
            object.add(key, click);
          }
        }
        case "font" -> {
          if (key(value) != null) {
            object.add(key, value);
          }
        }
        default -> object.add(key, value);
      }
    }
    return object;
  }

  /** A click event Adventure reads, its custom payload as a string, or none. */
  private static @Nullable JsonObject click(JsonElement value) {
    if (!value.isJsonObject()) {
      return null;
    }
    JsonObject click = value.getAsJsonObject();
    String action = string(click.get("action"));
    if (action == null || !CLICK_ACTIONS.contains(action)) {
      return null;
    }
    JsonObject copy = new JsonObject();
    for (Map.Entry<String, JsonElement> entry : click.entrySet()) {
      JsonElement field = entry.getValue();
      boolean text = field.isJsonPrimitive() && !field.getAsJsonPrimitive().isBoolean();
      copy.add(entry.getKey(), text ? field : new JsonPrimitive(field.toString()));
    }
    if (action.equals("change_page") && !copy.has("page")) {
      // Before 1.21.5 the page is the string value, which Adventure only reads as a page field.
      try {
        copy.addProperty("page", Integer.parseInt(String.valueOf(string(copy.get("value")))));
      } catch (NumberFormatException notAPage) {
        return null;
      }
    }
    return copy;
  }

  /**
   * A hover event in the 1.16 form Adventure reads, from any era's: {@code contents}, a 1.21.5
   * inlined one, or a pre-1.16 {@code value}, whose item or entity is SNBT text. Returns {@code
   * null} for one that cannot be read.
   */
  private static @Nullable JsonObject hover(JsonElement value) {
    if (!value.isJsonObject()) {
      return null;
    }
    JsonObject hover = value.getAsJsonObject();
    String action = string(hover.get("action"));
    if (action == null || !HOVER_ACTIONS.contains(action)) {
      return null;
    }
    JsonElement contents = hover.has("contents") ? hover.get("contents") : null;
    JsonElement legacy = contents == null && hover.has("value") ? hover.get("value") : null;
    JsonObject out = new JsonObject();
    out.addProperty("action", action);
    JsonElement normalized =
        switch (action) {
          case "show_text" -> {
            JsonElement text = contents != null ? contents : legacy;
            yield text == null ? null : normalize(text);
          }
          case "show_achievement" -> {
            JsonElement id = contents != null ? contents : legacy;
            yield id == null ? null : new JsonPrimitive(plainText(id));
          }
          case "show_item" -> item(contents != null ? contents : legacySnbt(legacy, hover));
          default -> entity(contents != null ? contents : legacySnbt(legacy, hover));
        };
    if (normalized == null) {
      return null;
    }
    out.add(action.equals("show_achievement") ? "value" : "contents", normalized);
    return out;
  }

  /**
   * The item or entity of a tooltip as JSON: from the SNBT of a pre-1.16 {@code value}, or the
   * hover event itself when 1.21.5 inlined its fields.
   */
  private static @Nullable JsonElement legacySnbt(@Nullable JsonElement legacy, JsonObject hover) {
    if (legacy == null) {
      return hover;
    }
    try {
      return toJson(SNBT.asCompound(plainText(legacy)), null);
    } catch (IOException | RuntimeException malformed) {
      return null;
    }
  }

  /** An item stack: {@code id}, {@code count}, and its {@code tag} or {@code components}. */
  private static @Nullable JsonObject item(@Nullable JsonElement source) {
    if (source == null) {
      return null;
    }
    if (source.isJsonPrimitive()) {
      String id = key(source);
      if (id == null) {
        return null;
      }
      JsonObject item = new JsonObject();
      item.addProperty("id", id);
      return item;
    }
    if (!source.isJsonObject()) {
      return null;
    }
    JsonObject fields = source.getAsJsonObject();
    String id = key(fields.get("id"));
    if (id == null) {
      return null;
    }
    JsonObject item = new JsonObject();
    item.addProperty("id", id);
    JsonElement count = fields.has("count") ? fields.get("count") : fields.get("Count");
    if (count != null && count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
      item.addProperty("count", count.getAsInt());
    }
    JsonElement tag = fields.get("tag");
    if (tag != null) {
      item.add("tag", tag.isJsonPrimitive() ? tag : new JsonPrimitive(tag.toString()));
    }
    JsonElement components = fields.get("components");
    if (components != null && components.isJsonObject()) {
      item.add("components", components);
    }
    return item;
  }

  /**
   * An entity: {@code type}, {@code id} as a UUID string, and its {@code name}. Before 1.21.5 the
   * type is {@code type} and the UUID {@code id}; from 1.21.5 the type is {@code id} and the UUID
   * {@code uuid}. Before 1.13 the name was plain text, a JSON component after.
   */
  private static @Nullable JsonObject entity(@Nullable JsonElement source) {
    if (source == null || !source.isJsonObject()) {
      return null;
    }
    JsonObject fields = source.getAsJsonObject();
    boolean inlined = fields.has("uuid");
    String type = key(inlined ? fields.get("id") : fields.get("type"));
    UUID id = uuid(inlined ? fields.get("uuid") : fields.get("id"));
    if (type == null || id == null) {
      return null;
    }
    JsonObject entity = new JsonObject();
    entity.addProperty("type", type);
    entity.addProperty("id", id.toString());
    JsonElement name = fields.get("name");
    if (name != null) {
      entity.add("name", normalize(entityName(name)));
    }
    return entity;
  }

  /** An entity name: a component, or the JSON or plain text a legacy tooltip carries it as. */
  private static JsonElement entityName(JsonElement name) {
    if (!name.isJsonPrimitive()) {
      return name;
    }
    String text = name.getAsString();
    if (text.startsWith("{") || text.startsWith("[") || text.startsWith("\"")) {
      try {
        return JsonParser.parseString(text);
      } catch (RuntimeException notJson) {
        // plain text after all
      }
    }
    return new JsonPrimitive(text);
  }

  // ---------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------

  private static @Nullable String string(@Nullable JsonElement element) {
    return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
  }

  /** A resource location, {@code minecraft:} when it has no namespace, or {@code null}. */
  private static @Nullable String key(@Nullable JsonElement element) {
    String value = string(element);
    if (value == null) {
      return null;
    }
    // Entity types of legacy tooltips are names such as "Pig" before 1.11.
    String lower = value.indexOf(':') < 0 ? value.toLowerCase(java.util.Locale.ROOT) : value;
    return Key.parseable(lower) ? lower : null;
  }

  /** A UUID from its string, or the four ints the codecs write it as. */
  private static @Nullable UUID uuid(@Nullable JsonElement element) {
    if (element == null) {
      return null;
    }
    if (element.isJsonArray() && element.getAsJsonArray().size() == 4) {
      JsonArray ints = element.getAsJsonArray();
      try {
        long most = ((long) ints.get(0).getAsInt() << 32) | (ints.get(1).getAsInt() & 0xFFFFFFFFL);
        long least = ((long) ints.get(2).getAsInt() << 32) | (ints.get(3).getAsInt() & 0xFFFFFFFFL);
        return new UUID(most, least);
      } catch (RuntimeException notInts) {
        return null;
      }
    }
    String text = string(element);
    if (text == null) {
      return null;
    }
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException notUuid) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Plain text fallback
  // ---------------------------------------------------------------------------

  private static String plainText(JsonElement element) {
    StringBuilder text = new StringBuilder();
    plainText(element, text);
    return text.toString();
  }

  /**
   * Appends the text a component tree shows, as far as it can be read: strings, the {@code text}
   * and {@code translate} fields (a translation's fallback when it has one), and its children.
   */
  private static void plainText(JsonElement element, StringBuilder text) {
    if (element.isJsonPrimitive()) {
      text.append(element.getAsString());
    } else if (element.isJsonArray()) {
      for (JsonElement item : element.getAsJsonArray()) {
        plainText(item, text);
      }
    } else if (element.isJsonObject()) {
      JsonObject object = element.getAsJsonObject();
      String contents = string(object.get("text"));
      if (contents == null) {
        String fallback = string(object.get("fallback"));
        contents = fallback != null ? fallback : string(object.get("translate"));
      }
      if (contents != null) {
        text.append(contents);
      }
      JsonElement extra = object.get("extra");
      if (extra != null) {
        plainText(extra, text);
      }
    }
  }
}
