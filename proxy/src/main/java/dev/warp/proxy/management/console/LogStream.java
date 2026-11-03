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
package dev.warp.proxy.management.console;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;

/**
 * One client of the live log: an open Server-Sent Events response. Lines are written on the
 * channel's event loop, in order, each once; a client too slow to read them (the channel no longer
 * writable) misses lines rather than making the proxy hold them, and is then told how many with a
 * {@code gap} event. A comment every 15 seconds keeps idle connections open through proxies.
 */
final class LogStream implements LogHistory.Subscriber {

  /** How often an idle stream sends a comment. */
  static final long HEARTBEAT_SECONDS = 15;

  /** How long a client waits before it reconnects, as the stream tells it. */
  static final long RETRY_MILLIS = 2000;

  private final Channel channel;
  private final LogFilter filter;
  private final String instance;
  private final boolean commands;

  /** Touched on the event loop only. */
  private long lastSent;

  private long missed;
  private long sent;
  private @Nullable ScheduledFuture<?> heartbeat;

  /**
   * Creates the stream of a channel whose response headers are written.
   *
   * @param channel the client's channel
   * @param filter which lines it receives
   * @param instance the identifier of this run of the proxy, which prefixes event ids
   * @param commands whether its token may run commands, and so wants to know when they change
   */
  LogStream(Channel channel, LogFilter filter, String instance, boolean commands) {
    this.channel = channel;
    this.filter = filter;
    this.instance = instance;
    this.commands = commands;
  }

  /**
   * Starts the stream: the hello event, the replay, then the heartbeat. On the event loop.
   *
   * @param hello the {@code hello} event's data
   * @param replay the lines read again
   * @param resumed whether the client resumed where it left off
   */
  void open(JsonObject hello, LogHistory.Replay replay, boolean resumed) {
    write("retry: " + RETRY_MILLIS + "\n" + event("hello", hello));
    if (resumed && replay.lost() > 0) {
      write(gap(replay.lost(), "history"));
    }
    for (ConsoleRecord record : replay.records()) {
      writeRecord(record);
    }
    lastSent = Math.max(lastSent, replay.last());
    heartbeat =
        channel
            .eventLoop()
            .scheduleAtFixedRate(
                () -> write(": keep-alive\n\n"),
                HEARTBEAT_SECONDS,
                HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
  }

  @Override
  public void offer(ConsoleRecord record) {
    if (channel.isActive() && filter.test(record)) {
      channel.eventLoop().execute(() -> deliver(record));
    }
  }

  private void deliver(ConsoleRecord record) {
    if (record.id() <= lastSent) {
      return;
    }
    lastSent = record.id();
    if (!channel.isWritable()) {
      missed++;
      return;
    }
    if (missed > 0) {
      write(gap(missed, "slow"));
      missed = 0;
    }
    writeRecord(record);
  }

  /** Tells the client that the commands changed, if it may run them. From any thread. */
  void commandsChanged() {
    if (commands && channel.isActive()) {
      channel.eventLoop().execute(() -> write(event("commands", new JsonObject())));
    }
  }

  /**
   * Ends the stream with a {@code bye} event, and closes it. From any thread.
   *
   * @param reason why, such as {@code stopping}
   */
  void close(String reason) {
    channel
        .eventLoop()
        .execute(
            () -> {
              JsonObject data = new JsonObject();
              data.addProperty("reason", reason);
              write(event("bye", data));
              var _ =
                  channel
                      .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                      .addListener(ChannelFutureListener.CLOSE);
            });
  }

  /**
   * Stops the heartbeat, once the channel closed.
   *
   * @return how many lines the client received
   */
  long closed() {
    ScheduledFuture<?> current = heartbeat;
    if (current != null) {
      current.cancel(false);
    }
    return sent;
  }

  private void writeRecord(ConsoleRecord record) {
    sent++;
    write(record.frame(instance));
  }

  private void write(String text) {
    write(text.getBytes(StandardCharsets.UTF_8));
  }

  private void write(byte[] bytes) {
    var _ =
        channel
            .writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)))
            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
  }

  private static String gap(long missed, String reason) {
    JsonObject data = new JsonObject();
    data.addProperty("missed", missed);
    data.addProperty("reason", reason);
    return event("gap", data);
  }

  private static String event(String name, JsonObject data) {
    return "event: " + name + "\ndata: " + data + "\n\n";
  }
}
