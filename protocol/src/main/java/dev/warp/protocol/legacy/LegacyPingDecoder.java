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
package dev.warp.protocol.legacy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;

/**
 * Recognizes, from the first bytes of a client connection, the legacy ping and login of Minecraft
 * 1.6 and older, and otherwise gets out of the way: it removes itself and {@link
 * LegacyDisconnectEncoder} from the pipeline, handing every byte it saw, untouched, to the frame
 * decoder.
 *
 * <p>A framed connection cannot be mistaken for a legacy one. Its first frame is a handshake of 6
 * to about 800 bytes, whose length prefix starts with {@code 0xFE} only for lengths 254, 382, 510
 * and so on: the next byte is then the rest of the length (1 for 254, 2 for 382), and for 254 the
 * frame goes on with the handshake's packet identifier, {@code 0x00}. So {@code 0xFE} alone, {@code
 * 0xFE 0x01} alone and {@code 0xFE 0x01 0xFA} are legacy pings, as vanilla servers read them, and
 * every other start is framed. A frame of 2 bytes cannot hold a handshake: {@code 0x02} followed by
 * more bytes is a legacy login.
 *
 * <p>TCP may split a ping, or the first frame of a modern client, after its first or second byte.
 * So {@code 0xFE} or {@code 0xFE 0x01} alone is answered as a ping only if nothing follows within
 * {@value #FRAGMENT_WAIT_MILLIS} ms, as Velocity's fix of the same ambiguity does: answered at
 * once, a 1.6 ping would get the older format, and a fragmented modern frame of 254 bytes (a long
 * Floodgate host name) a ping answer instead of a session.
 *
 * <p>Never throws on malformed input: a 1.6 ping whose plugin message does not parse is answered as
 * a ping without host name. It waits for more bytes only while a ping is incomplete, and never for
 * more than such a ping can hold. Once it has emitted a ping or a login it discards whatever the
 * client sends next: the proxy answers and closes.
 */
public final class LegacyPingDecoder extends ByteToMessageDecoder {

  /** How long a lone {@code 0xFE}, or {@code 0xFE 0x01}, waits for the bytes that may follow. */
  static final long FRAGMENT_WAIT_MILLIS = 100;

  private static final int PING = 0xFE;
  private static final int PING_PAYLOAD = 0x01;
  private static final int PLUGIN_MESSAGE = 0xFA;
  private static final int LOGIN = 0x02;

  /** The channel of the plugin message a 1.6 ping carries. */
  private static final String PING_CHANNEL = "MC|PingHost";

  /**
   * Bytes before the channel name: the ping, its payload byte, the message identifier, a length.
   */
  private static final int CHANNEL_OFFSET = 5;

  /** The longest channel name read; longer ones are not {@value #PING_CHANNEL}. */
  private static final int MAX_CHANNEL_CHARS = 64;

  /** The longest host data read: a protocol byte, a 255-character host name and a port. */
  private static final int MAX_HOST_DATA = 1 + 2 + 255 * 2 + 4;

  /** Whether a legacy packet was emitted: anything after it is discarded. */
  private boolean emitted;

