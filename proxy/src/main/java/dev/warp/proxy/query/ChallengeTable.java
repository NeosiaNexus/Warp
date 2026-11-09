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
package dev.warp.proxy.query;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * The challenge tokens handed out to query clients, bound to the endpoint (address and port) that
 * asked, in a table of fixed size.
 *
 * <p>A handshake needs no token, so anyone can make the proxy remember one for any spoofed source.
 * Velocity and BungeeCord keep tokens in a cache without a maximum size, which such a flood grows
 * until the heap gives. This table is an open-addressing array of {@value #SLOTS} slots indexed by
 * a hash of the endpoint: a new handshake overwrites whatever its slot held, so memory is fixed,
 * nothing is allocated per handshake and no eviction runs. A client whose slot was overwritten by a
 * colliding endpoint gets no reply to its stat and asks again, as it does once its token expired.
 *
 * <p>The hash is keyed with a secret drawn once per table, so that an attacker cannot compute
 * offline which spoofed endpoints share a slot with a chosen monitor and keep overwriting its
 * token: collisions are the random 2^-16 events the table is sized for, not a target.
 *
 * <p>Tokens are positive 31-bit values from {@link SecureRandom}: the whole design rests on a
 * source that did not receive the handshake reply being unable to predict the token issued to the
 * endpoint it spoofs, and a statistical generator such as {@code SplittableRandom} gives its state
 * away to anyone who collects a few of its outputs, which a handshake hands out to anyone. One call
 * per handshake, under the rate limits, costs nothing measurable. A token lives {@value
 * #LIFETIME_SECONDS} seconds, as the protocol specifies.
 *
 * <p>Not thread-safe: the query listener has one channel on one event loop.
 */
final class ChallengeTable {

  /** The table's size: a power of two, about 1.3 MB of arrays. */
  static final int SLOTS = 65_536;

  /** How long a token is accepted. */
  static final int LIFETIME_SECONDS = 30;

  private static final long LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(LIFETIME_SECONDS);
  private static final int MASK = SLOTS - 1;

  /** The endpoint each slot was issued to, as {@link #identity}. */
  private final long[] identities = new long[SLOTS];

  private final int[] tokens = new int[SLOTS];

  /** When each slot's token expires, in {@link System#nanoTime()} time. */
  private final long[] expiries = new long[SLOTS];

  private final RandomGenerator random;

  /** The secret the endpoint hash is keyed with. */
  private final long key;

  /** Creates a table whose tokens and hash key come from {@link SecureRandom}. */
  ChallengeTable() {
    this(new SecureRandom());
  }

  /**
   * Creates a table whose tokens and hash key come from a given generator, for tests.
   *
   * @param random the token generator; its first output becomes the hash key
   */
  ChallengeTable(RandomGenerator random) {
    this(random, random.nextLong());
  }

  /**
   * Creates a table with a given generator and hash key, for tests.
   *
   * @param random the token generator
   * @param key the secret the endpoint hash is keyed with
   */
  ChallengeTable(RandomGenerator random, long key) {
    this.random = random;
    this.key = key;
  }

  /**
   * Issues a token to an endpoint, replacing any it had.
   *
   * @param identity the endpoint, from {@link #identity}
   * @param now the current {@link System#nanoTime()}
   * @return the token, positive
   */
  int issue(long identity, long now) {
    int slot = slot(identity);
    int token = random.nextInt(1, Integer.MAX_VALUE);
    identities[slot] = identity;
    tokens[slot] = token;
    expiries[slot] = now + LIFETIME_NANOS;
    return token;
  }

  /**
   * Returns whether a token is the one issued to an endpoint and still valid.
   *
   * @param identity the endpoint, from {@link #identity}
   * @param token the token the datagram carries
   * @param now the current {@link System#nanoTime()}
   * @return {@code true} if the endpoint holds this token and it has not expired
   */
  boolean matches(long identity, int token, long now) {
    int slot = slot(identity);
    // Differences, not comparisons, so that the clock may wrap.
    return identities[slot] == identity && tokens[slot] == token && now - expiries[slot] < 0;
  }

  /**
   * Returns the identity of an endpoint: a 64-bit keyed mix of its address bytes and port. Two
   * endpoints share an identity with probability 2^-64, and even then a stat needs the other's
   * token. The slot an identity maps to depends on this table's key, so identities of one table
   * mean nothing to another.
   *
   * @param address the source address, 4 or 16 bytes
   * @param port the source port
   * @return the identity
   */
  long identity(byte[] address, int port) {
    long hash = mix(key ^ 0x9E37_79B9_7F4A_7C15L) ^ address.length;
    for (byte b : address) {
      hash = mix(hash ^ (b & 0xFF));
    }
    return mix(hash ^ port);
  }

  private static int slot(long identity) {
    return (int) (identity ^ (identity >>> 32)) & MASK;
  }

  /** A 64-bit finalizer (Stafford variant 13), as {@code SplittableRandom} uses. */
  private static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
    value = (value ^ (value >>> 27)) * 0x94D0_49BB_1331_11EBL;
    return value ^ (value >>> 31);
  }
}
