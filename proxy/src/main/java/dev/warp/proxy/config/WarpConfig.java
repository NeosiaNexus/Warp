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

import dev.warp.proxy.network.CidrRange;
import dev.warp.proxy.network.CidrRanges;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
  private static final String DEFAULT_SERVER_NAME = "lobby";
  private static final String DEFAULT_SERVER_ADDRESS = "localhost:25565";
  private static final String DEFAULT_FORWARDING_MODE = "velocity";
  private static final String DEFAULT_SECRET_FILE = "forwarding.secret";
  private static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
  private static final int DEFAULT_COMPRESSION_LEVEL = -1;
  private static final boolean DEFAULT_COMPRESSION_PASSTHROUGH = true;

  /** Characters used for auto-generated forwarding secret. */
  private static final String SECRET_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private static final int SECRET_LENGTH = 12;

  /** A whole number of zero or more, for counts. */
  private static final Pattern COUNT = Pattern.compile("\\d{1,10}");

  /** A duration: digits and an optional unit, milliseconds without one. */
  private static final Pattern DURATION = Pattern.compile("(\\d{1,15})\\s*(ms|s|m|h)?");

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  private final InetSocketAddress bind;
  private final boolean onlineMode;
  private final Map<String, InetSocketAddress> servers;
  private final String defaultServer;
  private final List<String> fallbackOrder;
  private final ForwardingMode forwardingMode;
  private final byte[] forwardingSecret;
  private final int compressionThreshold;
  private final int compressionLevel;
  private final boolean compressionPassthrough;
  private final ProxyProtocolConfig proxyProtocol;
  private final LimitsConfig limits;

  // ---------------------------------------------------------------------------
  // Constructor (private — use load())
  // ---------------------------------------------------------------------------

  private WarpConfig(
      InetSocketAddress bind,
      boolean onlineMode,
      Map<String, InetSocketAddress> servers,
      String defaultServer,
      List<String> fallbackOrder,
      ForwardingMode forwardingMode,
      byte[] forwardingSecret,
      int compressionThreshold,
      int compressionLevel,
      boolean compressionPassthrough,
      ProxyProtocolConfig proxyProtocol,
      LimitsConfig limits) {
    this.bind = bind;
    this.onlineMode = onlineMode;
    this.servers = Map.copyOf(servers);
    this.defaultServer = defaultServer;
    this.fallbackOrder = List.copyOf(fallbackOrder);
    this.forwardingMode = forwardingMode;
    this.forwardingSecret = forwardingSecret;
    this.compressionThreshold = compressionThreshold;
    this.compressionLevel = compressionLevel;
    this.compressionPassthrough = compressionPassthrough;
    this.proxyProtocol = proxyProtocol;
    this.limits = limits;
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
    String defaultServer =
        root.node("default-server").getString(DEFAULT_SERVER_NAME).toLowerCase(Locale.ROOT);

    if (!servers.containsKey(defaultServer)) {
      throw new ConfigurationException(
          "default-server '" + defaultServer + "' not found in servers list: " + servers.keySet());
    }

    List<String> fallbackOrder = parseFallbackOrder(root, servers, defaultServer);

    String modeStr = root.node("forwarding", "mode").getString(DEFAULT_FORWARDING_MODE);
    ForwardingMode forwardingMode = parseForwardingMode(modeStr);

    String secretPath = root.node("forwarding", "secret-file").getString(DEFAULT_SECRET_FILE);
    byte[] forwardingSecret = loadForwardingSecret(directory, secretPath, forwardingMode);

    int compressionThreshold =
        root.node("compression", "threshold").getInt(DEFAULT_COMPRESSION_THRESHOLD);
    int compressionLevel = root.node("compression", "level").getInt(DEFAULT_COMPRESSION_LEVEL);
    boolean compressionPassthrough =
        root.node("compression", "passthrough").getBoolean(DEFAULT_COMPRESSION_PASSTHROUGH);

    ProxyProtocolConfig proxyProtocol = parseProxyProtocol(root);
    LimitsConfig limits = parseLimits(root);

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
            forwardingMode,
            forwardingSecret,
            compressionThreshold,
            compressionLevel,
            compressionPassthrough,
            proxyProtocol,
            limits);

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
   * @return the default server name
   */
  public String defaultServer() {
    return defaultServer;
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

  private static Map<String, InetSocketAddress> parseServers(CommentedConfigurationNode root)
      throws ConfigurationException {
    CommentedConfigurationNode serversNode = root.node("servers");
    Map<String, InetSocketAddress> servers = new LinkedHashMap<>();

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

  private static List<String> parseFallbackOrder(
      CommentedConfigurationNode root,
      Map<String, InetSocketAddress> servers,
      String defaultServer) {
    CommentedConfigurationNode fallbackNode = root.node("fallback-order");

    if (fallbackNode.virtual()
        || (fallbackNode.isList() && fallbackNode.childrenList().isEmpty())) {
      // Not configured — default to [defaultServer].
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
  private static CidrRanges parseCidrRanges(CommentedConfigurationNode node, String key)
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
    return new LimitsConfig(
        enabled,
        exempt,
        connections,
        statusPings,
        logins,
        perAddress,
        authentications,
        loginTimeout,
        reportInterval);
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
  private static int parseCount(CommentedConfigurationNode node, String key, int defaultValue)
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
   * Parses a duration: a whole number followed by {@code ms}, {@code s}, {@code m} or {@code h}, or
   * a bare number of milliseconds, as Velocity's {@code login-ratelimit = 3000}. An absent key
   * takes {@code defaultValue}.
   */
  static Duration parseDuration(CommentedConfigurationNode node, String key, Duration defaultValue)
      throws ConfigurationException {
    if (node.virtual()) {
      return defaultValue;
    }
    String value = String.valueOf(node.getString()).strip();
    Matcher matcher = DURATION.matcher(value);
    if (!matcher.matches()) {
      throw new ConfigurationException(
          key + " must be a duration such as 200ms, 3s or 1m, got '" + value + "'");
    }
    long amount;
    try {
      amount = Long.parseLong(matcher.group(1));
    } catch (NumberFormatException e) {
      throw new ConfigurationException(key + " is too large: " + value, e);
    }
    String unit = matcher.group(2);
    return switch (unit == null ? "ms" : unit) {
      case "s" -> Duration.ofSeconds(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      default -> Duration.ofMillis(amount);
    };
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
        servers {
          lobby {
            address = "localhost:25565"
          }
        }

        # The server players are sent to when they first connect.
        default-server = "lobby"

        # Servers tried in order when a player is kicked from their current server.
        # The failed server is skipped. If all fail, the player is disconnected.
        # Defaults to [default-server] if omitted.
        fallback-order = ["lobby"]

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

          # Forward packets Warp does not inspect in their original compressed form instead of
          # decompressing and recompressing them. Requires compression on the backends too: keep
          # network-compression-threshold at the same value as `threshold` above (Paper's default,
          # 256) rather than -1. Disable only to troubleshoot.
          passthrough = true
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
        # an interval of 0 lifts that limit. Durations take ms, s, m or h (a bare number is ms).
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

          # Server list pings, counted apart from logins: refreshing the server list never locks
          # a player out. A refused ping gets no answer.
          status-pings {
            burst = 10
            interval = 1s
          }

          # Login attempts. A refused player is told to wait. One every 3s matches Velocity's
          # login-ratelimit = 3000; the burst of 2 lets two players behind one address (a
          # household) join together. Set burst = 1 for Velocity's exact behaviour.
          logins {
            burst = 2
            interval = 3s

            # Logins in progress at once from one address, from its handshake until the player is
            # logged in. 0 for no limit.
            max-concurrent-per-address = 2

            # Online mode: logins Warp authenticates at once, for the whole proxy (the RSA
            # decryption and the request to Mojang's session server). Only logins that sent their
            # encryption response count, so bots that stall earlier take none. 0 for no limit.
            max-concurrent-authentications = 256

            # A login that takes longer is closed, with its place among its address's logins in
            # progress. 0 for no timeout.
            timeout = 30s
          }

          # How often the counts of refused connections and logins are logged, when not zero.
          report-interval = 1m
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
    logger.info("  default-server: {}", config.defaultServer);
    for (var entry : config.servers.entrySet()) {
      logger.info("  server '{}': {}", entry.getKey(), entry.getValue());
    }
    logger.info("  fallback-order: {}", config.fallbackOrder);
    logger.info("  forwarding: {}", config.forwardingMode.name().toLowerCase(Locale.ROOT));
    logger.info(
        "  compression: threshold={}, level={}, passthrough={}",
        config.compressionThreshold,
        config.compressionLevel,
        config.compressionPassthrough);
    if (config.proxyProtocol.enabled()) {
      logger.info(
          "  proxy-protocol: required from trusted proxies {}",
          config.proxyProtocol.trustedProxies());
    } else {
      logger.info("  proxy-protocol: disabled");
    }
    LimitsConfig limits = config.limits;
    if (limits.enabled()) {
      logger.info(
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
          "  limits: {} authentications at once, logins time out after {}",
          limits.maxConcurrentAuthentications() == 0
              ? "any number of"
              : limits.maxConcurrentAuthentications(),
          limits.loginTimeout().isZero() ? "never" : LimitsConfig.format(limits.loginTimeout()));
    } else {
      logger.info("  limits: disabled");
    }
  }
}
