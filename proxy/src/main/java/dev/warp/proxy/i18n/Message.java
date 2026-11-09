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
package dev.warp.proxy.i18n;

import dev.warp.protocol.ProtocolVersion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jspecify.annotations.Nullable;

/**
 * Every message Warp itself shows players, by its translation key.
 *
 * <p>A message is sent as a translatable component and rendered for each player in its language
 * just before it is written (see {@link PlayerMessages#render}). Where vanilla has a message that
 * says the same thing, its key is used: a client that knows the key translates it itself, in any of
 * the game's languages; Warp renders it only for a client too old to know it, or when the operator
 * overrides it. Every other key starts with {@code warp.}.
 *
 * <p>A message's text is MiniMessage. Its placeholders are named: {@code <server>}, {@code
 * <reason>}... A vanilla message takes its arguments in order, {@code <arg:0>} first. Texts style
 * with the style tags of {@link PlayerMessages#STYLES} rather than colors, so that every message
 * looks the same: {@code <value>} for names and numbers, {@code <good>} for success, {@code <bad>}
 * for failures, {@code <warn>} for what needs attention, {@code <dim>} for hints; the plain text of
 * a chat line is gray. A message with a {@code <count>} may agree with it through {@code <n>}, a
 * choice: {@code <n:'0#players|1#player|2#players'>}.
 */
public enum Message {

  // ---------------------------------------------------------------------------
  // Refused at login
  // ---------------------------------------------------------------------------

