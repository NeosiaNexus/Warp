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
package dev.warp.proxy.logging;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * The stable code of each warning and error Warp writes for its operator, such as {@code
 * W-CFG-001}: the console shows it dimmed at the end of the line, as a link to its entry in {@code
 * docs/errors.md} (cause and fix), and the log file at the end of the message.
 *
 * <p>A code never changes meaning and is never reused: a warning that goes away retires its code.
 * Codes are passed as the SLF4J marker of the record, its first argument:
 *
 * <pre>{@code logger.warn(LogCode.PORT_IN_USE.marker(), "Cannot listen on {}", address);}</pre>
 *
 * <p>{@code docs/errors.md} is generated from this enum ({@code LogCodeTest} fails when it is out
 * of date, when a code is unused, and when a warning or error of the proxy has no code).
 */
public enum LogCode {
  // ---------------------------------------------------------------------------
  // Configuration
  // ---------------------------------------------------------------------------

  /** The configuration has problems. */
  CONFIG_INVALID(
      "W-CFG-001",
      true,
      "The configuration cannot be read",
      "warp.conf (or a variable such as WARP_LOG_FORMAT) has invalid values, unknown keys or a"
          + " syntax error. The message lists every problem with its file and line.",
      "Fix each problem listed, then start Warp again. To start from scratch, move warp.conf away:"
          + " Warp writes a fresh one with every setting explained."),
  /** The log format of warp.conf could not be read before logging started. */
  LOG_FORMAT_UNREAD(
      "W-CFG-002",
      false,
      "The log format differs from logging.format",
      "Warp picks the log format before it reads warp.conf in full, so that every line has the same"
          + " format. That early read failed (an invalid file), so the console uses the default.",
      "Fix warp.conf, or set the WARP_LOG_FORMAT variable, which Warp reads first."),
  /** The MOTD uses legacy colour codes. */
  MOTD_LEGACY_COLORS(
      "W-CFG-003",
      false,
      "status.motd uses legacy colour codes",
      "The MOTD contains codes such as &a, which MiniMessage shows as typed.",
      "Use MiniMessage tags instead, such as <green> or <gradient:gold:red>."),
  /** The MOTD has more than two lines. */
  MOTD_TOO_MANY_LINES(
      "W-CFG-004",
      false,
      "status.motd has more than two lines",
      "Clients show the first two lines of a MOTD only.",
      "Keep the MOTD to two lines."),
  /** The PROXY protocol trusts every address. */
  TRUSTED_PROXIES_EVERYONE(
      "W-CFG-005",
      false,
      "proxy-protocol.trusted-proxies trusts everyone",
      "A range such as 0.0.0.0/0 lets anyone who reaches Warp's port directly send a PROXY header"
          + " and choose the address Warp sees, defeating bans and rate limits.",
      "List only the addresses of your load balancers, and firewall Warp's port so that only they"
          + " reach it."),
  /** A new forwarding secret was generated. */
  FORWARDING_SECRET_GENERATED(
      "W-CFG-006",
      false,
      "A new forwarding secret was generated",
      "Modern forwarding needs a secret shared with every backend, and its file did not exist.",
      "Give the secret to every backend (Paper: proxies.velocity.secret in"
          + " config/paper-global.yml) and restart them, or players cannot join."),
  /** TCP Fast Open is on where it cannot work. */
  TCP_FAST_OPEN_UNSUPPORTED(
      "W-CFG-007",
      false,
      "tcp-fast-open is on without epoll",
      "TCP Fast Open needs the epoll transport, which only Linux has; the setting is ignored here.",
      "Set tcp-fast-open = false, or run Warp on Linux."),
  /** A console variable has an invalid value. */
  CONSOLE_SETTING_INVALID(
      "W-CFG-008",
      false,
      "A console setting is invalid",
      "WARP_COLOR, WARP_CONSOLE, WARP_THEME, WARP_HYPERLINKS or console.theme has a value Warp does"
          + " not know; it uses auto instead.",
      "Use one of the values the message lists, or remove the setting."),

  // ---------------------------------------------------------------------------
  // Startup
  // ---------------------------------------------------------------------------

