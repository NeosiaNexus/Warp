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

import dev.warp.api.messaging.Messaging;
import dev.warp.api.plugin.Capability;
import dev.warp.api.plugin.Plugin;
import dev.warp.api.plugin.PluginContext;
import dev.warp.api.plugin.WarpPlugin;

import java.io.File;
import java.util.Set;

import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.util.VersionInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * ViaVersion and ViaBackwards on Warp: players of other versions than their backend's join it.
 *
 * <p>The plugin declares the network pipeline capability: ViaVersion's handlers go on the backend
 * connections that need translation through Warp's pipeline API. It reads three files in its data
 * directory: {@code config.yml} (ViaVersion's settings), {@code viabackwards.yml} (ViaBackwards')
 * and {@code servers.yml} (each backend's version).
 */
@Plugin(
    id = WarpVia.ID,
    name = "ViaVersion",
    version = VersionInfo.VERSION,
    description =
        "Lets players of other Minecraft versions join each backend: ViaVersion and ViaBackwards"
            + " on Warp",
    url = "https://viaversion.com",
    authors = {
      "_MylesC",
      "creeper123123321",
      "Gerrygames",
      "kennytv",
      "Matsv",
      "EnZaXD",
      "RK_01",
      "ForceUpdate1",
      "Warp Contributors"
    },
    capabilities = Capability.NETWORK_PIPELINE)
public final class WarpVia implements WarpPlugin {

  /** The plugin's identifier: other plugins depend on it to use ViaVersion's API. */
  static final String ID = "viaversion";

  private final PluginContext context;
  private @Nullable ViaManagerImpl manager;

  /**
   * Creates the plugin.
   *
   * @param context the plugin's context
   */
  public WarpVia(PluginContext context) {
    this.context = context;
  }

  @Override
  public void enable() {
    Logger logger = context.logger();
    JulLogger viaLogger = new JulLogger(logger);
    File dataFolder = context.dataDirectory().toFile();

    ServerVersions settings = new ServerVersions(new File(dataFolder, "servers.yml"), viaLogger);
    settings.reload();
    BackendVersions versions = new BackendVersions(context.proxy().servers(), settings, logger);
    WarpViaConfig config = new WarpViaConfig(dataFolder, viaLogger);
    WarpViaPlatform platform = new WarpViaPlatform(context, viaLogger, config, versions);
    ViaCommand command = new ViaCommand(versions);
    BackendTranslation translation =
        new BackendTranslation(versions, () -> Via.getManager().getProtocolManager(), logger);

    ViaManagerImpl viaManager =
        ViaManagerImpl.builder()
            .platform(platform)
            .injector(new WarpViaInjector(ID, context.network(), translation))
            .loader(
                new WarpViaLoader(
                    context.events(),
                    versions,
                    settings,
                    new ConnectionDetailsSender(platform, config, versions),
                    logger))
            .commandHandler(command)
            .build();
    Via.init(viaManager);
    this.manager = viaManager;
    config.reload();

    // ViaBackwards registers its protocols once ViaVersion has its own, as on every platform.
    ViaBackwardsHost backwards = new ViaBackwardsHost(viaLogger, dataFolder);
    viaManager.addEnableListener(backwards::initialize);
    viaManager.addPostEnableListener(backwards::enable);

    viaManager.init();
    viaManager.onServerLoaded();
    awaitMappings(viaManager.getProtocolManager(), logger);

    context.messaging().register(ConnectionDetailsSender.CHANNEL, Set.of(Messaging.Origin.SERVER));
    context.commands().register("viaversion", command, "viaver", "vvwarp");
  }

  /**
   * Waits, at startup, for ViaVersion's mappings, which it otherwise loads in the background.
   *
   * <p>The first player translated while they still load would make ViaVersion wait for them on the
   * connection's event loop, stalling every player on that thread. Warp enables plugins before it
   * accepts players, so the wait happens there instead.
   */
  private static void awaitMappings(ProtocolManager protocols, Logger logger) {
    long start = System.nanoTime();
    for (Protocol<?, ?, ?, ?> protocol : protocols.getProtocols()) {
      protocols.completeMappingDataLoading(protocol.getClass());
    }
    var _ = protocols.checkForMappingCompletion();
    logger.info(
        "Loaded the translations of {} protocol versions in {} ms",
        protocols.getProtocols().size(),
        (System.nanoTime() - start) / 1_000_000);
  }

  @Override
  public void disable() {
    ViaManagerImpl viaManager = manager;
    if (viaManager != null) {
      viaManager.destroy();
      manager = null;
    }
  }
}
