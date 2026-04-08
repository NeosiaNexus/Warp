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
package dev.warp.proxy.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Immutable proxy configuration loaded from {@code warp.conf} (HOCON format).
 *
 * <p>On first run, a default config file and a random forwarding secret file are generated. The
 * forwarding secret can be overridden by the {@code WARP_FORWARDING_SECRET} environment variable.
 *
 * <p>All values are validated eagerly at load time — if something is wrong, the proxy fails fast
 * with a clear error message rather than crashing at runtime.
 */
public final class WarpConfig {

  private static final Logger logger = LoggerFactory.getLogger(WarpConfig.class);

  private static final String CONFIG_FILE = "warp.conf";
  private static final String ENV_FORWARDING_SECRET = "WARP_FORWARDING_SECRET";

  // ---------------------------------------------------------------------------
  // Default values
  // ---------------------------------------------------------------------------

  private static final String DEFAULT_BIND = "0.0.0.0:25577";
  private static final boolean DEFAULT_ONLINE_MODE = true;
  private static final String DEFAULT_BACKEND_ADDRESS = "localhost:25565";
  private static final String DEFAULT_FORWARDING_MODE = "velocity";
  private static final String DEFAULT_SECRET_FILE = "forwarding.secret";
  private static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
  private static final int DEFAULT_COMPRESSION_LEVEL = -1;

  /** Characters used for auto-generated forwarding secret. */
  private static final String SECRET_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private static final int SECRET_LENGTH = 12;

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  private final InetSocketAddress bind;
  private final boolean onlineMode;
  private final InetSocketAddress backendAddress;
  private final ForwardingMode forwardingMode;
  private final byte[] forwardingSecret;
  private final int compressionThreshold;
  private final int compressionLevel;

  // ---------------------------------------------------------------------------
  // Constructor (private — use load())
  // ---------------------------------------------------------------------------

  private WarpConfig(
      InetSocketAddress bind,
      boolean onlineMode,
      InetSocketAddress backendAddress,
      ForwardingMode forwardingMode,
      byte[] forwardingSecret,
      int compressionThreshold,
      int compressionLevel) {
    this.bind = bind;
    this.onlineMode = onlineMode;
    this.backendAddress = backendAddress;
    this.forwardingMode = forwardingMode;
    this.forwardingSecret = forwardingSecret;
    this.compressionThreshold = compressionThreshold;
    this.compressionLevel = compressionLevel;
  }

  // ---------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------

