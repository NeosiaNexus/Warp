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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McNbt;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;

/**
 * Encodes Adventure components exactly as the vanilla client of each version reads them.
 *
 * <ul>
 *   <li><b>1.7 to 1.20.2</b>, and the <b>login state of every version</b>: JSON text. In the
 *       configuration and play states it is a VarInt-prefixed string of at most 32 767 UTF-16
 *       units, 262 144 from 1.14; login disconnects carry their own limit.
 *   <li><b>1.20.3+</b>, configuration and play states: network NBT, a nameless root tag. A
 *       component of plain text alone is a string tag.
 * </ul>
 *
 * <p>Each version gets the fields, value shapes and contents its client knows, from one component:
 * RGB colours become the nearest named colour before 1.16, hover events carry a {@code value}
 * before 1.16 and typed {@code contents} after, click and hover events are snake case with typed
 * fields from 1.21.5, a shadow colour is only sent from 1.21.4, a translation fallback from 1.19.4.
 * Contents a client cannot read (a keybind before 1.12, an object before 1.21.9) are replaced by
 * what it can show, and events it would refuse are left out: a component never makes a client fail
 * the packet that carries it. {@link ComponentWriter} has the details.
 *
 * <p>Encoding writes the JSON or NBT directly from the component tree, with no intermediate JSON or
 * NBT objects, and is safe from any thread.
 */
public final class ComponentCodec {

  private ComponentCodec() {}

  /**
   * Encodes a component as a client of {@code version} reads it in the configuration and play
   * states.
   *
   * @param component the component
   * @param version the client's protocol version
   * @return the complete field as on the wire: a VarInt-prefixed JSON string before 1.20.3, an NBT
   *     tag from 1.20.3
   * @throws IllegalArgumentException if the component is too large for the client: JSON longer than
   *     the version reads, an NBT string over 65 535 bytes, or nesting deeper than 512 levels
   */
  public static byte[] encode(Component component, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      write(buf, component, version);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /**
   * Writes a component into {@code buf} as a client of {@code version} reads it in the
   * configuration and play states. Nothing is written when it is too large.
   *
   * @param buf the buffer to write to
   * @param component the component
   * @param version the client's protocol version
   * @throws IllegalArgumentException if the component is too large for the client (see {@link
   *     #encode})
   */
  public static void write(ByteBuf buf, Component component, ProtocolVersion version) {
    TextFeatures features = new TextFeatures(version);
    if (features.codecs()) {
      int start = buf.writerIndex();
      try {
        new ComponentWriter(features, new NbtTree(buf)).write(component);
      } catch (RuntimeException tooLarge) {
        buf.writerIndex(start);
        throw tooLarge;
      }
    } else {
      McString.write(buf, jsonText(component, features), features.maxJsonLength());
    }
  }

  /**
   * Reads one encoded component in the configuration and play states and returns its exact bytes,
   * without decoding it: a VarInt-prefixed JSON string before 1.20.3, a nameless NBT tag from.
   *
   * @param buf the buffer to read from
   * @param version the protocol version of the connection
   * @return the component as on the wire, ready to be written back
   * @throws io.netty.handler.codec.DecoderException if the component is malformed or truncated
   */
  public static byte[] readRaw(ByteBuf buf, ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
      return McNbt.readNameless(buf);
    }
    int start = buf.readerIndex();
    McString.skip(buf);
    byte[] raw = new byte[buf.readerIndex() - start];
    buf.getBytes(start, raw);
    return raw;
  }

  /**
   * Returns a component as the JSON text a client of {@code version} reads: the form of login
   * disconnects in every version, and of every component before 1.20.3.
   *
   * @param component the component
   * @param version the client's protocol version
   * @return the JSON text
   */
  public static String json(Component component, ProtocolVersion version) {
    return jsonText(component, new TextFeatures(version));
  }

  // ---------------------------------------------------------------------------
  // Decoding
  // ---------------------------------------------------------------------------

  /**
   * Reads a component a backend sent, as it is on the wire in {@code state}: a VarInt-prefixed JSON
   * string in the login state of every version and before 1.20.3, a nameless NBT tag in the
   * configuration and play states from 1.20.3. Never throws: a component that cannot be read whole
   * is replaced by the text that could be read of it, and an empty one (no bytes, an empty string,
   * a lone {@code TAG_End}, empty text) reads as {@link Component#empty()}, so that it tells apart
   * from a real one (see {@link ComponentDecoder}).
   *
   * @param buf the buffer to read from, advanced past the component
   * @param version the protocol version of the connection
   * @param state the state the component was sent in
   * @return the component
   */
  public static Component decode(ByteBuf buf, ProtocolVersion version, ProtocolState state) {
    if (state != ProtocolState.LOGIN && version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
      return ComponentDecoder.nbt(NbtReader.readNameless(buf));
    }
    return ComponentDecoder.json(readJson(buf));
  }

  /**
   * Reads a component a backend sent, as it is on the wire in {@code state} (see {@link
   * #decode(ByteBuf, ProtocolVersion, ProtocolState)}). Never throws.
   *
   * @param raw the encoded component, such as the reason of a disconnect packet
   * @param version the protocol version of the connection
   * @param state the state the component was sent in
   * @return the component
   */
  public static Component decode(byte[] raw, ProtocolVersion version, ProtocolState state) {
    return decode(Unpooled.wrappedBuffer(raw), version, state);
  }

  /**
   * Reads a JSON component, in the form of any version. Never throws: text that is not JSON reads
   * as itself, an empty string as {@link Component#empty()}.
   *
   * @param json the JSON text
   * @return the component
   */
  public static Component decodeJson(String json) {
    return ComponentDecoder.json(json);
  }

  /**
   * Reads a VarInt-prefixed string as far as it goes: a length the buffer does not hold reads the
   * bytes that are there.
   */
  private static String readJson(ByteBuf buf) {
    if (!buf.isReadable()) {
      return "";
    }
    int start = buf.readerIndex();
    int length;
    try {
      length = VarInt.read(buf);
    } catch (RuntimeException malformed) {
      buf.readerIndex(start);
      length = buf.readableBytes();
    }
    if (length < 0 || length > buf.readableBytes()) {
      length = buf.readableBytes();
    }
    return buf.readCharSequence(length, StandardCharsets.UTF_8).toString();
  }

  /**
   * Returns the text a 1.8 to 1.10 client shows with its formatting in the action bar: those
   * clients drop the styles of an action bar sent in a chat packet and only show section sign codes
   * written into its text (see {@link LegacyOverlay}).
   *
   * @param component the action bar text
   * @return an unstyled component carrying its formatting as section sign codes
   */
  public static Component legacyActionBar(Component component) {
    return LegacyOverlay.flatten(component);
  }

  private static String jsonText(Component component, TextFeatures features) {
    JsonTree tree = new JsonTree(features.htmlSafeJson());
    new ComponentWriter(features, tree).write(component);
    return tree.text();
  }
}
