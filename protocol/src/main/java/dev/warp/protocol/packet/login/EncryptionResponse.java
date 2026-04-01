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
package dev.warp.protocol.packet.login;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McByteArray;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Client responds with encrypted shared secret and verify token ({@code C→S, ID 0x01}).
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.7.2–1.18.2, 1.19.3+</b>: sharedSecret + verifyToken (both VarInt-prefixed byte arrays)
 *   <li><b>1.19–1.19.2</b>: sharedSecret + boolean prefix — if {@code true}, verifyToken (normal
 *       path); if {@code false}, salt (long) + message signature (alt crypto path, proxy stores
 *       empty verifyToken)
 * </ul>
 *
 * @param sharedSecret the RSA-encrypted shared secret (128 bytes encrypted)
 * @param verifyToken the RSA-encrypted verify token (empty if 1.19–1.19.2 alt crypto was used)
 */
public record EncryptionResponse(byte[] sharedSecret, byte[] verifyToken) implements LoginPacket {

  /** Empty byte array reused for the alt crypto path. */
  private static final byte[] EMPTY = new byte[0];

  /** Codec for reading and writing encryption response packets. */
  public static final PacketCodec<EncryptionResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public EncryptionResponse decode(ByteBuf buf, ProtocolVersion version) {
          byte[] sharedSecret;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            sharedSecret = McByteArray.read(buf, 256);
          } else {
            sharedSecret = McByteArray.readShortPrefixed(buf, 256);
          }

          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)
              && version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_3)) {
            // 1.19–1.19.2: boolean prefix selects normal vs alt crypto
            if (buf.readBoolean()) {
              // Normal path: encrypted verify token
              byte[] verifyToken = McByteArray.read(buf, 256);
              return new EncryptionResponse(sharedSecret, verifyToken);
            } else {
              // Alt crypto path: salt (long) + message signature — skip and store empty
              buf.skipBytes(Long.BYTES); // salt
              McByteArray.skip(buf); // message signature
              return new EncryptionResponse(sharedSecret, EMPTY);
            }
          }

          // 1.8+: VarInt-prefixed byte arrays
          // 1.7.x: unsigned-short-prefixed byte arrays
          byte[] verifyToken;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            verifyToken = McByteArray.read(buf, 256);
          } else {
            verifyToken = McByteArray.readShortPrefixed(buf, 256);
          }
          return new EncryptionResponse(sharedSecret, verifyToken);
        }

        @Override
        public void encode(EncryptionResponse packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            McByteArray.write(buf, packet.sharedSecret());

            if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)
                && version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_3)) {
              // 1.19–1.19.2: always encode as normal path (boolean true + verifyToken)
              buf.writeBoolean(true);
            }

            McByteArray.write(buf, packet.verifyToken());
          } else {
            McByteArray.writeShortPrefixed(buf, packet.sharedSecret());
            McByteArray.writeShortPrefixed(buf, packet.verifyToken());
          }
        }
      };
}
