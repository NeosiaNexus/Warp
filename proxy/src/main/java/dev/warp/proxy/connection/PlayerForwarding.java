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
package dev.warp.proxy.connection;

import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.login.LoginSuccess;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Builds player forwarding payloads for backend connections.
 *
 * <p>Supports Velocity modern forwarding: the proxy sends the player's IP, UUID, username, and
 * profile properties via the {@code velocity:player_info} login plugin channel, signed with
 * HMAC-SHA256 using a shared secret.
 */
public final class PlayerForwarding {

  /** The Velocity modern forwarding plugin channel name. */
  public static final String VELOCITY_CHANNEL = "velocity:player_info";

  /** Current Velocity modern forwarding version. */
  private static final int FORWARDING_VERSION = 1;

  /** HMAC-SHA256 signature length in bytes. */
  private static final int HMAC_LENGTH = 32;

  private PlayerForwarding() {}

  /**
   * Builds a Velocity modern forwarding payload.
   *
   * <p>Format: {@code [32-byte HMAC-SHA256 signature][forwarding data]}
   *
   * <p>Forwarding data: {@code [VarInt version][UTF-8 client IP][UUID][UTF-8 username][VarInt
   * property count][properties...]}
   *
   * @param player the connected player
   * @param forwardingSecret the shared forwarding secret
   * @return the signed forwarding payload
   */
  public static byte[] buildVelocityForwardingData(
      ConnectedPlayer player, byte[] forwardingSecret) {
    // Build the forwarding data first.
    ByteBuf data = Unpooled.buffer();
    try {
      VarInt.write(data, FORWARDING_VERSION);
      McString.write(data, player.remoteAddress().getAddress().getHostAddress());
      McUuid.write(data, player.uuid());
      McString.write(data, player.username());

      var properties = player.profileProperties();
      VarInt.write(data, properties.size());
      for (LoginSuccess.Property prop : properties) {
        McString.write(data, prop.name());
        McString.write(data, prop.value());
        if (prop.signature() != null) {
          data.writeBoolean(true);
          McString.write(data, prop.signature());
        } else {
          data.writeBoolean(false);
        }
      }

      byte[] dataBytes = new byte[data.readableBytes()];
      data.readBytes(dataBytes);

      // Sign the data with HMAC-SHA256.
      byte[] signature = hmacSha256(forwardingSecret, dataBytes);

      // Prepend signature to data.
      byte[] result = new byte[HMAC_LENGTH + dataBytes.length];
      System.arraycopy(signature, 0, result, 0, HMAC_LENGTH);
      System.arraycopy(dataBytes, 0, result, HMAC_LENGTH, dataBytes.length);
      return result;
    } finally {
      data.release();
    }
  }

  private static byte[] hmacSha256(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError("HmacSHA256 not available", e);
    }
  }
}
