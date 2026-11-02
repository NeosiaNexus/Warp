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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.BlockNBTComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.EntityNBTComponent;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.NBTComponent;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.ScoreComponent;
import net.kyori.adventure.text.SelectorComponent;
import net.kyori.adventure.text.StorageNBTComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.DataComponentValueConverterRegistry;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.object.SpriteObjectContents;
import org.jspecify.annotations.Nullable;

/**
 * Writes an Adventure component as the vanilla game of one version writes it, into a {@link
 * TreeSink}: field names, field order and value shapes of that version, so that the JSON is the one
 * a vanilla server sends and the NBT holds the same tags.
 *
 * <p>Up to 1.20.2 the vanilla Gson serializer writes the style first, then {@code extra}, then the
 * contents; from 1.20.3 the codecs write the contents, {@code extra}, then the style. Both collapse
 * a component of plain text alone, without style or children, into a bare string, wherever it is:
 * the root, a child, a translation argument, a tooltip.
 *
 * <p>What the version cannot hold is replaced by what its client would show (see {@link
 * TextFeatures}): a keybind becomes its translation, which is what a newer client falls back to; a
 * selector its pattern and a score or NBT value nothing, as a client shows them unresolved; an
 * object nothing. Events the version lacks or would refuse are left out, the rest of the component
 * is kept.
 */
final class ComponentWriter {

  /** The style's decorations in the order vanilla writes them. */
  private static final TextDecoration[] DECORATIONS = {
    TextDecoration.BOLD,
    TextDecoration.ITALIC,
    TextDecoration.UNDERLINED,
    TextDecoration.STRIKETHROUGH,
    TextDecoration.OBFUSCATED
  };

  private static final TagStringIO SNBT = TagStringIO.tagStringIO();

  private final TextFeatures features;
  private final TreeSink out;

  ComponentWriter(TextFeatures features, TreeSink out) {
    this.features = features;
    this.out = out;
  }

  /**
   * Writes {@code component} as the root of the tree.
   *
   * @param component the component
   */
  void write(Component component) {
    String plain = features.compactText() ? plainText(component) : null;
    if (plain != null) {
      out.stringValue(plain);
    } else {
      out.beginObjectValue();
      body(component);
      out.endObject();
    }
  }

  // ---------------------------------------------------------------------------
  // Components
  // ---------------------------------------------------------------------------

  private void body(Component component) {
    if (features.codecs()) {
      contents(component);
      children(component.children());
      style(component.style());
    } else {
      style(component.style());
      children(component.children());
      contents(component);
    }
  }

  /** Writes a component field, as a bare string where the version collapses plain text. */
  private void field(String key, Component component) {
    String plain = features.compactText() ? plainText(component) : null;
    if (plain != null) {
      out.string(key, plain);
    } else {
      out.beginObject(key);
      body(component);
      out.endObject();
    }
  }

  private void children(List<Component> children) {
    if (!children.isEmpty()) {
      list("extra", children, features.compactText());
    }
  }

  /**
   * Writes a list of components, those of plain text alone as bare strings when {@code collapse}:
   * anywhere in JSON; in NBT, whose lists hold one type, a list of strings when all are plain text,
   * else each boxed in a compound with an empty name, as vanilla does.
   */
  private void list(String key, List<Component> components, boolean collapse) {
    String[] plain = new String[components.size()];
    boolean allPlain = collapse;
    for (int i = 0; collapse && i < plain.length; i++) {
      plain[i] = plainText(components.get(i));
      allPlain &= plain[i] != null;
    }
    out.beginList(key, allPlain ? TreeSink.Kind.STRING : TreeSink.Kind.OBJECT, plain.length);
    for (int i = 0; i < plain.length; i++) {
      String text = plain[i];
      if (text == null) {
        out.beginObjectValue();
        body(components.get(i));
        out.endObject();
      } else if (allPlain || !out.homogeneousLists()) {
        out.stringValue(text);
      } else {
        out.beginObjectValue();
        out.string("", text);
        out.endObject();
      }
    }
    out.endList();
  }

  // ---------------------------------------------------------------------------
  // Contents
  // ---------------------------------------------------------------------------

