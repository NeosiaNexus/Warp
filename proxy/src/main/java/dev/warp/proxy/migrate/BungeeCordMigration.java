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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Migrates a BungeeCord or Waterfall installation: {@code config.yml}. */
final class BungeeCordMigration implements Migration {

  static final String FILE = "config.yml";

  private static final String NO_PASSTHROUGH =
      "Warp answers the server list itself; passing a backend's response through is not"
          + " available yet";

  private static final String NO_PERMISSIONS =
      "BungeeCord's built-in groups and permissions have no equivalent; Warp's commands are"
          + " permission-checked through its plugin API";

  @Override
  public String name() {
    return "BungeeCord";
  }

  @Override
  public String marker() {
    return FILE;
  }

  @Override
  public Result migrate(Path directory) throws MigrationException {
    Path file = directory.resolve(FILE);
    Source source = new Source(read(file));
    Draft draft = new Draft();
    Report report = new Report();

    general(source, draft, report);
    Map<String, String> servers = servers(source, draft, report);
    listeners(source, draft, report, servers);
    Shared.finish(source, report, name());
    if (Files.exists(directory.resolve("waterfall.yml"))) {
      report.warn(
          "waterfall.yml was not read: it tunes Waterfall itself (packet limits, caches), and"
              + " Warp has no equivalent settings.");
    }
    report.step(
        "Copy the plugins Warp can run to plugins/ (see the plugins above), then start Warp and"
            + " read its startup checks.");
    return new Result(draft.render(Shared.header(name(), file)), null, report);
  }

  // ---------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------

  static Map<String, Object> read(Path file) throws MigrationException {
    if (!Files.isRegularFile(file)) {
      throw new MigrationException("cannot read " + file + ": no such file");
    }
    ConfigurationNode root;
    try {
      root = YamlConfigurationLoader.builder().path(file).build().load();
    } catch (ConfigurateException e) {
      throw new MigrationException(file + ": " + e.getMessage());
    }
    Object converted = convert(root);
    if (!(converted instanceof Map<?, ?>)) {
      throw new MigrationException(file + ": not a YAML document of keys and values");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) converted;
    return map;
  }

