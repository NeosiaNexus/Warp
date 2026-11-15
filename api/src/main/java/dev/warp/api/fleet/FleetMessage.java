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
package dev.warp.api.fleet;

/**
 * A message received from the {@link FleetBus}.
 *
 * @param channel the channel it was published on
 * @param senderProxyId the id of the proxy that sent it, possibly this one
 * @param payload the bytes as published; the receiver must not keep or change the array
 */
public record FleetMessage(String channel, String senderProxyId, byte[] payload) {}
