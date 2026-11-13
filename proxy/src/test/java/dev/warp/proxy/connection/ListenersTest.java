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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Listeners")
class ListenersTest {

  private static final int LOOPS = 3;
  private static final int CLIENTS = 48;
  private static final long WAIT_MILLIS = 5_000;

  private final List<EventLoopGroup> groups = new ArrayList<>();
  private final List<Listeners> bound = new ArrayList<>();
  private final List<Socket> clients = new ArrayList<>();

  /** Every accepted connection, in the order the loops saw them. */
  private final List<Channel> accepted = new CopyOnWriteArrayList<>();

  private final AtomicInteger acceptorsAsked = new AtomicInteger();

  @AfterEach
  void release() throws IOException {
    for (Socket client : clients) {
      client.close();
    }
    for (Listeners listeners : bound) {
      listeners.close(WAIT_MILLIS, TimeUnit.MILLISECONDS);
    }
    for (EventLoopGroup group : groups) {
      group.shutdownGracefully(0, WAIT_MILLIS, TimeUnit.MILLISECONDS).awaitUninterruptibly();
    }
  }

  // ---------------------------------------------------------------------------
  // One socket per loop
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("where sockets can share the port (epoll)")
  class PerLoop {

    @Test
    @DisplayName("should name the option the epoll transport knows as SO_REUSEPORT")
    void namesTheOption() {
      assertSame(EpollChannelOption.SO_REUSEPORT, Listeners.sharedPortOption());
    }

    @Test
    @DisplayName("should listen with one socket per loop, all on the port the system chose")
    void bindsOneSocketPerLoop() throws Exception {
      assumeTrue(Epoll.isAvailable());
      EventLoopGroup workers = epoll(LOOPS);

      Listeners listeners = bindEpoll(anyPort(), workers);

      assertEquals(LOOPS, listeners.channels().size());
      int port = port(listeners);
      assertNotEquals(0, port);
      Set<EventLoop> loops = new HashSet<>();
      for (Channel channel : listeners.channels()) {
        assertEquals(port, ((InetSocketAddress) channel.localAddress()).getPort());
        assertTrue(channel.isActive());
        loops.add(channel.eventLoop());
      }
      assertEquals(LOOPS, loops.size());
      assertEquals(0, acceptorsAsked.get());
    }

    @Test
    @DisplayName("should keep every connection on the loop that accepted it")
    void keepsConnectionsOnTheAcceptingLoop() throws Exception {
      assumeTrue(Epoll.isAvailable());
      Listeners listeners = bindEpoll(anyPort(), epoll(LOOPS));

      connect(port(listeners), CLIENTS);

      awaitAccepted(CLIENTS);
      Set<EventLoop> loops = new HashSet<>();
      for (Channel channel : accepted) {
        assertSame(channel.parent().eventLoop(), channel.eventLoop());
        loops.add(channel.eventLoop());
      }
      // The kernel spreads connections by a hash of their addresses: 48 on one loop of 3 is not
      // chance.
      assertTrue(loops.size() > 1, "connections were spread over " + loops.size() + " loop");
    }

    @Test
    @DisplayName("should still refuse a port that another listener holds")
    void refusesATakenPort() throws Exception {
      assumeTrue(Epoll.isAvailable());
      Listeners first = bindEpoll(anyPort(), epoll(LOOPS));
      InetSocketAddress taken =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), port(first));
      EventLoopGroup others = epoll(LOOPS);

      assertThrows(
          IOException.class,
          () ->
              Listeners.bind(
                  taken,
                  others,
                  acceptor(),
                  EpollChannelOption.SO_REUSEPORT,
                  bootstraps(EpollServerSocketChannel.class)));