  /**
   * Answers a ping of one or two bytes if nothing follows them in time; {@code null} if unarmed.
   */
  private @Nullable ScheduledFuture<?> fragmentWait;

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (emitted) {
      in.skipBytes(in.readableBytes());
      return;
    }
    int readable = in.readableBytes();
    if (readable == 0) {
      return;
    }
    int start = in.readerIndex();
    int first = in.getUnsignedByte(start);
    if (first == LOGIN) {
      if (readable > 1) {
        emit(in, out, new LegacyLogin());
      }
      return; // A lone 0x02 is no frame either: wait for the rest of the login.
    }
    if (first != PING) {
      framed(ctx);
      return;
    }
    if (readable == 1) {
      awaitFragment(ctx);
      return;
    }
    if (in.getUnsignedByte(start + 1) != PING_PAYLOAD) {
      framed(ctx);
      return;
    }
    if (readable == 2) {
      awaitFragment(ctx);
      return;
    }
    if (in.getUnsignedByte(start + 2) != PLUGIN_MESSAGE) {
      framed(ctx);
      return;
    }
    LegacyPing ping = readPingHost(in);
    if (ping != null) {
      emit(in, out, ping);
    }
  }

  /**
   * Reads the {@code MC|PingHost} message of a 1.6 ping.
   *
   * @return the ping, with its host name when the message parses; {@code null} while incomplete
   */
  private static @Nullable LegacyPing readPingHost(ByteBuf in) {
    int start = in.readerIndex();
    int readable = in.readableBytes();
    if (readable < CHANNEL_OFFSET) {
      return null;
    }
    int channelChars = in.getUnsignedShort(start + CHANNEL_OFFSET - 2);
    if (channelChars > MAX_CHANNEL_CHARS) {
      return new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, null, 0);
    }
    int dataLengthOffset = CHANNEL_OFFSET + channelChars * 2;
    if (readable < dataLengthOffset + 2) {
      return null;
    }
    int dataLength = in.getUnsignedShort(start + dataLengthOffset);
    if (dataLength > MAX_HOST_DATA) {
      return new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, null, 0);
    }
    int dataOffset = dataLengthOffset + 2;
    if (readable < dataOffset + dataLength) {
      return null;
    }
    String channel =
        in.toString(start + CHANNEL_OFFSET, channelChars * 2, StandardCharsets.UTF_16BE);
    // The data: the client's protocol (a byte), the host name (a short length, UTF-16BE), the port.
    int hostChars = dataLength >= 3 ? in.getUnsignedShort(start + dataOffset + 1) : -1;
    if (!channel.equals(PING_CHANNEL) || dataLength != 1 + 2 + hostChars * 2 + 4) {
      return new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, null, 0);
    }
    String host = in.toString(start + dataOffset + 3, hostChars * 2, StandardCharsets.UTF_16BE);
    int port = in.getInt(start + dataOffset + 3 + hostChars * 2);
    return new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, host, port);
  }

  private void emit(ByteBuf in, List<Object> out, Object packet) {
    in.skipBytes(in.readableBytes());
    emitted = true;
    cancelFragmentWait();
    out.add(packet);
  }

  /** Answers the ping of one or two bytes seen so far, unless more bytes come within the wait. */
  private void awaitFragment(ChannelHandlerContext ctx) {
    if (fragmentWait == null) {
      fragmentWait =
          ctx.executor()
              .schedule(() -> fragmentTimedOut(ctx), FRAGMENT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
    }
  }

  /** Nothing followed the first bytes: they are a ping of Minecraft 1.3, or of 1.4 and 1.5. */
  private void fragmentTimedOut(ChannelHandlerContext ctx) {
    fragmentWait = null;
    if (emitted || ctx.isRemoved()) {
      return;
    }
    ByteBuf in = internalBuffer();
    int readable = in.readableBytes();
    LegacyPing ping;
    if (readable == 1) {
      ping = new LegacyPing(LegacyPing.Format.MINECRAFT_1_3, null, 0);
    } else if (readable == 2) {
      ping = new LegacyPing(LegacyPing.Format.MINECRAFT_1_4, null, 0);
    } else {
      return; // Decided by decode() when the bytes came.
    }
    in.skipBytes(readable);
    emitted = true;
    ctx.fireChannelRead(ping);
    ctx.fireChannelReadComplete();
  }

  private void cancelFragmentWait() {
    ScheduledFuture<?> wait = fragmentWait;
    if (wait != null) {
      fragmentWait = null;
      wait.cancel(false);
    }
  }

  @Override
  protected void handlerRemoved0(ChannelHandlerContext ctx) {
    cancelFragmentWait();
  }

  /**
   * Leaves the connection to the framed protocol: the bytes seen so far go on to the next handler.
   */
  private static void framed(ChannelHandlerContext ctx) {
    ChannelPipeline pipeline = ctx.pipeline();
    if (pipeline.context(LegacyDisconnectEncoder.INSTANCE) != null) {
      pipeline.remove(LegacyDisconnectEncoder.INSTANCE);
    }
    pipeline.remove(ctx.handler());
  }
}