  /** Configurate's node tree as the plain maps and lists {@link Source} reads. */
  private static @Nullable Object convert(ConfigurationNode node) {
    if (node.isMap()) {
      Map<String, Object> map = new LinkedHashMap<>();
      for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
        Object value = convert(entry.getValue());
        if (value != null) {
          map.put(String.valueOf(entry.getKey()), value);
        }
      }
      return map;
    }
    if (node.isList()) {
      List<Object> list = new ArrayList<>();
      for (ConfigurationNode child : node.childrenList()) {
        Object value = convert(child);
        if (value != null) {
          list.add(value);
        }
      }
      return list;
    }
    Object raw = node.raw();
    if (raw instanceof Integer i) {
      return (long) i;
    }
    if (raw instanceof Float f) {
      return (double) f;
    }
    return raw;
  }

  // ---------------------------------------------------------------------------
  // Top-level keys
  // ---------------------------------------------------------------------------

  private void general(Source source, Draft draft, Report report) {
    source.bool("online_mode").ifPresent(v -> set(draft, report, "online_mode", v, "online-mode"));
    source
        .bool("ip_forward")
        .ifPresent(
            v -> Shared.forwarding(draft, report, FILE, "ip_forward", v, v ? "legacy" : "none"));
    source
        .integer("player_limit")
        .ifPresent(v -> set(draft, report, "player_limit", v, "player-limit", v < 0 ? -1 : v));
    source
        .integer("network_compression_threshold")
        .ifPresent(
            v -> set(draft, report, "network_compression_threshold", v, "compression.threshold"));
    source.integer("timeout").ifPresent(v -> Shared.readTimeout(draft, report, FILE, "timeout", v));
    source
        .integer("server_connect_timeout")
        .ifPresent(v -> Shared.connectTimeout(draft, report, FILE, "server_connect_timeout", v));
    source.bool("log_pings").ifPresent(v -> set(draft, report, "log_pings", v, "logging.pings"));
    source
        .bool("log_commands")
        .ifPresent(v -> set(draft, report, "log_commands", v, "logging.commands"));
    throttle(source, draft, report);
    source
        .integer("remote_ping_cache")
        .ifPresent(v -> report.drop("remote_ping_cache", NO_PASSTHROUGH));
    source
        .integer("remote_ping_timeout")
        .ifPresent(v -> report.drop("remote_ping_timeout", NO_PASSTHROUGH));
    source
        .bool("prevent_proxy_connections")
        .ifPresent(
            v ->
                report.drop(
                    "prevent_proxy_connections",
                    "no equivalent; Warp's built-in anti-bot (the anti-bot block, on by"
                        + " default) tells bots from players without this heuristic"));
    source
        .bool("enforce_secure_profile")
        .ifPresent(
            v ->
                report.drop(
                    "enforce_secure_profile",
                    Report.ALWAYS
                        + " in online mode on 1.19 to 1.19.2, the versions with a key to check"));
    source
        .bool("forge_support")
        .ifPresent(
            v -> report.drop("forge_support", "Warp does not speak the Forge handshake yet"));
    source
        .bool("inject_commands")
        .ifPresent(
            v ->
                report.drop(
                    "inject_commands",
                    Report.ALWAYS + ": its commands are declared to 1.13+ clients"));
    source.string("stats").ifPresent(v -> report.drop("stats", "Warp sends no bStats metrics"));
    source
        .strings("disabled_commands")
        .ifPresent(
            v ->
                report.drop(
                    "disabled_commands",
                    "no equivalent yet; Warp has no command of these names to disable"));
    for (String table : List.of("permissions", "groups")) {
      Optional<Map<String, Object>> entries = source.table(table);
      if (entries.isEmpty()) {
        continue;
      }
      if (entries.get().isEmpty()) {
        report.drop(table, NO_PERMISSIONS);
      }
      for (String key : entries.get().keySet()) {
        source.consumeAll(table, key);
        report.drop(table + "." + Source.join(key), NO_PERMISSIONS);
      }
    }
    for (String key :
        List.of("max_packets_per_second", "max_packets_data_per_second", "log_server_list_ping")) {
      if (source.has(key)) {
        source.consumeAll(key);
        report.drop(key, "Waterfall's own; Warp's limits are in the limits block");
      }
    }
  }

  /**
   * BungeeCord allows {@code connection_throttle_limit} connections per address within {@code
   * connection_throttle} milliseconds; Warp's bucket allows a burst, then one more per interval.
   */
  private static void throttle(Source source, Draft draft, Report report) {
    Optional<Long> window = source.integer("connection_throttle");
    Optional<Long> limit = source.integer("connection_throttle_limit");
    if (window.isEmpty()) {
      limit.ifPresent(
          v -> report.drop("connection_throttle_limit", "connection_throttle is not set"));
      return;
    }
    if (window.get() <= 0) {
      report.drop(
          "connection_throttle",
          "the throttle was off; Warp keeps its default connection limits (limits.connections)");
      limit.ifPresent(v -> report.drop("connection_throttle_limit", "the throttle was off"));
      return;
    }
    long burst = Math.max(1, limit.orElse(3L));
    String origin =
        Shared.origin(FILE, "connection_throttle", window.get())
            + (limit.isPresent()
                ? ", " + Shared.origin(FILE, "connection_throttle_limit", limit.get())
                : "");
    draft.set("limits.connections.burst", burst, origin);
    draft.set("limits.connections.interval", Conversions.duration(window.get()), origin);
    report.keep("connection_throttle", window.get(), "limits.connections.interval");
    limit.ifPresent(v -> report.keep("connection_throttle_limit", v, "limits.connections.burst"));
  }

  // ---------------------------------------------------------------------------
  // Servers
  // ---------------------------------------------------------------------------

  private static Map<String, String> servers(Source source, Draft draft, Report report) {
    Map<String, String> servers = new LinkedHashMap<>();
    Optional<Map<String, Object>> table = source.table("servers");
    if (table.isPresent()) {
      for (String name : table.get().keySet()) {
        Optional<String> address = source.string("servers", name, "address");
        if (address.isEmpty()) {
          report.drop("servers." + Source.join(name), "has no address");
          source.consumeAll("servers", name);
          continue;
        }
        servers.put(name, address.get());
        source
            .string("servers", name, "motd")
            .ifPresent(
                v ->
                    report.drop(
                        "servers." + Source.join(name) + ".motd",
                        "Warp shows one message of the day, status.motd"));
        source
            .bool("servers", name, "restricted")
            .ifPresent(
                v ->
                    report.drop(
                        "servers." + Source.join(name) + ".restricted",
                        v
                            ? "no equivalent yet; restrict the server from a plugin's"
                                + " ServerPreConnectEvent"
                            : "off"));
      }
    }
    Shared.servers(
        draft, report, FILE, servers, name -> "servers." + Source.join(name) + ".address");
    return servers;
  }

  // ---------------------------------------------------------------------------
  // Listeners
  // ---------------------------------------------------------------------------

  private static void listeners(
      Source source, Draft draft, Report report, Map<String, String> servers) {
    Optional<List<Map<String, Object>>> listeners = source.tables("listeners");
    if (listeners.isEmpty() || listeners.get().isEmpty()) {
      report.warn("No listener is configured: bind keeps Warp's default, 0.0.0.0:25577.");
      Shared.fallback(draft, report, FILE, "listeners[0].priorities", List.of(), servers.keySet());
      return;
    }
    if (listeners.get().size() > 1) {
      report.warn(
          "config.yml has "
              + listeners.get().size()
              + " listeners: Warp binds one address, so the first listener was migrated and the"
              + " others dropped. Run one Warp per address, or put a load balancer in front.");
    }
    Map<String, Object> listener = listeners.get().getFirst();
    Source first = new Source(listener);
    String prefix = "listeners[0].";

    first.string("host").ifPresent(v -> set(draft, report, prefix + "host", v, "bind"));
    first.string("motd").ifPresent(v -> Shared.motd(draft, report, FILE, prefix + "motd", v, true));
    first
        .integer("max_players")
        .ifPresent(v -> set(draft, report, prefix + "max_players", v, "status.max-players"));
    // Without priorities, BungeeCord sends players to the first server: so does Warp.
    List<String> priorities = first.strings("priorities").orElse(List.of());
    Shared.fallback(draft, report, FILE, prefix + "priorities", priorities, servers.keySet());
    Optional<Map<String, Object>> forced = first.table("forced_hosts");
    if (forced.isPresent()) {
      Map<String, List<String>> hosts = new LinkedHashMap<>();
      for (String host : forced.get().keySet()) {
        first.strings("forced_hosts", host).ifPresent(targets -> hosts.put(host, targets));
      }
      Shared.routes(draft, report, FILE, prefix + "forced_hosts", hosts, servers.keySet());
    }
    first
        .bool("proxy_protocol")
        .ifPresent(v -> Shared.proxyProtocol(draft, report, FILE, prefix + "proxy_protocol", v));
    first
        .bool("ping_passthrough")
        .ifPresent(
            v -> {
              report.drop(prefix + "ping_passthrough", NO_PASSTHROUGH);
              if (v) {
                report.warn(
                    "ping_passthrough was on: Warp shows its own status (status block), not the"
                        + " backend's. Set status.motd and status.favicon to what players saw.");
              }
            });
    first
        .bool("query_enabled")
        .ifPresent(
            v -> {
              report.drop(prefix + "query_enabled", "Warp does not answer GameSpy queries");
              if (v) {
                report.warn(
                    "query_enabled is on: Warp does not answer GameSpy queries. Tools that relied"
                        + " on it need the management server's /healthz instead.");
              }
            });
    first
        .integer("query_port")
        .ifPresent(v -> report.drop(prefix + "query_port", "Warp does not answer GameSpy queries"));
    first
        .string("tab_list")
        .ifPresent(
            v ->
                report.drop(
                    prefix + "tab_list", "Warp leaves the tab list to the backend, as Velocity"));
    first
        .integer("tab_size")
        .ifPresent(
            v ->
                report.drop(
                    prefix + "tab_size", "Warp leaves the tab list to the backend, as Velocity"));
    first
        .bool("force_default_server")
        .ifPresent(
            v ->
                report.drop(
                    prefix + "force_default_server",
                    "Warp has no reconnect handler: a new player always joins default-server, or"
                        + " the route of the host they connected to"));
    first
        .bool("bind_local_address")
        .ifPresent(
            v ->
                report.drop(
                    prefix + "bind_local_address",
                    "no equivalent: Warp lets the system pick the local address of backend"
                        + " connections"));
    for (Source.Mismatch mismatch : first.mismatches()) {
      report.warn(
          prefix
              + mismatch.key()
              + " is "
              + mismatch.actual()
              + " where BungeeCord expects "
              + mismatch.expected()
              + ": ignored.");
    }
    for (String key : first.unconsumed()) {
      report.drop(prefix + key, "not a BungeeCord setting Warp knows");
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void set(Draft draft, Report report, String sourceKey, Object value, String key) {
    set(draft, report, sourceKey, value, key, value);
  }

  private static void set(
      Draft draft, Report report, String sourceKey, Object value, String key, Object written) {
    draft.set(key, written, Shared.origin(FILE, sourceKey, value));
    report.keep(sourceKey, value, key);
  }
}
