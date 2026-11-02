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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.command.CommandNode.Argument;
import dev.warp.protocol.command.CommandNode.Literal;
import dev.warp.protocol.command.CommandNode.Root;
import dev.warp.protocol.command.CommandTreeDecoding.Decoded;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CommandTree")
class CommandTreeTest {

  // ---------------------------------------------------------------------------
  // Creation and validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("creation")
  class Creation {

    @Test
    @DisplayName("should accept a tree whose redirects point back up, as /execute run does")
    void acceptRedirectToRoot() {
      List<CommandNode> nodes =
          List.of(
              new Root(Children.of(1)),
              new Literal("execute", Children.of(2), OptionalInt.empty(), false, false),
              new Literal("run", Children.of(), OptionalInt.of(0), false, false));

      CommandTree tree = CommandTree.of(nodes, 0);

      assertEquals(3, tree.size());
      assertEquals(nodes, tree.nodes());
      assertEquals(0, tree.rootIndex());
      assertSame(tree.node(0), tree.root());
    }

    @Test
    @DisplayName("should refuse every tree the client would refuse, saying why")
    void refuseInvalid() {
      Literal leaf = CommandNode.literal("x");
      assertRefused("Root index 1", List.of(new Root(Children.of())), 1);
      assertRefused("Root index -1", List.of(new Root(Children.of())), -1);
      assertRefused("the root, is not a root node", List.of(leaf), 0);
      assertRefused("Child 4 of node 0", List.of(new Root(Children.of(4))), 0);
      assertRefused(
          "Child 1 of node 0 is out of range (1 nodes)", List.of(new Root(Children.of(1))), 0);
      assertRefused(
          "Redirect 2 of node 1 is out of range",
          List.of(new Root(Children.of(1)), leaf.withRedirect(OptionalInt.of(2))),
          0);
      assertRefused(
          "has a root node (1) as a child",
          List.of(new Root(Children.of(1)), new Root(Children.of())),
          0);
      assertRefused(
          "Redirect 9 of node 1",
          List.of(new Root(Children.of(1)), leaf.withRedirect(OptionalInt.of(9))),
          0);
      assertRefused(
          "Children loop: 2 nodes are their own descendants",
          List.of(
              new Root(Children.of(1)),
              leaf.withChildren(Children.of(2)),
              leaf.withChildren(Children.of(1))),
          0);
      assertRefused(
          "Redirect loop",
          List.of(
              new Root(Children.of(1, 2)),
              leaf.withRedirect(OptionalInt.of(2)),
              leaf.withRedirect(OptionalInt.of(1))),
          0);
    }

    @Test
    @DisplayName("should accept long redirect chains that end, and nodes sharing one target")
    void acceptRedirectChains() {
      List<CommandNode> nodes = new ArrayList<>();
      nodes.add(new Root(Children.of(1, 2, 3, 4)));
      Literal leaf = CommandNode.literal("x");
      nodes.add(leaf.withRedirect(OptionalInt.of(2)));
      nodes.add(leaf.withRedirect(OptionalInt.of(3)));
      nodes.add(leaf.withRedirect(OptionalInt.of(4)));
      nodes.add(leaf);

      CommandTree tree = CommandTree.of(nodes, 0);

      assertEquals(5, tree.size());
    }

    private static void assertRefused(String message, List<CommandNode> nodes, int root) {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> CommandTree.of(nodes, root));
      assertTrue(String.valueOf(thrown.getMessage()).contains(message), thrown::getMessage);
    }

