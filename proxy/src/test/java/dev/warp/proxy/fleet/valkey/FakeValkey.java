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
package dev.warp.proxy.fleet.valkey;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.jspecify.annotations.Nullable;

/**
 * A server that speaks just enough RESP for the connection tests: every command it receives goes to
 * a scripted responder, whose answer (raw RESP, or {@code null} for silence) is written back. The
 * commands received are kept, by their words.
 */
final class FakeValkey implements AutoCloseable {

  /** Answers HELLO with an empty map, PING with PONG and anything else with OK. */
  static final Function<List<String>, @Nullable String> ANSWERING =
      command ->
          switch (command.getFirst()) {
            case "HELLO" -> "%0\r\n";
            case "PING" -> "+PONG\r\n";
            default -> "+OK\r\n";
          };

  final EventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
  final List<List<String>> received = new CopyOnWriteArrayList<>();
  final List<Channel> clients = new CopyOnWriteArrayList<>();
  private volatile Function<List<String>, @Nullable String> responder;
  private final Channel server;

  FakeValkey(Function<List<String>, @Nullable String> responder) {
    this.responder = responder;
    this.server =
        new ServerBootstrap()
            .group(loops)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    clients.add(ch);
                    ch.pipeline().addLast(new Decoder()).addLast(new Handler());
                  }
                })
            .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .syncUninterruptibly()
            .channel();
  }

  InetSocketAddress address() {
    return (InetSocketAddress) server.localAddress();
  }

  Class<NioSocketChannel> clientChannel() {
    return NioSocketChannel.class;
  }

  /** Changes what the server answers from now on. */
  void answer(Function<List<String>, @Nullable String> responder) {
    this.responder = responder;
  }

  /** Whether a command with that first word arrived. */
  boolean received(String word) {
    return received.stream().anyMatch(command -> command.getFirst().equals(word));
  }

  @Override
  public void close() {
    server.close().syncUninterruptibly();
    var _ = loops.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
  }

  private static final class Decoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
      while (true) {
        RespValue value = RespCodec.read(in);
        if (value == null) {
          return;
        }
        out.add(value);
      }
    }
  }

  private final class Handler extends SimpleChannelInboundHandler<RespValue> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RespValue value) {
      List<String> words = new ArrayList<>();
      for (RespValue item : value.items()) {
        words.add(String.valueOf(item.text()));
      }
      received.add(List.copyOf(words));
      String reply = responder.apply(words);
      if (reply != null) {
        var _ = ctx.writeAndFlush(Unpooled.copiedBuffer(reply, StandardCharsets.UTF_8));
      }
    }
  }
}