  /** Beyond the player limit. */
  SERVER_FULL(
      "multiplayer.disconnect.server_full", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_13),
  /** A login that took longer than the login timeout. */
  SLOW_LOGIN("multiplayer.disconnect.slow_login", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** The session server failed: nobody can log in for now. */
  AUTHENTICATION_SERVERS_DOWN(
      "multiplayer.disconnect.authservers_down", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** A cookie or plugin response the proxy never asked for. */
  UNEXPECTED_QUERY_RESPONSE(
      "multiplayer.disconnect.unexpected_query_response",
      Kind.DISCONNECT,
      ProtocolVersion.MINECRAFT_1_13),
  /** A chat signing key that is invalid (1.19 to 1.19.2 clients only). */
  INVALID_PUBLIC_KEY_SIGNATURE(
      "multiplayer.disconnect.invalid_public_key_signature",
      Kind.DISCONNECT,
      ProtocolVersion.MINECRAFT_1_19),
  /** A transfer while {@code accepts-transfers} is off (1.20.5+ clients only). */
  TRANSFERS_DISABLED(
      "multiplayer.disconnect.transfers_disabled",
      Kind.DISCONNECT,
      ProtocolVersion.MINECRAFT_1_20_5),
  /** Logging in again too soon. */
  LOGIN_TOO_FAST("warp.login.too-fast", Kind.DISCONNECT, "seconds"),
  /** Too many logins in progress from one address. */
  TOO_MANY_LOGINS("warp.login.too-many-logins", Kind.DISCONNECT),
  /** Waited in line for authentication longer than the queue allows. */
  LOGIN_BUSY("warp.login.busy", Kind.DISCONNECT, "seconds"),
  /** An unknown player during the anti-bot's lockdown. */
  LOCKDOWN("warp.login.lockdown", Kind.DISCONNECT),
  /** An unknown player the proxy had no room to verify. */
  VERIFICATION_BUSY("warp.login.verification-busy", Kind.DISCONNECT, "seconds"),
  /** An unknown player whose client did what no real client does during the probe. */
  VERIFICATION_FAILED("warp.login.verification-failed", Kind.DISCONNECT),
  /** An unknown player whose client did not answer the probe in time. */
  VERIFICATION_TIMED_OUT("warp.login.verification-timed-out", Kind.DISCONNECT),
  /** A player verified before authentication, on a version without transfers. */
  VERIFIED_RECONNECT("warp.login.verified-reconnect", Kind.DISCONNECT),
  /** A username no vanilla client sends. */
  INVALID_USERNAME("warp.login.invalid-username", Kind.DISCONNECT),
  /** Floodgate data that did not verify: a Bedrock player Warp cannot vouch for. */
  FLOODGATE_REFUSED("warp.login.floodgate-refused", Kind.DISCONNECT),
  /** A login packet sent out of order: a broken or modified client. */
  UNEXPECTED_PACKET("warp.login.unexpected-packet", Kind.DISCONNECT),
  /** The encryption handshake failed. */
  ENCRYPTION_FAILED("warp.login.encryption-failed", Kind.DISCONNECT),
  /** The session server does not know the player's session. */
  AUTHENTICATION_FAILED("warp.login.authentication-failed", Kind.DISCONNECT),
  /** The duplicate login policy keeps the session already connected. */
  ALREADY_CONNECTED("warp.login.already-connected", Kind.DISCONNECT),

  // ---------------------------------------------------------------------------
  // Disconnected
  // ---------------------------------------------------------------------------

  /** The same account logged in again, and the duplicate login policy keeps the newcomer. */
  DUPLICATE_LOGIN(
      "multiplayer.disconnect.duplicate_login", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** The proxy shuts down. */
  SERVER_SHUTDOWN(
      "multiplayer.disconnect.server_shutdown", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** The proxy drains and cannot transfer the player elsewhere. */
  RESTARTING("warp.disconnect.restarting", Kind.DISCONNECT),
  /** No server can take the player when it joins. */
  NO_SERVER_AVAILABLE("warp.disconnect.no-server-available", Kind.DISCONNECT),
  /** Every server of the fallback order failed. */
  NO_SERVER_LEFT("warp.disconnect.no-server-left", Kind.DISCONNECT),
  /** A plugin denied the player its first server. */
  INITIAL_SERVER_DENIED("warp.disconnect.initial-server-denied", Kind.DISCONNECT),
  /** A plugin denied a fallback server. */
  FALLBACK_DENIED("warp.disconnect.fallback-denied", Kind.DISCONNECT, "server"),
  /** A server switch took too long. */
  SWITCH_TIMED_OUT("warp.disconnect.switch-timed-out", Kind.DISCONNECT),
  /** Plugins took too long to decide where the player goes. */
  WAIT_TIMED_OUT("warp.disconnect.wait-timed-out", Kind.DISCONNECT),
  /** A reason too large for the client, replaced by this one. */
  REASON_TOO_LONG("warp.disconnect.reason-too-long", Kind.DISCONNECT),
  /** Something went wrong in the proxy itself; the reference is in the log too. */
  INTERNAL_ERROR("warp.disconnect.internal-error", Kind.DISCONNECT, "reference"),

  // ---------------------------------------------------------------------------
  // Chat
  // ---------------------------------------------------------------------------

  /** A server could not be reached or refused the player, which stays where it is. */
  CONNECT_FAILED("warp.connect.failed", Kind.CHAT, "server"),
  /** Same, with the reason the server gave. */
  CONNECT_FAILED_REASON("warp.connect.failed-reason", Kind.CHAT, "server", "reason"),
  /** A plugin denied a switch the player asked for. */
  CONNECT_DENIED("warp.connect.denied", Kind.CHAT, "server"),
  /** The player asked for a switch during another. */
  ALREADY_SWITCHING("warp.connect.already-switching", Kind.CHAT),
  /** The player asked for the server it is on. */
  ALREADY_ON_SERVER("warp.connect.already-on-server", Kind.CHAT, "server"),
  /** The player is moved to a fallback server. */
  CONNECTING("warp.connect.connecting", Kind.CHAT, "server"),
  /** The player was moved to a fallback after its server disconnected it with a reason. */
  FALLBACK_KICKED("warp.fallback.kicked", Kind.CHAT, "server", "fallback", "reason"),
  /** The player was moved to a fallback after its server went away. */
  FALLBACK_LOST("warp.fallback.lost", Kind.CHAT, "server", "fallback"),
  /** A command failed in the proxy. */
  COMMAND_FAILED("warp.command.failed", Kind.CHAT),

  // ---------------------------------------------------------------------------
  // Commands: shared
  // ---------------------------------------------------------------------------

  /** A name or a number standing out of the text around it. */
  VALUE("warp.command.value", Kind.PART, "text"),
  /** A command named a server that does not exist. */
  UNKNOWN_SERVER("warp.command.server.unknown", Kind.CHAT, "server"),
  /** A command named a player who is not connected. */
  UNKNOWN_PLAYER("warp.command.unknown-player", Kind.CHAT, "player"),
  /** Under a page of a list: where it is. */
  PAGE_STATUS("warp.command.page.status", Kind.PART, "page", "pages"),
  /** Under a page of a list: the link to the previous page. */
  PAGE_PREVIOUS("warp.command.page.previous", Kind.PART),
  /** Under a page of a list: the link to the next page. */
  PAGE_NEXT("warp.command.page.next", Kind.PART),
  /** The hover text of the link to the previous page. */
  PAGE_PREVIOUS_HOVER("warp.command.page.previous.hover", Kind.PART, "page"),
  /** The hover text of the link to the next page. */
  PAGE_NEXT_HOVER("warp.command.page.next.hover", Kind.PART, "page"),
  /** A page beyond the last one. */
  PAGE_OUT_OF_RANGE("warp.command.page.out-of-range", Kind.CHAT, "page", "pages"),
  /** A duration of days, then hours. */
  DURATION_DAYS("warp.command.duration.days", Kind.PART, "days", "hours"),
  /** A duration of whole days. */
  DURATION_DAYS_ONLY("warp.command.duration.days-only", Kind.PART, "days"),
  /** A duration of hours, then minutes. */
  DURATION_HOURS("warp.command.duration.hours", Kind.PART, "hours", "minutes"),
  /** A duration of whole hours. */
  DURATION_HOURS_ONLY("warp.command.duration.hours-only", Kind.PART, "hours"),
  /** A duration of minutes, then seconds. */
  DURATION_MINUTES("warp.command.duration.minutes", Kind.PART, "minutes", "seconds"),
  /** A duration of whole minutes. */
  DURATION_MINUTES_ONLY("warp.command.duration.minutes-only", Kind.PART, "minutes"),
  /** A duration of seconds. */
  DURATION_SECONDS("warp.command.duration.seconds", Kind.PART, "seconds"),
  /** What started a drain: SIGTERM. */
  TRIGGER_SIGNAL("warp.command.trigger.signal", Kind.PART),
  /** What started a drain: the management API. */
  TRIGGER_HTTP("warp.command.trigger.http", Kind.PART),
  /** What started a drain: a plugin. */
  TRIGGER_PLUGIN("warp.command.trigger.plugin", Kind.PART),
  /** What started a drain: {@code /shutdown}. */
  TRIGGER_COMMAND("warp.command.trigger.command", Kind.PART),
  /** A drain that has not started. */
  PHASE_NONE("warp.command.phase.none", Kind.PART),
  /** A drain announcing itself to load balancers. */
  PHASE_ANNOUNCING("warp.command.phase.announcing", Kind.PART),
  /** A drain transferring players. */
  PHASE_TRANSFERRING("warp.command.phase.transferring", Kind.PART),
  /** A drain waiting for the players it kept. */
  PHASE_WAITING("warp.command.phase.waiting", Kind.PART),
  /** A drain stopping the proxy. */
  PHASE_STOPPING("warp.command.phase.stopping", Kind.PART),

  // ---------------------------------------------------------------------------
  // /server
  // ---------------------------------------------------------------------------

  /** The list of servers. */
  SERVER_LIST("warp.command.server.list", Kind.CHAT, "servers"),
  /** The hover text of a server of the list. */
  SERVER_LIST_HOVER("warp.command.server.hover", Kind.PART, "server"),
  /** {@code /server <server>} from the console. */
  SERVER_NOT_A_PLAYER("warp.command.server.not-a-player", Kind.CHAT),

  // ---------------------------------------------------------------------------
  // /warp
  // ---------------------------------------------------------------------------

  /** The version line. */
  WARP_VERSION("warp.command.warp.version", Kind.CHAT, "version", "commit", "branch"),
  /** The hover text of the version line. */
  WARP_VERSION_HOVER("warp.command.warp.version.hover", Kind.PART),
  /** Uptime and Java. */
  WARP_RUNTIME("warp.command.warp.runtime", Kind.CHAT, "uptime", "java", "vm"),
  /** Players and servers. */
  WARP_PLAYERS("warp.command.warp.players", Kind.CHAT, "players", "servers"),
  /** The compression implementation. */
  WARP_COMPRESSION("warp.command.warp.compression", Kind.CHAT, "compression"),
  /** Heap and direct memory, in MiB. */
  WARP_MEMORY("warp.command.warp.memory", Kind.CHAT, "used", "max", "direct"),
  /** Plugins by state. */
  WARP_PLUGINS("warp.command.warp.plugins", Kind.CHAT, "count", "states"),
  /** No plugins. */
  WARP_PLUGINS_NONE("warp.command.warp.plugins-none", Kind.CHAT),
  /** The drain, while one runs. */
  WARP_DRAINING(
      "warp.command.warp.draining", Kind.CHAT, "elapsed", "phase", "trigger", "players", "n"),
  /** Links to the subcommands. */
  WARP_SUBCOMMANDS("warp.command.warp.subcommands", Kind.CHAT, "commands"),
  /** The hover text of a subcommand's link. */
  WARP_SUBCOMMAND_HOVER("warp.command.warp.subcommand.hover", Kind.PART, "command"),
  /** The startup's breakdown, heading. */
  WARP_STARTUP("warp.command.warp.startup", Kind.CHAT, "total"),
  /** One phase of the startup. */
  WARP_STARTUP_PHASE("warp.command.warp.startup.phase", Kind.CHAT, "phase", "time", "share"),
  /** The startup is not over. */
  WARP_STARTUP_PENDING("warp.command.warp.startup.pending", Kind.CHAT),
  /** No group is registered. */
  WARP_GROUPS_NONE("warp.command.warp.groups.none", Kind.CHAT),
  /** A group: its name, strategy and member count. */
  WARP_GROUP("warp.command.warp.groups.group", Kind.CHAT, "group", "strategy", "count", "n"),
  /** A member server of a group, with the balancer's view of it. */
  WARP_GROUP_SERVER(
      "warp.command.warp.groups.server", Kind.CHAT, "server", "players", "rtt", "state"),
  /** A member server that is not registered. */
  WARP_GROUP_SERVER_UNREGISTERED(
      "warp.command.warp.groups.server.unregistered", Kind.CHAT, "server"),
  /** The round trip of a server's last ping. */
  WARP_GROUP_RTT("warp.command.warp.groups.rtt", Kind.PART, "millis"),
  /** A server never answered a ping. */
  WARP_GROUP_RTT_NONE("warp.command.warp.groups.rtt.none", Kind.PART),
  /** A server's breaker is closed: it takes players. */
  WARP_GROUP_STATE_CLOSED("warp.command.warp.groups.state.closed", Kind.PART),
  /** A server's breaker is open: it is skipped. */
  WARP_GROUP_STATE_OPEN("warp.command.warp.groups.state.open", Kind.PART, "failures"),
  /** A server's breaker is half-open: one player probes it. */
  WARP_GROUP_STATE_HALF_OPEN("warp.command.warp.groups.state.half-open", Kind.PART),
  /** The routes, as a header. */
  WARP_ROUTES("warp.command.warp.routes", Kind.CHAT, "count"),
  /** No route is configured. */
  WARP_ROUTES_NONE("warp.command.warp.routes.none", Kind.CHAT),
  /** One route. */
  WARP_ROUTE("warp.command.warp.routes.route", Kind.CHAT, "host", "target"),

  // ---------------------------------------------------------------------------
  // /log (console only)
  // ---------------------------------------------------------------------------

  /** A source's level changed until the proxy stops. */
  LOG_LEVEL_SET("warp.command.log.level.set", Kind.CHAT, "source", "level", "loggers", "keep"),
  /** A source's level put back. */
  LOG_LEVEL_RESET("warp.command.log.level.reset", Kind.CHAT, "source"),
  /** A source that /log level did not change. */
  LOG_LEVEL_UNCHANGED("warp.command.log.level.unchanged", Kind.CHAT, "source"),
  /** The levels /log level changed. */
  LOG_LEVEL_LIST("warp.command.log.level.list", Kind.CHAT, "levels"),
  /** No level changed. */
  LOG_LEVEL_NONE("warp.command.log.level.none", Kind.CHAT),
  /** Not a level. */
  LOG_LEVEL_INVALID("warp.command.log.level.invalid", Kind.CHAT, "level"),
  /** No such source. */
  LOG_SOURCE_UNKNOWN("warp.command.log.source.unknown", Kind.CHAT, "source", "sources"),
  /** The console shows one source. */
  LOG_FILTER_ON("warp.command.log.filter.on", Kind.CHAT, "source"),
  /** The console shows every source again. */
  LOG_FILTER_OFF("warp.command.log.filter.off", Kind.CHAT),
  /** The console writes JSON. */
  LOG_UNAVAILABLE("warp.command.log.unavailable", Kind.CHAT),

  // ---------------------------------------------------------------------------
  // /warp plugins
  // ---------------------------------------------------------------------------

  /** No plugins. */
  PLUGINS_NONE("warp.command.plugins.none", Kind.CHAT),
  /** The count of plugins, by state. */
  PLUGINS_HEADER("warp.command.plugins.header", Kind.CHAT, "count", "n", "states"),
  /** One plugin. */
  PLUGINS_LINE("warp.command.plugins.line", Kind.CHAT, "name", "version", "state"),
  /** The hover text of a plugin. */
  PLUGINS_HOVER("warp.command.plugins.hover", Kind.PART, "id", "version", "description"),
  /** A plugin's authors, in its hover text. */
  PLUGINS_HOVER_AUTHORS("warp.command.plugins.hover.authors", Kind.PART, "authors"),
  /** A plugin's URL, in its hover text. */
  PLUGINS_HOVER_URL("warp.command.plugins.hover.url", Kind.PART, "url"),
  /** Why a plugin failed, in its hover text. */
  PLUGINS_HOVER_FAILURE("warp.command.plugins.hover.failure", Kind.PART, "failure"),
  /** A plugin loading. */
  PLUGIN_STATE_LOADING("warp.command.plugin-state.loading", Kind.PART),
  /** A plugin enabled. */
  PLUGIN_STATE_ENABLED("warp.command.plugin-state.enabled", Kind.PART),
  /** A plugin that failed. */
  PLUGIN_STATE_FAILED("warp.command.plugin-state.failed", Kind.PART),
  /** A plugin disabled. */
  PLUGIN_STATE_DISABLED("warp.command.plugin-state.disabled", Kind.PART),
  /** How many plugins are in a state. */
  PLUGIN_STATE_COUNT("warp.command.plugin-state.count", Kind.PART, "count", "state"),

  // ---------------------------------------------------------------------------
  // /glist
  // ---------------------------------------------------------------------------

  /** The number of players online. */
  GLIST_TOTAL("warp.command.glist.total", Kind.CHAT, "count", "n"),
  /** A server and its number of players. */
  GLIST_SERVER("warp.command.glist.server", Kind.CHAT, "server", "count"),
  /** The hover text of a server: a link to its players. */
  GLIST_SERVER_HOVER("warp.command.glist.server.hover", Kind.PART, "server"),
  /** The servers nobody is on. */
  GLIST_EMPTY("warp.command.glist.empty", Kind.CHAT, "servers"),
  /** The players not on a server yet. */
  GLIST_CONNECTING("warp.command.glist.connecting", Kind.CHAT, "count"),
  /** The header of a server's players. */
  GLIST_HEADER("warp.command.glist.header", Kind.CHAT, "server", "count", "n"),
  /** The header of every server's players. */
  GLIST_HEADER_ALL("warp.command.glist.header-all", Kind.CHAT, "count", "n"),
  /** A server's players. */
  GLIST_GROUP("warp.command.glist.group", Kind.CHAT, "server", "names"),
  /** Nobody on a server. */
  GLIST_NOBODY("warp.command.glist.nobody", Kind.CHAT, "server"),
  /** Nobody online. */
  GLIST_NOBODY_ALL("warp.command.glist.nobody-all", Kind.CHAT),

  // ---------------------------------------------------------------------------
  // /send
  // ---------------------------------------------------------------------------

  /** A name that is neither a player nor a server. */
  SEND_UNKNOWN_TARGET("warp.command.send.unknown-target", Kind.CHAT, "target"),
  /** A name that is both a player and a server. */
  SEND_AMBIGUOUS("warp.command.send.ambiguous", Kind.CHAT, "target"),
  /** {@code current} from a source on no server. */
  SEND_NO_CURRENT("warp.command.send.no-current", Kind.CHAT),
  /** Nobody to send. */
  SEND_NOBODY("warp.command.send.nobody", Kind.CHAT, "target"),
  /** Everyone is already on the destination. */
  SEND_ALREADY_THERE("warp.command.send.already-there", Kind.CHAT, "count", "n", "server"),
  /** The destination does not answer: nobody moves. */
  SEND_UNREACHABLE("warp.command.send.unreachable", Kind.CHAT, "server", "error"),
  /** Several players are being sent. */
  SEND_STARTED("warp.command.send.started", Kind.CHAT, "count", "target", "server"),
  /** One player was sent. */
  SEND_ONE_DONE("warp.command.send.one-done", Kind.CHAT, "player", "server"),
  /** One player could not be sent. */
  SEND_ONE_FAILED("warp.command.send.one-failed", Kind.CHAT, "player", "server", "reason"),
  /** How many players were sent, and why the others were not. */
  SEND_SUMMARY(
      "warp.command.send.summary", Kind.CHAT, "sent", "count", "target", "server", "failures"),
  /** The players not sent, in the summary. */
  SEND_FAILURES("warp.command.send.failures", Kind.PART, "groups"),
  /** Players not sent for one reason. */
  SEND_FAILURE_GROUP("warp.command.send.failure-group", Kind.PART, "count", "reason"),
  /** Names, and how many more there are. */
  SEND_MORE("warp.command.send.more", Kind.PART, "names", "count"),
  /** The players of every server. */
  SEND_TARGET_ALL("warp.command.send.target.all", Kind.PART),
  /** The players of a server. */
  SEND_TARGET_SERVER("warp.command.send.target.server", Kind.PART, "server"),
  /** Not sent: already on the destination. */
  SEND_REASON_ALREADY("warp.command.send.reason.already", Kind.PART),
  /** Not sent: switching servers already. */
  SEND_REASON_SWITCHING("warp.command.send.reason.switching", Kind.PART),
  /** Not sent: a plugin denied it. */
  SEND_REASON_DENIED("warp.command.send.reason.denied", Kind.PART),
  /** Not sent: the destination refused the player. */
  SEND_REASON_REFUSED("warp.command.send.reason.refused", Kind.PART, "reason"),
  /** Not sent: the destination could not be reached. */
  SEND_REASON_UNREACHABLE("warp.command.send.reason.unreachable", Kind.PART),
  /** Not sent: the player left. */
  SEND_REASON_LEFT("warp.command.send.reason.left", Kind.PART),
  /** Not sent: no answer in time. */
  SEND_REASON_TIMEOUT("warp.command.send.reason.timeout", Kind.PART, "seconds"),
  /** Not sent: an error. */
  SEND_REASON_ERROR("warp.command.send.reason.error", Kind.PART, "error"),

  // ---------------------------------------------------------------------------
  // /shutdown
  // ---------------------------------------------------------------------------

  /** What players are told of a shutdown with a reason, in chat and when disconnected. */
  SHUTDOWN_REASON("warp.command.shutdown.reason", Kind.DISCONNECT, "reason"),
  /** What the sender is told will happen. */
  SHUTDOWN_STARTED("warp.command.shutdown.started", Kind.CHAT, "count", "n", "plan", "timeout"),
  /** The players are transferred. */
  SHUTDOWN_PLAN_TRANSFER(
      "warp.command.shutdown.plan.transfer", Kind.PART, "target", "delay", "fallback"),
  /** The players are kicked. */
  SHUTDOWN_PLAN_KICK("warp.command.shutdown.plan.kick", Kind.PART, "delay"),
  /** The players are kept. */
  SHUTDOWN_PLAN_KEEP("warp.command.shutdown.plan.keep", Kind.PART),
  /** Clients too old for transfers are kicked. */
  SHUTDOWN_FALLBACK_KICK("warp.command.shutdown.fallback.kick", Kind.PART),
  /** Clients too old for transfers are kept. */
  SHUTDOWN_FALLBACK_KEEP("warp.command.shutdown.fallback.keep", Kind.PART),
  /** A shutdown already runs. */
  SHUTDOWN_ALREADY(
      "warp.command.shutdown.already", Kind.CHAT, "elapsed", "phase", "trigger", "players", "n"),
  /** The shutdown could not start. */
  SHUTDOWN_REFUSED("warp.command.shutdown.refused", Kind.CHAT, "error"),

  // ---------------------------------------------------------------------------
  // /find
  // ---------------------------------------------------------------------------

  /** Where a player is. */
  FIND_ON("warp.command.find.on", Kind.CHAT, "player", "server", "ping", "version", "address"),
  /** A player not on a server yet. */
  FIND_CONNECTING("warp.command.find.connecting", Kind.CHAT, "player", "version", "address"),
  /** A player's address, for sources allowed to see it. */
  FIND_ADDRESS("warp.command.find.address", Kind.PART, "address"),
  /** The hover text of a player's server: a link to its players. */
  FIND_SERVER_HOVER("warp.command.find.server.hover", Kind.PART, "server"),

  // ---------------------------------------------------------------------------
  // /alert
  // ---------------------------------------------------------------------------

  /** The alert every player gets. */
  ALERT("warp.command.alert", Kind.CHAT, "message"),
  /** What the sender is told. */
  ALERT_SENT("warp.command.alert.sent", Kind.CHAT, "count", "n");

  /** How a message reaches the player, which decides what an override may be. */
  public enum Kind {
    /** The reason on the disconnect screen: never empty. */
    DISCONNECT,
    /**
     * A chat line: gray where its text sets no color, never italic; an empty override means the
     * line is not sent.
     */
    CHAT,
    /**
     * A piece of another message, such as a hover text or a reason inside a summary: it takes the
     * style around it.
     */
    PART
  }

  private final String key;
  private final Kind kind;

  @SuppressWarnings("ImmutableEnumChecker") // an enum of protocol versions: never changes
  private final @Nullable ProtocolVersion vanillaSince;

  @SuppressWarnings("ImmutableEnumChecker") // List.of: unmodifiable
  private final List<String> placeholders;

  Message(String key, Kind kind, ProtocolVersion vanillaSince) {
    this.key = key;
    this.kind = kind;
    this.vanillaSince = vanillaSince;
    this.placeholders = List.of();
  }

  Message(String key, Kind kind, String... placeholders) {
    this.key = key;
    this.kind = kind;
    this.vanillaSince = null;
    this.placeholders = List.of(placeholders);
  }

  /**
   * Returns the translation key.
   *
   * @return the key, such as {@code warp.login.lockdown}
   */
  public String key() {
    return key;
  }

  /**
   * Returns how the message reaches the player.
   *
   * @return its kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the first version whose client translates the key itself, for a vanilla key.
   *
   * @return the version, or {@code null} for one of Warp's own keys, which Warp always renders
   */
  public @Nullable ProtocolVersion vanillaSince() {
    return vanillaSince;
  }

  /**
   * Returns the names of the message's placeholders, in the order {@link #of} takes them.
   *
   * @return the names, empty for a message without any
   */
  public List<String> placeholders() {
    return placeholders;
  }

  /**
   * Returns the message as a component to send to a player, rendered in its language when sent.
   *
   * @param values one value per {@linkplain #placeholders() placeholder}, in order: a string,
   *     inserted as typed (a player named {@code <red>} stays a name), a number, a component, or a
   *     tag resolver such as {@code Formatter.choice} for plurals
   * @return the translatable component
   * @throws IllegalArgumentException if the number of values is not the number of placeholders
   */
  public TranslatableComponent of(Object... values) {
    if (values.length != placeholders.size()) {
      throw new IllegalArgumentException(
          key + " takes " + placeholders + ", got " + values.length + " values");
    }
    ComponentLike[] arguments = new ComponentLike[values.length];
    for (int i = 0; i < values.length; i++) {
      String name = placeholders.get(i);
      arguments[i] =
          switch (values[i]) {
            case TagResolver resolver -> Argument.tagResolver(resolver);
            case ComponentLike component -> Argument.component(name, component);
            case Number number -> Argument.numeric(name, number);
            case Object other -> Argument.string(name, other.toString());
          };
    }
    return styled(Component.translatable(key, arguments));
  }

  /**
   * Returns the message as a component to send to a player, with named placeholders: the way the
   * proxy's commands build theirs, with {@link #text}, {@link #component}, {@link #number} and
   * {@link #count}.
   *
   * @param values the placeholders, in any order
   * @return the translatable component, rendered in the player's language when sent
   * @throws IllegalArgumentException if a {@linkplain #placeholders() placeholder} is missing
   */
  public TranslatableComponent with(TagResolver... values) {
    TagResolver resolver = TagResolver.resolver(values);
    for (String name : placeholders) {
      if (!resolver.has(name)) {
        throw new IllegalArgumentException(
            key + " takes " + placeholders + ", <" + name + "> is missing");
      }
    }
    return styled(Component.translatable(key, Argument.tagResolver(resolver)));
  }

  /** A chat line is gray where its text sets no color, and never italic (as item lore would be). */
  private TranslatableComponent styled(TranslatableComponent message) {
    return kind == Kind.CHAT
        ? message
            .colorIfAbsent(NamedTextColor.GRAY)
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        : message;
  }

  // ---------------------------------------------------------------------------
  // Placeholders
  // ---------------------------------------------------------------------------

  /**
   * A placeholder whose text is inserted as typed, never parsed: a player named {@code <red>} stays
   * a name.
   *
   * @param name the placeholder's name
   * @param text the text
   * @return the placeholder
   */
  public static TagResolver text(String name, String text) {
    return Placeholder.unparsed(name, text);
  }

  /**
   * A placeholder for a component, which may hold messages itself.
   *
   * @param name the placeholder's name
   * @param component the component
   * @return the placeholder
   */
  public static TagResolver component(String name, ComponentLike component) {
    return Placeholder.component(name, component);
  }

  /**
   * A number, grouped by thousands the way the player's language writes it: {@code 12,345} in
   * English, {@code 12 345} in French.
   *
   * @param name the placeholder's name
   * @param number the number
   * @return the placeholder
   */
  public static TagResolver number(String name, long number) {
    return TagResolver.resolver(
        name,
        (arguments, context) ->
            Tag.selfClosingInserting(Component.text(PlayerMessages.formatNumber(number))));
  }

  /**
   * A count: {@code <count>}, a {@linkplain #number number}, and {@code <n>} to choose the words
   * that agree with it ({@code <n:'0#players|1#player|2#players'>}).
   *
   * @param count the count
   * @return the placeholders
   */
  public static TagResolver count(long count) {
    return TagResolver.resolver(number("count", count), Formatter.choice("n", count));
  }

  /**
   * Returns the message whose key is {@code key}.
   *
   * @param key a translation key
   * @return the message, or {@code null} if no message has that key
   */
  public static @Nullable Message byKey(String key) {
    return BY_KEY.get(key);
  }

  /**
   * Returns the keys of every message.
   *
   * @return the keys, in the catalog's order
   */
  public static Set<String> keys() {
    return BY_KEY.keySet();
  }

  private static final Map<String, Message> BY_KEY;

  /** The newest version that brought a vanilla key Warp uses: clients from it know them all. */
  static final ProtocolVersion NEWEST_VANILLA;

  static {
    Map<String, Message> byKey = new LinkedHashMap<>();
    ProtocolVersion newest = ProtocolVersion.oldest();
    for (Message message : values()) {
      byKey.put(message.key, message);
      ProtocolVersion since = message.vanillaSince;
      if (since != null && newest.isOlderThan(since)) {
        newest = since;
      }
    }
    BY_KEY = Collections.unmodifiableMap(byKey);
    NEWEST_VANILLA = newest;
  }
}
