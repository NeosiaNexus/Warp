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
 * Marker interface for all Minecraft packets the proxy understands.
 *
 * <p>This interface is intentionally <em>not</em> sealed because Java requires sealed types to
 * permit only classes in the same package when no {@code module-info.java} is present. Each
 * protocol state defines its own sealed sub-interface in a dedicated sub-package:
 *
 * <ul>
 *   <li>{@link dev.warp.protocol.packet.handshake.HandshakePacket HandshakePacket}
 *   <li>{@link dev.warp.protocol.packet.status.StatusPacket StatusPacket}
 *   <li>{@link dev.warp.protocol.packet.login.LoginPacket LoginPacket}
 *   <li>{@link dev.warp.protocol.packet.config.ConfigPacket ConfigPacket}
 *   <li>{@link dev.warp.protocol.packet.play.PlayPacket PlayPacket}
 * </ul>
 *
 * <p>Those sealed interfaces guarantee exhaustive pattern matching within each state, while this
 * marker provides a common type for generic pipeline handling.
 */
public interface Packet {}