  /** The listening address does not resolve. */
  LISTEN_ADDRESS_UNRESOLVED(
      "W-START-001",
      true,
      "The listening address does not resolve",
      "bind (or management.bind) names a host that DNS does not know.",
      "Use an IP address of this machine, or 0.0.0.0 to listen on every address."),
  /** The port is taken. */
  PORT_IN_USE(
      "W-START-002",
      true,
      "The port is already in use",
      "Another program listens on the port: often another Warp, or a Minecraft server on the same"
          + " machine.",
      "Stop that program, or choose another port in bind (or management.bind). On Linux, ss -ltnp"
          + " shows who holds a port."),
  /** The port needs privileges. */
  PORT_PERMISSION_DENIED(
      "W-START-003",
      true,
      "Listening on the port is not permitted",
      "Ports below 1024 need extra rights, and some systems reserve other ranges.",
      "Use a port from 1024 up (25565 is Minecraft's), and forward the low port to it with your"
          + " firewall if players must use it."),
  /** The address is not one of this machine's. */
  ADDRESS_NOT_LOCAL(
      "W-START-004",
      true,
      "The listening address is not this machine's",
      "bind names an IP address that no network interface of this machine has: often a public"
          + " address behind NAT, or a container's address.",
      "Use one of the machine's own addresses, or 0.0.0.0 to listen on all of them."),
  /** The port cannot be opened for another reason. */
  CANNOT_LISTEN(
      "W-START-005",
      true,
      "Warp cannot listen on its address",
      "Opening the listening socket failed for a reason the message gives.",
      "Check bind in warp.conf and the system's network settings."),
  /** The plugins directory cannot be used. */
  PLUGINS_DIRECTORY_UNREADABLE(
      "W-START-006",
      true,
      "The plugins directory cannot be read",
      "plugins is a file, or the user running Warp may not read or create it.",
      "Move the file away, or give the user running Warp read and write access to the directory."),
  /** Starting failed unexpectedly. */
  START_FAILED(
      "W-START-007",
      true,
      "Warp failed to start",
      "An unexpected error stopped the startup; the stack trace says where.",
      "Report it with the log file at https://github.com/NeosiaNexus/Warp/issues."),

  // ---------------------------------------------------------------------------
  // The JVM and the process
  // ---------------------------------------------------------------------------

  /** A thread ended with an exception nobody caught. */
  UNCAUGHT_EXCEPTION(
      "W-SYS-001",
      true,
      "Uncaught exception",
      "A thread ended with an exception that no code handled: a bug, in Warp or in a plugin (the"
          + " stack trace names the classes).",
      "Report it to the project the top frames belong to, with the log file."),
  /** Handling SIGTERM failed. */
  SIGTERM_HANDLER_FAILED(
      "W-SYS-002",
      true,
      "Handling SIGTERM failed",
      "Warp could not start the graceful stop it runs on SIGTERM (drain.on-sigterm).",
      "Report it with the log file; meanwhile stop Warp with /shutdown or POST /drain."),
  /** A shutdown did not finish in time. */
  SHUTDOWN_STUCK(
      "W-SYS-003",
      false,
      "The shutdown did not finish in time",
      "Stopping took longer than Warp waits: a plugin or a connection did not let go.",
      "Look for the plugin or thread named in the lines before; a thread dump (jstack) shows what"
          + " held the shutdown."),

  // ---------------------------------------------------------------------------
  // Natives
  // ---------------------------------------------------------------------------

  /** Native compression works, with advice. */
  NATIVE_COMPRESSION_ADVICE(
      "W-NAT-001",
      false,
      "Native compression could run better",
      "libdeflate is loaded, but the message suggests a setting that makes it faster or safer.",
      "Apply the advice the message gives."),
  /** Native compression is unavailable. */
  NATIVE_COMPRESSION_UNAVAILABLE(
      "W-NAT-002",
      false,
      "Native compression is unavailable",
      "libdeflate could not load on this system (the message says why), so Warp compresses with"
          + " java.util.zip, which is slower.",
      "Follow the advice in the message (often --enable-native-access=ALL-UNNAMED, or a supported"
          + " platform). To keep java.util.zip quietly, start Warp with -Dwarp.natives.disable=true."),

  // ---------------------------------------------------------------------------
  // Plugins
  // ---------------------------------------------------------------------------

