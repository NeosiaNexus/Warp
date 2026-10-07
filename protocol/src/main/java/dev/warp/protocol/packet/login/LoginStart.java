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
 *   <li><b>1.19</b>: {@code name}, optional profile public key
 *   <li><b>1.19.1–1.19.2</b>: {@code name}, optional profile public key, optional UUID
 *   <li><b>1.19.3–1.20.1</b>: {@code name}, optional UUID
 *   <li><b>1.20.2+</b>: {@code name}, UUID (always present)
 * </ul>
 *
 * <p>An optional field is a boolean followed, when {@code true}, by the field. The profile public
 * key is the player's chat signing key, as {@link ProfilePublicKey} lays it out. A client that
 * sends one proves it holds the private key in its {@link EncryptionResponse}.
 *
 * <p>Warp never forwards the key: the login it sends a backend carries none, and the offline-mode
 * backends it logs players into do not require one.
 *
 * @param name the player's username (max 16 characters)
 * @param profileKey the player's profile public key, or {@code null} when the client sent none
 *     (always {@code null} outside 1.19 to 1.19.2, where the field does not exist)
 * @param playerUuid the player's UUID, or {@code null} when unknown (always {@code null} when
 *     decoded before 1.19.1, required to encode for 1.20.2+)
 */
public record LoginStart(
    String name, @Nullable ProfilePublicKey profileKey, @Nullable UUID playerUuid)
    implements LoginPacket {

  /** Maximum username length per protocol spec. */
  private static final int MAX_USERNAME = 16;

  /**
   * Creates a login start without a profile public key, the form of every version but 1.19 to
   * 1.19.2 and of every login Warp sends a backend.
   *
   * @param name the player's username (max 16 characters)
   * @param playerUuid the player's UUID, or {@code null} when unknown
   */
  public LoginStart(String name, @Nullable UUID playerUuid) {
    this(name, null, playerUuid);
  }

  /** Codec for reading and writing login start packets. */
  public static final PacketCodec<LoginStart> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginStart decode(ByteBuf buf, ProtocolVersion version) {
          String name = McString.read(buf, MAX_USERNAME);
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
            return new LoginStart(name, null, McUuid.read(buf));
          }
          @Nullable ProfilePublicKey key =
              hasProfileKey(version) && buf.readBoolean() ? ProfilePublicKey.read(buf) : null;
          @Nullable UUID uuid =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1) && buf.readBoolean()
                  ? McUuid.read(buf)
                  : null;
          return new LoginStart(name, key, uuid);
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
          if (hasProfileKey(version)) {
            @Nullable ProfilePublicKey key = packet.profileKey();
            buf.writeBoolean(key != null);
            if (key != null) {
              key.write(buf);
            }
          }
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
            buf.writeBoolean(uuid != null);
            if (uuid != null) {
              McUuid.write(buf, uuid);
            }
          }
        }
      };

  /** Whether {@code version} carries the optional profile public key (1.19 to 1.19.2). */
  private static boolean hasProfileKey(ProtocolVersion version) {
    return version.isBetween(ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2);
  }

  // ---------------------------------------------------------------------------
  // Profile public key
  // ---------------------------------------------------------------------------

  /**
   * A player's profile public key: the RSA key a 1.19 to 1.19.2 client signs its chat with, and
   * Mojang's certificate for it.
   *
   * <p>Wire format: the expiry as a long (milliseconds since the epoch), then the public key and
   * Mojang's signature, each a VarInt-prefixed byte array. Vanilla reads at most 512 bytes of key
   * and 4096 bytes of signature, and so does Warp: Mojang issues 2048-bit player keys (294 bytes of
   * DER) and signs them with a 4096-bit key (512-byte signatures).
   *
   * <p>What Mojang signs changed in 1.19.1: the expiry and the key as PEM text in 1.19, the
   * player's UUID, the expiry and the DER key from 1.19.1, which binds the key to the player.
   *
   * @param expiresAt when the key expires, in milliseconds since the epoch
   * @param publicKey the player's RSA public key, a DER-encoded X.509 {@code SubjectPublicKeyInfo}
   * @param keySignature Mojang's signature of the key
   */
  @SuppressWarnings("ArrayRecordComponent") // the arrays are never mutated
  public record ProfilePublicKey(long expiresAt, byte[] publicKey, byte[] keySignature) {

    /** Longest public key vanilla reads ({@code FriendlyByteBuf.readPublicKey}). */
    private static final int MAX_PUBLIC_KEY = 512;

    /** Longest key signature vanilla reads ({@code ProfilePublicKey.Data}). */
    private static final int MAX_KEY_SIGNATURE = 4096;

    /** Reads the key that follows its presence flag. */
    static ProfilePublicKey read(ByteBuf buf) {
      long expiresAt = buf.readLong();
      byte[] publicKey = McByteArray.read(buf, MAX_PUBLIC_KEY);
      byte[] keySignature = McByteArray.read(buf, MAX_KEY_SIGNATURE);
      return new ProfilePublicKey(expiresAt, publicKey, keySignature);
    }

    /** Writes the key, without its presence flag. */
    void write(ByteBuf buf) {
      buf.writeLong(expiresAt);
      McByteArray.write(buf, publicKey);
      McByteArray.write(buf, keySignature);
    }
  }
}
