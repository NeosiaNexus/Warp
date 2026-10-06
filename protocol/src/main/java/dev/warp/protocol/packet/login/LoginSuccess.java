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
import java.util.HexFormat;
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
 *   <li><b>1.7.2–1.7.5</b>: UUID as a 32-character hex string without dashes + username
 *   <li><b>1.7.6–1.15.2</b>: UUID as a 36-character string with dashes + username
 *   <li><b>1.16+</b>: UUID as a 128-bit binary value + username (1.16–1.18.2 describe it as four
 *       ints, which puts the same bytes on the wire as two big-endian longs)
 *   <li><b>1.19+</b>: Added properties array (skin/cape)
 *   <li><b>1.20.5–1.21.1</b>: Added {@code strictErrorHandling} boolean, removed again in 1.21.2
 *   <li><b>26.2+</b>: Added the play session ID (a UUID) after the properties
 * </ul>
 *
 * <p>Encoding always uses the exact form of the target version. Decoding a string UUID accepts both
 * the dashed and the undashed form, whatever the version, and rejects anything else with a {@link
 * DecoderException}.
 *
 * @param uuid the player's UUID
 * @param username the player's username
 * @param properties game profile properties (skin, cape), empty before 1.19
 * @param strictErrorHandling if {@code true}, client disconnects on decode errors (1.20.5–1.21.1)
 * @param sessionId the server's play session ID, which the client reports in its telemetry (26.2+):
 *     vanilla shares one among every player connected at the same time. {@code null} when decoded
 *     before 26.2, required to encode for 26.2+
 */
public record LoginSuccess(
    UUID uuid,
    String username,
    List<Property> properties,
    boolean strictErrorHandling,
    @Nullable UUID sessionId)
    implements LoginPacket {

  /** Most profile properties accepted: vanilla sends a handful (textures). */
  private static final int MAX_PROPERTIES = 64;

  /** Length of a UUID string without dashes, as sent by 1.7.2–1.7.5. */
  private static final int UNDASHED_UUID_LENGTH = 32;

  /** Length of a UUID string with dashes, as sent by 1.7.6–1.15.2. */
  private static final int DASHED_UUID_LENGTH = 36;

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
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16)) {
            uuid = McUuid.read(buf);
          } else {
            uuid = parseUuidString(McString.read(buf, DASHED_UUID_LENGTH));
          }

          String username = McString.read(buf, 16);

          List<Property> properties;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
            // A property takes at least three bytes: two empty strings and its signature flag.
            int count = VarInt.readCount(buf, MAX_PROPERTIES, 3, "property");
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
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)
                  && version.isOlderThan(ProtocolVersion.MINECRAFT_1_21_2)
                  && buf.readBoolean();

          @Nullable UUID sessionId =
              version.isAtLeast(ProtocolVersion.MINECRAFT_26_2) ? McUuid.read(buf) : null;

          return new LoginSuccess(uuid, username, properties, strictErrorHandling, sessionId);
        }

        @Override
        public void encode(LoginSuccess packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16)) {
            McUuid.write(buf, packet.uuid());
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_7_6)) {
            McString.write(buf, packet.uuid().toString(), DASHED_UUID_LENGTH);
          } else {
            McString.write(buf, toUndashedString(packet.uuid()), UNDASHED_UUID_LENGTH);
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

          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)
              && version.isOlderThan(ProtocolVersion.MINECRAFT_1_21_2)) {
            buf.writeBoolean(packet.strictErrorHandling());
          }

          if (version.isAtLeast(ProtocolVersion.MINECRAFT_26_2)) {
            @Nullable UUID sessionId = packet.sessionId();
            if (sessionId == null) {
              throw new IllegalStateException("sessionId is required for 26.2+");
            }
            McUuid.write(buf, sessionId);
          }
        }
      };

  // ---------------------------------------------------------------------------
  // String UUID helpers (1.7.2–1.15.2)
  // ---------------------------------------------------------------------------

  /**
   * Parses a UUID sent as a string, in either the undashed ({@code 32} hex digits) or the dashed
   * ({@code 8-4-4-4-12}) form.
   *
   * @throws DecoderException if the string is neither form
   */
  private static UUID parseUuidString(String str) {
    try {
      if (str.length() == UNDASHED_UUID_LENGTH) {
        return new UUID(
            HexFormat.fromHexDigitsToLong(str, 0, 16), HexFormat.fromHexDigitsToLong(str, 16, 32));
      }
      if (str.length() == DASHED_UUID_LENGTH
          && str.charAt(8) == '-'
          && str.charAt(13) == '-'
          && str.charAt(18) == '-'
          && str.charAt(23) == '-') {
        long msb =
            (HexFormat.fromHexDigitsToLong(str, 0, 8) << 32)
                | (HexFormat.fromHexDigitsToLong(str, 9, 13) << 16)
                | HexFormat.fromHexDigitsToLong(str, 14, 18);
        long lsb =
            (HexFormat.fromHexDigitsToLong(str, 19, 23) << 48)
                | HexFormat.fromHexDigitsToLong(str, 24, 36);
        return new UUID(msb, lsb);
      }
    } catch (IllegalArgumentException e) {
      throw new DecoderException("Malformed UUID string: " + str, e);
    }
    throw new DecoderException("Malformed UUID string: " + str);
  }

  /** Formats a UUID as 32 lowercase hex digits without dashes. */
  private static String toUndashedString(UUID uuid) {
    HexFormat hex = HexFormat.of();
    return hex.toHexDigits(uuid.getMostSignificantBits())
        + hex.toHexDigits(uuid.getLeastSignificantBits());
  }
}
