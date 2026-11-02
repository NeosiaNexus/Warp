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
import java.util.ArrayList;
import java.util.List;

/**
 * An immutable list of {@link CidrRange}s, matched as one: an address is in the list when any of
 * its ranges contains it.
 *
 * <p>Matching reads the address once and compares it with each range in turn, without allocating
 * beyond the address bytes {@link InetAddress#getAddress()} copies. The lists Warp builds from its
 * configuration hold a handful of ranges, for which a linear scan is the fastest structure.
 */
public final class CidrRanges {

  private static final CidrRanges NONE = new CidrRanges(List.of());

  private final List<CidrRange> ranges;

  private CidrRanges(List<CidrRange> ranges) {
    this.ranges = List.copyOf(ranges);
  }

  /**
   * Returns the empty list, which contains no address.
   *
   * @return the empty list
   */
  public static CidrRanges none() {
    return NONE;
  }

  /**
   * Returns a list of the given ranges, in their order.
   *
   * @param ranges the ranges
   * @return the list
   */
  public static CidrRanges of(List<CidrRange> ranges) {
    return ranges.isEmpty() ? NONE : new CidrRanges(ranges);
  }

  /**
   * Parses each value with {@link CidrRange#parse(String)}.
   *
   * @param values IP addresses or CIDR ranges
   * @return the list, in the order of the values
   * @throws IllegalArgumentException if a value is not an address or a valid range; the message
   *     names the value
   */
  public static CidrRanges parse(List<String> values) {
    List<CidrRange> ranges = new ArrayList<>(values.size());
    for (String value : values) {
      ranges.add(CidrRange.parse(value));
    }
    return of(ranges);
  }

  /**
   * Returns whether any range of the list contains {@code address}.
   *
   * @param address the address to test
   * @return {@code true} if the address is in the list
   */
  public boolean contains(InetAddress address) {
    if (ranges.isEmpty()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    for (CidrRange range : ranges) {
      if (range.contains(bytes)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether the list holds no range, and so contains no address.
   *
   * @return {@code true} if the list is empty
   */
  public boolean isEmpty() {
    return ranges.isEmpty();
  }

  /**
   * Returns the ranges, in their order.
   *
   * @return the unmodifiable list of ranges
   */
  public List<CidrRange> ranges() {
    return ranges;
  }

  /**
   * Returns the ranges in canonical CIDR notation, such as {@code [10.0.0.0/8, 2001:db8::/32]}.
   *
   * @return the ranges as text
   */
  @Override
  public String toString() {
    return ranges.toString();
  }
}
