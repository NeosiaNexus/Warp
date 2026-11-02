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

import dev.warp.api.antibot.AttackReport;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts the hostile events of each network during an attack, to name its main sources: a fixed
 * table of {@value #SLOTS} networks, filled lock-free, where a network that finds no room within a
 * few probes counts as "other". Its memory never grows, whatever the number of networks attacking.
 */
final class SourceCounter {

  static final int SLOTS = 1024;
  private static final int PROBES = 8;
  private static final long IPV4_TAG = 0xFFFF_FFFF_0000_0000L;

  private final AtomicLongArray keys = new AtomicLongArray(SLOTS);
  private final AtomicLongArray counts = new AtomicLongArray(SLOTS);
  private final LongAdder others = new LongAdder();
  private final int ipv4Prefix;
  private final int ipv6Prefix;

  /**
   * Creates a counter grouping addresses by network.
   *
   * @param ipv4Prefix the bits of an IPv4 network
   * @param ipv6Prefix the bits of an IPv6 network; at most 64 count
   */
  SourceCounter(int ipv4Prefix, int ipv6Prefix) {
    this.ipv4Prefix = ipv4Prefix;
    this.ipv6Prefix = Math.min(64, ipv6Prefix);
  }

  /**
   * Counts an event of an address's network.
   *
   * @param address the address
   */
  void count(InetAddress address) {
    long key = key(address.getAddress());
    int start = (int) (mix(key) & (SLOTS - 1));
    for (int probe = 0; probe < PROBES; probe++) {
      int i = (start + probe) & (SLOTS - 1);
      long held = keys.get(i);
      if (held == 0 && keys.compareAndSet(i, 0, key)) {
        held = key;
      } else if (held == 0) {
        held = keys.get(i);
      }
      if (held == key) {
        counts.incrementAndGet(i);
        return;
      }
    }
    others.increment();
  }

  /** Forgets every network, as an attack starts. Called while nothing counts. */
  void clear() {
    for (int i = 0; i < SLOTS; i++) {
      keys.set(i, 0);
      counts.set(i, 0);
    }
    others.reset();
  }

  /**
   * Returns the networks with the most events.
   *
   * @param limit how many
   * @return the networks, most first
   */
  List<AttackReport.Source> top(int limit) {
    List<AttackReport.Source> sources = new ArrayList<>();
    for (int i = 0; i < SLOTS; i++) {
      long key = keys.get(i);
      long count = counts.get(i);
      if (key != 0 && count > 0) {
        sources.add(new AttackReport.Source(cidr(key), count));
      }
    }
    sources.sort(Comparator.comparingLong(AttackReport.Source::events).reversed());
    return sources.size() > limit ? List.copyOf(sources.subList(0, limit)) : sources;
  }

  // ---------------------------------------------------------------------------
  // Keys
  // ---------------------------------------------------------------------------

  private long key(byte[] bytes) {
    int offset = -1;
    if (bytes.length == 4) {
      offset = 0;
    } else if (isIpv4Mapped(bytes)) {
      offset = 12;
    }
    if (offset >= 0) {
      long ipv4 =
          ((bytes[offset] & 0xFFL) << 24)
              | ((bytes[offset + 1] & 0xFFL) << 16)
              | ((bytes[offset + 2] & 0xFFL) << 8)
              | (bytes[offset + 3] & 0xFFL);
      long mask = ipv4Prefix == 0 ? 0 : (0xFFFF_FFFFL << (32 - ipv4Prefix)) & 0xFFFF_FFFFL;
      return IPV4_TAG | (ipv4 & mask);
    }
    long network = 0;
    for (int i = 0; i < 8; i++) {
      network = (network << 8) | (bytes[i] & 0xFFL);
    }
    long mask = ipv6Prefix == 64 ? -1L : ~(-1L >>> ipv6Prefix);
    long key = network & mask;
    return key == 0 ? 1 : key; // 0 marks an empty slot; ::/n opens no connection anyway
  }

  private String cidr(long key) {
    if ((key & IPV4_TAG) == IPV4_TAG) {
      return ((key >>> 24) & 0xFF)
          + "."
          + ((key >>> 16) & 0xFF)
          + "."
          + ((key >>> 8) & 0xFF)
          + "."
          + (key & 0xFF)
          + "/"
          + ipv4Prefix;
    }
    StringBuilder text = new StringBuilder();
    int groups = (ipv6Prefix + 15) / 16;
    for (int g = 0; g < groups; g++) {
      if (g > 0) {
        text.append(':');
      }
      text.append(Long.toHexString((key >>> (48 - 16 * g)) & 0xFFFF));
    }
    return text.append("::/").append(ipv6Prefix).toString();
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

  private static long mix(long key) {
    long h = key * 0x9E37_79B9_7F4A_7C15L;
    return h ^ (h >>> 29);
  }
}
