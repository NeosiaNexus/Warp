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
package dev.warp.proxy.remote;

import dev.warp.api.command.CommandSource;
import dev.warp.proxy.command.WarpCommandManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import org.jspecify.annotations.Nullable;

/**
 * A remote proxy's commands, rebuilt in a local dispatcher: the local console highlights the line,
 * hints at the arguments and completes command names as it does for the proxy's own commands,
 * without a round trip per key. Arguments are completed by the proxy ({@code POST
 * /console/suggest}), and running a command sends the line to it.
 */
final class RemoteCommands {

  /** The owner of the rebuilt commands in the local dispatcher. */
  static final String OWNER = "remote";

  private final WarpCommandManager commands;
  private final Command<CommandSource> run;
  private final SuggestionProvider<CommandSource> suggest;
  private final List<String> names = new ArrayList<>();

  /**
   * Creates the commands of a remote proxy.
   *
   * @param commands the local dispatcher the prompt reads
   * @param run runs a command line on the proxy
   * @param suggest completes a line up to the cursor on the proxy
   */
  RemoteCommands(
      WarpCommandManager commands,
      Function<String, Integer> run,
      Function<String, CompletableFuture<Suggestions>> suggest) {
    this.commands = commands;
    this.run = context -> run.apply(context.getInput());
    this.suggest = (context, builder) -> suggest.apply(builder.getInput());
  }

  /**
   * Replaces the commands with those of a tree.
   *
   * @param tree the answer of {@code GET /console/commands}
   * @return how many commands the proxy lets this token run
   */
  synchronized int replace(JsonObject tree) {
    for (String name : names) {
      commands.unregister(name);
    }
    names.clear();
    JsonElement list = tree.get("commands");
    if (list == null || !list.isJsonArray()) {
      return 0;
    }
    for (JsonElement element : list.getAsJsonArray()) {
      if (element.isJsonObject() && element.getAsJsonObject().has("literal")) {
        JsonObject node = element.getAsJsonObject();
        @SuppressWarnings("unchecked")
        LiteralArgumentBuilder<CommandSource> literal =
            (LiteralArgumentBuilder<CommandSource>) node(node, 0);
        try {
          var _ = commands.register(OWNER, literal);
          names.add(literal.getLiteral());
        } catch (IllegalStateException e) {
          // A name twice in the tree: the first wins.
        }
      }
    }
    return names.size();
  }

  private ArgumentBuilder<CommandSource, ?> node(JsonObject json, int depth) {
    ArgumentBuilder<CommandSource, ?> builder;
    String literal = text(json, "literal");
    if (literal != null) {
      builder = LiteralArgumentBuilder.literal(literal);
    } else {
      String name = text(json, "argument");
      builder =
          RequiredArgumentBuilder.<CommandSource, Object>argument(
                  name == null ? "argument" : name, type(json))
              .suggests(suggest);
    }
    JsonElement executes = json.get("executes");
    if (executes != null && executes.isJsonPrimitive() && executes.getAsBoolean()) {
      builder.executes(run);
    }
    JsonElement children = json.get("children");
    if (children != null && children.isJsonArray() && depth < 32) {
      for (JsonElement child : children.getAsJsonArray()) {
        if (child.isJsonObject()) {
          builder.then(node(child.getAsJsonObject(), depth + 1));
        }
      }
    }
    return builder;
  }

  @SuppressWarnings("unchecked")
  private static ArgumentType<Object> type(JsonObject json) {
    String type = text(json, "type");
    ArgumentType<?> argument =
        switch (type == null ? "" : type) {
          case "phrase" -> StringArgumentType.string();
          case "greedy" -> StringArgumentType.greedyString();
          case "bool" -> BoolArgumentType.bool();
          case "integer" ->
              IntegerArgumentType.integer(
                  (int) number(json, "min", Integer.MIN_VALUE),
                  (int) number(json, "max", Integer.MAX_VALUE));
          case "long" ->
              LongArgumentType.longArg(
                  (long) number(json, "min", Long.MIN_VALUE),
                  (long) number(json, "max", Long.MAX_VALUE));
          case "float" ->
              FloatArgumentType.floatArg(
                  (float) number(json, "min", -Float.MAX_VALUE),
                  (float) number(json, "max", Float.MAX_VALUE));
          case "double" ->
              DoubleArgumentType.doubleArg(
                  number(json, "min", -Double.MAX_VALUE), number(json, "max", Double.MAX_VALUE));
          default -> StringArgumentType.word();
        };
    return (ArgumentType<Object>) argument;
  }

  /**
   * Turns the answer of {@code POST /console/suggest} into Brigadier's suggestions.
   *
   * @param input the line completed
   * @param answer the answer's body
   * @return the suggestions
   */
  static Suggestions suggestions(String input, JsonObject answer) {
    List<Suggestion> list = new ArrayList<>();
    JsonElement items = answer.get("suggestions");
    if (items != null && items.isJsonArray()) {
      JsonArray array = items.getAsJsonArray();
      for (JsonElement element : array) {
        if (!element.isJsonObject()) {
          continue;
        }
        JsonObject item = element.getAsJsonObject();
        String text = text(item, "text");
        int start = (int) number(item, "start", -1);
        int end = (int) number(item, "end", -1);
        if (text == null || start < 0 || end < start || end > input.length()) {
          continue;
        }
        String tooltip = text(item, "tooltip");
        list.add(
            new Suggestion(
                StringRange.between(start, end),
                text,
                tooltip == null ? null : new LiteralMessage(tooltip)));
      }
    }
    return Suggestions.create(input, list);
  }

  private static @Nullable String text(JsonObject json, String name) {
    JsonElement value = json.get(name);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
  }

  private static double number(JsonObject json, String name, double fallback) {
    JsonElement value = json.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      return fallback;
    }
    return value.getAsDouble();
  }
}
