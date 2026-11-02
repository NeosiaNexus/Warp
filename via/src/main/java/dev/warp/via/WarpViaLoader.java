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

import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscribe;
import dev.warp.api.event.Subscription;
import dev.warp.api.event.player.KickedFromServerEvent;
import dev.warp.api.event.player.PreLoginEvent;
import dev.warp.api.event.player.ServerPostConnectEvent;
import dev.warp.api.event.player.ServerPreConnectEvent;
import dev.warp.api.server.RegisteredServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.platform.PlatformTask;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import com.viaversion.viaversion.util.ChatColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Wires ViaVersion to Warp's events once ViaVersion is loaded: the version provider, version
 * detection before connections, connection details after them, blocked versions, and the periodic
 * probe of the {@code auto} servers.
 */
final class WarpViaLoader implements ViaPlatformLoader {

  private final EventBus events;
  private final BackendVersions versions;
  private final ServerVersions settings;
  private final ConnectionDetailsSender details;
  private final Logger logger;
  private final List<Subscription> subscriptions = new ArrayList<>();
  private @Nullable PlatformTask<?> probeTask;

  /**
   * Creates the loader.
   *
   * @param events the plugin's event bus
   * @param versions the backend versions
   * @param settings the backend version settings
   * @param details the connection details sender
   * @param logger the plugin's logger
   */
  WarpViaLoader(
      EventBus events,
      BackendVersions versions,
      ServerVersions settings,
      ConnectionDetailsSender details,
      Logger logger) {
    this.events = events;
    this.versions = versions;
    this.settings = settings;
    this.details = details;
    this.logger = logger;
  }

  @Override
  public void load() {
    Via.getManager().getProviders().use(VersionProvider.class, new WarpVersionProvider());

    // Last, so that the server it pings is the one the other plugins settled on. Bounded by the
    // detection time-out: a connection never waits longer than that for a version.
    int timeoutSeconds = Math.ceilDiv(settings.detectionTimeoutMillis(), 1000) + 1;
    subscriptions.add(
        events.subscribeAsync(
            ServerPreConnectEvent.class, Subscribe.LATE - 1, timeoutSeconds, this::detectFirst));
    subscriptions.add(
        events.subscribe(ServerPostConnectEvent.class, Subscribe.LATE, details::serverReached));
    subscriptions.add(
        events.subscribe(KickedFromServerEvent.class, Subscribe.EARLY, this::checkAgain));
    subscriptions.add(
        events.subscribe(PreLoginEvent.class, Subscribe.EARLY, this::refuseBlockedVersions));

    versions.probeAllServers();
    int interval = settings.detectionIntervalSeconds();
    if (interval > 0) {
      probeTask = Via.getPlatform().runRepeatingAsync(versions::probeAllServers, interval * 20L);
    }
  }

  @Override
  public void unload() {
    PlatformTask<?> task = probeTask;
    if (task != null) {
      task.cancel();
      probeTask = null;
    }
    subscriptions.forEach(Subscription::unsubscribe);
    subscriptions.clear();
  }

  /** Pings the target of a connection whose version was never detected, before it connects. */
  private CompletableFuture<?> detectFirst(ServerPreConnectEvent event) {
    if (event.result() instanceof ServerPreConnectEvent.Allowed(RegisteredServer server)) {
      return versions.ensureKnown(server);
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Checks the version of a server that refused a player during its login again: it may have
   * restarted with another version, which the next attempt then uses.
   */
  private void checkAgain(KickedFromServerEvent event) {
    if (event.kickedDuringServerConnect()
        && event.cause() == KickedFromServerEvent.Cause.KICKED
        && !versions.isOff(event.server().name())) {
      var _ = versions.probe(event.server());
    }
  }

  /** Refuses the versions ViaVersion's {@code blocked-versions} lists, as ViaVersion does. */
  private void refuseBlockedVersions(PreLoginEvent event) {
    ViaVersionConfig config = Via.getConfig();
    ProtocolVersion version = ProtocolVersion.getProtocol(event.connection().protocolVersion());
    if (!config.blockedProtocolVersions().contains(version)) {
      return;
    }
    if (config.logBlockedJoins()) {
      logger.info("Refused {} on blocked version {}", event.username(), version.getName());
    }
    event.setResult(
        new PreLoginEvent.Denied(
            LegacyComponentSerializer.legacySection()
                .deserialize(
                    ChatColorUtil.translateAlternateColorCodes(config.getBlockedDisconnectMsg()))));
  }
}