  /** The plugins directory cannot be listed. */
  PLUGINS_DIRECTORY_UNLISTABLE(
      "W-PLG-001",
      true,
      "The plugins directory cannot be listed",
      "Reading the plugins directory failed; no plugin is loaded.",
      "Give the user running Warp read access to the directory and its jars."),
  /** Several jars declare the same plugin id. */
  PLUGIN_ID_DUPLICATE(
      "W-PLG-002",
      true,
      "Several jars declare the same plugin",
      "Two or more jars in plugins declare the same id, often an old and a new version side by"
          + " side; none of them is loaded.",
      "Keep one jar per plugin."),
  /** A jar is not a Warp plugin. */
  PLUGIN_NOT_A_PLUGIN(
      "W-PLG-003",
      true,
      "A jar is not a Warp plugin",
      "A jar in plugins has no warp-plugin.json: a plugin for another proxy or server, or a"
          + " library.",
      "Remove it, or use the plugin's Warp build. Libraries belong inside the plugin jar."),
  /** A plugin jar cannot be read. */
  PLUGIN_JAR_UNREADABLE(
      "W-PLG-004",
      true,
      "A plugin jar cannot be read",
      "The jar is damaged (an interrupted download) or not readable by the user running Warp.",
      "Download the plugin again, and check the file's permissions."),
  /** A plugin descriptor is invalid. */
  PLUGIN_DESCRIPTOR_INVALID(
      "W-PLG-005",
      true,
      "A plugin descriptor is invalid",
      "warp-plugin.json in the jar is not valid JSON, or lacks a required field.",
      "Report it to the plugin's author; the message names the problem."),
  /** A plugin cannot load. */
  PLUGIN_CANNOT_LOAD(
      "W-PLG-006",
      true,
      "A plugin cannot load",
      "Something the plugin needs is missing: a dependency that is absent or failed, a newer Warp"
          + " API, or a dependency cycle (the message says which).",
      "Install or update what the message names."),
  /** A plugin's main class is missing. */
  PLUGIN_MAIN_CLASS_MISSING(
      "W-PLG-007",
      true,
      "A plugin's main class is missing",
      "warp-plugin.json names a main class the jar does not contain: a broken build.",
      "Report it to the plugin's author."),
  /** A plugin uses a class it cannot see. */
  PLUGIN_CLASS_NOT_VISIBLE(
      "W-PLG-008",
      true,
      "A plugin uses a class it cannot see",
      "A plugin sees the Warp API, Adventure, SLF4J, its own jar and the plugins it depends on."
          + " It uses a class from elsewhere: an unshaded library, or Warp's internals.",
      "Report it to the plugin's author: shade the library, or declare the dependency."),
  /** A plugin failed while loading. */
  PLUGIN_LOAD_FAILED(
      "W-PLG-009",
      true,
      "A plugin failed to load",
      "The plugin threw an exception while it was constructed or enabled.",
      "Report it to the plugin's author with the stack trace."),
  /** A plugin failed while disabling. */
  PLUGIN_DISABLE_FAILED(
      "W-PLG-010",
      true,
      "A plugin failed to disable cleanly",
      "The plugin threw an exception while it was disabled at shutdown.",
      "Report it to the plugin's author; Warp still stops."),
  /** A plugin's registration could not be undone. */
  PLUGIN_CLEANUP_FAILED(
      "W-PLG-011",
      false,
      "A plugin's registration could not be undone",
      "Removing something a plugin registered (a listener, a command, a channel) failed while the"
          + " plugin was unloaded.",
      "Report it with the stack trace; restart Warp if the plugin's feature still shows."),
  /** A permission provider failed. */
  PERMISSION_PROVIDER_FAILED(
      "W-PLG-012",
      true,
      "A permission provider failed",
      "The plugin that answers permission checks threw an exception; the check was denied.",
      "Report it to the plugin's author."),
  /** A plugin failed to set up a connection's pipeline. */
  PIPELINE_SETUP_FAILED(
      "W-PLG-013",
      true,
      "A plugin failed to set up a connection",
      "The plugin's pipeline handler threw while a connection was set up; the connection was"
          + " closed.",
      "Report it to the plugin's author."),
  /** A plugin changed handlers it does not own. */
  PIPELINE_TAMPERED(
      "W-PLG-014",
      true,
      "A plugin changed handlers it does not own",
      "A plugin may add handlers to a connection's pipeline, never remove, replace or move the"
          + " proxy's or another plugin's; the connection was closed.",
      "Report it to the plugin's author."),
  /** A plugin's log sink failed. */
  LOG_SINK_FAILED(
      "W-PLG-015",
      false,
      "A plugin's log sink failed",
      "The plugin's log sink threw on a record; later failures are counted.",
      "Report it to the plugin's author."),
  /** A plugin's log sink was removed. */
  LOG_SINK_REMOVED(
      "W-PLG-016",
      false,
      "A plugin's log sink was removed",
      "The plugin's log sink cannot run at all, so Warp stopped feeding it.",
      "Report it to the plugin's author."),
  /** A plugin's log sink lost records. */
  LOG_SINK_LOST_RECORDS(
      "W-PLG-017",
      false,
      "A plugin's log sink lost records",
      "The plugin's log sink did not keep up, and records were dropped from its queue (the log"
          + " file has them all).",
      "Make the sink faster, or report it to the plugin's author."),
  /** Shutdown handlers took too long. */
  SHUTDOWN_HANDLERS_SLOW(
      "W-PLG-018",
      false,
      "Shutdown handlers did not finish in time",
      "Plugins' ProxyShutdownEvent handlers ran longer than Warp waits; Warp stopped anyway.",
      "Find the slow plugin (it runs last in the log) and report it to its author."),
  /** A ping response changed after it was sent. */
  PING_CHANGED_LATE(
      "W-PLG-019",
      false,
      "A plugin changed a ping after the answer",
      "A ProxyPingEvent handler changed the response from a callback after Warp answered.",
      "Report it to the plugin's author: the handler must return the callback's stage."),
  /** A server list entry is too long. */
  SERVER_LIST_ENTRY_TOO_LONG(
      "W-PLG-020",
      false,
      "A server list entry is too long",
      "A plugin made the server list answer longer than a client reads, so Warp dropped a part.",
      "Shorten what the plugin puts in the server list (sample players, MOTD, favicon)."),

