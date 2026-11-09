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

import dev.warp.api.event.Subscription;
import dev.warp.api.fleet.NodeState;
import dev.warp.api.fleet.StoreState;
import dev.warp.api.fleet.spi.ClaimResult;
import dev.warp.api.fleet.spi.FleetStore;
import dev.warp.api.fleet.spi.FleetStoreContext;
import dev.warp.api.fleet.spi.NodeRecord;
import dev.warp.api.fleet.spi.PlayerRecord;
import dev.warp.proxy.config.FleetConfig;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fleet store on Valkey (or Redis 6.2 and later): one command connection, pipelined, and one
 * subscriber connection, both on the proxy's event loops through {@link ValkeyConnection}. The
 * atomic operations are the functions of {@link ValkeyFunctions}, loaded at each connection, with
 * {@code EVALSHA} as the fallback for a server without {@code FUNCTION}.
 *
 * <p>Keys are prefixed with {@code {warp:<network>}:}, which hash-tags every key of a network onto
 * one cluster slot. Channels are published as the fleet names them ({@code warp:<network>:...}).
 *
 * <p>The state is {@link StoreState#CONNECTED} while both connections are ready and the functions
 * are loaded; anything less is {@link StoreState#DEGRADED}, and the connections reconnect by
 * themselves. {@code MOVED} redirects of a cluster are not followed yet: point the address at the
 * node that owns the network's slot, or at a cluster-aware proxy.
 */
public final class ValkeyFleetStore implements FleetStore {

  private static final Logger logger = LoggerFactory.getLogger(ValkeyFleetStore.class);

  private final FleetConfig.Valkey settings;
  private final Duration heartbeatTtl;
  private final Supplier<EventLoopGroup> loops;
  private final Supplier<Class<? extends Channel>> channelClass;
  private final Map<String, List<Consumer<byte[]>>> subscribers = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final String[] scriptShas = new String[ValkeyFunctions.NAMES.size()];

  private volatile String prefix = "{warp:main}:";
  private volatile Executor executor = Runnable::run;
  private volatile Consumer<StoreState> stateListener = _ -> {};
  private volatile StoreState state = StoreState.DEGRADED;
  private volatile boolean commandsReady;
  private volatile boolean subscriberReady;
  private volatile boolean useScripts;
  private volatile @Nullable ValkeyConnection commands;
  private volatile @Nullable ValkeyConnection subscriber;
  private volatile @Nullable Throwable lastFailure;

  /**
   * Creates the store; {@link #connect} opens the connections.
   *
   * @param config the fleet section, for the Valkey settings and the heartbeat time-to-live
   * @param loops the proxy's worker loops, available by the time {@link #connect} runs
   * @param channelClass the client channel class of the detected transport
   */
  public ValkeyFleetStore(
      FleetConfig config,
      Supplier<EventLoopGroup> loops,
      Supplier<Class<? extends Channel>> channelClass) {
    this.settings = config.store().valkey();
    this.heartbeatTtl = config.heartbeatTtl();
    this.loops = loops;
    this.channelClass = channelClass;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public CompletableFuture<Void> connect(FleetStoreContext context) {
    this.prefix = "{warp:" + context.networkId() + "}:";
    this.executor = context.executor();
    InetSocketAddress address = parseAddress(settings.address());
    SslContext ssl = settings.tls() ? clientTls() : null;
    CompletableFuture<Void> first = new CompletableFuture<>();
    ValkeyConnection command =
        new ValkeyConnection(
            "valkey",
            address,
            ssl,
            settings.username(),
            settings.password(),
            settings.database(),
            loops,
            channelClass,
            _ -> {},
            this::loadFunctions,
            ready -> {
              commandsReady = ready;
              updateState();
              if (state == StoreState.CONNECTED) {
                first.complete(null);
              }
            },
            failure -> {
              lastFailure = failure;
              logger.debug("valkey: {}", failure.toString());
              first.complete(null);
            });
    ValkeyConnection sub =
        new ValkeyConnection(
            "valkey-sub",
            address,
            ssl,
            settings.username(),
            settings.password(),
            settings.database(),
            loops,
            channelClass,
            this::onPush,
            this::resubscribe,
            ready -> {
              subscriberReady = ready;
              updateState();
              if (state == StoreState.CONNECTED) {
                first.complete(null);
              }
            },
            failure -> {
              lastFailure = failure;
              first.complete(null);
            });
    this.commands = command;
    this.subscriber = sub;
    command.start();
    sub.start();
    return first;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    ValkeyConnection command = commands;
    if (command != null) {
      command.close();
    }
    ValkeyConnection sub = subscriber;
    if (sub != null) {
      sub.close();
    }
  }

  @Override
  public StoreState state() {
    return state;
  }

  @Override
  public void onStateChange(Consumer<StoreState> listener) {
    this.stateListener = listener;
  }

  /**
   * Returns why the last connection attempt failed, for the operator's warning.
   *
   * @return the failure, or {@code null} when none happened
   */
  public @Nullable Throwable lastFailure() {
    return lastFailure;
  }

  private void updateState() {
    StoreState now = commandsReady && subscriberReady ? StoreState.CONNECTED : StoreState.DEGRADED;
    if (now == state) {
      return;
    }
    state = now;
    if (now == StoreState.DEGRADED && lastFailure != null) {
      logger.debug("valkey: degraded, last failure {}", String.valueOf(lastFailure));
    }
    Consumer<StoreState> listener = stateListener;
    executor.execute(() -> listener.accept(now));
  }

  /** On the command connection, after HELLO: the functions, or the scripts on an old server. */
  private CompletableFuture<?> loadFunctions(ValkeyConnection connection) {
    return connection
        .command("FUNCTION", "LOAD", "REPLACE", ValkeyFunctions.library())
        .handle(
            (_, error) -> {
              if (error == null) {
                useScripts = false;
                return CompletableFuture.<Void>completedFuture(null);
              }
              if (!(unwrap(error) instanceof ValkeyConnection.ValkeyException e)
                  || !e.code().equals("ERR")) {
                return CompletableFuture.<Void>failedFuture(error);
              }
              // No FUNCTION command (Redis 6.2): plain scripts do the same.
              useScripts = true;
              logger.info("The fleet store has no FUNCTION command: using EVALSHA scripts");
              CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
              for (int i = 0; i < ValkeyFunctions.NAMES.size(); i++) {
                int index = i;
                all =
                    all.thenCompose(
                        _ ->
                            connection
                                .command("SCRIPT", "LOAD", ValkeyFunctions.script(index))
                                .thenAccept(sha -> scriptShas[index] = String.valueOf(sha.text())));
              }
              return all;
            })
        .thenCompose(f -> f);
  }

  /** On the subscriber connection, after HELLO: every channel again. */
  private CompletableFuture<?> resubscribe(ValkeyConnection connection) {
    for (String channel : subscribers.keySet()) {
      connection.fireAndForget("SUBSCRIBE", channel);
    }
    return CompletableFuture.completedFuture(null);
  }

  // ---------------------------------------------------------------------------
  // Records
  // ---------------------------------------------------------------------------

  private ValkeyConnection commandConnection() {
    ValkeyConnection connection = commands;
    if (connection == null) {
      throw new IllegalStateException("the store is not connected yet");
    }
    return connection;
  }

  private String key(String suffix) {
    return prefix + suffix;
  }

  @Override
  public CompletableFuture<Long> nextIncarnation(String nodeId) {
    return commandConnection()
        .command("INCR", key("inc:" + nodeId))
        .thenApply(value -> Long.parseLong(String.valueOf(value.text())));
  }

  @Override
  public CompletableFuture<Void> heartbeat(NodeRecord node) {
    ValkeyConnection connection = commandConnection();
    String hash = key("node:" + node.id());
    CompletableFuture<RespValue> set =
        connection.command(
            "HSET",
            hash,
            "inc",
            Long.toString(node.incarnation()),
            "addr",
            node.publicAddress() == null ? "" : node.publicAddress(),
            "state",
            node.state().name().toLowerCase(Locale.ROOT),
            "players",
            Integer.toString(node.players()),
            "version",
            node.version(),
            "started",
            Long.toString(node.started().toEpochMilli()));
    CompletableFuture<RespValue> expire =
        connection.command("PEXPIRE", hash, Long.toString(heartbeatTtl.toMillis()));
    CompletableFuture<RespValue> member = connection.command("SADD", key("nodes"), node.id());
    return CompletableFuture.allOf(set, expire, member);
  }

  @Override
  public CompletableFuture<Collection<NodeRecord>> nodes() {
    ValkeyConnection connection = commandConnection();
    return connection
        .command("SMEMBERS", key("nodes"))
        .thenCompose(
            members -> {
              List<String> ids = new ArrayList<>();
              List<CompletableFuture<RespValue>> hashes = new ArrayList<>();
              for (RespValue member : members.items()) {
                String id = String.valueOf(member.text());
                ids.add(id);
                hashes.add(connection.command("HGETALL", key("node:" + id)));
              }
              return CompletableFuture.allOf(hashes.toArray(CompletableFuture[]::new))
                  .thenApply(
                      _ -> {
                        List<NodeRecord> live = new ArrayList<>();
                        for (int i = 0; i < ids.size(); i++) {
                          Map<String, String> fields = fields(hashes.get(i).join());
                          if (fields.isEmpty()) {
                            // Expired: its id stays in the set until a reap removes it.
                            continue;
                          }
                          live.add(toNode(ids.get(i), fields));
                        }
                        return live;
                      });
            });
  }

  @Override
  public CompletableFuture<Collection<PlayerRecord>> players(String nodeId) {
    ValkeyConnection connection = commandConnection();
    return connection
        .command("SMEMBERS", key("node:" + nodeId + ":players"))
        .thenCompose(
            members -> {
              List<UUID> uuids = new ArrayList<>();
              List<CompletableFuture<RespValue>> hashes = new ArrayList<>();
              for (RespValue member : members.items()) {
                UUID uuid = UUID.fromString(String.valueOf(member.text()));
                uuids.add(uuid);
                hashes.add(connection.command("HGETALL", key("player:" + uuid)));
              }
              return CompletableFuture.allOf(hashes.toArray(CompletableFuture[]::new))
                  .thenApply(
                      _ -> {
                        List<PlayerRecord> records = new ArrayList<>();
                        for (int i = 0; i < uuids.size(); i++) {
                          Map<String, String> fields = fields(hashes.get(i).join());
                          if (fields.isEmpty() || !nodeId.equals(fields.get("node"))) {
                            continue;
                          }
                          records.add(toPlayer(uuids.get(i), fields));
                        }
                        return records;
                      });
            });
  }

  @Override
  public CompletableFuture<ClaimResult> claim(PlayerRecord player) {
    return call(
            0,
            player.uuid().toString(),
            player.name(),
            player.nodeId(),
            Long.toString(player.incarnation()),
            player.server() == null ? "" : player.server(),
            Long.toString(player.since().toEpochMilli()))
        .thenApply(
            reply -> {
              List<RespValue> items = reply.items();
              if (items.size() == 3 && "OWNED".equals(items.getFirst().text())) {
                return new ClaimResult.Owned(
                    String.valueOf(items.get(1).text()),
                    Long.parseLong(String.valueOf(items.get(2).text())));
              }
              return new ClaimResult.Claimed();
            });
  }

  @Override
  public CompletableFuture<Void> release(UUID player, String nodeId, long incarnation) {
    return call(1, player.toString(), nodeId, Long.toString(incarnation)).thenApply(_ -> null);
  }

  @Override
  public CompletableFuture<Void> move(
      UUID player, String nodeId, long incarnation, @Nullable String server) {
    return call(
            2, player.toString(), nodeId, Long.toString(incarnation), server == null ? "" : server)
        .thenApply(_ -> null);
  }

  @Override
  public CompletableFuture<Integer> reap(String nodeId, long incarnation) {
    return call(3, nodeId, Long.toString(incarnation))
        .thenApply(reply -> Integer.parseInt(String.valueOf(reply.text())));
  }

  /** Calls function {@code index} with the prefix and {@code args}, by FCALL or EVALSHA. */
  private CompletableFuture<RespValue> call(int index, String... args) {
    ValkeyConnection connection = commandConnection();
    Object[] command = new Object[args.length + 4];
    if (useScripts) {
      command[0] = "EVALSHA";
      command[1] = String.valueOf(scriptShas[index]);
    } else {
      command[0] = "FCALL";
      command[1] = ValkeyFunctions.NAMES.get(index);
    }
    command[2] = "0";
    command[3] = prefix;
    System.arraycopy(args, 0, command, 4, args.length);
    return connection
        .command(command)
        .handle(
            (reply, error) -> {
              if (error == null) {
                return CompletableFuture.completedFuture(reply);
              }
              if (useScripts
                  && unwrap(error) instanceof ValkeyConnection.ValkeyException e
                  && e.code().equals("NOSCRIPT")) {
                // The server lost its scripts (a restart, a flush): load this one again.
                return connection
                    .command("SCRIPT", "LOAD", ValkeyFunctions.script(index))
                    .thenCompose(
                        sha -> {
                          scriptShas[index] = String.valueOf(sha.text());
                          command[1] = scriptShas[index];
                          return connection.command(command);
                        });
              }
              return CompletableFuture.<RespValue>failedFuture(error);
            })
        .thenCompose(f -> f);
  }

  // ---------------------------------------------------------------------------
  // Channels
  // ---------------------------------------------------------------------------

  @Override
  public CompletableFuture<Void> publish(String channel, byte[] payload) {
    return commandConnection().command("PUBLISH", channel, payload).thenApply(_ -> null);
  }

  @Override
  public Subscription subscribe(String channel, Consumer<byte[]> listener) {
    List<Consumer<byte[]>> listeners =
        subscribers.computeIfAbsent(
            channel,
            c -> {
              ValkeyConnection sub = subscriber;
              if (sub != null && sub.isReady()) {
                sub.fireAndForget("SUBSCRIBE", c);
              }
              return new CopyOnWriteArrayList<>();
            });
    listeners.add(listener);
    return new Subscription() {
      private volatile boolean active = true;

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public void unsubscribe() {
        active = false;
        listeners.remove(listener);
        if (listeners.isEmpty() && subscribers.remove(channel, listeners)) {
          ValkeyConnection sub = subscriber;
          if (sub != null) {
            sub.fireAndForget("UNSUBSCRIBE", channel);
          }
        }
      }
    };
  }

  /**
   * A push on the subscriber connection: {@code message <channel> <payload>}, or a confirmation.
   */
  private void onPush(RespValue push) {
    List<RespValue> items = push.items();
    if (items.size() != 3 || !"message".equals(items.getFirst().text())) {
      return;
    }
    String channel = String.valueOf(items.get(1).text());
    if (!(items.get(2) instanceof RespValue.Blob(byte[] payload))) {
      return;
    }
    List<Consumer<byte[]>> listeners = subscribers.get(channel);
    if (listeners == null) {
      return;
    }
    executor.execute(
        () -> {
          for (Consumer<byte[]> listener : listeners) {
            listener.accept(payload);
          }
        });
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Map<String, String> fields(RespValue hash) {
    List<RespValue> entries = hash.items();
    Map<String, String> fields = new HashMap<>();
    for (int i = 0; i + 1 < entries.size(); i += 2) {
      fields.put(String.valueOf(entries.get(i).text()), String.valueOf(entries.get(i + 1).text()));
    }
    return fields;
  }

  private static NodeRecord toNode(String id, Map<String, String> fields) {
    String addr = fields.getOrDefault("addr", "");
    return new NodeRecord(
        id,
        Long.parseLong(fields.getOrDefault("inc", "0")),
        addr.isEmpty() ? null : addr,
        "draining".equals(fields.get("state")) ? NodeState.DRAINING : NodeState.READY,
        Integer.parseInt(fields.getOrDefault("players", "0")),
        fields.getOrDefault("version", ""),
        Instant.ofEpochMilli(Long.parseLong(fields.getOrDefault("started", "0"))));
  }

  private static PlayerRecord toPlayer(UUID uuid, Map<String, String> fields) {
    String server = fields.getOrDefault("server", "");
    return new PlayerRecord(
        uuid,
        fields.getOrDefault("name", ""),
        fields.getOrDefault("node", ""),
        Long.parseLong(fields.getOrDefault("inc", "0")),
        server.isEmpty() ? null : server,
        Instant.ofEpochMilli(Long.parseLong(fields.getOrDefault("since", "0"))));
  }

  private static Throwable unwrap(Throwable error) {
    Throwable cause = error;
    while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  /**
   * Parses {@code host:port} or {@code [ipv6]:port}; the port defaults to 6379.
   *
   * @param text the address
   * @return the address, unresolved until the connection resolves it
   */
  static InetSocketAddress parseAddress(String text) {
    String value = text.trim();
    int port = 6379;
    String host = value;
    if (value.startsWith("[")) {
      int end = value.indexOf(']');
      host = value.substring(1, end);
      if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
        port = Integer.parseInt(value.substring(end + 2));
      }
    } else {
      int colon = value.lastIndexOf(':');
      if (colon > 0 && value.indexOf(':') == colon) {
        host = value.substring(0, colon);
        port = Integer.parseInt(value.substring(colon + 1));
      }
    }
    return InetSocketAddress.createUnresolved(host, port);
  }

  private static SslContext clientTls() {
    try {
      return SslContextBuilder.forClient().build();
    } catch (javax.net.ssl.SSLException e) {
      throw new IllegalStateException("cannot create the TLS context for the fleet store", e);
    }
  }
}
