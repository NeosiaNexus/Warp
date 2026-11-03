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
import dev.warp.proxy.console.ConsoleMarkers;
import dev.warp.proxy.messaging.BungeeCordSettings;
import dev.warp.proxy.messaging.BungeeCordSubchannel;
import dev.warp.proxy.network.CidrRange;
import dev.warp.proxy.network.CidrRanges;
import dev.warp.proxy.status.ServerList;
import dev.warp.proxy.status.StatusSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

  static final String CONFIG_FILE = "warp.conf";
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
  private static final boolean DEFAULT_TCP_FAST_OPEN = false;

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

  /** A whole number of zero or more, for counts. */
  private static final Pattern COUNT = Pattern.compile("\\d{1,10}");

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
  private final ProxyProtocolConfig proxyProtocol;
  private final LimitsConfig limits;
  private final AntiBotConfig antiBot;
  private final TimeoutsConfig timeouts;
  private final boolean tcpFastOpen;
  private final LoggingConfig logging;
  private final BungeeCordSettings bungeeCord;

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
      StatusSettings status,
      ProxyProtocolConfig proxyProtocol,
      LimitsConfig limits,
      AntiBotConfig antiBot,
      TimeoutsConfig timeouts,
      boolean tcpFastOpen,
      LoggingConfig logging,
      BungeeCordSettings bungeeCord) {
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
    this.proxyProtocol = proxyProtocol;
    this.limits = limits;
    this.antiBot = antiBot;
    this.timeouts = timeouts;
    this.tcpFastOpen = tcpFastOpen;
    this.logging = logging;
    this.bungeeCord = bungeeCord;
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
    StatusSettings status = parseStatus(root, directory);
    ProxyProtocolConfig proxyProtocol = parseProxyProtocol(root);
    LimitsConfig limits = parseLimits(root);
    AntiBotConfig antiBot = AntiBotConfig.parse(root);
    TimeoutsConfig timeouts = TimeoutsConfig.parse(root);
    boolean tcpFastOpen = booleanValue(root.node("tcp-fast-open"), DEFAULT_TCP_FAST_OPEN);
    LoggingConfig logging = LoggingConfig.parse(root, System::getenv);
    BungeeCordSettings bungeeCord = parseBungeeCord(root.node("bungeecord-channel"));

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
            status,
            proxyProtocol,
            limits,
            antiBot,
            timeouts,
            tcpFastOpen,
            logging,
            bungeeCord);

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

  /**
   * Returns whether connections arrive through a load balancer that sends the PROXY protocol, and
   * which load balancers are trusted to send it.
   *
   * @return the PROXY protocol settings
   */
  public ProxyProtocolConfig proxyProtocol() {
    return proxyProtocol;
  }

  /**
   * Returns the limits on what one client address may do, and on the authentications the proxy runs
   * at once.
   *
   * @return the limits
   */
  public LimitsConfig limits() {
    return limits;
  }

  /**
   * Returns the anti-bot's settings: attack detection, trusted identities, lockdown, blocklist.
   *
   * @return the {@code anti-bot} section
   */
  public AntiBotConfig antiBot() {
    return antiBot;
  }

  /**
   * Returns how long the proxy waits on a backend that does not answer, and on a logged-in
   * connection that went quiet.
   *
   * @return the timeouts
   */
  public TimeoutsConfig timeouts() {
    return timeouts;
  }

  /**
   * Returns whether TCP Fast Open is requested, on the listener and on connections to backends. It
   * takes effect with the epoll transport only (Linux), with the kernel's {@code
   * net.ipv4.tcp_fastopen} allowing it.
   *
   * @return {@code true} if requested
   */
  public boolean tcpFastOpen() {
    return tcpFastOpen;
  }

  /**
   * Returns the log format, and which routine events get a line each.
   *
   * @return the logging settings
   */
  public LoggingConfig logging() {
    return logging;
  }

  /**
   * Returns how the proxy answers the BungeeCord plugin messaging channel, and which servers may
   * use which of its sub-channels.
   *
   * @return the {@code bungeecord-channel} block
   */
  public BungeeCordSettings bungeeCord() {
    return bungeeCord;
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

  private static StatusSettings parseStatus(CommentedConfigurationNode root, Path directory)
      throws ConfigurationException {
    StatusSettings defaults = StatusSettings.defaults();
    CommentedConfigurationNode node = root.node("status");
    // Without a status block every key is virtual and takes its default: server-icon.png is still
    // loaded when it exists, as vanilla, Velocity and BungeeCord load it.
    if (!node.virtual() && !node.isMap()) {
      throw new ConfigurationException(
          "status must be a block, for example status { motd = \"A Warp Proxy\" }");
    }
    int playerLimit = intValue(root.node("player-limit"), defaults.playerLimit());
    if (playerLimit < StatusSettings.NO_LIMIT) {
      throw new ConfigurationException(
          "player-limit must be 0 or more, or -1 for no limit; got " + playerLimit);
    }
    // Shown only. Without a value, the limit when there is one: what players can actually reach.
    int maxPlayers =
        intValue(
            node.node("max-players"),
            playerLimit == StatusSettings.NO_LIMIT ? defaults.maxPlayers() : playerLimit);
    if (maxPlayers < StatusSettings.ONE_MORE_THAN_ONLINE) {
      throw new ConfigurationException(
          "status.max-players must be 0 or more, or -1 to show one more than the players online;"
              + " got "
              + maxPlayers);
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
            playerLimit,
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

  static int intValue(ConfigurationNode node, int fallback) throws ConfigurationException {
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

  static boolean booleanValue(ConfigurationNode node, boolean fallback)
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

  static String stringValue(ConfigurationNode node, String fallback) throws ConfigurationException {
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

  /**
   * Parses the {@code proxy-protocol} section. An enabled PROXY protocol must name the peers it
   * trusts: trusting every peer, as Velocity's {@code haproxy-protocol} does, lets anyone who
   * reaches the port choose the address Warp sees.
   */
  private static ProxyProtocolConfig parseProxyProtocol(CommentedConfigurationNode root)
      throws ConfigurationException {
    CommentedConfigurationNode section = root.node("proxy-protocol");
    boolean enabled = section.node("enabled").getBoolean(false);

    CidrRanges trusted =
        parseCidrRanges(section.node("trusted-proxies"), "proxy-protocol.trusted-proxies");

    if (!enabled) {
      return new ProxyProtocolConfig(false, trusted);
    }
    if (trusted.isEmpty()) {
      throw new ConfigurationException(
          "proxy-protocol.enabled is true but proxy-protocol.trusted-proxies is empty. List the"
              + " addresses or CIDR ranges of the load balancers that send the PROXY protocol"
              + " header, such as [\"10.0.0.0/8\"]: Warp refuses to accept the header from any"
              + " peer, as whoever sends it chooses the address Warp sees.");
    }
    for (CidrRange range : trusted.ranges()) {
      if (range.prefixLength() == 0) {
        logger.warn(
            "proxy-protocol.trusted-proxies contains {}, which trusts every {} address: anyone"
                + " who can reach Warp's port directly can spoof their address. List only your"
                + " load balancers.",
            range,
            range.isIpv4() ? "IPv4" : "IPv6");
      }
    }
    return new ProxyProtocolConfig(true, trusted);
  }

  /** Parses a list of addresses or CIDR ranges; an absent list is empty. */
  static CidrRanges parseCidrRanges(CommentedConfigurationNode node, String key)
      throws ConfigurationException {
    if (!node.virtual() && !node.isList()) {
      throw new ConfigurationException(
          key
              + " must be a list of addresses or CIDR ranges, such as"
              + " [\"10.0.0.0/8\", \"2001:db8::/32\"]");
    }
    List<String> values = new ArrayList<>();
    for (CommentedConfigurationNode child : node.childrenList()) {
      String value = child.getString();
      if (value == null || value.isBlank()) {
        throw new ConfigurationException(
            key + " holds an empty entry: list addresses or CIDR ranges");
      }
      values.add(value.strip());
    }
    try {
      return CidrRanges.parse(values);
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException(key + ": " + e.getMessage(), e);
    }
  }

  /**
   * Parses the {@code limits} section, each absent key taking its value from {@link
   * LimitsConfig#DEFAULTS}.
   */
  private static LimitsConfig parseLimits(CommentedConfigurationNode root)
      throws ConfigurationException {
    LimitsConfig defaults = LimitsConfig.DEFAULTS;
    CommentedConfigurationNode section = root.node("limits");
    CommentedConfigurationNode exemptNode = section.node("exempt");
    CommentedConfigurationNode loginsNode = section.node("logins");

    boolean enabled = section.node("enabled").getBoolean(defaults.enabled());
    CidrRanges exempt =
        exemptNode.virtual() ? defaults.exempt() : parseCidrRanges(exemptNode, "limits.exempt");
    LimitsConfig.Rate connections =
        parseRate(section.node("connections"), "limits.connections", defaults.connections());
    LimitsConfig.Rate statusPings =
        parseRate(section.node("status-pings"), "limits.status-pings", defaults.statusPings());
    LimitsConfig.Rate logins = parseRate(loginsNode, "limits.logins", defaults.logins());
    int perAddress =
        parseCount(
            loginsNode.node("max-concurrent-per-address"),
            "limits.logins.max-concurrent-per-address",
            defaults.maxConcurrentLoginsPerAddress());
    int authentications =
        parseCount(
            loginsNode.node("max-concurrent-authentications"),
            "limits.logins.max-concurrent-authentications",
            defaults.maxConcurrentAuthentications());
    Duration loginTimeout =
        parseDuration(loginsNode.node("timeout"), "limits.logins.timeout", defaults.loginTimeout());
    Duration reportInterval =
        parseDuration(
            section.node("report-interval"), "limits.report-interval", defaults.reportInterval());
    if (reportInterval.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new ConfigurationException(
          "limits.report-interval must be at least 1s, got " + LimitsConfig.format(reportInterval));
    }
    int maxPending =
        parseCount(
            section.node("max-pending-connections"),
            "limits.max-pending-connections",
            defaults.maxPendingConnections());
    Duration handshakeTimeout =
        parseDuration(
            section.node("handshake-timeout"),
            "limits.handshake-timeout",
            defaults.handshakeTimeout());
    Duration statusTimeout =
        parseDuration(
            section.node("status-timeout"), "limits.status-timeout", defaults.statusTimeout());
    CommentedConfigurationNode queueNode = loginsNode.node("authentication-queue");
    int queue =
        parseCount(
            queueNode.node("size"),
            "limits.logins.authentication-queue.size",
            defaults.authenticationQueue());
    Duration queueWait =
        parseDuration(
            queueNode.node("max-wait"),
            "limits.logins.authentication-queue.max-wait",
            defaults.authenticationQueueWait());
    if (queueWait.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new ConfigurationException(
          "limits.logins.authentication-queue.max-wait must be at least 1s, got "
              + LimitsConfig.format(queueWait));
    }
    CommentedConfigurationNode attackNode = loginsNode.node("under-attack");
    LimitsConfig.Rate loginsUnderAttack =
        parseRate(attackNode, "limits.logins.under-attack", defaults.loginsUnderAttack());
    int perAddressUnderAttack =
        parseCount(
            attackNode.node("max-concurrent-per-address"),
            "limits.logins.under-attack.max-concurrent-per-address",
            defaults.maxConcurrentLoginsPerAddressUnderAttack());
    return new LimitsConfig(
        enabled,
        exempt,
        connections,
        statusPings,
        logins,
        perAddress,
        authentications,
        loginTimeout,
        reportInterval,
        maxPending,
        handshakeTimeout,
        statusTimeout,
        queue,
        queueWait,
        loginsUnderAttack,
        perAddressUnderAttack);
  }

  /**
   * Parses the {@code bungeecord-channel} block. Without it, the channel is answered for every
   * server, every sub-channel allowed, as BungeeCord and Velocity answer it.
   */
  private static BungeeCordSettings parseBungeeCord(CommentedConfigurationNode node)
      throws ConfigurationException {
    BungeeCordSettings defaults = BungeeCordSettings.DEFAULTS;
    if (node.virtual()) {
      return defaults;
    }
    if (!node.isMap()) {
      throw new ConfigurationException(
          "bungeecord-channel must be a block, for example bungeecord-channel { enabled = true }");
    }
    boolean enabled = booleanValue(node.node("enabled"), defaults.enabled());
    Duration forwardHold =
        parseDuration(
            node.node("forward-hold"), "bungeecord-channel.forward-hold", defaults.forwardHold());
    CommentedConfigurationNode allowNode = node.node("allow");
    Map<String, Set<BungeeCordSubchannel>> allow = new LinkedHashMap<>();
    if (allowNode.virtual()) {
      allow.putAll(defaults.allow());
    } else if (!allowNode.isMap()) {
      throw new ConfigurationException(
          "bungeecord-channel.allow must be a block of rules, for example allow { \"*\" = [\"*\"]"
              + " }");
    } else {
      for (var rule : allowNode.childrenMap().entrySet()) {
        String key = String.valueOf(rule.getKey());
        CommentedConfigurationNode value = rule.getValue();
        List<String> entries = new ArrayList<>();
        if (value.isList()) {
          for (CommentedConfigurationNode entry : value.childrenList()) {
            String text = entry.isMap() || entry.isList() ? null : entry.getString();
            if (text == null) {
              throw new ConfigurationException(
                  "bungeecord-channel.allow." + key + " must be a list of sub-channel names");
            }
            entries.add(text);
          }
        } else {
          String text = value.isMap() ? null : value.getString();
          if (text == null) {
            throw new ConfigurationException(
                "bungeecord-channel.allow." + key + " must be a list of sub-channel names");
          }
          entries.add(text);
        }
        try {
          allow.put(key, BungeeCordSettings.parseRule(entries));
        } catch (IllegalArgumentException e) {
          throw new ConfigurationException(
              "bungeecord-channel.allow." + key + ": " + e.getMessage(), e);
        }
      }
    }
    try {
      return new BungeeCordSettings(enabled, allow, forwardHold);
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException("bungeecord-channel.allow: " + e.getMessage(), e);
    }
  }

  /** Parses the {@code burst} and {@code interval} of a token bucket section. */
  private static LimitsConfig.Rate parseRate(
      CommentedConfigurationNode node, String key, LimitsConfig.Rate defaults)
      throws ConfigurationException {
    int burst = parseCount(node.node("burst"), key + ".burst", defaults.burst());
    if (burst < 1) {
      throw new ConfigurationException(
          key + ".burst must be at least 1 (set interval = 0 for no limit), got " + burst);
    }
    Duration interval =
        parseDuration(node.node("interval"), key + ".interval", defaults.interval());
    return new LimitsConfig.Rate(burst, interval);
  }

  /** Parses a whole number, zero or more; an absent key takes {@code defaultValue}. */
  static int parseCount(CommentedConfigurationNode node, String key, int defaultValue)
      throws ConfigurationException {
    if (node.virtual()) {
      return defaultValue;
    }
    String value = String.valueOf(node.getString()).strip();
    if (!COUNT.matcher(value).matches()) {
      throw new ConfigurationException(
          key + " must be a whole number, 0 or more, got '" + value + "'");
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ConfigurationException(key + " is too large: " + value, e);
    }
  }

  /**
   * Parses a duration in the configuration's one format (see {@link Durations}); an absent key
   * takes {@code defaultValue}.
   */
  static Duration parseDuration(CommentedConfigurationNode node, String key, Duration defaultValue)
      throws ConfigurationException {
    return Durations.parse(node, key, defaultValue);
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
        # Warp Proxy Configuration, in HOCON (https://github.com/lightbend/config/blob/main/HOCON.md).
        #
        # Durations take a unit: 200ms, 30s, 5m, 1h or 30d (a bare 0 is zero, any other bare number
        # is refused).
        #
        # Any value can come from an environment variable: write the value, then the same key again
        # with ${?NAME}, which replaces it only when NAME is set:
        #   bind = "0.0.0.0:25577"
        #   bind = ${?WARP_BIND}
        # Alone, `key = ${?NAME}` leaves the key at its default when NAME is not set. This works in
        # any block, and inside strings: status { motd = "<aqua>Welcome to "${?NETWORK_NAME} }
        #
        # Secrets stay out of this file: the forwarding secret is read from forwarding.secret-file or
        # the WARP_FORWARDING_SECRET variable, the management token from management.token-file or the
        # WARP_MANAGEMENT_TOKEN variable.

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

        # The most players the proxy lets in at once. Players beyond it are refused with "The server is
        # full.", unless a plugin lets them in (LoginEvent); 0 refuses everyone a plugin does not let
        # in, a maintenance mode. -1 for no limit. The server list shows it as the maximum, unless
        # status.max-players says otherwise.
        player-limit = -1

        # The proxy's entry in the server list.
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

          # The maximum the server list shows, only shown: player-limit is what lets players in.
          # Without it, the server list shows player-limit when one is set, 500 otherwise. -1 shows
          # one more than the players online.
          # max-players = 500

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


        # Packet compression settings (applied after login). Warp compresses with the libdeflate
        # bundled in its jar, or with Java's zlib where that cannot load (the startup log says
        # which, and why). -Dwarp.natives.disable=true forces Java's zlib.
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

        # How long the proxy waits.
        timeouts {
          # Opening a TCP connection to a backend server.
          connect = 5s

          # A logged-in player's connection that sends nothing for this long is closed. Below the
          # client's own 30s, so that the proxy notices a dead client first. The deadline before
          # login is limits.logins.timeout.
          read = 25s

          # A connection to a backend server that sends nothing for this long is closed, in every
          # state: while it logs a player in, until its first Join Game, and in play. Its player is
          # sent to a fallback server. The proxy keeps a waiting client connected meanwhile, so this
          # may exceed the client's 30s, for backends slow to prepare a new player.
          backend-read = 30s

          # A plugin's RegisteredServer.ping() without a time-out of its own: connection, handshake
          # and answer. A server that does not answer in time fails the ping with TIMED_OUT.
          ping = 5s
        }

        # TCP Fast Open, on the listener and on connections to backends: a connection opens one round
        # trip sooner when both ends support it. Linux only (epoll transport), with the kernel's
        # net.ipv4.tcp_fastopen set to 3; ignored, with a warning, elsewhere.
        tcp-fast-open = false

        logging {
          # text: a line of text per record, for a terminal. json: one JSON object per line on the
          # standard output, for log collectors (timestamp, level, logger, thread, message, exception,
          # and context such as player and session). The WARP_LOG_FORMAT variable takes precedence.
          # The log files (logs/warp.log) stay in text.
          format = text

          # Players logging in, leaving, and moving between servers. During a bot attack (anti-bot)
          # neither these nor pings are logged one by one: the attack's summaries count them.
          connections = true

          # Each server list ping, with its address. Off by default: refreshing a server list writes
          # a line per ping.
          pings = false

          # Each command a player sends, with its arguments. Off by default: commands carry passwords
          # on offline-mode networks (/login, /register).
          commands = false
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
          # Shown to the players the drain disconnects and to the logins it refuses, in MiniMessage
          # like status.motd.
          message = "<yellow>This proxy is restarting. Please reconnect."
        }

        # PROXY protocol (v1 and v2), for a load balancer in front of Warp that sends it: HAProxy
        # (send-proxy, send-proxy-v2), AWS NLB, Cloudflare Spectrum, TCPShield, or a Kubernetes
        # LoadBalancer Service with the PROXY protocol on. The header carries each player's real
        # address, which Warp then uses everywhere: logs, and the address forwarded to backends.
        proxy-protocol {
          # When enabled, every connection must come from a trusted proxy below and start with a
          # PROXY protocol header (Warp detects v1 and v2). Connections from any other address are
          # refused, and so are connections from a trusted proxy without a valid header. Health
          # checks may send a v2 LOCAL header (HAProxy's check-send-proxy).
          enabled = false

          # Addresses or CIDR ranges, IPv4 or IPv6, of the load balancers that send the header,
          # e.g. ["10.0.0.0/8", "2001:db8::/32"]. Required when enabled. Never list an address
          # players can connect from: whoever sends the header chooses the address Warp sees.
          trusted-proxies = []
        }

        # Limits on what one client address may do, against connection floods and bot attacks.
        # An address is an IPv4 address, or an IPv6 /64 network (what an ISP gives one customer).
        # Behind the PROXY protocol, limits count the players' real addresses, never the load
        # balancer's. Refusals are not logged one by one: their counts are, at most once per
        # report-interval. Rates are token buckets: `burst` at once, then one every `interval`;
        # an interval of 0 lifts that limit.
        limits {
          # Set to false to lift every limit, e.g. behind a DDoS protection that already does it.
          enabled = true

          # Addresses or CIDR ranges never limited. Loopback by default: local tools, and local
          # tunnels or sidecars that hide the real clients, where per-address limits would
          # throttle every player at once. The trusted proxies are never limited either.
          exempt = ["127.0.0.0/8", "::1/128"]

          # New TCP connections, refused before Warp reads anything from them.
          connections {
            burst = 10
            interval = 200ms
          }

          # Connections that have not logged in yet (server list pings, logins in progress,
          # connections that sent nothing), for the whole proxy, whatever their address: what a
          # botnet of many addresses can hold at once. Over it, new connections are refused before
          # Warp reads anything from them. 0 for no limit.
          max-pending-connections = 8192

          # The longest a connection may take to send its handshake (the PROXY protocol header
          # included), counted from the moment it connects, and the longest a server list ping may
          # take, from its handshake to its answer. A game sends both at once; bytes trickled in do
          # not extend them. 0 for no timeout.
          handshake-timeout = 5s
          status-timeout = 5s

          # Server list pings, counted apart from logins: refreshing the server list never locks
          # a player out. A refused ping gets no answer.
          status-pings {
            burst = 10
            interval = 1s
          }

          # Login attempts. A refused player is told to wait. Many players share one address
          # (households, schools, LAN parties, carrier-grade NAT, a Docker gateway): out of an
          # attack, ten may join at once from one address, then one a second. While the anti-bot
          # detects an attack, the strict budget of `under-attack` applies instead (one every 3s
          # after a burst of 2, Velocity's login-ratelimit = 3000).
          logins {
            burst = 10
            interval = 1s

            # Logins in progress at once from one address, from its handshake until the player is
            # logged in. 0 for no limit.
            max-concurrent-per-address = 10

            under-attack {
              burst = 2
              interval = 3s
              max-concurrent-per-address = 2
            }

            # Online mode: logins Warp authenticates at once, for the whole proxy (the RSA
            # decryption and the request to Mojang's session server). Only logins that sent their
            # encryption response count, so bots that stall earlier take none. 0 for no limit.
            max-concurrent-authentications = 256

            # When those are all taken, online logins wait in line instead of being refused:
            # players the anti-bot trusts first, then the others in arrival order, at most `size`
            # of them for at most `max-wait`; beyond, they are told the server is busy.
            authentication-queue {
              size = 1024
              max-wait = 10s
            }

            # A login that takes longer is closed, with its place among its address's logins in
            # progress. 0 for no timeout.
            timeout = 30s
          }

          # How often the counts of connections refused before login are logged, by reason (limits,
          # time-outs, invalid packets, failed authentications), when not zero. Warp never logs
          # these one connection at a time: anyone can cause them, as fast as they like.
          report-interval = 1m
        }

        # The built-in anti-bot. It learns the identities that play here (the account in online
        # mode, the name with its network in offline mode), detects attacks from the logins of
        # identities it does not know, and lets only known ones in during a lockdown
        # (/warp antibot lockdown on, or POST /antibot/lockdown on the management server).
        # The limits above apply whether it runs or not.
        anti-bot {
          enabled = true

          # Addresses or CIDR ranges never refused by lockdown nor counted as unknown: a staff VPN,
          # a Geyser host.
          exempt = []

          verified {
            # An identity stays trusted this long after it last joined (sliding, not from when it
            # was first trusted). Durations take ms, s, m, h or d.
            expire-after-last-join = 30d
            # The most identities remembered: about 35 bytes each. When full, the least recently
            # seen of a random sample of 8 makes room.
            capacity = 250000
            # In offline mode an identity is a name and the network it connects from: an IPv4 /24
            # or an IPv6 /48 by default, so that a new address from the same provider pool does
            # not make a player unknown.
            prefix {
              ipv4 = 24
              ipv6 = 48
            }
            # Keep the identities on disk (anti-bot/verified.dat and its journal, flushed every
            # second), so that a restart does not forget them. No name or address is stored in
            # clear: only keyed hashes.
            persist = true
            # The secret the identities are hashed under, generated on first start; share it (or
            # set WARP_ANTIBOT_SECRET) between proxies that share a store through a plugin.
            secret-file = "anti-bot/secret.key"
            # How long a login waits for a plugin's shared store to answer.
            shared-store-timeout = 500ms
          }

          # An attack starts when, for `sustain`, the logins of unknown identities or the failed
          # authentications per second go over their floor (or `baseline-factor` times their usual
          # rate, learned outside attacks), or connections not logged in go over `pending-share`
          # percent of limits.max-pending-connections. It ends once everything stayed under half
          # its threshold for `calm-for`. Meanwhile, one summary line per `report-interval`.
          attack {
            floors {
              unknown-logins = 10
              authentication-failures = 5
            }
            baseline-factor = 4
            pending-share = 50
            sustain = 3s
            calm-for = 60s
            report-interval = 10s
          }

          # Addresses that send what no game client sends (frames over the limits of their state,
          # invalid handshakes, unexpected packets) `strikes` times within `duration` are refused at
          # once for `duration`, and listed on GET /antibot/blocklist for nftables. 0 turns it off.
          blocklist {
            strikes = 3
            duration = 10m
          }

          # How long a login waits for the checks plugins add (VPN detection, say).
          checks {
            timeout = 2s
          }
        }

        # BungeeCord's plugin messaging channel ("BungeeCord" before 1.13, "bungeecord:main"
        # after), through which backend plugins move players and query the network: Connect,
        # ConnectOther, IP, IPOther, PlayerCount, PlayerList, GetServers, GetPlayerServer,
        # Message, MessageRaw, GetServer, UUID, UUIDOther, ServerIP, KickPlayer, KickPlayerRaw,
        # Forward and ForwardToPlayer. Warp answers it as BungeeCord and Velocity do. Players
        # never speak it: what a client sends on it is dropped.
        bungeecord-channel {
          enabled = true

          # The sub-channels each server may use. A backend can move or kick any player, message
          # everyone, or read players' addresses through this channel: on a network where some
          # servers belong to someone else, allow them only what they need. Keys are server names
          # or prefixes ending with * (game-*); the rule named after a server applies, else the
          # longest prefix that matches it, else "*". A server no rule matches may use nothing.
          # Lists name sub-channels, * for all of them, and !Name to take one away. Requests a
          # rule refuses are dropped and logged.
          allow {
            "*" = ["*"]
            # "game-*" = [Connect, GetServer, GetServers, PlayerCount, PlayerList, UUID, Forward]
            # "lobby" = ["*", "!KickPlayer", "!KickPlayerRaw"]
          }

          # A Forward to a server nobody plays on cannot be delivered: plugin messages only travel
          # over a player's connection. It waits this long for a player to arrive there (so that
          # "send the data, then the player" works), then is dropped, with a warning. ONLINE
          # forwards never wait. 0 drops them at once.
          forward-hold = 30s
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
    logger.info(ConsoleMarkers.FILE_ONLY, "Configuration loaded:");
    logger.info(ConsoleMarkers.FILE_ONLY, "  bind: {}", config.bind);
    logger.info(ConsoleMarkers.FILE_ONLY, "  online-mode: {}", config.onlineMode);
    if (config.servers.isEmpty()) {
      logger.info(
          ConsoleMarkers.FILE_ONLY, "  servers: none configured, registered at runtime by plugins");
    } else {
      logger.info(ConsoleMarkers.FILE_ONLY, "  default-server: {}", config.defaultServer);
      for (var entry : config.servers.entrySet()) {
        logger.info(
            ConsoleMarkers.FILE_ONLY, "  server '{}': {}", entry.getKey(), entry.getValue());
      }
      logger.info(ConsoleMarkers.FILE_ONLY, "  fallback-order: {}", config.fallbackOrder);
    }
    logger.info(
        ConsoleMarkers.FILE_ONLY,
        "  duplicate-login: {}",
        config.duplicateLoginPolicy.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    logger.info(ConsoleMarkers.FILE_ONLY, "  accepts-transfers: {}", config.acceptsTransfers);
    logger.info(ConsoleMarkers.FILE_ONLY, "  management: {}", config.management);
    logger.info(ConsoleMarkers.FILE_ONLY, "  drain: {}", config.drain);
    logger.info(
        ConsoleMarkers.FILE_ONLY,
        "  forwarding: {}",
        config.forwardingMode.name().toLowerCase(Locale.ROOT));
    logger.info(
        ConsoleMarkers.FILE_ONLY,
        "  compression: threshold={}, level={}, passthrough={}",
        config.compressionThreshold,
        config.compressionLevel,
        config.compressionPassthrough);
    StatusSettings status = config.status;
    Favicon favicon = status.favicon();
    logger.info(
        ConsoleMarkers.FILE_ONLY,
        "  player-limit: {}",
        status.playerLimit() == StatusSettings.NO_LIMIT ? "none" : status.playerLimit());
    logger.info(
        ConsoleMarkers.FILE_ONLY,
        "  status: max-players shown={}, show-player-count={}, sample-size={}, favicon={}",
        status.maxPlayers() == StatusSettings.ONE_MORE_THAN_ONLINE
            ? "one more than online"
            : status.maxPlayers(),
        status.showPlayerCount(),
        status.sampleSize(),
        favicon == null ? "none" : favicon.uri().length() + " characters");
    if (config.proxyProtocol.enabled()) {
      logger.info(
          ConsoleMarkers.FILE_ONLY,
          "  proxy-protocol: required from trusted proxies {}",
          config.proxyProtocol.trustedProxies());
    } else {
      logger.info(ConsoleMarkers.FILE_ONLY, "  proxy-protocol: disabled");
    }
    logger.info(ConsoleMarkers.FILE_ONLY, "  timeouts: {}", config.timeouts);
    logger.info(ConsoleMarkers.FILE_ONLY, "  tcp-fast-open: {}", config.tcpFastOpen);
    LoggingConfig logging = config.logging;
    logger.info(
        ConsoleMarkers.FILE_ONLY,
        "  logging: format={}, connections={}, pings={}, commands={}",
        logging.format().configName(),
        logging.connections(),
        logging.pings(),
        logging.commands());
    logger.info(ConsoleMarkers.FILE_ONLY, "  bungeecord-channel: {}", config.bungeeCord.describe());
    LimitsConfig limits = config.limits;
    if (limits.enabled()) {
      logger.info(
          ConsoleMarkers.FILE_ONLY,
          "  limits per address: connections {}, server list pings {}, logins {} with {} in"
              + " progress at most; exempt {}",
          limits.connections(),
          limits.statusPings(),
          limits.logins(),
          limits.maxConcurrentLoginsPerAddress() == 0
              ? "any number"
              : limits.maxConcurrentLoginsPerAddress(),
          limits.exempt());
      logger.info(
          ConsoleMarkers.FILE_ONLY,
          "  limits: {} authentications at once, logins time out after {}",
          limits.maxConcurrentAuthentications() == 0
              ? "any number of"
              : limits.maxConcurrentAuthentications(),
          limits.loginTimeout().isZero() ? "never" : LimitsConfig.format(limits.loginTimeout()));
      logger.info(
          ConsoleMarkers.FILE_ONLY,
          "  limits: {} connections not logged in at once, handshakes within {}, server list pings"
              + " within {}",
          limits.maxPendingConnections() == 0 ? "any number of" : limits.maxPendingConnections(),
          limits.handshakeTimeout().isZero()
              ? "any time"
              : LimitsConfig.format(limits.handshakeTimeout()),
          limits.statusTimeout().isZero()
              ? "any time"
              : LimitsConfig.format(limits.statusTimeout()));
    } else {
      logger.info(ConsoleMarkers.FILE_ONLY, "  limits: disabled");
    }
    logger.info(ConsoleMarkers.FILE_ONLY, "  anti-bot: {}", config.antiBot);
  }
}