  // ---------------------------------------------------------------------------
  // Events and plugin callbacks
  // ---------------------------------------------------------------------------

  /** An event handler held an event too long. */
  EVENT_HANDLER_TIMED_OUT(
      "W-EVT-001",
      false,
      "An event handler held an event too long",
      "An asynchronous handler did not complete within its time-out, so the event went on without"
          + " it.",
      "Report it to the plugin's author; it can raise timeoutSeconds if it needs longer."),
  /** An event handler failed. */
  EVENT_HANDLER_FAILED(
      "W-EVT-002",
      true,
      "An event handler failed",
      "A plugin's event handler threw an exception; the other handlers still ran.",
      "Report it to the plugin's author with the stack trace."),
  /** An event handler was slow. */
  EVENT_HANDLER_SLOW(
      "W-EVT-003",
      false,
      "An event handler was slow",
      "A handler blocked for long, and the player's later events waited for it.",
      "Report it to the plugin's author: slow work belongs off the event."),
  /** Events were still running at shutdown. */
  EVENTS_STILL_RUNNING(
      "W-EVT-004",
      false,
      "Events were still running at shutdown",
      "Handlers were still running when the shutdown's grace period ended; they were interrupted.",
      "Find the slow plugin and report it to its author."),
  /** Plugins held a player too long. */
  PLAYER_HELD_TOO_LONG(
      "W-EVT-005",
      false,
      "Plugins held a player too long",
      "Plugins kept a player waiting (login, server choice) beyond their handlers' time-outs, so"
          + " the player was disconnected.",
      "Find the plugin that holds logins and report it to its author."),
  /** A plugin callback failed. */
  PLUGIN_CALLBACK_FAILED(
      "W-EVT-006",
      true,
      "A plugin callback failed",
      "A callback a plugin registered (such as a resource pack callback) threw an exception.",
      "Report it to the plugin's author with the stack trace."),

  // ---------------------------------------------------------------------------
  // Commands and the console
  // ---------------------------------------------------------------------------

  /** A command failed. */
  COMMAND_FAILED(
      "W-CMD-001",
      true,
      "A command failed",
      "A command threw an exception; its sender was told it failed.",
      "Report it to whoever owns the command (Warp, or the plugin) with the stack trace."),
  /** The interactive console could not start. */
  CONSOLE_PROMPT_FAILED(
      "W-CON-001",
      false,
      "The interactive console could not start",
      "The terminal could not be opened for the prompt (an unusual terminal, or a restricted"
          + " environment); the console reads plain lines instead.",
      "Nothing to do if plain lines suit you; WARP_CONSOLE=plain silences this."),
  /** The console stopped reading commands. */
  CONSOLE_INPUT_STOPPED(
      "W-CON-002",
      false,
      "The console stopped reading commands",
      "Reading standard input failed; the proxy runs on, without console commands.",
      "Use the management endpoint or an in-game account to run commands; restart to get the"
          + " console back."),

  // ---------------------------------------------------------------------------
  // Drain
  // ---------------------------------------------------------------------------

