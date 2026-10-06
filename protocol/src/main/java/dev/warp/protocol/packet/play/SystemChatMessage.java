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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Server sends a system message to the client ({@code S→C}): shown in the chat box, or above the
 * hotbar (the action bar) when {@code overlay} is set.
 *
 * <p>Minecraft carried these in its single Chat Message packet until 1.18.2 and gave them their own
 * System Chat Message packet in 1.19. This record covers both, so the proxy sends a message the
 * same way to every version. The text component (a VarInt-prefixed JSON string before 1.20.3, NBT
 * from 1.20.3) is followed by:
 *
 * <ul>
 *   <li><b>1.7.2–1.7.10</b> (Chat Message): nothing. There is no action bar, so an overlay message
 *       lands in the chat box.
 *   <li><b>1.8–1.15.2</b> (Chat Message): a position byte, {@code 1} for the chat box (system) or
 *       {@code 2} for the action bar (game info).
 *   <li><b>1.16–1.18.2</b> (Chat Message): the position byte, then the sender UUID. The proxy
 *       writes the nil UUID: no player sent the message, so the client never hides it.
 *   <li><b>1.19</b> (System Chat Message): a VarInt chat type, {@code 1} (system) or {@code 2}
 *       (game info).
 *   <li><b>1.19.1+</b> (System Chat Message): the overlay boolean.
 * </ul>
 *
 * <p>The proxy only sends this packet (it is registered encode-only), so player chat, the third use
 * of the old Chat Message packet, never reaches this record. Its decoder reads every position or
 * chat type other than game info as a chat-box message.
 *
 * @param rawContent the text component, encoded for the client's version (see {@link
 *     dev.warp.protocol.packet.TextComponent TextComponent})
 * @param overlay if {@code true}, the message is displayed as an action bar overlay
 */
public record SystemChatMessage(byte[] rawContent, boolean overlay) implements PlayPacket {

  /** Chat position (until 1.18.2) and chat type (1.19) of a message shown in the chat box. */
  private static final int SYSTEM = 1;

  /** Chat position (until 1.18.2) and chat type (1.19) of a message shown in the action bar. */
  private static final int GAME_INFO = 2;

  /** Codec for reading and writing system chat message packets. */
  public static final PacketCodec<SystemChatMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public SystemChatMessage decode(ByteBuf buf, ProtocolVersion version) {
          byte[] rawContent = readContent(buf, version);
          boolean overlay;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
            overlay = buf.readBoolean();
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
            overlay = VarInt.read(buf) == GAME_INFO;
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            overlay = buf.readByte() == GAME_INFO;
            if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16)) {
              buf.skipBytes(McUuid.ENCODED_SIZE); // sender
            }
          } else {
            overlay = false;
          }
          return new SystemChatMessage(rawContent, overlay);
        }

        @Override
        public void encode(SystemChatMessage packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawContent());
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
            buf.writeBoolean(packet.overlay());
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
            VarInt.write(buf, packet.overlay() ? GAME_INFO : SYSTEM);
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            buf.writeByte(packet.overlay() ? GAME_INFO : SYSTEM);
            if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16)) {
              buf.writeZero(McUuid.ENCODED_SIZE); // nil sender
            }
          }
        }
      };

  /**
   * Reads the text component as it is on the wire: a self-delimiting VarInt-prefixed JSON string
   * before 1.20.3, or NBT running up to the overlay boolean from 1.20.3.
   */
  private static byte[] readContent(ByteBuf buf, ProtocolVersion version) {
    int start = buf.readerIndex();
    int length;
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_3)) {
      McString.skip(buf);
      length = buf.readerIndex() - start;
      buf.readerIndex(start);
    } else {
      length = buf.readableBytes() - 1;
      if (length < 0) {
        throw new DecoderException("SystemChatMessage too short: need at least 1 byte for overlay");
      }
    }
    byte[] rawContent = new byte[length];
    buf.readBytes(rawContent);
    return rawContent;
  }
}
