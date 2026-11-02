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
 * Server asks the client for the cookie stored under a key ({@code S→C}), in the login,
 * configuration and play states from 1.20.5.
 *
 * <p>The client answers every request with a {@link CookieResponse} for the same key, in the order
 * the requests arrived, whether or not it holds that cookie.
 *
 * @param key the cookie's identifier, exactly as the server wrote it: it may omit the {@code
 *     minecraft} namespace (see {@link McIdentifier#canonical})
 */
public record CookieRequest(String key) implements CookiePacket {

  /** Codec for reading and writing cookie requests. */
  public static final PacketCodec<CookieRequest> CODEC =
      new PacketCodec<>() {
        @Override
        public CookieRequest decode(ByteBuf buf, ProtocolVersion version) {
          return new CookieRequest(McIdentifier.read(buf));
        }

        @Override
        public void encode(CookieRequest packet, ByteBuf buf, ProtocolVersion version) {
          McIdentifier.write(buf, packet.key());
        }
      };

  /** Refuses a key a vanilla client would not read. */
  public CookieRequest {
    McIdentifier.requireValid(key);
  }
}
