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
package dev.warp.proxy.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/** Migrates a Velocity installation: {@code velocity.toml} and {@code forwarding.secret}. */
final class VelocityMigration implements Migration {

  static final String FILE = "velocity.toml";

  private static final String NO_RATE_LIMITER = "Warp has no command rate limiter yet";

  private static final String NO_QUERY = "Warp does not answer GameSpy queries";

  private static final String NO_PASSTHROUGH =
      "Warp answers the server list itself; passing a backend's response through is not"
          + " available yet";

  private static final String NO_PACKET_LIMITER =
      "Warp has no per-player packet rate limiter yet; its connection and login limits are in"
          + " the limits block";

  @Override
  public String name() {
    return "Velocity";
  }

  @Override
  public String marker() {
    return FILE;
  }

  @Override
  public Result migrate(Path directory) throws MigrationException {
    Path file = directory.resolve(FILE);
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new MigrationException("cannot read " + file + ": " + e.getMessage());
    }
    Source source;
    try {
      source = new Source(Toml.parse(text));
    } catch (Toml.TomlException e) {
      throw new MigrationException(file + ": " + e.getMessage());
    }
    Draft draft = new Draft();
    Report report = new Report();

    general(source, draft, report);
    String secret = forwarding(source, draft, report, directory);
    servers(source, draft, report);
    advanced(source, draft, report);
    sections(source, report);
    Shared.finish(source, report, name());

