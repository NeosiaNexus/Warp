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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * Client reports what became of a resource pack ({@code C->S}, from 1.8, in the play state and,
 * from 1.20.2, the configuration state, where it has the same layout).
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.8-1.9.4</b>: String hash (of the pack it answers) + VarInt result
 *   <li><b>1.10-1.20.2</b>: VarInt result only: the answer is for the last pack sent
 *   <li><b>1.20.3+</b>: UUID + VarInt result
 * </ul>
 *
 * <p>Results: {@link #SUCCESSFULLY_LOADED}, {@link #DECLINED}, {@link #FAILED_DOWNLOAD} and {@link
 * #ACCEPTED} in every version; {@link #DOWNLOADED}, {@link #INVALID_URL}, {@link #FAILED_RELOAD}
 * and {@link #DISCARDED} from 1.20.3. The proxy decodes the answers, which come once per pack, to
 * keep those for its own packs and pass the others to the server that sent them.
 *
 * @param uuid the resource pack UUID (zero UUID before 1.20.3)
 * @param hash the resource pack hash (empty from 1.10)
 * @param result the result code
 */
public record ResourcePackResponse(UUID uuid, String hash, int result) implements PlayPacket {

  /** The pack is applied. Final. */
  public static final int SUCCESSFULLY_LOADED = 0;

  /** The player declined the pack. Final. */
  public static final int DECLINED = 1;

  /** The download failed. Final. */
  public static final int FAILED_DOWNLOAD = 2;

  /** The player accepted the pack; the download starts. */
  public static final int ACCEPTED = 3;

  /** The pack is downloaded (1.20.3+). */
  public static final int DOWNLOADED = 4;

  /** The URL is not valid (1.20.3+). Final. */
  public static final int INVALID_URL = 5;

  /** The pack could not be applied (1.20.3+). Final. */
  public static final int FAILED_RELOAD = 6;

  /** The pack was dropped before it was applied, replaced or removed (1.20.3+). Final. */
  public static final int DISCARDED = 7;

  /**
   * Returns whether a result ends what the client does with the pack: every result but {@link
   * #ACCEPTED} and {@link #DOWNLOADED}.
   *
   * @param result the result code
   * @return {@code true} if no other result follows it for that pack
   */
  public static boolean isFinal(int result) {
    return result != ACCEPTED && result != DOWNLOADED;
  }

  /** Codec for reading and writing resource pack response packets. */
  public static final PacketCodec<ResourcePackResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public ResourcePackResponse decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)
                  ? McUuid.read(buf)
                  : new UUID(0, 0);
          String hash = version.isAtMost(ProtocolVersion.MINECRAFT_1_9_4) ? McString.read(buf) : "";
          int result = VarInt.read(buf);
          return new ResourcePackResponse(uuid, hash, result);
        }

        @Override
        public void encode(ResourcePackResponse packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
            McUuid.write(buf, packet.uuid());
          }
          if (version.isAtMost(ProtocolVersion.MINECRAFT_1_9_4)) {
            McString.write(buf, packet.hash());
          }
          VarInt.write(buf, packet.result());
        }
      };
}
