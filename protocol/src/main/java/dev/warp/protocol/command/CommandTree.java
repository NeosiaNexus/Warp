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

import dev.warp.protocol.command.CommandNode.Argument;
import dev.warp.protocol.command.CommandNode.Root;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * A command graph, as the commands packet carries it: nodes in packet order, referring to each
 * other by index, and the index of the root.
 *
 * <p>A tree is immutable and always valid: every index is in range, the root is a {@link Root}, no
 * node has a root as a child, and neither children nor redirects loop on themselves, which is what
 * the vanilla client checks before it accepts a tree ("Server sent an impossible command tree").
 * Redirects may still point back up the tree: {@code /execute run} redirects to the root.
 *
 * <p>Decoding keeps every node where the backend put it, so a tree that is not edited encodes to
 * the very bytes it came from. The editors ({@link #merge}, {@link #withoutTopLevel}, {@link
 * #filter}) return new trees, without the nodes their edit left unreachable, every index renumbered
 * to match.
 *
 * <p>Every walk over a tree is iterative: a hostile backend's deep or looping tree cannot overflow
 * the stack.
 */
public final class CommandTree {

  private static final CommandTree EMPTY =
      new CommandTree(new CommandNode[] {new Root(Children.of())}, 0);

  /** What {@link #filter} knows of a node: not reached yet, kept, or rejected. */
  private static final byte UNSEEN = 0;

  private static final byte KEPT = 1;
  private static final byte REJECTED = 2;

  /** Owned: never handed out, never changed. */
  private final CommandNode[] nodes;

  private final int root;

  private CommandTree(CommandNode[] nodes, int root) {
    this.nodes = nodes;
    this.root = root;
  }

  // ---------------------------------------------------------------------------
  // Creation
  // ---------------------------------------------------------------------------

  /**
   * Returns a tree with nothing but a root.
   *
   * @return the empty tree
   */
  public static CommandTree empty() {
    return EMPTY;
  }

  /**
   * Returns a tree of the given nodes, after checking it is one the client accepts.
   *
   * @param nodes the nodes, in packet order: a node's index is its position here
   * @param root the index of the root
   * @return the tree
   * @throws IllegalArgumentException if an index is out of range, the root is not a {@link Root}, a
   *     node has a root as a child, or children or redirects loop
   */
  public static CommandTree of(List<? extends CommandNode> nodes, int root) {
    CommandNode[] array = nodes.toArray(new CommandNode[0]);
    for (CommandNode node : array) {
      Objects.requireNonNull(node, "node");
    }
    String invalid = invalid(array, root);
    if (invalid != null) {
      throw new IllegalArgumentException(invalid);
    }
    return new CommandTree(array, root);
  }

  /** A tree of nodes the caller checked with {@link #invalid} and gives up. */
  static CommandTree trusted(CommandNode[] nodes, int root) {
    return new CommandTree(nodes, root);
  }

  /**
   * Returns a builder of a tree, starting with a lone root at index 0.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  // ---------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------

  /**
   * Returns how many nodes the tree has, reachable or not.
   *
   * @return the number of nodes
   */
  public int size() {
    return nodes.length;
  }

  /**
   * Returns the node at an index.
   *
   * @param index from 0 to {@link #size()} excluded
   * @return the node
   * @throws IndexOutOfBoundsException if the index is out of range
   */
  public CommandNode node(int index) {
    return nodes[index];
  }

  /**
   * Returns every node, in packet order.
   *
   * @return an unmodifiable view of the nodes
   */
  public List<CommandNode> nodes() {
    return Collections.unmodifiableList(Arrays.asList(nodes));
  }

  /**
   * Returns the index of the root.
   *
   * @return the root's index
   */
  public int rootIndex() {
    return root;
  }

  /**
   * Returns the root.
   *
   * @return the root node
   */
  public Root root() {
    return (Root) nodes[root];
  }

  /**
   * Returns the first child of a node that has a given name.
   *
   * @param parent the node's index
   * @param name the literal word or argument name
   * @return the child's index, or empty if the node has no child of that name
   * @throws IndexOutOfBoundsException if the parent's index is out of range
   */
  public OptionalInt child(int parent, String name) {
    Children children = nodes[parent].children();
    for (int i = 0; i < children.size(); i++) {
      int child = children.get(i);
      if (name.equals(nodes[child].name())) {
        return OptionalInt.of(child);
      }
    }
    return OptionalInt.empty();
  }

  /**
   * Returns the top-level command of a given name: the first child of the root that has it.
   *
   * @param name the command's name, without the slash
   * @return its node's index, or empty if the tree has no such command
   */
  public OptionalInt topLevel(String name) {
    return child(root, name);
  }

  /**
   * Returns the names of the top-level commands, in packet order.
   *
   * @return the names, duplicates included
   */
  public List<String> topLevelNames() {
    Children children = nodes[root].children();
    List<String> names = new ArrayList<>(children.size());
    for (int i = 0; i < children.size(); i++) {
      names.add(nameOf(children.get(i)));
    }
    return Collections.unmodifiableList(names);
  }

  // ---------------------------------------------------------------------------
  // Editing
  // ---------------------------------------------------------------------------

  /**
   * Returns this tree with the top-level commands of another one added: a backend's tree, with the
   * proxy's commands in it.
   *
   * <p>The added commands come after the backend's at the root, with every node they reach. A node
   * of {@code commands} that redirects to its root redirects to this tree's root. When a command's
   * name is already at the top level of this tree, {@code conflict} decides which one the client
   * gets.
   *
   * @param commands the commands to add: the children of its root, and what they reach
   * @param conflict what to do when a name is already taken
   * @return the merged tree, without the nodes no command reaches any more
   * @throws IllegalArgumentException if merging nodes made children loop, which only a {@link
   *     NameConflict#MERGE} of oddly shaped trees can
   */
  public CommandTree merge(CommandTree commands, NameConflict conflict) {
    Objects.requireNonNull(commands, "commands");
    Objects.requireNonNull(conflict, "conflict");
    return new Merge(this, commands).run(conflict);
  }

  /**
   * Returns this tree without some of its top-level commands. Nodes the removed commands share with
   * others, or that others redirect to, stay.
   *
   * @param remove which commands to remove, given each child of the root
   * @return the tree, without the nodes no command reaches any more
   */
  public CommandTree withoutTopLevel(Predicate<? super CommandNode> remove) {
    Objects.requireNonNull(remove, "remove");
    Children children = nodes[root].children();
    IntList kept = new IntList(children.size());
    for (int i = 0; i < children.size(); i++) {
      int child = children.get(i);
      if (!remove.test(nodes[child])) {
        kept.add(child);
      }
    }
    if (kept.size() == children.size()) {
      return this;
    }
    CommandNode[] edited = nodes.clone();
    edited[root] = new Root(kept.toChildren());
    return trusted(edited, root).compact();
  }

  /**
   * Returns this tree with only the nodes {@code keep} accepts: a tree as one player may see it.
   *
   * <p>{@code keep} is asked once about each node the root reaches, parents first, never about the
   * root, and never about a node only rejected nodes reach. A rejected node leaves every child list
   * it is in, and nodes that redirected to it no longer redirect, as the vanilla server does when
   * it drops the commands a player may not use.
   *
   * @param keep whether a node stays
   * @return the filtered tree, without the nodes no command reaches any more
   */
  public CommandTree filter(Predicate<? super CommandNode> keep) {
    Objects.requireNonNull(keep, "keep");
    byte[] state = new byte[nodes.length];
    state[root] = KEPT;
    IntList queue = new IntList(nodes.length);
    queue.add(root);
    for (int head = 0; head < queue.size(); head++) {
      CommandNode node = nodes[queue.get(head)];
      Children children = node.children();
      for (int i = 0; i < children.size(); i++) {
        ask(children.get(i), keep, state, queue);
      }
      OptionalInt redirect = node.redirect();
      if (redirect.isPresent()) {
        ask(redirect.getAsInt(), keep, state, queue);
      }
    }
    CommandNode[] edited = nodes.clone();
    for (int kept = 0; kept < queue.size(); kept++) {
      int index = queue.get(kept);
      edited[index] = withoutRejected(nodes[index], state);
    }
    return trusted(edited, root).compact();
  }

  /** Asks {@code keep} about a node the first time the walk reaches it. */
  private void ask(int index, Predicate<? super CommandNode> keep, byte[] state, IntList queue) {
    if (state[index] == UNSEEN) {
      if (keep.test(nodes[index])) {
        state[index] = KEPT;
        queue.add(index);
      } else {
        state[index] = REJECTED;
      }
    }
  }

  /** {@code node} without its rejected children, and without its redirect if that is rejected. */
  private static CommandNode withoutRejected(CommandNode node, byte[] state) {
    Children children = node.children();
    IntList kept = new IntList(children.size());
    for (int i = 0; i < children.size(); i++) {
      if (state[children.get(i)] != REJECTED) {
        kept.add(children.get(i));
      }
    }
    CommandNode filtered = node.withChildren(kept.toChildren());
    OptionalInt redirect = node.redirect();
    return redirect.isPresent() && state[redirect.getAsInt()] == REJECTED
        ? filtered.withRedirect(OptionalInt.empty())
        : filtered;
  }

  /**
   * Returns this tree without the nodes the root does not reach, through children or redirects, the
   * others keeping their order.
   *
   * @return the compacted tree, or this one if every node is reachable
   */
  public CommandTree compact() {
    int n = nodes.length;
    boolean[] reached = new boolean[n];
    int[] queue = new int[n];
    int head = 0;
    int tail = 0;
    reached[root] = true;
    queue[tail++] = root;
    while (head < tail) {
      CommandNode node = nodes[queue[head++]];
      Children children = node.children();
      for (int i = 0; i < children.size(); i++) {
        int child = children.get(i);
        if (!reached[child]) {
          reached[child] = true;
          queue[tail++] = child;
        }
      }
      OptionalInt redirect = node.redirect();
      if (redirect.isPresent() && !reached[redirect.getAsInt()]) {
        reached[redirect.getAsInt()] = true;
        queue[tail++] = redirect.getAsInt();
      }
    }
    if (tail == n) {
      return this;
    }
    int[] renumbered = new int[n];
    CommandNode[] kept = new CommandNode[tail];
    int next = 0;
    for (int i = 0; i < n; i++) {
      renumbered[i] = reached[i] ? next++ : -1;
    }
    for (int i = 0; i < n; i++) {
      if (reached[i]) {
        kept[renumbered[i]] = renumber(nodes[i], renumbered);
      }
    }
    return trusted(kept, renumbered[root]);
  }

  /** {@code node} with every index it holds replaced by {@code renumbered[index]}. */
  private static CommandNode renumber(CommandNode node, int[] renumbered) {
    Children children = node.children();
    int[] mapped = new int[children.size()];
    for (int i = 0; i < mapped.length; i++) {
      mapped[i] = renumbered[children.get(i)];
    }
    CommandNode result = node.withChildren(Children.wrap(mapped));
    OptionalInt redirect = node.redirect();
    return redirect.isPresent()
        ? result.withRedirect(OptionalInt.of(renumbered[redirect.getAsInt()]))
        : result;
  }

  private String nameOf(int index) {
    return Objects.requireNonNull(nodes[index].name(), "a root is never a child");
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  /**
   * Why the client would refuse these nodes as a tree, or {@code null} if it accepts them: an index
   * out of range, a root that is not a {@link Root}, a root as a child, or a loop of children or of
   * redirects.
   */
  static @Nullable String invalid(CommandNode[] nodes, int root) {
    int n = nodes.length;
    if (root < 0 || root >= n) {
      return "Root index " + root + " is out of range (" + n + " nodes)";
    }
    if (!(nodes[root] instanceof Root)) {
      return "Node " + root + ", the root, is not a root node";
    }
    int[] parents = new int[n]; // in-degree through children
    for (int i = 0; i < n; i++) {
      CommandNode node = nodes[i];
      Children children = node.children();
      for (int c = 0; c < children.size(); c++) {
        int child = children.get(c);
        if (child >= n) {
          return "Child " + child + " of node " + i + " is out of range (" + n + " nodes)";
        }
        if (nodes[child] instanceof Root) {
          return "Node " + i + " has a root node (" + child + ") as a child";
        }
        parents[child]++;
      }
      OptionalInt redirect = node.redirect();
      if (redirect.isPresent() && redirect.getAsInt() >= n) {
        return "Redirect " + redirect.getAsInt() + " of node " + i + " is out of range";
      }
    }
    // Children: Kahn's algorithm, every node is resolved once all its parents are.
    int[] queue = new int[n];
    int head = 0;
    int tail = 0;
    for (int i = 0; i < n; i++) {
      if (parents[i] == 0) {
        queue[tail++] = i;
      }
    }
    while (head < tail) {
      Children children = nodes[queue[head++]].children();
      for (int c = 0; c < children.size(); c++) {
        if (--parents[children.get(c)] == 0) {
          queue[tail++] = children.get(c);
        }
      }
    }
    if (tail < n) {
      return "Children loop: " + (n - tail) + " nodes are their own descendants";
    }
    // Redirects: one per node at most. Each walk stamps the nodes it reaches, and stops at one
    // stamped before: by itself, a loop; by an earlier walk, a chain already known to end.
    int[] walk = new int[n];
    for (int i = 0; i < n; i++) {
      int stamp = i + 1;
      int at = i;
      while (walk[at] == 0) {
        walk[at] = stamp;
        OptionalInt redirect = nodes[at].redirect();
        if (redirect.isEmpty()) {
          break;
        }
        at = redirect.getAsInt();
      }
      if (walk[at] == stamp && nodes[at].redirect().isPresent()) {
        return "Redirect loop through node " + at;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Object
  // ---------------------------------------------------------------------------

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof CommandTree tree
        && root == tree.root
        && Arrays.equals(nodes, tree.nodes);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(nodes) + root;
  }

  @Override
  public String toString() {
    return "CommandTree["
        + nodes.length
        + " nodes, root "
        + root
        + ", "
        + nodes[root].children().size()
        + " top-level commands]";
  }

  // ---------------------------------------------------------------------------
  // Merge
  // ---------------------------------------------------------------------------

  /** One {@link #merge}: the backend's nodes, edited in place, then the proxy's appended. */
  private static final class Merge {

    private final CommandTree backend;
    private final CommandTree proxy;
    private final List<@Nullable CommandNode> out;

    /** Proxy node index to its index in {@link #out}: appended, merged into, or -1 not yet. */
    private final int[] placed;

    /** Proxy nodes appended to {@link #out} whose children and redirect are still the proxy's. */
    private final IntList pending;

    Merge(CommandTree backend, CommandTree proxy) {
      this.backend = backend;
      this.proxy = proxy;
      this.out = new ArrayList<>(backend.nodes.length + proxy.nodes.length);
      Collections.addAll(out, backend.nodes);
      this.placed = new int[proxy.nodes.length];
      Arrays.fill(placed, -1);
      placed[proxy.root] = backend.root;
      this.pending = new IntList(proxy.nodes.length);
    }

    CommandTree run(NameConflict conflict) {
      IntList top = IntList.of(backend.nodes[backend.root].children());
      int backendCommands = top.size();
      Children commands = proxy.nodes[proxy.root].children();
      for (int i = 0; i < commands.size(); i++) {
        int command = commands.get(i);
        String name = Objects.requireNonNull(proxy.nodes[command].name());
        switch (conflict) {
          case PROXY_WINS -> {
            for (int t = backendCommands - 1; t >= 0; t--) {
              if (name.equals(nameAt(top.get(t)))) {
                top.removeAt(t);
                backendCommands--;
              }
            }
            top.add(place(command));
          }
          case BACKEND_WINS -> {
            if (find(top, backendCommands, name) < 0) {
              top.add(place(command));
            }
          }
          case MERGE -> {
            int match = find(top, backendCommands, name);
            if (match < 0) {
              top.add(place(command));
            } else {
              mergeInto(top.get(match), command);
            }
          }
        }
      }
      for (int p = 0; p < pending.size(); p++) {
        int index = pending.get(p);
        CommandNode node = proxy.nodes[index];
        Children children = node.children();
        int[] mapped = new int[children.size()];
        for (int c = 0; c < mapped.length; c++) {
          mapped[c] = place(children.get(c));
        }
        CommandNode copy = node.withChildren(Children.wrap(mapped));
        OptionalInt redirect = node.redirect();
        if (redirect.isPresent()) {
          copy = copy.withRedirect(OptionalInt.of(place(redirect.getAsInt())));
        }
        out.set(placed[index], copy);
      }
      out.set(backend.root, new Root(top.toChildren()));
      CommandNode[] merged = out.toArray(new CommandNode[0]);
      String invalid = invalid(merged, backend.root);
      if (invalid != null) {
        throw new IllegalArgumentException("Merged tree is invalid: " + invalid);
      }
      return trusted(merged, backend.root).compact();
    }

    /** The index in {@link #out} of a proxy node, appending it the first time. */
    private int place(int proxyIndex) {
      int index = placed[proxyIndex];
      if (index < 0) {
        index = out.size();
        out.add(null); // filled in once every node is placed
        placed[proxyIndex] = index;
        pending.add(proxyIndex);
      }
      return index;
    }

    /** Merges a proxy node into a node of {@link #out}, as Brigadier's {@code addChild} does. */
    private void mergeInto(int target, int proxyIndex) {
      if (placed[proxyIndex] < 0) {
        placed[proxyIndex] = target;
      }
      CommandNode proxyNode = proxy.nodes[proxyIndex];
      CommandNode node = Objects.requireNonNull(out.get(target));
      IntList children = IntList.of(node.children());
      int existing = children.size();
      Children proxyChildren = proxyNode.children();
      for (int i = 0; i < proxyChildren.size(); i++) {
        int proxyChild = proxyChildren.get(i);
        int match =
            find(children, existing, Objects.requireNonNull(proxy.nodes[proxyChild].name()));
        if (match < 0) {
          children.add(place(proxyChild));
        } else {
          mergeInto(children.get(match), proxyChild);
        }
      }
      CommandNode merged =
          Objects.requireNonNull(out.get(target)).withChildren(children.toChildren());
      if (proxyNode.executable()) {
        merged = merged.withExecutable(true); // never the root: it is no proxy node's match
      }
      out.set(target, merged);
    }

    /** The first of the first {@code count} indices of {@code list} whose node has the name. */
    private int find(IntList list, int count, String name) {
      for (int i = 0; i < count; i++) {
        if (name.equals(nameAt(list.get(i)))) {
          return i;
        }
      }
      return -1;
    }

    private @Nullable String nameAt(int index) {
      CommandNode node = out.get(index);
      return node == null ? null : node.name();
    }
  }

  // ---------------------------------------------------------------------------
  // Builder
  // ---------------------------------------------------------------------------

  /**
   * Builds a tree node by node: proxy commands, or a tree converted from Brigadier on the proxy's
   * side. Nodes are handled by index, the root being 0; each method returns the index of the node
   * it adds, or the builder.
   *
   * <pre>{@code
   * CommandTree.Builder builder = CommandTree.builder();
   * int friend = builder.literal(builder.root(), "friend");
   * int add = builder.literal(friend, "add");
   * builder.executable(builder.argument(add, "player", ArgumentParser.word()));
   * CommandTree tree = builder.build();
   * }</pre>
   */
  public static final class Builder {

    private final List<CommandNode> nodes = new ArrayList<>();

    Builder() {
      nodes.add(new Root(Children.of()));
    }

    /**
     * Returns the index of the root.
     *
     * @return 0
     */
    public int root() {
      return 0;
    }

    /**
     * Adds a literal as the last child of a node.
     *
     * @param parent the parent's index
     * @param name the literal word
     * @return the literal's index
     */
    public int literal(int parent, String name) {
      return add(parent, CommandNode.literal(name));
    }

    /**
     * Adds an argument as the last child of a node.
     *
     * @param parent the parent's index
     * @param name the argument's name
     * @param parser how the client parses it
     * @return the argument's index
     */
    public int argument(int parent, String name, ArgumentParser parser) {
      return add(parent, CommandNode.argument(name, parser));
    }

    /**
     * Adds a node, with no parent yet: to share it, or have nodes redirect to it.
     *
     * @param node the node; its children and redirect must be indices of this builder
     * @return the node's index
     */
    public int node(CommandNode node) {
      nodes.add(Objects.requireNonNull(node, "node"));
      return nodes.size() - 1;
    }

    /**
     * Makes a node, already added, the last child of another one as well: shared.
     *
     * @param parent the parent's index
     * @param child the child's index
     * @return this builder
     */
    public Builder child(int parent, int child) {
      nodes.set(parent, nodes.get(parent).withChildren(nodes.get(parent).children().with(child)));
      return this;
    }

    /**
     * Makes a node executable: the command may end there.
     *
     * @param node the node's index, not the root's
     * @return this builder
     */
    public Builder executable(int node) {
      nodes.set(node, nodes.get(node).withExecutable(true));
      return this;
    }

    /**
     * Makes a node restricted: running it needs more than the default permission (1.21.6+).
     *
     * @param node the node's index, not the root's
     * @return this builder
     */
    public Builder restricted(int node) {
      nodes.set(node, nodes.get(node).withRestricted(true));
      return this;
    }

    /**
     * Gives an argument a suggestions provider.
     *
     * @param node the argument's index
     * @param provider the provider's identifier, {@link Argument#ASK_SERVER} for instance
     * @return this builder
     */
    public Builder suggestions(int node, String provider) {
      if (!(nodes.get(node) instanceof Argument argument)) {
        throw new IllegalArgumentException("Only an argument has suggestions: node " + node);
      }
      nodes.set(node, argument.withSuggestions(Objects.requireNonNull(provider, "provider")));
      return this;
    }

    /**
     * Makes a node redirect to another one.
     *
     * @param node the node's index, not the root's
     * @param target the target's index
     * @return this builder
     */
    public Builder redirect(int node, int target) {
      nodes.set(node, nodes.get(node).withRedirect(OptionalInt.of(target)));
      return this;
    }

    /**
     * Builds the tree.
     *
     * @return the tree
     * @throws IllegalArgumentException if it is not one the client accepts (see {@link #of})
     */
    public CommandTree build() {
      return of(nodes, 0);
    }

    private int add(int parent, CommandNode node) {
      int index = node(node);
      child(parent, index);
      return index;
    }
  }
}
