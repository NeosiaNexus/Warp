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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.warp.api.Warp;
import dev.warp.api.command.CommandSource;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.permission.Tristate;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginManager;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;
import dev.warp.api.server.ServerPing;
import dev.warp.api.server.ServerRegistry;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.i18n.PlayerMessages;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.brigadier.suggestion.Suggestion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * A proxy for the administration commands' tests: servers and players are mocks that live in maps,
 * the commands run on the calling thread, and sources record what they are told.
 */
final class AdminFixture {

  static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

  /** A message rendered in English, as the console gets it, in plain text. */
  static String plain(Component message) {
    return PLAIN.serialize(PlayerMessages.english(message));
  }

  final Warp proxy = mock(Warp.class);
  final ServerRegistry servers = mock(ServerRegistry.class);
  final PluginManager plugins = mock(PluginManager.class);
  final List<PluginContainer> pluginList = new ArrayList<>();
  final Map<String, RegisteredServer> serverMap = new LinkedHashMap<>();
  final Map<String, Player> playerMap = new LinkedHashMap<>();
  final Map<Player, @Nullable RegisteredServer> locations = new LinkedHashMap<>();
  final WarpCommandManager commands = new WarpCommandManager(Runnable::run);
  final FakeControl control = new FakeControl();
  private volatile boolean draining;
  private volatile @Nullable DrainStatus drainStatus;

