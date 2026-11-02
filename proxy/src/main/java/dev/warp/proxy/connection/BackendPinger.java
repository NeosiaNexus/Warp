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

import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPing;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * Asks backend servers for their server list entry, as a client refreshing its list would: a
 * handshake into the status state, a status request, the JSON answer, then the connection closes.
 */
public final class BackendPinger implements ServerPinger {

  /** How long a server has to connect and answer. */
  private static final int TIMEOUT_SECONDS = 5;

  private final EventLoopGroup group;
  private final Class<? extends Channel> channelClass;

  /**
   * Creates a pinger.
   *
   * @param group the event loops that serve the ping connections
   * @param channelClass the socket channel class of the proxy's transport
   */
  public BackendPinger(EventLoopGroup group, Class<? extends Channel> channelClass) {
    this.group = group;
    this.channelClass = channelClass;
  }

  @Override
  public CompletableFuture<ServerPing> ping(ServerInfo server) {
    CompletableFuture<ServerPing> result = new CompletableFuture<>();
    ProtocolVersion version = ProtocolVersion.latest();
    var _ =
        new Bootstrap()
            .group(group)
            .channel(channelClass)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_SECONDS * 1000)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(
                new ChannelInitializer<>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline()
                        .addLast(
                            ServerChannelInitializer.READ_TIMEOUT,
                            new ReadTimeoutHandler(TIMEOUT_SECONDS, TimeUnit.SECONDS))
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
            .connect(server.address())
            .addListener(
                (ChannelFutureListener)
                    future -> {
                      if (!future.isSuccess()) {
                        result.completeExceptionally(future.cause());
                        return;
                      }
                      Channel channel = future.channel();
                      MinecraftConnection connection =
                          channel.pipeline().get(MinecraftConnection.class);
                      connection.setSessionHandler(
                          new PingSessionHandler(connection, server, version, result));
                      var _ =
                          channel
                              .closeFuture()
                              .addListener(
                                  _ ->
                                      result.completeExceptionally(
                                          new IllegalStateException(
                                              server.name() + " closed without answering")));
                    });
    return result;
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
          result.completeExceptionally(e);
        }
        connection.close();
      }
    }

    @Override
    public void disconnected() {
      result.completeExceptionally(
          new IllegalStateException(server.name() + " closed without answering"));
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
    @Nullable String favicon = root.has("favicon") ? string(root, "favicon") : null;
    return new ServerPing(version, players, description, favicon);
  }

  private static String string(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }
}