  /** The drain timed out. */
  DRAIN_TIMED_OUT(
      "W-DRAIN-001",
      false,
      "The drain timed out",
      "Players were still connected when drain.timeout ended; they were disconnected.",
      "Raise drain.timeout, or check why transfers did not happen (servers down, plugins)."),
  /** Plugins were slow to decide about a player. */
  DRAIN_DECISION_SLOW(
      "W-DRAIN-002",
      false,
      "Plugins were slow to decide about a player",
      "DrainEvent handlers did not decide where a player goes in time, so the default applied.",
      "Report it to the plugin's author."),
  /** Asking plugins about a player failed. */
  DRAIN_DECISION_FAILED(
      "W-DRAIN-003",
      true,
      "Asking plugins about a player failed",
      "Firing the drain's event for a player failed; the default applied.",
      "Report it with the stack trace."),
  /** A player could not be drained. */
  DRAIN_PLAYER_FAILED(
      "W-DRAIN-004",
      true,
      "A player could not be drained",
      "Moving or disconnecting a player during the drain failed; the drain went on.",
      "Report it with the stack trace."),
  /** A transfer was slow to write. */
  DRAIN_TRANSFER_SLOW(
      "W-DRAIN-005",
      false,
      "A transfer was slow to write",
      "The player's connection did not take the transfer packet in time (a slow or stalled"
          + " client); the drain moved on.",
      "Nothing to do for one player; many point at the network."),
  /** A drain step failed. */
  DRAIN_STEP_FAILED(
      "W-DRAIN-006",
      true,
      "A drain step failed",
      "A scheduled step of the drain threw an exception.",
      "Report it with the stack trace."),
  /** SIGTERM cannot be caught. */
  DRAIN_SIGTERM_UNSUPPORTED(
      "W-DRAIN-007",
      false,
      "SIGTERM cannot be caught",
      "drain.on-sigterm is on, but this JVM does not let Warp handle SIGTERM, so SIGTERM stops"
          + " the proxy at once.",
      "Run Warp on a standard JDK (Temurin, for instance), or drain with POST /drain before"
          + " stopping."),
  /** SIGTERM arrived during the drain. */
  DRAIN_SIGTERM_AGAIN(
      "W-DRAIN-008",
      false,
      "SIGTERM again during the drain",
      "A second SIGTERM stops the proxy at once, without finishing the drain: what orchestrators"
          + " send once their grace period is over.",
      "Raise the orchestrator's grace period (Kubernetes terminationGracePeriodSeconds) above"
          + " drain.timeout."),

  // ---------------------------------------------------------------------------
  // Anti-bot
  // ---------------------------------------------------------------------------

  /** The attack detector failed. */
  ANTIBOT_DETECTOR_FAILED(
      "W-BOT-001",
      true,
      "The attack detector failed",
      "The anti-bot's periodic check threw an exception.",
      "Report it with the stack trace."),
  /** A plugin's anti-bot check failed. */
  ANTIBOT_PLUGIN_CHECK_FAILED(
      "W-BOT-002",
      false,
      "A plugin's anti-bot check failed",
      "A plugin's check threw an exception; the login went on as if it had passed.",
      "Report it to the plugin's author."),
  /** Lockdown is on. */
  ANTIBOT_LOCKDOWN(
      "W-BOT-003",
      false,
      "Lockdown is on",
      "Only players who have played here before may log in, set by /warp antibot lockdown, the"
          + " management endpoint or the anti-bot itself.",
      "Turn it off with /warp antibot lockdown off once the attack is over."),
  /** A bot attack is under way. */
  ANTIBOT_ATTACK(
      "W-BOT-004",
      false,
      "Bot attack detected",
      "Connections or logins exceeded anti-bot.attack thresholds. Warp stops learning new"
          + " identities from joins and summarises the attack periodically.",
      "Usually nothing: the anti-bot copes. To let only known players in, /warp antibot"
          + " lockdown on."),
  /** Saving trusted identities on shutdown failed. */
  ANTIBOT_SAVE_ON_SHUTDOWN_FAILED(
      "W-BOT-005",
      false,
      "Trusted identities were not saved on shutdown",
      "Writing the anti-bot's store failed while Warp stopped; the changes since the last save"
          + " are lost.",
      "Check free disk space and the permissions of the anti-bot's directory."),
  /** Saving trusted identities took too long. */
  ANTIBOT_SAVE_SLOW(
      "W-BOT-006",
      false,
      "Saving trusted identities took too long",
      "The anti-bot's store did not finish writing within 10 s of the shutdown.",
      "Check the disk: a slow or full disk delays writes."),
  /** The anti-bot store's periodic work failed. */
  ANTIBOT_STORE_FAILED(
      "W-BOT-007",
      true,
      "The anti-bot store's periodic work failed",
      "Compacting or expiring the trusted identities threw an exception.",
      "Report it with the stack trace."),
  /** Trusted identities cannot be saved. */
  ANTIBOT_DISK_UNWRITABLE(
      "W-BOT-008",
      false,
      "Trusted identities cannot be saved",
      "Writing the anti-bot's store failed; identities stay in memory until it works again.",
      "Check free disk space and the permissions of the anti-bot's directory."),
  /** The anti-bot snapshot is corrupt. */
  ANTIBOT_SNAPSHOT_CORRUPT(
      "W-BOT-009",
      false,
      "The anti-bot snapshot is corrupt",
      "The snapshot fails its checksum (a crash or a disk fault); it was set aside and the journal"
          + " replayed alone.",
      "Nothing to do; keep the set-aside file if you want to investigate."),
  /** The anti-bot journal was cut short. */
  ANTIBOT_JOURNAL_TRUNCATED(
      "W-BOT-010",
      false,
      "The anti-bot journal was cut short",
      "The journal ended mid-write (a crash); the incomplete change was dropped.",
      "Nothing to do."),

