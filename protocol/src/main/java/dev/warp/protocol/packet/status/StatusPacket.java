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
package dev.warp.protocol.packet.status;

import dev.warp.protocol.packet.Packet;

/**
 * Packets in the {@link dev.warp.protocol.ProtocolState#STATUS STATUS} state.
 *
 * <p>The status state handles server list ping and MOTD exchange. All four packets have been
 * unchanged since Minecraft 1.7.2.
 */
public sealed interface StatusPacket extends Packet
    permits StatusRequest, StatusResponse, PingRequest, PongResponse {}
