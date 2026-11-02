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
package dev.warp.protocol.legacy;

import dev.warp.protocol.packet.Packet;

/**
 * The start of a login from a client of Minecraft 1.6 or older ({@code 0x02}), which no supported
 * version speaks. Answered with a {@link LegacyDisconnect} the client can show, then the connection
 * closes.
 */
public record LegacyLogin() implements Packet {}
