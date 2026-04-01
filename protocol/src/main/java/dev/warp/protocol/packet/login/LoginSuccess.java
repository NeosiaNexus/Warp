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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;

/**
 * Server confirms successful login ({@code S→C, ID 0x02}).
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.7.x</b>: UUID as string + username
 *   <li><b>1.8–1.18.2</b>: UUID as 128-bit value + username
 *   <li><b>1.19+</b>: Added properties array (skin/cape)
 *   <li><b>1.20.5+</b>: Added {@code strictErrorHandling} boolean
 * </ul>
 *
 * @param uuid the player's UUID
 * @param username the player's username
 * @param properties game profile properties (skin, cape), empty before 1.19
 * @param strictErrorHandling if {@code true}, client disconnects on decode errors (1.20.5+)
 */
public record LoginSuccess(
    UUID uuid, String username, List<Property> properties, boolean strictErrorHandling)
    implements LoginPacket {

  /**
   * A game profile property (typically skin/cape textures).
   *
   * @param name the property name
   * @param value the base64-encoded property value
   * @param signature the optional base64-encoded Mojang signature
   */
  public record Property(String name, String value, @Nullable String signature) {}

  /** Codec for reading and writing login success packets. */
  public static final PacketCodec<LoginSuccess> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginSuccess decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid;
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            // 1.7.x: UUID as string (with or without dashes)
            String uuidStr = McString.read(buf, 36);
            if (!uuidStr.contains("-")) {
              // Insert dashes: 8-4-4-4-12
              uuidStr =
                  uuidStr.substring(0, 8)
                      + "-"
                      + uuidStr.substring(8, 12)
                      + "-"
                      + uuidStr.substring(12, 16)
                      + "-"
                      + uuidStr.substring(16, 20)
                      + "-"
                      + uuidStr.substring(20);
            }
            uuid = UUID.fromString(uuidStr);
          } else {
            uuid = McUuid.read(buf);
          }

          String username = McString.read(buf, 16);

          List<Property> properties;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
            int count = VarInt.read(buf);
            if (count > 64) {
              throw new DecoderException("Too many properties: " + count + " (max 64)");
            }
            properties = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
              String name = McString.read(buf);
              String value = McString.read(buf);
              @Nullable String signature = buf.readBoolean() ? McString.read(buf) : null;
              properties.add(new Property(name, value, signature));
            }
            properties = List.copyOf(properties);
          } else {
            properties = List.of();
          }

          boolean strictErrorHandling =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5) && buf.readBoolean();

          return new LoginSuccess(uuid, username, properties, strictErrorHandling);
        }

        @Override
        public void encode(LoginSuccess packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            McString.write(buf, packet.uuid().toString(), 36);
          } else {
            McUuid.write(buf, packet.uuid());
          }

          McString.write(buf, packet.username(), 16);

          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
            VarInt.write(buf, packet.properties().size());
            for (Property prop : packet.properties()) {
              McString.write(buf, prop.name());
              McString.write(buf, prop.value());
              buf.writeBoolean(prop.signature() != null);
              if (prop.signature() != null) {
                McString.write(buf, prop.signature());
              }
            }
          }

          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
            buf.writeBoolean(packet.strictErrorHandling());
          }
        }
      };
}
