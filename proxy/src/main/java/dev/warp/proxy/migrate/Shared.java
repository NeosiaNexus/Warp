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

import dev.warp.proxy.WarpBuildInfo;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The parts of a migration that Velocity and BungeeCord share: servers, routes, text, limits. */
final class Shared {

  /** The client's own read timeout: Warp's {@code timeouts.read} has to stay below it. */
  static final long CLIENT_TIMEOUT_MILLIS = 30_000;

  static final String DOCS = "https://github.com/NeosiaNexus/Warp/blob/main/docs/migration.md";

  static final String BACKENDS_STEP =
      "On every backend, enable Velocity modern forwarding with the content of forwarding.secret"
          + " (Paper: proxies.velocity in config/paper-global.yml) and set online-mode=false."
          + " Backends before 1.13 cannot use it.";

  private Shared() {}

  /**
   * Returns the comment at the top of the generated file.
   *
   * @param proxy the source proxy's name
   * @param file the source configuration file
   * @return the header
   */
  static String header(String proxy, Path file) {
    return "Warp configuration migrated from "
        + proxy
        + " ("
        + file.toAbsolutePath().normalize()
        + ")\n"
        + "by warp migrate "
        + WarpBuildInfo.VERSION
        + " on "
        + LocalDate.now()
        + ".\n"
        + "\n"
        + "The comment above each value names the "
        + proxy
        + " setting it came from. Every key left\n"
        + "out keeps Warp's default: start Warp once in an empty directory to read the default"
        + " warp.conf,\n"
        + "which documents them all, or see "
        + DOCS
        + ".";
  }

  /**
   * Renders the origin comment of a value.
   *
   * @param file the source file's name
   * @param key the source key
   * @param value the source value
   * @return the comment, such as {@code velocity.toml: bind = "0.0.0.0:25565"}
   */
  static String origin(String file, String key, Object value) {
    return file + ": " + key + " = " + Source.describe(value);
  }

  /**
   * Writes the servers block.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param servers the servers, name to address
   * @param keyOf renders the source key of a server's address, from its name
   */
  static void servers(
      Draft draft,
      Report report,
      String file,
      Map<String, String> servers,
      java.util.function.UnaryOperator<String> keyOf) {
    if (servers.isEmpty()) {
      report.warn(
          "No server is configured: Warp starts without any, and plugins register them at"
              + " runtime. Add them to the servers block otherwise.");
      return;
    }
    for (Map.Entry<String, String> entry : servers.entrySet()) {
      String key = keyOf.apply(entry.getKey());
      draft.set(
          List.of("servers", entry.getKey(), "address"),
          entry.getValue(),
          origin(file, key, entry.getValue()));
      report.keep(key, entry.getValue(), "servers." + entry.getKey() + ".address");
    }
  }

  /**
   * Writes {@code default-server} and {@code fallback-order} from the source's order of servers,
   * dropping the names of servers that do not exist.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param key the source key of the order
   * @param order the servers to try, in order
   * @param servers the names of the servers that exist
   */
  static void fallback(
      Draft draft,
      Report report,
      String file,
      String key,
      List<String> order,
      Set<String> servers) {
    if (servers.isEmpty()) {
      if (!order.isEmpty()) {
        report.drop(key, "no server is configured");
      }
      return;
    }
    List<String> existing = new ArrayList<>();
    for (String name : order) {
      if (servers.contains(name)) {
        existing.add(name);
      } else {
        report.warn(key + " names the server '" + name + "', which does not exist: left out.");
      }
    }
    if (existing.isEmpty()) {
      String first = servers.iterator().next();
      existing.add(first);
      report.warn(
          key + " names no server: the first server, '" + first + "', is the default server.");
      String why = file + ": " + key + " names no server; the servers in their order";
      draft.set("default-server", first, why);
      draft.set("fallback-order", existing, why);
      if (!order.isEmpty()) {
        report.keep(key, order, "default-server, fallback-order");
      }
      return;
    }
    String origin = origin(file, key, order);
    draft.set("default-server", existing.getFirst(), origin);
    draft.set("fallback-order", existing, origin);
    report.keep(key, order, "default-server, fallback-order");
  }

  /**
   * Writes the routes block (forced hosts): one target per host name. A host that names several
   * servers gets a group of them, since a route has one target.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param key the source key of the forced hosts
   * @param hosts the hosts, each with the servers it sends players to
   * @param servers the names of the servers that exist
   */
  static void routes(
      Draft draft,
      Report report,
      String file,
      String key,
      Map<String, List<String>> hosts,
      Set<String> servers) {
    for (Map.Entry<String, List<String>> entry : hosts.entrySet()) {
      String host = entry.getKey().toLowerCase(Locale.ROOT);
      String sourceKey = key + "." + Source.join(entry.getKey());
      List<String> targets = new ArrayList<>();
      for (String target : entry.getValue()) {
        if (servers.contains(target)) {
          targets.add(target);
        } else {
          report.warn(
              sourceKey
                  + " sends players to '"
                  + target
                  + "', a server that does not exist: left out rather than refused at startup.");
        }
      }
      if (targets.isEmpty()) {
        report.drop(sourceKey, "names no server that exists");
        continue;
      }
      String target = targets.getFirst();
      if (targets.size() > 1) {
        target = groupName(host, servers);
        String origin = origin(file, sourceKey, entry.getValue());
        draft.set(List.of("groups", target, "servers"), targets, origin);
        draft.set(
            List.of("groups", target, "strategy"),
            "round-robin",
            "Velocity tried these servers in order; Warp balances players across the group");
        report.warn(
            sourceKey
                + " lists several servers, which Velocity tried in order: Warp routes the host"
                + " to the group '"
                + target
                + "' of them, balanced round-robin. Change its strategy, or route to one"
                + " server, if that is not what you want.");
      }
      draft.set(List.of("routes", host), target, origin(file, sourceKey, entry.getValue()));
      report.keep(sourceKey, entry.getValue(), "routes." + Source.join(host));
    }
  }

