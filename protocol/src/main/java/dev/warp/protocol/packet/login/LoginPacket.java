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
package dev.warp.protocol.packet.login;

import dev.warp.protocol.packet.Packet;

/**
 * Packets in the {@link dev.warp.protocol.ProtocolState#LOGIN LOGIN} state.
 *
 * <p>The login state handles authentication, encryption setup, compression negotiation, and plugin
 * login channels. Several packets have version-dependent codecs, particularly around the 1.19.x
 * signing changes and 1.20.2 configuration state introduction.
 */
public sealed interface LoginPacket extends Packet
    permits LoginDisconnect,
        LoginStart,
        EncryptionRequest,
        EncryptionResponse,
        LoginSuccess,
        SetCompression,
        LoginAcknowledged,
        LoginPluginRequest,
        LoginPluginResponse {}
