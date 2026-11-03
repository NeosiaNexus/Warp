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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McByteArray;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.command.ArgumentParser.DoubleParser;
import dev.warp.protocol.command.ArgumentParser.EntityParser;
import dev.warp.protocol.command.ArgumentParser.FloatParser;
import dev.warp.protocol.command.ArgumentParser.ForgeEnumParser;
import dev.warp.protocol.command.ArgumentParser.IntegerParser;
import dev.warp.protocol.command.ArgumentParser.LongParser;
import dev.warp.protocol.command.ArgumentParser.PlainParser;
import dev.warp.protocol.command.ArgumentParser.ResourceParser;
import dev.warp.protocol.command.ArgumentParser.ScoreHolderParser;
import dev.warp.protocol.command.ArgumentParser.StringMode;
import dev.warp.protocol.command.ArgumentParser.StringParser;
import dev.warp.protocol.command.ArgumentParser.TimeParser;
import dev.warp.protocol.command.ArgumentParser.WrappedParser;
import dev.warp.protocol.command.ArgumentTypes.Format;
import dev.warp.protocol.command.ArgumentTypes.Table;
import dev.warp.protocol.command.CommandNode.Argument;
import dev.warp.protocol.command.CommandNode.Literal;
import dev.warp.protocol.command.CommandNode.Root;
import dev.warp.protocol.command.CommandTreeDecoding.Decoded;
import dev.warp.protocol.command.CommandTreeDecoding.Opaque;

import java.util.OptionalInt;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes the payload of the commands packet (clientbound, PLAY, 1.13+): the node count,
 * the nodes, and the root's index.
 *
 * <p>The node format, for every version:
 *
 * <pre>
 * flags         byte    0x03 type (0 root, 1 literal, 2 argument), 0x04 executable,
 *                       0x08 redirect, 0x10 suggestions (arguments), 0x20 restricted (1.21.6+)
 * children      VarInt count, then a VarInt index each
 * redirect      VarInt index, if flagged
 * name          String, literals and arguments
 * parser        String identifier (1.13 to 1.18.2), VarInt registry id (1.19+), arguments
 * properties    as the parser's type says, arguments
 * suggestions   String identifier, if flagged
 * </pre>
 *
 * <p>Decoding is exact or nothing: whatever decodes encodes back to the same bytes (given the
 * shortest VarInts and valid UTF-8 every server writes; otherwise to the same tree), and whatever
 * Warp cannot represent exactly (an argument type it does not know, a flag it does not know) makes
 * the whole packet {@link Opaque}, for the caller to forward untouched. Only a packet the vanilla
 * client would refuse as well (truncated, an index out of range, children or redirects looping)
 * fails, with the {@link DecoderException} every decoder throws. Whatever decodes also encodes
 * again at the same version: a vanilla argument type the version does not have is opaque.
 */
public final class CommandTreeCodec {

  /** The first version with a commands packet: 1.13. */
  public static final ProtocolVersion FIRST = ProtocolVersion.MINECRAFT_1_13;

  /** The first version whose clients read the restricted flag (0x20): 1.21.6. */
  public static final ProtocolVersion RESTRICTED_SINCE = ProtocolVersion.MINECRAFT_1_21_6;

  private static final int TYPE_MASK = 0x03;
  private static final int TYPE_ROOT = 0;
  private static final int TYPE_LITERAL = 1;
  private static final int TYPE_ARGUMENT = 2;
  private static final int FLAG_EXECUTABLE = 0x04;
  private static final int FLAG_REDIRECT = 0x08;
  private static final int FLAG_SUGGESTIONS = 0x10;
  private static final int FLAG_RESTRICTED = 0x20;

  /** The fewest bytes a node takes: its flags and an empty child count. */
  private static final int MIN_NODE_BYTES = 2;

  private static final int NUMBER_MIN = 0x01;
  private static final int NUMBER_MAX = 0x02;
  private static final int ENTITY_SINGLE = 0x01;
  private static final int ENTITY_PLAYERS = 0x02;
  private static final int SCORE_HOLDER_MULTIPLE = 0x01;

  private CommandTreeCodec() {}

  // ---------------------------------------------------------------------------
  // Decoding
  // ---------------------------------------------------------------------------

