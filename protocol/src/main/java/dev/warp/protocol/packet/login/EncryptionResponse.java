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
 * Client answers the encryption request with the shared secret, and proves it received the verify
 * token ({@code C→S, ID 0x01}).
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.7.2–1.7.10</b>: shared secret, encrypted verify token (unsigned-short-prefixed arrays)
 *   <li><b>1.8–1.18.2, 1.19.3+</b>: shared secret, encrypted verify token (VarInt-prefixed arrays)
 *   <li><b>1.19–1.19.2</b>: shared secret, then a boolean: {@code true} for the encrypted verify
 *       token, {@code false} for a salt (long) and a signature (VarInt-prefixed array)
 * </ul>
 *
 * <p>A 1.19 to 1.19.2 client that sent a profile public key in its {@link LoginStart} signs the
 * token instead of encrypting it: a {@link SignedToken}. Every other client sends an {@link
 * EncryptedToken}.
 *
 * @param sharedSecret the shared secret, encrypted with the server's public key
 * @param verifyToken the proof that the client received the verify token
 */
@SuppressWarnings("ArrayRecordComponent") // the array is never mutated
public record EncryptionResponse(byte[] sharedSecret, VerifyToken verifyToken)
    implements LoginPacket {

  /**
   * Longest array accepted: twice what the proxy's 1024-bit key encrypts to, and the length of a
   * signature by a 2048-bit player key, the size Mojang issues.
   */
  private static final int MAX_ARRAY = 256;

  /** How the client proves it received the verify token. */
  public sealed interface VerifyToken permits EncryptedToken, SignedToken {}

  /**
   * The verify token, encrypted with the server's public key (RSA, PKCS #1 v1.5 padding).
   *
   * @param encrypted the encrypted token
   */
  @SuppressWarnings("ArrayRecordComponent") // the array is never mutated
  public record EncryptedToken(byte[] encrypted) implements VerifyToken {}

  /**
   * The verify token signed with the player's profile key, 1.19 to 1.19.2 only: {@code
   * SHA256withRSA} over the token followed by the salt as 8 big-endian bytes.
   *
   * @param salt the random salt the client mixed into the signature
   * @param signature the signature
   */
  @SuppressWarnings("ArrayRecordComponent") // the array is never mutated
  public record SignedToken(long salt, byte[] signature) implements VerifyToken {}

  /** Codec for reading and writing encryption response packets. */
  public static final PacketCodec<EncryptionResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public EncryptionResponse decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            byte[] sharedSecret = McByteArray.readShortPrefixed(buf, MAX_ARRAY);
            byte[] token = McByteArray.readShortPrefixed(buf, MAX_ARRAY);
            return new EncryptionResponse(sharedSecret, new EncryptedToken(token));
          }
          byte[] sharedSecret = McByteArray.read(buf, MAX_ARRAY);
          if (hasSignedTokenOption(version) && !buf.readBoolean()) {
            long salt = buf.readLong();
            byte[] signature = McByteArray.read(buf, MAX_ARRAY);
            return new EncryptionResponse(sharedSecret, new SignedToken(salt, signature));
          }
          byte[] token = McByteArray.read(buf, MAX_ARRAY);
          return new EncryptionResponse(sharedSecret, new EncryptedToken(token));
        }

        @Override
        public void encode(EncryptionResponse packet, ByteBuf buf, ProtocolVersion version) {
          VerifyToken verifyToken = packet.verifyToken();
          if (verifyToken instanceof SignedToken && !hasSignedTokenOption(version)) {
            throw new IllegalStateException(
                "A signed verify token exists only in 1.19 to 1.19.2, not in " + version);
          }
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            McByteArray.writeShortPrefixed(buf, packet.sharedSecret());
            McByteArray.writeShortPrefixed(buf, ((EncryptedToken) verifyToken).encrypted());
            return;
          }
          McByteArray.write(buf, packet.sharedSecret());
          if (hasSignedTokenOption(version)) {
            buf.writeBoolean(verifyToken instanceof EncryptedToken);
          }
          switch (verifyToken) {
            case EncryptedToken(byte[] encrypted) -> McByteArray.write(buf, encrypted);
            case SignedToken(long salt, byte[] signature) -> {
              buf.writeLong(salt);
              McByteArray.write(buf, signature);
            }
          }
        }
      };

  /** Whether {@code version} lets the client sign the verify token (1.19 to 1.19.2). */
  private static boolean hasSignedTokenOption(ProtocolVersion version) {
    return version.isBetween(ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2);
  }
}
