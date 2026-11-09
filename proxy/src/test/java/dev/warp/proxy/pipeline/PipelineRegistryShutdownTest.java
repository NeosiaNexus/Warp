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
package dev.warp.proxy.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.network.PipelinePosition;
import dev.warp.api.player.InboundConnection;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PipelineRegistry at shutdown")
class PipelineRegistryShutdownTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final PipelineRegistry registry = new PipelineRegistry();
  private final List<EmbeddedChannel> channels = new ArrayList<>();
  private final InboundConnection connection = mock(InboundConnection.class);

  PipelineRegistryShutdownTest() {
    when(connection.remoteAddress())
        .thenReturn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 40_000));
  }

  @AfterEach
  void tearDown() {
    for (EmbeddedChannel channel : channels) {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("should close the connections with plugin handlers before the plugin is disabled")
  void closesBeforeDisable() {
    // given: a plugin whose handler records a call that comes after the plugin was disabled
    FakePlugin plugin = new FakePlugin();
    var _ = registry.addClientInitializer("fake", plugin::initialize);
    Host client = host();
    Host other = host();
    assertTrue(registry.initializeClient(client, 769, connection));
    assertTrue(registry.initializeClient(other, 769, connection));
    assertEquals(2, registry.connectionCount());

    // when: the proxy stops, in order: connections closed and awaited, then the plugin disabled
    assertTrue(closeConnections());
    plugin.disable();
    client.channel().pipeline().fireChannelRead(Unpooled.wrappedBuffer(new byte[] {1}));

    // then
    assertFalse(client.channel().isOpen(), "the client connection is closed");
    assertFalse(other.channel().isOpen(), "every connection with plugin handlers is closed");
    assertEquals(0, registry.connectionCount());
    assertTrue(plugin.calledBeforeDisable.get(), "the handler ran while the plugin was enabled");
    assertFalse(plugin.calledAfterDisable.get(), "no handler runs once its plugin is disabled");
  }

  @Test
  @DisplayName("should close a connection that asks for plugin handlers once stopping")
  void refusesLateConnections() {
    var _ = registry.addClientInitializer("fake", new FakePlugin()::initialize);
    assertTrue(closeConnections());

    Host late = host();
    assertFalse(registry.initializeClient(late, 769, connection));
    assertFalse(late.channel().isOpen());
    assertEquals(0, registry.connectionCount());
  }

  @Test
  @DisplayName("should let a connection go by itself and close nothing when none is open")
  void tracksClosedConnections() {
    var _ = registry.addClientInitializer("fake", new FakePlugin()::initialize);
    Host client = host();
    assertTrue(registry.initializeClient(client, 769, connection));
    client.channel().close().syncUninterruptibly();

    assertEquals(0, registry.connectionCount());
    assertTrue(closeConnections());
  }

  /**
   * Closes the connections as the proxy does when it stops. An embedded channel completes a close
   * on its own event loop, which runs only when pumped: the wait goes on another thread.
   */
  private boolean closeConnections() {
    CompletableFuture<Boolean> closed =
        CompletableFuture.supplyAsync(() -> registry.closeConnections(TIMEOUT));
    while (!closed.isDone()) {
      channels.forEach(EmbeddedChannel::runPendingTasks);
      Thread.onSpinWait();
    }
    return closed.join();
  }

  private Host host() {
    // Embedded channels share one id unless given their own: the group tells them apart by id.
    EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
    channels.add(channel);
    return new Host(channel);
  }

  /** A plugin with one packet handler, like a protocol translator. */
  private static final class FakePlugin {
    final AtomicBoolean disabled = new AtomicBoolean();
    final AtomicBoolean calledBeforeDisable = new AtomicBoolean();
    final AtomicBoolean calledAfterDisable = new AtomicBoolean();

    void initialize(dev.warp.api.network.ClientPipeline pipeline) {
      var _ =
          pipeline.addHandler(
              PipelinePosition.FRAME,
              "translator",
              new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                  (disabled.get() ? calledAfterDisable : calledBeforeDisable).set(true);
                  ctx.fireChannelRead(msg);
                }
              });
      // The handler sees one frame while the plugin is enabled.
      pipeline.channel().pipeline().fireChannelRead(Unpooled.wrappedBuffer(new byte[] {0}));
    }

    void disable() {
      disabled.set(true);
    }
  }

  /** A connection with no handler of its own: plugin handlers go at the tail. */
  private record Host(Channel channel) implements PipelineHost {
    @Override
    public void addPluginHandler(PipelinePosition position, String name, ChannelHandler handler) {
      channel.pipeline().addLast(name, handler);
    }

    @Override
    public List<String> pluginHandlers(PipelinePosition position) {
      return List.copyOf(channel.pipeline().names());
    }

    @Override
    public boolean isPacketLevel() {
      return false;
    }
  }
}
