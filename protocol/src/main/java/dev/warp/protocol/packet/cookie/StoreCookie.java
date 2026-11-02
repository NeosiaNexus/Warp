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
package dev.warp.protocol.packet.cookie;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McIdentifier;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server stores a cookie on the client ({@code S→C}), in the configuration and play states from
 * 1.20.5.
 *
 * <p>The client keeps it for the server that stored it, replacing any cookie under the same key,
 * and carries it over when a Transfer sends it to another server.
 *
 * @param key the cookie's identifier, exactly as the server wrote it
 * @param payload the cookie, at most {@value CookiePayload#MAX_SIZE} bytes
 */
@SuppressWarnings("ArrayRecordComponent") // the payload is never mutated
public record StoreCookie(String key, byte[] payload) implements CookiePacket {

  /** Codec for reading and writing store cookie packets. */
  public static final PacketCodec<StoreCookie> CODEC =
      new PacketCodec<>() {
        @Override
        public StoreCookie decode(ByteBuf buf, ProtocolVersion version) {
          String key = McIdentifier.read(buf);
          return new StoreCookie(key, CookiePayload.read(buf));
        }

        @Override
        public void encode(StoreCookie packet, ByteBuf buf, ProtocolVersion version) {
          McIdentifier.write(buf, packet.key());
          CookiePayload.write(buf, packet.payload());
        }
      };

  /** Refuses a key or a payload a vanilla client would not read. */
  public StoreCookie {
    McIdentifier.requireValid(key);
    CookiePayload.requireValid(payload);
  }
}