      // The first proxy is untouched: it still gets every connection.
      connect(port(first), CLIENTS);
      awaitAccepted(CLIENTS);
      assertEquals(0, acceptorsAsked.get());
    }

    @Test
    @DisplayName("should refuse new connections once closed and leave the port free")
    void closesEverySocket() throws Exception {
      assumeTrue(Epoll.isAvailable());
      EventLoopGroup workers = epoll(LOOPS);
      Listeners listeners = bindEpoll(anyPort(), workers);
      int port = port(listeners);

      listeners.close(WAIT_MILLIS, TimeUnit.MILLISECONDS);

      for (Channel channel : listeners.channels()) {
        assertFalse(channel.isOpen());
      }
      assertThrows(IOException.class, () -> connect(port, 1));
      Listeners again =
          bindEpoll(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), workers);
      assertEquals(port, port(again));
    }
  }

  // ---------------------------------------------------------------------------
  // One shared socket
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("where they cannot")
  class Shared {

    @Test
    @DisplayName("should listen with one socket on the acceptor's loop without the option")
    void bindsOneSocket() throws Exception {
      EventLoopGroup workers = nio(LOOPS);

      Listeners listeners =
          track(
              Listeners.bind(
                  anyPort(), workers, acceptor(), null, bootstraps(NioServerSocketChannel.class)));

      assertEquals(1, listeners.channels().size());
      assertEquals(1, acceptorsAsked.get());
      assertEquals(listeners.channels().getFirst().localAddress(), listeners.localAddress());
      connect(port(listeners), CLIENTS);
      awaitAccepted(CLIENTS);
      for (Channel channel : accepted) {
        assertNotEquals(channel.parent().eventLoop(), channel.eventLoop());
      }
    }

    @Test
    @DisplayName("should fall back to one socket when the transport does not know the option")
    void fallsBackWithoutTheOption() throws Exception {
      Listeners listeners =
          track(
              Listeners.bind(
                  anyPort(),
                  nio(LOOPS),
                  acceptor(),
                  EpollChannelOption.SO_REUSEPORT,
                  bootstraps(NioServerSocketChannel.class)));

      assertEquals(1, listeners.channels().size());
      assertEquals(1, acceptorsAsked.get());
      connect(port(listeners), CLIENTS);
      awaitAccepted(CLIENTS);
    }

    @Test
    @DisplayName("should fall back to one socket when a second socket cannot join the first")
    void fallsBackWhenTheSystemRefuses() throws Exception {
      // An option every transport knows, and which does not let two listening sockets share a
      // port: the first socket binds, the second is refused by the system.
      int port = freePort();

      Listeners listeners =
          track(
              Listeners.bind(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                  nio(LOOPS),
                  acceptor(),
                  ChannelOption.AUTO_READ,
                  bootstraps(NioServerSocketChannel.class)));

      assertEquals(1, listeners.channels().size());
      assertEquals(1, acceptorsAsked.get());
      assertEquals(port, port(listeners));
      connect(port, CLIENTS);
      awaitAccepted(CLIENTS);
    }

    @Test
    @DisplayName(
        "should fail with the system's error on a taken port, without trying a shared socket")
    void failsOnATakenPort() throws Exception {
      Listeners first =
          track(
              Listeners.bind(
                  anyPort(), nio(1), acceptor(), null, bootstraps(NioServerSocketChannel.class)));
      InetSocketAddress taken =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), port(first));

      assertThrows(
          IOException.class,
          () ->
              Listeners.bind(
                  taken,
                  nio(LOOPS),
                  acceptor(),
                  ChannelOption.AUTO_READ,
                  bootstraps(NioServerSocketChannel.class)));

      // Only the first listener's acceptor: the refused bind never reached the shared socket.
      assertEquals(1, acceptorsAsked.get());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private EventLoopGroup epoll(int loops) {
    EventLoopGroup group = new MultiThreadIoEventLoopGroup(loops, EpollIoHandler.newFactory());
    groups.add(group);
    return group;
  }

  private EventLoopGroup nio(int loops) {
    EventLoopGroup group = new MultiThreadIoEventLoopGroup(loops, NioIoHandler.newFactory());
    groups.add(group);
    return group;
  }

  private Supplier<EventLoopGroup> acceptor() {
    return () -> {
      acceptorsAsked.incrementAndGet();
      return nio(1);
    };
  }

  private Listeners bindEpoll(InetSocketAddress address, EventLoopGroup workers)
      throws InterruptedException {
    return track(
        Listeners.bind(
            address,
            workers,
            acceptor(),
            EpollChannelOption.SO_REUSEPORT,
            bootstraps(EpollServerSocketChannel.class)));
  }

  private Listeners track(Listeners listeners) {
    bound.add(listeners);
    return listeners;
  }

  private BiFunction<EventLoopGroup, EventLoopGroup, ServerBootstrap> bootstraps(
      Class<? extends ServerChannel> channel) {
    return (acceptors, servers) ->
        new ServerBootstrap()
            .group(acceptors, servers)
            .channel(channel)
            .childHandler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    accepted.add(ch);
                  }
                });
  }

  private static InetSocketAddress anyPort() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
  }

  private static int port(Listeners listeners) {
    return ((InetSocketAddress) listeners.localAddress()).getPort();
  }

  private static int freePort() throws IOException {
    try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }

  private void connect(int port, int count) throws IOException {
    for (int i = 0; i < count; i++) {
      clients.add(new Socket(InetAddress.getLoopbackAddress(), port));
    }
  }

  private void awaitAccepted(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
    while (accepted.size() < count && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals(count, accepted.size());
  }
}
