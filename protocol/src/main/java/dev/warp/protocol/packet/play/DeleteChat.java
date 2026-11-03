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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McByteArray;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Server deletes a signed chat message from the client's chat ({@code S→C}, from 1.19.1): the
 * Delete Chat packet, which names the message by its signature. The client hides the message it
 * holds with that signature, and ignores one it does not hold.
 *
 * <p>Layout (minecraft-data's {@code packet_hide_message}): the signature as a VarInt-prefixed byte
 * array in 1.19.1 and 1.19.2; from 1.19.3 a packed signature, {@code 0} (not an index into the
 * client's signature cache) then the 256 bytes of the signature.
 *
 * @param signature the message's signature, 256 bytes
 */
@SuppressWarnings("ArrayRecordComponent") // never mutated
public record DeleteChat(byte[] signature) implements PlayPacket {

  /** The size of a message signature: an RSA 2048 signature. */
  public static final int SIGNATURE_BYTES = 256;

  /**
   * Creates the packet.
   *
   * @param signature the message's signature
   * @throws IllegalArgumentException if the signature is not 256 bytes, the size of every message
   *     signature: no message has it
   */
  public DeleteChat {
    if (signature.length != SIGNATURE_BYTES) {
      throw new IllegalArgumentException(
          "A message signature has 256 bytes, not " + signature.length);
    }
  }

  /** Codec for reading and writing chat deletions. */
  public static final PacketCodec<DeleteChat> CODEC =
      new PacketCodec<>() {
        @Override
        public DeleteChat decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_19_3)) {
            return new DeleteChat(McByteArray.read(buf, SIGNATURE_BYTES));
          }
          int id = VarInt.read(buf);
          if (id != 0) {
            throw new DecoderException("Cached signature " + (id - 1) + " instead of a signature");
          }
          byte[] signature = new byte[SIGNATURE_BYTES];
          buf.readBytes(signature);
          return new DeleteChat(signature);
        }

        @Override
        public void encode(DeleteChat packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_19_3)) {
            McByteArray.write(buf, packet.signature());
          } else {
            VarInt.write(buf, 0); // the full signature follows
            buf.writeBytes(packet.signature());
          }
        }
      };
}
