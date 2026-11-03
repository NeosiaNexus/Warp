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
import dev.warp.api.logging.LogRecord;
import dev.warp.api.permission.Permissions;
import dev.warp.api.permission.Tristate;
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
import dev.warp.proxy.config.LogFormat;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.logging.LogCapture;
import dev.warp.proxy.permission.PluginPermissions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.netty.channel.embedded.EmbeddedChannel;
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
    @DisplayName("should tell its listener once commands are registered or removed")
    void changes() {
      AtomicInteger changes = new AtomicInteger();
      commands.onChange(changes::incrementAndGet);

      Subscription registration =
          commands.register(
              "test",
              LiteralArgumentBuilder.<CommandSource>literal("hub")
                  .executes(context -> Command.SINGLE_SUCCESS));
      registration.close();
      boolean removedAgain = commands.unregister("hub");

      assertEquals(2, changes.get());
      assertFalse(removedAgain);
    }

    @Test
    @DisplayName("should let a plugin remove the proxy's own /server")
    void unregisterBuiltIn() {
      WarpCommandManager withServer =
          TestLoginContexts.commands(
              new dev.warp.proxy.server.ServerRegistry(java.util.Map.of(), null, List.of()));

      assertTrue(new PluginCommandManager(withServer, "router", s -> {}).unregister("server"));

      assertFalse(withServer.owns(source(), "server lobby"));
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
    @DisplayName("should log every command a player sends when logging.commands is on")
    void logged() throws Exception {
      SwitchHarness setup = playingOnLobby();
      when(setup.player.loginContext().logging())
          .thenReturn(new LoggingConfig(LogFormat.TEXT, true, false, true));

      try (LogCapture logs = new LogCapture()) {
        clientHandler(setup).handle(new ChatCommand("tell Alex hi", new byte[0], 0));
        clientHandler(setup).readComplete();

        LogRecord record =
            logs.await(r -> r.message().equals("Steve ran the command /tell Alex hi"));
        assertEquals("Steve", record.context().get(ConnectedPlayer.LOG_PLAYER));
      }
    }

    @Test
    @DisplayName("should not log commands by default: they may carry passwords")
    void notLoggedByDefault() throws Exception {
      SwitchHarness setup = playingOnLobby();

      try (LogCapture logs = new LogCapture()) {
        clientHandler(setup).handle(new ChatCommand("login hunter2", new byte[0], 0));
        clientHandler(setup).readComplete();

        assertFalse(logs.anyAfterSettling(r -> r.message().contains("hunter2")));
      }
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
  }

  @Nested
  @DisplayName("command list refresh")
  class Refresh {

    /** The node the fake permission provider grants, flipped by the tests. */
    private final AtomicBoolean admin = new AtomicBoolean();

    @Test
    @DisplayName("should show a command once its permission is granted and the provider says so")
    void granted() {
      SwitchHarness setup = playingOnLobby();
      Permissions permissions = adminCommandAndProvider(setup);
      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));
      assertFalse(shownCommands(setup).contains("admin"));

      admin.set(true);
      permissions.changed(setup.player.api());
      afterRefreshDelay(setup);

      List<String> names = shownCommands(setup);
      assertTrue(names.contains("admin"), names.toString());
      assertTrue(names.contains("tell"), "the server's commands stay: " + names);
    }

    @Test
    @DisplayName("should hide a command once its permission is taken and the provider says so")
    void taken() {
      SwitchHarness setup = playingOnLobby();
      admin.set(true);
      Permissions permissions = adminCommandAndProvider(setup);
      setup.player.loginContext().permissions().trackPlayers(() -> List.of(setup.player.api()));
      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));
      assertTrue(shownCommands(setup).contains("admin"));

      admin.set(false);
      permissions.allChanged();
      afterRefreshDelay(setup);

      assertFalse(shownCommands(setup).contains("admin"));
    }

    @Test
    @DisplayName("should send one list for a burst of changes, once the delay is over")
    void debounced() {
      SwitchHarness setup = playingOnLobby();
      Permissions permissions = adminCommandAndProvider(setup);
      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));
      setup.sentToClient();

      admin.set(true);
      for (int i = 0; i < 5; i++) {
        permissions.changed(setup.player.api());
      }
      setup.runPendingTasks();
      List<SwitchHarness.Sent> early = setup.sentToClient();
      afterRefreshDelay(setup);
      List<SwitchHarness.Sent> sent = setup.sentToClient();

      assertTrue(early.isEmpty(), early.toString());
      assertEquals(1, sent.size(), sent.toString());
      assertEquals(DeclareCommands.class, sent.getFirst().type());
    }

    @Test
    @DisplayName("should refresh again for a change after a refresh started")
    void later() {
      SwitchHarness setup = playingOnLobby();
      Permissions permissions = adminCommandAndProvider(setup);
      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));
      setup.sentToClient();
      permissions.changed(setup.player.api());
      afterRefreshDelay(setup);
      assertFalse(shownCommands(setup).contains("admin"));

      admin.set(true);
      permissions.changed(setup.player.api());
      afterRefreshDelay(setup);

      assertTrue(shownCommands(setup).contains("admin"));
    }

    @Test
    @DisplayName("should run the handlers of PlayerAvailableCommandsEvent again")
    void eventFiredAgain() {
      SwitchHarness setup = playingOnLobby();
      List<String> offered = new CopyOnWriteArrayList<>();
      events.subscribe(
          PlayerAvailableCommandsEvent.class,
          event -> {
            offered.add(String.join(",", event.commands()));
            event.removeIf(name -> name.equals("tell"));
          });
      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));
      setup.sentToClient();

      setup.player.api().refreshCommands();
      afterRefreshDelay(setup);

      assertEquals(2, offered.size(), offered.toString());
      assertFalse(shownCommands(setup).contains("tell"));
    }

    @Test
    @DisplayName("should send nothing before the server sent a list")
    void noServerList() {
      SwitchHarness setup = playingOnLobby();
      setup.sentToClient();

      setup.player.api().refreshCommands();
      afterRefreshDelay(setup);

      assertTrue(setup.sentToClient().isEmpty());
    }

    @Test
    @DisplayName("should drop a refresh when the player leaves the server before it runs")
    void leftMeanwhile() {
      SwitchHarness setup = playingOnLobby();
      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));
      setup.sentToClient();

      setup.player.api().refreshCommands();
      // Moving to the configuration phase of a server switch: no play packet may reach the client.
      setup.player.playOn(null);
      afterRefreshDelay(setup);

      assertTrue(setup.sentToClient().isEmpty());
    }

    @Test
    @DisplayName("should send nothing while the client is in the configuration phase")
    void configuring() {
      SwitchHarness setup = playingOnLobby();
      setup.receive(setup.current(), frame(VERSION, DeclareCommands.of(backendTree(), VERSION)));
      setup.sentToClient();

      setup.client.setState(ProtocolState.CONFIGURATION);
      setup.player.api().refreshCommands();
      afterRefreshDelay(setup);

      assertTrue(setup.sentToClient().isEmpty());
    }

    @Test
    @DisplayName("should do nothing for a client older than 1.13, which has no command list")
    void beforeDeclareCommands() {
      SwitchHarness setup = playingOnLobby(ProtocolVersion.MINECRAFT_1_12_2);
      setup.sentToClient();

      setup.player.api().refreshCommands();
      afterRefreshDelay(setup);

      assertTrue(setup.sentToClient().isEmpty());
    }

    /**
     * Registers {@code /admin}, behind {@code proxy.admin}, and a provider granting it on demand.
     */
    private Permissions adminCommandAndProvider(SwitchHarness setup) {
      plugin(setup)
          .register(
              LiteralArgumentBuilder.<CommandSource>literal("admin")
                  .requires(source -> source.hasPermission("proxy.admin"))
                  .executes(context -> Command.SINGLE_SUCCESS));
      Permissions permissions =
          new PluginPermissions(setup.player.loginContext().permissions(), "perms", s -> {});
      var _ =
          permissions.register(
              0,
              (player, permission) ->
                  permission.equals("proxy.admin") ? Tristate.of(admin.get()) : Tristate.UNDEFINED);
      setup.sentToClient();
      return permissions;
    }

    private void afterRefreshDelay(SwitchHarness setup) {
      ((EmbeddedChannel) setup.client.channel())
          .advanceTimeBy(ConnectedPlayer.COMMAND_REFRESH_DELAY.toMillis(), TimeUnit.MILLISECONDS);
      setup.runPendingTasks();
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

  private static CommandTree backendTree() {
    CommandTree.Builder builder = CommandTree.builder();
    int tell = builder.literal(builder.root(), "tell");
    builder.executable(tell);
    return builder.build();
  }

  private static List<String> shownCommands(SwitchHarness setup) {
    DeclareCommands packet = setup.sentToClient().getFirst().as(DeclareCommands.class);
    CommandTreeDecoding decoding = packet.tree(VERSION);
    return assertInstanceOf(CommandTreeDecoding.Decoded.class, decoding).tree().topLevelNames();
  }

  private PluginCommandManager plugin(SwitchHarness setup) {
    return new PluginCommandManager(setup.player.loginContext().commands(), "test", s -> {});
  }

  private static ClientPlaySessionHandler clientHandler(SwitchHarness setup) {
    return (ClientPlaySessionHandler)
        java.util.Objects.requireNonNull(setup.client.sessionHandler());
  }

  private SwitchHarness playingOnLobby() {
    return playingOnLobby(VERSION);
  }

  private SwitchHarness playingOnLobby(ProtocolVersion version) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY);
    when(setup.player.loginContext().events()).thenReturn(bus);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    // In a game, as after its Join Game: the proxy's messages go out at once.
    setup.player.joinedGame();
    return setup;
  }
}
