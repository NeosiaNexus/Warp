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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.packet.Packet;

/**
 * Proxy-critical packets in the {@link dev.warp.protocol.ProtocolState#PLAY PLAY} state.
 *
 * <p>Only the packet types the proxy inspects or sends are defined here. The remaining 500+
 * play-state packets are blind-forwarded as raw {@code ByteBuf} — never deserialized, never
 * touching the Java heap.
 */
public sealed interface PlayPacket extends Packet
    permits PlayDisconnect,
        KeepAlive,
        PlayPluginMessage,
        Transfer,
        StartConfiguration,
        AcknowledgeConfiguration,
        SystemChatMessage,
        JoinGame,
        Respawn,
        BundleDelimiter,
        TabCompleteRequest,
        TabCompleteResponse,
        ChatCommand,
        ResourcePackResponse,
        PlayClientSettings,
        ChatMessage,
        BossBar,
        PlayerInfo,
        PlayerInfoUpdate,
        PlayerInfoRemove,
        TabListHeaderFooter,
        ClearTitles {}
