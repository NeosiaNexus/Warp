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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Addresses that sent what no game client sends: frames over the limits of their state, invalid
 * handshakes, unexpected packets. An address with {@code strikes} of them within {@code duration}
 * is listed until {@code duration} after its last one: the proxy resets its new connections at
 * accept, and the management server exports the list as CIDR lines for an nftables set or an XDP
 * filter, so that the kernel drops what Warp already judged (decision D14 of the anti-bot
 * research).
 *
 * <p>Only protocol impossibilities strike: never a time-out, a rate limit or a failed
 * authentication, which real players produce on a bad day. Addresses are keyed as the limits key
 * them, IPv4 by address and IPv6 by /64; exempt addresses and trusted proxies never strike. At most
 * {@value RateLimiter#MAX_KEYS} addresses are remembered (Caffeine, W-TinyLFU), so a flood of
 * addresses evicts the least useful ones rather than grow memory.
 */
public final class Blocklist {

  private final int strikes;
  private final Duration duration;
  private final Cache<Long, AtomicInteger> offenders;

  /**
   * Creates a blocklist.
   *
   * @param strikes the violations that list an address; at least 1
   * @param duration the window violations count in, and how long a listed address stays listed
   * @param nanoClock a monotonic clock in nanoseconds
   */
  public Blocklist(int strikes, Duration duration, LongSupplier nanoClock) {
    if (strikes < 1) {
      throw new IllegalArgumentException("strikes must be at least 1, got " + strikes);
    }
    this.strikes = strikes;
    this.duration = duration;
    this.offenders =
        Caffeine.newBuilder()
            .expireAfterWrite(duration)
            .maximumSize(RateLimiter.MAX_KEYS)
            .ticker(nanoClock::getAsLong)
            .executor(Runnable::run)
            .build();
  }

  /**
   * Records a violation of an address, keyed as {@link AddressKey} keys it. Each violation pushes
   * the address's expiry back to {@code duration} from now.
   *
   * @param key the address's key
   */
  void strike(long key) {
    AtomicInteger count = offenders.asMap().computeIfAbsent(key, _ -> new AtomicInteger());
    count.incrementAndGet();
    offenders.put(key, count); // the write restarts its expiry
  }

  /**
   * Returns whether an address is listed.
   *
   * @param key the address's key
   * @return {@code true} if its new connections are to be refused
   */
  boolean listed(long key) {
    AtomicInteger count = offenders.getIfPresent(key);
    return count != null && count.get() >= strikes;
  }

  /**
   * Returns the listed addresses, as CIDR lines: {@code 203.0.113.7/32}, {@code 2001:db8:1:2::/64}.
   *
   * @return the list, in no particular order
   */
  public List<String> cidrs() {
    List<String> listed = new ArrayList<>();
    offenders
        .asMap()
        .forEach(
            (key, count) -> {
              if (count.get() >= strikes) {
                listed.add(AddressKey.cidr(key));
              }
            });
    return listed;
  }

  /**
   * Returns how many addresses are listed.
   *
   * @return the count
   */
  public int size() {
    int listed = 0;
    for (AtomicInteger count : offenders.asMap().values()) {
      if (count.get() >= strikes) {
        listed++;
      }
    }
    return listed;
  }

  /**
   * Returns how long an address stays listed after its last violation.
   *
   * @return the duration
   */
  public Duration duration() {
    return duration;
  }
}
