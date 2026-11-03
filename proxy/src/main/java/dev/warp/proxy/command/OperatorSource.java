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
package dev.warp.proxy.command;

import dev.warp.api.command.CommandSource;

/**
 * A command source that is the proxy's operator rather than a player: the console, or a session of
 * the remote console holding a token that may run commands. Commands meant for the operator only
 * ({@code /log level}) accept these sources, and never a player, whatever a permission plugin
 * grants players.
 */
public interface OperatorSource extends CommandSource {}
