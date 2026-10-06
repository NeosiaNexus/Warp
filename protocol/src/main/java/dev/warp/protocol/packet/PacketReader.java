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
package dev.warp.protocol.packet;

/**
 * How the proxy reads a packet it receives, as registered in a {@link PacketRegistry}: either
 * decoded into a {@link Packet} that replaces the frame ({@link PacketCodec}), or watched in place
 * while the frame itself is forwarded untouched ({@link PacketWatch}). A packet with no reader is
 * forwarded without being read at all.
 */
public sealed interface PacketReader permits PacketCodec, PacketWatch {}
