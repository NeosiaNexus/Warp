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
package dev.warp.proxy.fleet;

import dev.warp.api.event.Subscription;
import dev.warp.api.fleet.FleetBus;
import dev.warp.api.fleet.FleetMessage;
import dev.warp.api.fleet.FleetResponder;
import dev.warp.api.fleet.spi.FleetStore;
import dev.warp.proxy.logging.LogCode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bus over a store's channels. A published message rides {@code bus:<channel>} framed as sender
 * id, message id, payload. A request rides the target's inbox, {@code inbox:<node>}, framed as
 * type, sender id, message id, channel, payload; the reply comes back on the requester's inbox with
 * the same id. Channels starting with {@value #RESERVED} belong to the proxy.
 */
final class FleetBusImpl implements FleetBus {

  private static final Logger logger = LoggerFactory.getLogger(FleetBusImpl.class);

  /** The prefix of the channels the proxy uses itself; plugins may not take them. */
  static final String RESERVED = "warp:";

  private static final int REQUEST = 0;
  private static final int REPLY = 1;
  private static final int FAILURE = 2;

  private final String nodeId;
  private final Function<String, String> key;
  private final FleetStore store;
  private final Executor executor;
  private final Predicate<String> nodeAlive;
  private final FleetMetrics metrics;
  private final AtomicLong ids = new AtomicLong(System.nanoTime());
  private final Map<Long, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
  private final Map<String, FleetResponder> responders = new ConcurrentHashMap<>();

  /**
   * Creates the bus.
   *
   * @param nodeId this node
   * @param key namespaces a channel into a store key
   * @param store the store
   * @param executor runs the listeners
   * @param nodeAlive whether a node is in the fleet, for retries
   * @param metrics the fleet's metrics
   */
  FleetBusImpl(
      String nodeId,
      Function<String, String> key,
      FleetStore store,
      Executor executor,
      Predicate<String> nodeAlive,
      FleetMetrics metrics) {
    this.nodeId = nodeId;
    this.key = key;
    this.store = store;
    this.executor = executor;
    this.nodeAlive = nodeAlive;
    this.metrics = metrics;
  }

  /** Subscribes to this node's inbox. Called once the store is connected. */
  Subscription openInbox() {
    return store.subscribe(key.apply("inbox:" + nodeId), this::onInbox);
  }

  /** Registers a responder for one of the proxy's own channels. */
  void respondInternal(String channel, FleetResponder responder) {
    responders.put(channel, responder);
  }

  // ---------------------------------------------------------------------------
  // FleetBus
  // ---------------------------------------------------------------------------

  @Override
  public void publish(String channel, byte[] payload) {
    checkChannel(channel, false);
    if (payload.length > MAX_PAYLOAD) {
      throw new IllegalArgumentException(
          "payload of " + payload.length + " bytes exceeds " + MAX_PAYLOAD);
    }
    byte[] frame =
        new FleetCodec.Writer().string(nodeId).lng(ids.incrementAndGet()).bytes(payload).toBytes();
    metrics.busOut();
    var _ = store.publish(key.apply("bus:" + channel), frame);
  }

  @Override
  public Subscription subscribe(String channel, Consumer<FleetMessage> listener) {
    checkChannel(channel, false);
    return store.subscribe(
        key.apply("bus:" + channel),
        frame -> {
          metrics.busIn();
          FleetMessage message;
          try {
            FleetCodec.Reader reader = new FleetCodec.Reader(frame);
            String sender = reader.string();
            var _ = reader.lng();
            message = new FleetMessage(channel, sender, reader.bytes());
          } catch (IllegalArgumentException e) {
            logger.warn(
                LogCode.FLEET_FRAME_INVALID.marker(),
                "Dropped a malformed fleet message on {}: {}",
                channel,
                e.getMessage());
            return;
          }
          try {
            listener.accept(message);
          } catch (RuntimeException e) {
            logger.warn(
                LogCode.FLEET_HANDLER_FAILED.marker(),
                "A fleet bus listener on {} failed",
                channel,
                e);
          }
        });
  }

  @Override
  public CompletableFuture<byte[]> request(
      String proxyId, String channel, byte[] payload, Duration timeout) {
    checkChannel(channel, false);
    return requestInternal(proxyId, channel, payload, timeout);
  }

  /** {@link #request} for the proxy's own channels too. */
  CompletableFuture<byte[]> requestInternal(
      String proxyId, String channel, byte[] payload, Duration timeout) {
    if (payload.length > MAX_PAYLOAD) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException(
              "payload of " + payload.length + " bytes exceeds " + MAX_PAYLOAD));
    }
    if (!nodeAlive.test(proxyId)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("no proxy " + proxyId + " in the fleet"));
    }
    long id = ids.incrementAndGet();
    CompletableFuture<byte[]> reply = new CompletableFuture<>();
    pending.put(id, reply);
    byte[] frame = frame(REQUEST, id, channel, payload);
    send(proxyId, frame);
    long half = Math.max(1, timeout.toMillis() / 2);
    // One retry at half-time while the target is alive: a lost message, not a dead node.
    CompletableFuture.delayedExecutor(half, TimeUnit.MILLISECONDS, executor)
        .execute(
            () -> {
              if (!reply.isDone() && nodeAlive.test(proxyId)) {
                send(proxyId, frame);
              }
            });
    return reply
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((_, _) -> pending.remove(id));
  }

  @Override
  public Subscription respond(String channel, FleetResponder responder) {
    checkChannel(channel, false);
    responders.put(channel, responder);
    return new Subscription() {
      @Override
      public boolean isActive() {
        return responders.get(channel) == responder;
      }

      @Override
      public void unsubscribe() {
        responders.remove(channel, responder);
      }
    };
  }

  // ---------------------------------------------------------------------------
  // Inbox
  // ---------------------------------------------------------------------------

  private void onInbox(byte[] frame) {
    metrics.busIn();
    int type;
    String sender;
    long id;
    String channel;
    byte[] payload;
    try {
      FleetCodec.Reader reader = new FleetCodec.Reader(frame);
      type = reader.byt();
      sender = reader.string();
      id = reader.lng();
      channel = reader.string();
      payload = reader.bytes();
    } catch (IllegalArgumentException e) {
      logger.warn(
          LogCode.FLEET_FRAME_INVALID.marker(),
          "Dropped a malformed frame on this node's inbox: {}",
          e.getMessage());
      return;
    }
    switch (type) {
      case REQUEST -> answer(sender, id, channel, payload);
      case REPLY -> {
        CompletableFuture<byte[]> waiting = pending.remove(id);
        if (waiting != null) {
          waiting.complete(payload);
        }
      }
      case FAILURE -> {
        CompletableFuture<byte[]> waiting = pending.remove(id);
        if (waiting != null) {
          waiting.completeExceptionally(
              new IllegalStateException(
                  sender
                      + " failed to answer on "
                      + channel
                      + ": "
                      + new String(payload, StandardCharsets.UTF_8)));
        }
      }
      default -> logger.warn(LogCode.FLEET_FRAME_INVALID.marker(), "Unknown inbox frame {}", type);
    }
  }

  private void answer(String sender, long id, String channel, byte[] payload) {
    FleetResponder responder = responders.get(channel);
    if (responder == null) {
      send(
          sender,
          frame(
              FAILURE,
              id,
              channel,
              ("no responder on " + channel).getBytes(StandardCharsets.UTF_8)));
      return;
    }
    CompletableFuture<byte[]> result;
    try {
      result = responder.answer(new FleetMessage(channel, sender, payload));
    } catch (RuntimeException e) {
      result = CompletableFuture.failedFuture(e);
    }
    var _ =
        result.whenComplete(
            (reply, error) -> {
              if (error != null || reply == null) {
                String text = error == null ? "no reply" : String.valueOf(error.getMessage());
                if (error != null && !(error instanceof TimeoutException)) {
                  logger.warn(
                      LogCode.FLEET_HANDLER_FAILED.marker(),
                      "The responder on {} failed to answer {}",
                      channel,
                      sender,
                      error);
                }
                send(sender, frame(FAILURE, id, channel, text.getBytes(StandardCharsets.UTF_8)));
              } else {
                send(sender, frame(REPLY, id, channel, reply));
              }
            });
  }

  private void send(String target, byte[] frame) {
    metrics.busOut();
    var _ = store.publish(key.apply("inbox:" + target), frame);
  }

  private byte[] frame(int type, long id, String channel, byte[] payload) {
    return new FleetCodec.Writer()
        .byt(type)
        .string(nodeId)
        .lng(id)
        .string(channel)
        .bytes(payload)
        .toBytes();
  }

  private static void checkChannel(String channel, boolean internal) {
    if (channel.isEmpty()) {
      throw new IllegalArgumentException("empty channel");
    }
    if (!internal && channel.startsWith(RESERVED)) {
      throw new IllegalArgumentException(
          "channels starting with " + RESERVED + " belong to the proxy");
    }
  }
}
