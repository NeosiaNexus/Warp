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
 *   <li><b>1.7.2–1.18.2</b>: {@code name}
 *   <li><b>1.19</b>: {@code name}, signature data
 *   <li><b>1.19.1–1.19.2</b>: {@code name}, signature data, optional UUID
 *   <li><b>1.19.3–1.20.1</b>: {@code name}, optional UUID
 *   <li><b>1.20.2+</b>: {@code name}, UUID (always present)
 * </ul>
 *
 * <p>Signature data is a boolean followed, when {@code true}, by the player's chat signing key
 * (expiry timestamp, public key, Mojang signature). An optional UUID is a boolean followed, when
 * {@code true}, by the UUID.
 *
 * <p>The signing key is skipped when decoding and always encoded as absent: Warp does not forward
 * it, and the offline-mode backends it logs players into do not require one.
 *
 * @param name the player's username (max 16 characters)
 * @param playerUuid the player's UUID, or {@code null} when unknown (always {@code null} when
 *     decoded before 1.19.1, required to encode for 1.20.2+)
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
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
            return new LoginStart(name, McUuid.read(buf));
          }
          if (hasSignatureData(version) && buf.readBoolean()) {
            buf.skipBytes(Long.BYTES); // key expiry timestamp
            McByteArray.skip(buf); // public key
            McByteArray.skip(buf); // signature
          }
          @Nullable UUID uuid =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1) && buf.readBoolean()
                  ? McUuid.read(buf)
                  : null;
          return new LoginStart(name, uuid);
        }

        @Override
        public void encode(LoginStart packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.name(), MAX_USERNAME);
          @Nullable UUID uuid = packet.playerUuid();
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
            if (uuid == null) {
              throw new IllegalStateException("playerUuid is required for 1.20.2+");
            }
            McUuid.write(buf, uuid);
            return;
          }
          if (hasSignatureData(version)) {
            buf.writeBoolean(false); // no signing key
          }
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
            buf.writeBoolean(uuid != null);
            if (uuid != null) {
              McUuid.write(buf, uuid);
            }
          }
        }
      };

  /** Whether {@code version} carries signature data after the name (1.19 to 1.19.2). */
  private static boolean hasSignatureData(ProtocolVersion version) {
    return version.isBetween(ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2);
  }
}
