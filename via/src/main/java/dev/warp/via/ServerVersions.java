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

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import com.viaversion.viaversion.util.Config;
import org.jspecify.annotations.Nullable;

/**
 * The backend versions the administrator set, from {@code servers.yml} in the plugin's data
 * directory: a version per server, {@code auto} to detect it, or {@code off}.
 *
 * <p>Unlike ViaVersion's Velocity platform, a version set here always wins over a detected one, and
 * detection never writes into this file (ViaVersion#4482, ViaVersion#1064).
 */
final class ServerVersions extends Config {

  /** Where the default file, with its comments, is in the plugin jar. */
  static final String DEFAULT_RESOURCE = "assets/warp-via/servers.yml";

  /** What the plugin does for a server. */
  sealed interface Mode {}

  /**
   * The server always runs this version.
   *
   * @param version the version
   */
  record Pinned(ProtocolVersion version) implements Mode {}

  /** The server's version is detected. */
  record Auto() implements Mode {}

  /** The plugin never translates for this server. */
  record Off() implements Mode {}

  private volatile Map<String, Mode> modes = Map.of();
  private volatile Mode fallback = new Auto();

  /**
   * Creates the settings; {@link #reload()} reads them.
   *
   * @param file the file, created with the defaults if missing
   * @param logger where invalid entries are reported
   */
  ServerVersions(File file, Logger logger) {
    super(file, logger);
  }

  @Override
  public URL getDefaultConfigURL() {
    return Objects.requireNonNull(
        ServerVersions.class.getClassLoader().getResource(DEFAULT_RESOURCE), DEFAULT_RESOURCE);
  }

  @Override
  public InputStream getDefaultConfigInputStream() {
    return Objects.requireNonNull(
        ServerVersions.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE),
        DEFAULT_RESOURCE);
  }

  @Override
  public Set<String> getSectionsWithModifiableKeys() {
    return Set.of("servers");
  }

  @Override
  public void reload() {
    super.reload();
    Map<String, Mode> parsed = new HashMap<>();
    Map<String, Object> servers = get("servers", Map.of());
    if (servers != null) {
      for (Map.Entry<String, Object> entry : servers.entrySet()) {
        parsed.put(
            entry.getKey().toLowerCase(Locale.ROOT), parse(entry.getKey(), entry.getValue()));
      }
    }
    modes = Map.copyOf(parsed);
    fallback = parse("default", get("default", "auto"));
  }

  /**
   * Returns what the plugin does for a server.
   *
   * @param server the server's name, in any case
   * @return its mode: its own entry, or the default
   */
  Mode mode(String server) {
    Mode mode = modes.get(server.toLowerCase(Locale.ROOT));
    return mode != null ? mode : fallback;
  }

  /**
   * Returns how often the {@code auto} servers are checked again.
   *
   * @return the interval in seconds, 0 for never
   */
  int detectionIntervalSeconds() {
    return Math.max(0, getInt("detection-interval", 60));
  }

  /**
   * Returns how long a connection waits for a server whose version is not known yet.
   *
   * @return the time-out in milliseconds, at least 100
   */
  int detectionTimeoutMillis() {
    return Math.max(100, getInt("detection-timeout", 3000));
  }

  /**
   * Reads one entry: a version name, a protocol number, {@code auto} or {@code off}.
   *
   * @param key the entry's key, for the warning
   * @param value the entry's value
   * @return the mode; {@code auto}, with a warning, for a value that is none of them
   */
  Mode parse(String key, @Nullable Object value) {
    switch (value) {
      case null -> {
        return new Auto();
      }
      case Integer protocol when ProtocolVersion.isRegistered(VersionType.RELEASE, protocol) -> {
        return new Pinned(ProtocolVersion.getProtocol(VersionType.RELEASE, protocol));
      }
      case String text when text.equalsIgnoreCase("auto") -> {
        return new Auto();
      }
      case String text when text.equalsIgnoreCase("off") -> {
        return new Off();
      }
      case Boolean on when !on -> {
        // YAML 1.1 reads an unquoted off (or no, false) as false.
        return new Off();
      }
      case String text -> {
        ProtocolVersion version = ProtocolVersion.getClosest(text);
        if (version != null && version.isKnown()) {
          return new Pinned(version);
        }
      }
      default -> {
        // reported below
      }
    }
    logger.warning(
        "servers.yml: '"
            + key
            + "' is set to '"
            + value
            + "', which is neither a Minecraft version, a protocol number, auto nor off:"
            + " detecting its version instead");
    return new Auto();
  }
}
