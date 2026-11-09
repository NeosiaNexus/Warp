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

import dev.warp.proxy.bedrock.FloodgateCipher;
import dev.warp.proxy.network.CidrRanges;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code bedrock} section of {@code warp.conf}, and the {@code floodgate} flag of each server:
 * how Bedrock players reach the proxy through Geyser and Floodgate.
 *
 * @param floodgate Floodgate's settings
 * @param trustedGeyser the addresses Geyser connects from, trusted to name the Bedrock player's own
 *     address in the Floodgate data
 * @param floodgateServers each server that sets {@code floodgate} itself, to its value: the others
 *     take {@link Floodgate#forward()}
 */
public record BedrockConfig(
    Floodgate floodgate, CidrRanges trustedGeyser, Map<String, Boolean> floodgateServers) {

  /** Where Geyser connects from when it runs beside the proxy: the loopback addresses. */
  static final List<String> DEFAULT_TRUSTED = List.of("127.0.0.1/32", "::1/128");

  /** Everything off: a proxy without Bedrock players. */
  public static final BedrockConfig DISABLED =
      new BedrockConfig(Floodgate.DISABLED, CidrRanges.parse(DEFAULT_TRUSTED), Map.of());

  /** Floodgate's own default prefix. */
  public static final String DEFAULT_PREFIX = ".";

  /** Floodgate's own key file name. */
  static final String DEFAULT_KEY_FILE = "key.pem";

  /** The longest prefix that leaves a gamertag at least one character. */
  static final int MAX_PREFIX_LENGTH = 15;

  /** Validates the record. */
  public BedrockConfig {
    floodgateServers = Map.copyOf(floodgateServers);
  }

  /**
   * Returns whether a backend gets the Floodgate data appended to its handshake: its own {@code
   * floodgate} flag, else {@code bedrock.floodgate.forward}.
   *
   * @param server the server's name, in lowercase
   * @return {@code true} to re-emit the data to it
   */
  public boolean forwardsTo(String server) {
    Boolean own = floodgateServers.get(server);
    return own != null ? own : floodgate.forward();
  }

  /**
   * Floodgate's settings.
   *
   * @param enabled whether Floodgate data is read at all
   * @param key the raw key, empty when disabled
   * @param keyFile the file the key was read from, for the startup summary
   * @param usernamePrefix what Bedrock names start with, {@code .} by default, possibly empty
   * @param forward whether backends not saying otherwise get the data re-emitted
   */
  @SuppressWarnings("ArrayRecordComponent") // the key is treated as immutable
  public record Floodgate(
      boolean enabled, byte[] key, String keyFile, String usernamePrefix, boolean forward) {

    /** Floodgate off. */
    public static final Floodgate DISABLED =
        new Floodgate(false, new byte[0], DEFAULT_KEY_FILE, DEFAULT_PREFIX, false);

    /** Validates the record. */
    public Floodgate {
      if (enabled && !FloodgateCipher.isValidKeyLength(key.length)) {
        throw new IllegalArgumentException("a Floodgate key is 16, 24 or 32 bytes");
      }
    }
  }

  /**
   * Parses the section and the servers' flags.
   *
   * @param root the configuration
   * @param directory the proxy directory the key file is relative to
   * @return the settings
   * @throws ConfigurationException if a value is invalid, the key file cannot be read, or a server
   *     asks for Floodgate data while Floodgate is off
   */
  static BedrockConfig parse(CommentedConfigurationNode root, Path directory)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("bedrock");
    CommentedConfigurationNode floodgateNode = node.node("floodgate");
    boolean enabled = WarpConfig.booleanValue(floodgateNode.node("enabled"), false);
    String keyFile = WarpConfig.stringValue(floodgateNode.node("key-file"), DEFAULT_KEY_FILE);
    String prefix = WarpConfig.stringValue(floodgateNode.node("username-prefix"), DEFAULT_PREFIX);
    boolean forward = WarpConfig.booleanValue(floodgateNode.node("forward"), false);

    if (prefix.length() > MAX_PREFIX_LENGTH
        || prefix.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
      throw ConfigurationException.invalid(
          "bedrock.floodgate.username-prefix",
          "up to " + MAX_PREFIX_LENGTH + " characters without spaces, such as \".\" or \"\"",
          "'" + prefix + "'");
    }

    byte[] key = new byte[0];
    if (enabled) {
      if (keyFile.isBlank()) {
        throw ConfigurationException.at(
            "bedrock.floodgate.key-file", "is empty: name Floodgate's key.pem, copied from Geyser");
      }
      Path path = directory.resolve(keyFile);
      try {
        key = Files.readAllBytes(path);
      } catch (IOException e) {
        throw ConfigurationException.at(
            "bedrock.floodgate.key-file",
            "cannot read "
                + path
                + ": copy the key.pem Geyser and Floodgate share there, so that Warp can verify"
                + " the Bedrock players Geyser sends it");
      }
      if (!FloodgateCipher.isValidKeyLength(key.length)) {
        throw ConfigurationException.at(
            "bedrock.floodgate.key-file",
            path
                + " is "
                + key.length
                + " bytes, but a Floodgate key is 16, 24 or 32 raw bytes: copy Floodgate's key.pem"
                + " as it is, without converting it");
      }
    }

    CommentedConfigurationNode trustedNode = node.node("geyser", "trusted");
    CidrRanges trusted =
        trustedNode.virtual()
            ? CidrRanges.parse(DEFAULT_TRUSTED)
            : WarpConfig.parseCidrRanges(trustedNode, "bedrock.geyser.trusted");

    Map<String, Boolean> servers = new LinkedHashMap<>();
    CommentedConfigurationNode serversNode = root.node("servers");
    if (!serversNode.virtual() && serversNode.isMap()) {
      for (var entry : serversNode.childrenMap().entrySet()) {
        CommentedConfigurationNode flag = entry.getValue().node("floodgate");
        if (flag.virtual()) {
          continue;
        }
        String name = entry.getKey().toString().toLowerCase(Locale.ROOT);
        boolean value = WarpConfig.booleanValue(flag, false);
        if (value && !enabled) {
          throw ConfigurationException.at(
              "servers." + entry.getKey() + ".floodgate",
              "is true, but bedrock.floodgate.enabled is false: Warp has no key to write the"
                  + " data with. Enable Floodgate, or remove the flag");
        }
        servers.put(name, value);
      }
    }
    if (forward && !enabled) {
      throw ConfigurationException.at(
          "bedrock.floodgate.forward",
          "is true, but bedrock.floodgate.enabled is false: enable Floodgate, or turn forward off");
    }
    return new BedrockConfig(
        new Floodgate(enabled, key, keyFile, prefix, forward), trusted, servers);
  }

  /**
   * Describes the settings for the startup summary, without the key.
   *
   * @return one line
   */
  public String describe() {
    if (!floodgate.enabled()) {
      return "floodgate disabled";
    }
    return "floodgate key "
        + floodgate.keyFile()
        + ", prefix '"
        + floodgate.usernamePrefix()
        + "', forward="
        + floodgate.forward()
        + (floodgateServers.isEmpty() ? "" : " except " + floodgateServers)
        + ", trusted geyser "
        + trustedGeyser;
  }
}
