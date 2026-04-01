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
package dev.warp.protocol.packet.config;

import dev.warp.protocol.packet.Packet;

/**
 * Packets in the {@link dev.warp.protocol.ProtocolState#CONFIGURATION CONFIGURATION} state.
 *
 * <p>The configuration state was introduced in Minecraft 1.20.2 (protocol 764) for registry
 * synchronisation, resource pack exchange, and known packs negotiation. The proxy must track
 * registry data and tags for replay during server switches.
 */
public sealed interface ConfigPacket extends Packet
    permits ConfigDisconnect,
        FinishConfiguration,
        AcknowledgeFinishConfiguration,
        ConfigPluginMessage,
        ClientInformation,
        RegistryData,
        KnownPacks,
        ResourcePackPush,
        ServerData {}
