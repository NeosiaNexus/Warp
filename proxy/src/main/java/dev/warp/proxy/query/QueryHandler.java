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

import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Metrics;
import dev.warp.proxy.config.QueryConfig;
import dev.warp.proxy.limit.AddressKey;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers GS4 Query datagrams: a handshake with a challenge token, a stat with the snapshot.
 *
 * <p>Order of checks, each before the next costs anything: the global bucket, the per-address
 * bucket, the length and the magic, the type, then the token for a stat. Whatever fails is dropped
 * without a reply and counted under {@code warp_query_requests_total}; no exception leaves the
 * handler and the channel stays open. The only allocation per datagram besides Netty's envelope is
 * the reply buffer, and a stat reply is a copy of a body encoded once per snapshot.
 *
 * <p>Protocol: magic {@code FE FD}, a type byte ({@code 09} handshake, {@code 00} stat), an int32
 * session id echoed as received. A stat carries the token as a big-endian int32; four more bytes of
 * padding make it a full stat. The handshake reply carries the token as a null-terminated decimal
 * string.
 */
@ChannelHandler.Sharable
public final class QueryHandler extends SimpleChannelInboundHandler<DatagramPacket> {

  private static final Logger logger = LoggerFactory.getLogger(QueryHandler.class);

  static final int MAGIC = 0xFEFD;
  static final byte TYPE_HANDSHAKE = 9;
  static final byte TYPE_STAT = 0;

  /** Magic, type and session id. */
  static final int HANDSHAKE_LENGTH = 7;

  /** A handshake plus the token. */
  static final int BASIC_STAT_LENGTH = 11;

  /** A basic stat plus four bytes of padding. */
  static final int FULL_STAT_LENGTH = 15;

  /** The most bytes a handshake reply takes: the header, ten decimal digits and a terminator. */
  private static final int HANDSHAKE_REPLY_LENGTH = QuerySnapshot.HEADER + 10 + 1;

  private static final String[] TYPES = {"handshake", "basic", "full", "unknown"};
  private static final int HANDSHAKE = 0;
  private static final int BASIC = 1;
  private static final int FULL = 2;
  private static final int UNKNOWN = 3;

  private static final String[] OUTCOMES = {
    "answered", "no_token", "rate_limited", "malformed", "ignored"
  };
  private static final int ANSWERED = 0;
  private static final int NO_TOKEN = 1;
  private static final int RATE_LIMITED = 2;
  private static final int MALFORMED = 3;
  private static final int IGNORED = 4;

  private final QueryStatus status;
  private final ChallengeTable challenges;
  private final QueryRateLimiter perAddress;
  private final QueryRateLimiter global;
  private final LongSupplier nanoClock;

  /** The counter's children, by type then outcome, resolved once. */
  private final Counter.Child[][] requests;

  private final Counter.Child replyBytes;

  /**
   * Creates the handler.
   *
   * @param config the {@code query} section, for the rate limits
   * @param status the snapshots to answer from
   * @param metrics the registry {@code warp_query_*} is registered in
   * @param nanoClock a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
   */
  public QueryHandler(
      QueryConfig config, QueryStatus status, Metrics metrics, LongSupplier nanoClock) {
    this(
        status,
        new ChallengeTable(),
        new QueryRateLimiter(
            QueryRateLimiter.ADDRESS_SLOTS,
            config.rateLimit().perAddress(),
            config.rateLimit().burst()),
        new QueryRateLimiter(1, config.rateLimit().global(), config.rateLimit().global()),
        metrics,
        nanoClock);
  }

