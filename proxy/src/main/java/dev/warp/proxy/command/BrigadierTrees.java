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

import dev.warp.api.command.CommandSource;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.command.ArgumentParser;
import dev.warp.protocol.command.ArgumentTypes;
import dev.warp.protocol.command.CommandTree;

import java.util.IdentityHashMap;
import java.util.Map;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

/**
 * Turns the proxy's Brigadier commands into the protocol's command tree, as one player may see
 * them: nodes it cannot use are left out, and argument types its client does not know become free
 * text the proxy completes.
 */
final class BrigadierTrees {

  /** Suggestions the client asks the proxy for. */
  private static final String ASK_SERVER = "minecraft:ask_server";

  private BrigadierTrees() {}

  /**
   * Builds the tree of the proxy's commands a source may use.
   *
   * @param root the proxy's root
   * @param source the player
   * @param version the player's protocol version
   * @return the tree, ready to merge into a backend's
   */
  static CommandTree treeFor(
      RootCommandNode<CommandSource> root, CommandSource source, ProtocolVersion version) {
    CommandTree.Builder builder = CommandTree.builder();
    Map<CommandNode<CommandSource>, Integer> indices = new IdentityHashMap<>();
    indices.put(root, builder.root());
    for (CommandNode<CommandSource> child : root.getChildren()) {
      add(builder, indices, builder.root(), child, source, version);
    }
    // Redirects once every node has an index.
    for (var entry : indices.entrySet()) {
      CommandNode<CommandSource> target = entry.getKey().getRedirect();
      Integer to = target == null ? null : indices.get(target);
      if (to != null) {
        builder.redirect(entry.getValue(), to);
      }
    }
    return builder.build();
  }

  private static void add(
      CommandTree.Builder builder,
      Map<CommandNode<CommandSource>, Integer> indices,
      int parent,
      CommandNode<CommandSource> node,
      CommandSource source,
      ProtocolVersion version) {
    if (!node.canUse(source)) {
      return;
    }
    Integer known = indices.get(node);
    if (known != null) {
      builder.child(parent, known);
      return;
    }
    int index;
    switch (node) {
      case LiteralCommandNode<CommandSource> literal ->
          index = builder.literal(parent, literal.getLiteral());
      case ArgumentCommandNode<CommandSource, ?> argument -> {
        ArgumentParser parser = parser(argument.getType(), version);
        index = builder.argument(parent, argument.getName(), parser);
        if (argument.getCustomSuggestions() != null || isFallback(argument.getType(), parser)) {
          builder.suggestions(index, ASK_SERVER);
        }
      }
      default -> {
        return;
      }
    }
    indices.put(node, index);
    if (node.getCommand() != null) {
      builder.executable(index);
    }
    for (CommandNode<CommandSource> child : node.getChildren()) {
      add(builder, indices, index, child, source, version);
    }
  }

  /** The protocol parser of a Brigadier argument type, or free text when the client lacks it. */
  static ArgumentParser parser(ArgumentType<?> type, ProtocolVersion version) {
    ArgumentParser parser =
        switch (type) {
          case StringArgumentType string ->
              new ArgumentParser.StringParser(
                  switch (string.getType()) {
                    case SINGLE_WORD -> ArgumentParser.StringMode.SINGLE_WORD;
                    case QUOTABLE_PHRASE -> ArgumentParser.StringMode.QUOTABLE_PHRASE;
                    case GREEDY_PHRASE -> ArgumentParser.StringMode.GREEDY_PHRASE;
                  });
          case IntegerArgumentType integer ->
              new ArgumentParser.IntegerParser(
                  integer.getMinimum() == Integer.MIN_VALUE ? null : integer.getMinimum(),
                  integer.getMaximum() == Integer.MAX_VALUE ? null : integer.getMaximum());
          case LongArgumentType longType ->
              new ArgumentParser.LongParser(
                  longType.getMinimum() == Long.MIN_VALUE ? null : longType.getMinimum(),
                  longType.getMaximum() == Long.MAX_VALUE ? null : longType.getMaximum());
          case FloatArgumentType floatType ->
              new ArgumentParser.FloatParser(
                  floatType.getMinimum() == -Float.MAX_VALUE ? null : floatType.getMinimum(),
                  floatType.getMaximum() == Float.MAX_VALUE ? null : floatType.getMaximum());
          case DoubleArgumentType doubleType ->
              new ArgumentParser.DoubleParser(
                  doubleType.getMinimum() == -Double.MAX_VALUE ? null : doubleType.getMinimum(),
                  doubleType.getMaximum() == Double.MAX_VALUE ? null : doubleType.getMaximum());
          case BoolArgumentType _ -> new ArgumentParser.PlainParser("brigadier:bool");
          default -> FREE_TEXT;
        };
    return ArgumentTypes.isAvailable(parser.identifier(), version) ? parser : FREE_TEXT;
  }

  private static boolean isFallback(ArgumentType<?> type, ArgumentParser parser) {
    return parser == FREE_TEXT && !(type instanceof StringArgumentType);
  }

  /** What a client sees for an argument type it does not know: a word the proxy completes. */
  private static final ArgumentParser FREE_TEXT =
      new ArgumentParser.StringParser(ArgumentParser.StringMode.GREEDY_PHRASE);
}
