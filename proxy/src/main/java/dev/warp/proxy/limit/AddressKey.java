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
package dev.warp.proxy.limit;

import java.net.InetAddress;

/**
 * The key under which limits count a client address: an IPv4 address on its own, an IPv6 address
 * with its /64 network.
 *
 * <p>A /64 is what an ISP hands a single subscriber, and every address in it is the subscriber's to
 * use: keyed by its full 128 bits, as Velocity keys its login limiter, one subscriber could open
 * each connection from a fresh address and never meet a limit. IPv4 addresses stay whole: grouping
 * them by /24, as Gate does, would make the customers of a carrier-grade NAT share one budget with
 * their neighbours. An IPv4-mapped IPv6 address counts as its IPv4 address.
 */
public final class AddressKey {

  /**
   * Marks IPv4 keys apart from IPv6 networks. An IPv6 /64 with these 32 leading bits is in {@code
   * ff00::/8}, multicast, which never opens a TCP connection.
   */
  private static final long IPV4_TAG = 0xFFFF_FFFF_0000_0000L;

  private AddressKey() {}

  /**
   * Returns the key of an address.
   *
   * @param address the client's address
   * @return its key: the IPv4 address, or the IPv6 /64 network, in a {@code long}
   */
  public static long of(InetAddress address) {
    return of(address.getAddress());
  }

  /**
   * Returns the key of an address given as bytes, for a caller that already holds them.
   *
   * @param bytes the address, 4 bytes (IPv4) or 16 (IPv6, IPv4-mapped included)
   * @return its key, as {@link #of(InetAddress)} returns it
   */
  public static long of(byte[] bytes) {
    if (bytes.length == 4) {
      return IPV4_TAG | ipv4(bytes, 0);
    }
    if (isIpv4Mapped(bytes)) {
      return IPV4_TAG | ipv4(bytes, 12);
    }
    long network = 0;
    for (int i = 0; i < 8; i++) {
      network = (network << 8) | (bytes[i] & 0xFFL);
    }
    return network;
  }

  /**
   * Returns what a key covers, in CIDR notation: {@code 203.0.113.7/32} for an IPv4 address, {@code
   * 2001:db8:1:2::/64} for an IPv6 network.
   *
   * @param key a key from {@link #of(InetAddress)}
   * @return its CIDR
   */
  public static String cidr(long key) {
    if ((key & IPV4_TAG) == IPV4_TAG) {
      return ((key >>> 24) & 0xFF)
          + "."
          + ((key >>> 16) & 0xFF)
          + "."
          + ((key >>> 8) & 0xFF)
          + "."
          + (key & 0xFF)
          + "/32";
    }
    return Long.toHexString((key >>> 48) & 0xFFFF)
        + ":"
        + Long.toHexString((key >>> 32) & 0xFFFF)
        + ":"
        + Long.toHexString((key >>> 16) & 0xFFFF)
        + ":"
        + Long.toHexString(key & 0xFFFF)
        + "::/64";
  }

  private static long ipv4(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFFL) << 24)
        | ((bytes[offset + 1] & 0xFFL) << 16)
        | ((bytes[offset + 2] & 0xFFL) << 8)
        | (bytes[offset + 3] & 0xFFL);
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
  }
}