  // ---------------------------------------------------------------------------
  // Logging and management
  // ---------------------------------------------------------------------------

  /** Log sinks cannot receive anything. */
  LOG_SINKS_UNAVAILABLE(
      "W-LOG-001",
      false,
      "Log sinks receive nothing",
      "The logging backend is not Log4j Core (another SLF4J binding is first on the class path),"
          + " so plugins' log sinks get no records.",
      "Run Warp from its own jar, without another logging backend."),
  /** A management request was refused. */
  MANAGEMENT_UNAUTHORIZED(
      "W-MGMT-001",
      false,
      "A management request was refused",
      "A POST to the management endpoint had no bearer token, or the wrong one.",
      "Send Authorization: Bearer <management.token>; if you did not, someone else is trying."),

  // ---------------------------------------------------------------------------
  // BungeeCord messaging channel
  // ---------------------------------------------------------------------------

  /** A sub-channel is not allowed. */
  BUNGEE_SUBCHANNEL_DENIED(
      "W-MSG-001",
      false,
      "A BungeeCord sub-channel is not allowed",
      "A server used a sub-channel its bungeecord-channel.allow rule does not allow.",
      "Allow the sub-channel for that server if it needs it."),
  /** A server asked to move a player to an unknown server. */
  BUNGEE_CONNECT_UNKNOWN_SERVER(
      "W-MSG-002",
      false,
      "BungeeCord Connect to an unknown server",
      "A server asked to move a player to a server that is not registered.",
      "Register the server in warp.conf, or fix the name in the backend's plugin."),
  /** A server forwarded to an unknown server. */
  BUNGEE_FORWARD_UNKNOWN_SERVER(
      "W-MSG-003",
      false,
      "BungeeCord Forward to an unknown server",
      "A server forwarded a message to a server that is not registered.",
      "Register the server in warp.conf, or fix the name in the backend's plugin."),
  /** Forwarded messages were dropped. */
  BUNGEE_FORWARD_DROPPED(
      "W-MSG-004",
      false,
      "BungeeCord Forward messages were dropped",
      "Messages waited for a player on the target server that never came, or too many waited.",
      "Check that the target server has players when the plugin forwards to it."),
  /** A BungeeCord message is too large. */
  BUNGEE_MESSAGE_TOO_LARGE(
      "W-MSG-005",
      false,
      "A BungeeCord message is too large",
      "The message is larger than a backend accepts from a client: forwarding it would disconnect"
          + " the player carrying it.",
      "Send smaller messages from the backend's plugin."),
  /** A BungeeCord message is malformed. */
  BUNGEE_MESSAGE_MALFORMED(
      "W-MSG-006",
      false,
      "A malformed BungeeCord message",
      "A server sent a message that does not follow the BungeeCord channel's format.",
      "Report it to the author of the backend's plugin."),

  // ---------------------------------------------------------------------------
  // Backends
  // ---------------------------------------------------------------------------

