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
package dev.warp.proxy.command;

import dev.warp.api.Warp;
import dev.warp.api.command.CommandSource;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginDescription;
import dev.warp.api.plugin.PluginState;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerGroup;
import dev.warp.proxy.WarpBuildInfo;
import dev.warp.proxy.console.StartupTimings;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.server.HostRoutes;
import dev.warp.proxy.server.ServerHealth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.netty.util.internal.PlatformDependent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import org.jspecify.annotations.Nullable;

/**
 * {@code /warp}: what runs, for staff granted {@value #PERMISSION}. Its subcommands each have a
 * permission of their own; a source granted only {@code warp.command.antibot} gets {@code /warp} as
 * the list of what it may use.
 *
 * <ul>
 *   <li>{@code /warp}: version and build, uptime, Java, players and servers, compression, memory,
 *       plugins, the drain when one runs
 *   <li>{@code /warp plugins}: every plugin, its version and state, why it failed on hover
 *   <li>{@code /warp startup}: where the startup's time went, phase by phase and plugin by plugin
 *   <li>{@code /warp antibot ...}: see {@link AntiBotCommand}
 * </ul>
 */
final class WarpCommand {

  /** The permission {@code /warp} and {@code /warp plugins} need. */
  static final String PERMISSION = "warp.command.warp";

  private static final long MIB = 1024 * 1024;

  private WarpCommand() {}

  /**
   * Builds {@code /warp}.
   *
   * @param proxy the proxy
   * @param control what the proxy's API does not tell
   * @param antiBot {@code /warp antibot}, or {@code null} without an anti-bot
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> build(
      Warp proxy, ProxyControl control, @Nullable LiteralArgumentBuilder<CommandSource> antiBot) {
    LiteralArgumentBuilder<CommandSource> warp =
        LiteralArgumentBuilder.<CommandSource>literal("warp")
            .requires(
                source ->
                    source.hasPermission(PERMISSION)
                        || (antiBot != null && source.hasPermission(AntiBotCommand.PERMISSION)))
            .executes(
                context -> {
                  CommandSource source = context.getSource();
                  source.sendMessage(
                      source.hasPermission(PERMISSION)
                          ? overview(proxy, control, source, antiBot != null)
                          : subcommands(source, antiBot != null));
                  return Command.SINGLE_SUCCESS;
                })
            .then(
                LiteralArgumentBuilder.<CommandSource>literal("plugins")
                    .requires(source -> source.hasPermission(PERMISSION))
                    .executes(
                        context -> {
                          context.getSource().sendMessage(plugins(proxy.plugins().plugins()));
                          return Command.SINGLE_SUCCESS;
                        }))
            .then(
                LiteralArgumentBuilder.<CommandSource>literal("startup")
                    .requires(source -> source.hasPermission(PERMISSION))
                    .executes(
                        context -> {
                          context.getSource().sendMessage(startup(control.startup()));
                          return Command.SINGLE_SUCCESS;
                        }));
    warp.then(
        LiteralArgumentBuilder.<CommandSource>literal("groups")
            .requires(source -> source.hasPermission(PERMISSION))
            .executes(
                context -> {
                  context.getSource().sendMessage(groups(proxy, control.health()));
                  return Command.SINGLE_SUCCESS;
                }));
    warp.then(
        LiteralArgumentBuilder.<CommandSource>literal("routes")
            .requires(source -> source.hasPermission(PERMISSION))
            .executes(
                context -> {
                  context.getSource().sendMessage(routes(control.routes()));
                  return Command.SINGLE_SUCCESS;
                }));
    if (antiBot != null) {
      warp.then(antiBot);
    }
    return warp;
  }

  // ---------------------------------------------------------------------------
  // /warp groups, /warp routes
  // ---------------------------------------------------------------------------

  /**
   * The answer to {@code /warp groups}: each group with its strategy, and under it each member
   * server as the balancer sees it (players, last ping round trip, circuit breaker).
   *
   * @param proxy the proxy
   * @param health the servers' health, or {@code null} when not tracked
   * @return the message
   */
  static Component groups(Warp proxy, @Nullable ServerHealth health) {
    Collection<ServerGroup> groups = proxy.groups().groups();
    if (groups.isEmpty()) {
      return Message.WARP_GROUPS_NONE.of();
    }
    List<Component> lines = new ArrayList<>();
    for (ServerGroup group : groups) {
      lines.add(
          Message.WARP_GROUP.with(
              Message.text("group", group.name()),
              Message.text("strategy", group.strategy()),
              Message.count(group.members().size())));
      for (ServerGroup.Member member : group.members()) {
        Optional<RegisteredServer> server = proxy.servers().server(member.server());
        if (server.isEmpty()) {
          lines.add(
              Message.WARP_GROUP_SERVER_UNREGISTERED.with(Message.text("server", member.server())));
          continue;
        }
        ServerHealth.Status status =
            health != null
                ? health.status(member.server())
                : new ServerHealth.Status(ServerHealth.State.CLOSED, 0, null, 0, false);
        Component rtt =
            status
                .rtt()
                .map(d -> Message.WARP_GROUP_RTT.with(Message.number("millis", d.toMillis())))
                .orElseGet(Message.WARP_GROUP_RTT_NONE::of);
        Component state =
            switch (status.state()) {
              case CLOSED -> Message.WARP_GROUP_STATE_CLOSED.of();
              case OPEN ->
                  Message.WARP_GROUP_STATE_OPEN.with(Message.number("failures", status.failures()));
              case HALF_OPEN -> Message.WARP_GROUP_STATE_HALF_OPEN.of();
            };
        lines.add(
            Message.WARP_GROUP_SERVER.with(
                Message.component("server", GlistCommand.serverLink(member.server())),
                Message.number("players", server.get().playerCount()),
                Message.component("rtt", rtt),
                Message.component("state", state)));
      }
    }
    return Component.join(JoinConfiguration.newlines(), lines);
  }

