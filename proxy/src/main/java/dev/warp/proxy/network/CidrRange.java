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
package dev.warp.proxy.network;

import java.net.InetAddress;
import java.util.Objects;

import io.netty.util.NetUtil;
import org.jspecify.annotations.Nullable;

/**
 * A block of IP addresses written in CIDR notation, such as {@code 10.0.0.0/8} or {@code
 * 2001:db8::/32}; a single address ({@code 10.0.0.1}) is the block of that address alone.
 *
 * <p>IPv4 addresses match whether they arrive as IPv4 or as IPv4-mapped IPv6 ({@code
 * ::ffff:10.0.0.1}, what a dual-stack socket reports for an IPv4 peer), and a range written in the
 * mapped form ({@code ::ffff:10.0.0.0/104}) is the IPv4 range it maps.
 *
 * <p>Parsing never resolves a host name: only IP literals are accepted. Matching takes a few
 * comparisons of two {@code long}s and is thread-safe, the range being immutable.
 */
public final class CidrRange {

  private static final int IPV4_BITS = 32;
  private static final int IPV6_BITS = 128;

  /** Bits of the {@code ::ffff:0:0/96} prefix of IPv4-mapped IPv6 addresses. */
  private static final int IPV4_MAPPED_PREFIX_BITS = IPV6_BITS - IPV4_BITS;

  /** The second half of an IPv4-mapped IPv6 address, before its IPv4 address is added. */
  private static final long IPV4_MAPPED_LOW = 0x0000_FFFF_0000_0000L;

  private final boolean ipv4;

  /** The first 64 bits of an IPv6 network; 0 for IPv4. */
  private final long high;

  /** The last 64 bits of an IPv6 network, or the IPv4 network in the low 32 bits. */
  private final long low;

  private final long highMask;
  private final long lowMask;
  private final int prefixLength;
  private final String text;

  private CidrRange(boolean ipv4, long high, long low, int prefixLength) {
    this.ipv4 = ipv4;
    this.prefixLength = prefixLength;
    int bits = ipv4 ? IPV4_BITS : IPV6_BITS;
    // The mask of an IPv4 range covers the low 32 bits only, those of the address.
    int hostBits = bits - prefixLength;
    this.highMask = ipv4 ? 0 : mask(Math.max(0, 64 - prefixLength), 64);
    this.lowMask = ipv4 ? mask(hostBits, IPV4_BITS) : mask(Math.min(64, hostBits), 64);
    this.high = high;
    this.low = low;
    this.text = canonicalText();
  }

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  /**
   * Parses an IP address or a CIDR range.
   *
   * <p>A range whose address has bits set past its prefix ({@code 10.0.0.1/8}) is refused rather
   * than silently widened: it usually means the author had another range in mind, and the error
   * names the range the prefix covers.
   *
   * @param value an IPv4 or IPv6 literal, optionally followed by {@code /} and a prefix length
   * @return the range
   * @throws IllegalArgumentException if {@code value} is not an IP literal, the prefix is out of
   *     range, or the address has bits set past the prefix
   */
  public static CidrRange parse(String value) {
    int slash = value.indexOf('/');
    String addressText = slash < 0 ? value : value.substring(0, slash);
    byte @Nullable [] bytes = NetUtil.createByteArrayFromIpAddressString(addressText);
    if (bytes == null) {
      throw new IllegalArgumentException(
          "'" + value + "' is not an IP address or a CIDR range such as 10.0.0.0/8");
    }
    int maxBits = bytes.length * Byte.SIZE;
    int prefix = slash < 0 ? maxBits : parsePrefix(value, value.substring(slash + 1), maxBits);

    CidrRange range;
    if (bytes.length == 4) {
      range = new CidrRange(true, 0, ipv4Bits(bytes, 0), prefix);
    } else if (isIpv4Mapped(bytes) && prefix >= IPV4_MAPPED_PREFIX_BITS) {
      range = new CidrRange(true, 0, ipv4Bits(bytes, 12), prefix - IPV4_MAPPED_PREFIX_BITS);
    } else {
      range = new CidrRange(false, longAt(bytes, 0), longAt(bytes, 8), prefix);
    }
    if ((range.high & range.highMask) != 0 || (range.low & range.lowMask) != 0) {
      throw new IllegalArgumentException(
          "'"
              + value
              + "' has address bits set past its /"
              + prefix
              + " prefix: the range it covers is "
              + range.withoutHostBits());
    }
    return range;
  }