  private void contents(Component component) {
    switch (component) {
      case TextComponent text -> out.string("text", text.content());
      case TranslatableComponent translatable -> translatable(translatable);
      case KeybindComponent keybind ->
          // Before 1.12 the keybind's translation, which a newer client falls back to as well.
          out.string(features.keybinds() ? "keybind" : "translate", keybind.keybind());
      case ScoreComponent score -> score(score);
      case SelectorComponent selector -> selector(selector);
      case NBTComponent<?, ?> nbt -> nbt(nbt);
      case ObjectComponent object -> object(object);
      default ->
          throw new IllegalArgumentException(
              "No wire form for " + component.getClass().getSimpleName());
    }
  }

  private void translatable(TranslatableComponent translatable) {
    out.string("translate", translatable.key());
    String fallback = translatable.fallback();
    if (fallback != null && features.translationFallbacks()) {
      out.string("fallback", fallback);
    }
    List<TranslationArgument> arguments = translatable.arguments();
    if (!arguments.isEmpty()) {
      List<Component> components = new ArrayList<>(arguments.size());
      for (TranslationArgument argument : arguments) {
        // Numbers and booleans read the same as their text, in every version.
        components.add(
            argument.value() instanceof Component component
                ? component
                : Component.text(String.valueOf(argument.value())));
      }
      list("with", components, features.compactText() || features.plainArgumentsAsText());
    }
  }

  /**
   * A score the proxy cannot resolve: its value as text when Adventure carries one (a client from
   * 1.20.3 would show nothing for it), the score contents otherwise, nothing before 1.8.
   */
  private void score(ScoreComponent score) {
    String value = resolved(score);
    if (value != null || !features.scoresAndSelectors()) {
      out.string("text", value != null ? value : "");
      return;
    }
    out.beginObject("score");
    out.string("name", score.name());
    out.string("objective", score.objective());
    out.endObject();
  }

  /** A selector, shown as its pattern before 1.8 as a client shows one unresolved. */
  private void selector(SelectorComponent selector) {
    if (!features.scoresAndSelectors()) {
      out.string("text", selector.pattern());
      return;
    }
    out.string("selector", selector.pattern());
    separator(selector.separator());
  }

  /** The value of a score whoever resolved it wrote, which Adventure keeps. */
  @SuppressWarnings("deprecation") // the resolved value
  private static @Nullable String resolved(ScoreComponent score) {
    return score.value();
  }

  /** Whether the version reads NBT contents: from 1.14, from command storage from 1.15. */
  private boolean writesNbt(NBTComponent<?, ?> nbt) {
    return features.nbtContents()
        && (!(nbt instanceof StorageNBTComponent) || features.storageAndClipboard());
  }

  private void nbt(NBTComponent<?, ?> nbt) {
    if (!writesNbt(nbt)) {
      out.string("text", ""); // a client shows unresolved NBT contents as nothing
      return;
    }
    out.string("nbt", nbt.nbtPath());
    // The codecs leave out the default; the Gson serializer always wrote it.
    if (nbt.interpret() || !features.codecs()) {
      out.bool("interpret", nbt.interpret());
    }
    separator(nbt.separator());
    switch (nbt) {
      case BlockNBTComponent block -> out.string("block", block.pos().asString());
      case EntityNBTComponent entity -> out.string("entity", entity.selector());
      case StorageNBTComponent storageNbt -> out.string("storage", storageNbt.storage().asString());
      default ->
          throw new IllegalArgumentException("No wire form for " + nbt.getClass().getSimpleName());
    }
  }

  private void separator(@Nullable Component separator) {
    if (separator != null && features.separators()) {
      field("separator", separator);
    }
  }

  /** Atlas sprites and player heads from 1.21.9; nothing before, which cannot show them. */
  private void object(ObjectComponent object) {
    if (!features.objects()) {
      out.string("text", "");
      return;
    }
    ObjectContents contents = object.contents();
    switch (contents) {
      case SpriteObjectContents sprite -> {
        if (!sprite.atlas().equals(SpriteObjectContents.DEFAULT_ATLAS)) {
          out.string("atlas", sprite.atlas().asString());
        }
        out.string("sprite", sprite.sprite().asString());
      }
      case PlayerHeadObjectContents head -> playerHead(head);
      default ->
          throw new IllegalArgumentException(
              "No wire form for " + contents.getClass().getSimpleName());
    }
  }

