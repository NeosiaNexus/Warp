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

import dev.warp.proxy.network.CidrRanges;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code management.console} section of {@code warp.conf}: the remote console, which streams
 * the log ({@code GET /console/logs}) and runs commands ({@code POST /console/command}) on the
 * management server, for hosts without a terminal.
 *
 * @param enabled whether the console endpoints answer; off by default
 * @param tokens who may attach, each with its scopes; never empty when enabled
 * @param trustedNetworks without TLS, the networks besides loopback that may run commands
 * @param history how many log lines are kept for a replay
 * @param lockoutAfter how many wrong tokens one address may send before it is locked out
 * @param lockoutDuration how long an address stays locked out
 */
public record RemoteConsoleConfig(
    boolean enabled,
    List<Token> tokens,
    CidrRanges trustedNetworks,
    int history,
    int lockoutAfter,
    Duration lockoutDuration) {

  /** A console that is off. */
  public static final RemoteConsoleConfig DISABLED =
      new RemoteConsoleConfig(false, List.of(), CidrRanges.none(), 1000, 5, Duration.ofMinutes(15));

  /** The shortest token accepted from a file or a variable: brute force must stay hopeless. */
  public static final int MIN_TOKEN_LENGTH = 32;

  /** The most lines a replay may keep. */
  public static final int MAX_HISTORY = 10_000;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,32}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

  /** What a token may do. */
  public enum Scope {
    /** Read the live log and its replay. */
    LOGS,
    /** Run commands, list them and complete them. */
    COMMANDS;

    /**
     * Returns the name of the scope in {@code warp.conf} and in the answers.
     *
     * @return {@code logs} or {@code commands}
     */
    public String configName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** A token of the remote console: who holds it, what it may do, and its digest. */
  public static final class Token {

    private final String name;
    private final Set<Scope> scopes;
    private final byte[] digest;
    private final @Nullable String secret;

    /**
     * Creates a token.
     *
     * @param name who holds it, as the audit log names them
     * @param scopes what it may do
     * @param digest the SHA-256 of the token, which is all the server compares
     * @param secret the token itself, to keep it out of the streamed log, or {@code null} when only
     *     its digest is configured
     */
    public Token(String name, Set<Scope> scopes, byte[] digest, @Nullable String secret) {
      this.name = name;
      this.scopes = Set.copyOf(scopes);
      this.digest = digest.clone();
      this.secret = secret;
    }

    /**
     * Returns who holds the token.
     *
     * @return its name
     */
    public String name() {
      return name;
    }

    /**
     * Returns what the token may do.
     *
     * @return its scopes, unmodifiable
     */
    public Set<Scope> scopes() {
      return scopes;
    }

    /**
     * Returns a copy of the digest.
     *
     * @return the SHA-256 of the token
     */
    public byte[] digest() {
      return digest.clone();
    }

    /**
     * Returns the token itself, which only the redaction of the streamed log reads.
     *
     * @return the token, or {@code null} when only its digest is configured
     */
    public @Nullable String secret() {
      return secret;
    }

    /**
     * Returns whether the token has a scope.
     *
     * @param scope the scope
     * @return {@code true} if it has it
     */
    public boolean allows(Scope scope) {
      return scopes.contains(scope);
    }

    /** Names the token and its scopes, never its value. */
    @Override
    public String toString() {
      return name + " " + scopeNames(scopes);
    }
  }

  /**
   * Copies the tokens.
   *
   * @param enabled whether the console endpoints answer
   * @param tokens who may attach
   * @param trustedNetworks the networks that may run commands without TLS
   * @param history how many lines are kept for a replay
   * @param lockoutAfter wrong tokens before a lockout
   * @param lockoutDuration how long a lockout lasts
   */
  public RemoteConsoleConfig {
    tokens = List.copyOf(tokens);
  }

  /**
   * Returns whether some token may run commands.
   *
   * @return {@code true} if a token has the {@code commands} scope
   */
  public boolean runsCommands() {
    return tokens.stream().anyMatch(token -> token.allows(Scope.COMMANDS));
  }

  /**
   * Reads the section.
   *
   * @param node the {@code management.console} node
   * @param directory the directory token files resolve against
   * @param env reads an environment variable
   * @param drainToken the token of {@code POST /drain}, which no console token may equal
   * @param bind where the management server listens
   * @param tls whether the management server speaks TLS
   * @return the section
   * @throws ConfigurationException if a value is invalid, or commands would cross the network in
   *     clear text
   */
  static RemoteConsoleConfig parse(
      CommentedConfigurationNode node,
      Path directory,
      UnaryOperator<@Nullable String> env,
      @Nullable String drainToken,
      InetSocketAddress bind,
      boolean tls)
      throws ConfigurationException {
    boolean enabled = WarpConfig.booleanValue(node.node("enabled"), false);
    CidrRanges trusted =
        WarpConfig.parseCidrRanges(
            node.node("trusted-networks"), "management.console.trusted-networks");
    int history = WarpConfig.intValue(node.node("history"), 1000);
    if (history < 0 || history > MAX_HISTORY) {
      throw new ConfigurationException(
          "management.console.history must be 0 to " + MAX_HISTORY + ", found " + history);
    }
    int lockoutAfter = WarpConfig.intValue(node.node("lockout-after"), 5);
    if (lockoutAfter < 1 || lockoutAfter > 100) {
      throw new ConfigurationException(
          "management.console.lockout-after must be 1 to 100, found " + lockoutAfter);
    }
    Duration lockoutDuration =
        Durations.parse(
            node.node("lockout-duration"),
            "management.console.lockout-duration",
            Duration.ofMinutes(15));
    List<Token> tokens = parseTokens(node.node("tokens"), directory, env, drainToken);
    RemoteConsoleConfig config =
        new RemoteConsoleConfig(enabled, tokens, trusted, history, lockoutAfter, lockoutDuration);
    if (!enabled) {
      return config;
    }
    if (tokens.isEmpty()) {
      throw new ConfigurationException(
          "management.console.enabled is true but management.console.tokens is empty: add a"
              + " token, such as { name = \"ops\", scopes = [logs, commands], file ="
              + " \"console.token\" }");
    }
    if (config.runsCommands()
        && !tls
        && !bind.getAddress().isLoopbackAddress()
        && trusted.isEmpty()) {
      throw new ConfigurationException(
          "management.console: a token may run commands, but the management server listens on "
              + bind.getHostString()
              + ":"
              + bind.getPort()
              + " without TLS, where commands and their token would cross the network in clear"
              + " text. Set management.tls (certificate and key), bind to 127.0.0.1 (kubectl"
              + " port-forward and SSH tunnels arrive there), or list the networks you trust (a"
              + " VPN, the ingress that terminates TLS) in management.console.trusted-networks");
    }
    return config;
  }

  private static List<Token> parseTokens(
      CommentedConfigurationNode node,
      Path directory,
      UnaryOperator<@Nullable String> env,
      @Nullable String drainToken)
      throws ConfigurationException {
    if (node.virtual()) {
      return List.of();
    }
    if (!node.isList()) {
      throw new ConfigurationException(
          "management.console.tokens must be a list of blocks, such as [{ name = \"ops\", scopes"
              + " = [logs, commands], file = \"console.token\" }]");
    }
    List<Token> tokens = new ArrayList<>();
    Set<String> names = new HashSet<>();
    Set<String> digests = new HashSet<>();
    byte[] drainDigest = drainToken == null ? null : sha256(drainToken);
    for (CommentedConfigurationNode entry : node.childrenList()) {
      Token token = parseToken(entry, directory, env);
      if (!names.add(token.name().toLowerCase(Locale.ROOT))) {
        throw new ConfigurationException(
            "management.console.tokens names " + token.name() + " twice: names must be unique");
      }
      if (!digests.add(HexFormat.of().formatHex(token.digest()))) {
        throw new ConfigurationException(
            "management.console.tokens: "
                + token.name()
                + " has the same token as another entry; give each holder a token of its own, so"
                + " that the audit log knows who did what");
      }
      if (drainDigest != null && MessageDigest.isEqual(drainDigest, token.digest())) {
        throw new ConfigurationException(
            "management.console.tokens: "
                + token.name()
                + " uses the management token (token-file or "
                + ManagementConfig.ENV_TOKEN
                + "), which starts drains; give the console a token of its own");
      }
      tokens.add(token);
    }
    return tokens;
  }

  private static Token parseToken(
      CommentedConfigurationNode entry, Path directory, UnaryOperator<@Nullable String> env)
      throws ConfigurationException {
    if (!entry.isMap()) {
      throw new ConfigurationException(
          "management.console.tokens holds "
              + WarpConfig.describe(entry)
              + ": each entry is a block { name = ..., scopes = [...], file = ... }");
    }
    String name = WarpConfig.stringValue(entry.node("name"), "").strip();
    if (!NAME.matcher(name).matches()) {
      throw new ConfigurationException(
          "management.console.tokens: each token needs a name of 1 to 32 letters, digits, dots,"
              + " dashes or underscores, found '"
              + name
              + "'");
    }
    String where = "management.console.tokens (" + name + ")";
    Set<Scope> scopes = parseScopes(entry.node("scopes"), where);
    String file = WarpConfig.stringValue(entry.node("file"), "").strip();
    String variable = WarpConfig.stringValue(entry.node("env"), "").strip();
    String digestHex = WarpConfig.stringValue(entry.node("sha256"), "").strip();
    int sources = (file.isEmpty() ? 0 : 1) + (variable.isEmpty() ? 0 : 1);
    sources += digestHex.isEmpty() ? 0 : 1;
    if (sources != 1) {
      throw new ConfigurationException(
          where
              + ": give exactly one of file (a file holding the token), env (a variable holding"
              + " it) or sha256 (its SHA-256 in hexadecimal)");
    }
    if (!digestHex.isEmpty()) {
      if (!SHA256.matcher(digestHex).matches()) {
        throw new ConfigurationException(
            where
                + ": sha256 must be 64 hexadecimal digits, as printed by"
                + " `printf %s \"$TOKEN\" | sha256sum`");
      }
      return new Token(name, scopes, HexFormat.of().parseHex(digestHex), null);
    }
    String secret = file.isEmpty() ? fromVariable(variable, env, where) : fromFile(file, directory);
    if (secret.length() < MIN_TOKEN_LENGTH) {
      throw new ConfigurationException(
          where
              + ": the token has "
              + secret.length()
              + " characters, fewer than "
              + MIN_TOKEN_LENGTH
              + ": generate one with `openssl rand -hex 32`");
    }
    return new Token(name, scopes, sha256(secret), secret);
  }

  private static Set<Scope> parseScopes(CommentedConfigurationNode node, String where)
      throws ConfigurationException {
    if (!node.isList() || node.childrenList().isEmpty()) {
      throw new ConfigurationException(
          where + ": scopes must list logs, commands or both, such as scopes = [logs]");
    }
    Set<Scope> scopes = EnumSet.noneOf(Scope.class);
    for (CommentedConfigurationNode child : node.childrenList()) {
      String value = WarpConfig.stringValue(child, "").strip().toLowerCase(Locale.ROOT);
      switch (value) {
        case "logs" -> scopes.add(Scope.LOGS);
        case "commands" -> scopes.add(Scope.COMMANDS);
        default ->
            throw new ConfigurationException(
                where + ": unknown scope '" + value + "'; the scopes are logs and commands");
      }
    }
    return scopes;
  }

  private static String fromVariable(
      String variable, UnaryOperator<@Nullable String> env, String where)
      throws ConfigurationException {
    String value = env.apply(variable);
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(where + ": the variable " + variable + " is not set");
    }
    return value.strip();
  }

  private static String fromFile(String file, Path directory) throws ConfigurationException {
    Path path = directory.resolve(file);
    if (!Files.isRegularFile(path)) {
      throw new ConfigurationException(
          "The console token file " + path + " does not exist: create it with a token in it");
    }
    try {
      String token = Files.readString(path, StandardCharsets.UTF_8).strip();
      if (token.isEmpty()) {
        throw new ConfigurationException(
            "The console token file " + path + " is empty: write a token in it");
      }
      return token;
    } catch (IOException e) {
      throw new ConfigurationException("Failed to read the console token from " + path, e);
    }
  }

  /**
   * Returns the SHA-256 of a token, as the server compares it.
   *
   * @param token the token
   * @return its digest
   */
  public static byte[] sha256(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is always available", e);
    }
  }

  /**
   * Lists scopes as the answers and the log show them.
   *
   * @param scopes the scopes
   * @return such as {@code [logs, commands]}
   */
  public static String scopeNames(Set<Scope> scopes) {
    List<String> names = new ArrayList<>();
    for (Scope scope : Scope.values()) {
      if (scopes.contains(scope)) {
        names.add(scope.configName());
      }
    }
    return names.toString();
  }

  /** Describes the section without any token. */
  @Override
  public String toString() {
    if (!enabled) {
      return "off";
    }
    return "tokens "
        + tokens
        + ", trusted networks "
        + (trustedNetworks.isEmpty() ? "none" : trustedNetworks.toString())
        + ", history "
        + history;
  }
}
