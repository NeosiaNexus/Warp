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

import java.time.Duration;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code fleet} section of {@code warp.conf}: how several proxies share one store and act as
 * one network.
 *
 * @param store which store backs the fleet, and how to reach it
 * @param network the network's name: proxies of the same network share players and messages
 * @param nodeId this proxy's id in the network, or {@code null} to use the host name
 * @param publicAddress the {@code host:port} other proxies transfer players to, or {@code null}
 * @param heartbeat how often the node writes its heartbeat
 * @param heartbeatTtl how long a heartbeat lives in the store: a node silent that long is dead
 * @param reconcile how often the node compares its view with the store
 * @param claimTimeout how long a login waits for the store to settle who owns the player
 * @param onStoreDown what logins do while the store is unreachable
 * @param networkCount whether the server list shows the network's player count, or this proxy's
 */
public record FleetConfig(
    Store store,
    String network,
    @Nullable String nodeId,
    @Nullable String publicAddress,
    Duration heartbeat,
    Duration heartbeatTtl,
    Duration reconcile,
    Duration claimTimeout,
    OnStoreDown onStoreDown,
    boolean networkCount) {

  static final String DEFAULT_NETWORK = "main";
  static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(2);
  static final Duration DEFAULT_HEARTBEAT_TTL = Duration.ofSeconds(10);
  static final Duration DEFAULT_RECONCILE = Duration.ofSeconds(30);
  static final Duration DEFAULT_CLAIM_TIMEOUT = Duration.ofSeconds(2);

  /** The section as a fresh file writes it: no store, this proxy alone. */
  public static final FleetConfig DEFAULTS =
      new FleetConfig(
          new Store(StoreType.NONE, new Valkey("valkey:6379", false, null, null, 0)),
          DEFAULT_NETWORK,
          null,
          null,
          DEFAULT_HEARTBEAT,
          DEFAULT_HEARTBEAT_TTL,
          DEFAULT_RECONCILE,
          DEFAULT_CLAIM_TIMEOUT,
          OnStoreDown.ALLOW,
          false);

  /** The stores Warp ships. */
  public enum StoreType {
    /** No store: the fleet is this proxy alone. */
    NONE,
    /** A Valkey (or Redis 6.2+) server or cluster. */
    VALKEY
  }

  /** What logins do while the store is unreachable. */
  public enum OnStoreDown {
    /** Let players in; duplicates are settled on this proxy alone until the store is back. */
    ALLOW,
    /** Refuse new logins until the store is back; connected players are unaffected. */
    REFUSE_NEW_LOGINS
  }

  /**
   * Which store backs the fleet.
   *
   * @param type the store
   * @param valkey how to reach Valkey, read whatever the type
   */
  public record Store(StoreType type, Valkey valkey) {}

  /**
   * How to reach Valkey.
   *
   * @param address {@code host:port} of the server, or of any node of the cluster
   * @param tls whether the connection is TLS
   * @param username the ACL user, or {@code null} for the default user
   * @param password the password, or {@code null} for none: from the file named in {@code
   *     password-file}, or the {@code WARP_FLEET_PASSWORD} variable
   * @param database the logical database, 0 on a cluster
   */
  public record Valkey(
      String address,
      boolean tls,
      @Nullable String username,
      @Nullable String password,
      int database) {}

  /**
   * Returns whether a store is configured: the proxy is one of several.
   *
   * @return {@code true} unless the type is {@link StoreType#NONE}
   */
  public boolean enabled() {
    return store.type() != StoreType.NONE;
  }

  /**
   * Reads the section.
   *
   * @param root the configuration's root node
   * @param env the environment, for the password variable
   * @return the section's values
   * @throws ConfigurationException if a value is invalid
   */
  static FleetConfig parse(
      CommentedConfigurationNode root, java.util.function.Function<String, @Nullable String> env)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("fleet");
    CommentedConfigurationNode storeNode = node.node("store");
    StoreType type = parseEnum(StoreType.class, storeNode, "type", StoreType.NONE, "fleet.store.");
    CommentedConfigurationNode valkeyNode = storeNode.node("valkey");
    String address = WarpConfig.stringValue(valkeyNode.node("address"), "valkey:6379").trim();
    if (type == StoreType.VALKEY && address.isEmpty()) {
      throw new ConfigurationException("fleet.store.valkey.address is required with type valkey");
    }
    boolean tls = WarpConfig.booleanValue(valkeyNode.node("tls"), false);
    String username = blankToNull(WarpConfig.stringValue(valkeyNode.node("username"), ""));
    String passwordFile = WarpConfig.stringValue(valkeyNode.node("password-file"), "").trim();
    String password = blankToNull(env.apply("WARP_FLEET_PASSWORD"));
    if (!passwordFile.isEmpty()) {
      try {
        password =
            blankToNull(
                java.nio.file.Files.readString(java.nio.file.Path.of(passwordFile)).strip());
      } catch (java.io.IOException e) {
        throw new ConfigurationException(
            "fleet.store.valkey.password-file cannot be read: " + e.getMessage(), e);
      }
    }
    int database = WarpConfig.intValue(valkeyNode.node("database"), 0);
    if (database < 0) {
      throw new ConfigurationException("fleet.store.valkey.database must be 0 or more");
    }
    Store store = new Store(type, new Valkey(address, tls, username, password, database));

    String network = WarpConfig.stringValue(node.node("network"), DEFAULT_NETWORK).trim();
    if (network.isEmpty() || !network.matches("[A-Za-z0-9_.-]+")) {
      throw new ConfigurationException(
          "fleet.network must be letters, digits, '-', '_' or '.', got '" + network + "'");
    }
    String nodeId = blankToNull(WarpConfig.stringValue(node.node("node-id"), ""));
    if (nodeId != null && !nodeId.matches("[A-Za-z0-9_.-]+")) {
      throw new ConfigurationException(
          "fleet.node-id must be letters, digits, '-', '_' or '.', got '" + nodeId + "'");
    }
    String publicAddress = blankToNull(WarpConfig.stringValue(node.node("public-address"), ""));
    if (publicAddress != null) {
      var _ = DrainConfig.parseTarget(publicAddress); // validates host:port
    }
    Duration heartbeat = duration(node, "heartbeat", DEFAULT_HEARTBEAT);
    Duration heartbeatTtl = duration(node, "heartbeat-ttl", DEFAULT_HEARTBEAT_TTL);
    if (heartbeatTtl.compareTo(heartbeat.multipliedBy(2)) < 0) {
      throw new ConfigurationException(
          "fleet.heartbeat-ttl must be at least twice fleet.heartbeat, or one missed heartbeat"
              + " kills the node");
    }
    Duration reconcile = duration(node, "reconcile", DEFAULT_RECONCILE);
    Duration claimTimeout = duration(node, "claim-timeout", DEFAULT_CLAIM_TIMEOUT);
    OnStoreDown onStoreDown =
        parseEnum(OnStoreDown.class, node, "on-store-down", OnStoreDown.ALLOW, "fleet.");
    boolean networkCount =
        WarpConfig.booleanValue(node.node("status", "network-count"), type != StoreType.NONE);
    return new FleetConfig(
        store,
        network,
        nodeId,
        publicAddress,
        heartbeat,
        heartbeatTtl,
        reconcile,
        claimTimeout,
        onStoreDown,
        networkCount);
  }

  private static Duration duration(CommentedConfigurationNode node, String key, Duration fallback)
      throws ConfigurationException {
    Duration duration = Durations.parse(node.node(key), "fleet." + key, fallback);
    if (duration.isZero() || duration.isNegative()) {
      throw new ConfigurationException("fleet." + key + " must be more than zero");
    }
    return duration;
  }

  private static <E extends Enum<E>> E parseEnum(
      Class<E> type, CommentedConfigurationNode node, String key, E fallback, String prefix)
      throws ConfigurationException {
    String value = node.node(key).getString();
    if (value == null) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      StringBuilder allowed = new StringBuilder();
      for (E constant : type.getEnumConstants()) {
        if (!allowed.isEmpty()) {
          allowed.append(", ");
        }
        allowed.append(constant.name().toLowerCase(Locale.ROOT).replace('_', '-'));
      }
      throw new ConfigurationException(
          prefix + key + " must be one of " + allowed + "; got '" + value + "'", e);
    }
  }

  private static @Nullable String blankToNull(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @Override
  public String toString() {
    return "store "
        + store.type().name().toLowerCase(Locale.ROOT)
        + ", network "
        + network
        + ", node-id "
        + (nodeId == null ? "(hostname)" : nodeId)
        + ", heartbeat "
        + Durations.format(heartbeat)
        + "/"
        + Durations.format(heartbeatTtl)
        + ", reconcile "
        + Durations.format(reconcile);
  }
}
