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
import org.jspecify.annotations.Nullable;

/**
 * Client answers a {@link CookieRequest} ({@code C→S}), in the login, configuration and play states
 * from 1.20.5.
 *
 * <p>On the wire: the key, then a boolean telling whether a payload of at most {@value
 * CookiePayload#MAX_SIZE} bytes follows. Decoding refuses a larger payload before allocating it, as
 * a vanilla server does.
 *
 * @param key the key of the cookie, as the client writes it: always with its namespace
 * @param payload the cookie the client holds under {@code key}, or {@code null} if it holds none
 */
@SuppressWarnings("ArrayRecordComponent") // the payload is never mutated
public record CookieResponse(String key, byte @Nullable [] payload) implements CookiePacket {

  /** Codec for reading and writing cookie responses. */
  public static final PacketCodec<CookieResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public CookieResponse decode(ByteBuf buf, ProtocolVersion version) {
          String key = McIdentifier.read(buf);
          byte @Nullable [] payload = buf.readBoolean() ? CookiePayload.read(buf) : null;
          return new CookieResponse(key, payload);
        }

        @Override
        public void encode(CookieResponse packet, ByteBuf buf, ProtocolVersion version) {
          McIdentifier.write(buf, packet.key());
          byte @Nullable [] payload = packet.payload();
          buf.writeBoolean(payload != null);
          if (payload != null) {
            CookiePayload.write(buf, payload);
          }
        }
      };

  /**
   * Creates a response, refusing a key or a payload a vanilla server would not read.
   *
   * @param key the key of the cookie
   * @param payload the cookie, or {@code null} if the client holds none
   */
  public CookieResponse(String key, byte @Nullable [] payload) {
    this.key = McIdentifier.requireValid(key);
    this.payload = payload == null ? null : CookiePayload.requireValid(payload);
  }
}
