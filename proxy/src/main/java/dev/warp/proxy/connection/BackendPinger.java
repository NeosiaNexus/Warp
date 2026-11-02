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

import dev.warp.api.server.Favicon;
import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPing;
import dev.warp.api.server.ServerPingException;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;
import dev.warp.proxy.server.ServerPinger;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * Asks backend servers for their server list entry, as a client refreshing its list would: a
 * handshake into the status state, a status request, the JSON answer, then the connection closes.
 *
 * <p>Each ping has one deadline, connection included; every failure completes the future with a
 * {@link ServerPingException} whose reason says what happened.
 */
public final class BackendPinger implements ServerPinger {

  /** The longest a ping waits for its connection, whatever its time-out: int milliseconds. */
  private static final long MAX_CONNECT_TIMEOUT_MILLIS = Integer.MAX_VALUE;

  private final EventLoopGroup group;
  private final Class<? extends Channel> channelClass;
  private final Executor callbacks;

  /**
   * Creates a pinger whose answers complete on virtual threads, never on an event loop: a plugin
   * that blocks in a callback of {@code ping()} cannot stall the players of that event loop.
   *
   * @param group the event loops that serve the ping connections
   * @param channelClass the socket channel class of the proxy's transport
   */
  public BackendPinger(EventLoopGroup group, Class<? extends Channel> channelClass) {
    this(
        group,
        channelClass,
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("warp-ping-", 0).factory()));
  }

  /**
   * Creates a pinger whose answers complete on {@code callbacks}.
   *
   * @param group the event loops that serve the ping connections
   * @param channelClass the socket channel class of the proxy's transport
   * @param callbacks runs the completion of each ping's future
   */
  BackendPinger(EventLoopGroup group, Class<? extends Channel> channelClass, Executor callbacks) {
    this.group = group;
    this.channelClass = channelClass;
    this.callbacks = callbacks;
  }

  @Override
  public CompletableFuture<ServerPing> ping(ServerInfo server, Duration timeout) {
    CompletableFuture<ServerPing> outcome = new CompletableFuture<>();
    ProtocolVersion version = ProtocolVersion.latest();
    long timeoutMillis = Math.max(1, Math.min(timeout.toMillis(), MAX_CONNECT_TIMEOUT_MILLIS));
    EventLoop loop = group.next();
    ChannelFuture connect =
        new Bootstrap()
            .group(loop)
            .channel(channelClass)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeoutMillis)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(
                new ChannelInitializer<>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    // No read time-out: the deadline below covers the whole ping.
                    ch.pipeline()
                        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
                        .addLast(
                            ServerChannelInitializer.MINECRAFT_DECODER,
                            new MinecraftDecoder(
                                PacketDirection.CLIENTBOUND, version, ProtocolState.STATUS))
                        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
                        .addLast(
                            ServerChannelInitializer.MINECRAFT_ENCODER,
                            new MinecraftEncoder(
                                PacketDirection.SERVERBOUND, version, ProtocolState.HANDSHAKE))
                        .addLast(
                            ServerChannelInitializer.CONNECTION_HANDLER,
                            new MinecraftConnection(ch));
                  }
                })
            .connect(server.address());
    // One deadline for the connection, the handshake and the answer: a server that accepts the
    // connection, then stays silent or trickles bytes, fails all the same.
    ScheduledFuture<?> deadline;
    try {
      deadline =
          loop.schedule(
              () ->
                  outcome.completeExceptionally(
                      new ServerPingException(
                          server,
                          ServerPingException.Reason.TIMED_OUT,
                          "no answer within " + timeoutMillis + " ms",
                          null)),
              timeoutMillis,
              TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // The event loops are shutting down: fail now rather than leave the caller waiting.
      var _ = connect.channel().close();
      return CompletableFuture.failedFuture(
          new ServerPingException(
              server, ServerPingException.Reason.UNREACHABLE, "the proxy is shutting down", e));
    }
    var _ =
        outcome.whenComplete(
            (_, _) -> {
              deadline.cancel(false);
              var _ = connect.channel().close();
            });
    connect.addListener(
        (ChannelFutureListener)
            future -> {
              if (!future.isSuccess()) {
                outcome.completeExceptionally(connectFailure(server, future.cause()));
                return;
              }
              Channel channel = future.channel();
              MinecraftConnection connection = channel.pipeline().get(MinecraftConnection.class);
              connection.setSessionHandler(
                  new PingSessionHandler(connection, server, version, outcome));
              var _ =
                  channel
                      .closeFuture()
                      .addListener(_ -> outcome.completeExceptionally(closed(server, null)));
            });
    // Callbacks of the caller never run on the event loop.
    CompletableFuture<ServerPing> result = new CompletableFuture<>();
    var _ =
        outcome.whenCompleteAsync(
            (ping, error) -> {
              if (error != null) {
                result.completeExceptionally(error);
              } else {
                result.complete(ping);
              }
            },
            callbacks);
    return result;
  }

  /** Describes a connection that could not be opened. */
  static ServerPingException connectFailure(ServerInfo server, Throwable cause) {
    if (cause instanceof ConnectTimeoutException) {
      return new ServerPingException(
          server, ServerPingException.Reason.TIMED_OUT, "the connection timed out", cause);
    }
    return new ServerPingException(
        server,
        ServerPingException.Reason.UNREACHABLE,
        "cannot connect: " + (cause.getMessage() == null ? cause : cause.getMessage()),
        cause);
  }

  private static ServerPingException closed(ServerInfo server, @Nullable Throwable cause) {
    return new ServerPingException(
        server,
        ServerPingException.Reason.CLOSED,
        "closed the connection without answering",
        cause);
  }

  /** Sends the status request and reads the answer. */
  private static final class PingSessionHandler implements SessionHandler {

    private final MinecraftConnection connection;
    private final ServerInfo server;
    private final ProtocolVersion version;
    private final CompletableFuture<ServerPing> result;

    PingSessionHandler(
        MinecraftConnection connection,
        ServerInfo server,
        ProtocolVersion version,
        CompletableFuture<ServerPing> result) {
      this.connection = connection;
      this.server = server;
      this.version = version;
      this.result = result;
    }

    @Override
    public void activated() {
      InetSocketAddress address = server.address();
      connection.writeAndFlush(
          new Handshake(version.protocol(), address.getHostString(), address.getPort(), 1));
      connection.setState(ProtocolState.STATUS);
      connection.writeAndFlush(new StatusRequest());
    }

    @Override
    public void handle(Packet packet) {
      if (packet instanceof StatusResponse response) {
        try {
          result.complete(parse(response.json()));
        } catch (RuntimeException e) {
          result.completeExceptionally(
              new ServerPingException(
                  server,
                  ServerPingException.Reason.INVALID_RESPONSE,
                  "its answer is not a server list entry: " + e.getMessage(),
                  e));
        }
        connection.close();
      }
    }

    @Override
    public void disconnected() {
      result.completeExceptionally(closed(server, null));
    }
  }

  /**
   * Reads a status answer.
   *
   * @param json the server's JSON answer
   * @return the server list entry
   * @throws RuntimeException if the answer is not a status object
   */
  static ServerPing parse(String json) {
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
    JsonObject versionObject = root.getAsJsonObject("version");
    ServerPing.Version version =
        versionObject == null
            ? new ServerPing.Version("", -1)
            : new ServerPing.Version(
                string(versionObject, "name"), versionObject.get("protocol").getAsInt());
    ServerPing.Players players = null;
    JsonObject playersObject = root.getAsJsonObject("players");
    if (playersObject != null) {
      List<ServerPing.SamplePlayer> sample = new ArrayList<>();
      JsonElement sampleElement = playersObject.get("sample");
      if (sampleElement != null && sampleElement.isJsonArray()) {
        for (JsonElement entry : sampleElement.getAsJsonArray()) {
          JsonObject player = entry.getAsJsonObject();
          try {
            sample.add(
                new ServerPing.SamplePlayer(
                    string(player, "name"), UUID.fromString(string(player, "id"))));
          } catch (IllegalArgumentException ignored) {
            // A sample entry with a malformed UUID: skip it, the rest of the answer stands.
          }
        }
      }
      players =
          new ServerPing.Players(
              playersObject.get("online").getAsInt(), playersObject.get("max").getAsInt(), sample);
    }
    JsonElement descriptionElement = root.get("description");
    Component description =
        descriptionElement == null
            ? Component.empty()
            : GsonComponentSerializer.gson().deserializeFromTree(descriptionElement);
    return new ServerPing(version, players, description, favicon(root));
  }

  /** Reads the icon, if any: one that is no PNG data URI is dropped, as clients drop it. */
  private static @Nullable Favicon favicon(JsonObject root) {
    String uri = root.has("favicon") ? string(root, "favicon") : "";
    return uri.startsWith(Favicon.URI_PREFIX) ? new Favicon(uri) : null;
  }

  private static String string(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }
}
