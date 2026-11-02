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

import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerRegistry;
import dev.warp.via.ServerVersions.Auto;
import dev.warp.via.ServerVersions.Off;
import dev.warp.via.ServerVersions.Pinned;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.viaversion.viaversion.api.platform.ProtocolDetectorService;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.fastutil.objects.Object2IntMap;
import com.viaversion.viaversion.libs.fastutil.objects.Object2IntOpenHashMap;
import org.slf4j.Logger;

/**
 * The version each backend server runs: set in {@code servers.yml}, or detected by status ping.
 *
 * <p>Where ViaVersion's Velocity platform differs, and why:
 *
 * <ul>
 *   <li>A version set by the administrator wins over a detected one; detection never rewrites the
 *       settings (ViaVersion#4482).
 *   <li>A server whose version is not known yet is pinged before a player connects to it ({@link
 *       #ensureKnown}), instead of being assumed to run the proxy's lowest version until the next
 *       periodic probe (ViaVersion#4438, #2394). A server that cannot be asked is never guessed:
 *       its version stays unknown, and players connect to it untranslated.
 *   <li>The ping announces no protocol version (-1), so that a backend running ViaVersion itself
 *       answers with its own version instead of echoing the player's (ViaVersion#1064, #915).
 *   <li>A server that rejected a player during its login is checked again at once, in case it
 *       restarted with another version.
 * </ul>
 *
 * <p>Thread-safe: read from event loops while players connect, written from ping answers.
 */
final class BackendVersions implements ProtocolDetectorService {

  /** The protocol the detection ping announces: none, so that the server answers with its own. */
  static final int UNKNOWN_PROTOCOL = -1;

  private final ServerRegistry servers;
  private final ServerVersions settings;
  private final Logger logger;

  /** Detected protocol numbers, by lowercase server name. */
  private final Map<String, Integer> detected = new ConcurrentHashMap<>();

  /** Pings in flight, by lowercase server name, so that a crowd joining triggers only one. */
  private final Map<String, CompletableFuture<ProtocolVersion>> probes = new ConcurrentHashMap<>();

  /**
   * Creates the service.
   *
   * @param servers the proxy's servers
   * @param settings the versions the administrator set
   * @param logger where detections are reported
   */
  BackendVersions(ServerRegistry servers, ServerVersions settings, Logger logger) {
    this.servers = servers;
    this.settings = settings;
    this.logger = logger;
  }

  // ---------------------------------------------------------------------------
  // Lookup
  // ---------------------------------------------------------------------------

  @Override
  public ProtocolVersion serverProtocolVersion(String serverName) {
    return switch (settings.mode(serverName)) {
      case Pinned(ProtocolVersion version) -> version;
      case Off() -> ProtocolVersion.unknown;
      case Auto() -> {
        Integer protocol = detected.get(key(serverName));
        yield protocol != null ? ProtocolVersion.getProtocol(protocol) : ProtocolVersion.unknown;
      }
    };
  }

  /**
   * Returns whether translation is turned off for a server.
   *
   * @param serverName the server's name
   * @return {@code true} if its mode is {@code off}
   */
  boolean isOff(String serverName) {
    return settings.mode(serverName) instanceof Off;
  }

  // ---------------------------------------------------------------------------
  // Detection
  // ---------------------------------------------------------------------------

  /**
   * Makes sure the version of a server is known before a player connects to it: pings it if it is
   * detected and was never answered.
   *
   * @param server the server
   * @return completed once its version is known, or once the time-out expired; never exceptionally
   */
  CompletableFuture<ProtocolVersion> ensureKnown(RegisteredServer server) {
    if (!(settings.mode(server.name()) instanceof Auto)
        || detected.containsKey(key(server.name()))) {
      return CompletableFuture.completedFuture(serverProtocolVersion(server.name()));
    }
    return probe(server);
  }

  /**
   * Asks a server for its version, unless a ping to it is already in flight.
   *
   * @param server the server
   * @return completed with the version it answered, or {@link ProtocolVersion#unknown} if it did
   *     not answer in time; never exceptionally
   */
  CompletableFuture<ProtocolVersion> probe(RegisteredServer server) {
    String key = key(server.name());
    CompletableFuture<ProtocolVersion> running = probes.get(key);
    if (running != null) {
      return running;
    }
    CompletableFuture<ProtocolVersion> probe = new CompletableFuture<>();
    CompletableFuture<ProtocolVersion> raced = probes.putIfAbsent(key, probe);
    if (raced != null) {
      return raced;
    }
    var _ =
        server
            .ping(UNKNOWN_PROTOCOL)
            .orTimeout(settings.detectionTimeoutMillis(), TimeUnit.MILLISECONDS)
            .whenComplete(
                (ping, error) -> {
                  ProtocolVersion version = ProtocolVersion.unknown;
                  if (error != null) {
                    logger.debug(
                        "Could not ask {} for its version: {}", server.name(), error.toString());
                  } else {
                    version =
                        record(server.name(), ping.version().protocol(), ping.version().name());
                  }
                  probes.remove(key, probe);
                  probe.complete(version);
                });
    return probe;
  }

  /**
   * Records the protocol a server answered.
   *
   * @param serverName the server's name
   * @param protocol the protocol number it announced
   * @param name the version name it announced, for the log
   * @return the version, or {@link ProtocolVersion#unknown} if the answer is unusable
   */
  ProtocolVersion record(String serverName, int protocol, String name) {
    if (protocol < 0) {
      logger.warn(
          "Server {} does not tell its version (it answered {}): set it in servers.yml, or"
              + " players of other versions will not be translated for it",
          serverName,
          protocol);
      return ProtocolVersion.unknown;
    }
    ProtocolVersion version = ProtocolVersion.getProtocol(protocol);
    Integer previous = detected.put(key(serverName), protocol);
    if (previous == null || previous != protocol) {
      logger.info(
          "Server {} runs {} (protocol {}){}",
          serverName,
          version.isKnown() ? version.getName() : name,
          protocol,
          previous == null ? "" : ", was protocol " + previous);
    }
    return version;
  }

  @Override
  public void probeAllServers() {
    Set<String> registered = new HashSet<>();
    for (RegisteredServer server : servers.servers()) {
      registered.add(key(server.name()));
      if (settings.mode(server.name()) instanceof Auto) {
        var _ = probe(server);
      }
    }
    // Forget the servers that were unregistered.
    detected.keySet().retainAll(registered);
  }

  @Override
  public void setProtocolVersion(String serverName, int protocolVersion) {
    detected.put(key(serverName), protocolVersion);
  }

  @Override
  public int uncacheProtocolVersion(String serverName) {
    Integer previous = detected.remove(key(serverName));
    return previous != null ? previous : -1;
  }

  @Override
  public Object2IntMap<String> detectedProtocolVersions() {
    // A copy: ViaVersion ships a trimmed fastutil, without the unmodifiable wrappers.
    Object2IntOpenHashMap<String> copy = new Object2IntOpenHashMap<>(detected);
    copy.defaultReturnValue(-1);
    return copy;
  }

  private static String key(String serverName) {
    return serverName.toLowerCase(Locale.ROOT);
  }
}
