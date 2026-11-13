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

import java.net.InetSocketAddress;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code query} section: the GS4 Query listener, a UDP protocol server lists, hosting panels
 * and monitoring agents use to read the MOTD, the counts and the player names.
 *
 * @param enabled whether the listener runs; off by default, as in Velocity and Gate
 * @param bind the UDP address it listens on: {@code query.bind}, or the proxy's {@code bind} with
 *     the same port number when empty
 * @param map the {@code map} field of the replies
 * @param showPlugins whether a full stat lists the plugins after Warp's version
 * @param playerNames how many player names a full stat lists; 0 omits the list
 * @param advertiseHost the host written as {@code hostip}, or empty to derive it
 * @param rateLimit the token buckets applied before a datagram is parsed
 */
public record QueryConfig(
    boolean enabled,
    InetSocketAddress bind,
    String map,
    boolean showPlugins,
    int playerNames,
    String advertiseHost,
    RateLimit rateLimit) {

  /** The default {@code map}. */
  public static final String DEFAULT_MAP = "Warp";

  /** The most names a full stat lists: more would not fit the reply anyway. */
  public static final int MAX_PLAYER_NAMES = 1000;

  /** The longest {@code map} written: the reply is capped and the field is informative. */
  static final int MAX_MAP_LENGTH = 64;

  /** The most datagrams per second one key may send, or the whole listener may receive. */
  static final int MAX_RATE = 1_000_000;

  /**
   * The token buckets of the listener, checked before any byte of a datagram is read.
   *
   * @param perAddress datagrams per second allowed from one source address (one IPv6 /64)
   * @param burst how many datagrams a source may send at once before its rate applies
   * @param global datagrams per second allowed from every source together
   */
  public record RateLimit(int perAddress, int burst, int global) {

    /** The defaults: a monitoring agent polls once or twice a second, a panel far less. */
    public static final RateLimit DEFAULTS = new RateLimit(5, 10, 2000);

    /** Validates the limits. */
    public RateLimit {
      if (perAddress < 1 || perAddress > MAX_RATE) {
        throw new IllegalArgumentException("perAddress must be 1 to " + MAX_RATE);
      }
      if (burst < perAddress || burst > MAX_RATE) {
        throw new IllegalArgumentException("burst must be perAddress to " + MAX_RATE);
      }
      if (global < perAddress || global > MAX_RATE) {
        throw new IllegalArgumentException("global must be perAddress to " + MAX_RATE);
      }
    }
  }

  /**
   * Returns the section of a {@code warp.conf} without {@code query} block: off.
   *
   * @param proxyBind the proxy's {@code bind}, whose address and port the query listener takes
   * @return the defaults
   */
  public static QueryConfig defaults(InetSocketAddress proxyBind) {
    return new QueryConfig(
        false, udpTwin(proxyBind), DEFAULT_MAP, false, 0, "", RateLimit.DEFAULTS);
  }

  /**
   * Reads the section.
   *
   * @param root the configuration's root node
   * @param proxyBind the proxy's {@code bind}, for an empty {@code query.bind}
   * @return the section's values
   * @throws ConfigurationException if a value is invalid
   */
  static QueryConfig parse(CommentedConfigurationNode root, InetSocketAddress proxyBind)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("query");
    boolean enabled = WarpConfig.booleanValue(node.node("enabled"), false);
    String bindText = WarpConfig.stringValue(node.node("bind"), "").trim();
    InetSocketAddress bind =
        bindText.isEmpty() ? udpTwin(proxyBind) : WarpConfig.parseAddress(bindText, "query.bind");
    String map = WarpConfig.stringValue(node.node("map"), DEFAULT_MAP);
    if (map.isBlank() || map.length() > MAX_MAP_LENGTH || hasControlCharacter(map)) {
      throw ConfigurationException.invalid(
          "query.map",
          "a name of 1 to " + MAX_MAP_LENGTH + " printable characters",
          map.isBlank() ? "a blank name" : map);
    }
    boolean showPlugins = WarpConfig.booleanValue(node.node("show-plugins"), false);
    int playerNames = WarpConfig.intValue(node.node("player-names"), 0);
    if (playerNames < 0 || playerNames > MAX_PLAYER_NAMES) {
      throw ConfigurationException.invalid(
          "query.player-names", "a count from 0 (no names) to " + MAX_PLAYER_NAMES, playerNames);
    }
    String advertiseHost = WarpConfig.stringValue(node.node("advertise-host"), "").trim();
    if (advertiseHost.length() > 253 || hasControlCharacter(advertiseHost)) {
      throw ConfigurationException.invalid(
          "query.advertise-host", "a host name or address, or empty", advertiseHost);
    }
    CommentedConfigurationNode limits = node.node("rate-limit");
    int perAddress =
        WarpConfig.intValue(limits.node("per-address"), RateLimit.DEFAULTS.perAddress());
    int burst = WarpConfig.intValue(limits.node("burst"), RateLimit.DEFAULTS.burst());
    int global = WarpConfig.intValue(limits.node("global"), RateLimit.DEFAULTS.global());
    if (perAddress < 1 || perAddress > MAX_RATE) {
      throw ConfigurationException.invalid(
          "query.rate-limit.per-address", "datagrams per second, 1 to " + MAX_RATE, perAddress);
    }
    if (burst < perAddress || burst > MAX_RATE) {
      throw ConfigurationException.invalid(
          "query.rate-limit.burst",
          "a count from per-address (" + perAddress + ") to " + MAX_RATE,
          burst);
    }
    if (global < perAddress || global > MAX_RATE) {
      throw ConfigurationException.invalid(
          "query.rate-limit.global",
          "datagrams per second, from per-address (" + perAddress + ") to " + MAX_RATE,
          global);
    }
    return new QueryConfig(
        enabled,
        bind,
        map,
        showPlugins,
        playerNames,
        advertiseHost,
        new RateLimit(perAddress, burst, global));
  }

  /** The same host and port as a TCP address, for the UDP listener. */
  private static InetSocketAddress udpTwin(InetSocketAddress tcp) {
    return tcp.isUnresolved()
        ? InetSocketAddress.createUnresolved(tcp.getHostString(), tcp.getPort())
        : new InetSocketAddress(tcp.getAddress(), tcp.getPort());
  }

  private static boolean hasControlCharacter(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) < ' ' || text.charAt(i) == 0x7F) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    if (!enabled) {
      return "off";
    }
    return "on "
        + bind
        + "/udp, map "
        + map
        + ", plugins "
        + (showPlugins ? "shown" : "hidden")
        + ", "
        + playerNames
        + " names, "
        + rateLimit.perAddress()
        + "/s per address (burst "
        + rateLimit.burst()
        + "), "
        + rateLimit.global()
        + "/s global";
  }
}