  private void playerHead(PlayerHeadObjectContents head) {
    String name = head.name();
    UUID id = head.id();
    List<PlayerHeadObjectContents.ProfileProperty> properties = head.profileProperties();
    Key texture = head.texture();
    if (name != null && id == null && properties.isEmpty() && texture == null) {
      out.string("player", name);
    } else {
      out.beginObject("player");
      if (name != null) {
        out.string("name", name);
      }
      if (id != null) {
        out.intArray("id", uuidInts(id));
      }
      if (!properties.isEmpty()) {
        out.beginList("properties", TreeSink.Kind.OBJECT, properties.size());
        for (PlayerHeadObjectContents.ProfileProperty property : properties) {
          out.beginObjectValue();
          out.string("name", property.name());
          out.string("value", property.value());
          String signature = property.signature();
          if (signature != null) {
            out.string("signature", signature);
          }
          out.endObject();
        }
        out.endList();
      }
      if (texture != null) {
        out.string("texture", texture.asString());
      }
      out.endObject();
    }
    out.bool("hat", head.hat());
  }

  // ---------------------------------------------------------------------------
  // Style
  // ---------------------------------------------------------------------------

  private void style(Style style) {
    if (style.isEmpty()) {
      return;
    }
    if (features.codecs()) {
      color(style.color());
      shadowColor(style.shadowColor());
      decorations(style);
      clickEvent(style.clickEvent());
      hoverEvent(style.hoverEvent());
      insertion(style.insertion());
      font(style.font());
    } else {
      decorations(style);
      color(style.color());
      insertion(style.insertion());
      clickEvent(style.clickEvent());
      hoverEvent(style.hoverEvent());
      font(style.font());
    }
  }

  private void decorations(Style style) {
    for (TextDecoration decoration : DECORATIONS) {
      TextDecoration.State state = style.decoration(decoration);
      if (state != TextDecoration.State.NOT_SET) {
        out.bool(TextDecoration.NAMES.keyOrThrow(decoration), state == TextDecoration.State.TRUE);
      }
    }
  }

  /** A named colour by its name; RGB as {@code #RRGGBB} from 1.16, its nearest named one before. */
  private void color(@Nullable TextColor color) {
    if (color == null) {
      return;
    }
    if (color instanceof NamedTextColor named) {
      out.string("color", NamedTextColor.NAMES.keyOrThrow(named));
    } else if (features.rgbFontsAndHoverContents()) {
      out.string("color", "#%06X".formatted(color.value()));
    } else {
      out.string("color", NamedTextColor.NAMES.keyOrThrow(NamedTextColor.nearestTo(color)));
    }
  }

  private void shadowColor(@Nullable ShadowColor shadowColor) {
    if (shadowColor != null && features.shadowColors()) {
      out.integer("shadow_color", shadowColor.value());
    }
  }

  private void insertion(@Nullable String insertion) {
    if (insertion != null && features.insertion()) {
      out.string("insertion", insertion);
    }
  }

  private void font(@Nullable Key font) {
    if (font != null && features.rgbFontsAndHoverContents()) {
      out.string("font", font.asString());
    }
  }

  // ---------------------------------------------------------------------------
  // Click events
  // ---------------------------------------------------------------------------

  /**
   * Writes a click event the version can take: {@code clickEvent} with a string {@code value}
   * before 1.21.5, {@code click_event} with typed fields from. A server never sends {@code
   * open_file} (from 1.20.3 the client refuses it), and {@code show_dialog} carries a dialog
   * Adventure has no wire form for: both are left out, as is any action the version lacks.
   */
  private void clickEvent(@Nullable ClickEvent event) {
    if (event == null || !writesClick(event)) {
      return;
    }
    ClickEvent.Action action = event.action();
    String name = action.name().toLowerCase(Locale.ROOT);
    ClickEvent.Payload payload = event.payload();
    switch (action) {
      case OPEN_URL -> {
        String url = text(payload);
        click(
            name, "url", features.typedEvents() ? Objects.requireNonNull(untrustedUri(url)) : url);
      }
      case RUN_COMMAND, SUGGEST_COMMAND -> click(name, "command", text(payload));
      case COPY_TO_CLIPBOARD -> click(name, "value", text(payload));
      case CHANGE_PAGE -> changePage(name, page(payload));
      default -> custom(name, (ClickEvent.Payload.Custom) payload);
    }
  }

