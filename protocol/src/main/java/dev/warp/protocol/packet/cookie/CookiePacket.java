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
package dev.warp.protocol.packet.cookie;

import dev.warp.protocol.packet.Packet;

/**
 * Packets of the client's cookie store, introduced in 1.20.5 (protocol 766).
 *
 * <p>A cookie is a payload of at most {@value CookiePayload#MAX_SIZE} bytes that the client keeps
 * under an identifier for the server that stored it, and carries over to the server a Transfer
 * sends it to. Unlike most packets, these span several states: a server may request a cookie during
 * login, configuration or play, and store one during configuration or play. Each type is registered
 * in every state it exists in, at that state's packet ID.
 */
public sealed interface CookiePacket extends Packet
    permits CookieRequest, CookieResponse, StoreCookie {}
