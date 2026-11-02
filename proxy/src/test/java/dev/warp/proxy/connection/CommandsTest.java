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
package dev.warp.proxy.connection;

import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.warp.api.command.CommandExecuteEvent;
import dev.warp.api.command.CommandSource;
import dev.warp.api.command.PlayerAvailableCommandsEvent;
import dev.warp.api.command.SimpleCommand;
import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscription;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.command.CommandTree;
import dev.warp.protocol.command.CommandTreeDecoding;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.DeclareCommands;
import dev.warp.protocol.packet.play.TabCompleteRequest;
import dev.warp.protocol.packet.play.TabCompleteResponse;
import dev.warp.proxy.command.PluginCommandManager;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Commands")
class CommandsTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  private final List<SwitchHarness> harnesses = new ArrayList<>();
  private final WarpEventBus bus = new WarpEventBus(Runnable::run);
  private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
    bus.close();
  }

  @Nested
  @DisplayName("registration")
  class Registration {

    private final WarpCommandManager commands = new WarpCommandManager(Runnable::run);

    @Test
    @DisplayName("should run a command and its aliases")
    void aliases() {
      List<String> ran = new CopyOnWriteArrayList<>();
      new PluginCommandManager(commands, "lobby", s -> {})
          .register(
              LiteralArgumentBuilder.<CommandSource>literal("lobby")
                  .executes(
                      context -> {
                        ran.add("lobby");
                        return Command.SINGLE_SUCCESS;
                      }),
              "hub");

      assertTrue(commands.execute(source(), "hub").join());
      assertTrue(commands.execute(source(), "LOBBY").join());
      assertEquals(List.of("lobby", "lobby"), ran);
    }

    @Test
    @DisplayName("should refuse a name another plugin holds, naming it")
    void conflict() {
      new PluginCommandManager(commands, "first", s -> {}).register("lobby", invocation -> {});

      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  new PluginCommandManager(commands, "second", s -> {})
                      .register("hub", invocation -> {}, "lobby"));

      assertTrue(String.valueOf(error.getMessage()).contains("first"));
      assertFalse(commands.hasCommand("hub"), "nothing of the refused registration stays");
    }

    @Test
    @DisplayName("should remove a command and its aliases once its registration is closed")
    void close() {
      Subscription registration =
          new PluginCommandManager(commands, "lobby", s -> {})
              .register("lobby", invocation -> {}, "hub");

      registration.close();

      assertFalse(commands.hasCommand("lobby"));
      assertFalse(commands.hasCommand("hub"));
      assertFalse(commands.execute(source(), "lobby").join());
    }

    @Test
    @DisplayName("should let a plugin remove the proxy's own /server")
    void unregisterBuiltIn() {
      WarpCommandManager withServer =
          TestLoginContexts.commands(
              new dev.warp.proxy.server.ServerRegistry(java.util.Map.of(), null, List.of()));

      assertTrue(new PluginCommandManager(withServer, "router", s -> {}).unregister("server"));

      assertFalse(withServer.owns("server lobby"));
    }

    @Test
    @DisplayName("should hand a simple command its words, and suggest the last one")
    void simple() {
      List<SimpleCommand.Invocation> ran = new CopyOnWriteArrayList<>();
      new PluginCommandManager(commands, "friends", s -> {})
          .register(
              "friend",
              new SimpleCommand() {
                @Override
                public void execute(Invocation invocation) {
                  ran.add(invocation);
                }

                @Override
                public List<String> suggest(Invocation invocation) {
                  return List.of("Steve", "Alex");
                }
              });

      commands.execute(source(), "friend add Steve").join();

      assertEquals(List.of("add", "Steve"), ran.getFirst().arguments());
      List<String> suggested =
          commands.suggest(source(), "friend add ").join().getList().stream()
              .map(s -> s.getText())
              .toList();
      assertEquals(List.of("Alex", "Steve"), suggested);
    }
  }

  @Nested
  @DisplayName("typed by a player")
  class Typed {

    @Test
    @DisplayName("should run a proxy command and keep it from the backend")
    void proxyCommand() {
      SwitchHarness setup = playingOnLobby();
      List<String> ran = new CopyOnWriteArrayList<>();
      plugin(setup)
          .register(
              "ping",
              invocation -> ran.add(((dev.warp.api.player.Player) invocation.source()).username()));

      clientHandler(setup).handle(new ChatCommand("ping", new byte[0], 0));
      clientHandler(setup).readComplete();

      assertEquals(List.of("Steve"), ran);
      assertEquals(0, setup.sentToBackend(setup.current()));
    }

    @Test
    @DisplayName("should send a backend command to the backend untouched")
    void backendCommand() {
      SwitchHarness setup = playingOnLobby();

      clientHandler(setup).handle(new ChatCommand("tell Alex hi", new byte[0], 0));
      clientHandler(setup).readComplete();

      assertEquals(1, setup.sentToBackend(setup.current()));
    }

    @Test
    @DisplayName("should block a command a handler denies, on every server")
    void denied() {
      SwitchHarness setup = playingOnLobby();
      List<String> seen = new CopyOnWriteArrayList<>();
      events.subscribe(
          CommandExecuteEvent.class,
          event -> {
            seen.add(event.commandLine());
            event.setResult(new CommandExecuteEvent.Denied());
          });

      clientHandler(setup).handle(new ChatCommand("skin set Notch", new byte[0], 0));
      clientHandler(setup).readComplete();

      assertEquals(List.of("skin set Notch"), seen);
      assertEquals(0, setup.sentToBackend(setup.current()));
    }

    @Test
    @DisplayName("should answer suggestions for a proxy command itself")
    void suggestions() {
      SwitchHarness setup = playingOnLobby();
      setup.sentToClient();

      clientHandler(setup).handle(new TabCompleteRequest(7, "/server lo"));

      SwitchHarness.Sent answer = setup.sentToClient().getFirst();
      TabCompleteResponse response = answer.as(TabCompleteResponse.class);
      assertEquals(7, response.transactionId());
      assertEquals(0, setup.sentToBackend(setup.current()));
    }
  }

  @Nested
  @DisplayName("command list")
  class CommandList {

    @Test
    @DisplayName("should merge the proxy's commands into the backend's list")
    void merged() {
      SwitchHarness setup = playingOnLobby();
      setup.sentToClient();

      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));

      List<String> names = shownCommands(setup);
      assertTrue(names.contains("server"), names.toString());
      assertTrue(names.contains("tell"), names.toString());
    }

    @Test
    @DisplayName("should hide the commands a handler removes")
    void hidden() {
      SwitchHarness setup = playingOnLobby();
      events.subscribe(
          PlayerAvailableCommandsEvent.class, event -> event.removeIf(name -> name.equals("tell")));
      setup.sentToClient();

      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));

      List<String> names = shownCommands(setup);
      assertFalse(names.contains("tell"), names.toString());
      assertTrue(names.contains("server"));
    }

    @Test
    @DisplayName("should leave out the commands a player may not use")
    void permissions() {
      SwitchHarness setup = playingOnLobby();
      plugin(setup)
          .register(
              LiteralArgumentBuilder.<CommandSource>literal("admin")
                  .requires(source -> source.hasPermission("proxy.admin"))
                  .executes(context -> Command.SINGLE_SUCCESS));
      setup.sentToClient();

      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));

      assertFalse(shownCommands(setup).contains("admin"));
    }

    @Test
    @DisplayName("should show a command once a permission provider grants it")
    void granted() {
      SwitchHarness setup = playingOnLobby();
      plugin(setup)
          .register(
              LiteralArgumentBuilder.<CommandSource>literal("admin")
                  .requires(source -> source.hasPermission("proxy.admin"))
                  .executes(context -> Command.SINGLE_SUCCESS));
      new dev.warp.proxy.permission.PluginPermissions(
              setup.player.loginContext().permissions(), "perms", s -> {})
          .register(
              0,
              (player, permission) ->
                  dev.warp.api.permission.Tristate.of(permission.equals("proxy.admin")));
      setup.sentToClient();

      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));

      assertTrue(shownCommands(setup).contains("admin"));
      assertTrue(setup.player.api().hasPermission("proxy.admin"));
    }

    @Test
    @DisplayName("should show an argument type the client lacks as text the proxy completes")
    void fallbackArgument() {
      SwitchHarness setup = playingOnLobby();
      plugin(setup)
          .register(
              LiteralArgumentBuilder.<CommandSource>literal("wait")
                  .then(
                      RequiredArgumentBuilder.<CommandSource, Long>argument(
                              "ticks", LongArgumentType.longArg())
                          .executes(context -> Command.SINGLE_SUCCESS)));
      WarpCommandManager commands = setup.player.loginContext().commands();

      CommandTree old = commands.treeFor(setup.player.api(), ProtocolVersion.MINECRAFT_1_14);
      CommandTree current = commands.treeFor(setup.player.api(), VERSION);

      assertEquals("brigadier:string", parserOf(old, "ticks"));
      assertEquals("brigadier:long", parserOf(current, "ticks"));
    }

    private String parserOf(CommandTree tree, String argument) {
      return tree.nodes().stream()
          .filter(
              node ->
                  node instanceof dev.warp.protocol.command.CommandNode.Argument a
                      && a.name().equals(argument))
          .map(
              node -> ((dev.warp.protocol.command.CommandNode.Argument) node).parser().identifier())
          .findFirst()
          .orElseThrow();
    }

    private CommandTree backendTree() {
      CommandTree.Builder builder = CommandTree.builder();
      int tell = builder.literal(builder.root(), "tell");
      builder.executable(tell);
      return builder.build();
    }

    private List<String> shownCommands(SwitchHarness setup) {
      DeclareCommands packet = setup.sentToClient().getFirst().as(DeclareCommands.class);
      CommandTreeDecoding decoding = packet.tree(VERSION);
      return assertInstanceOf(CommandTreeDecoding.Decoded.class, decoding).tree().topLevelNames();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static CommandSource source() {
    return new CommandSource() {
      @Override
      public void sendMessage(net.kyori.adventure.text.Component message) {}

      @Override
      public boolean hasPermission(String permission) {
        return true;
      }
    };
  }

  private PluginCommandManager plugin(SwitchHarness setup) {
    return new PluginCommandManager(setup.player.loginContext().commands(), "test", s -> {});
  }

  private static ClientPlaySessionHandler clientHandler(SwitchHarness setup) {
    return (ClientPlaySessionHandler)
        java.util.Objects.requireNonNull(setup.client.sessionHandler());
  }

  private SwitchHarness playingOnLobby() {
    SwitchHarness setup = new SwitchHarness(VERSION, List.of(), ProtocolState.PLAY);
    when(setup.player.loginContext().events()).thenReturn(bus);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    return setup;
  }
}
