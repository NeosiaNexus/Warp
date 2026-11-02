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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.codec.VarInt;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * The plugin message channels whose clientbound messages the proxy reads ({@link
 * PlayPluginMessage#WATCH}): those plugins own, and those the proxy speaks itself.
 *
 * <p>A message's channel is matched against the set in place, byte for byte, so that a message on
 * any other channel costs no allocation at all: not even its channel's name is decoded. Channel
 * names are UTF-8 on the wire, and two names are equal exactly when their bytes are.
 *
 * <p>Thread-safe and lock-free: changes copy the array of names (they come from plugin
 * registrations, rare), and readers only load it.
 */
public final class WatchedChannels {

  /**
   * A watched channel.
   *
   * @param name the channel's name, the instance a match returns
   * @param utf8 the name as it appears on the wire
   */
  @SuppressWarnings("ArrayRecordComponent") // never exposed, never modified
  private record Entry(String name, byte[] utf8) {}

  private static final Entry[] NONE = new Entry[0];

  private final AtomicReference<Entry[]> entries = new AtomicReference<>(NONE);

  /** Creates an empty set. */
  public WatchedChannels() {}

  /**
   * Watches a channel.
   *
   * @param channel the channel's wire name
   * @return {@code true} if it was not watched yet
   */
  public boolean add(String channel) {
    Entry entry = new Entry(channel, channel.getBytes(StandardCharsets.UTF_8));
    while (true) {
      Entry[] current = snapshot();
      if (indexOf(current, channel) >= 0) {
        return false;
      }
      Entry[] next = Arrays.copyOf(current, current.length + 1);
      next[current.length] = entry;
      if (entries.compareAndSet(current, next)) {
        return true;
      }
    }
  }

  /**
   * Stops watching a channel.
   *
   * @param channel the channel's wire name
   * @return {@code true} if it was watched
   */
  public boolean remove(String channel) {
    while (true) {
      Entry[] current = snapshot();
      int index = indexOf(current, channel);
      if (index < 0) {
        return false;
      }
      Entry[] next = new Entry[current.length - 1];
      System.arraycopy(current, 0, next, 0, index);
      System.arraycopy(current, index + 1, next, index, next.length - index);
      if (entries.compareAndSet(current, next)) {
        return true;
      }
    }
  }

  /**
   * Returns whether a channel is watched.
   *
   * @param channel the channel's wire name
   * @return {@code true} if watched
   */
  public boolean contains(String channel) {
    return indexOf(snapshot(), channel) >= 0;
  }

  /**
   * Returns whether no channel is watched.
   *
   * @return {@code true} if none is
   */
  public boolean isEmpty() {
    return snapshot().length == 0;
  }

  /**
   * Reads the channel a plugin message starts with, if it is watched. Allocates nothing.
   *
   * @param buf the message, positioned at its channel
   * @return the watched channel's name, the buffer then positioned just after it; or {@code null}
   *     if the channel is not watched or its length is malformed, the buffer's position then
   *     unspecified
   * @throws io.netty.handler.codec.DecoderException if the channel's length is not a valid VarInt
   */
  public @Nullable String read(ByteBuf buf) {
    Entry[] current = snapshot();
    if (current.length == 0) {
      return null;
    }
    int length = VarInt.read(buf);
    if (length <= 0 || !buf.isReadable(length)) {
      return null;
    }
    int start = buf.readerIndex();
    for (Entry entry : current) {
      if (matches(entry.utf8(), buf, start, length)) {
        buf.skipBytes(length);
        return entry.name();
      }
    }
    return null;
  }

  private static boolean matches(byte[] name, ByteBuf buf, int start, int length) {
    if (name.length != length) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (buf.getByte(start + i) != name[i]) {
        return false;
      }
    }
    return true;
  }

  /** The watched channels right now; the reference is never null. */
  private Entry[] snapshot() {
    return Objects.requireNonNull(entries.get());
  }

  private static int indexOf(Entry[] entries, String channel) {
    for (int i = 0; i < entries.length; i++) {
      if (entries[i].name().equals(channel)) {
        return i;
      }
    }
    return -1;
  }
}
