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

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One connection to Valkey: RESP3 after {@code HELLO 3}, pipelined, reconnecting by itself with a
 * jittered backoff from {@value #BACKOFF_MIN_MILLIS} ms to {@value #BACKOFF_MAX_MILLIS} ms. Replies
 * complete the futures of the commands in order; pushes go to {@link #onPush}. Everything runs on
 * one event loop of the proxy's worker group: a command from any thread is handed to that loop,
 * which writes it and queues its future, so that replies never pair with the wrong command.
 *
 * <p>Bounded: at most {@value #MAX_PENDING} commands wait for a reply; beyond that a command fails
 * at once rather than growing the heap while the server stalls.
 */
final class ValkeyConnection {

  private static final Logger logger = LoggerFactory.getLogger(ValkeyConnection.class);

  static final int MAX_PENDING = 10_000;
  static final long BACKOFF_MIN_MILLIS = 200;
  static final long BACKOFF_MAX_MILLIS = 10_000;
  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

  /** Thrown when the server answers a command with an error, or the connection is down. */
  static final class ValkeyException extends RuntimeException {
    private final String code;

    ValkeyException(String message) {
      super(message);
      int space = message.indexOf(' ');
      this.code = space < 0 ? message : message.substring(0, space);
    }

    /** The error's first word: {@code ERR}, {@code NOSCRIPT}, {@code NOAUTH}... */
    String code() {
      return code;
    }
  }

  private final String name;
  private final InetSocketAddress address;
  private final @Nullable SslContext ssl;
  private final @Nullable String username;
  private final @Nullable String password;
  private final int database;
  private final Supplier<EventLoopGroup> loops;
  private final Supplier<Class<? extends Channel>> channelClass;
  private final Consumer<RespValue> onPush;
  private final Function<ValkeyConnection, CompletableFuture<?>> onReady;
  private final Consumer<Boolean> onState;
  private final Consumer<Throwable> onFailure;

  private final Queue<CompletableFuture<RespValue>> pending = new ArrayDeque<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile @Nullable Channel channel;
  private volatile boolean ready;

  /** Between HELLO and readiness: the owner's own readiness commands may flow. */
  private volatile boolean handshaking;

  private volatile long backoffMillis = BACKOFF_MIN_MILLIS;
  private volatile @Nullable EventLoop loop;

  /**
   * Creates the connection; {@link #start} opens it.
   *
   * @param name what the logs call it
   * @param address the server
   * @param ssl the TLS context, or {@code null} for plain TCP
   * @param username the ACL user, or {@code null} for the default user
   * @param password the password, or {@code null} for none
   * @param database the logical database to select
   * @param loops the proxy's worker loops
   * @param channelClass the client channel class of the transport
   * @param onPush receives pushes (pub/sub messages), on the event loop
   * @param onReady runs after {@code HELLO} on each connection, before commands flow: its future
   *     gates readiness (loading functions, subscribing again)
   * @param onState told {@code true} once ready, {@code false} when the connection drops
   * @param onFailure told why a connection attempt or the handshake failed
   */
  ValkeyConnection(
      String name,
      InetSocketAddress address,
      @Nullable SslContext ssl,
      @Nullable String username,
      @Nullable String password,
      int database,
      Supplier<EventLoopGroup> loops,
      Supplier<Class<? extends Channel>> channelClass,
      Consumer<RespValue> onPush,
      Function<ValkeyConnection, CompletableFuture<?>> onReady,
      Consumer<Boolean> onState,
      Consumer<Throwable> onFailure) {
    this.name = name;
    this.address = address;
    this.ssl = ssl;
    this.username = username;
    this.password = password;
    this.database = database;
    this.loops = loops;
    this.channelClass = channelClass;
    this.onPush = onPush;
    this.onReady = onReady;
    this.onState = onState;
    this.onFailure = onFailure;
  }

  /** Opens the connection; reconnects by itself until {@link #close}. */
  void start() {
    EventLoop chosen = loops.get().next();
    this.loop = chosen;
    chosen.execute(this::connect);
  }

  /** Whether commands can be sent now. */
  boolean isReady() {
    return ready;
  }

  /** Closes for good. */
  void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Channel current = channel;
    if (current != null) {
      current.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------------

  /**
   * Sends a command and awaits its reply.
   *
   * @param args the command and its arguments, each a {@link String} or a {@code byte[]}
   * @return completed with the reply, or exceptionally with a {@link ValkeyException}
   */
  CompletableFuture<RespValue> command(Object... args) {
    CompletableFuture<RespValue> reply = new CompletableFuture<>();
    EventLoop current = loop;
    if (current == null) {
      reply.completeExceptionally(new ValkeyException("DOWN " + name + " not started"));
      return reply;
    }
    current.execute(
        () -> {
          Channel open = channel;
          if (open == null || !(ready || handshaking) || !open.isActive()) {
            reply.completeExceptionally(new ValkeyException("DOWN " + name + " is not connected"));
            return;
          }
          if (pending.size() >= MAX_PENDING) {
            reply.completeExceptionally(
                new ValkeyException("BUSY " + name + " has " + MAX_PENDING + " commands waiting"));
            return;
          }
          pending.add(reply);
          write(open, args);
        });
    return reply;
  }

  /**
   * Sends a command whose reply arrives as a push, not in order: {@code SUBSCRIBE}.
   *
   * @param args the command and its arguments
   */
  void fireAndForget(Object... args) {
    EventLoop current = loop;
    if (current == null) {
      return;
    }
    current.execute(
        () -> {
          Channel open = channel;
          if (open != null && open.isActive()) {
            write(open, args);
          }
        });
  }

  /** A command during the handshake, before {@link #ready}: on the loop already. */
  private CompletableFuture<RespValue> handshake(Channel open, Object... args) {
    CompletableFuture<RespValue> reply = new CompletableFuture<>();
    pending.add(reply);
    write(open, args);
    return reply;
  }

  private static void write(Channel open, Object... args) {
    ByteBuf out = open.alloc().buffer();
    RespCodec.writeCommand(out, args);
    open.writeAndFlush(out, open.voidPromise());
  }

  // ---------------------------------------------------------------------------
  // Connecting
  // ---------------------------------------------------------------------------

  private void connect() {
    if (closed.get()) {
      return;
    }
    EventLoop current = loop;
    if (current == null) {
      return;
    }
    Bootstrap bootstrap =
        new Bootstrap()
            .group(current)
            .channel(channelClass.get())
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    if (ssl != null) {
                      ch.pipeline()
                          .addLast(
                              "tls",
                              ssl.newHandler(
                                  ch.alloc(), address.getHostString(), address.getPort()));
                    }
                    ch.pipeline().addLast("resp", new Decoder()).addLast("handler", new Handler());
                  }
                });
    ChannelFuture connecting = bootstrap.connect(address);
    connecting.addListener(
        (ChannelFuture done) -> {
          if (!done.isSuccess()) {
            Throwable cause = done.cause();
            onFailure.accept(cause == null ? new ValkeyException("DOWN connect failed") : cause);
            scheduleReconnect();
            return;
          }
          Channel open = done.channel();
          this.channel = open;
          hello(open);
        });
  }

  /** {@code HELLO 3 [AUTH user pass]}, {@code SELECT db}, then the owner's own readiness. */
  private void hello(Channel open) {
    Object[] hello =
        password == null
            ? new Object[] {"HELLO", "3"}
            : new Object[] {
              "HELLO", "3", "AUTH", username == null ? "default" : username, password
            };
    handshaking = true;
    CompletableFuture<RespValue> step = handshake(open, hello);
    if (database != 0) {
      step = step.thenCompose(_ -> handshake(open, "SELECT", Integer.toString(database)));
    }
    var _ =
        step.thenCompose(_ -> onReady.apply(this))
            .whenCompleteAsync(
                (_, error) -> {
                  handshaking = false;
                  if (error != null) {
                    onFailure.accept(error);
                    open.close();
                    return;
                  }
                  if (open.isActive()) {
                    ready = true;
                    backoffMillis = BACKOFF_MIN_MILLIS;
                    logger.debug("{}: connected to {}", name, address);
                    onState.accept(true);
                  }
                },
                open.eventLoop());
  }

  private void scheduleReconnect() {
    if (closed.get()) {
      return;
    }
    EventLoop current = loop;
    if (current == null) {
      return;
    }
    long delay = backoffMillis;
    backoffMillis = Math.min(BACKOFF_MAX_MILLIS, delay * 2);
    long jittered = delay / 2 + ThreadLocalRandom.current().nextLong(delay / 2 + 1);
    var _ = current.schedule(this::connect, jittered, TimeUnit.MILLISECONDS);
  }

  /** Called on the loop once the channel is gone: fails what waited and reconnects. */
  private void lost(@Nullable Throwable cause) {
    boolean wasReady = ready;
    ready = false;
    handshaking = false;
    channel = null;
    ValkeyException down =
        new ValkeyException(
            "DOWN "
                + name
                + " lost its connection"
                + (cause == null ? "" : ": " + cause.getMessage()));
    CompletableFuture<RespValue> waiting;
    while ((waiting = pending.poll()) != null) {
      waiting.completeExceptionally(down);
    }
    if (wasReady) {
      onState.accept(false);
    }
    scheduleReconnect();
  }

  /** Reads whole RESP values from the stream. */
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

  /** Pairs replies with the commands that wait, and hands pushes over. */
  private final class Handler extends SimpleChannelInboundHandler<RespValue> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RespValue value) {
      if (value instanceof RespValue.Push push) {
        onPush.accept(push);
        return;
      }
      CompletableFuture<RespValue> waiting = pending.poll();
      if (waiting == null) {
        logger.debug("{}: unexpected reply {}", name, value);
        return;
      }
      if (value instanceof RespValue.Err(String message)) {
        waiting.completeExceptionally(new ValkeyException(message));
      } else {
        waiting.complete(value);
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      lost(null);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      logger.debug("{}: connection error", name, cause);
      ctx.close();
    }
  }
}
