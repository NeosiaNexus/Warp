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

import dev.warp.proxy.i18n.Message;

import java.time.Duration;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code drain} section of {@code warp.conf}: how the proxy empties itself before it stops.
 *
 * @param transferTo where players are transferred (1.20.5+), {@link Target#FLEET} for the least
 *     loaded ready proxy of the fleet, or {@code null} to transfer nobody
 * @param onSigterm whether SIGTERM starts a drain instead of stopping at once
 * @param propagationDelay how long load balancers get to see the drain before players move
 * @param transferInterval the time between two transfers (or kicks)
 * @param cannotTransfer what happens to a player the proxy cannot transfer
 * @param newPlayers what happens to a player who logs in while the proxy drains
 * @param timeout how long the proxy waits for the players it kept, once the transfers are done
 * @param message shown to the players the drain disconnects and to the logins it refuses: {@link
 *     Message#RESTARTING}, rendered in each player's language
 */
public record DrainConfig(
    @Nullable Target transferTo,
    boolean onSigterm,
    Duration propagationDelay,
    Duration transferInterval,
    CannotTransfer cannotTransfer,
    NewPlayers newPlayers,
    Duration timeout,
    Component message) {

  /** Minecraft's default port, for a {@code transfer-to} without one. */
  static final int DEFAULT_PORT = 25565;

  static final Duration DEFAULT_PROPAGATION_DELAY = Duration.ofSeconds(5);
  static final Duration DEFAULT_TRANSFER_INTERVAL = Duration.ofMillis(100);
  static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

  /**
   * An address players are transferred to.
   *
   * @param host the host name or address, as the client gets it
   * @param port the port
   */
  public record Target(String host, int port) {

    /** The word that makes the drain pick the least loaded proxy of the fleet. */
    public static final String FLEET_WORD = "fleet";

    /** {@code transfer-to = fleet}: the target is chosen among the fleet's ready proxies. */
    public static final Target FLEET = new Target(FLEET_WORD, 0);

    /**
     * Returns whether players go to the fleet's least loaded ready proxy rather than a fixed
     * address.
     *
     * @return {@code true} for {@code transfer-to = fleet}
     */
    public boolean isFleet() {
      return port == 0 && host.equals(FLEET_WORD);
    }

    @Override
    public String toString() {
      if (isFleet()) {
        return FLEET_WORD;
      }
      return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }
  }

  /** What happens to a player the proxy cannot transfer (a client older than 1.20.5). */
  public enum CannotTransfer {
    /** It stays until it leaves or the drain times out. */
    KEEP,
    /** It is disconnected with the drain's message, at the transfer rate. */
    KICK
  }

  /** What happens to a player who logs in while the proxy drains. */
  public enum NewPlayers {
    /**
     * It is let in, and transferred like the others once it is on a server. A client the proxy
     * could not transfer, or one that a transfer just sent here, is refused instead.
     */
    ACCEPT,
    /** It is refused with the drain's message. */
    REFUSE
  }

  /**
   * Reads the section.
   *
   * @param root the configuration's root node
   * @return the section's values
   * @throws ConfigurationException if a value is invalid
   */
  static DrainConfig parse(CommentedConfigurationNode root) throws ConfigurationException {
    CommentedConfigurationNode node = root.node("drain");
    Target transferTo = parseTarget(WarpConfig.stringValue(node.node("transfer-to"), ""));
    boolean onSigterm = WarpConfig.booleanValue(node.node("on-sigterm"), false);
    Duration propagationDelay =
        parseDuration(node, "propagation-delay", DEFAULT_PROPAGATION_DELAY, false);
    Duration transferInterval =
        parseDuration(node, "transfer-interval", DEFAULT_TRANSFER_INTERVAL, false);
    CannotTransfer cannotTransfer =
        parseEnum(CannotTransfer.class, node, "cannot-transfer", CannotTransfer.KEEP);
    NewPlayers newPlayers = parseEnum(NewPlayers.class, node, "new-players", NewPlayers.ACCEPT);
    Duration timeout = parseDuration(node, "timeout", DEFAULT_TIMEOUT, true);
    Component message = Message.RESTARTING.of();
    return new DrainConfig(
        transferTo,
        onSigterm,
        propagationDelay,
        transferInterval,
        cannotTransfer,
        newPlayers,
        timeout,
        message);
  }

  /**
   * Parses {@code host}, {@code host:port} or {@code [ipv6]:port}.
   *
   * @return the target, or {@code null} for a blank value
   */
  public static @Nullable Target parseTarget(String value) throws ConfigurationException {
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.equalsIgnoreCase(Target.FLEET_WORD)) {
      return Target.FLEET;
    }
    String host;
    String port;
    if (trimmed.startsWith("[")) {
      int end = trimmed.indexOf(']');
      if (end < 0) {
        throw new ConfigurationException("drain.transfer-to has an unclosed '[': '" + value + "'");
      }
      host = trimmed.substring(1, end);
      String rest = trimmed.substring(end + 1);
      if (!rest.isEmpty() && !rest.startsWith(":")) {
        throw new ConfigurationException(
            "drain.transfer-to must be host, host:port or [ipv6]:port, got '" + value + "'");
      }
      port = rest.isEmpty() ? null : rest.substring(1);
    } else {
      int colon = trimmed.indexOf(':');
      if (colon >= 0 && trimmed.indexOf(':', colon + 1) >= 0) {
        throw new ConfigurationException(
            "drain.transfer-to: write an IPv6 address in brackets, as [::1]:25565, got '"
                + value
                + "'");
      }
      host = colon < 0 ? trimmed : trimmed.substring(0, colon);
      port = colon < 0 ? null : trimmed.substring(colon + 1);
    }
    if (host.isBlank()) {
      throw new ConfigurationException("drain.transfer-to has no host: '" + value + "'");
    }
    return new Target(host, port == null ? DEFAULT_PORT : parsePort(port, value));
  }

  private static int parsePort(String port, String value) throws ConfigurationException {
    int parsed;
    try {
      parsed = Integer.parseInt(port);
    } catch (NumberFormatException e) {
      throw new ConfigurationException("drain.transfer-to has an invalid port: '" + value + "'", e);
    }
    if (parsed < 1 || parsed > 65_535) {
      throw new ConfigurationException("drain.transfer-to port must be 1-65535, got " + parsed);
    }
    return parsed;
  }

  /**
   * Parses a duration in the configuration's one format (see {@link Durations}).
   *
   * @param positive whether zero is refused
   */
  static Duration parseDuration(
      CommentedConfigurationNode node, String key, Duration fallback, boolean positive)
      throws ConfigurationException {
    Duration duration = Durations.parse(node.node(key), "drain." + key, fallback);
    if (positive && duration.isZero()) {
      throw new ConfigurationException("drain." + key + " must be more than zero");
    }
    return duration;
  }

  private static <E extends Enum<E>> E parseEnum(
      Class<E> type, CommentedConfigurationNode node, String key, E fallback)
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
        allowed.append(constant.name().toLowerCase(Locale.ROOT));
      }
      throw new ConfigurationException(
          "drain." + key + " must be one of " + allowed + "; got '" + value + "'", e);
    }
  }

  @Override
  public String toString() {
    return "transfer-to "
        + (transferTo == null ? "none" : transferTo.toString())
        + ", on-sigterm "
        + onSigterm
        + ", propagation-delay "
        + format(propagationDelay)
        + ", transfer-interval "
        + format(transferInterval)
        + ", cannot-transfer "
        + cannotTransfer.name().toLowerCase(Locale.ROOT)
        + ", new-players "
        + newPlayers.name().toLowerCase(Locale.ROOT)
        + ", timeout "
        + format(timeout);
  }

  /**
   * Formats a duration the way the configuration writes it: {@code 100ms}, {@code 5s}, {@code 30m},
   * {@code 2h}, or the largest unit that divides it.
   *
   * @param duration the duration
   * @return the duration, as the configuration writes it
   */
  public static String format(Duration duration) {
    long millis = duration.toMillis();
    if (millis % 3_600_000 == 0 && millis > 0) {
      return millis / 3_600_000 + "h";
    }
    if (millis % 60_000 == 0 && millis > 0) {
      return millis / 60_000 + "m";
    }
    if (millis % 1_000 == 0) {
      return millis / 1_000 + "s";
    }
    return millis + "ms";
  }
}
