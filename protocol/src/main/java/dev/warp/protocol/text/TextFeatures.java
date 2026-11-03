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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_11_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_9;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_8;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_9;

import dev.warp.protocol.ProtocolVersion;

/**
 * What the text components of one client version can hold, and how that version writes them.
 *
 * <p>Each method names a feature with the version that introduced it (minecraft.wiki, "Text
 * component format", History). What an older client does with a feature it lacks decides what the
 * encoder does instead: up to 1.20.2 the client reads components with Gson and skips keys it does
 * not know, but throws on a content type it does not know ("Don't know how to turn ... into a
 * Component"), which disconnects it; from 1.20.3 it reads them with codecs, and an unknown click or
 * hover action, or a malformed value, fails the whole packet. So contents a version lacks are
 * replaced by what its client would show, and events it lacks are left out.
 */
final class TextFeatures {

  private final ProtocolVersion version;

  TextFeatures(ProtocolVersion version) {
    this.version = version;
  }

  /** The client version these features are those of. */
  ProtocolVersion version() {
    return version;
  }

  /**
   * 1.20.3: components are written by codecs. Fields follow the codec's order (contents, {@code
   * extra}, style) rather than the Gson serializer's (style, {@code extra}, contents), show-entity
   * UUIDs become int arrays, and the configuration and play states carry components as NBT.
   */
  boolean codecs() {
    return version.isAtLeast(MINECRAFT_1_20_3);
  }

  /**
   * Whether a component of plain text alone, without style or children, is written as a bare
   * string: up to 1.8.9, as the Gson serializer did before 1.9 rewrote components, and again from
   * 1.20.3, as the codecs do. Either form reads the same in every version.
   */
  boolean compactText() {
    return version.isOlderThan(MINECRAFT_1_9) || codecs();
  }

  /**
   * Whether a translation argument of plain text is written as a bare string up to 1.19.2: the Gson
   * deserializer turned such arguments into strings, and the serializer wrote them back so.
   */
  boolean plainArgumentsAsText() {
    return version.isOlderThan(MINECRAFT_1_19_3);
  }

  /** 1.8: the {@code insertion} style field (shift-click). */
  boolean insertion() {
    return version.isAtLeast(MINECRAFT_1_8);
  }

  /**
   * 1.8: {@code score} and {@code selector} contents, the {@code show_entity} hover and the {@code
   * change_page} click actions.
   */
  boolean scoresAndSelectors() {
    return version.isAtLeast(MINECRAFT_1_8);
  }

  /** Up to 1.11.2: the {@code show_achievement} hover action, removed with achievements in 1.12. */
  boolean achievements() {
    return version.isAtMost(MINECRAFT_1_11_1);
  }

  /** 1.12: {@code keybind} contents. */
  boolean keybinds() {
    return version.isAtLeast(MINECRAFT_1_12);
  }

  /**
   * 1.13: an entity's name in a legacy show-entity hover is a JSON component, plain text before.
   */
  boolean componentEntityNames() {
    return version.isAtLeast(MINECRAFT_1_13);
  }

  /** 1.14: {@code nbt} contents from a block or an entity. */
  boolean nbtContents() {
    return version.isAtLeast(MINECRAFT_1_14);
  }

  /**
   * 1.15: {@code nbt} contents from a command storage, and the {@code copy_to_clipboard} action.
   */
  boolean storageAndClipboard() {
    return version.isAtLeast(MINECRAFT_1_15);
  }

  /**
   * 1.16: RGB colours, the {@code font} style field, and hover events carrying typed {@code
   * contents} instead of a component {@code value}.
   */
  boolean rgbFontsAndHoverContents() {
    return version.isAtLeast(MINECRAFT_1_16);
  }

  /** 1.17: the {@code separator} of {@code selector} and {@code nbt} contents. */
  boolean separators() {
    return version.isAtLeast(MINECRAFT_1_17);
  }

  /** 1.19.4: the {@code fallback} of translatable contents. */
  boolean translationFallbacks() {
    return version.isAtLeast(MINECRAFT_1_19_4);
  }

  /** 1.20.5: items in show-item hovers carry data components rather than an NBT tag. */
  boolean itemComponents() {
    return version.isAtLeast(MINECRAFT_1_20_5);
  }

  /** 1.21.4: the {@code shadow_color} style field. */
  boolean shadowColors() {
    return version.isAtLeast(MINECRAFT_1_21_4);
  }

  /**
   * 1.21.5: {@code click_event} and {@code hover_event} in snake case, each action with typed
   * fields of its own ({@code url}, {@code command}, {@code page}), hover contents inlined. Click
   * events are also validated: an {@code open_url} that is not an http(s) URI, a command with a
   * character chat refuses, or a page below 1 fails the whole component.
   */
  boolean typedEvents() {
    return version.isAtLeast(MINECRAFT_1_21_5);
  }

  /** 1.21.6: the {@code custom} click action. */
  boolean customClicks() {
    return version.isAtLeast(MINECRAFT_1_21_6);
  }

  /** 1.21.9: {@code object} contents, atlas sprites and player heads. */
  boolean objects() {
    return version.isAtLeast(MINECRAFT_1_21_9);
  }

  /**
   * Up to 1.13.2 the JSON serializer escapes HTML characters ({@code < > & = '}) as Gson does by
   * default; from 1.14 it writes them as they are. Either reads the same.
   */
  boolean htmlSafeJson() {
    return version.isOlderThan(MINECRAFT_1_14);
  }

  /**
   * Returns the longest JSON component, in UTF-16 code units, a client reads in the configuration
   * and play states: 32 767 until 1.13.2, 262 144 from 1.14.
   */
  int maxJsonLength() {
    return version.isAtLeast(MINECRAFT_1_14) ? 262_144 : 32_767;
  }
}