  /** A backend runs in online mode. */
  BACKEND_ONLINE_MODE(
      "W-BACK-001",
      false,
      "A backend runs in online mode",
      "The backend asked for encryption: it authenticates players itself, which only works"
          + " without a proxy.",
      "Set online-mode=false in the backend's server.properties and enable forwarding (Paper:"
          + " proxies.velocity in config/paper-global.yml)."),
  /** A backend compresses from another threshold. */
  BACKEND_COMPRESSION_MISMATCH(
      "W-BACK-002",
      false,
      "A backend's compression threshold differs",
      "The backend compresses from another size than players do, so packets between the two"
          + " thresholds are re-encoded instead of forwarded as they are.",
      "Set the backend's network-compression-threshold to the value the message gives."),
  /** A backend does not compress. */
  BACKEND_COMPRESSION_OFF(
      "W-BACK-003",
      false,
      "A backend has compression off",
      "The backend sends uncompressed packets, so Warp compresses its traffic for every player.",
      "Set network-compression-threshold on the backend to the value the message gives."),
  /** A backend sent an unexpected packet. */
  BACKEND_UNEXPECTED_PACKET(
      "W-BACK-004",
      false,
      "A backend sent an unexpected packet",
      "The backend sent a packet that does not belong where the connection stands; it was"
          + " ignored.",
      "Report it with the backend's software and version if it repeats."),
  /** The client left while the backend switched to configuration. */
  BACKEND_CLIENT_GONE(
      "W-BACK-005",
      false,
      "The player left during a server switch",
      "The player's connection closed while Warp set up the backend's configuration phase.",
      "Nothing to do for one player."),
  /** A player could not be connected to a server. */
  BACKEND_CONNECT_FAILED(
      "W-BACK-006",
      false,
      "A player could not join a server",
      "Connecting to the backend failed: it is down, refused the player, or timed out (the"
          + " message says which).",
      "Check that the server runs and is reachable from Warp at its address in warp.conf."),
  /** No server could take a player. */
  NO_SERVER_AVAILABLE(
      "W-BACK-007",
      false,
      "No server available for a player",
      "Every server of the try list and of the fallbacks is down or refused the player.",
      "Start the servers, or check the try list and fallback-order in warp.conf."),
  /** A server switch timed out. */
  SERVER_SWITCH_TIMED_OUT(
      "W-BACK-008",
      false,
      "A server switch timed out",
      "The target server did not finish the player's join in time; the player stays where they"
          + " were.",
      "Check the target server's load and the network between it and Warp."),
  /** A held packet was dropped. */
  HELD_PACKET_DROPPED(
      "W-BACK-009",
      false,
      "A held packet was dropped",
      "A packet waiting for a protocol phase that the connection never reached was dropped.",
      "Report it with the client's and the backend's versions if it repeats."),

  // ---------------------------------------------------------------------------
  // Authentication and login
  // ---------------------------------------------------------------------------

  /** A custom session server authenticates players. */
  SESSION_SERVER_CUSTOM(
      "W-AUTH-001",
      false,
      "Players authenticate against a custom server",
      "A system property points authentication at another session server than Mojang's.",
      "Expected with a custom authentication server; remove the property otherwise."),
  /** A custom key checks profile keys. */
  PROFILE_KEY_CUSTOM(
      "W-AUTH-002",
      false,
      "Profile keys are checked against a custom key",
      "A system property replaces Mojang's key for the profile keys of 1.19 to 1.19.2 players.",
      "Expected with a custom authentication server; remove the property otherwise."),
  /** Encryption could not be enabled. */
  ENCRYPTION_FAILED(
      "W-AUTH-003",
      true,
      "Encryption could not be enabled",
      "Setting up a player's encryption failed; the player was disconnected with the reference"
          + " the message gives.",
      "Report it with the reference and the log file."),
  /** The session server did not authenticate a player. */
  SESSION_SERVER_FAILED(
      "W-AUTH-004",
      false,
      "The session server failed",
      "Mojang's session server (or the custom one) did not answer, or answered with an error.",
      "Usually a Mojang outage: check its status. Persistent failures point at DNS or a"
          + " firewall blocking sessionserver.mojang.com."),
  /** A login was refused. */
  LOGIN_REJECTED(
      "W-AUTH-005",
      false,
      "A login was refused",
      "A player was disconnected during login for the reason the message gives, with a reference"
          + " the player also sees.",
      "Match the reference the player reports with this line."),

  // ---------------------------------------------------------------------------
  // Connections
  // ---------------------------------------------------------------------------

