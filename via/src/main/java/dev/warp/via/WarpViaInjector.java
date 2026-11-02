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

import dev.warp.api.event.Subscription;
import dev.warp.api.network.NetworkPipelines;

import java.util.SortedSet;
import java.util.TreeSet;

import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import com.viaversion.viaversion.libs.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * Hooks ViaVersion into the proxy through Warp's network pipeline API: {@link #inject()} registers
 * a backend initializer, {@link #uninject()} closes it. No reflection, no replaced channel
 * initializer, and the handlers never have to be moved when compression starts (ViaVersion#2546).
 */
final class WarpViaInjector implements ViaInjector {

  private final String pluginId;
  private final NetworkPipelines network;
  private final BackendTranslation translation;
  private @Nullable Subscription subscription;

  /**
   * Creates the injector.
   *
   * @param pluginId this plugin's identifier, which prefixes the names of its handlers
   * @param network the plugin's pipeline registrar
   * @param translation the backend initializer
   */
  WarpViaInjector(String pluginId, NetworkPipelines network, BackendTranslation translation) {
    this.pluginId = pluginId;
    this.network = network;
    this.translation = translation;
  }

  @Override
  public void inject() {
    if (subscription == null) {
      subscription = network.initializeBackends(translation);
    }
  }

  @Override
  public void uninject() {
    Subscription registered = subscription;
    if (registered != null) {
      registered.unsubscribe();
      subscription = null;
    }
  }

  @Override
  public boolean lateProtocolVersionSetting() {
    return false;
  }

  @Override
  public ProtocolVersion getServerProtocolVersion() {
    return getServerProtocolVersions().first();
  }

  /**
   * {@inheritDoc}
   *
   * <p>The versions the proxy speaks natively, which are those a player connects with: every
   * release version from 1.7.2 on.
   */
  @Override
  public SortedSet<ProtocolVersion> getServerProtocolVersions() {
    SortedSet<ProtocolVersion> versions = new TreeSet<>();
    for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
      if (version.getVersionType() == VersionType.RELEASE
          && version.newerThanOrEqualTo(ProtocolVersion.v1_7_2)) {
        versions.add(version);
      }
    }
    return versions;
  }

  @Override
  public String getEncoderName() {
    return pluginId + ":" + BackendTranslation.ENCODER;
  }

  @Override
  public String getDecoderName() {
    return pluginId + ":" + BackendTranslation.DECODER;
  }

  @Override
  public JsonObject getDump() {
    JsonObject dump = new JsonObject();
    dump.addProperty("injection", "Warp network pipeline API, backend connections only");
    dump.addProperty("injected", subscription != null && subscription.isActive());
    return dump;
  }
}
