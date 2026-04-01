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
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.packet.PacketCodec;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Client begins the login process ({@code C→S, ID 0x00}).
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.7.2–1.19</b>: Only {@code name}
 *   <li><b>1.19.1–1.19.2</b>: Added optional UUID (boolean-prefixed)
 *   <li><b>1.19.3+</b>: UUID is always present (no boolean prefix)
 * </ul>
 *
 * <p>The 1.19–1.19.2 signature fields (public key, profile signature) are skipped during decode —
 * they were a short-lived experiment removed in 1.19.3 and are irrelevant for proxy forwarding.
 *
 * @param name the player's username (max 16 characters)
 * @param playerUuid the player's UUID, or {@code null} for versions before 1.19.1
 */
public record LoginStart(String name, @Nullable UUID playerUuid) implements LoginPacket {

  /** Maximum username length per protocol spec. */
  private static final int MAX_USERNAME = 16;

  /** Codec for reading and writing login start packets. */
  public static final PacketCodec<LoginStart> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginStart decode(ByteBuf buf, ProtocolVersion version) {
          String name = McString.read(buf, MAX_USERNAME);
          @Nullable UUID uuid = null;

          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
            // 1.20.2+: UUID always present, no boolean prefix
            uuid = McUuid.read(buf);
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
            // 1.19.1–1.20.1: skip signature fields (1.19.1–1.19.2 only), optional UUID
            if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_3)) {
              skipSignatureFields(buf);
            }
            if (buf.readBoolean()) {
              uuid = McUuid.read(buf);
            }
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
            // 1.19: has signature fields but no UUID
            skipSignatureFields(buf);
          }
          // 1.7.2–1.18.2: just the name, nothing else

          return new LoginStart(name, uuid);
        }

        @Override
        public void encode(LoginStart packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.name(), MAX_USERNAME);

          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
            if (packet.playerUuid() == null) {
              throw new IllegalStateException("playerUuid is required for 1.20.2+");
            }
            McUuid.write(buf, packet.playerUuid());
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
            // 1.19.1–1.20.1: no signature data on encode, optional UUID
            buf.writeBoolean(packet.playerUuid() != null);
            if (packet.playerUuid() != null) {
              McUuid.write(buf, packet.playerUuid());
            }
          }
          // 1.7.2–1.19: just the name
        }

        private void skipSignatureFields(ByteBuf buf) {
          if (buf.readBoolean()) { // has signature data
            buf.skipBytes(Long.BYTES); // timestamp
            McByteArray.skip(buf); // public key
            McByteArray.skip(buf); // signature
          }
        }
      };
}
