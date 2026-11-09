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
package dev.warp.proxy.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.event.proxy.ProxyPingEvent;
import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.SampleVisitor;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.server.ServerPing;
import dev.warp.proxy.config.QueryConfig;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.metrics.MetricRegistry;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.status.ServerList;
import dev.warp.proxy.status.StatusSettings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("QueryHandler")
class QueryHandlerTest {

  private static final InetSocketAddress LISTENER = new InetSocketAddress("0.0.0.0", 25577);
  private static final InetSocketAddress PLAYERS = new InetSocketAddress("127.0.0.1", 25565);
  private static final InetSocketAddress CLIENT = address("203.0.113.7", 40000);
  private static final byte[] SESSION = {0, 0, 0, 1};

  private long now;
  private MetricRegistry metrics;
  private WarpEventBus events;
  private PlayerRegistry players;
  private QueryStatus status;
  private QueryHandler handler;
  private EmbeddedChannel channel;
  private QueryConfig config;

  private static InetSocketAddress address(String host, int port) {
    try {
      return new InetSocketAddress(InetAddress.getByName(host), port);
    } catch (java.net.UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @BeforeEach
  void setUp() {
    now = TimeUnit.HOURS.toNanos(1);
    events = new WarpEventBus(Runnable::run);
    players = new PlayerRegistry();
    config = config(2, false);
    start(config, 5, 10, 100);
  }

  private static QueryConfig config(int names, boolean plugins) {
    return new QueryConfig(
        true, LISTENER, "world", plugins, names, "", new QueryConfig.RateLimit(5, 10, 100));
  }

  private void start(QueryConfig config, int perAddress, int burst, int global) {
    metrics = new MetricRegistry(1);
    StatusSettings settings =
        new StatusSettings(
            Component.text("A Minecraft Server"),
            null,
            20,
            StatusSettings.NO_LIMIT,
            true,
            12,
            "Beta 1.9 Prerelease 4");
    status =
        new QueryStatus(
            config,
            new ServerList(settings, players),
            events,
            players,
            List::of,
            LISTENER,
            PLAYERS,
            () -> now);
    handler =
        new QueryHandler(
            status,
            new ChallengeTable(new SplittableRandom(7)),
            new QueryRateLimiter(16, perAddress, burst),
            new QueryRateLimiter(1, global, global),
            metrics,
            () -> now);
    channel = new EmbeddedChannel(handler);
  }

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Sends bytes given as ints, for literals such as 0xFE. */
  private byte @Nullable [] send(InetSocketAddress from, int... datagram) {
    byte[] bytes = new byte[datagram.length];
    for (int i = 0; i < datagram.length; i++) {
      bytes[i] = (byte) datagram[i];
    }
    return send(from, bytes);
  }

  private byte @Nullable [] send(InetSocketAddress from, byte[] datagram) {
    channel.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(datagram), LISTENER, from));
    DatagramPacket reply = channel.readOutbound();
    if (reply == null) {
      return null;
    }
    try {
      assertEquals(from, reply.recipient());
      ByteBuf content = reply.content();
      byte[] bytes = new byte[content.readableBytes()];
      content.readBytes(bytes);
      return bytes;
    } finally {
      reply.release();
    }
  }

  private static byte[] handshake() {
    return new byte[] {(byte) 0xFE, (byte) 0xFD, 9, 0, 0, 0, 1};
  }