  private static int parsePrefix(String value, String prefixText, int maxBits) {
    if (prefixText.isEmpty()
        || prefixText.length() > 3
        || !prefixText.chars().allMatch(c -> c >= '0' && c <= '9')) {
      throw new IllegalArgumentException(
          "'" + value + "' has an invalid prefix length: expected 0 to " + maxBits);
    }
    int prefix = Integer.parseInt(prefixText);
    if (prefix > maxBits) {
      throw new IllegalArgumentException(
          "'" + value + "' has a prefix longer than its " + maxBits + "-bit address");
    }
    return prefix;
  }

  // ---------------------------------------------------------------------------
  // Matching
  // ---------------------------------------------------------------------------

  /**
   * Returns whether this range contains {@code address}.
   *
   * @param address the address to test
   * @return {@code true} if the address is in the range
   */
  public boolean contains(InetAddress address) {
    return contains(address.getAddress());
  }

  /**
   * Returns whether this range contains the address of {@code bytes}, as returned by {@link
   * InetAddress#getAddress()}: 4 bytes for IPv4, 16 for IPv6.
   *
   * @param bytes the address, in network byte order
   * @return {@code true} if the address is in the range
   */
  boolean contains(byte[] bytes) {
    if (bytes.length == 4) {
      long address = ipv4Bits(bytes, 0);
      return ipv4
          ? (address & ~lowMask) == low
          // An IPv6 range holds an IPv4 address in its mapped form.
          : matchesIpv6(0, IPV4_MAPPED_LOW | address);
    }
    if (isIpv4Mapped(bytes)) {
      long address = ipv4Bits(bytes, 12);
      return ipv4 ? (address & ~lowMask) == low : matchesIpv6(0, IPV4_MAPPED_LOW | address);
    }
    return !ipv4 && matchesIpv6(longAt(bytes, 0), longAt(bytes, 8));
  }

  private boolean matchesIpv6(long addressHigh, long addressLow) {
    return (addressHigh & ~highMask) == high && (addressLow & ~lowMask) == low;
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns whether this is a range of IPv4 addresses (IPv4-mapped ranges included).
   *
   * @return {@code true} for IPv4, {@code false} for IPv6
   */
  public boolean isIpv4() {
    return ipv4;
  }

  /**
   * Returns the number of leading bits every address of the range shares: 0 for a range of every
   * address, 32 (IPv4) or 128 (IPv6) for a single address.
   *
   * @return the prefix length
   */
  public int prefixLength() {
    return prefixLength;
  }

  /**
   * Returns the range in canonical CIDR notation, such as {@code 10.0.0.0/8} or {@code
   * 2001:db8::/32} (RFC 5952 for IPv6), always with its prefix length.
   *
   * @return the canonical notation
   */
  @Override
  public String toString() {
    return text;
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof CidrRange range
        && ipv4 == range.ipv4
        && high == range.high
        && low == range.low
        && prefixLength == range.prefixLength;
  }

  @Override
  public int hashCode() {
    return Objects.hash(ipv4, high, low, prefixLength);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Returns this range with the bits past the prefix cleared. */
  private CidrRange withoutHostBits() {
    return new CidrRange(ipv4, high & ~highMask, low & ~lowMask, prefixLength);
  }

  private String canonicalText() {
    byte[] bytes;
    if (ipv4) {
      bytes = new byte[4];
      for (int i = 0; i < 4; i++) {
        bytes[i] = (byte) (low >>> (24 - i * 8));
      }
    } else {
      bytes = new byte[16];
      for (int i = 0; i < 8; i++) {
        bytes[i] = (byte) (high >>> (56 - i * 8));
        bytes[i + 8] = (byte) (low >>> (56 - i * 8));
      }
    }
    return NetUtil.bytesToIpAddress(bytes) + "/" + prefixLength;
  }

  /** Returns a mask of the {@code hostBits} low bits of a {@code width}-bit value. */
  private static long mask(int hostBits, int width) {
    if (hostBits <= 0) {
      return 0;
    }
    if (hostBits >= 64) {
      return -1L;
    }
    long mask = (1L << hostBits) - 1;
    return width < 64 ? mask & ((1L << width) - 1) : mask;
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
  }

  private static long ipv4Bits(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFFL) << 24)
        | ((bytes[offset + 1] & 0xFFL) << 16)
        | ((bytes[offset + 2] & 0xFFL) << 8)
        | (bytes[offset + 3] & 0xFFL);
  }

  private static long longAt(byte[] bytes, int offset) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (bytes[offset + i] & 0xFFL);
    }
    return value;
  }
}
