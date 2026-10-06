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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Client sends a chat command ({@code C→S}).
 *
 * <p>Split from the chat message packet in 1.19 (before, commands are chat lines starting with
 * {@code /}, see {@link LegacyChatMessage}). The proxy intercepts this for command routing between
 * backend servers.
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.19-1.19.2</b>: command (256 characters at most), then its signing fields: timestamp,
 *       salt and argument signatures, a signed preview flag, and from 1.19.1 the messages the
 *       client last saw, in full
 *   <li><b>1.19.3-1.20.4</b>: command (256 characters at most), timestamp, salt, argument
 *       signatures (8 at most, each a name and a 256-byte signature), then a last-seen update: a
 *       VarInt offset and which of the last 20 messages the client acknowledges, 3 bytes
 *   <li><b>1.20.5+</b>: command only (32767 characters at most). 1.20.5 moved commands with signed
 *       arguments to a separate packet, which the proxy forwards untouched
 * </ul>
 *
 * <p>The command string does not include the leading {@code /}. The signing fields are kept as raw
 * bytes and written back as they came, since the proxy does not validate signatures. From 1.19.3 to
 * 1.20.4 the decoder also reads the last-seen offset out of them ({@link #lastSeenOffset}): a
 * server applies it whether or not it runs the command, so a command the proxy keeps from the
 * backend must still pass it on, as a {@link ChatAcknowledgement}.
 *
 * <p>Sources: vanilla {@code ServerboundChatCommandPacket}, {@code ArgumentSignatures} and {@code
 * LastSeenMessages.Update}; Velocity's {@code SessionPlayerCommandPacket}; minecraft-data's {@code
 * chat_command}.
 *
 * @param command the command string (without leading slash)
 * @param rawSignatureData the bytes after the command (signing fields before 1.20.5, else empty)
 * @param lastSeenOffset how many chat messages the client saw since its previous last-seen update,
 *     read from {@code rawSignatureData} from 1.19.3 to 1.20.4, else 0; never written on its own
 */
public record ChatCommand(String command, byte[] rawSignatureData, int lastSeenOffset)
    implements PlayPacket {

  /** Maximum command length before 1.20.5, which raised it to the protocol's string maximum. */
  private static final int MAX_COMMAND_LENGTH_BEFORE_1_20_5 = 256;

  /** Timestamp and salt, two longs, before the argument signatures. */
  private static final int TIMESTAMP_AND_SALT_BYTES = 2 * Long.BYTES;

  /** Most argument signatures a command carries ({@code ArgumentSignatures.MAX_ARGUMENT_COUNT}). */
  private static final int MAX_ARGUMENT_SIGNATURES = 8;

  /** An RSA signature from 1.19.3, fixed size ({@code MessageSignature.BYTES}). */
  private static final int SIGNATURE_BYTES = 256;

  /** The acknowledged messages of a last-seen update: a bit set over the last 20, 3 bytes. */
  private static final int ACKNOWLEDGED_BYTES = 3;

  /** Rejects a negative offset, which a server refuses (and kicks the player for). */
  public ChatCommand {
    if (lastSeenOffset < 0) {
      throw new IllegalArgumentException("Negative last-seen offset: " + lastSeenOffset);
    }
  }

  /** Codec for reading and writing chat command packets. */
  public static final PacketCodec<ChatCommand> CODEC =
      new PacketCodec<>() {
        @Override
        public ChatCommand decode(ByteBuf buf, ProtocolVersion version) {
          String command = McString.read(buf, maxCommandLength(version));
          int lastSeenOffset = hasLastSeenOffset(version) ? peekLastSeenOffset(buf) : 0;
          byte[] rawSignatureData = new byte[buf.readableBytes()];
          buf.readBytes(rawSignatureData);
          return new ChatCommand(command, rawSignatureData, lastSeenOffset);
        }

        @Override
        public void encode(ChatCommand packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.command(), maxCommandLength(version));
          buf.writeBytes(packet.rawSignatureData());
        }
      };

  private static int maxCommandLength(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)
        ? McString.DEFAULT_MAX_CHARS
        : MAX_COMMAND_LENGTH_BEFORE_1_20_5;
  }

  /**
   * Whether a command of {@code version} ends with an offset-based last-seen update: 1.19.3 brought
   * it (1.19.1 and 1.19.2 send the last seen messages in full), and 1.20.5 dropped it from the
   * commands this packet still carries, those with no argument to sign.
   */
  private static boolean hasLastSeenOffset(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)
        && version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_5);
  }

  /**
   * Reads the last-seen offset of a 1.19.3 to 1.20.4 command, from the reader index (the timestamp)
   * on, and leaves the reader index there: the signing fields are forwarded as they came. Walks the
   * whole layout, so a command the proxy keeps from the backend is held to what the backend would
   * have accepted.
   */
  private static int peekLastSeenOffset(ByteBuf buf) {
    int start = buf.readerIndex();
    buf.skipBytes(TIMESTAMP_AND_SALT_BYTES);
    int signatures = VarInt.read(buf);
    if (signatures < 0 || signatures > MAX_ARGUMENT_SIGNATURES) {
      throw new DecoderException(
          "Invalid argument signature count: "
              + signatures
              + " (max "
              + MAX_ARGUMENT_SIGNATURES
              + ")");
    }
    for (int i = 0; i < signatures; i++) {
      McString.skip(buf); // argument name
      buf.skipBytes(SIGNATURE_BYTES);
    }
    int offset = VarInt.read(buf);
    if (offset < 0) {
      throw new DecoderException("Negative last-seen offset: " + offset);
    }
    buf.skipBytes(ACKNOWLEDGED_BYTES);
    if (buf.isReadable()) {
      throw new DecoderException(
          "Unexpected bytes after the last-seen update: " + buf.readableBytes());
    }
    buf.readerIndex(start);
    return offset;
  }
}
