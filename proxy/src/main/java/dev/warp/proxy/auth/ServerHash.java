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
package dev.warp.proxy.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the Minecraft-style server hash used for session authentication.
 *
 * <p>The server hash is a SHA-1 digest of {@code serverId + sharedSecret + publicKey}, formatted as
 * a twos-complement hexadecimal string. Unlike standard hex encoding, Minecraft uses Java's {@link
 * BigInteger} representation: negative hashes are prefixed with {@code -}, and there are no leading
 * zeros.
 *
 * <p>This is the hash sent to the Mojang session server in the {@code hasJoined} request.
 */
public final class ServerHash {

  private ServerHash() {}

  /**
   * Computes the Minecraft server hash.
   *
   * @param serverId the server ID string (empty string for online-mode servers since 1.7)
   * @param sharedSecret the negotiated shared secret (16 bytes)
   * @param publicKey the server's DER-encoded RSA public key
   * @return the hex-encoded server hash string
   */
  public static String compute(String serverId, byte[] sharedSecret, byte[] publicKey) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      // SHA-1 is required by the JCA specification — this cannot happen.
      throw new AssertionError("SHA-1 not available", e);
    }
    digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
    digest.update(sharedSecret);
    digest.update(publicKey);
    return new BigInteger(digest.digest()).toString(16);
  }
}
