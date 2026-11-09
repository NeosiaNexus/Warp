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
import dev.warp.proxy.i18n.PlayerMessages;
import dev.warp.proxy.logging.LogCode;
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
import java.util.Collections;
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
  private final Map<String, GroupConfig> groups;
  private final Map<String, String> routes;
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
  private final Locale defaultLocale;

  /** Set by {@link #load} before it returns the configuration, which is then never changed. */
  private @Nullable PlayerMessages messages;

  // ---------------------------------------------------------------------------
  // Layout
  // ---------------------------------------------------------------------------

  /** The layout of {@code warp.conf} this Warp writes and reads ({@code config-version}). */
  static final int CONFIG_VERSION = 1;

  /** The settings that exist: the keys of the default file, with their shapes. */
  private static final ConfigFile SCHEMA = ConfigFile.parse(defaultConfig()).normalized();

  /** Settings that exist without being in the default file: their absence means something. */
  private static final Set<String> OPTIONAL_KEYS =
      Set.of(
          "status.max-players",
          "status.version-name",
          "logging.levels.*",
          "groups.*",
          "groups.*.servers",
          "groups.*.strategy",
          "groups.*.weights",
          "groups.*.weights.*",
          "groups.*.capacity",
          "routes.*");

  /** Settings of earlier layouts, with what replaced them. */
  private static final Map<String, String> MOVED_KEYS =
      Map.of(
          "backend",
          "list the servers in the servers block, such as servers { lobby { address ="
              + " \"localhost:25565\" } }",
          "drain.message",
          "the message is warp.disconnect.restarting in messages/messages.properties, where it"
              + " can be overridden per language");

  // ---------------------------------------------------------------------------
  // Constructor (private — use load())
  // ---------------------------------------------------------------------------

  private WarpConfig(
      InetSocketAddress bind,
      boolean onlineMode,
      Map<String, InetSocketAddress> servers,
      @Nullable String defaultServer,
      List<String> fallbackOrder,
      Map<String, GroupConfig> groups,
      Map<String, String> routes,
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
      BungeeCordSettings bungeeCord,
      Locale defaultLocale) {
    this.defaultLocale = defaultLocale;
    this.bind = bind;
    this.onlineMode = onlineMode;
    this.servers = Map.copyOf(servers);
    this.defaultServer = defaultServer;
    this.fallbackOrder = List.copyOf(fallbackOrder);
    this.groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    this.routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
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

    boolean fresh = !Files.exists(configPath);
    if (fresh) {
      writeDefaultConfig(configPath);
      logger.info("Created default configuration file: {}", configPath);
    } else if (!Files.isRegularFile(configPath) || !Files.isReadable(configPath)) {
      throw ConfigurationException.of(
          List.of(
              new ConfigurationException.Problem(
                  configPath,
                  0,
                  null,
                  "cannot be read: "
                      + (Files.isDirectory(configPath)
                          ? "it is a directory. Move it away, and Warp writes a default file"
                          : "give the user running Warp read access to it"))));
    }

    // The layout first: a syntax error is reported with its line, and so is every unknown key.
    ConfigFile layout = ConfigFile.read(configPath);
    List<ConfigurationException.Problem> problems =
        new ArrayList<>(layout.compareWith(configPath, SCHEMA, OPTIONAL_KEYS, MOVED_KEYS));

    HoconConfigurationLoader loader = HoconConfigurationLoader.builder().path(configPath).build();
    CommentedConfigurationNode root;
    try {
      root = loader.load();
    } catch (IOException e) {
      throw ConfigurationException.of(
          List.of(
              new ConfigurationException.Problem(
                  configPath, 0, null, "cannot be read: " + e.getMessage())));
    }

    WarpConfig config = null;
    try {
      config = parse(root, directory, fresh);
    } catch (ConfigurationException e) {
      List<ConfigurationException.Problem> layoutProblems = List.copyOf(problems);
      for (ConfigurationException.Problem problem :
          e.locatedIn(configPath, layout::lineOf).problems()) {
        // A key the layout already reported, or one of the block it is in (a misspelt address,
        // then the address missing), is reported once, by the layout.
        if (layoutProblems.stream().noneMatch(p -> related(p.key(), problem.key()))) {
          problems.add(problem);
        }
      }
    }
    PlayerMessages messages = null;
    try {
      messages =
          PlayerMessages.load(
              directory, config != null ? config.defaultLocale : PlayerMessages.DEFAULT_LOCALE);
    } catch (ConfigurationException e) {
      problems.addAll(e.problems());
    }
    if (!problems.isEmpty() || config == null || messages == null) {
      throw ConfigurationException.of(problems);
    }
    config.messages = messages;
    logConfig(config);
    return config;
  }

  /**
   * Whether a value problem is about what a layout problem already reports: the same key, or a key
   * of the same block (other than the top level).
   */
  private static boolean related(@Nullable String layoutKey, @Nullable String valueKey) {
    if (layoutKey == null || valueKey == null) {
      return false;
    }
    if (layoutKey.equals(valueKey)) {
      return true;
    }
    int dot = layoutKey.lastIndexOf('.');
    return dot > 0 && valueKey.startsWith(layoutKey.substring(0, dot + 1));
  }

  /** Reads the settings of a configuration whose layout is valid. */
  private static WarpConfig parse(CommentedConfigurationNode root, Path directory, boolean fresh)
      throws ConfigurationException {
    // ---------------------------------------------------------------------------
    // Parse
    // ---------------------------------------------------------------------------

    int configVersion = intValue(root.node("config-version"), CONFIG_VERSION);
    if (configVersion > CONFIG_VERSION) {
      throw ConfigurationException.at(
          "config-version",
          "this file was written for a newer Warp (layout "
              + configVersion
              + "); this Warp reads layout "
              + CONFIG_VERSION
              + " and older. Run the Warp it was written for, or start from a fresh warp.conf");
    }
    if (configVersion < 1) {
      throw ConfigurationException.invalid(
          "config-version", "a layout from 1 to " + CONFIG_VERSION, configVersion);
    }

    InetSocketAddress bind = parseAddress(stringValue(root.node("bind"), DEFAULT_BIND), "bind");

    boolean onlineMode = booleanValue(root.node("online-mode"), DEFAULT_ONLINE_MODE);

    // Parse server list.
    Map<String, InetSocketAddress> servers = parseServers(root);
    Map<String, GroupConfig> groups = RoutingConfig.parseGroups(root, servers);
    @Nullable String defaultServer = parseDefaultServer(root, servers, groups);
    List<String> fallbackOrder = parseFallbackOrder(root, servers, groups, defaultServer);
    Map<String, String> routes = RoutingConfig.parseRoutes(root, servers, groups);

    DuplicateLoginPolicy duplicateLoginPolicy =
        parseDuplicateLoginPolicy(
            stringValue(root.node("duplicate-login"), DEFAULT_DUPLICATE_LOGIN));

    String modeStr = stringValue(root.node("forwarding", "mode"), DEFAULT_FORWARDING_MODE);
    ForwardingMode forwardingMode = parseForwardingMode(modeStr);

    String secretPath = stringValue(root.node("forwarding", "secret-file"), DEFAULT_SECRET_FILE);
    byte[] forwardingSecret = loadForwardingSecret(directory, secretPath, forwardingMode, fresh);

    int compressionThreshold =
        intValue(root.node("compression", "threshold"), DEFAULT_COMPRESSION_THRESHOLD);
    int compressionLevel = intValue(root.node("compression", "level"), DEFAULT_COMPRESSION_LEVEL);
    boolean compressionPassthrough =
        booleanValue(root.node("compression", "passthrough"), DEFAULT_COMPRESSION_PASSTHROUGH);

    boolean acceptsTransfers =
        booleanValue(root.node("accepts-transfers"), DEFAULT_ACCEPTS_TRANSFERS);

    String localeText = stringValue(root.node("messages", "default-locale"), "en_US");
    Locale defaultLocale = PlayerMessages.parseLocale(localeText);
    if (defaultLocale == null) {
      throw ConfigurationException.invalid(
          "messages.default-locale",
          "a language such as en_US, fr_FR or de",
          "'" + localeText + "'");
    }

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
      throw ConfigurationException.invalid(
          "compression.threshold",
          "a size in bytes, 0 or more, or -1 to turn compression off",
          compressionThreshold);
    }
    if (compressionLevel < -1 || compressionLevel > 9) {
      throw ConfigurationException.invalid(
          "compression.level",
          "a level from 0 (fastest) to 9 (smallest), or -1 for zlib's default",
          compressionLevel);
    }

    return new WarpConfig(
        bind,
        onlineMode,
        servers,
        defaultServer,
        fallbackOrder,
        groups,
        routes,
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
        bungeeCord,
        defaultLocale);
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns what the proxy tells players, in their languages, with the operator's overrides from
   * the {@code messages} directory.
   *
   * @return the player messages
   */
  public PlayerMessages messages() {
    PlayerMessages loaded = messages;
    return loaded != null ? loaded : PlayerMessages.bundled();
  }

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
   * Returns the server groups of the {@code groups} block.
   *
   * @return the groups by lowercase name, in the order of the file, unmodifiable
   */
  public Map<String, GroupConfig> groups() {
    return groups;
  }

  /**
   * Returns the routes of the {@code routes} block: which server or group a client connecting with
   * a host name goes to.
   *
   * @return normalized host keys to lowercase targets, in the order of the file, unmodifiable
   */
  public Map<String, String> routes() {
    return routes;
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
    String expected = "host:port, such as \"0.0.0.0:25577\" or \"localhost:25565\"";
    if (lastColon <= 0 || lastColon == value.length() - 1) {
      throw ConfigurationException.invalid(field, expected, "'" + value + "'");
    }
    String host = value.substring(0, lastColon);
    int port;
    try {
      port = Integer.parseInt(value.substring(lastColon + 1));
    } catch (NumberFormatException e) {
      throw ConfigurationException.invalid(
          field, expected + ", the port a number", "'" + value + "'");
    }
    if (port < 1 || port > 65535) {
      throw ConfigurationException.invalid(field, "a port from 1 to 65535", port);
    }
    return new InetSocketAddress(host, port);
  }

  private static Map<String, InetSocketAddress> parseServers(CommentedConfigurationNode root)
      throws ConfigurationException {
    CommentedConfigurationNode serversNode = root.node("servers");
    Map<String, InetSocketAddress> servers = new LinkedHashMap<>();

    if (!serversNode.virtual() && !serversNode.isMap() && !serversNode.empty()) {
      throw ConfigurationException.invalid(
          "servers",
          "a block of named servers, such as servers { lobby { address = \"localhost:25565\" } }",
          "'" + serversNode.raw() + "'");
    }

    if (!serversNode.virtual() && serversNode.isMap()) {
      for (var entry : serversNode.childrenMap().entrySet()) {
        String key = entry.getKey().toString();
        String name = key.toLowerCase(Locale.ROOT);
        String addressKey = "servers." + key + ".address";
        CommentedConfigurationNode address = entry.getValue().node("address");
        if (address.virtual()) {
          throw ConfigurationException.at(
              addressKey,
              "missing: each server needs its address, such as address = \"localhost:25565\"");
        }
        String addressStr = stringValue(address, "");
        if (addressStr.isBlank()) {
          throw ConfigurationException.invalid(addressKey, "host:port", "an empty address");
        }
        servers.put(name, parseAddress(addressStr, addressKey));
      }
    }

    // An explicit empty block means no static server: servers are registered at runtime.
    if (!serversNode.virtual()) {
      return servers;
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
      CommentedConfigurationNode root,
      Map<String, InetSocketAddress> servers,
      Map<String, GroupConfig> groups)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("default-server");
    if (servers.isEmpty()) {
      String configured = stringValue(node, "").toLowerCase(Locale.ROOT);
      if (configured.isBlank()) {
        return null;
      }
      if (groups.containsKey(configured)) {
        return configured;
      }
      throw ConfigurationException.at(
          "default-server",
          "'"
              + configured
              + "' is set but no server is configured: remove default-server, or add the server"
              + " to the servers block");
    }
    String defaultServer = stringValue(node, DEFAULT_SERVER_NAME).toLowerCase(Locale.ROOT);
    if (!servers.containsKey(defaultServer) && !groups.containsKey(defaultServer)) {
      throw unknownServer("default-server", defaultServer, servers, groups);
    }
    return defaultServer;
  }

  /** A name that is neither a server nor a group, with the closest one if it is a typo. */
  private static ConfigurationException unknownServer(
      String key,
      String name,
      Map<String, InetSocketAddress> servers,
      Map<String, GroupConfig> groups) {
    List<String> known = new ArrayList<>(servers.keySet());
    known.addAll(groups.keySet());
    String suggestion = Suggestions.closest(name, known);
    return ConfigurationException.invalid(
        key,
        (groups.isEmpty() ? "one of the servers block: " : "a server or group: ")
            + String.join(", ", known),
        "'" + name + "'" + (suggestion != null ? ". Did you mean '" + suggestion + "'?" : ""));
  }

  private static List<String> parseFallbackOrder(
      CommentedConfigurationNode root,
      Map<String, InetSocketAddress> servers,
      Map<String, GroupConfig> groups,
      @Nullable String defaultServer)
      throws ConfigurationException {
    CommentedConfigurationNode fallbackNode = root.node("fallback-order");
    if (!fallbackNode.virtual() && !fallbackNode.isList()) {
      throw ConfigurationException.invalid(
          "fallback-order", "a list of server names, such as [\"lobby\"]", fallbackNode.raw());
    }

    if (defaultServer == null) {
      if (!fallbackNode.virtual() && !fallbackNode.childrenList().isEmpty()) {
        throw ConfigurationException.at(
            "fallback-order",
            "lists servers, but the servers block has none: remove fallback-order, or add the"
                + " servers");
      }
      return List.of();
    }

    if (fallbackNode.virtual() || fallbackNode.childrenList().isEmpty()) {
      // Not configured: default to [defaultServer], the documented behaviour.
      return List.of(defaultServer);
    }

    List<String> result = new ArrayList<>();
    for (var child : fallbackNode.childrenList()) {
      String name = stringValue(child, "");
      String key = name.toLowerCase(Locale.ROOT);
      if (!servers.containsKey(key) && !groups.containsKey(key)) {
        throw unknownServer("fallback-order", name, servers, groups);
      }
      result.add(key);
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
          LogCode.MOTD_LEGACY_COLORS.marker(),
          "status.motd contains legacy color codes (such as &a), shown as typed: use MiniMessage"
              + " tags instead, such as <green>");
    }
    long lines = PlainTextComponentSerializer.plainText().serialize(motd).lines().count();
    if (lines > MOTD_LINES) {
      logger.warn(
          LogCode.MOTD_TOO_MANY_LINES.marker(),
          "status.motd has {} lines: clients only show the first {}",
          lines,
          MOTD_LINES);
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
    throw ConfigurationException.invalid(path(node), "a whole number", describe(node));
  }

  /** What a node holds, for "found ..." in errors. */
  static String describe(ConfigurationNode node) {
    if (node.isMap()) {
      return "a block { ... }";
    }
    if (node.isList()) {
      return "a list [ ... ]";
    }
    return "'" + node.raw() + "'";
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
    throw ConfigurationException.invalid(path(node), "true or false", describe(node));
  }

  static String stringValue(ConfigurationNode node, String fallback) throws ConfigurationException {
    if (node.virtual()) {
      return fallback;
    }
    String value = node.isMap() || node.isList() ? null : node.getString();
    if (value == null) {
      throw ConfigurationException.invalid(path(node), "a text in quotes", describe(node));
    }
    return value;
  }

  /** The dotted path of a node, such as {@code status.max-players}, for error messages. */
  static String path(ConfigurationNode node) {
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
    boolean enabled = booleanValue(section.node("enabled"), false);

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
            LogCode.TRUSTED_PROXIES_EVERYONE.marker(),
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

    boolean enabled = booleanValue(section.node("enabled"), defaults.enabled());
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

  /**
   * Reads the forwarding secret: from {@code WARP_FORWARDING_SECRET}, else from the secret file.
   * The default file, {@code forwarding.secret}, is generated when it does not exist, as
   * documented; a file the operator named must exist, since generating a secret the backends do not
   * have would lock every player out (an unmounted secret volume, a typo in the path).
   */
  private static byte[] loadForwardingSecret(
      Path directory, String secretPath, ForwardingMode mode, boolean fresh)
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

    String key = "forwarding.secret-file";
    String fix =
        "Put the secret your backends use in it (Paper: proxies.velocity.secret in"
            + " config/paper-global.yml), or set "
            + ENV_FORWARDING_SECRET;
    Path file = directory.resolve(secretPath);
    if (!Files.exists(file)) {
      if (!secretPath.equals(DEFAULT_SECRET_FILE)) {
        throw ConfigurationException.at(
            key,
            file + " does not exist. " + fix + ", or remove secret-file to use a generated one");
      }
      String generated = generateSecret();
      try {
        Files.writeString(file, generated, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw ConfigurationException.at(
            key,
            "cannot create "
                + file
                + " ("
                + e
                + "): give the user running Warp write access to its directory, or set "
                + ENV_FORWARDING_SECRET);
      }
      if (fresh) {
        logger.info("Generated a random forwarding secret in {}", file);
      } else {
        logger.warn(
            LogCode.FORWARDING_SECRET_GENERATED.marker(),
            "{} did not exist: generated a new forwarding secret. Give it to every backend (Paper:"
                + " proxies.velocity.secret in config/paper-global.yml), or players cannot join",
            file);
      }
    }
    if (Files.isDirectory(file)) {
      throw ConfigurationException.at(key, file + " is a directory, not a file. " + fix);
    }

    String secret;
    try {
      secret = Files.readString(file, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw ConfigurationException.at(
          key,
          "cannot read "
              + file
              + " ("
              + e.getClass().getSimpleName()
              + "): give the user running Warp read access to it, or set "
              + ENV_FORWARDING_SECRET);
    }

    if (secret.isEmpty()) {
      throw ConfigurationException.at(key, file + " is empty. " + fix);
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
    try {
      Files.writeString(path, defaultConfig(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ConfigurationException.of(
          List.of(
              new ConfigurationException.Problem(
                  path,
                  0,
                  null,
                  "cannot be created ("
                      + e
                      + "): give the user running Warp write access to its directory")));
    }
  }

  /**
   * Returns the default {@code warp.conf}, which documents every setting: the one Warp writes on
   * first start, and the reference unknown keys are found against.
   *
   * @return the file's text
   */
  static String defaultConfig() {
    return """
        # Warp Proxy Configuration, in HOCON (https://github.com/lightbend/config/blob/main/HOCON.md).
        #
        # Warp checks this file when it starts, and refuses to start if anything is wrong with it: a
        # key it does not know (with the closest one it does), a value of the wrong kind, a server
        # that does not exist. Each error names the line. A key left out takes the default shown
        # here. The messages players see are overridden in the messages directory next to this file.
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

        # The layout of this file. Warp reads its own layout and older ones, and refuses a file written
        # for a newer Warp rather than misread it. Leave it as it is.
        config-version = 1

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
        # Defaults to [default-server] if omitted. Entries may be groups.
        fallback-order = ["lobby"]

        # Server groups: several servers a strategy spreads players over. A group stands wherever
        # a server name does: default-server, fallback-order, or the target of a route.
        # Strategies: round-robin, least-connections (default), lowest-latency, random,
        # weighted (with weights { lobby1 = 3, lobby2 = 1 }), fill (with capacity = 100).
        # A server that failed its last ping or a player's connection is skipped for 10 seconds,
        # then one player is let through to find out whether it is back.
        #   groups {
        #     lobbies {
        #       servers = ["lobby1", "lobby2"]
        #       strategy = "least-connections"
        #     }
        #   }
        groups {}

        # Routes: the server or group a player goes to for the host name they connected with.
        # Hosts are case-insensitive; a port (":25566") restricts an entry to that port.
        # "*.example.com" matches every subdomain, and "$1" in the target stands for the part
        # the * matched: "*.play.example.com" = "$1" sends kitpvp.play.example.com to kitpvp.
        # The most specific entry wins; a host with no entry goes to default-server.
        #   routes {
        #     "play.example.com" = "lobbies"
        #     "survival.example.com" = "survival"
        #     "*.mini.example.com" = "$1"
        #   }
        routes {}

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

          # A plugin's RegisteredServer.ping() without a time-out of its own, and the proxy's own
          # pings of the servers every 10s: connection, handshake and answer. A server that does not
          # answer in time fails the ping with TIMED_OUT, and the groups and fallbacks skip it until
          # it answers again.
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

          # Levels of log sources, kept across restarts: a source as the console names it (antibot,
          # conn, drain, warp, plugin:<id>, netty...) or a logger name, then error, warn, info, debug
          # or trace. Quote names with a colon or dots. /log level changes a level until the next
          # restart, for instance levels { antibot = debug, "plugin:showcase" = trace }
          levels {}
        }

        console {
          # The console's colours: auto follows the terminal's background (light or dark, as the
          # terminal answers or COLORFGBG says), dark, light, or colorblind (blue and orange where
          # others use green and red, for the background found). WARP_THEME takes precedence. A log
          # file is never coloured.
          theme = auto
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
          # TLS on this port, from PEM files read again when they change (cert-manager renewals).
          # The key must be PKCS#8 ("BEGIN PRIVATE KEY"). Probes then use scheme HTTPS.
          tls {
            certificate = ""
            key = ""
          }
          # Remote console, for hosts without a terminal (Kubernetes, panels, docker without -it):
          #   GET /console/logs       the live log (Server-Sent Events), with a replay
          #   POST /console/command   runs a command, answers its output
          # Attach with: java -jar warp.jar console attach --url http://host:9901 --token-file f
          console {
            enabled = false
            # Who may attach: a name (in the audit log), scopes (logs, commands) and one of file,
            # env (a variable) or sha256 (the token's digest, so that no file here holds it).
            # Tokens have at least 32 characters (openssl rand -hex 32), never the drain token's.
            tokens = [
              # { name = "ops", scopes = [logs, commands], file = "console-ops.token" }
              # { name = "dashboard", scopes = [logs], env = "WARP_CONSOLE_DASHBOARD_TOKEN" }
            ]
            # Without TLS, commands are accepted from loopback only, and from these networks: a
            # VPN, or the ingress that terminates TLS in front of Warp.
            trusted-networks = []
            # Lines kept for the replay of a client that attaches or reconnects.
            history = 1000
            # Wrong tokens from one address (IPv6: its /64) before it is locked out, and how long.
            lockout-after = 5
            lockout-duration = 15m
          }
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
          # The players the drain disconnects and the logins it refuses are told the message
          # warp.disconnect.restarting, in their language: override it in the messages directory.
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

        # ---------------------------------------------------------------------------
        # Player messages
        # ---------------------------------------------------------------------------
        # Warp talks to each player in the language of its game: English and French are bundled,
        # any other language falls back to English. Every message can be overridden, for every
        # language or for one, in the messages directory next to this file: Warp writes a template
        # there on first start, listing them all.
        messages {
          # The language for a player whose game has not said which one it uses yet: one refused
          # at login on its first visit. On later visits, Warp uses the language it last played in.
          # A code such as en_US, fr_FR, de or pt_BR.
          default-locale = "en_US"
        }
        """
        .replace("{default-version-name}", StatusSettings.defaultVersionName());
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
    for (GroupConfig group : config.groups.values()) {
      logger.info(
          ConsoleMarkers.FILE_ONLY,
          "  group '{}': {} {}",
          group.name(),
          group.strategy(),
          group.servers());
    }
    for (var route : config.routes.entrySet()) {
      logger.info(ConsoleMarkers.FILE_ONLY, "  route '{}' -> {}", route.getKey(), route.getValue());
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
