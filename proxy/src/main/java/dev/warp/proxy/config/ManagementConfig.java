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
import java.util.List;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code management} section of {@code warp.conf}: the HTTP server that answers Kubernetes
 * probes and load balancers and takes drain requests, and the HAProxy agent check.
 *
 * @param enabled whether the management server runs; off by default
 * @param bind the HTTP address
 * @param agentCheck the HAProxy agent check's address, or {@code null} for none
 * @param token the bearer token the mutating endpoints require, or {@code null} when none is
 *     configured: they are then refused
 * @param readyRequiresServer whether readiness also needs a registered backend server
 * @param tls the certificate and key the HTTP port speaks TLS with, or {@code null} for plain HTTP
 * @param console the remote console
 * @param metrics the metrics endpoint
 */
public record ManagementConfig(
    boolean enabled,
    InetSocketAddress bind,
    @Nullable InetSocketAddress agentCheck,
    @Nullable String token,
    boolean readyRequiresServer,
    @Nullable Tls tls,
    RemoteConsoleConfig console,
    MetricsConfig metrics) {

  /**
   * The TLS of the management HTTP port, in PEM files that are read again when they change (as
   * cert-manager renews them).
   *
   * @param certificate the certificate chain, such as {@code tls.crt} of a Kubernetes TLS secret
   * @param key its private key, in PKCS#8 ({@code BEGIN PRIVATE KEY})
   */
  public record Tls(Path certificate, Path key) {}

  /**
   * Creates the section without TLS and without the remote console.
   *
   * @param enabled whether the management server runs
   * @param bind the HTTP address
   * @param agentCheck the agent check's address, or {@code null}
   * @param token the bearer token of the mutating endpoints, or {@code null}
   * @param readyRequiresServer whether readiness needs a registered backend server
   */
  public ManagementConfig(
      boolean enabled,
      InetSocketAddress bind,
      @Nullable InetSocketAddress agentCheck,
      @Nullable String token,
      boolean readyRequiresServer) {
    this(
        enabled,
        bind,
        agentCheck,
        token,
        readyRequiresServer,
        null,
        RemoteConsoleConfig.DISABLED,
        MetricsConfig.DEFAULTS);
  }

  /**
   * Returns whether {@code GET /metrics} can be read from another machine without a credential: the
   * bind is not loopback, and neither TLS nor {@code metrics.require-token} is on.
   *
   * @return {@code true} when the metrics are open
   */
  public boolean metricsExposed() {
    return enabled
        && metrics.enabled()
        && !bind.getAddress().isLoopbackAddress()
        && tls == null
        && !metrics.requireToken();
  }

  /** The environment variable that holds the token, ahead of {@code token-file}. */
  public static final String ENV_TOKEN = "WARP_MANAGEMENT_TOKEN";

  static final String DEFAULT_BIND = "127.0.0.1:9901";

  /**
   * Reads the section.
   *
   * @param root the configuration's root node
   * @param directory the proxy's working directory, against which {@code token-file} resolves
   * @param env reads an environment variable, {@code null} when unset
   * @return the section's values
   * @throws ConfigurationException if an address is invalid or the token file is unreadable or
   *     empty
   */
  static ManagementConfig parse(
      CommentedConfigurationNode root, Path directory, UnaryOperator<@Nullable String> env)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("management");
    boolean enabled = WarpConfig.booleanValue(node.node("enabled"), false);
    InetSocketAddress bind =
        WarpConfig.parseAddress(
            WarpConfig.stringValue(node.node("bind"), DEFAULT_BIND), "management.bind");
    String agent = WarpConfig.stringValue(node.node("agent-check"), "");
    InetSocketAddress agentCheck =
        agent.isBlank() ? null : WarpConfig.parseAddress(agent, "management.agent-check");
    if (agentCheck != null && agentCheck.getPort() == bind.getPort() && bind.getPort() != 0) {
      throw new ConfigurationException(
          "management.agent-check must use another port than management.bind, both use "
              + bind.getPort());
    }
    String token =
        readToken(
            env.apply(ENV_TOKEN), directory, WarpConfig.stringValue(node.node("token-file"), ""));
    boolean readyRequiresServer = WarpConfig.booleanValue(node.node("ready-requires-server"), true);
    Tls tls = parseTls(node.node("tls"), directory);
    RemoteConsoleConfig console =
        RemoteConsoleConfig.parse(node.node("console"), directory, env, token, bind, tls != null);
    if (console.enabled() && !enabled) {
      throw new ConfigurationException(
          "management.console.enabled is true but management.enabled is false: the remote"
              + " console lives on the management server; enable both");
    }
    MetricsConfig metrics = MetricsConfig.parse(node.node("metrics"), token != null);
    return new ManagementConfig(
        enabled, bind, agentCheck, token, readyRequiresServer, tls, console, metrics);
  }

  private static @Nullable Tls parseTls(CommentedConfigurationNode node, Path directory)
      throws ConfigurationException {
    String certificate = WarpConfig.stringValue(node.node("certificate"), "").strip();
    String key = WarpConfig.stringValue(node.node("key"), "").strip();
    if (certificate.isEmpty() && key.isEmpty()) {
      return null;
    }
    if (certificate.isEmpty() || key.isEmpty()) {
      throw new ConfigurationException(
          "management.tls needs both certificate and key (PEM files), or neither for plain HTTP");
    }
    Path certificateFile = directory.resolve(certificate);
    Path keyFile = directory.resolve(key);
    for (Path file : List.of(certificateFile, keyFile)) {
      if (!Files.isReadable(file)) {
        throw new ConfigurationException(
            "management.tls: " + file + " does not exist or cannot be read");
      }
    }
    return new Tls(certificateFile, keyFile);
  }

  private static @Nullable String readToken(
      @Nullable String fromEnv, Path directory, String tokenFile) throws ConfigurationException {
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    if (tokenFile.isBlank()) {
      return null;
    }
    Path file = directory.resolve(tokenFile);
    if (!Files.exists(file)) {
      throw new ConfigurationException(
          "management.token-file " + file + " does not exist: create it, or set " + ENV_TOKEN);
    }
    String token;
    try {
      token = Files.readString(file, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new ConfigurationException("Failed to read the management token from " + file, e);
    }
    if (token.isEmpty()) {
      throw new ConfigurationException(
          "The management token file "
              + file
              + " is empty: write a token in it, or set "
              + ENV_TOKEN);
    }
    return token;
  }

  /** Describes the section without its token. */
  @Override
  public String toString() {
    if (!enabled) {
      return "off";
    }
    return "http "
        + bind
        + ", agent check "
        + (agentCheck == null ? "off" : agentCheck.toString())
        + ", token "
        + (token == null ? "none (POST /drain refused)" : "set")
        + ", ready requires a server: "
        + readyRequiresServer
        + ", tls "
        + (tls == null ? "off" : "on")
        + ", console "
        + console
        + ", metrics "
        + metrics;
  }
}