  /**
   * Decodes the payload of a commands packet, from its reader index to its writer index. The
   * buffer's indices do not move: on an opaque result, the caller forwards it as it is.
   *
   * @param payload the packet's bytes after its id
   * @param version the protocol version of the connection
   * @return the tree, or why the packet is left opaque
   * @throws DecoderException if the packet is malformed: one the vanilla client refuses too
   * @throws IllegalArgumentException if the version has no commands packet (before 1.13)
   */
  public static CommandTreeDecoding decode(ByteBuf payload, ProtocolVersion version) {
    Reader reader = new Reader(payload.duplicate(), version, ArgumentTypes.table(version));
    try {
      return reader.read();
    } catch (Unrepresentable e) {
      return new Opaque(version, reader.node, String.valueOf(e.getMessage()));
    } catch (IndexOutOfBoundsException e) {
      throw new DecoderException("Truncated commands packet", e);
    }
  }

  /**
   * Thrown within a decoding at the first thing Warp cannot represent exactly; caught by {@link
   * #decode}, never further. Rare (a modded server's tree), so it may cost an allocation.
   */
  private static final class Unrepresentable extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    Unrepresentable(String reason) {
      super(reason, null, false, false);
    }
  }

  /** One decoding: the buffer, the version's argument types, and the node being read. */
  private static final class Reader {

    private final ByteBuf buf;
    private final ProtocolVersion version;
    private final Table table;
    private final int knownFlags;

    /** The index of the node being read. */
    private int node;

    Reader(ByteBuf buf, ProtocolVersion version, Table table) {
      this.buf = buf;
      this.version = version;
      this.table = table;
      int flags = TYPE_MASK | FLAG_EXECUTABLE | FLAG_REDIRECT | FLAG_SUGGESTIONS;
      this.knownFlags = version.isAtLeast(RESTRICTED_SINCE) ? flags | FLAG_RESTRICTED : flags;
    }

    CommandTreeDecoding read() {
      int count = VarInt.readCount(buf, Integer.MAX_VALUE, MIN_NODE_BYTES, "command node");
      CommandNode[] nodes = new CommandNode[count];
      for (node = 0; node < count; node++) {
        nodes[node] = readNode();
      }
      int root = VarInt.read(buf);
      if (buf.isReadable()) {
        throw new DecoderException(
            "Commands packet has " + buf.readableBytes() + " bytes after the root index");
      }
      String invalid = CommandTree.invalid(nodes, root);
      if (invalid != null) {
        throw new DecoderException("Impossible command tree: " + invalid);
      }
      return new Decoded(CommandTree.trusted(nodes, root));
    }

    private CommandNode readNode() {
      int flags = buf.readUnsignedByte();
      if ((flags & ~knownFlags) != 0) {
        throw new Unrepresentable("unknown flags 0x" + Integer.toHexString(flags & ~knownFlags));
      }
      int type = flags & TYPE_MASK;
      if (type == TYPE_MASK) {
        throw new Unrepresentable("node type 3"); // which the vanilla client builds as a root
      }
      if (type == TYPE_ROOT && flags != TYPE_ROOT) {
        // Ignored by the client on a root, and a root cannot redirect.
        throw new Unrepresentable("a root with flags 0x" + Integer.toHexString(flags));
      }
      if (type == TYPE_LITERAL && (flags & FLAG_SUGGESTIONS) != 0) {
        throw new Unrepresentable("a literal with suggestions"); // ignored by the client
      }
      Children children = readChildren();
      if (type == TYPE_ROOT) {
        return new Root(children);
      }
      OptionalInt redirect =
          (flags & FLAG_REDIRECT) != 0 ? OptionalInt.of(readIndex()) : OptionalInt.empty();
      boolean executable = (flags & FLAG_EXECUTABLE) != 0;
      boolean restricted = (flags & FLAG_RESTRICTED) != 0;
      String name = McString.read(buf);
      if (type == TYPE_LITERAL) {
        return new Literal(name, children, redirect, executable, restricted);
      }
      ArgumentParser parser = readParser();
      String suggestions = (flags & FLAG_SUGGESTIONS) != 0 ? McString.read(buf) : null;
      return new Argument(name, parser, suggestions, children, redirect, executable, restricted);
    }

    private Children readChildren() {
      int count = VarInt.readCount(buf, Integer.MAX_VALUE, 1, "command node child");
      int[] children = new int[count];
      for (int i = 0; i < count; i++) {
        children[i] = readIndex();
      }
      return Children.wrap(children);
    }

    private int readIndex() {
      int index = VarInt.read(buf);
      if (index < 0) {
        throw new DecoderException("Negative command node index: " + index);
      }
      return index;
    }

    private ArgumentParser readParser() {
      if (!table.usesIds()) {
        return readLegacyParser();
      }
      int id = VarInt.read(buf);
      if (id == WrappedParser.REGISTRY_ID) {
        int wrapped = readIndex();
        return new WrappedParser(null, wrapped, McByteArray.read(buf));
      }
      if (id < 0 || id >= table.size()) {
        throw new Unrepresentable(
            "argument type #"
                + id
                + ", none of the "
                + table.size()
                + " vanilla argument types of "
                + version.name());
      }
      return readProperties(table.identifier(id), table.format(id));
    }

    private ArgumentParser readLegacyParser() {
      String identifier = McString.read(buf);
      if (identifier.equals(WrappedParser.IDENTIFIER)) {
        return new WrappedParser(McString.read(buf), -1, McByteArray.read(buf));
      }
      Format format = ArgumentTypes.legacyFormat(identifier);
      if (format == null) {
        throw new Unrepresentable("argument type " + identifier + ", not a vanilla or Forge one");
      }
      if (ArgumentTypes.isVanilla(identifier) && !table.contains(identifier)) {
        // Whatever decodes must encode again, and the encoder refuses it for this version.
        throw new Unrepresentable(
            "argument type " + identifier + ", which " + version.name() + " clients do not know");
      }
      return readProperties(identifier, format);
    }

    private ArgumentParser readProperties(String identifier, Format format) {
      return switch (format) {
        case NONE -> ArgumentTypes.plain(identifier);
        case FLOAT -> {
          int flags = numberFlags(identifier);
          Float min = (flags & NUMBER_MIN) != 0 ? buf.readFloat() : null;
          yield new FloatParser(min, (flags & NUMBER_MAX) != 0 ? buf.readFloat() : null);
        }
        case DOUBLE -> {
          int flags = numberFlags(identifier);
          Double min = (flags & NUMBER_MIN) != 0 ? buf.readDouble() : null;
          yield new DoubleParser(min, (flags & NUMBER_MAX) != 0 ? buf.readDouble() : null);
        }
        case INTEGER -> {
          int flags = numberFlags(identifier);
          Integer min = (flags & NUMBER_MIN) != 0 ? buf.readInt() : null;
          yield new IntegerParser(min, (flags & NUMBER_MAX) != 0 ? buf.readInt() : null);
        }
        case LONG -> {
          int flags = numberFlags(identifier);
          Long min = (flags & NUMBER_MIN) != 0 ? buf.readLong() : null;
          yield new LongParser(min, (flags & NUMBER_MAX) != 0 ? buf.readLong() : null);
        }
        case STRING ->
            StringParser.of(
                switch (VarInt.read(buf)) {
                  case 0 -> StringMode.SINGLE_WORD;
                  case 1 -> StringMode.QUOTABLE_PHRASE;
                  case 2 -> StringMode.GREEDY_PHRASE;
                  default -> throw new DecoderException("Unknown brigadier:string mode");
                });
        case ENTITY -> EntityParser.of(flags(identifier, ENTITY_SINGLE | ENTITY_PLAYERS));
        case SCORE_HOLDER -> ScoreHolderParser.of(flags(identifier, SCORE_HOLDER_MULTIPLE) != 0);
        case TIME ->
            new TimeParser(version.isAtLeast(ArgumentTypes.TIME_MINIMUM_SINCE) ? buf.readInt() : 0);
        case RESOURCE ->
            new ResourceParser(ArgumentTypes.resourceKind(identifier), McString.read(buf));
        case FORGE_ENUM -> new ForgeEnumParser(McString.read(buf));
      };
    }

    /** The min/max flags of a Brigadier number. */
    private int numberFlags(String identifier) {
      return flags(identifier, NUMBER_MIN | NUMBER_MAX);
    }

    /** A flags byte, which may only hold {@code known} flags. */
    private int flags(String identifier, int known) {
      int flags = buf.readUnsignedByte();
      if ((flags & ~known) != 0) {
        throw new Unrepresentable(identifier + " flags 0x" + Integer.toHexString(flags));
      }
      return flags;
    }
  }

  // ---------------------------------------------------------------------------
  // Encoding
  // ---------------------------------------------------------------------------

  /**
   * Encodes a tree as the payload of a commands packet, at the buffer's writer index.
   *
   * <p>What a version's clients do not read is left out: the restricted flag before 1.21.6, the
   * minimum of {@code minecraft:time} before 1.19.4.
   *
   * @param tree the tree
   * @param out the buffer to write to
   * @param version the protocol version of the connection
   * @throws IllegalArgumentException if a parser is not one the version's clients know (a vanilla
   *     type added later, or a type with no registry id at that version), or the version has no
   *     commands packet
   */
  public static void encode(CommandTree tree, ByteBuf out, ProtocolVersion version) {
    Table table = ArgumentTypes.table(version);
    boolean restrictedFlag = version.isAtLeast(RESTRICTED_SINCE);
    VarInt.write(out, tree.size());
    for (int i = 0; i < tree.size(); i++) {
      CommandNode node = tree.node(i);
      int flags =
          switch (node) {
            case Root _ -> TYPE_ROOT;
            case Literal _ -> TYPE_LITERAL;
            case Argument argument ->
                argument.suggestions() != null ? TYPE_ARGUMENT | FLAG_SUGGESTIONS : TYPE_ARGUMENT;
          };
      if (node.executable()) {
        flags |= FLAG_EXECUTABLE;
      }
      if (node.redirect().isPresent()) {
        flags |= FLAG_REDIRECT;
      }
      if (restrictedFlag && node.restricted()) {
        flags |= FLAG_RESTRICTED;
      }
      out.writeByte(flags);
      Children children = node.children();
      VarInt.write(out, children.size());
      for (int c = 0; c < children.size(); c++) {
        VarInt.write(out, children.get(c));
      }
      if (node.redirect().isPresent()) {
        VarInt.write(out, node.redirect().getAsInt());
      }
      switch (node) {
        case Root _ -> {}
        case Literal literal -> McString.write(out, literal.name());
        case Argument argument -> {
          McString.write(out, argument.name());
          writeParser(argument.parser(), out, version, table);
          String suggestions = argument.suggestions();
          if (suggestions != null) {
            McString.write(out, suggestions);
          }
        }
      }
    }
    VarInt.write(out, tree.rootIndex());
  }

  private static void writeParser(
      ArgumentParser parser, ByteBuf out, ProtocolVersion version, Table table) {
    String identifier = parser.identifier();
    if (parser instanceof WrappedParser wrapped) {
      // Unwrapped: the client reads the type as the backend registered it.
      String wrappedIdentifier = wrapped.wrappedIdentifier();
      if (table.usesIds() == (wrappedIdentifier != null)) {
        throw new IllegalArgumentException(
            identifier + " is wrapped for another protocol era than " + version.name());
      }
      if (wrappedIdentifier != null) {
        McString.write(out, wrappedIdentifier);
      } else {
        VarInt.write(out, wrapped.wrappedId());
      }
    } else if (table.usesIds()) {
      int id = table.id(identifier);
      if (id < 0) {
        throw new IllegalArgumentException(
            identifier + " is not an argument type of " + version.name());
      }
      VarInt.write(out, id);
    } else {
      if (ArgumentTypes.isVanilla(identifier) && !table.contains(identifier)) {
        throw new IllegalArgumentException(
            identifier + " is not an argument type of " + version.name());
      }
      McString.write(out, identifier);
    }
    switch (parser) {
      case PlainParser _ -> {}
      case WrappedParser p -> out.writeBytes(p.propertiesUnsafe());
      case FloatParser p -> {
        out.writeByte(numberFlags(p.min(), p.max()));
        if (p.min() != null) {
          out.writeFloat(p.min());
        }
        if (p.max() != null) {
          out.writeFloat(p.max());
        }
      }
      case DoubleParser p -> {
        out.writeByte(numberFlags(p.min(), p.max()));
        if (p.min() != null) {
          out.writeDouble(p.min());
        }
        if (p.max() != null) {
          out.writeDouble(p.max());
        }
      }
      case IntegerParser p -> {
        out.writeByte(numberFlags(p.min(), p.max()));
        if (p.min() != null) {
          out.writeInt(p.min());
        }
        if (p.max() != null) {
          out.writeInt(p.max());
        }
      }
      case LongParser p -> {
        out.writeByte(numberFlags(p.min(), p.max()));
        if (p.min() != null) {
          out.writeLong(p.min());
        }
        if (p.max() != null) {
          out.writeLong(p.max());
        }
      }
      case StringParser p ->
          VarInt.write(
              out,
              switch (p.mode()) {
                case SINGLE_WORD -> 0;
                case QUOTABLE_PHRASE -> 1;
                case GREEDY_PHRASE -> 2;
              });
      case EntityParser p ->
          out.writeByte((p.single() ? ENTITY_SINGLE : 0) | (p.playersOnly() ? ENTITY_PLAYERS : 0));
      case ScoreHolderParser p -> out.writeByte(p.multiple() ? SCORE_HOLDER_MULTIPLE : 0);
      case TimeParser p -> {
        if (version.isAtLeast(ArgumentTypes.TIME_MINIMUM_SINCE)) {
          out.writeInt(p.minimum());
        }
      }
      case ResourceParser p -> McString.write(out, p.registry());
      case ForgeEnumParser p -> McString.write(out, p.enumClass());
    }
  }

  private static int numberFlags(@Nullable Object min, @Nullable Object max) {
    return (min != null ? NUMBER_MIN : 0) | (max != null ? NUMBER_MAX : 0);
  }
}
