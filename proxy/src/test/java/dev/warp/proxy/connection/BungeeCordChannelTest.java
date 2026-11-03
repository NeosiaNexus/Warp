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

import static dev.warp.proxy.connection.SwitchHarness.CREATIVE;
import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscription;
import dev.warp.api.event.player.ServerPreConnectEvent;
import dev.warp.api.messaging.Messaging.Origin;
import dev.warp.api.messaging.PluginMessageEvent;
import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.messaging.BungeeCordChannel;
import dev.warp.proxy.messaging.BungeeCordSettings;
import dev.warp.proxy.messaging.BungeeCordSubchannel;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.messaging.PluginMessaging;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.ServerRegistry;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BungeeCord channel")
class BungeeCordChannelTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion LEGACY = ProtocolVersion.MINECRAFT_1_12_2;

  private final WarpEventBus bus = new WarpEventBus(Runnable::run);
  private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});
  private final List<SwitchHarness> harnesses = new ArrayList<>();
  private final List<Subscription> registrations = new ArrayList<>();

  private PlayerRegistry players = new PlayerRegistry();
  private ServerRegistry servers = new ServerRegistry(Map.of(), null, List.of());
  private ChannelRegistry channels = new ChannelRegistry();

  /** Steve, who carries the requests: he plays on the lobby. */
  private SwitchHarness steve = harness("Steve", VERSION);

  /** Steve's connection to the lobby. */
  private MinecraftConnection lobby = steve.client;

  @AfterEach
  void tearDown() {
    registrations.forEach(Subscription::close);
    harnesses.forEach(SwitchHarness::close);
    bus.close();
  }

  // ---------------------------------------------------------------------------
  // Queries
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("queries")
  class Queries {

    @Test
    @DisplayName("IP: answers the carrying player's address and port")
    void ip() {
      start(BungeeCordSettings.DEFAULTS);

      byte[] reply = ask(utf("IP"));

      String host = steve.player.remoteAddress().getHostString();
      assertArrayEquals(utf("IP", host, 50000), reply);
    }

    @Test
    @DisplayName("IPOther: answers another player's name as it is, address and port")
    void ipOther() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);

      byte[] reply = ask(utf("IPOther", "alex"));

      String host = alex.player.remoteAddress().getHostString();
      assertArrayEquals(utf("IPOther", "Alex", host, 50000), reply);
      assertTrue(asked(utf("IPOther", "nobody")).isEmpty(), "nothing for an offline player");
    }

    @Test
    @DisplayName("PlayerCount: answers the proxy's count, a server's, nothing for an unknown one")
    void playerCount() {
      start(BungeeCordSettings.DEFAULTS);
      join("Alex", VERSION, SURVIVAL);

      assertArrayEquals(utf("PlayerCount", "ALL", 2), ask(utf("PlayerCount", "ALL")));
      assertArrayEquals(utf("PlayerCount", "survival", 1), ask(utf("PlayerCount", "Survival")));
      assertArrayEquals(utf("PlayerCount", "creative", 0), ask(utf("PlayerCount", "creative")));
      assertTrue(asked(utf("PlayerCount", "nowhere")).isEmpty());
    }

    @Test
    @DisplayName("PlayerList: answers the names, comma separated, nothing for an unknown server")
    void playerList() {
      start(BungeeCordSettings.DEFAULTS);
      join("Alex", VERSION, SURVIVAL);

      byte[] all = ask(utf("PlayerList", "ALL"));
      assertTrue(
          List.of(utf("PlayerList", "ALL", "Steve, Alex"), utf("PlayerList", "ALL", "Alex, Steve"))
              .stream()
              .anyMatch(expected -> Arrays.equals(expected, all)),
          "both players, in any order");
      assertArrayEquals(utf("PlayerList", "survival", "Alex"), ask(utf("PlayerList", "survival")));
      assertArrayEquals(utf("PlayerList", "creative", ""), ask(utf("PlayerList", "creative")));
      assertTrue(asked(utf("PlayerList", "nowhere")).isEmpty());
    }

    @Test
    @DisplayName("GetServers: answers every server's name, sorted, comma separated")
    void getServers() {
      start(BungeeCordSettings.DEFAULTS);

      byte[] reply = ask(utf("GetServers"));

      assertArrayEquals(utf("GetServers", "creative, lobby, survival"), reply);
      // Pinned once in hex, as Guava's ByteArrayDataOutput writes it for BungeeCord.
      assertEquals(
          "000a47657453657276657273"
              + "0019"
              + HexFormat.of()
                  .formatHex("creative, lobby, survival".getBytes(StandardCharsets.UTF_8)),
          HexFormat.of().formatHex(reply));
    }

    @Test
    @DisplayName("GetPlayerServer: answers the name as asked, with an empty server if offline")
    void getPlayerServer() {
      start(BungeeCordSettings.DEFAULTS);
      join("Alex", VERSION, SURVIVAL);

      assertArrayEquals(
          utf("GetPlayerServer", "alex", "survival"), ask(utf("GetPlayerServer", "alex")));
      assertArrayEquals(
          utf("GetPlayerServer", "nobody", ""), ask(utf("GetPlayerServer", "nobody")));
    }

    @Test
    @DisplayName("GetServer: answers the name of the server that asks")
    void getServer() {
      start(BungeeCordSettings.DEFAULTS);

      assertArrayEquals(utf("GetServer", "lobby"), ask(utf("GetServer")));
    }

    @Test
    @DisplayName("UUID: answers the carrying player's UUID without dashes")
    void uuid() {
      start(BungeeCordSettings.DEFAULTS);

      byte[] reply = ask(utf("UUID"));

      assertArrayEquals(utf("UUID", undashed(steve)), reply);
    }

    @Test
    @DisplayName("UUIDOther: answers another player's name as it is and UUID without dashes")
    void uuidOther() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);

      assertArrayEquals(utf("UUIDOther", "Alex", undashed(alex)), ask(utf("UUIDOther", "ALEX")));
      assertTrue(asked(utf("UUIDOther", "nobody")).isEmpty());
    }

    @Test
    @DisplayName("ServerIP: answers a server's name, host as configured and port as a short")
    void serverIp() {
      start(BungeeCordSettings.DEFAULTS);

      byte[] reply = ask(utf("ServerIP", "survival"));

      String host = SURVIVAL.address().getHostString();
      assertArrayEquals(utf("ServerIP", "survival", host, (short) 25002), reply);
      assertTrue(asked(utf("ServerIP", "nowhere")).isEmpty());
    }

    @Test
    @DisplayName("should answer on the legacy channel name before 1.13")
    void legacyName() {
      steve = harness("Steve", LEGACY);
      start(BungeeCordSettings.DEFAULTS);

      request(utf("GetServer"));

      PlayPluginMessage reply = bungeeCordMessages(steve, lobby).getFirst();
      assertEquals("BungeeCord", reply.channel());
      assertArrayEquals(utf("GetServer", "lobby"), reply.data());
    }

    @Test
    @DisplayName("should take the modern name from a legacy server, and the legacy from a modern")
    void eitherName() {
      start(BungeeCordSettings.DEFAULTS);

      steve.receive(lobby, frame(VERSION, new PlayPluginMessage("BungeeCord", utf("GetServer"))));

      PlayPluginMessage reply = bungeeCordMessages(steve, lobby).getFirst();
      assertEquals("bungeecord:main", reply.channel(), "the connection's own name");
    }

    @Test
    @DisplayName("should never relay the channel to the client")
    void notRelayed() {
      start(BungeeCordSettings.DEFAULTS);
      steve.sentToClient();

      request(utf("GetServer"));
      request(utf("NoSuchSubchannel"));

      assertTrue(steve.sentToClient().isEmpty());
    }

    @Test
    @DisplayName("should ignore a malformed request without disconnecting anyone")
    void malformed() {
      start(BungeeCordSettings.DEFAULTS);

      request(new byte[] {0, 7, 'C', 'o'});
      request(utf("ServerIP"));

      assertTrue(bungeeCordMessages(steve, lobby).isEmpty());
      assertTrue(steve.client.channel().isActive());
      assertTrue(lobby.channel().isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("actions")
  class Actions {

    @Test
    @DisplayName("Connect: moves the carrying player through ServerPreConnectEvent")
    void connect() {
      start(BungeeCordSettings.DEFAULTS);
      List<ServerPreConnectEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(ServerPreConnectEvent.class, fired::add);

      request(utf("Connect", "Survival"));

      ServerPreConnectEvent event = fired.getFirst();
      assertSame(steve.player.api(), event.player());
      assertEquals("survival", event.originalServer().name());
      assertEquals("lobby", event.previousServer().orElseThrow().name());
    }

    @Test
    @DisplayName("Connect: keeps the player where it is and tells it when a plugin denies it")
    void connectDenied() {
      start(BungeeCordSettings.DEFAULTS);
      events.subscribe(
          ServerPreConnectEvent.class,
          event -> event.setResult(new ServerPreConnectEvent.Denied()));
      steve.sentToClient();

      request(utf("Connect", "survival"));

      assertEquals(List.of(SystemChatMessage.class), types(steve.sentToClient()));
      assertEquals("lobby", Objects.requireNonNull(steve.player.currentServer()).name());
      assertTrue(steve.client.channel().isActive());
    }

    @Test
    @DisplayName("Connect: does nothing for an unknown server")
    void connectUnknown() {
      start(BungeeCordSettings.DEFAULTS);
      List<ServerPreConnectEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(ServerPreConnectEvent.class, fired::add);

      request(utf("Connect", "nowhere"));

      assertTrue(fired.isEmpty());
      assertEquals("lobby", Objects.requireNonNull(steve.player.currentServer()).name());
    }

    @Test
    @DisplayName("ConnectOther: moves another player through ServerPreConnectEvent")
    void connectOther() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, CREATIVE);
      List<ServerPreConnectEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(ServerPreConnectEvent.class, fired::add);

      request(utf("ConnectOther", "alex", "survival"));

      ServerPreConnectEvent event = fired.getFirst();
      assertSame(alex.player.api(), event.player());
      assertEquals("survival", event.originalServer().name());
    }

    @Test
    @DisplayName("Message: sends legacy text to a player")
    void message() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      alex.sentToClient();
      steve.sentToClient();

      request(utf("Message", "Alex", "§aHello"));

      assertEquals(List.of(SystemChatMessage.class), types(alex.sentToClient()));
      assertTrue(steve.sentToClient().isEmpty());
    }

    @Test
    @DisplayName("Message: sends to every player with ALL")
    void messageAll() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      alex.sentToClient();
      steve.sentToClient();

      request(utf("Message", "ALL", "Hello"));

      assertEquals(List.of(SystemChatMessage.class), types(alex.sentToClient()));
      assertEquals(List.of(SystemChatMessage.class), types(steve.sentToClient()));
    }

    @Test
    @DisplayName("MessageRaw: sends a JSON component, and ignores invalid JSON")
    void messageRaw() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      alex.sentToClient();

      request(utf("MessageRaw", "Alex", "{\"text\":\"Hello\",\"color\":\"green\"}"));
      request(utf("MessageRaw", "Alex", "{not json"));

      assertEquals(List.of(SystemChatMessage.class), types(alex.sentToClient()));
      assertTrue(alex.client.channel().isActive());
    }

    @Test
    @DisplayName("KickPlayer: disconnects a player with a legacy text reason")
    void kickPlayer() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      alex.sentToClient();

      request(utf("KickPlayer", "Alex", "§cBanned"));
      alex.runPendingTasks();

      assertTrue(types(alex.sentToClient()).contains(PlayDisconnect.class));
      assertFalse(alex.client.channel().isActive());
      assertTrue(steve.client.channel().isActive());
    }

    @Test
    @DisplayName("KickPlayerRaw: disconnects a player with a JSON reason")
    void kickPlayerRaw() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      alex.sentToClient();

      request(utf("KickPlayerRaw", "Alex", "{\"text\":\"Banned\"}"));
      alex.runPendingTasks();

      assertTrue(types(alex.sentToClient()).contains(PlayDisconnect.class));
      assertFalse(alex.client.channel().isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // Forward
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Forward")
  class Forwards {

    private final byte[] data = forwarded("Party", new byte[] {1, 2, 3});

    @Test
    @DisplayName("should deliver the sub-channel, length and data to a server through its player")
    void toServer() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", LEGACY, SURVIVAL);
      MinecraftConnection survival = alex.current();
      bungeeCordMessages(alex, survival);

      request(concat(utf("Forward", "survival"), data, new byte[] {9, 9}));

      PlayPluginMessage delivered = bungeeCordMessages(alex, survival).getFirst();
      assertEquals("BungeeCord", delivered.channel(), "the target connection's own name");
      assertArrayEquals(data, delivered.data(), "without what follows the data, as BungeeCord");
    }

    @Test
    @DisplayName("ALL: should deliver to every other server, never back to the sender")
    void all() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, LOBBY);
      SwitchHarness bob = join("Bob", VERSION, SURVIVAL);
      bungeeCordMessages(alex, alex.current());
      bungeeCordMessages(bob, bob.current());

      request(concat(utf("Forward", "ALL"), data));

      assertArrayEquals(data, bungeeCordMessages(bob, bob.current()).getFirst().data());
      assertTrue(bungeeCordMessages(alex, alex.current()).isEmpty());
      assertTrue(bungeeCordMessages(steve, lobby).isEmpty());
    }

    @Test
    @DisplayName("should hold a forward to an empty server until a player arrives there")
    void held() {
      start(BungeeCordSettings.DEFAULTS);

      request(concat(utf("Forward", "survival"), data));
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);

      List<PlayPluginMessage> delivered = bungeeCordMessages(alex, alex.current());
      assertEquals(1, delivered.size());
      assertArrayEquals(data, delivered.getFirst().data());
    }

    @Test
    @DisplayName("ONLINE: should skip empty servers rather than hold for them")
    void online() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness bob = join("Bob", VERSION, CREATIVE);
      bungeeCordMessages(bob, bob.current());

      request(concat(utf("Forward", "ONLINE"), data));
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);

      assertArrayEquals(data, bungeeCordMessages(bob, bob.current()).getFirst().data());
      assertTrue(bungeeCordMessages(alex, alex.current()).isEmpty());
    }

    @Test
    @DisplayName("should drop a forward to an empty server at once with forward-hold 0")
    void noHold() {
      start(new BungeeCordSettings(true, allowAll(), Duration.ZERO));

      request(concat(utf("Forward", "survival"), data));
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);

      assertTrue(bungeeCordMessages(alex, alex.current()).isEmpty());
    }

    @Test
    @DisplayName("should drop a forward a backend would refuse: over 32767 bytes")
    void tooLarge() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      bungeeCordMessages(alex, alex.current());

      request(concat(utf("Forward", "survival"), forwarded("Big", new byte[32767])));

      assertTrue(bungeeCordMessages(alex, alex.current()).isEmpty());
      assertTrue(alex.client.channel().isActive());
    }

    @Test
    @DisplayName("should drop a forward whose length announces more data than it carries")
    void truncated() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      bungeeCordMessages(alex, alex.current());

      request(concat(utf("Forward", "survival"), utf("Party", (short) 10), new byte[] {1}));

      assertTrue(bungeeCordMessages(alex, alex.current()).isEmpty());
    }

    @Test
    @DisplayName("ForwardToPlayer: should deliver to the server the player plays on")
    void toPlayer() {
      start(BungeeCordSettings.DEFAULTS);
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);
      bungeeCordMessages(alex, alex.current());

      request(concat(utf("ForwardToPlayer", "alex"), data));

      assertArrayEquals(data, bungeeCordMessages(alex, alex.current()).getFirst().data());
      assertTrue(bungeeCordMessages(steve, lobby).isEmpty(), "not back to the sender");
    }
  }

  // ---------------------------------------------------------------------------
  // Security
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("security")
  class Security {

    @Test
    @DisplayName("should drop a sub-channel the server's rule takes away, and answer the others")
    void denied() {
      Map<String, Set<BungeeCordSubchannel>> allow = new LinkedHashMap<>(allowAll());
      allow.put("lobby", BungeeCordSettings.parseRule(List.of("*", "!KickPlayer")));
      start(new BungeeCordSettings(true, allow, Duration.ofSeconds(30)));
      SwitchHarness alex = join("Alex", VERSION, SURVIVAL);

      request(utf("KickPlayer", "Alex", "bye"));
      alex.runPendingTasks();

      assertTrue(alex.client.channel().isActive(), "not kicked");
      assertArrayEquals(utf("GetServer", "lobby"), ask(utf("GetServer")));
    }

    @Test
    @DisplayName("should apply the longest matching prefix")
    void prefix() {
      start(
          new BungeeCordSettings(
              true,
              Map.of(
                  "*", EnumSet.allOf(BungeeCordSubchannel.class),
                  "lob*", EnumSet.of(BungeeCordSubchannel.GET_SERVER)),
              Duration.ZERO));

      assertArrayEquals(utf("GetServer", "lobby"), ask(utf("GetServer")));
      assertTrue(asked(utf("GetServers")).isEmpty());
    }

    @Test
    @DisplayName("should neither register the channel nor answer for a server allowed nothing")
    void nothingAllowed() {
      start(
          new BungeeCordSettings(
              true, Map.of("survival", EnumSet.allOf(BungeeCordSubchannel.class)), Duration.ZERO));

      assertTrue(lobbyMessages().isEmpty(), "no register");
      request(utf("GetServer"));
      assertTrue(bungeeCordMessages(steve, lobby).isEmpty());
    }

    @Test
    @DisplayName("should drop a client's message on the channel, never interpreting it")
    void fromClient() {
      start(BungeeCordSettings.DEFAULTS);
      List<ServerPreConnectEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(ServerPreConnectEvent.class, fired::add);
      lobbyMessages();

      clientHandler(steve)
          .handle(new PlayPluginMessage("bungeecord:main", utf("Connect", "survival")));
      clientHandler(steve).handle(new PlayPluginMessage("BungeeCord", utf("Connect", "survival")));

      assertTrue(fired.isEmpty());
      assertEquals(0, steve.sentToBackend(lobby), "not relayed to the server");
    }

    @Test
    @DisplayName("should still drop a client's message on the channel when disabled")
    void fromClientDisabled() {
      start(BungeeCordSettings.DISABLED);

      clientHandler(steve).handle(new PlayPluginMessage("bungeecord:main", utf("GetServer")));

      assertEquals(0, steve.sentToBackend(lobby));
    }

    @Test
    @DisplayName("disabled: should answer nothing and relay the backend's message to the client")
    void disabled() {
      start(BungeeCordSettings.DISABLED);
      steve.sentToClient();

      request(utf("GetServer"));

      assertTrue(bungeeCordMessages(steve, lobby).isEmpty());
      assertEquals(List.of(PlayPluginMessage.class), types(steve.sentToClient()));
    }
  }

  // ---------------------------------------------------------------------------
  // Registration with the backend
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("registration with the backend")
  class Registration {

    @Test
    @DisplayName("should register bungeecord:main for the client from 1.13")
    void modern() {
      start(BungeeCordSettings.DEFAULTS);

      PlayPluginMessage register = lobbyMessages().getFirst();

      assertEquals("minecraft:register", register.channel());
      assertArrayEquals("bungeecord:main".getBytes(StandardCharsets.UTF_8), register.data());
    }

    @Test
    @DisplayName("should register BungeeCord for the client before 1.13")
    void legacy() {
      steve = harness("Steve", LEGACY);
      start(BungeeCordSettings.DEFAULTS);

      PlayPluginMessage register = lobbyMessages().getFirst();

      assertEquals("REGISTER", register.channel());
      assertArrayEquals("BungeeCord".getBytes(StandardCharsets.UTF_8), register.data());
    }
  }

  // ---------------------------------------------------------------------------
  // Plugins
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("plugins")
  class Plugins {

    @Test
    @DisplayName("should let a plugin watch every request, then answer it")
    void observe() {
      start(BungeeCordSettings.DEFAULTS);
      List<PluginMessageEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PluginMessageEvent.class, fired::add);

      byte[] reply = ask(utf("GetServer"));

      PluginMessageEvent event = fired.getFirst();
      assertEquals(BungeeCordChannel.MODERN, event.channel());
      assertInstanceOf(PluginMessageEvent.Pass.class, event.result());
      assertInstanceOf(PluginMessageEvent.FromServer.class, event.source());
      assertArrayEquals(utf("GetServer", "lobby"), reply);
    }

    @Test
    @DisplayName("should let a plugin take over one sub-channel while the proxy answers the rest")
    void takeOverSubchannel() {
      start(BungeeCordSettings.DEFAULTS);
      events.subscribe(
          PluginMessageEvent.class,
          event -> {
            if (startsWith(event.data(), utf("PlayerCount"))) {
              event.setResult(new PluginMessageEvent.Handled());
            }
          });

      assertTrue(asked(utf("PlayerCount", "ALL")).isEmpty());
      assertArrayEquals(utf("GetServer", "lobby"), ask(utf("GetServer")));
    }

    @Test
    @DisplayName("should let a plugin that registers the channel take it whole")
    void register() {
      start(BungeeCordSettings.DEFAULTS);
      registrations.add(
          new PluginMessaging(channels, "test", s -> {})
              .register(BungeeCordChannel.MODERN, Set.of(Origin.SERVER)));
      List<PluginMessageEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PluginMessageEvent.class, fired::add);

      request(utf("GetServer"));

      assertInstanceOf(PluginMessageEvent.Handled.class, fired.getFirst().result());
      assertTrue(bungeeCordMessages(steve, lobby).isEmpty(), "the proxy stands aside");
    }

    @Test
    @DisplayName("should answer what the owner passes back")
    void registerAndPass() {
      start(BungeeCordSettings.DEFAULTS);
      registrations.add(
          new PluginMessaging(channels, "test", s -> {})
              .register(BungeeCordChannel.MODERN, Set.of(Origin.SERVER)));
      events.subscribe(
          PluginMessageEvent.class, event -> event.setResult(new PluginMessageEvent.Pass()));

      assertArrayEquals(utf("GetServer", "lobby"), ask(utf("GetServer")));
    }

    @Test
    @DisplayName("should answer the channel again once the owner releases it")
    void released() {
      start(BungeeCordSettings.DEFAULTS);
      Subscription registration =
          new PluginMessaging(channels, "test", s -> {})
              .register(BungeeCordChannel.MODERN, Set.of(Origin.SERVER));

      registration.close();

      assertTrue(PlayPluginMessage.WATCHED_CHANNELS.contains("bungeecord:main"), "still read");
      assertArrayEquals(utf("GetServer", "lobby"), ask(utf("GetServer")));
    }

    @Test
    @DisplayName("should relay a request to the client when a plugin forwards it")
    void forwardToClient() {
      start(BungeeCordSettings.DEFAULTS);
      events.subscribe(
          PluginMessageEvent.class, event -> event.setResult(new PluginMessageEvent.Forward()));
      steve.sentToClient();

      request(utf("GetServer"));

      assertEquals(List.of(PlayPluginMessage.class), types(steve.sentToClient()));
      assertTrue(bungeeCordMessages(steve, lobby).isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // Setup
  // ---------------------------------------------------------------------------

  /** Sets up the proxy's channels with {@code settings}, and Steve playing on the lobby. */
  private void start(BungeeCordSettings settings) {
    ServerLoginContext context = steve.player.loginContext();
    when(context.events()).thenReturn(bus);
    players = context.playerRegistry();
    servers = context.serverRegistry();
    channels = new ChannelRegistry(new BungeeCordChannel(settings, players, servers));
    when(context.channels()).thenReturn(channels);
    var _ = players.register(steve.player, false);
    lobby = playOn(steve, LOBBY);
  }

  /** Another player, sharing Steve's proxy, playing on {@code server}. */
  private SwitchHarness join(String name, ProtocolVersion version, ServerInfo server) {
    SwitchHarness other = harness(name, version);
    ServerLoginContext context = other.player.loginContext();
    when(context.events()).thenReturn(bus);
    when(context.playerRegistry()).thenReturn(players);
    when(context.serverRegistry()).thenReturn(servers);
    when(context.channels()).thenReturn(channels);
    var _ = players.register(other.player, false);
    playOn(other, server);
    return other;
  }

  private SwitchHarness harness(String name, ProtocolVersion version) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY, name);
    harnesses.add(setup);
    return setup;
  }

  /** Puts a player in a game on a server, its server counting it. */
  private static MinecraftConnection playOn(SwitchHarness setup, ServerInfo server) {
    MinecraftConnection backend = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(backend, server));
    setup.player.setCurrentServerName(server.name());
    backend.setSessionHandler(new BackendPlaySessionHandler(setup.player, backend));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    setup.player.joinedGame();
    return backend;
  }

  private static ClientPlaySessionHandler clientHandler(SwitchHarness setup) {
    return (ClientPlaySessionHandler) Objects.requireNonNull(setup.client.sessionHandler());
  }

  private static Map<String, Set<BungeeCordSubchannel>> allowAll() {
    return Map.of("*", EnumSet.allOf(BungeeCordSubchannel.class));
  }

  // ---------------------------------------------------------------------------
  // Requests and replies
  // ---------------------------------------------------------------------------

  /** The lobby sends a request through Steve. */
  private void request(byte[] payload) {
    steve.receive(
        lobby,
        frame(
            steve.version,
            new PlayPluginMessage(BungeeCordChannel.nameFor(steve.version), payload)));
  }

  /** Sends a request and returns the one reply. */
  private byte[] ask(byte[] payload) {
    List<byte[]> replies = asked(payload);
    assertEquals(1, replies.size(), "one reply");
    return replies.getFirst();
  }

  /** Sends a request and returns its replies. */
  private List<byte[]> asked(byte[] payload) {
    bungeeCordMessages(steve, lobby);
    request(payload);
    return bungeeCordMessages(steve, lobby).stream().map(PlayPluginMessage::data).toList();
  }

  /** Drains what the lobby received from Steve's connection, register included. */
  private List<PlayPluginMessage> lobbyMessages() {
    return pluginMessages(steve, lobby);
  }

  /** Drains the BungeeCord channel messages a backend received. */
  private static List<PlayPluginMessage> bungeeCordMessages(
      SwitchHarness setup, MinecraftConnection backend) {
    return pluginMessages(setup, backend).stream()
        .filter(message -> BungeeCordChannel.isChannel(message.channel()))
        .toList();
  }

  /** Drains the plugin messages a backend received. */
  private static List<PlayPluginMessage> pluginMessages(
      SwitchHarness setup, MinecraftConnection backend) {
    int id =
        StateRegistry.get(ProtocolState.PLAY, PacketDirection.SERVERBOUND)
            .packetId(setup.version, PlayPluginMessage.class);
    List<PlayPluginMessage> messages = new ArrayList<>();
    for (byte[] frame : setup.framesSentOn(backend)) {
      ByteBuf buf = Unpooled.wrappedBuffer(frame);
      VarInt.read(buf);
      if (VarInt.read(buf) == id) {
        messages.add(PlayPluginMessage.CODEC.decode(buf, setup.version));
      }
    }
    return messages;
  }

  /**
   * Writes values as Guava's {@code ByteArrayDataOutput} writes them for BungeeCord: strings with
   * {@code writeUTF}, integers with {@code writeInt}, shorts with {@code writeShort}.
   */
  private static byte[] utf(Object... values) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      for (Object value : values) {
        switch (value) {
          case String text -> out.writeUTF(text);
          case Integer number -> out.writeInt(number);
          case Short number -> out.writeShort(number);
          default -> throw new IllegalArgumentException(String.valueOf(value));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** A forward's body: the sub-channel, the data's length and the data. */
  private static byte[] forwarded(String subchannel, byte[] data) {
    return concat(utf(subchannel, (short) data.length), data);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      bytes.writeBytes(part);
    }
    return bytes.toByteArray();
  }

  private static boolean startsWith(byte[] data, byte[] prefix) {
    return data.length >= prefix.length
        && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
  }

  private static String undashed(SwitchHarness setup) {
    return setup.player.uuid().toString().replace("-", "");
  }
}
