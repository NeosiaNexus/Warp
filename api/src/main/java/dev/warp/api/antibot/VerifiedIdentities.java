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
package dev.warp.api.antibot;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The identities the proxy trusts: it lets them in during a lockdown, gives them the next free
 * authentication when the proxy authenticates its most, and, once it verifies newcomers, never
 * makes them verify again.
 *
 * <p>Stored on disk under keyed hashes, never in clear; bounded by {@code
 * anti-bot.verified.capacity} (the least recently seen of a random sample makes room); forgotten
 * {@code anti-bot.verified.expire-after-last-join} after their last join. Every method is
 * thread-safe and returns at once: changes are applied by the store's own thread.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface VerifiedIdentities {

  /**
   * Looks an identity up in the proxy's own store.
   *
   * @param identity the identity
   * @return what the proxy remembers of it, or empty if it does not trust it or forgot it
   */
  Optional<Verification> check(Identity identity);

  /**
   * Trusts an identity, as verified by a plugin ({@link VerificationMethod#PLUGIN}), now: a web or
   * Discord verification, say. Fires an {@link dev.warp.api.event.antibot.IdentityVerifiedEvent} if
   * it was not trusted yet.
   *
   * @param identity the identity
   * @return completed with what the proxy remembers of it, once stored
   */
  CompletableFuture<Verification> add(Identity identity);

  /**
   * Stops trusting an identity.
   *
   * @param identity the identity
   * @return completed with {@code true} if it was trusted, once removed
   */
  CompletableFuture<Boolean> remove(Identity identity);

  /**
   * Returns how many identities the proxy trusts.
   *
   * @return the count, expired ones not yet swept included
   */
  int size();

  /**
   * Returns the most identities the proxy remembers.
   *
   * @return {@code anti-bot.verified.capacity}
   */
  int capacity();
}