  /**
   * The answer to {@code /warp routes}: the routes of the configuration, most specific first.
   *
   * @param hostRoutes the routes
   * @return the message
   */
  static Component routes(HostRoutes hostRoutes) {
    if (hostRoutes.isEmpty()) {
      return Message.WARP_ROUTES_NONE.of();
    }
    var routes = hostRoutes.entries();
    List<Component> lines = new ArrayList<>();
    lines.add(Message.WARP_ROUTES.with(Message.number("count", routes.size())));
    for (var route : routes) {
      lines.add(
          Message.WARP_ROUTE.with(
              Message.text("host", route.getKey()), Message.text("target", route.getValue())));
    }
    return Component.join(JoinConfiguration.newlines(), lines);
  }

  // ---------------------------------------------------------------------------
  // /warp startup
  // ---------------------------------------------------------------------------

  /**
   * The answer to {@code /warp startup}: where the startup's time went, phase by phase, each plugin
   * under {@code plugins}.
   *
   * @param timings the startup's timings, or {@code null} when nothing recorded them
   * @return the message
   */
  static Component startup(@Nullable StartupTimings timings) {
    long total = timings == null ? -1 : timings.totalNanos();
    if (timings == null || total <= 0) {
      return Message.WARP_STARTUP_PENDING.of();
    }
    List<Component> lines = new ArrayList<>();
    lines.add(Message.WARP_STARTUP.with(Message.text("total", StartupTimings.format(total))));
    for (StartupTimings.Phase phase : timings.phases()) {
      lines.add(phase(phase, "", total));
      for (StartupTimings.Phase part : phase.parts()) {
        lines.add(phase(part, "  ", total));
      }
    }
    return Component.join(JoinConfiguration.newlines(), lines);
  }

  private static Component phase(StartupTimings.Phase phase, String indent, long total) {
    return Message.WARP_STARTUP_PHASE.with(
        Message.text("phase", indent + phase.name()),
        Message.text("time", StartupTimings.format(phase.nanos())),
        Message.text("share", String.format(Locale.ROOT, "%.0f%%", 100.0 * phase.nanos() / total)));
  }

  // ---------------------------------------------------------------------------
  // Overview
  // ---------------------------------------------------------------------------

  /**
   * The answer to {@code /warp}.
   *
   * @param proxy the proxy
   * @param control what the API does not tell
   * @param source who asks, for the subcommands it may use
   * @param antiBot whether {@code /warp antibot} exists
   * @return the message
   */
  static Component overview(
      Warp proxy, ProxyControl control, CommandSource source, boolean antiBot) {
    List<Component> lines = new ArrayList<>();
    String copy = "Warp " + WarpBuildInfo.VERSION + " (" + WarpBuildInfo.GIT_COMMIT + ")";
    lines.add(
        Message.WARP_VERSION
            .with(
                Message.text("version", WarpBuildInfo.VERSION),
                Message.text("commit", abbreviate(WarpBuildInfo.GIT_COMMIT)),
                Message.text("branch", WarpBuildInfo.GIT_BRANCH))
            .clickEvent(ClickEvent.copyToClipboard(copy))
            .hoverEvent(HoverEvent.showText(Message.WARP_VERSION_HOVER.of())));
    lines.add(
        Message.WARP_RUNTIME.with(
            Message.component("uptime", CommandSupport.duration(control.uptime())),
            Message.text("java", Runtime.version().toString()),
            Message.text(
                "vm",
                System.getProperty("java.vm.name", "?")
                    + ", "
                    + System.getProperty("java.vm.vendor", "?"))));
    lines.add(
        Message.WARP_PLAYERS.with(
            Message.number("players", proxy.playerCount()),
            Message.number("servers", proxy.servers().servers().size())));
    lines.add(Message.WARP_COMPRESSION.with(Message.text("compression", control.compression())));
    Runtime runtime = Runtime.getRuntime();
    long direct = PlatformDependent.usedDirectMemory();
    lines.add(
        Message.WARP_MEMORY.with(
            Message.number("used", (runtime.totalMemory() - runtime.freeMemory()) / MIB),
            Message.number("max", runtime.maxMemory() / MIB),
            direct < 0 ? Message.text("direct", "?") : Message.number("direct", direct / MIB)));
    List<PluginContainer> plugins = proxy.plugins().plugins();
    lines.add(
        plugins.isEmpty()
            ? Message.WARP_PLUGINS_NONE.of()
            : Message.WARP_PLUGINS.with(
                Message.number("count", plugins.size()),
                Message.component("states", states(plugins))));
    if (proxy.isDraining()) {
      DrainStatus drain = proxy.drainStatus();
      lines.add(
          Message.WARP_DRAINING.with(
              Message.component("elapsed", CommandSupport.duration(drain.elapsed())),
              Message.component("phase", ShutdownCommand.describe(drain.phase())),
              Message.component("trigger", ShutdownCommand.describe(drain.trigger())),
              Message.number("players", drain.players()),
              Formatter.choice("n", drain.players())));
    }
    lines.add(subcommands(source, antiBot));
    return Component.join(JoinConfiguration.newlines(), lines);
  }

