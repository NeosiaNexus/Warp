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
package dev.warp.proxy.connection;

import dev.warp.protocol.ProtocolVersion;

import org.jspecify.annotations.Nullable;

/**
 * Why a client opened its connection: the {@code nextState} of its handshake.
 *
 * <p>Recorded on the client's {@link MinecraftConnection} by the handshake, and on the {@link
 * ConnectedPlayer} it becomes, so that what follows can tell a player who arrived by a Transfer
 * from one who connected directly.
 */
public enum HandshakeIntent {

  /** A server list ping: {@code nextState = 1}. */
  STATUS(1),

  /** A login: {@code nextState = 2}. */
  LOGIN(2),

  /**
   * A login after a server sent the client a Transfer packet: {@code nextState = 3}, from 1.20.5.
   * The client carries over the cookies the previous server stored.
   */
  TRANSFER(3);

  private final int id;

  HandshakeIntent(int id) {
    this.id = id;
  }

  /**
   * Returns the {@code nextState} value of this intent on the wire.
   *
   * @return 1, 2 or 3
   */
  public int id() {
    return id;
  }

  /**
   * Returns the intent a handshake declares, or {@code null} for a value no client sends.
   *
   * @param id the handshake's {@code nextState}
   * @return the intent, or {@code null}
   */
  public static @Nullable HandshakeIntent byId(int id) {
    return switch (id) {
      case 1 -> STATUS;
      case 2 -> LOGIN;
      case 3 -> TRANSFER;
      default -> null;
    };
  }

  /**
   * Returns whether a client of {@code version} may declare this intent: transfers arrived in
   * 1.20.5, status and login exist in every version.
   *
   * @param version the protocol version the handshake declares
   * @return {@code false} for a transfer from a client before 1.20.5
   */
  public boolean existsIn(ProtocolVersion version) {
    return this != TRANSFER || version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);
  }
}
