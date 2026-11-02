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
package dev.warp.protocol.command;

/**
 * What {@link CommandTree#merge} does with a command whose name the backend's tree already has at
 * its top level.
 */
public enum NameConflict {

  /**
   * The proxy's command replaces the backend's: the backend's node leaves the root, and the client
   * completes the proxy's syntax. The default for commands the proxy runs itself, since the client
   * must sign exactly what the proxy expects (a backend {@code /msg} left in place has the client
   * sign arguments the proxy then consumes: SpigotMC/BungeeCord#3495), and must complete the
   * proxy's syntax (BungeeCord#3938).
   *
   * <p>Only the root's edge to the backend node goes: backend nodes that redirect to it, vanilla's
   * {@code /tell} and {@code /w} to {@code /msg}, keep it as their target, since the backend still
   * runs those aliases.
   */
  PROXY_WINS,

  /**
   * The backend's command stays, and the proxy's is dropped: for proxy commands that only fill in
   * for servers lacking one.
   */
  BACKEND_WINS,

  /**
   * Both trees merge, as Brigadier merges a node into a parent that already has a child of that
   * name: the backend's node stays where it is, becomes executable if the proxy's is, and takes the
   * proxy's children, merging those whose name it already has. Its type, parser, redirect and
   * suggestions stay the backend's.
   */
  MERGE
}