  /** The subcommands a source may use, each a link that types it. */
  private static Component subcommands(CommandSource source, boolean antiBot) {
    List<Component> links = new ArrayList<>();
    if (source.hasPermission(PERMISSION)) {
      links.add(subcommand("/warp plugins"));
      links.add(subcommand("/warp startup"));
      links.add(subcommand("/warp groups"));
      links.add(subcommand("/warp routes"));
    }
    if (antiBot && source.hasPermission(AntiBotCommand.PERMISSION)) {
      links.add(subcommand("/warp antibot"));
    }
    return Message.WARP_SUBCOMMANDS.with(
        Message.component("commands", CommandSupport.join(links, ", ")));
  }

  private static Component subcommand(String command) {
    return CommandSupport.suggests(
        Message.VALUE.with(Message.text("text", command)),
        command,
        Message.WARP_SUBCOMMAND_HOVER.with(Message.text("command", command)));
  }

  private static String abbreviate(String commit) {
    return commit.length() > 10 ? commit.substring(0, 10) : commit;
  }

  // ---------------------------------------------------------------------------
  // Plugins
  // ---------------------------------------------------------------------------

  /**
   * The answer to {@code /warp plugins}: one line per plugin, by identifier.
   *
   * @param plugins the plugins
   * @return the message
   */
  static Component plugins(List<PluginContainer> plugins) {
    if (plugins.isEmpty()) {
      return Message.PLUGINS_NONE.of();
    }
    List<Component> lines = new ArrayList<>();
    lines.add(
        Message.PLUGINS_HEADER.with(
            Message.count(plugins.size()), Message.component("states", states(plugins))));
    plugins.stream()
        .sorted(Comparator.comparing(plugin -> plugin.description().id()))
        .forEach(plugin -> lines.add(line(plugin)));
    return Component.join(JoinConfiguration.newlines(), lines);
  }

  private static Component line(PluginContainer plugin) {
    PluginDescription description = plugin.description();
    Component hover =
        Message.PLUGINS_HOVER.with(
            Message.text("id", description.id()),
            Message.text("version", description.version()),
            Message.text("description", description.description()));
    if (!description.authors().isEmpty()) {
      hover =
          hover.append(
              Message.PLUGINS_HOVER_AUTHORS.with(
                  Message.text("authors", String.join(", ", description.authors()))));
    }
    if (!description.url().isEmpty()) {
      hover = hover.append(Message.PLUGINS_HOVER_URL.with(Message.text("url", description.url())));
    }
    Throwable failure = plugin.failure().orElse(null);
    if (failure != null) {
      hover =
          hover.append(
              Message.PLUGINS_HOVER_FAILURE.with(Message.text("failure", String.valueOf(failure))));
    }
    return Message.PLUGINS_LINE
        .with(
            Message.text("name", description.name()),
            Message.text("version", description.version()),
            Message.component("state", state(plugin.state())))
        .hoverEvent(HoverEvent.showText(hover));
  }

  /** How many plugins are in each state: "2 enabled, 1 failed". */
  private static Component states(List<PluginContainer> plugins) {
    Map<PluginState, Integer> counts = new EnumMap<>(PluginState.class);
    for (PluginContainer plugin : plugins) {
      counts.merge(plugin.state(), 1, Integer::sum);
    }
    List<Component> parts = new ArrayList<>();
    counts.forEach(
        (state, count) ->
            parts.add(
                Message.PLUGIN_STATE_COUNT.with(
                    Message.number("count", count), Message.component("state", state(state)))));
    return CommandSupport.join(parts, ", ");
  }

  private static Component state(PluginState state) {
    return switch (state) {
      case LOADING -> Message.PLUGIN_STATE_LOADING.of();
      case ENABLED -> Message.PLUGIN_STATE_ENABLED.of();
      case FAILED -> Message.PLUGIN_STATE_FAILED.of();
      case DISABLED -> Message.PLUGIN_STATE_DISABLED.of();
    };
  }
}