  /** A group name from a host: {@code lobby.example.com} becomes {@code lobby-example-com}. */
  private static String groupName(String host, Set<String> servers) {
    String name = host.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    if (name.isEmpty()) {
      name = "hosts";
    }
    return servers.contains(name) ? name + "-group" : name;
  }

  /**
   * Writes the message of the day.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param key the source key
   * @param text the source text, in MiniMessage or legacy codes
   */
  static void motd(Draft draft, Report report, String file, String key, String text) {
    List<String> lines = Conversions.motd(text);
    if (lines.size() > 2) {
      report.warn(key + " has " + lines.size() + " lines; clients show two.");
    }
    Object value = lines.size() == 1 ? lines.getFirst() : lines;
    draft.set("status.motd", value, origin(file, key, text));
    report.keep(key, text, "status.motd");
  }

  /**
   * Writes the forwarding mode. Warp forwards with Velocity's modern protocol or not at all:
   * BungeeCord's legacy forwarding and BungeeGuard are turned into modern forwarding, with a
   * warning.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param key the source key
   * @param value the source value, as the report shows it
   * @param mode the source mode: {@code none}, {@code modern}, {@code legacy} or {@code
   *     bungeeguard}
   */
  static void forwarding(
      Draft draft, Report report, String file, String key, Object value, String mode) {
    String origin = origin(file, key, value);
    switch (mode) {
      case "none" -> draft.set("forwarding.mode", "none", origin);
      case "modern" -> {
        draft.set("forwarding.mode", "velocity", origin);
        report.step(BACKENDS_STEP);
      }
      default -> {
        draft.set("forwarding.mode", "velocity", origin);
        report.warn(
            key
                + " used "
                + mode
                + " forwarding, which a client can spoof. Warp only forwards with Velocity's"
                + " modern protocol (signed): the backends must be reconfigured, see the next"
                + " steps.");
        report.step(BACKENDS_STEP);
      }
    }
    draft.set("forwarding.secret-file", "forwarding.secret", origin);
    report.keep(key, value, "forwarding.mode");
  }

  /**
   * Writes the PROXY protocol setting. Warp refuses the header from addresses it does not trust, so
   * enabling it needs the load balancers' addresses, which neither source proxy has.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param key the source key
   * @param enabled whether the source accepted the header
   */
  static void proxyProtocol(Draft draft, Report report, String file, String key, boolean enabled) {
    draft.set("proxy-protocol.enabled", enabled, origin(file, key, enabled));
    report.keep(key, enabled, "proxy-protocol.enabled");
    if (enabled) {
      draft.set(
          "proxy-protocol.trusted-proxies",
          List.of(),
          "Fill in before starting: the load balancers that send the PROXY header, as addresses"
              + " or CIDR ranges");
      report.warn(
          key
              + " is on. Warp only accepts the PROXY header from the load balancers listed in"
              + " proxy-protocol.trusted-proxies, and refuses to start while the list is empty:"
              + " fill it in before starting.");
      report.step("List your load balancers in proxy-protocol.trusted-proxies.");
    }
  }

  /**
   * Writes the read timeouts. The source has one for clients and backends alike; Warp keeps the
   * client's below the client's own 30 seconds, so that the proxy notices a dead client first.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param key the source key
   * @param millis the source timeout
   */
  static void readTimeout(Draft draft, Report report, String file, String key, long millis) {
    String origin = origin(file, key, millis);
    draft.set("timeouts.backend-read", Conversions.duration(millis), origin);
    if (millis < CLIENT_TIMEOUT_MILLIS) {
      draft.set("timeouts.read", Conversions.duration(millis), origin);
      report.keep(key, millis, "timeouts.read, timeouts.backend-read");
    } else {
      report.keep(key, millis, "timeouts.backend-read");
      report.warn(
          key
              + " is "
              + millis
              + " ms, not below the client's own 30 s: timeouts.read keeps Warp's default of"
              + " 25 s, so that Warp notices a dead client before the client gives up.");
    }
  }

  /**
   * Writes the login rate limit from one login per address every {@code millis}.
   *
   * @param draft the configuration
   * @param report the report
   * @param file the source file's name
   * @param key the source key
   * @param millis the source interval, 0 for none
   */
  static void loginRate(Draft draft, Report report, String file, String key, long millis) {
    if (millis <= 0) {
      report.drop(
          key,
          "the limiter was off; Warp keeps its default login limits (limits.logins), which"
              + " never lock a player out while they refresh the server list");
      return;
    }
    String origin = origin(file, key, millis);
    draft.set("limits.logins.burst", 2, origin);
    draft.set("limits.logins.interval", Conversions.duration(millis), origin);
    report.keep(key, millis, "limits.logins.burst, limits.logins.interval");
  }

  /**
   * Drops every key nobody read, and reports values of an unexpected kind.
   *
   * @param source the source
   * @param report the report
   * @param proxy the source proxy's name
   */
  static void finish(Source source, Report report, String proxy) {
    for (Source.Mismatch mismatch : source.mismatches()) {
      report.warn(
          mismatch.key()
              + " is "
              + mismatch.actual()
              + " where "
              + proxy
              + " expects "
              + mismatch.expected()
              + ": ignored.");
    }
    for (String key : source.unconsumed()) {
      report.drop(key, "not a " + proxy + " setting Warp knows");
    }
  }
}