  private static byte[] stat(int token, boolean full) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeShort(0xFEFD).writeByte(0).writeBytes(SESSION).writeInt(token);
    if (full) {
      buf.writeInt(0);
    }
    byte[] bytes = new byte[buf.readableBytes()];
    buf.readBytes(bytes);
    buf.release();
    return bytes;
  }

  /** Shakes hands and returns the token, parsed from the decimal reply as a client does. */
  private int token(InetSocketAddress from) {
    byte[] reply = send(from, handshake());
    assertNotNull(reply);
    assertEquals(9, reply[0]);
    assertArrayEquals(SESSION, java.util.Arrays.copyOfRange(reply, 1, 5));
    assertEquals(0, reply[reply.length - 1]);
    String decimal = new String(reply, 5, reply.length - 6, StandardCharsets.US_ASCII);
    return Integer.parseInt(decimal);
  }

  private long count(String type, String outcome) {
    double[] total = {0};
    metrics.collect(
        new SampleVisitor() {
          @Override
          public void family(String name, MetricType metricType, String help) {}

          @Override
          public void sample(
              String name, List<String> labelNames, List<String> labelValues, double value) {
            if (name.equals("warp_query_requests_total")
                && labelValues.equals(List.of(type, outcome))) {
              total[0] += value;
            }
          }
        });
    return (long) total[0];
  }

  // ---------------------------------------------------------------------------
  // Handshake and stats
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("handshake")
  class Handshake {

    @Test
    @DisplayName(
        "should answer with a positive decimal token, null-terminated, echoing the session id")
    void answers() {
      int token = token(CLIENT);

      assertTrue(token > 0);
      assertEquals(1, count("handshake", "answered"));
    }

    @Test
    @DisplayName("should echo the session id as received, without masking it")
    void echoesSession() {
      byte[] reply = send(CLIENT, 0xFE, 0xFD, 9, 0xAB, 0xCD, 0xEF, 0x12);

      assertNotNull(reply);
      assertArrayEquals(
          new byte[] {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x12},
          java.util.Arrays.copyOfRange(reply, 1, 5));
    }

    @Test
    @DisplayName("should drop a handshake with trailing bytes")
    void trailingBytes() {
      assertNull(send(CLIENT, 0xFE, 0xFD, 9, 0, 0, 0, 1, 0));
      assertEquals(1, count("handshake", "malformed"));
    }
  }

  @Nested
  @DisplayName("stat")
  class Stat {

    @Test
    @DisplayName("should answer a basic stat with the wiki's bytes after a valid handshake")
    void basic() {
      register("barneygale");
      register("Vivalahelvig");
      now += TimeUnit.SECONDS.toNanos(3);
      int token = token(CLIENT);

      byte[] reply = send(CLIENT, stat(token, false));

      assertArrayEquals(QuerySnapshotTest.hex(QuerySnapshotTest.WIKI_BASIC), reply);
      assertEquals(1, count("basic", "answered"));
    }

    @Test
    @DisplayName("should answer a full stat with the wiki's bytes, the players listed")
    void full() {
      register("barneygale");
      register("Vivalahelvig");
      // Built before the players joined: let the snapshot refresh.
      now += TimeUnit.SECONDS.toNanos(3);
      int token = token(CLIENT);

      // The refresh runs on this thread (direct executor) before the stat is answered.
      byte[] reply = send(CLIENT, stat(token, true));

      assertNotNull(reply);
      // Warp's own plugins field in place of the wiki's empty one: compare around it. Latin-1,
      // so that the 0x80 of the padding survives the decoding.
      String text = new String(reply, StandardCharsets.ISO_8859_1);
      assertTrue(
          text.startsWith(
              "\0\0\0\0\1splitnum\0\u0080\0hostname\0A Minecraft Server\0gametype\0SMP\0game_id\0MINECRAFT\0version\0Beta 1.9 Prerelease 4\0plugins\0Warp "));
      assertTrue(
          text.endsWith(
              "\0map\0world\0numplayers\u00002\0maxplayers\u000020\0hostport\u000025565\0hostip\u0000127.0.0.1\0\0\1player_\0\0barneygale\0Vivalahelvig\0\0"));
      assertEquals(1, count("full", "answered"));
    }

    @Test
    @DisplayName("should answer the same token twice within 30 seconds")
    void tokenReusable() {
      int token = token(CLIENT);

      assertNotNull(send(CLIENT, stat(token, false)));
      now += TimeUnit.SECONDS.toNanos(29);
      assertNotNull(send(CLIENT, stat(token, true)));
    }

    @Test
    @DisplayName("should drop a stat without a handshake, silently")
    void noHandshake() {
      assertNull(send(CLIENT, stat(12345, false)));
      assertEquals(1, count("basic", "no_token"));
    }

    @Test
    @DisplayName("should drop a stat with a wrong token")
    void wrongToken() {
      int token = token(CLIENT);

      assertNull(send(CLIENT, stat(token ^ 1, true)));
      assertEquals(1, count("full", "no_token"));
    }

    @Test
    @DisplayName("should drop a stat once the token expired")
    void expired() {
      int token = token(CLIENT);
      now += TimeUnit.SECONDS.toNanos(30);

      assertNull(send(CLIENT, stat(token, false)));
      assertEquals(1, count("basic", "no_token"));
    }

    @Test
    @DisplayName("should drop a stat from the same address on another port")
    void otherPort() {
      int token = token(CLIENT);

      assertNull(send(address("203.0.113.7", 40001), stat(token, false)));
      assertEquals(1, count("basic", "no_token"));
    }

    @Test
    @DisplayName("should drop a stat from another address with a token it did not receive")
    void otherAddress() {
      int token = token(CLIENT);

      assertNull(send(address("198.51.100.9", 40000), stat(token, false)));
    }

    @Test
    @DisplayName("should drop a stat of a length that is neither basic nor full")
    void oddLength() {
      int token = token(CLIENT);
      byte[] full = stat(token, true);

      assertNull(send(CLIENT, java.util.Arrays.copyOf(full, 13)));
      assertNull(send(CLIENT, java.util.Arrays.copyOf(full, 16)));
      assertEquals(2, count("unknown", "malformed"));
    }

    @Test
    @DisplayName("should drop stats while a plugin hides the proxy from pings")
    void ignored() {
      new PluginEventBus(events, "test", null, s -> {})
          .subscribe(ProxyPingEvent.class, event -> event.setResult(new ProxyPingEvent.Ignore()));
      now += TimeUnit.SECONDS.toNanos(3);
      int token = token(CLIENT);

      assertNull(send(CLIENT, stat(token, false)));
      assertEquals(1, count("basic", "ignored"));
      assertNull(status.peek());
    }

    @Test
    @DisplayName("should answer what a plugin set on the ping event")
    void pluginEntry() {
      new PluginEventBus(events, "test", null, s -> {})
          .subscribe(
              ProxyPingEvent.class,
              event ->
                  event.setPing(
                      new ServerPing(
                          new ServerPing.Version("Custom", 0),
                          new ServerPing.Players(7, 99, List.of()),
                          Component.text("Hidden MOTD"),
                          null)));
      now += TimeUnit.SECONDS.toNanos(3);
      int token = token(CLIENT);

      byte[] reply = send(CLIENT, stat(token, false));

      assertNotNull(reply);
      String text = new String(reply, StandardCharsets.UTF_8);
      assertTrue(text.contains("Hidden MOTD\0SMP\0world\u00007\u000099\0"));
    }
  }

  // ---------------------------------------------------------------------------
  // Malformed input
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("malformed datagrams")
  class Malformed {

    @Test
    @DisplayName("should drop an empty datagram")
    void empty() {
      assertNull(send(CLIENT, new byte[0]));
      assertEquals(1, count("unknown", "malformed"));
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {1, 2, 3, 6})
    @DisplayName("should drop a datagram shorter than a handshake (Velocity #1828)")
    void tooShort(int length) {
      byte[] datagram = java.util.Arrays.copyOf(handshake(), length);

      assertNull(send(CLIENT, datagram));
      assertTrue(channel.isOpen());
      assertEquals(1, count("unknown", "malformed"));
    }

    @Test
    @DisplayName("should drop a wrong magic")
    void wrongMagic() {
      assertNull(send(CLIENT, 0xFE, 0xFC, 9, 0, 0, 0, 1));
      assertNull(send(CLIENT, 0x00, 0x00, 9, 0, 0, 0, 1));
      assertEquals(2, count("unknown", "malformed"));
    }

    @Test
    @DisplayName("should drop an unknown type")
    void unknownType() {
      assertNull(send(CLIENT, 0xFE, 0xFD, 1, 0, 0, 0, 1));
      assertNull(send(CLIENT, 0xFE, 0xFD, 0xFF, 0, 0, 0, 1));
      assertEquals(2, count("unknown", "malformed"));
    }

    @Test
    @DisplayName("should drop random datagrams without exception or reply, from fixed seeds")
    void randomBytes() {
      start(config, 1_000_000, 1_000_000, 1_000_000);
      for (long seed = 1; seed <= 500; seed++) {
        SplittableRandom random = new SplittableRandom(seed);
        byte[] datagram = new byte[random.nextInt(0, 64)];
        for (int i = 0; i < datagram.length; i++) {
          datagram[i] = (byte) random.nextInt(256);
        }
        if (random.nextBoolean() && datagram.length >= 2) {
          // Half of them start with the magic, to reach the type and length checks.
          datagram[0] = (byte) 0xFE;
          datagram[1] = (byte) 0xFD;
        }
        if (datagram.length >= 3 && random.nextInt(4) == 0) {
          datagram[2] = (byte) (random.nextBoolean() ? 0 : 9);
        }
        byte[] reply = send(CLIENT, datagram);
        // Only an exact handshake earns a reply; no stat does, without a token.
        if (reply != null) {
          assertEquals(9, reply[0], "seed " + seed);
          assertEquals(7, datagram.length, "seed " + seed);
        }
        assertTrue(channel.isOpen(), "seed " + seed);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Rate limits
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("rate limits")
  class RateLimits {

    @Test
    @DisplayName("should drop a source's datagrams past its burst, before reading them")
    void perAddress() {
      for (int i = 0; i < 10; i++) {
        assertNotNull(send(CLIENT, handshake()));
      }

      assertNull(send(CLIENT, handshake()));
      // Malformed and over the limit: counted as rate limited, never parsed.
      assertNull(send(CLIENT, 1));
      assertEquals(2, count("unknown", "rate_limited"));
      assertEquals(0, count("unknown", "malformed"));
    }

    @Test
    @DisplayName("should keep answering another source while one is limited")
    void otherSourceUnaffected() {
      for (int i = 0; i < 11; i++) {
        send(CLIENT, handshake());
      }

      assertNotNull(send(address("198.51.100.9", 40000), handshake()));
    }

    @Test
    @DisplayName("should let a limited source back in at its rate")
    void recovers() {
      for (int i = 0; i < 11; i++) {
        send(CLIENT, handshake());
      }
      now += TimeUnit.MILLISECONDS.toNanos(200);

      assertNotNull(send(CLIENT, handshake()));
    }

    @Test
    @DisplayName("should drop every source past the global limit")
    void global() {
      start(config, 1_000_000, 1_000_000, 20);
      for (int i = 0; i < 20; i++) {
        assertNotNull(send(address("203.0.113." + (i + 1), 40000), handshake()));
      }

      assertNull(send(address("203.0.113.99", 40000), handshake()));
      assertEquals(1, count("unknown", "rate_limited"));
    }

    @Test
    @DisplayName("should not let one source past its own limit spend the global budget of the rest")
    void singleSourceCannotStarveOthers() {
      start(config, 5, 10, 20);
      // One host floods well past the global rate: its own bucket drops all but its burst, and
      // only those count against the global bucket.
      for (int i = 0; i < 60; i++) {
        send(CLIENT, handshake());
      }

      assertNotNull(send(address("198.51.100.9", 40000), handshake()));
      assertNotNull(send(address("198.51.100.10", 40000), handshake()));
      assertEquals(10, count("handshake", "answered") - 2);
      assertEquals(50, count("unknown", "rate_limited"));
    }

    @Test
    @DisplayName("should count the whole IPv6 /64 as one source")
    void ipv6Network() {
      for (int i = 0; i < 10; i++) {
        assertNotNull(
            send(address("2001:db8:1:2::" + Integer.toHexString(i + 1), 40000), handshake()));
      }

      assertNull(send(address("2001:db8:1:2::ffff", 40000), handshake()));
      assertNotNull(send(address("2001:db8:1:3::1", 40000), handshake()));
    }
  }

  // ---------------------------------------------------------------------------
  // Asynchronous refresh
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("with plugins answering on another thread")
  class Asynchronous {

    /** What the event bus was asked to run, held until the test lets the plugins speak. */
    private final Deque<Runnable> deliveries = new ArrayDeque<>();

    @BeforeEach
    void asynchronousBus() {
      events = new WarpEventBus(deliveries::add);
      start(config, 5, 10, 100);
    }

    /** Runs the plugins, then what they completed on the channel's event loop. */
    private void deliver() {
      while (!deliveries.isEmpty()) {
        deliveries.poll().run();
      }
      channel.runPendingTasks();
    }

    private byte @Nullable [] reply() {
      DatagramPacket reply = channel.readOutbound();
      if (reply == null) {
        return null;
      }
      try {
        byte[] bytes = new byte[reply.content().readableBytes()];
        reply.content().readBytes(bytes);
        return bytes;
      } finally {
        reply.release();
      }
    }

    @Test
    @DisplayName("should hold the first stat after boot until the plugins answered, then hide")
    void hidesFromTheFirstStat() {
      new PluginEventBus(events, "test", null, s -> {})
          .subscribe(ProxyPingEvent.class, event -> event.setResult(new ProxyPingEvent.Ignore()));
      int token = token(CLIENT);

      assertNull(send(CLIENT, stat(token, false)));
      assertEquals(0, count("basic", "answered"));
      assertEquals(0, count("basic", "ignored"));
      deliver();

      assertNull(reply());
      assertEquals(1, count("basic", "ignored"));
      assertNull(status.peek());
    }

    @Test
    @DisplayName("should answer the first stat with what the plugin set, never the proposal")
    void answersFromTheRefresh() {
      new PluginEventBus(events, "test", null, s -> {})
          .subscribe(
              ProxyPingEvent.class,
              event ->
                  event.setPing(
                      new ServerPing(
                          new ServerPing.Version("Custom", 0),
                          new ServerPing.Players(7, 99, List.of()),
                          Component.text("Hidden MOTD"),
                          null)));
      int token = token(CLIENT);

      assertNull(send(CLIENT, stat(token, false)));
      deliver();

      byte[] reply = reply();
      assertNotNull(reply);
      String text = new String(reply, StandardCharsets.UTF_8);
      assertTrue(text.contains("Hidden MOTD\0SMP\0world\u00007\u000099\0"));
      assertTrue(!text.contains("A Minecraft Server"));
      assertEquals(1, count("basic", "answered"));
    }

    @Test
    @DisplayName("should answer every stat that arrived during the refresh once it completes")
    void joinsTheRefresh() {
      new PluginEventBus(events, "test", null, s -> {})
          .subscribe(ProxyPingEvent.class, event -> {});
      int token = token(CLIENT);
      InetSocketAddress other = address("198.51.100.9", 40000);
      int otherToken = token(other);

      assertNull(send(CLIENT, stat(token, false)));
      assertNull(send(other, stat(otherToken, true)));
      assertEquals(1, deliveries.size());
      deliver();

      DatagramPacket first = channel.readOutbound();
      DatagramPacket second = channel.readOutbound();
      assertNotNull(first);
      assertNotNull(second);
      // Both answered, in whichever order the future ran its dependents.
      assertEquals(
          java.util.Set.of(CLIENT, other), java.util.Set.of(first.recipient(), second.recipient()));
      first.release();
      second.release();
      assertEquals(1, count("basic", "answered"));
      assertEquals(1, count("full", "answered"));
    }

    @Test
    @DisplayName("should answer a fresh snapshot at once and refresh again after 2 seconds")
    void freshAnsweredInline() {
      new PluginEventBus(events, "test", null, s -> {})
          .subscribe(ProxyPingEvent.class, event -> {});
      int token = token(CLIENT);
      assertNull(send(CLIENT, stat(token, false)));
      deliver();
      assertNotNull(reply());

      now += TimeUnit.SECONDS.toNanos(1);
      assertNotNull(send(CLIENT, stat(token, false)));
      assertTrue(deliveries.isEmpty());

      now += TimeUnit.SECONDS.toNanos(1);
      assertNull(send(CLIENT, stat(token, false)));
      assertEquals(1, deliveries.size());
    }
  }

  // ---------------------------------------------------------------------------
  // Names
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("player names")
  class Names {

    @Test
    @DisplayName("should list no names by default")
    void noneByDefault() {
      start(config(0, false), 5, 10, 100);
      register("barneygale");
      now += TimeUnit.SECONDS.toNanos(3);
      int token = token(CLIENT);

      byte[] reply = send(CLIENT, stat(token, true));

      assertNotNull(reply);
      String text = new String(reply, StandardCharsets.UTF_8);
      assertTrue(text.endsWith("\1player_\0\0\0"));
      assertTrue(text.contains("numplayers\u00001\0"));
    }

    @Test
    @DisplayName("should cap the names at query.player-names and skip players who refuse listings")
    void capped() {
      start(config(2, false), 5, 10, 100);
      register("one");
      register("two");
      register("three");
      ConnectedPlayer hidden = register("hidden");
      when(hidden.allowsServerListing()).thenReturn(false);
      now += TimeUnit.SECONDS.toNanos(3);
      int token = token(CLIENT);

      byte[] reply = send(CLIENT, stat(token, true));

      assertNotNull(reply);
      QuerySnapshot snapshot = status.peek();
      assertNotNull(snapshot);
      assertEquals(2, snapshot.listed());
      assertTrue(!snapshot.players().contains("hidden"));
    }

    @Test
    @DisplayName("should list the plugins after Warp's version only when asked")
    void plugins() {
      PluginContainer plugin = mock(PluginContainer.class);
      when(plugin.state()).thenReturn(dev.warp.api.plugin.PluginState.ENABLED);
      when(plugin.description())
          .thenReturn(
              new dev.warp.api.plugin.PluginDescription(
                  "foo",
                  "Foo",
                  "1.2",
                  "",
                  "",
                  List.of(),
                  List.of(),
                  "foo.Main",
                  java.util.Set.of()));
      List<PluginContainer> plugins = new ArrayList<>(List.of(plugin));
      QueryStatus shown =
          new QueryStatus(
              config(0, true),
              new ServerList(StatusSettings.defaults(), players),
              events,
              players,
              () -> plugins,
              LISTENER,
              PLAYERS,
              () -> now);
      QueryStatus hidden =
          new QueryStatus(
              config(0, false),
              new ServerList(StatusSettings.defaults(), players),
              events,
              players,
              () -> plugins,
              LISTENER,
              PLAYERS,
              () -> now);

      assertNotNull(shown.peek());
      assertNotNull(hidden.peek());
      assertTrue(shown.peek().plugins().matches("Warp \\S+: Foo 1\\.2"));
      assertTrue(hidden.peek().plugins().matches("Warp \\S+"));
    }
  }

  private ConnectedPlayer register(String name) {
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.uuid()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    when(player.username()).thenReturn(name);
    when(player.allowsServerListing()).thenReturn(true);
    players.register(player, false);
    return player;
  }
}