  /** A connection's pipeline threw. */
  PIPELINE_EXCEPTION(
      "W-NET-001",
      true,
      "A connection failed",
      "An unexpected exception in a connection's pipeline closed it.",
      "Report it with the stack trace if it repeats."),
  /** A trusted proxy's connection was refused. */
  TRUSTED_PROXY_REFUSED(
      "W-NET-002",
      false,
      "A trusted proxy's connection was refused",
      "A load balancer sent no PROXY header, or an invalid one.",
      "Enable the PROXY protocol (v1 or v2) on the load balancer, towards Warp."),
  /** A PROXY protocol connection from an untrusted address. */
  PROXY_PROTOCOL_UNTRUSTED(
      "W-NET-003",
      false,
      "A connection from an untrusted address",
      "The PROXY protocol is on, and the address is not in proxy-protocol.trusted-proxies.",
      "Add your load balancers' addresses to proxy-protocol.trusted-proxies; firewall the port"
          + " from everyone else."),
  /** A client sent an unexpected packet. */
  CLIENT_UNEXPECTED_PACKET(
      "W-NET-004",
      false,
      "A client sent an unexpected packet",
      "A client sent a packet that does not belong where its connection stands; it was ignored.",
      "Report it with the client's version and mods if it repeats."),
  /** A client answered a keep-alive Warp did not send. */
  KEEPALIVE_INVALID(
      "W-NET-005",
      false,
      "An invalid keep-alive answer",
      "A client answered a keep-alive with an id Warp did not send: a modified client, or a bug.",
      "Report it with the client's version and mods if it repeats."),
  /** A disconnect reason is too large. */
  DISCONNECT_REASON_TOO_LARGE(
      "W-NET-006",
      false,
      "A disconnect reason is too large",
      "A kick message exceeds what the client accepts, so a short reason was sent instead.",
      "Shorten the kick message (often a plugin's)."),
  /** A boss bar name is too large. */
  BOSS_BAR_NAME_TOO_LARGE(
      "W-NET-007",
      false,
      "A boss bar name is too large",
      "A boss bar's name exceeds what the client accepts, so it shows empty.",
      "Shorten the boss bar's name (often a plugin's)."),
  /** A cookie was not stored. */
  COOKIE_NOT_STORED(
      "W-NET-008",
      false,
      "A cookie was not stored",
      "Storing a cookie on a client failed (too large, or the client left).",
      "Report it to the plugin storing the cookie if it repeats."),
  /** Cookie requests went unanswered. */
  COOKIE_REQUESTS_UNANSWERED(
      "W-NET-009",
      false,
      "Cookie requests went unanswered",
      "A client did not answer cookie requests in time, so it was disconnected.",
      "Nothing to do for one client; many point at a modified client."),
  /** A transfer failed. */
  TRANSFER_FAILED(
      "W-NET-010",
      false,
      "A transfer failed",
      "Sending a player to another host (a 1.20.5+ transfer) failed.",
      "Check the target host and port, and that the client is 1.20.5 or newer."),
  /** A player's command list could not be refreshed. */
  COMMAND_LIST_REFRESH_FAILED(
      "W-NET-011",
      false,
      "A player's command list could not be refreshed",
      "Rebuilding the commands a player sees failed; the player keeps the previous list.",
      "Report it with the stack trace.");

  /** Where the codes are documented: each has an anchor named after it. */
  public static final String DOCUMENTATION =
      "https://github.com/NeosiaNexus/Warp/blob/main/docs/errors.md";

  private static final Map<String, LogCode> BY_ID = new HashMap<>();

  static {
    for (LogCode code : values()) {
      BY_ID.put(code.id, code);
    }
  }

  private final String id;
  private final boolean error;
  private final String title;
  private final String cause;
  private final String fix;

  LogCode(String id, boolean error, String title, String cause, String fix) {
    this.id = id;
    this.error = error;
    this.title = title;
    this.cause = cause;
    this.fix = fix;
  }

  /**
   * Returns the code of an id, such as {@code W-CFG-001}.
   *
   * @param id the id, possibly a marker's name
   * @return the code, or {@code null} when no code has this id
   */
  public static @Nullable LogCode byId(@Nullable String id) {
    return id == null ? null : BY_ID.get(id);
  }

  /**
   * Returns the code as shown.
   *
   * @return the id, such as {@code W-CFG-001}
   */
  public String id() {
    return id;
  }

  /**
   * Returns the marker that carries the code on a log record: the first argument of the call.
   *
   * @return the marker, named after the id
   */
  public Marker marker() {
    return MarkerFactory.getMarker(id);
  }

  /**
   * Returns whether the code is logged as an error.
   *
   * @return {@code true} for an error, {@code false} for a warning
   */
  public boolean error() {
    return error;
  }

  /**
   * Returns what happened, in a few words.
   *
   * @return the title of the entry
   */
  public String title() {
    return title;
  }

  /**
   * Returns why it happens.
   *
   * @return the cause, a sentence or two
   */
  public String cause() {
    return cause;
  }

  /**
   * Returns what to do about it.
   *
   * @return the fix, a sentence or two
   */
  public String fix() {
    return fix;
  }

  /**
   * Returns the address of the code's entry in the documentation.
   *
   * @return the URL, with the anchor of the entry
   */
  public String documentation() {
    return DOCUMENTATION + "#" + id.toLowerCase(Locale.ROOT);
  }
}