  /**
   * Loads configuration from the given directory.
   *
   * <p>If {@code warp.conf} does not exist, a default file is created. If the forwarding secret
   * file does not exist, a random secret is generated.
   *
   * @param directory the proxy's working directory
   * @return the loaded and validated configuration
   * @throws ConfigurationException if the configuration is invalid or cannot be read
   */
  public static WarpConfig load(Path directory) throws ConfigurationException {
    Path configPath = directory.resolve(CONFIG_FILE);

    if (!Files.exists(configPath)) {
      writeDefaultConfig(configPath);
      logger.info("Created default configuration file: {}", configPath);
    }

    HoconConfigurationLoader loader = HoconConfigurationLoader.builder().path(configPath).build();

    CommentedConfigurationNode root;
    try {
      root = loader.load();
    } catch (IOException e) {
      throw new ConfigurationException("Failed to read " + configPath, e);
    }

    // ---------------------------------------------------------------------------
    // Parse
    // ---------------------------------------------------------------------------

    InetSocketAddress bind = parseAddress(root.node("bind").getString(DEFAULT_BIND), "bind");

    boolean onlineMode = root.node("online-mode").getBoolean(DEFAULT_ONLINE_MODE);

    InetSocketAddress backendAddress =
        parseAddress(
            root.node("backend", "address").getString(DEFAULT_BACKEND_ADDRESS), "backend.address");

    String modeStr = root.node("forwarding", "mode").getString(DEFAULT_FORWARDING_MODE);
    ForwardingMode forwardingMode = parseForwardingMode(modeStr);

    String secretFile = root.node("forwarding", "secret-file").getString(DEFAULT_SECRET_FILE);
    byte[] forwardingSecret = loadForwardingSecret(directory, secretFile, forwardingMode);

    int compressionThreshold =
        root.node("compression", "threshold").getInt(DEFAULT_COMPRESSION_THRESHOLD);
    int compressionLevel = root.node("compression", "level").getInt(DEFAULT_COMPRESSION_LEVEL);

    // ---------------------------------------------------------------------------
    // Validate
    // ---------------------------------------------------------------------------

    if (compressionThreshold < -1) {
      throw new ConfigurationException(
          "compression.threshold must be >= -1, got " + compressionThreshold);
    }
    if (compressionLevel < -1 || compressionLevel > 9) {
      throw new ConfigurationException(
          "compression.level must be -1 to 9, got " + compressionLevel);
    }

    WarpConfig config =
        new WarpConfig(
            bind,
            onlineMode,
            backendAddress,
            forwardingMode,
            forwardingSecret,
            compressionThreshold,
            compressionLevel);

    logConfig(config);
    return config;
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the address and port to listen on.
   *
   * @return the bind address
   */
  public InetSocketAddress bind() {
    return bind;
  }

  /**
   * Returns whether to authenticate players with Mojang.
   *
   * @return {@code true} if online mode is enabled
   */
  public boolean onlineMode() {
    return onlineMode;
  }

  /**
   * Returns the backend server address.
   *
   * @return the backend address
   */
  public InetSocketAddress backendAddress() {
    return backendAddress;
  }

  /**
   * Returns the player info forwarding mode.
   *
   * @return the forwarding mode
   */
  public ForwardingMode forwardingMode() {
    return forwardingMode;
  }

  /**
   * Returns the forwarding secret as raw bytes.
   *
   * @return the forwarding secret
   */
  public byte[] forwardingSecret() {
    return forwardingSecret;
  }

  /**
   * Returns the compression threshold in bytes, or {@code -1} to disable.
   *
   * @return the compression threshold
   */
  public int compressionThreshold() {
    return compressionThreshold;
  }

  /**
   * Returns the zlib compression level (0-9, {@code -1} for default).
   *
   * @return the compression level
   */
  public int compressionLevel() {
    return compressionLevel;
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private static InetSocketAddress parseAddress(String value, String field)
      throws ConfigurationException {
    int lastColon = value.lastIndexOf(':');
    if (lastColon <= 0 || lastColon == value.length() - 1) {
      throw new ConfigurationException(field + " must be host:port, got '" + value + "'");
    }
    String host = value.substring(0, lastColon);
    int port;
    try {
      port = Integer.parseInt(value.substring(lastColon + 1));
    } catch (NumberFormatException e) {
      throw new ConfigurationException(field + " has invalid port in '" + value + "'");
    }
    if (port < 1 || port > 65535) {
      throw new ConfigurationException(field + " port must be 1-65535, got " + port);
    }
    return new InetSocketAddress(host, port);
  }

  private static ForwardingMode parseForwardingMode(String value) throws ConfigurationException {
    try {
      return ForwardingMode.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException(
          "forwarding.mode must be one of: none, velocity — got '" + value + "'");
    }
  }

  private static byte[] loadForwardingSecret(
      Path directory, String secretFileName, ForwardingMode mode) throws ConfigurationException {
    // Environment variable takes precedence.
    @Nullable String envSecret = System.getenv(ENV_FORWARDING_SECRET);
    if (envSecret != null && !envSecret.isBlank()) {
      logger.info("Using forwarding secret from {} environment variable", ENV_FORWARDING_SECRET);
      return envSecret.trim().getBytes(StandardCharsets.UTF_8);
    }

    if (mode == ForwardingMode.NONE) {
      return new byte[0];
    }

    Path secretPath = directory.resolve(secretFileName);
    if (!Files.exists(secretPath)) {
      String generated = generateSecret();
      try {
        Files.writeString(secretPath, generated, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new ConfigurationException("Failed to write forwarding secret to " + secretPath, e);
      }
      logger.info("Generated random forwarding secret: {}", secretPath);
    }

    String secret;
    try {
      secret = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new ConfigurationException("Failed to read forwarding secret from " + secretPath, e);
    }

    if (secret.isEmpty()) {
      throw new ConfigurationException(
          "Forwarding secret file is empty: "
              + secretPath
              + " — generate a secret or set "
              + ENV_FORWARDING_SECRET);
    }

    return secret.getBytes(StandardCharsets.UTF_8);
  }

  private static String generateSecret() {
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder(SECRET_LENGTH);
    for (int i = 0; i < SECRET_LENGTH; i++) {
      sb.append(SECRET_CHARS.charAt(random.nextInt(SECRET_CHARS.length())));
    }
    return sb.toString();
  }

  private static void writeDefaultConfig(Path path) throws ConfigurationException {
    String content =
        """
        # Warp Proxy Configuration

        # Address to listen on.
        bind = "0.0.0.0:25577"

        # Authenticate players with Mojang session servers.
        # Disable only for development or offline-mode networks.
        online-mode = true

        # The backend Minecraft server to connect players to.
        backend {
          address = "localhost:25565"
        }

        # How the proxy forwards player identity to the backend.
        # The backend must be configured to accept this forwarding mode.
        forwarding {
          # none     — no forwarding (backend sees proxy IP, generates offline UUID)
          # velocity — Velocity modern forwarding via login plugin channel (HMAC-SHA256 signed)
          mode = "velocity"

          # Path to the file containing the shared HMAC secret (relative to proxy directory).
          # Auto-generated on first run. Override with WARP_FORWARDING_SECRET env var.
          secret-file = "forwarding.secret"
        }

        # Packet compression settings (applied after login).
        compression {
          # Packets >= this size (bytes) are compressed. Set to -1 to disable.
          threshold = 256

          # Zlib compression level: 0 (none) to 9 (best). -1 for default (level 6).
          level = -1
        }
        """;

    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ConfigurationException("Failed to write default config to " + path, e);
    }
  }

  private static void logConfig(WarpConfig config) {
    logger.info("Configuration loaded:");
    logger.info("  bind: {}", config.bind);
    logger.info("  online-mode: {}", config.onlineMode);
    logger.info("  backend: {}", config.backendAddress);
    logger.info("  forwarding: {}", config.forwardingMode.name().toLowerCase(Locale.ROOT));
    logger.info(
        "  compression: threshold={}, level={}",
        config.compressionThreshold,
        config.compressionLevel);
  }
}
