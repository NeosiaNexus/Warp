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

import dev.warp.api.server.Favicon;
import dev.warp.proxy.status.ServerList;
import dev.warp.proxy.status.StatusSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
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
  private static final String DEFAULT_SERVER_NAME = "lobby";
  private static final String DEFAULT_SERVER_ADDRESS = "localhost:25565";
  private static final String DEFAULT_FORWARDING_MODE = "velocity";
  private static final String DEFAULT_SECRET_FILE = "forwarding.secret";
  private static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
  private static final int DEFAULT_COMPRESSION_LEVEL = -1;
  private static final boolean DEFAULT_COMPRESSION_PASSTHROUGH = true;
  private static final String DEFAULT_DUPLICATE_LOGIN = "auto";
  private static final boolean DEFAULT_ACCEPTS_TRANSFERS = false;
  private static final String DEFAULT_FAVICON = "server-icon.png";

  /** How many lines of the message of the day clients show. */
  private static final int MOTD_LINES = 2;

  /**
   * The largest favicon file read: far more than a 64x64 PNG needs, and than a status answer holds
   * once encoded, so that a wrong path cannot load a huge file.
   */
  private static final long MAX_FAVICON_BYTES = 1024 * 1024;

  /** A legacy color code as BungeeCord configurations write them, such as {@code &a}. */
  private static final Pattern LEGACY_CODE = Pattern.compile("[&\\u00a7][0-9a-fk-orA-FK-OR]");

  /** Characters used for auto-generated forwarding secret. */
  private static final String SECRET_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private static final int SECRET_LENGTH = 12;

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  private final InetSocketAddress bind;
  private final boolean onlineMode;
  private final Map<String, InetSocketAddress> servers;
  private final @Nullable String defaultServer;
  private final List<String> fallbackOrder;
  private final DuplicateLoginPolicy duplicateLoginPolicy;
  private final ForwardingMode forwardingMode;
  private final byte[] forwardingSecret;
  private final int compressionThreshold;
  private final int compressionLevel;
  private final boolean compressionPassthrough;
  private final boolean acceptsTransfers;
  private final ManagementConfig management;
  private final DrainConfig drain;
  private final StatusSettings status;

  // ---------------------------------------------------------------------------
  // Constructor (private — use load())
  // ---------------------------------------------------------------------------

  private WarpConfig(
      InetSocketAddress bind,
      boolean onlineMode,
      Map<String, InetSocketAddress> servers,
      @Nullable String defaultServer,
      List<String> fallbackOrder,
      DuplicateLoginPolicy duplicateLoginPolicy,
      ForwardingMode forwardingMode,
      byte[] forwardingSecret,
      int compressionThreshold,
      int compressionLevel,
      boolean compressionPassthrough,
      boolean acceptsTransfers,
      ManagementConfig management,
      DrainConfig drain,
      StatusSettings status) {
    this.bind = bind;
    this.onlineMode = onlineMode;
    this.servers = Map.copyOf(servers);
    this.defaultServer = defaultServer;
    this.fallbackOrder = List.copyOf(fallbackOrder);
    this.duplicateLoginPolicy = duplicateLoginPolicy;
    this.forwardingMode = forwardingMode;
    this.forwardingSecret = forwardingSecret;
    this.compressionThreshold = compressionThreshold;
    this.compressionLevel = compressionLevel;
    this.compressionPassthrough = compressionPassthrough;
    this.acceptsTransfers = acceptsTransfers;
    this.management = management;
    this.drain = drain;
    this.status = status;
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

    // Parse server list.
    Map<String, InetSocketAddress> servers = parseServers(root);
    @Nullable String defaultServer = parseDefaultServer(root, servers);
    List<String> fallbackOrder = parseFallbackOrder(root, servers, defaultServer);

    DuplicateLoginPolicy duplicateLoginPolicy =
        parseDuplicateLoginPolicy(root.node("duplicate-login").getString(DEFAULT_DUPLICATE_LOGIN));

    String modeStr = root.node("forwarding", "mode").getString(DEFAULT_FORWARDING_MODE);
    ForwardingMode forwardingMode = parseForwardingMode(modeStr);

    String secretPath = root.node("forwarding", "secret-file").getString(DEFAULT_SECRET_FILE);
    byte[] forwardingSecret = loadForwardingSecret(directory, secretPath, forwardingMode);

    int compressionThreshold =
        root.node("compression", "threshold").getInt(DEFAULT_COMPRESSION_THRESHOLD);
    int compressionLevel = root.node("compression", "level").getInt(DEFAULT_COMPRESSION_LEVEL);
    boolean compressionPassthrough =
        root.node("compression", "passthrough").getBoolean(DEFAULT_COMPRESSION_PASSTHROUGH);

    boolean acceptsTransfers = root.node("accepts-transfers").getBoolean(DEFAULT_ACCEPTS_TRANSFERS);

    ManagementConfig management = ManagementConfig.parse(root, directory, System::getenv);
    DrainConfig drain = DrainConfig.parse(root);
    StatusSettings status = parseStatus(root.node("status"), directory);

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
            servers,
            defaultServer,
            fallbackOrder,
            duplicateLoginPolicy,
            forwardingMode,
            forwardingSecret,
            compressionThreshold,
            compressionLevel,
            compressionPassthrough,
            acceptsTransfers,
            management,
            drain,
            status);

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
   * Returns the configured backend servers (name to address mapping).
   *
   * @return unmodifiable map of server name to address
   */
  public Map<String, InetSocketAddress> servers() {
    return servers;
  }

  /**
   * Returns the name of the default server players connect to on join.
   *
   * @return the default server name, or {@code null} when no static server is configured (servers
   *     are then registered at runtime, and plugins pick each player's server)
   */
  public @Nullable String defaultServer() {
    return defaultServer;
  }

  /**
   * Returns what happens when a player logs in while a session with its UUID is still connected.
   *
   * @return the duplicate login policy
   */
  public DuplicateLoginPolicy duplicateLoginPolicy() {
    return duplicateLoginPolicy;
  }

  /**
   * Returns the ordered list of fallback server names.
   *
   * <p>When a backend disconnects a player, the proxy tries each server in this list (skipping the
   * server that failed) until one accepts the connection. If all fail, the player is disconnected.
   *
   * @return unmodifiable list of fallback server names
   */
  public List<String> fallbackOrder() {
    return fallbackOrder;
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

  /**
   * Returns whether packets the proxy does not inspect are forwarded in their original compressed
   * form instead of being decompressed and recompressed.
   *
   * @return {@code true} if compression passthrough is enabled
   */
  public boolean compressionPassthrough() {
    return compressionPassthrough;
  }

  /**
   * Returns whether players that a Transfer packet sends to the proxy (handshake intent 3, clients
   * 1.20.5 and newer) may log in. Off by default, as on vanilla servers and Velocity: a client is
   * then refused with vanilla's translated "transfers disabled" reason.
   *
   * @return {@code true} if transferred players may log in
   */
  public boolean acceptsTransfers() {
    return acceptsTransfers;
  }

  /**
   * Returns the management server's settings: Kubernetes probes, the HAProxy agent check and the
   * drain endpoint.
   *
   * @return the {@code management} section
   */
  public ManagementConfig management() {
    return management;
  }

  /**
   * Returns how the proxy drains before it stops.
   *
   * @return the {@code drain} section
   */
  public DrainConfig drain() {
    return drain;
  }

  /**
   * Returns the server list entry and the player limit, from the {@code status} block.
   *
   * @return the status settings
   */
  public StatusSettings status() {
    return status;
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  static InetSocketAddress parseAddress(String value, String field) throws ConfigurationException {
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

  private static Map<String, InetSocketAddress> parseServers(CommentedConfigurationNode root)
      throws ConfigurationException {
    CommentedConfigurationNode serversNode = root.node("servers");
    Map<String, InetSocketAddress> servers = new LinkedHashMap<>();

    if (!serversNode.virtual() && !serversNode.isMap() && !serversNode.empty()) {
      throw new ConfigurationException(
          "servers must be a block of named servers, for example servers { lobby { address ="
              + " \"localhost:25565\" } }");
    }

    if (!serversNode.virtual() && serversNode.isMap()) {
      for (var entry : serversNode.childrenMap().entrySet()) {
        String name = entry.getKey().toString().toLowerCase(Locale.ROOT);
        String addressStr = entry.getValue().node("address").getString();
        if (addressStr == null || addressStr.isBlank()) {
          throw new ConfigurationException(
              "Server '" + name + "' is missing an address in servers configuration");
        }
        servers.put(name, parseAddress(addressStr, "servers." + name + ".address"));
      }
    }

    // An explicit empty block means no static server: servers are registered at runtime.
    if (!serversNode.virtual()) {
      return servers;
    }

    // Fallback: if no servers block, check legacy backend.address.
    if (servers.isEmpty()) {
      String legacyAddress = root.node("backend", "address").getString();
      if (legacyAddress != null && !legacyAddress.isBlank()) {
        servers.put(DEFAULT_SERVER_NAME, parseAddress(legacyAddress, "backend.address"));
      }
    }

    if (servers.isEmpty()) {
      // Use built-in default.
      servers.put(
          DEFAULT_SERVER_NAME,
          parseAddress(DEFAULT_SERVER_ADDRESS, "servers." + DEFAULT_SERVER_NAME + ".address"));
    }

    return servers;
  }

  private static @Nullable String parseDefaultServer(
      CommentedConfigurationNode root, Map<String, InetSocketAddress> servers)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("default-server");
    if (servers.isEmpty()) {
      String configured = node.getString();
      if (configured != null && !configured.isBlank()) {
        throw new ConfigurationException(
            "default-server '"
                + configured
                + "' is set but no server is configured: remove default-server, or add the server"
                + " to the servers block");
      }
      return null;
    }
    String defaultServer = node.getString(DEFAULT_SERVER_NAME).toLowerCase(Locale.ROOT);
    if (!servers.containsKey(defaultServer)) {
      throw new ConfigurationException(
          "default-server '" + defaultServer + "' not found in servers list: " + servers.keySet());
    }
    return defaultServer;
  }

  private static List<String> parseFallbackOrder(
      CommentedConfigurationNode root,
      Map<String, InetSocketAddress> servers,
      @Nullable String defaultServer) {
    CommentedConfigurationNode fallbackNode = root.node("fallback-order");

    if (defaultServer == null) {
      if (!fallbackNode.virtual() && !fallbackNode.childrenList().isEmpty()) {
        logger.warn("fallback-order is ignored: no server is configured");
      }
      return List.of();
    }

    if (fallbackNode.virtual()
        || (fallbackNode.isList() && fallbackNode.childrenList().isEmpty())) {
      // Not configured: default to [defaultServer].
      return List.of(defaultServer);
    }

    List<String> result = new ArrayList<>();
    for (var child : fallbackNode.childrenList()) {
      String name = child.getString();
      if (name == null || name.isBlank()) {
        continue;
      }
      String key = name.toLowerCase(Locale.ROOT);
      if (!servers.containsKey(key)) {
        logger.warn("fallback-order references unknown server '{}', skipping", name);
        continue;
      }
      result.add(key);
    }

    if (result.isEmpty()) {
      logger.warn("fallback-order is empty after validation, defaulting to [{}]", defaultServer);
      return List.of(defaultServer);
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Status
  // ---------------------------------------------------------------------------

  private static StatusSettings parseStatus(CommentedConfigurationNode node, Path directory)
      throws ConfigurationException {
    StatusSettings defaults = StatusSettings.defaults();
    // Without a status block every key is virtual and takes its default: server-icon.png is still
    // loaded when it exists, as vanilla, Velocity and BungeeCord load it.
    if (!node.virtual() && !node.isMap()) {
      throw new ConfigurationException(
          "status must be a block, for example status { motd = \"A Warp Proxy\" }");
    }
    int maxPlayers = intValue(node.node("max-players"), defaults.maxPlayers());
    if (maxPlayers < StatusSettings.NO_LIMIT) {
      throw new ConfigurationException(
          "status.max-players must be 0 or more, or -1 for no limit; got " + maxPlayers);
    }
    int sampleSize = intValue(node.node("sample-size"), defaults.sampleSize());
    if (sampleSize < 0) {
      throw new ConfigurationException(
          "status.sample-size must be 0 (no sample) or more; got " + sampleSize);
    }
    StatusSettings settings =
        new StatusSettings(
            parseMotd(node.node("motd")),
            parseFavicon(node.node("favicon"), directory),
            maxPlayers,
            booleanValue(node.node("show-player-count"), defaults.showPlayerCount()),
            sampleSize,
            stringValue(node.node("version-name"), defaults.versionName()));
    try {
      ServerList.checkFits(settings);
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException("status: " + e.getMessage(), e);
    }
    return settings;
  }

  /** Reads the message of the day: one MiniMessage string, or a list of them, one per line. */
  private static Component parseMotd(ConfigurationNode node) throws ConfigurationException {
    String source;
    if (node.virtual()) {
      source = StatusSettings.DEFAULT_MOTD;
    } else if (node.isList()) {
      List<String> lines = new ArrayList<>();
      for (ConfigurationNode line : node.childrenList()) {
        String text = line.isMap() || line.isList() ? null : line.getString();
        if (text == null) {
          throw new ConfigurationException(
              "status.motd must be a string, or a list of strings (one per line), in MiniMessage");
        }
        lines.add(text);
      }
      source = String.join("\n", lines);
    } else {
      String text = node.isMap() ? null : node.getString();
      if (text == null) {
        throw new ConfigurationException(
            "status.motd must be a string, or a list of strings (one per line), in MiniMessage");
      }
      source = text;
    }
    Component motd;
    try {
      motd = MiniMessage.miniMessage().deserialize(source);
    } catch (ParsingException e) {
      throw new ConfigurationException(
          "status.motd is not valid MiniMessage: " + e.getMessage(), e);
    }
    if (LEGACY_CODE.matcher(source).find()) {
      logger.warn(
          "status.motd contains legacy color codes (such as &a), shown as typed: use MiniMessage"
              + " tags instead, such as <green>");
    }
    long lines = PlainTextComponentSerializer.plainText().serialize(motd).lines().count();
    if (lines > MOTD_LINES) {
      logger.warn("status.motd has {} lines: clients only show the first {}", lines, MOTD_LINES);
    }
    return motd;
  }

  /**
   * Reads the server icon: a 64x64 PNG file, relative to the proxy directory. The default file may
   * be missing (no icon), a file configured otherwise may not.
   */
  private static @Nullable Favicon parseFavicon(ConfigurationNode node, Path directory)
      throws ConfigurationException {
    String name = stringValue(node, DEFAULT_FAVICON);
    if (name.isBlank()) {
      return null;
    }
    Path file = directory.resolve(name);
    if (!Files.isRegularFile(file)) {
      if (name.equals(DEFAULT_FAVICON)) {
        return null;
      }
      throw new ConfigurationException(
          "status.favicon: no file at " + file.toAbsolutePath().normalize());
    }
    byte[] png;
    try {
      long size = Files.size(file);
      if (size > MAX_FAVICON_BYTES) {
        throw new ConfigurationException(
            "status.favicon: "
                + file
                + " is "
                + size
                + " bytes, far more than a 64x64 PNG needs and a status answer holds");
      }
      png = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new ConfigurationException("status.favicon: cannot read " + file, e);
    }
    try {
      return Favicon.fromPng(png);
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException("status.favicon: " + file + ": " + e.getMessage(), e);
    }
  }

  private static int intValue(ConfigurationNode node, int fallback) throws ConfigurationException {
    if (node.virtual()) {
      return fallback;
    }
    Object raw = node.raw();
    if (raw instanceof Number number && number.doubleValue() == number.intValue()) {
      return number.intValue();
    }
    if (raw instanceof String text) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        // Reported below.
      }
    }
    throw new ConfigurationException(path(node) + " must be a whole number, got '" + raw + "'");
  }

  private static boolean booleanValue(ConfigurationNode node, boolean fallback)
      throws ConfigurationException {
    if (node.virtual()) {
      return fallback;
    }
    Object raw = node.raw();
    if (raw instanceof Boolean value) {
      return value;
    }
    if (raw instanceof String text
        && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
      return Boolean.parseBoolean(text);
    }
    throw new ConfigurationException(path(node) + " must be true or false, got '" + raw + "'");
  }

  private static String stringValue(ConfigurationNode node, String fallback)
      throws ConfigurationException {
    if (node.virtual()) {
      return fallback;
    }
    String value = node.isMap() || node.isList() ? null : node.getString();
    if (value == null) {
      throw new ConfigurationException(path(node) + " must be a string");
    }
    return value;
  }

  /** The dotted path of a node, such as {@code status.max-players}, for error messages. */
  private static String path(ConfigurationNode node) {
    StringBuilder path = new StringBuilder();
    for (Object key : node.path()) {
      if (!path.isEmpty()) {
        path.append('.');
      }
      path.append(key);
    }
    return path.toString();
  }

  private static DuplicateLoginPolicy parseDuplicateLoginPolicy(String value)
      throws ConfigurationException {
    try {
      return DuplicateLoginPolicy.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException(
          "duplicate-login must be one of auto, kick-existing, refuse-new; got '" + value + "'", e);
    }
  }

  private static ForwardingMode parseForwardingMode(String value) throws ConfigurationException {
    try {
      return ForwardingMode.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException(
          "forwarding.mode must be one of: none, velocity — got '" + value + "'");
    }
  }

  private static byte[] loadForwardingSecret(Path directory, String secretPath, ForwardingMode mode)
      throws ConfigurationException {
    // Environment variable takes precedence.
    @Nullable String envSecret = System.getenv(ENV_FORWARDING_SECRET);
    if (envSecret != null && !envSecret.isBlank()) {
      logger.info("Using forwarding secret from {} environment variable", ENV_FORWARDING_SECRET);
      return envSecret.trim().getBytes(StandardCharsets.UTF_8);
    }

    if (mode == ForwardingMode.NONE) {
      return new byte[0];
    }

    Path file = directory.resolve(secretPath);
    if (!Files.exists(file)) {
      String generated = generateSecret();
      try {
        Files.writeString(file, generated, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new ConfigurationException("Failed to write forwarding secret to " + file, e);
      }
      logger.info("Generated a random forwarding secret in {}", file);
    }

    String secret;
    try {
      secret = Files.readString(file, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new ConfigurationException("Failed to read forwarding secret from " + file, e);
    }

    if (secret.isEmpty()) {
      throw new ConfigurationException(
          "Forwarding secret file is empty: "
              + file
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

        # Backend servers. Each entry has a unique name and an address.
        # Players connect to the default-server on join.
        # Leave the block empty (servers {}) to only use servers that plugins register at runtime,
        # and remove default-server and fallback-order in that case.
        servers {
          lobby {
            address = "localhost:25565"
          }
        }

        # The server players are sent to when they first connect.
        default-server = "lobby"

        # Let in the players a server sends here with a Transfer packet (Minecraft 1.20.5+):
        # another proxy draining for a restart, or a server of another network. Off, they are
        # refused with the game's own "transfers disabled" message, as on a vanilla server.
        accepts-transfers = false

        # Servers tried in order when a player is kicked from their current server.
        # The failed server is skipped. If all fail, the player is disconnected.
        # Defaults to [default-server] if omitted.
        fallback-order = ["lobby"]

        # A player who logs in while a session with the same UUID is still connected:
        #   auto           online mode kicks the existing session (the newcomer proved it owns the
        #                  account), offline mode refuses the newcomer (anyone can claim a name)
        #   kick-existing  always kick the existing session (what a vanilla server does)
        #   refuse-new     always refuse the newcomer
        duplicate-login = "auto"

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

        # The proxy's entry in the server list, and its player limit.
        status {
          # The message of the day, in MiniMessage
          # (https://docs.papermc.io/adventure/minimessage/format/): colors such as <aqua> or
          # <#38bdf8>, <bold>, <gradient:#38bdf8:#a78bfa>text</gradient>... Clients show two lines:
          # write a list of strings, one per line, or use <newline>. Before 1.16, clients see the
          # closest of the 16 named colors.
          motd = "<#38bdf8>A Warp Proxy"

          # A 64x64 PNG image shown next to the entry, relative to the proxy directory. Without
          # this default file, no icon; set "" to ignore it.
          favicon = "server-icon.png"

          # The most players the proxy lets in at once, also shown as the maximum. Players beyond
          # it are refused with "The server is full.", unless a plugin lets them in (LoginEvent).
          # -1 for no limit: the server list then shows one more than the players online.
          max-players = 500

          # Show how many players are online. Off, clients show ??? instead of the counts.
          show-player-count = true

          # How many names of players online are listed when hovering the counts, drawn at random
          # every 5 seconds. Players who turned off "Allow Server Listings" (1.18+) are listed as
          # Anonymous Player. 0 for none.
          sample-size = 12

          # The version shown in red, in place of the counts, to clients whose version Warp does
          # not support. Defaults to the versions Warp supports: "{default-version-name}".
          # version-name = "{default-version-name}"
        }


        # Packet compression settings (applied after login).
        compression {
          # Packets >= this size (bytes) are compressed. Set to -1 to disable.
          threshold = 256

          # Zlib compression level: 0 (none) to 9 (best). -1 for default (level 6).
          level = -1

          # Forward packets Warp does not inspect in their original compressed form instead of
          # decompressing and recompressing them. Requires compression on the backends too: keep
          # network-compression-threshold at the same value as `threshold` above (Paper's default,
          # 256) rather than -1. Disable only to troubleshoot.
          passthrough = true
        }

        # Management HTTP server: Kubernetes probes, load balancer health checks and the drain.
        #   GET /livez     200 while the process runs
        #   GET /startupz  200 once the proxy listens and its plugins are enabled
        #   GET /readyz    200 while players can be sent here: started, not draining, at least one
        #                  server registered (see below), and no plugin holding readiness
        #   GET /drain     where the drain stands
        #   POST /drain    starts the drain (Authorization: Bearer <token>); add ?wait=true to
        #                  answer only once every player left
        management {
          enabled = false
          # Keep it off the internet. In Kubernetes, bind 0.0.0.0 so that the kubelet reaches it.
          bind = "127.0.0.1:9901"
          # HAProxy agent check: answers "up ready", "drain" or "down" on this TCP port. Empty: off.
          agent-check = ""
          # The token POST /drain requires, read from this file (relative to this directory) or
          # from the WARP_MANAGEMENT_TOKEN variable. Without one, POST /drain is refused.
          token-file = ""
          # Ready only once at least one backend server is registered.
          ready-requires-server = true
        }

        # Draining: empty the proxy before it stops, for restarts without kicking anyone.
        #   phase 0  readiness and the agent check say drain; wait propagation-delay
        #   phase 1  players are transferred to transfer-to (1.20.5+), one every transfer-interval;
        #            plugins may keep some (a match in progress) until they reach another server
        #   phase 2  wait for the players kept to leave, at most timeout
        #   phase 3  disconnect whoever is left, disable plugins and exit
        #            (exit code 0 once empty, 2 when the time-out expired)
        drain {
          # Where players go: your public address (the load balancer), never this proxy or a
          # backend. Empty: nobody is transferred, the drain waits for players to leave.
          transfer-to = ""
          # Drain on SIGTERM instead of stopping at once (a second SIGTERM stops at once). Turn it
          # on under Kubernetes, with a terminationGracePeriodSeconds longer than the drain.
          on-sigterm = false
          # How long load balancers get to stop sending players here before the transfers start
          # (HAProxy checks the agent every agent-inter; Kubernetes updates its endpoints).
          propagation-delay = 5s
          # Time between two transfers, so that the other proxies are not hit all at once.
          transfer-interval = 100ms
          # Players that cannot be transferred (clients older than 1.20.5, or no transfer-to):
          # keep (until they leave) or kick.
          cannot-transfer = keep
          # Players who log in while draining: accept (transferred with the others once on a
          # server, once the load balancers had time to stop sending players here) or refuse.
          new-players = accept
          # Longest wait for the players kept, once the transfers are done.
          timeout = 30m
          # Shown to the players the drain disconnects and to the logins it refuses.
          message = "This proxy is restarting. Please reconnect."
        }
        """
            .replace("{default-version-name}", StatusSettings.defaultVersionName());

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
    if (config.servers.isEmpty()) {
      logger.info("  servers: none configured, registered at runtime by plugins");
    } else {
      logger.info("  default-server: {}", config.defaultServer);
      for (var entry : config.servers.entrySet()) {
        logger.info("  server '{}': {}", entry.getKey(), entry.getValue());
      }
      logger.info("  fallback-order: {}", config.fallbackOrder);
    }
    logger.info(
        "  duplicate-login: {}",
        config.duplicateLoginPolicy.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    logger.info("  accepts-transfers: {}", config.acceptsTransfers);
    logger.info("  management: {}", config.management);
    logger.info("  drain: {}", config.drain);
    logger.info("  forwarding: {}", config.forwardingMode.name().toLowerCase(Locale.ROOT));
    logger.info(
        "  compression: threshold={}, level={}, passthrough={}",
        config.compressionThreshold,
        config.compressionLevel,
        config.compressionPassthrough);
    StatusSettings status = config.status;
    Favicon favicon = status.favicon();
    logger.info(
        "  status: max-players={}, show-player-count={}, sample-size={}, favicon={}",
        status.maxPlayers() == StatusSettings.NO_LIMIT ? "none" : status.maxPlayers(),
        status.showPlayerCount(),
        status.sampleSize(),
        favicon == null ? "none" : favicon.uri().length() + " characters");
  }
}
