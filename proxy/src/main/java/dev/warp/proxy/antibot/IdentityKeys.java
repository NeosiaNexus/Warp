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
package dev.warp.proxy.antibot;

import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.VerifiedKey;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes the keys identities are stored under: the first 16 bytes of an HMAC-SHA256 of a
 * domain-separated input, under the proxy's secret (decision D3 of the anti-bot research).
 *
 * <ul>
 *   <li>{@code "account:" + uuid}: an account, the identity in online mode.
 *   <li>{@code "name:" + lowercase(name) + "@" + network}: a name and the network it connects from,
 *       the identity in offline mode, and how online mode recognizes a returning player before it
 *       authenticates. The network is the IPv4 /24 or IPv6 /48 of the address by default, an
 *       IPv4-mapped IPv6 address counting as IPv4.
 * </ul>
 *
 * <p>Nothing reversible is kept: without the secret, a key reveals neither name nor address. Thread
 * safe: each thread computes with its own {@link Mac}.
 */
public final class IdentityKeys {

  private static final String ALGORITHM = "HmacSHA256";

  /** The length of a generated secret, in bytes. */
  static final int SECRET_LENGTH = 32;

  private final SecretKeySpec secret;
  private final int ipv4Prefix;
  private final int ipv6Prefix;
  private final ThreadLocal<Mac> macs;

  /** Seeds {@link #networkHash}: the first bytes of the secret. */
  private final int seed;

  /**
   * Creates the keys of a secret.
   *
   * @param secret the secret, at least 16 bytes
   * @param ipv4Prefix the bits of an IPv4 address that make its network, 8 to 32
   * @param ipv6Prefix the bits of an IPv6 address that make its network, 16 to 128
   * @throws IllegalArgumentException if the secret is too short or a prefix out of range
   */
  public IdentityKeys(byte[] secret, int ipv4Prefix, int ipv6Prefix) {
    if (secret.length < 16) {
      throw new IllegalArgumentException("the secret must be at least 16 bytes");
    }
    if (ipv4Prefix < 8 || ipv4Prefix > 32 || ipv6Prefix < 16 || ipv6Prefix > 128) {
      throw new IllegalArgumentException(
          "prefixes out of range: /" + ipv4Prefix + ", /" + ipv6Prefix);
    }
    this.secret = new SecretKeySpec(secret.clone(), ALGORITHM);
    this.ipv4Prefix = ipv4Prefix;
    this.ipv6Prefix = ipv6Prefix;
    this.macs = ThreadLocal.withInitial(this::newMac);
    this.seed = ByteBuffer.wrap(secret).getInt();
    newMac(); // fails now rather than on a login if the platform lacks HMAC-SHA256
  }

  // ---------------------------------------------------------------------------
  // Keys
  // ---------------------------------------------------------------------------

  /**
   * Returns the key of an identity.
   *
   * @param identity the identity
   * @return its key
   */
  public VerifiedKey key(Identity identity) {
    return switch (identity) {
      case Identity.Account(UUID uuid) -> account(uuid);
      case Identity.NameAtAddress(String name, InetAddress address) -> nameAt(name, address);
    };
  }

  /**
   * Returns the key of an account.
   *
   * @param uuid the account's UUID
   * @return its key
   */
  public VerifiedKey account(UUID uuid) {
    return hash("account:" + uuid);
  }

  /**
   * Returns the key of a name and the network of an address.
   *
   * @param name the name, in any case
   * @param address an address of the network
   * @return its key
   */
  public VerifiedKey nameAt(String name, InetAddress address) {
    return hash("name:" + name.toLowerCase(Locale.ROOT) + "@" + network(address));
  }

  /**
   * Returns a hash of the network an address counts as, for structures that spread sources over
   * buckets (the verification queue's fairness): the same for every address of a network, and
   * seeded by the secret, so that an attacker cannot aim at one bucket.
   *
   * @param address the address
   * @return the hash of its network
   */
  public int networkHash(InetAddress address) {
    byte[] bytes = ipv4(address.getAddress());
    int prefix = bytes.length == 4 ? ipv4Prefix : ipv6Prefix;
    byte[] masked = mask(bytes, prefix);
    int hash = seed;
    for (byte b : masked) {
      hash = (hash ^ (b & 0xFF)) * 0x01000193; // FNV-1a
    }
    hash ^= hash >>> 16;
    hash *= 0x85EBCA6B;
    return hash ^ (hash >>> 13);
  }

  /**
   * Returns the network an address counts as, in CIDR notation: {@code 203.0.113.0/24}, {@code
   * 2001:db8:1::/48}. An IPv4-mapped IPv6 address counts as IPv4.
   *
   * @param address the address
   * @return its network
   */
  public String network(InetAddress address) {
    byte[] bytes = ipv4(address.getAddress());
    int prefix = bytes.length == 4 ? ipv4Prefix : ipv6Prefix;
    byte[] masked = mask(bytes, prefix);
    try {
      InetAddress network = InetAddress.getByAddress(masked);
      String host = network instanceof Inet4Address ? network.getHostAddress() : compress(network);
      return host + "/" + prefix;
    } catch (UnknownHostException e) {
      throw new AssertionError("4 or 16 bytes always make an address", e);
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private VerifiedKey hash(String input) {
    byte[] digest = macs.get().doFinal(input.getBytes(StandardCharsets.UTF_8));
    ByteBuffer buf = ByteBuffer.wrap(digest);
    return new VerifiedKey(buf.getLong(), buf.getLong());
  }

  private Mac newMac() {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(secret);
      return mac;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is a mandatory JCA algorithm", e);
    }
  }

  /** The 4 bytes of an IPv4 address, IPv4-mapped IPv6 included; other addresses unchanged. */
  private static byte[] ipv4(byte[] bytes) {
    if (bytes.length == 16) {
      for (int i = 0; i < 10; i++) {
        if (bytes[i] != 0) {
          return bytes;
        }
      }
      if (bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
        return Arrays.copyOfRange(bytes, 12, 16);
      }
    }
    return bytes;
  }

  private static byte[] mask(byte[] bytes, int prefix) {
    byte[] masked = bytes.clone();
    for (int bit = prefix; bit < masked.length * 8; bit++) {
      masked[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
    }
    return masked;
  }

  /** The canonical text of an IPv6 address: lowercase, the longest run of zeros as {@code ::}. */
  private static String compress(InetAddress address) {
    String full = address.getHostAddress();
    int scope = full.indexOf('%');
    if (scope >= 0) {
      full = full.substring(0, scope);
    }
    String[] groups = full.split(":", -1);
    int bestStart = -1;
    int bestLength = 1;
    for (int i = 0; i < groups.length; ) {
      if (!groups[i].equals("0")) {
        i++;
        continue;
      }
      int j = i;
      while (j < groups.length && groups[j].equals("0")) {
        j++;
      }
      if (j - i > bestLength) {
        bestStart = i;
        bestLength = j - i;
      }
      i = j;
    }
    if (bestStart < 0) {
      return String.join(":", groups);
    }
    String head = String.join(":", Arrays.copyOfRange(groups, 0, bestStart));
    String tail =
        String.join(":", Arrays.copyOfRange(groups, bestStart + bestLength, groups.length));
    return head + "::" + tail;
  }
}