  AdminFixture() {
    lenient()
        .when(servers.server(anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(
                    serverMap.get(invocation.<String>getArgument(0).toLowerCase(Locale.ROOT))));
    lenient()
        .when(servers.servers())
        .thenAnswer(
            invocation ->
                serverMap.values().stream()
                    .sorted(Comparator.comparing(RegisteredServer::name))
                    .toList());
    lenient().when(proxy.servers()).thenReturn(servers);
    lenient().when(proxy.plugins()).thenReturn(plugins);
    lenient().when(plugins.plugins()).thenAnswer(invocation -> List.copyOf(pluginList));
    lenient().when(proxy.players()).thenAnswer(invocation -> List.copyOf(playerMap.values()));
    lenient().when(proxy.playerCount()).thenAnswer(invocation -> playerMap.size());
    lenient()
        .when(proxy.player(anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(
                    playerMap.get(invocation.<String>getArgument(0).toLowerCase(Locale.ROOT))));
    lenient().when(proxy.isDraining()).thenAnswer(invocation -> draining);
    lenient()
        .when(proxy.drainStatus())
        .thenAnswer(invocation -> drainStatus != null ? drainStatus : DrainStatus.notDraining(0));
    ProxyCommands.register(commands, servers);
    ProxyCommands.registerAdmin(commands, proxy, control, mock(AntiBotService.class));
  }

  /** Makes the proxy drain, as the status says. */
  void draining(DrainStatus status) {
    draining = true;
    drainStatus = status;
  }

  // ---------------------------------------------------------------------------
  // Servers and players
  // ---------------------------------------------------------------------------

  /** Adds a server that answers pings. */
  RegisteredServer server(String name) {
    RegisteredServer server = mock(RegisteredServer.class);
    lenient().when(server.name()).thenReturn(name);
    lenient().when(server.players()).thenAnswer(invocation -> playersOn(server));
    lenient().when(server.playerCount()).thenAnswer(invocation -> playersOn(server).size());
    lenient()
        .when(server.ping())
        .thenReturn(
            CompletableFuture.completedFuture(
                new ServerPing(
                    new ServerPing.Version("Paper", 770), null, Component.empty(), null)));
    serverMap.put(name.toLowerCase(Locale.ROOT), server);
    return server;
  }

  private List<Player> playersOn(RegisteredServer server) {
    List<Player> on = new ArrayList<>();
    locations.forEach(
        (player, where) -> {
          if (server.equals(where)) {
            on.add(player);
          }
        });
    return on;
  }

  /**
   * Adds a player on a server, or on none. Its moves succeed, and move it, unless {@link
   * #moves(Player, ConnectionResult)} says otherwise.
   */
  Player player(String name, @Nullable RegisteredServer on) {
    Player player = mock(Player.class);
    List<Component> received = new CopyOnWriteArrayList<>();
    lenient().when(player.username()).thenReturn(name);
    lenient()
        .when(player.uniqueId())
        .thenReturn(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    lenient().when(player.ping()).thenReturn(34L);
    lenient()
        .when(player.protocolVersion())
        .thenReturn(ProtocolVersion.MINECRAFT_1_21_4.protocol());
    lenient().when(player.remoteAddress()).thenReturn(new InetSocketAddress("203.0.113.7", 50000));
    lenient()
        .when(player.currentServer())
        .thenAnswer(
            invocation -> {
              RegisteredServer where = locations.get(player);
              if (where == null) {
                return Optional.empty();
              }
              ServerConnection connection = mock(ServerConnection.class);
              lenient().when(connection.server()).thenReturn(where);
              return Optional.of(connection);
            });
    lenient()
        .when(player.connect(any()))
        .thenAnswer(
            invocation -> {
              RegisteredServer target = invocation.getArgument(0);
              locations.put(player, target);
              return CompletableFuture.completedFuture(new ConnectionResult.Connected(target));
            });
    lenient()
        .doAnswer(
            invocation -> {
              received.add(invocation.getArgument(0));
              return null;
            })
        .when(player)
        .sendMessage(any(Component.class));
    lenient().when(player.hasPermission(anyString())).thenAnswer(invocation -> false);
    lenient().when(player.permissionValue(anyString())).thenReturn(Tristate.UNDEFINED);
    receivedBy.put(player, received);
    playerMap.put(name.toLowerCase(Locale.ROOT), player);
    locations.put(player, on);
    return player;
  }

  private final Map<Player, List<Component>> receivedBy = new LinkedHashMap<>();

  /** Makes a player's moves end with a result, without moving it. */
  void moves(Player player, ConnectionResult result) {
    lenient().doReturn(CompletableFuture.completedFuture(result)).when(player).connect(any());
  }

  /** What a player was told, in plain text: rendered in English, as its client would be. */
  List<String> told(Player player) {
    return receivedBy.getOrDefault(player, List.of()).stream()
        .map(message -> PLAIN.serialize(PlayerMessages.english(message)))
        .toList();
  }

  /** What a player was told, as components. */
  List<Component> components(Player player) {
    return List.copyOf(receivedBy.getOrDefault(player, List.of()));
  }

  // ---------------------------------------------------------------------------
  // Running commands
  // ---------------------------------------------------------------------------

  /** Runs a command line; returns whether a proxy command took it. */
  boolean run(CommandSource source, String commandLine) {
    return commands.execute(source, commandLine).join();
  }

  /** The suggestions for a command line being typed. */
  List<String> suggest(CommandSource source, String commandLine) {
    return commands.suggest(source, commandLine).join().getList().stream()
        .map(Suggestion::getText)
        .toList();
  }

  /** The console: every permission. */
  static Source console() {
    return new Source(null);
  }

  /** A source with these permissions only, as a player of a permission plugin would be. */
  static Source granted(String... permissions) {
    return new Source(Set.of(permissions));
  }

  /** A command source that records what it is told. */
  static final class Source implements CommandSource {

    private final @Nullable Set<String> permissions;
    final List<Component> received = new CopyOnWriteArrayList<>();

    Source(@Nullable Set<String> permissions) {
      this.permissions = permissions;
    }

    @Override
    public void sendMessage(Component message) {
      received.add(message);
    }

    @Override
    public boolean hasPermission(String permission) {
      return permissions == null || permissions.contains(permission);
    }

    /** Everything it was told, one line per message. */
    List<String> told() {
      return received.stream().map(AdminFixture::plain).toList();
    }

    /** The last thing it was told, rendered in English as the console renders it by default. */
    String last() {
      return plain(received.getLast());
    }

    /** The last thing it was told, as a component rendered in English. */
    Component lastComponent() {
      return PlayerMessages.english(received.getLast());
    }
  }

  // ---------------------------------------------------------------------------
  // Control
  // ---------------------------------------------------------------------------

  /** The proxy's side of {@code /shutdown} and {@code /warp}, recording shutdowns. */
  static final class FakeControl implements ProxyControl {

    final List<@Nullable Component> shutdowns = new ArrayList<>();
    private volatile @Nullable RuntimeException refusal;
    private final DrainConfig drain =
        new DrainConfig(
            new DrainConfig.Target("next.example.net", 25565),
            false,
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            DrainConfig.CannotTransfer.KEEP,
            DrainConfig.NewPlayers.ACCEPT,
            Duration.ofMinutes(30),
            Component.text("This proxy is restarting. Please reconnect."));

    /** Makes the next shutdowns throw. */
    void refuse(RuntimeException refusal) {
      this.refusal = refusal;
    }

    /** What /warp startup shows, or {@code null} for no timings. */
    volatile dev.warp.proxy.console.@Nullable StartupTimings startup;

    @Override
    public dev.warp.proxy.console.@Nullable StartupTimings startup() {
      return startup;
    }

    @Override
    public Duration uptime() {
      return Duration.ofHours(27).plusMinutes(5);
    }

    @Override
    public String compression() {
      return "libdeflate 1.24 (native)";
    }

    @Override
    public DrainConfig drain() {
      return drain;
    }

    @Override
    public CompletableFuture<DrainStatus> shutdown(@Nullable Component reason) {
      RuntimeException refused = refusal;
      if (refused != null) {
        throw refused;
      }
      shutdowns.add(reason);
      return new CompletableFuture<>();
    }
  }

  /** The text every hover of a component and its children shows, one per line. */
  static String hovers(Component component) {
    StringBuilder text = new StringBuilder();
    collectHovers(PlayerMessages.english(component), text);
    return text.toString();
  }

  private static void collectHovers(Component component, StringBuilder text) {
    HoverEvent<?> hover = component.hoverEvent();
    if (hover != null && hover.value() instanceof Component shown) {
      text.append(PLAIN.serialize(shown)).append('\n');
    }
    for (Component child : component.children()) {
      collectHovers(child, text);
    }
  }

  /** The players of a collection, by name. */
  static List<String> names(Collection<Player> players) {
    return players.stream().map(Player::username).sorted().toList();
  }
}