  QueryHandler(
      QueryStatus status,
      ChallengeTable challenges,
      QueryRateLimiter perAddress,
      QueryRateLimiter global,
      Metrics metrics,
      LongSupplier nanoClock) {
    this.status = status;
    this.challenges = challenges;
    this.perAddress = perAddress;
    this.global = global;
    this.nanoClock = nanoClock;
    Counter counter =
        metrics.counter(
            "warp_query_requests_total",
            "GS4 query datagrams, by type and outcome.",
            "type",
            "outcome");
    this.requests = new Counter.Child[TYPES.length][OUTCOMES.length];
    for (int type = 0; type < TYPES.length; type++) {
      for (int outcome = 0; outcome < OUTCOMES.length; outcome++) {
        requests[type][outcome] = counter.labels(TYPES[type], OUTCOMES[outcome]);
      }
    }
    this.replyBytes =
        metrics.counter("warp_query_reply_bytes_total", "Bytes the query listener sent.").labels();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
    long now = nanoClock.getAsLong();
    if (!global.tryAcquire(0, now)) {
      requests[UNKNOWN][RATE_LIMITED].inc();
      return;
    }
    InetSocketAddress sender = packet.sender();
    InetAddress address = sender == null ? null : sender.getAddress();
    if (address == null) {
      requests[UNKNOWN][MALFORMED].inc();
      return;
    }
    byte[] addressBytes = address.getAddress();
    if (!perAddress.tryAcquire(AddressKey.of(addressBytes), now)) {
      requests[UNKNOWN][RATE_LIMITED].inc();
      return;
    }
    ByteBuf in = packet.content();
    int length = in.readableBytes();
    if (length < HANDSHAKE_LENGTH || in.getUnsignedShort(in.readerIndex()) != MAGIC) {
      requests[UNKNOWN][MALFORMED].inc();
      return;
    }
    byte type = in.getByte(in.readerIndex() + 2);
    int sessionId = in.getInt(in.readerIndex() + 3);
    long identity = ChallengeTable.identity(addressBytes, sender.getPort());
    switch (type) {
      case TYPE_HANDSHAKE -> {
        if (length != HANDSHAKE_LENGTH) {
          requests[HANDSHAKE][MALFORMED].inc();
          return;
        }
        int token = challenges.issue(identity, now);
        ByteBuf reply = ctx.alloc().ioBuffer(HANDSHAKE_REPLY_LENGTH);
        reply.writeByte(TYPE_HANDSHAKE).writeInt(sessionId);
        writeDecimal(reply, token);
        reply.writeByte(0);
        send(ctx, reply, sender, HANDSHAKE);
      }
      case TYPE_STAT -> {
        int kind;
        if (length == BASIC_STAT_LENGTH) {
          kind = BASIC;
        } else if (length == FULL_STAT_LENGTH) {
          kind = FULL;
        } else {
          requests[UNKNOWN][MALFORMED].inc();
          return;
        }
        int token = in.getInt(in.readerIndex() + HANDSHAKE_LENGTH);
        if (!challenges.matches(identity, token, now)) {
          requests[kind][NO_TOKEN].inc();
          return;
        }
        QuerySnapshot snapshot = status.current(now);
        if (snapshot == null) {
          requests[kind][IGNORED].inc();
          return;
        }
        byte[] body = kind == BASIC ? snapshot.basicBody() : snapshot.fullBody();
        ByteBuf reply = ctx.alloc().ioBuffer(QuerySnapshot.HEADER + body.length);
        reply.writeByte(TYPE_STAT).writeInt(sessionId).writeBytes(body);
        send(ctx, reply, sender, kind);
      }
      default -> requests[UNKNOWN][MALFORMED].inc();
    }
  }

  private void send(ChannelHandlerContext ctx, ByteBuf reply, InetSocketAddress to, int type) {
    replyBytes.add(reply.readableBytes());
    requests[type][ANSWERED].inc();
    ctx.writeAndFlush(new DatagramPacket(reply, to), ctx.voidPromise());
  }

  /** Writes a positive int as ASCII decimal digits, without a String. */
  static void writeDecimal(ByteBuf out, int value) {
    int digits = 1;
    for (int rest = value; rest >= 10; rest /= 10) {
      digits++;
    }
    int start = out.writerIndex();
    out.writerIndex(start + digits);
    int rest = value;
    for (int i = digits - 1; i >= 0; i--) {
      out.setByte(start + i, '0' + rest % 10);
      rest /= 10;
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    // A datagram that failed this way is lost, the listener is not: a read error on UDP is one
    // datagram, never the socket.
    requests[UNKNOWN][MALFORMED].inc();
    logger.debug("Dropped a query datagram", cause);
  }
}