    @Test
    @DisplayName("should start empty with a lone root")
    void empty() {
      CommandTree empty = CommandTree.empty();

      assertEquals(1, empty.size());
      assertEquals(List.of(), empty.topLevelNames());
      assertEquals("CommandTree[1 nodes, root 0, 0 top-level commands]", empty.toString());
    }
  }

  // ---------------------------------------------------------------------------
  // Builder
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("builder")
  class BuilderTests {

    @Test
    @DisplayName("should build nodes in the order they are added, with their flags")
    void build() {
      CommandTree.Builder builder = CommandTree.builder();
      int friend = builder.literal(builder.root(), "friend");
      int add = builder.literal(friend, "add");
      int player = builder.argument(add, "player", ArgumentParser.word());
      builder.executable(player).suggestions(player, Argument.ASK_SERVER).restricted(add);
      int alias = builder.literal(builder.root(), "f");
      builder.redirect(alias, friend).restricted(player).executable(alias);
      int shared = builder.node(CommandNode.literal("list").withExecutable(true));
      builder.child(friend, shared).child(add, shared);

      CommandTree tree = builder.build();

      assertEquals(
          List.of(
              new Root(Children.of(1, 4)),
              new Literal("friend", Children.of(2, 5), OptionalInt.empty(), false, false),
              new Literal("add", Children.of(3, 5), OptionalInt.empty(), false, true),
              new Argument(
                  "player",
                  ArgumentParser.word(),
                  Argument.ASK_SERVER,
                  Children.of(),
                  OptionalInt.empty(),
                  true,
                  true),
              new Literal("f", Children.of(), OptionalInt.of(1), true, false),
              new Literal("list", Children.of(), OptionalInt.empty(), true, false)),
          tree.nodes());
    }

    @Test
    @DisplayName("should refuse flags the root cannot have, and suggestions on a literal")
    void refuse() {
      CommandTree.Builder builder = CommandTree.builder();
      int literal = builder.literal(builder.root(), "x");

      assertThrows(IllegalArgumentException.class, () -> builder.executable(builder.root()));
      assertThrows(IllegalArgumentException.class, () -> builder.restricted(builder.root()));
      assertThrows(IllegalArgumentException.class, () -> builder.redirect(builder.root(), literal));
      assertThrows(IllegalArgumentException.class, () -> builder.suggestions(literal, "x:y"));
      builder.child(literal, literal);
      assertThrows(IllegalArgumentException.class, builder::build);
    }
  }

  // ---------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("reading")
  class Reading {

    @Test
    @DisplayName("should find children and top-level commands by name")
    void findByName() {
      CommandTree tree =
          tree(
              b -> {
                int gamemode = b.literal(b.root(), "gamemode");
                b.literal(gamemode, "creative");
                b.argument(gamemode, "mode", ArgumentParser.word());
                b.literal(b.root(), "give");
              });

      assertEquals(OptionalInt.of(1), tree.topLevel("gamemode"));
      assertEquals(OptionalInt.of(4), tree.topLevel("give"));
      assertEquals(OptionalInt.empty(), tree.topLevel("creative"));
      assertEquals(OptionalInt.of(3), tree.child(1, "mode"));
      assertEquals(OptionalInt.empty(), tree.child(1, "survival"));
      assertEquals(List.of("gamemode", "give"), tree.topLevelNames());
      assertThrows(UnsupportedOperationException.class, () -> tree.topLevelNames().add("x"));
      assertThrows(UnsupportedOperationException.class, () -> tree.nodes().clear());
    }

    @Test
    @DisplayName("should be equal to a tree of equal nodes, and only then")
    void equality() {
      CommandTree a = tree(b -> b.literal(b.root(), "a"));
      CommandTree same = tree(b -> b.literal(b.root(), "a"));
      CommandTree other = tree(b -> b.literal(b.root(), "b"));
      CommandTree otherRoot =
          CommandTree.of(List.of(CommandNode.literal("a"), new Root(Children.of(0))), 1);

      assertEquals(a, same);
      assertEquals(a.hashCode(), same.hashCode());
      assertNotEquals(a, other);
      assertNotEquals(a, otherRoot);
      List<CommandNode> roots = List.of(new Root(Children.of()), new Root(Children.of()));
      assertNotEquals(CommandTree.of(roots, 0), CommandTree.of(roots, 1));
      assertEquals(31 * a.nodes().hashCode() + a.rootIndex(), a.hashCode());
      assertEquals(31 * otherRoot.nodes().hashCode() + 1, otherRoot.hashCode());
      assertNotEquals(a, null);
      assertEquals("CommandTree[2 nodes, root 0, 1 top-level commands]", a.toString());
    }
  }

  // ---------------------------------------------------------------------------
  // Merge
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("merge")
  class Merge {

    /** A backend tree: /msg with an argument, /tell and /w redirecting to it, /list. */
    private final CommandTree backend =
        tree(
            b -> {
              int msg = b.literal(b.root(), "msg");
              b.executable(
                  b.argument(msg, "targets", new ArgumentParser.EntityParser(false, true)));
              b.redirect(b.literal(b.root(), "tell"), msg);
              b.redirect(b.literal(b.root(), "w"), msg);
              b.executable(b.literal(b.root(), "list"));
            });

    /** The proxy's commands: its own /msg, and /friend with an alias. */
    private final CommandTree proxy =
        tree(
            b -> {
              int msg = b.literal(b.root(), "msg");
              b.executable(b.argument(msg, "player", ArgumentParser.word()));
              int friend = b.literal(b.root(), "friend");
              b.executable(b.literal(friend, "list"));
              b.redirect(b.literal(friend, "again"), b.root());
            });

    @Test
    @DisplayName("should replace the backend's command when the proxy wins, keeping its aliases")
    void proxyWins() {
      CommandTree merged = backend.merge(proxy, NameConflict.PROXY_WINS);

      assertEquals(List.of("tell", "w", "list", "msg", "friend"), merged.topLevelNames());
      int proxyMsg = merged.topLevel("msg").orElseThrow();
      int player = merged.child(proxyMsg, "player").orElseThrow();
      assertEquals(ArgumentParser.word(), ((Argument) merged.node(player)).parser());
      assertFalse(merged.child(proxyMsg, "targets").isPresent());
      // The backend's /msg left the root, but /tell and /w still redirect to it.
      int tell = merged.topLevel("tell").orElseThrow();
      int backendMsg = merged.node(tell).redirect().orElseThrow();
      assertTrue(merged.child(backendMsg, "targets").isPresent());
      assertEquals(
          merged.node(tell).redirect(), merged.node(merged.topLevel("w").orElseThrow()).redirect());
      // The proxy's redirect to its own root goes to the merged root.
      int again = merged.child(merged.topLevel("friend").orElseThrow(), "again").orElseThrow();
      assertEquals(OptionalInt.of(merged.rootIndex()), merged.node(again).redirect());
    }

    @Test
    @DisplayName("should keep the backend's command when the backend wins")
    void backendWins() {
      CommandTree merged = backend.merge(proxy, NameConflict.BACKEND_WINS);

      assertEquals(List.of("msg", "tell", "w", "list", "friend"), merged.topLevelNames());
      int msg = merged.topLevel("msg").orElseThrow();
      assertTrue(merged.child(msg, "targets").isPresent());
      assertFalse(merged.child(msg, "player").isPresent());
    }

    @Test
    @DisplayName("should merge both commands as Brigadier merges nodes")
    void mergeNodes() {
      CommandTree proxyWithShared =
          tree(
              b -> {
                int msg = b.literal(b.root(), "msg");
                int targets = b.argument(msg, "targets", ArgumentParser.word());
                b.executable(b.literal(targets, "quietly"));
                b.executable(b.literal(msg, "toggle"));
                b.executable(msg);
              });

      CommandTree merged = backend.merge(proxyWithShared, NameConflict.MERGE);

      assertEquals(List.of("msg", "tell", "w", "list"), merged.topLevelNames());
      int msg = merged.topLevel("msg").orElseThrow();
      assertTrue(merged.node(msg).executable(), "executable if the proxy's is");
      int targets = merged.child(msg, "targets").orElseThrow();
      // The backend's argument stays, with its parser, and takes the proxy's child.
      assertEquals(
          new ArgumentParser.EntityParser(false, true), ((Argument) merged.node(targets)).parser());
      assertTrue(merged.child(targets, "quietly").isPresent());
      assertTrue(merged.child(msg, "toggle").isPresent());
      // Aliases see the merged node: it is the same node.
      assertEquals(
          OptionalInt.of(msg), merged.node(merged.topLevel("tell").orElseThrow()).redirect());
    }

    @Test
    @DisplayName("should add a command the backend lacks when merging, executable arguments too")
    void mergeNewAndExecutable() {
      CommandTree proxyCommands =
          tree(
              b -> {
                int msg = b.literal(b.root(), "msg");
                b.executable(b.argument(msg, "targets", ArgumentParser.word()));
                b.literal(b.root(), "party");
              });
      CommandTree quiet =
          tree(
              b -> {
                int msg = b.literal(b.root(), "msg");
                b.argument(msg, "targets", new ArgumentParser.EntityParser(false, true));
              });

      CommandTree merged = quiet.merge(proxyCommands, NameConflict.MERGE);

      assertEquals(List.of("msg", "party"), merged.topLevelNames());
      int targets = merged.child(merged.topLevel("msg").orElseThrow(), "targets").orElseThrow();
      assertTrue(merged.node(targets).executable(), "executable since the proxy's is");
    }

    @Test
    @DisplayName("should merge a proxy node shared by two commands into each of their matches")
    void mergeSharedTwice() {
      CommandTree backendTree =
          tree(
              b -> {
                b.literal(b.literal(b.root(), "a"), "x");
                b.literal(b.literal(b.root(), "b"), "x");
              });
      CommandTree proxyCommands =
          tree(
              b -> {
                int x = b.node(CommandNode.literal("x"));
                b.literal(x, "deeper");
                b.child(b.literal(b.root(), "a"), x);
                b.child(b.literal(b.root(), "b"), x);
              });

      CommandTree merged = backendTree.merge(proxyCommands, NameConflict.MERGE);

      int ax = merged.child(merged.topLevel("a").orElseThrow(), "x").orElseThrow();
      assertFalse(merged.node(ax).executable(), "not executable: neither x is");
      for (String command : List.of("a", "b")) {
        int x = merged.child(merged.topLevel(command).orElseThrow(), "x").orElseThrow();
        assertTrue(merged.child(x, "deeper").isPresent(), command);
      }
    }

    @Test
    @DisplayName("should map a proxy node merged twice to its first match, for nodes added later")
    void mergedTwiceThenShared() {
      CommandTree backendTree =
          tree(
              b -> {
                b.literal(b.literal(b.root(), "a"), "x");
                b.literal(b.literal(b.root(), "b"), "x");
              });
      CommandTree proxyCommands =
          tree(
              b -> {
                int x = b.node(CommandNode.literal("x"));
                b.child(b.literal(b.root(), "a"), x);
                b.child(b.literal(b.root(), "b"), x);
                b.child(b.literal(b.root(), "c"), x);
              });

      CommandTree merged = backendTree.merge(proxyCommands, NameConflict.MERGE);

      int ax = merged.child(merged.topLevel("a").orElseThrow(), "x").orElseThrow();
      assertEquals(OptionalInt.of(ax), merged.child(merged.topLevel("c").orElseThrow(), "x"));
    }

    @Test
    @DisplayName("should merge two proxy commands of one name into the same backend command")
    void mergeDuplicateNames() {
      CommandTree proxyCommands =
          tree(
              b -> {
                b.literal(b.literal(b.root(), "list"), "all");
                b.literal(b.literal(b.root(), "list"), "all");
              });

      CommandTree merged = backend.merge(proxyCommands, NameConflict.MERGE);

      int list = merged.topLevel("list").orElseThrow();
      assertEquals(2, merged.node(list).children().size(), "each proxy child appended");
    }

    @Test
    @DisplayName("should add many commands at once")
    void mergeMany() {
      CommandTree many =
          tree(
              b -> {
                for (int i = 0; i < 12; i++) {
                  b.executable(b.literal(b.root(), "command" + i));
                }
              });

      CommandTree merged = backend.merge(many, NameConflict.PROXY_WINS);

      assertEquals(4 + 12, merged.topLevelNames().size());
      assertEquals("command11", merged.topLevelNames().get(15));
    }

    @Test
    @DisplayName("should change nothing when merging no command")
    void mergeNothing() {
      for (NameConflict conflict : NameConflict.values()) {
        assertEquals(backend, backend.merge(CommandTree.empty(), conflict));
      }
    }

    @Test
    @DisplayName("should keep proxy nodes shared, and append each once")
    void shareProxyNodes() {
      CommandTree shared =
          tree(
              b -> {
                int leaf = b.node(CommandNode.literal("leaf").withExecutable(true));
                b.child(b.literal(b.root(), "one"), leaf);
                b.child(b.literal(b.root(), "two"), leaf);
              });

      CommandTree merged = CommandTree.empty().merge(shared, NameConflict.PROXY_WINS);

      assertEquals(4, merged.size());
      assertEquals(
          merged.child(merged.topLevel("one").orElseThrow(), "leaf"),
          merged.child(merged.topLevel("two").orElseThrow(), "leaf"));
    }

    @Test
    @DisplayName("should merge into a vanilla tree and encode a tree that decodes back the same")
    void mergeIntoVanillaTree() {
      ProtocolVersion version = MINECRAFT_1_21_4;
      CommandTree vanilla = decode(CommandFixtures.capture(version), version);

      CommandTree merged = vanilla.merge(proxy, NameConflict.PROXY_WINS);

      assertEquals(decode(encode(merged, version), version), merged);
      assertEquals(vanilla.topLevelNames().size() + 1, merged.topLevelNames().size());
      assertTrue(merged.topLevel("friend").isPresent());
      assertEquals(1, merged.topLevelNames().stream().filter("msg"::equals).count());
      // tell and w still reach the backend's msg, which is kept for them.
      assertTrue(merged.size() > vanilla.size());
    }

    @Test
    @DisplayName("should refuse a merge that would make children loop")
    void refuseLoop() {
      // Backend: /a, whose child b is also at the top level. Proxy: /a, and /b with /a as a child.
      // Merged, the backend's b gets the backend's a as a child: a -> b -> a.
      CommandTree loopBackend =
          tree(
              b -> {
                int a = b.literal(b.root(), "a");
                b.child(b.root(), b.literal(a, "b"));
              });
      CommandTree loopProxy =
          tree(
              b -> {
                int a = b.literal(b.root(), "a");
                b.child(b.literal(b.root(), "b"), a);
              });

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> loopBackend.merge(loopProxy, NameConflict.MERGE));

      assertTrue(String.valueOf(thrown.getMessage()).contains("Children loop"), thrown::getMessage);
      assertEquals(loopBackend, loopBackend.merge(loopProxy, NameConflict.BACKEND_WINS));
    }
  }

  // ---------------------------------------------------------------------------
  // Removal, filtering, compaction
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("removal and filtering")
  class Removal {

    private final CommandTree tree =
        tree(
            b -> {
              int shared = b.node(CommandNode.literal("shared").withExecutable(true));
              int plugins = b.literal(b.root(), "plugins");
              b.child(plugins, shared);
              b.literal(plugins, "only");
              int version = b.literal(b.root(), "version");
              b.child(version, shared);
              int alias = b.literal(b.root(), "ver");
              b.redirect(alias, version);
            });

    @Test
    @DisplayName("should remove top-level commands and the nodes only they reached")
    void withoutTopLevel() {
      CommandTree removed = tree.withoutTopLevel(node -> "plugins".equals(node.name()));

      assertEquals(List.of("version", "ver"), removed.topLevelNames());
      assertEquals(4, removed.size(), "root, shared, version, ver");
      int version = removed.topLevel("version").orElseThrow();
      assertTrue(removed.child(version, "shared").isPresent());
    }

    @Test
    @DisplayName("should keep a removed command that another one redirects to")
    void keepRedirectTarget() {
      CommandTree removed = tree.withoutTopLevel(node -> "version".equals(node.name()));

      assertEquals(List.of("plugins", "ver"), removed.topLevelNames());
      int ver = removed.topLevel("ver").orElseThrow();
      int target = removed.node(ver).redirect().orElseThrow();
      assertEquals("version", removed.node(target).name());
    }

    @Test
    @DisplayName("should return the same tree when nothing is removed")
    void nothingRemoved() {
      assertSame(tree, tree.withoutTopLevel(node -> false));
      assertEquals(tree, tree.filter(node -> true));
    }

    @Test
    @DisplayName("should filter nodes at any depth, asking once about each, never about the root")
    void filter() {
      List<String> asked = new ArrayList<>();

      CommandTree filtered =
          tree.filter(
              node -> {
                asked.add(node.name());
                return !"shared".equals(node.name()) && !"version".equals(node.name());
              });

      assertEquals(List.of("plugins", "version", "ver", "shared", "only"), asked);
      CommandTree hidden =
          tree(
              b -> {
                int target = b.node(CommandNode.literal("target"));
                b.redirect(b.literal(b.root(), "alias"), target);
              });
      List<String> reached = new ArrayList<>();
      CommandTree kept = hidden.filter(node -> reached.add(node.name()));
      assertEquals(
          List.of("alias", "target"), reached, "a node only a redirect reaches is asked too");
      assertEquals(hidden, kept);
      assertEquals(List.of("plugins", "ver"), filtered.topLevelNames());
      int ver = filtered.topLevel("ver").orElseThrow();
      assertEquals(OptionalInt.empty(), filtered.node(ver).redirect(), "its target is gone");
      int plugins = filtered.topLevel("plugins").orElseThrow();
      assertEquals(OptionalInt.empty(), filtered.child(plugins, "shared"));
      assertTrue(filtered.child(plugins, "only").isPresent());
      assertEquals(4, filtered.size());
      CommandTree withoutOnly = tree.filter(node -> !"only".equals(node.name()));
      int version = withoutOnly.topLevel("version").orElseThrow();
      assertEquals(
          OptionalInt.of(version),
          withoutOnly.node(withoutOnly.topLevel("ver").orElseThrow()).redirect(),
          "a redirect to a kept node stays");
    }

    @Test
    @DisplayName("should drop unreachable nodes, keeping the others in order")
    void compact() {
      CommandTree sparse =
          CommandTree.of(
              List.of(
                  CommandNode.literal("orphan"),
                  CommandNode.literal("kept"),
                  new Root(Children.of(1, 3)),
                  CommandNode.literal("target"),
                  CommandNode.literal("alias").withRedirect(OptionalInt.of(3))),
              2);

      CommandTree compacted = sparse.compact();

      assertEquals(
          List.of(
              CommandNode.literal("kept"),
              new Root(Children.of(0, 2)),
              CommandNode.literal("target")),
          compacted.nodes());
      assertEquals(1, compacted.rootIndex());
      assertSame(compacted, compacted.compact());
    }

    @Test
    @DisplayName("should compact a tree far deeper than any stack, without recursing")
    void deepTree() {
      int depth = 200_000;
      List<CommandNode> nodes = new ArrayList<>(depth + 2);
      nodes.add(new Root(Children.of(1)));
      for (int i = 1; i <= depth; i++) {
        nodes.add(
            CommandNode.literal("n")
                .withChildren(i < depth ? Children.of(i + 1) : Children.of())
                .withRedirect(OptionalInt.of(0)));
      }
      nodes.add(CommandNode.literal("orphan"));

      CommandTree tree = CommandTree.of(nodes, 0);

      assertEquals(depth + 1, tree.compact().size());
      assertEquals(depth + 1, tree.filter(node -> true).size());
      assertEquals(1, tree.withoutTopLevel(node -> true).size());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static CommandTree tree(Consumer<CommandTree.Builder> nodes) {
    CommandTree.Builder builder = CommandTree.builder();
    nodes.accept(builder);
    return builder.build();
  }

  private static CommandTree decode(byte[] wire, ProtocolVersion version) {
    CommandTreeDecoding decoding = CommandTreeCodec.decode(Unpooled.wrappedBuffer(wire), version);
    return assertInstanceOf(Decoded.class, decoding).tree();
  }

  private static byte[] encode(CommandTree tree, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    CommandTreeCodec.encode(tree, buf, version);
    return ByteBufUtil.getBytes(buf);
  }
}