    report.step(
        "Copy the plugins Warp can run to plugins/ (see the plugins above), then start Warp and"
            + " read its startup checks.");
    return new Result(draft.render(Shared.header(name(), file)), secret, report);
  }

  // ---------------------------------------------------------------------------
  // Top-level keys
  // ---------------------------------------------------------------------------

  private void general(Source source, Draft draft, Report report) {
    Optional<String> version = source.string("config-version");
    version.ifPresent(v -> report.drop("config-version", "Velocity's own"));
    boolean legacyMotd = legacyMotd(version);
    source.string("bind").ifPresent(v -> set(draft, report, "bind", v, "bind"));
    source.string("motd").ifPresent(v -> Shared.motd(draft, report, FILE, "motd", v, legacyMotd));
    source
        .integer("show-max-players")
        .ifPresent(v -> set(draft, report, "show-max-players", v, "status.max-players"));
    source.bool("online-mode").ifPresent(v -> set(draft, report, "online-mode", v, "online-mode"));
    source
        .bool("force-key-authentication")
        .ifPresent(
            v ->
                report.drop(
                    "force-key-authentication",
                    Report.ALWAYS
                        + " in online mode on 1.19 to 1.19.2, the versions with a key to check"));
    source
        .bool("prevent-client-proxy-connections")
        .ifPresent(
            v ->
                report.drop(
                    "prevent-client-proxy-connections",
                    "no equivalent; Warp's built-in anti-bot (the anti-bot block, on by"
                        + " default) tells bots from players without this heuristic"));
    source
        .bool("announce-forge")
        .ifPresent(
            v -> report.drop("announce-forge", "Warp does not speak the Forge handshake yet"));
    source
        .bool("kick-existing-players")
        .ifPresent(
            v -> {
              String policy = v ? "kick-existing" : "refuse-new";
              draft.set(
                  "duplicate-login",
                  policy,
                  Shared.origin(FILE, "kick-existing-players", v)
                      + " (Warp's \"auto\" kicks the existing session in online mode, where the"
                      + " newcomer proved it owns the account, and refuses the newcomer in"
                      + " offline mode)");
              report.keep("kick-existing-players", v, "duplicate-login");
            });
    source
        .bool("sample-players-in-ping")
        .ifPresent(
            v -> {
              if (v) {
                report.drop(
                    "sample-players-in-ping",
                    "on, as Warp's default status.sample-size = 12 players");
              } else {
                set(draft, report, "sample-players-in-ping", v, "status.sample-size", 0);
              }
            });
    source
        .bool("enable-player-address-logging")
        .ifPresent(
            v ->
                report.drop(
                    "enable-player-address-logging",
                    v ? Report.ALWAYS : "Warp logs player addresses; it does not redact them"));
    source
        .bool("accepts-transfers")
        .ifPresent(v -> set(draft, report, "accepts-transfers", v, "accepts-transfers"));
  }

  // ---------------------------------------------------------------------------
  // Forwarding
  // ---------------------------------------------------------------------------

  private @Nullable String forwarding(Source source, Draft draft, Report report, Path directory) {
    Optional<String> modeValue = source.string("player-info-forwarding-mode");
    String mode = modeValue.orElse("none").toLowerCase(Locale.ROOT);
    if (!List.of("none", "modern", "legacy", "bungeeguard").contains(mode)) {
      report.warn(
          "player-info-forwarding-mode is \"" + mode + "\", which Velocity does not know: none.");
      mode = "none";
    }
    Shared.forwarding(
        draft, report, FILE, "player-info-forwarding-mode", modeValue.orElse("none"), mode);

    // Velocity 1.x wrote the secret in the file; since 3.0 a file holds it.
    Optional<String> inline = source.string("forwarding-secret");
    Optional<String> secretFile = source.string("forwarding-secret-file");
    String secret = null;
    if (inline.isPresent() && !inline.get().isBlank()) {
      secret = inline.get().strip();
      report.keep("forwarding-secret", "(hidden)", "forwarding.secret (file)");
    } else {
      inline.ifPresent(v -> report.drop("forwarding-secret", "empty"));
    }
    if (secretFile.isPresent()) {
      Path path = directory.resolve(secretFile.get());
      String read = readSecret(path);
      if (read != null && secret == null) {
        secret = read;
        report.keep("forwarding-secret-file", secretFile.get(), "forwarding.secret (file)");
      } else if (read != null) {
        report.drop("forwarding-secret-file", "forwarding-secret in the file takes over");
      } else {
        report.drop("forwarding-secret-file", "the file is missing or empty");
      }
    }
    if (secret == null && !mode.equals("none")) {
      report.warn(
          "No forwarding secret found: Warp generates one in forwarding.secret on its first"
              + " start. Copy it to the backends.");
    }
    return secret;
  }

  private static @Nullable String readSecret(Path path) {
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8).strip();
      return content.isEmpty() ? null : content;
    } catch (IOException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Servers and forced hosts
  // ---------------------------------------------------------------------------

  private void servers(Source source, Draft draft, Report report) {
    Map<String, String> servers = new LinkedHashMap<>();
    Optional<Map<String, Object>> table = source.table("servers");
    if (table.isPresent()) {
      for (String name : table.get().keySet()) {
        if (name.equals("try")) {
          continue;
        }
        source.string("servers", name).ifPresent(address -> servers.put(name, address));
      }
    }
    Shared.servers(draft, report, FILE, servers, name -> "servers." + Source.join(name));
    List<String> order = source.strings("servers", "try").orElse(List.of());
    if (table.isPresent()) {
      Shared.fallback(draft, report, FILE, "servers.try", order, servers.keySet());
    }

    Optional<Map<String, Object>> forced = source.table("forced-hosts");
    if (forced.isPresent()) {
      Map<String, List<String>> hosts = new LinkedHashMap<>();
      for (String host : forced.get().keySet()) {
        source.strings("forced-hosts", host).ifPresent(targets -> hosts.put(host, targets));
      }
      Shared.routes(draft, report, FILE, "forced-hosts", hosts, servers.keySet());
    }
  }

  // ---------------------------------------------------------------------------
  // [advanced]
  // ---------------------------------------------------------------------------

  private void advanced(Source source, Draft draft, Report report) {
    if (source.table("advanced").isEmpty()) {
      return;
    }
    integer(source, "compression-threshold")
        .ifPresent(
            v -> set(draft, report, "advanced.compression-threshold", v, "compression.threshold"));
    integer(source, "compression-level")
        .ifPresent(v -> set(draft, report, "advanced.compression-level", v, "compression.level"));
    integer(source, "login-ratelimit")
        .ifPresent(v -> Shared.loginRate(draft, report, FILE, "advanced.login-ratelimit", v));
    integer(source, "connection-timeout")
        .ifPresent(
            v -> Shared.connectTimeout(draft, report, FILE, "advanced.connection-timeout", v));
    integer(source, "read-timeout")
        .ifPresent(v -> Shared.readTimeout(draft, report, FILE, "advanced.read-timeout", v));
    bool(source, "haproxy-protocol")
        .ifPresent(v -> Shared.proxyProtocol(draft, report, FILE, "advanced.haproxy-protocol", v));
    bool(source, "tcp-fast-open")
        .ifPresent(v -> set(draft, report, "advanced.tcp-fast-open", v, "tcp-fast-open"));
    bool(source, "bungee-plugin-message-channel")
        .ifPresent(
            v ->
                set(
                    draft,
                    report,
                    "advanced.bungee-plugin-message-channel",
                    v,
                    "bungeecord-channel.enabled"));
    bool(source, "show-ping-requests")
        .ifPresent(v -> set(draft, report, "advanced.show-ping-requests", v, "logging.pings"));
    bool(source, "failover-on-unexpected-server-disconnect")
        .ifPresent(
            v ->
                report.drop(
                    "advanced.failover-on-unexpected-server-disconnect",
                    v
                        ? Report.ALWAYS + ": a player a server drops follows fallback-order"
                        : "Warp always falls a dropped player back to fallback-order"));
    bool(source, "announce-proxy-commands")
        .ifPresent(
            v ->
                report.drop(
                    "advanced.announce-proxy-commands",
                    Report.ALWAYS + ": its commands are declared to 1.13+ clients"));
    bool(source, "log-command-executions")
        .ifPresent(
            v -> set(draft, report, "advanced.log-command-executions", v, "logging.commands"));
    bool(source, "log-player-connections")
        .ifPresent(
            v -> set(draft, report, "advanced.log-player-connections", v, "logging.connections"));
    bool(source, "accepts-transfers")
        .ifPresent(v -> set(draft, report, "advanced.accepts-transfers", v, "accepts-transfers"));
    bool(source, "enable-reuse-port")
        .ifPresent(
            v ->
                report.drop(
                    "advanced.enable-reuse-port",
                    "no equivalent: Warp's listener hands connections to its event loops itself"));
    for (String key :
        List.of(
            "command-rate-limit",
            "forward-commands-if-rate-limited",
            "kick-after-rate-limited-commands",
            "tab-complete-rate-limit",
            "kick-after-rate-limited-tab-completes")) {
      source
          .string("advanced", key)
          .ifPresent(v -> report.drop("advanced." + key, NO_RATE_LIMITER));
    }
  }

  // ---------------------------------------------------------------------------
  // Sections Warp has no equivalent for
  // ---------------------------------------------------------------------------

  private void sections(Source source, Report report) {
    // Before Velocity 3.4, ping-passthrough was one string ("DISABLED", "MODS", "DESCRIPTION",
    // "ALL"); since then it is a table of booleans. Both shapes are accepted.
    if (source.has("ping-passthrough")) {
      Optional<Map<String, Object>> table = source.table("ping-passthrough");
      if (table.isPresent()) {
        boolean any = false;
        for (Map.Entry<String, Object> entry : table.get().entrySet()) {
          source.consumeAll("ping-passthrough", entry.getKey());
          any |= Boolean.TRUE.equals(entry.getValue());
          report.drop("ping-passthrough." + entry.getKey(), NO_PASSTHROUGH);
        }
        if (any) {
          passthroughWarning(report);
        }
      } else {
        String mode = source.string("ping-passthrough").orElse("disabled");
        report.drop("ping-passthrough", NO_PASSTHROUGH);
        if (!mode.equalsIgnoreCase("disabled")) {
          passthroughWarning(report);
        }
      }
    }
    dropTable(source, report, "packet-limiter", NO_PACKET_LIMITER);
    if (source.has("query")) {
      boolean enabled = source.bool("query", "enabled").orElse(false);
      dropTable(source, report, "query", NO_QUERY);
      if (enabled) {
        report.warn(
            "query.enabled is on: Warp does not answer GameSpy queries. Tools that relied on it"
                + " (dashboards, server lists) need the management server's /healthz instead.");
      }
    }
    dropTable(source, report, "metrics", "Warp sends no bStats metrics");
  }

  private static void passthroughWarning(Report report) {
    report.warn(
        "ping-passthrough was on: Warp shows its own status (status block), not the backend's."
            + " Set status.motd and status.favicon to what players saw.");
  }

  private static void dropTable(Source source, Report report, String table, String reason) {
    Optional<Map<String, Object>> entries = source.table(table);
    if (entries.isEmpty()) {
      return;
    }
    if (entries.get().isEmpty()) {
      report.drop(table, reason);
    }
    for (String key : entries.get().keySet()) {
      source.consumeAll(table, key);
      report.drop(table + "." + key, reason);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Optional<Long> integer(Source source, String key) {
    return source.integer("advanced", key);
  }

  /**
   * Whether the file's message of the day is in legacy {@code &} codes. Velocity read it so until
   * {@code config-version} 2.0, which switched to MiniMessage; a file without a version is from
   * 1.x.
   */
  private static boolean legacyMotd(Optional<String> configVersion) {
    if (configVersion.isEmpty()) {
      return true;
    }
    try {
      return Double.parseDouble(configVersion.get().trim()) < 2.0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static Optional<Boolean> bool(Source source, String key) {
    return source.bool("advanced", key);
  }

  private static void set(Draft draft, Report report, String sourceKey, Object value, String key) {
    set(draft, report, sourceKey, value, key, value);
  }

  private static void set(
      Draft draft, Report report, String sourceKey, Object value, String key, Object written) {
    draft.set(key, written, Shared.origin(FILE, sourceKey, value));
    report.keep(sourceKey, value, key);
  }
}
