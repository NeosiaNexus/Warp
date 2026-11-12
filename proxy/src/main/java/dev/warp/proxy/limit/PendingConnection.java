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

import org.jspecify.annotations.Nullable;

/**
 * A connection that is not logged in, as {@link PendingConnections} sees it: a node of the list its
 * event loop keeps of the connections it may close to make room for a new one. The links live in
 * the connection's own watcher, which extends this class, so that tracking a connection allocates
 * nothing.
 *
 * <p>Every field is read and written on the connection's event loop only.
 */
public abstract class PendingConnection {

  /** The event loop's lists the connection was tracked on, or {@code null} if it never was. */
  PendingConnections.@Nullable Loop home;

  /** The list the connection is in, or {@code null} while nothing may close it. */
  PendingConnections.@Nullable Fifo queue;

  /** The neighbours in {@link #queue}, towards the oldest and the newest. */
  @Nullable PendingConnection older;

  @Nullable PendingConnection newer;

  /** When the connection last made progress, on the clock of {@link PendingConnections}. */
  long since;

  /** Creates a connection nothing tracks yet. */
  protected PendingConnection() {}

  /**
   * Closes the connection to make room for a new one. Called on the connection's event loop, once,
   * after it left the lists. The implementation must give the connection's place back ({@link
   * PendingConnections#end()}, exactly once over the connection's life, as for any other close) and
   * close it without blocking.
   */
  protected abstract void evicted();
}