  /**
   * Whether the version takes a click event: {@code copy_to_clipboard} from 1.15, {@code
   * change_page} from 1.8, {@code custom} from 1.21.6, and from 1.21.5 only the URLs, commands and
   * pages its client validates. A server never sends {@code open_file} (from 1.20.3 the client
   * refuses it), and {@code show_dialog} carries a dialog Adventure has no wire form for.
   */
  private boolean writesClick(ClickEvent event) {
    ClickEvent.Payload payload = event.payload();
    boolean typed = features.typedEvents();
    return switch (event.action()) {
      case OPEN_URL -> !typed || untrustedUri(text(payload)) != null;
      case RUN_COMMAND, SUGGEST_COMMAND -> !typed || isChatString(text(payload));
      case COPY_TO_CLIPBOARD -> features.storageAndClipboard();
      case CHANGE_PAGE -> features.scoresAndSelectors() && (!typed || page(payload) > 0);
      case CUSTOM -> features.customClicks();
      case OPEN_FILE, SHOW_DIALOG -> false;
    };
  }

  private void changePage(String name, int page) {
    if (features.typedEvents()) {
      out.beginObject("click_event");
      out.string("action", name);
      out.integer("page", page);
      out.endObject();
    } else {
      click(name, "value", Integer.toString(page));
    }
  }

  private static int page(ClickEvent.Payload payload) {
    return ((ClickEvent.Payload.Int) payload).integer();
  }

  /** The {@code custom} action (1.21.6): an id, and an NBT payload the client sends back. */
  private void custom(String name, ClickEvent.Payload.Custom custom) {
    BinaryTag payload = parse(custom.nbt());
    out.beginObject("click_event");
    out.string("action", name);
    out.string("id", custom.key().asString());
    if (payload != null) {
      out.tag("payload", payload);
    }
    out.endObject();
  }

  /** Writes a click event whose value is a string, under its typed field from 1.21.5. */
  private void click(String action, String typedField, String value) {
    if (features.typedEvents()) {
      out.beginObject("click_event");
      out.string("action", action);
      out.string(typedField, value);
    } else {
      out.beginObject("clickEvent");
      out.string("action", action);
      out.string("value", value);
    }
    out.endObject();
  }

  private static String text(ClickEvent.Payload payload) {
    return ((ClickEvent.Payload.Text) payload).value();
  }

