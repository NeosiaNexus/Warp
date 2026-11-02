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

import java.util.Objects;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

/**
 * One node of a {@link CommandTree}, as the commands packet carries it: the root, a literal word,
 * or an argument. Nodes refer to each other by index into their tree, never by reference, so a tree
 * keeps every node exactly where the backend put it: shared nodes stay shared, redirects stay
 * redirects, and nodes no command reaches any more are still there until {@link
 * CommandTree#compact()} drops them.
 *
 * <p>Records compare by value: two nodes with equal fields are equal even at different indices.
 * Nothing in this package ever merges nodes because they are equal (Velocity's re-encoder once did,
 * and broke redirects: PaperMC/Velocity#429).
 */
public sealed interface CommandNode
    permits CommandNode.Root, CommandNode.Literal, CommandNode.Argument {

  /**
   * Returns the node's children, by index into its tree.
   *
   * @return the children, in packet order
   */
  Children children();

  /**
   * Returns the node this one redirects to, by index into its tree: once the client has parsed this
   * node, it goes on parsing from the target's children ({@code /execute run} redirects to the
   * root).
   *
   * @return the redirect target, or empty
   */
  OptionalInt redirect();

  /**
   * Returns whether the command may end at this node: whether the client accepts it as complete.
   *
   * @return {@code true} if the node is executable
   */
  boolean executable();

  /**
   * Returns whether running this command needs more than a player's default permission, which the
   * client asks the player to confirm before running it from a click event. Clients read it from
   * 1.21.6 only ({@link CommandTreeCodec#RESTRICTED_SINCE}): an encoder for an older version leaves
   * it out.
   *
   * @return {@code true} if the node is restricted
   */
  boolean restricted();

  /**
   * Returns the name the node is matched by: the word of a literal, the name of an argument.
   *
   * @return the name, or {@code null} for the root
   */
  @Nullable String name();

  /**
   * Returns this node with other children.
   *
   * @param children the new children
   * @return a copy of this node
   */
  CommandNode withChildren(Children children);

  /**
   * Returns this node with another redirect target. The root has none.
   *
   * @param redirect the new target, or empty
   * @return a copy of this node
   * @throws IllegalArgumentException for a target on the root
   */
  CommandNode withRedirect(OptionalInt redirect);

  /**
   * Returns this node, executable or not. The root never is.
   *
   * @param executable whether the command may end here
   * @return a copy of this node
   * @throws IllegalArgumentException to make the root executable
   */
  CommandNode withExecutable(boolean executable);

  /**
   * Returns this node, restricted or not. The root never is.
   *
   * @param restricted whether running it needs more than the default permission
   * @return a copy of this node
   * @throws IllegalArgumentException to make the root restricted
   */
  CommandNode withRestricted(boolean restricted);

  // ---------------------------------------------------------------------------
  // Factories
  // ---------------------------------------------------------------------------

  /**
   * Returns a leaf literal: not executable, no redirect, not restricted.
   *
   * @param name the literal word
   * @return the node
   */
  static Literal literal(String name) {
    return new Literal(name, Children.of(), OptionalInt.empty(), false, false);
  }

  /**
   * Returns a leaf argument: no suggestions provider, not executable, no redirect, not restricted.
   *
   * @param name the argument's name
   * @param parser how the client parses it
   * @return the node
   */
  static Argument argument(String name, ArgumentParser parser) {
    return new Argument(name, parser, null, Children.of(), OptionalInt.empty(), false, false);
  }

  // ---------------------------------------------------------------------------
  // Nodes
  // ---------------------------------------------------------------------------

  /**
   * The root: the node every command starts from. It has no name, no redirect, and is neither
   * executable nor restricted.
   *
   * @param children the top-level commands, by index
   */
  record Root(Children children) implements CommandNode {

    // Checks the children.
    public Root {
      Objects.requireNonNull(children, "children");
    }

    @Override
    public OptionalInt redirect() {
      return OptionalInt.empty();
    }

    @Override
    public boolean executable() {
      return false;
    }

    @Override
    public boolean restricted() {
      return false;
    }

    @Override
    public @Nullable String name() {
      return null;
    }

    @Override
    public Root withChildren(Children children) {
      return new Root(children);
    }

    @Override
    public Root withRedirect(OptionalInt redirect) {
      if (redirect.isPresent()) {
        throw new IllegalArgumentException("The root cannot redirect");
      }
      return this;
    }

    @Override
    public Root withExecutable(boolean executable) {
      if (executable) {
        throw new IllegalArgumentException("The root cannot be executable");
      }
      return this;
    }

    @Override
    public Root withRestricted(boolean restricted) {
      if (restricted) {
        throw new IllegalArgumentException("The root cannot be restricted");
      }
      return this;
    }
  }

  /**
   * A literal: a fixed word, {@code gamemode} or {@code creative}.
   *
   * @param name the word
   * @param children the nodes that may follow, by index
   * @param redirect the node to go on from, by index, or empty
   * @param executable whether the command may end here
   * @param restricted whether running it needs more than the default permission (1.21.6+)
   */
  record Literal(
      String name, Children children, OptionalInt redirect, boolean executable, boolean restricted)
      implements CommandNode {

    // Checks the components: a redirect target is an index, never negative.
    public Literal {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(children, "children");
      checkRedirect(redirect);
    }

    @Override
    public Literal withChildren(Children children) {
      return new Literal(name, children, redirect, executable, restricted);
    }

    @Override
    public Literal withRedirect(OptionalInt redirect) {
      return new Literal(name, children, redirect, executable, restricted);
    }

    @Override
    public Literal withExecutable(boolean executable) {
      return new Literal(name, children, redirect, executable, restricted);
    }

    @Override
    public Literal withRestricted(boolean restricted) {
      return new Literal(name, children, redirect, executable, restricted);
    }
  }

  /**
   * An argument: a value the client parses with {@code parser}, completing it with the suggestions
   * the client computes itself, or with those of {@code suggestions} when there is one.
   *
   * @param name the argument's name, shown in the usage hints
   * @param parser how the client parses the value
   * @param suggestions the suggestions provider's identifier ({@code minecraft:ask_server} has the
   *     client ask the server), or {@code null} for the parser's own suggestions
   * @param children the nodes that may follow, by index
   * @param redirect the node to go on from, by index, or empty
   * @param executable whether the command may end here
   * @param restricted whether running it needs more than the default permission (1.21.6+)
   */
  record Argument(
      String name,
      ArgumentParser parser,
      @Nullable String suggestions,
      Children children,
      OptionalInt redirect,
      boolean executable,
      boolean restricted)
      implements CommandNode {

    /** The suggestions provider that has the client ask the server for suggestions. */
    public static final String ASK_SERVER = "minecraft:ask_server";

    // Checks the components: a redirect target is an index, never negative.
    public Argument {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(parser, "parser");
      Objects.requireNonNull(children, "children");
      checkRedirect(redirect);
    }

    @Override
    public Argument withChildren(Children children) {
      return new Argument(name, parser, suggestions, children, redirect, executable, restricted);
    }

    @Override
    public Argument withRedirect(OptionalInt redirect) {
      return new Argument(name, parser, suggestions, children, redirect, executable, restricted);
    }

    @Override
    public Argument withExecutable(boolean executable) {
      return new Argument(name, parser, suggestions, children, redirect, executable, restricted);
    }

    @Override
    public Argument withRestricted(boolean restricted) {
      return new Argument(name, parser, suggestions, children, redirect, executable, restricted);
    }

    /**
     * Returns this argument with another suggestions provider.
     *
     * @param suggestions the provider's identifier, or {@code null} for the parser's own
     * @return a copy of this node
     */
    public Argument withSuggestions(@Nullable String suggestions) {
      return new Argument(name, parser, suggestions, children, redirect, executable, restricted);
    }
  }

  private static void checkRedirect(OptionalInt redirect) {
    Objects.requireNonNull(redirect, "redirect");
    if (redirect.isPresent() && redirect.getAsInt() < 0) {
      throw new IllegalArgumentException("Negative redirect index: " + redirect.getAsInt());
    }
  }
}
