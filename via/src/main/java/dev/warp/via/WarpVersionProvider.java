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
package dev.warp.via;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;

/**
 * Tells ViaVersion which version the backend of a connection runs, when the handshake goes through
 * its handlers: the version {@link BackendTranslation} decided when it installed them.
 *
 * <p>ViaVersion's Velocity platform finds the server by reflection on Velocity's connection ({@code
 * MinecraftConnection#getAssociation}) and asks its detector again at that moment; here the
 * decision travels with the connection, so the handshake can never announce another version than
 * the one translation was set up for.
 */
final class WarpVersionProvider implements VersionProvider {

  @Override
  public ProtocolVersion getClosestServerProtocol(UserConnection connection) {
    ProtocolVersion target = BackendTranslation.targetOf(connection);
    // Only connections this plugin set up get ViaVersion's handlers; anything else is passed on.
    return target != null ? target : connection.getProtocolInfo().protocolVersion();
  }
}
