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
 * A store of trusted identities shared by several proxies, such as one backed by Redis or a
 * database, which a plugin implements and registers with {@link
 * AntiBot#shareVerifications(VerifiedStore)}. The proxy's own store stays in front of it, as a
 * cache:
 *
 * <ul>
 *   <li>When a login's identity is not in the proxy's store, the proxy asks this one, and waits for
 *       the answer at most {@code anti-bot.verified.shared-store-timeout}: a slow or failed answer
 *       counts as unknown, never as a stall. An identity found is copied into the proxy's store.
 *   <li>Every identity the proxy starts trusting, every join of a trusted one and every removal is
 *       passed on with {@link #save} or {@link #delete}, after the proxy's own store applied it.
 * </ul>
 *
 * <p>The store only ever sees {@link VerifiedKey}s, never names, addresses or accounts. Proxies
 * that share it must share {@code anti-bot.verified.secret-file}. Methods are called from the
 * proxy's threads and must not block: return a future, and hand any I/O to a thread of your own.
 */
public interface VerifiedStore {

  /**
   * Looks a key up.
   *
   * @param key the key of the identity
   * @return completed with what the store remembers of it, or empty
   */
  CompletableFuture<Optional<Verification>> load(VerifiedKey key);

  /**
   * Records that an identity is trusted, or was seen again. Called after the proxy's own store
   * applied it; the proxy does not wait.
   *
   * @param key the key of the identity
   * @param verification what the proxy now remembers of it
   */
  void save(VerifiedKey key, Verification verification);

  /**
   * Forgets an identity. Called after the proxy's own store applied it; the proxy does not wait.
   *
   * @param key the key of the identity
   */
  void delete(VerifiedKey key);
}
