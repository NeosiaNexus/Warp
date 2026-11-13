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
package dev.warp.proxy.bedrock;

import dev.warp.api.player.ClientPlatform.Bedrock;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Who a Bedrock player is, once its Floodgate data was verified: what the proxy logs it in as, and
 * what it tells backends.
 *
 * @param data the data Geyser wrote, kept whole for re-emission to backends running Floodgate
 * @param platform the platform the connection takes
 * @param javaId the UUID the player logs in with: the linked Java account's, else Floodgate's
 *     {@code 00000000-0000-0000-0009-<xuid>}
 * @param javaName the name the player logs in with: the linked Java account's, else the prefixed
 *     gamertag
 * @param realAddress the address the connection takes as the player's, when the data named one
 *     other than the connection's and the Geyser host is trusted to say so; {@code null} to keep
 *     the connection's own
 */
public record BedrockIdentity(
    FloodgateData data,
    Bedrock platform,
    UUID javaId,
    String javaName,
    @Nullable InetSocketAddress realAddress) {}
