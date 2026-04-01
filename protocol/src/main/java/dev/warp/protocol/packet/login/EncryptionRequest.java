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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server requests encryption setup ({@code S→C, ID 0x01}).
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.7.2–1.20.4</b>: serverId, publicKey, verifyToken
 *   <li><b>1.20.5+</b>: Added {@code shouldAuthenticate} boolean
 * </ul>
 *
 * @param serverId the server ID string (empty for online-mode servers since 1.7)
 * @param publicKey the server's DER-encoded RSA public key
 * @param verifyToken a random 4-byte verification token
 * @param shouldAuthenticate whether the client should authenticate with Mojang (1.20.5+, defaults
 *     to {@code true} for older versions)
 */
public record EncryptionRequest(
    String serverId, byte[] publicKey, byte[] verifyToken, boolean shouldAuthenticate)
    implements LoginPacket {

  /** Codec for reading and writing encryption request packets. */
  public static final PacketCodec<EncryptionRequest> CODEC =
      new PacketCodec<>() {
        @Override
        public EncryptionRequest decode(ByteBuf buf, ProtocolVersion version) {
          String serverId = McString.read(buf, 20);
          byte[] publicKey;
          byte[] verifyToken;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            publicKey = McByteArray.read(buf, 512);
            verifyToken = McByteArray.read(buf, 256);
          } else {
            publicKey = McByteArray.readShortPrefixed(buf, 512);
            verifyToken = McByteArray.readShortPrefixed(buf, 256);
          }
          boolean shouldAuthenticate =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5) ? buf.readBoolean() : true;
          return new EncryptionRequest(serverId, publicKey, verifyToken, shouldAuthenticate);
        }

        @Override
        public void encode(EncryptionRequest packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.serverId(), 20);
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            McByteArray.write(buf, packet.publicKey());
            McByteArray.write(buf, packet.verifyToken());
          } else {
            McByteArray.writeShortPrefixed(buf, packet.publicKey());
            McByteArray.writeShortPrefixed(buf, packet.verifyToken());
          }
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
            buf.writeBoolean(packet.shouldAuthenticate());
          }
        }
      };
}
