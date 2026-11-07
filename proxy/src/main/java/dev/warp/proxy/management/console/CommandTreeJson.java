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
package dev.warp.proxy.management.console;

import dev.warp.api.command.CommandSource;

import java.util.Collection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

/**
 * The commands a remote client may run, as JSON: the client rebuilds the tree, so that it
 * highlights, hints and completes command names as the local console does, without a round trip per
 * key. Arguments keep their Brigadier type when it is one of Brigadier's own; any other type is
 * sent as a token, everything up to the next space (or the rest of the line, at the end of a
 * branch), and its completions come from the server. Clients that do not know tokens read them as
 * words.
 *
 * <p>Each node is {@code {"literal": name}} or {@code {"argument": name, "type": ...}}, with {@code
 * "executes": true} when the command can end there, and its {@code "children"}. A node that
 * redirects (Brigadier's forks) is sent as taking the rest of the line.
 */
final class CommandTreeJson {

  /** Deeper trees than this are cut: no real command nests so deep, and the answer stays small. */
  private static final int MAX_DEPTH = 32;

  private CommandTreeJson() {}

  /**
   * Describes the commands a source may use.
   *
   * @param root the dispatcher's root
   * @param source who will run them
   * @return {@code {"commands": [...]}}
   */
  static JsonObject of(CommandNode<CommandSource> root, CommandSource source) {
    JsonObject json = new JsonObject();
    json.add("commands", children(root.getChildren(), source, 0));
    return json;
  }

  private static JsonArray children(
      Collection<CommandNode<CommandSource>> nodes, CommandSource source, int depth) {
    JsonArray array = new JsonArray();
    if (depth >= MAX_DEPTH) {
      return array;
    }
    for (CommandNode<CommandSource> node : nodes) {
      if (node.canUse(source)) {
        array.add(node(node, source, depth));
      }
    }
    return array;
  }

  private static JsonObject node(CommandNode<CommandSource> node, CommandSource source, int depth) {
    JsonObject json = new JsonObject();
    boolean redirects = node.getRedirect() != null;
    if (node instanceof LiteralCommandNode<CommandSource> literal) {
      json.addProperty("literal", literal.getLiteral());
    } else if (node instanceof ArgumentCommandNode<CommandSource, ?> argument) {
      json.addProperty("argument", argument.getName());
      type(argument.getType(), node.getChildren().isEmpty() || redirects, json);
    } else {
      json.addProperty("literal", node.getName());
    }
    if (node.getCommand() != null || redirects) {
      json.addProperty("executes", true);
    }
    if (redirects) {
      // A fork: whatever follows is the server's to parse.
      JsonObject rest = new JsonObject();
      rest.addProperty("argument", "arguments");
      rest.addProperty("type", "greedy");
      rest.addProperty("executes", true);
      JsonArray children = new JsonArray();
      children.add(rest);
      json.add("children", children);
    } else if (!node.getChildren().isEmpty()) {
      json.add("children", children(node.getChildren(), source, depth + 1));
    }
    return json;
  }

  private static void type(ArgumentType<?> type, boolean last, JsonObject json) {
    switch (type) {
      case StringArgumentType string ->
          json.addProperty(
              "type",
              switch (string.getType()) {
                case SINGLE_WORD -> "word";
                case QUOTABLE_PHRASE -> "phrase";
                case GREEDY_PHRASE -> "greedy";
              });
      case BoolArgumentType _ -> json.addProperty("type", "bool");
      case IntegerArgumentType integer -> {
        json.addProperty("type", "integer");
        json.addProperty("min", integer.getMinimum());
        json.addProperty("max", integer.getMaximum());
      }
      case LongArgumentType number -> {
        json.addProperty("type", "long");
        json.addProperty("min", number.getMinimum());
        json.addProperty("max", number.getMaximum());
      }
      case FloatArgumentType number -> {
        json.addProperty("type", "float");
        json.addProperty("min", number.getMinimum());
        json.addProperty("max", number.getMaximum());
      }
      case DoubleArgumentType number -> {
        json.addProperty("type", "double");
        json.addProperty("min", number.getMinimum());
        json.addProperty("max", number.getMaximum());
      }
      default -> json.addProperty("type", last ? "greedy" : "token");
    }
  }
}