  /**
   * Returns the URL a 1.21.5+ client accepts in {@code open_url}, or {@code null} when there is
   * none: it parses the value as a URI and refuses any scheme but http and https, failing the whole
   * component (vanilla {@code Util#parseAndValidateUntrustedUri}). A URL without a scheme is taken
   * as https, as Adventure does for these versions.
   */
  private static @Nullable String untrustedUri(String url) {
    String candidate = url;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        String scheme = new URI(candidate).getScheme();
        if (scheme == null) {
          candidate = "https://" + url;
          continue;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        return lower.equals("http") || lower.equals("https") ? candidate : null;
      } catch (URISyntaxException e) {
        return null;
      }
    }
    return null;
  }

  /**
   * Whether every character of a command is one chat accepts, which 1.21.5+ requires of {@code
   * run_command} and {@code suggest_command}: no section sign, no control character, no DEL
   * (vanilla {@code StringUtil#isAllowedChatCharacter}).
   */
  private static boolean isChatString(String command) {
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (c == '§' || c < ' ' || c == '\u007f') {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // Hover events
  // ---------------------------------------------------------------------------

  private void hoverEvent(@Nullable HoverEvent<?> event) {
    if (event == null || !writesHover(event)) {
      return;
    }
    switch (event.value()) {
      case Component text -> showText(text);
      case HoverEvent.ShowItem item -> showItem(item);
      case HoverEvent.ShowEntity entity -> showEntity(entity);
      default -> {
        out.beginObject("hoverEvent");
        out.string("action", "show_achievement");
        legacyValue(Component.text(String.valueOf(event.value())));
        out.endObject();
      }
    }
  }

  /**
   * Whether the version takes a hover event: {@code show_entity} from 1.8, {@code show_achievement}
   * up to 1.11.2, text and items in every version.
   */
  @SuppressWarnings("deprecation") // show_achievement, written up to 1.11.2
  private boolean writesHover(HoverEvent<?> event) {
    return switch (event.value()) {
      case Component _, HoverEvent.ShowItem _ -> true;
      case HoverEvent.ShowEntity _ -> features.scoresAndSelectors();
      default -> event.action() == HoverEvent.Action.SHOW_ACHIEVEMENT && features.achievements();
    };
  }

  private void showText(Component text) {
    if (features.typedEvents()) {
      out.beginObject("hover_event");
      out.string("action", "show_text");
      field("value", text);
    } else {
      out.beginObject("hoverEvent");
      out.string("action", "show_text");
      if (features.rgbFontsAndHoverContents()) {
        field("contents", text);
      } else {
        legacyValue(text);
      }
    }
    out.endObject();
  }

  /**
   * Writes an item tooltip: an SNBT item in a text {@code value} before 1.16, then {@code contents}
   * with an NBT {@code tag} string, with data {@code components} from 1.20.5, inlined from 1.21.5.
   * Item data of the other era than the version's is left out: neither converts to the other.
   */
  private void showItem(HoverEvent.ShowItem item) {
    if (!features.rgbFontsAndHoverContents()) {
      out.beginObject("hoverEvent");
      out.string("action", "show_item");
      legacyValue(Component.text(legacyItem(item)));
      out.endObject();
      return;
    }
    if (features.typedEvents()) {
      out.beginObject("hover_event");
      out.string("action", "show_item");
      item(item);
    } else {
      out.beginObject("hoverEvent");
      out.string("action", "show_item");
      out.beginObject("contents");
      item(item);
      out.endObject();
    }
    out.endObject();
  }

  /** The fields of an item stack in a tooltip, from 1.16. */
  private void item(HoverEvent.ShowItem item) {
    out.string("id", item.item().asString());
    // 1.16 to 1.20.4 leave out a count of 1; 1.20.5 always writes it.
    if (item.count() != 1 || features.itemComponents()) {
      out.integer("count", item.count());
    }
    if (features.itemComponents()) {
      dataComponents(item.dataComponents());
    } else {
      @SuppressWarnings("deprecation") // the pre-1.20.5 item NBT, as SNBT
      BinaryTagHolder nbt = item.nbt();
      if (nbt != null) {
        out.string("tag", nbt.string());
      }
    }
  }

  /**
   * Writes an item's data components as NBT values: those Adventure holds as SNBT or can convert to
   * it, and the removed ones as {@code "!id": {}}. A value that converts to nothing is left out.
   */
  private void dataComponents(Map<Key, DataComponentValue> components) {
    List<String> names = new ArrayList<>(components.size());
    List<BinaryTag> values = new ArrayList<>(components.size());
    for (Map.Entry<Key, DataComponentValue> entry : components.entrySet()) {
      Key key = entry.getKey();
      DataComponentValue value = entry.getValue();
      if (value instanceof DataComponentValue.Removed) {
        names.add("!" + key.asString());
        values.add(CompoundBinaryTag.empty());
        continue;
      }
      BinaryTag tag = dataComponent(key, value);
      if (tag != null) {
        names.add(key.asString());
        values.add(tag);
      }
    }
    if (names.isEmpty()) {
      return;
    }
    out.beginObject("components");
    for (int i = 0; i < names.size(); i++) {
      out.tag(names.get(i), values.get(i));
    }
    out.endObject();
  }

  private static @Nullable BinaryTag dataComponent(Key key, DataComponentValue value) {
    DataComponentValue.TagSerializable serializable;
    if (value instanceof DataComponentValue.TagSerializable tag) {
      serializable = tag;
    } else {
      try {
        serializable =
            DataComponentValueConverterRegistry.convert(
                DataComponentValue.TagSerializable.class, key, value);
      } catch (RuntimeException noConversion) {
        return null;
      }
    }
    return parse(serializable.asBinaryTag());
  }

  /** Parses SNBT Adventure holds, or returns {@code null} when it is not valid SNBT. */
  private static @Nullable BinaryTag parse(BinaryTagHolder holder) {
    try {
      return SNBT.asTag(holder.string());
    } catch (IOException | RuntimeException malformed) {
      return null; // Adventure's SNBT reader throws both on malformed input
    }
  }

  /**
   * Writes an entity tooltip: SNBT in a text {@code value} before 1.16 (none before 1.8), then
   * {@code contents} with the UUID as a string, as an int array from 1.20.3, inlined from 1.21.5
   * with the type as {@code id} and the UUID as {@code uuid}.
   */
  private void showEntity(HoverEvent.ShowEntity entity) {
    if (!features.rgbFontsAndHoverContents()) {
      out.beginObject("hoverEvent");
      out.string("action", "show_entity");
      legacyValue(Component.text(legacyEntity(entity)));
      out.endObject();
      return;
    }
    Component name = entity.name();
    if (features.typedEvents()) {
      out.beginObject("hover_event");
      out.string("action", "show_entity");
      out.string("id", entity.type().asString());
      out.intArray("uuid", uuidInts(entity.id()));
    } else {
      out.beginObject("hoverEvent");
      out.string("action", "show_entity");
      out.beginObject("contents");
      out.string("type", entity.type().asString());
      if (features.codecs()) {
        out.intArray("id", uuidInts(entity.id()));
      } else {
        out.string("id", entity.id().toString());
      }
    }
    if (name != null) {
      field("name", name);
    }
    if (!features.typedEvents()) {
      out.endObject(); // contents
    }
    out.endObject();
  }

  /**
   * The {@code value} of a hover event before 1.16: a component, which carries the item or the
   * entity of a tooltip as SNBT text.
   */
  private void legacyValue(Component value) {
    field("value", value);
  }

  /** The SNBT of an item stack in a legacy tooltip, which the client parses from the text. */
  private static String legacyItem(HoverEvent.ShowItem item) {
    StringBuilder snbt =
        new StringBuilder("{id:")
            .append(quoteSnbt(item.item().asString()))
            .append(",Count:")
            .append(item.count())
            .append('b');
    @SuppressWarnings("deprecation") // the pre-1.20.5 item NBT, as SNBT
    BinaryTagHolder nbt = item.nbt();
    if (nbt != null) {
      snbt.append(",tag:").append(nbt.string());
    }
    return snbt.append('}').toString();
  }

  /**
   * The SNBT of an entity in a legacy tooltip. The client shows the name as it is before 1.13, and
   * parses it as a JSON component from 1.13.
   */
  private String legacyEntity(HoverEvent.ShowEntity entity) {
    StringBuilder snbt =
        new StringBuilder("{type:")
            .append(quoteSnbt(entity.type().asString()))
            .append(",id:")
            .append(quoteSnbt(entity.id().toString()));
    Component name = entity.name();
    if (name != null) {
      String shown =
          features.componentEntityNames()
              ? ComponentCodec.json(name, features.version())
              : PlainText.of(name);
      snbt.append(",name:").append(quoteSnbt(shown));
    }
    return snbt.append('}').toString();
  }

  private static String quoteSnbt(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns the text of a component that is plain text alone as the version sees it, or {@code
   * null}: no children, no style the version writes, and text contents, or contents the version
   * replaces by text.
   */
  private @Nullable String plainText(Component component) {
    if (!component.children().isEmpty() || writesStyle(component.style())) {
      return null;
    }
    return switch (component) {
      case TextComponent text -> text.content();
      case SelectorComponent selector when !features.scoresAndSelectors() -> selector.pattern();
      case ScoreComponent score when resolved(score) != null || !features.scoresAndSelectors() -> {
        String value = resolved(score);
        yield value != null ? value : "";
      }
      case NBTComponent<?, ?> nbt when !writesNbt(nbt) -> "";
      case ObjectComponent _ when !features.objects() -> "";
      default -> null;
    };
  }

  /** Whether any part of {@code style} is written for the version. */
  private boolean writesStyle(Style style) {
    if (style.isEmpty()) {
      return false;
    }
    if (style.color() != null) {
      return true;
    }
    for (TextDecoration decoration : DECORATIONS) {
      if (style.decoration(decoration) != TextDecoration.State.NOT_SET) {
        return true;
      }
    }
    ClickEvent click = style.clickEvent();
    HoverEvent<?> hover = style.hoverEvent();
    return (style.insertion() != null && features.insertion())
        || (style.font() != null && features.rgbFontsAndHoverContents())
        || (style.shadowColor() != null && features.shadowColors())
        || (click != null && writesClick(click))
        || (hover != null && writesHover(hover));
  }

  /** A UUID as the four ints the codecs write it as, most significant first. */
  static int[] uuidInts(UUID uuid) {
    long most = uuid.getMostSignificantBits();
    long least = uuid.getLeastSignificantBits();
    return new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
  }
}
