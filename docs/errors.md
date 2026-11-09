# Warning and error codes

<!-- Generated from proxy/src/main/java/dev/warp/proxy/logging/LogCode.java: after changing a code, run
     WARP_UPDATE_DOCS=1 ./gradlew :proxy:test --tests '*LogCodeTest' -->

Every warning and error Warp writes for its operator ends with a code, such as `W-CFG-001`: dimmed at the end of the console line (a link to its entry here, on terminals that show links), in brackets at the end of the line in `logs/warp.log`, and as `marker` in the JSON format. Search this page for the code to learn why it happened and what to do.

A code keeps its meaning for good: a warning that goes away retires its code, which is never given to another.

| Code | Level | What happened |
|---|---|---|
| [W-CFG-001](#w-cfg-001) | error | The configuration cannot be read |
| [W-CFG-002](#w-cfg-002) | warning | The log format differs from logging.format |
| [W-CFG-003](#w-cfg-003) | warning | status.motd uses legacy colour codes |
| [W-CFG-004](#w-cfg-004) | warning | status.motd has more than two lines |
| [W-CFG-005](#w-cfg-005) | warning | proxy-protocol.trusted-proxies trusts everyone |
| [W-CFG-006](#w-cfg-006) | warning | A new forwarding secret was generated |
| [W-CFG-007](#w-cfg-007) | warning | tcp-fast-open is on without epoll |
| [W-CFG-008](#w-cfg-008) | warning | A console setting is invalid |
| [W-CFG-009](#w-cfg-009) | warning | A route points to an unknown server |
| [W-START-001](#w-start-001) | error | The listening address does not resolve |
| [W-START-002](#w-start-002) | error | The port is already in use |
| [W-START-003](#w-start-003) | error | Listening on the port is not permitted |
| [W-START-004](#w-start-004) | error | The listening address is not this machine's |
| [W-START-005](#w-start-005) | error | Warp cannot listen on its address |
| [W-START-006](#w-start-006) | error | The plugins directory cannot be read |
| [W-START-007](#w-start-007) | error | Warp failed to start |
| [W-SYS-001](#w-sys-001) | error | Uncaught exception |
| [W-SYS-002](#w-sys-002) | error | Handling SIGTERM failed |
| [W-SYS-003](#w-sys-003) | warning | The shutdown did not finish in time |
| [W-NAT-001](#w-nat-001) | warning | Native compression could run better |
| [W-NAT-002](#w-nat-002) | warning | Native compression is unavailable |
| [W-PLG-001](#w-plg-001) | error | The plugins directory cannot be listed |
| [W-PLG-002](#w-plg-002) | error | Several jars declare the same plugin |
| [W-PLG-003](#w-plg-003) | error | A jar is not a Warp plugin |
| [W-PLG-004](#w-plg-004) | error | A plugin jar cannot be read |
| [W-PLG-005](#w-plg-005) | error | A plugin descriptor is invalid |
| [W-PLG-006](#w-plg-006) | error | A plugin cannot load |
| [W-PLG-007](#w-plg-007) | error | A plugin's main class is missing |
| [W-PLG-008](#w-plg-008) | error | A plugin uses a class it cannot see |
| [W-PLG-009](#w-plg-009) | error | A plugin failed to load |
| [W-PLG-010](#w-plg-010) | error | A plugin failed to disable cleanly |
| [W-PLG-011](#w-plg-011) | warning | A plugin's registration could not be undone |
| [W-PLG-012](#w-plg-012) | error | A permission provider failed |
| [W-PLG-013](#w-plg-013) | error | A plugin failed to set up a connection |
| [W-PLG-014](#w-plg-014) | error | A plugin changed handlers it does not own |
| [W-PLG-015](#w-plg-015) | warning | A plugin's log sink failed |
| [W-PLG-016](#w-plg-016) | warning | A plugin's log sink was removed |
| [W-PLG-017](#w-plg-017) | warning | A plugin's log sink lost records |
| [W-PLG-018](#w-plg-018) | warning | Shutdown handlers did not finish in time |
| [W-PLG-019](#w-plg-019) | warning | A plugin changed a ping after the answer |
| [W-PLG-020](#w-plg-020) | warning | A server list entry is too long |
| [W-EVT-001](#w-evt-001) | warning | An event handler held an event too long |
| [W-EVT-002](#w-evt-002) | error | An event handler failed |
| [W-EVT-003](#w-evt-003) | warning | An event handler was slow |
| [W-EVT-004](#w-evt-004) | warning | Events were still running at shutdown |
| [W-EVT-005](#w-evt-005) | warning | Plugins held a player too long |
| [W-EVT-006](#w-evt-006) | error | A plugin callback failed |
| [W-CMD-001](#w-cmd-001) | error | A command failed |
| [W-CON-001](#w-con-001) | warning | The interactive console could not start |
| [W-CON-002](#w-con-002) | warning | The console stopped reading commands |
| [W-DRAIN-001](#w-drain-001) | warning | The drain timed out |
| [W-DRAIN-002](#w-drain-002) | warning | Plugins were slow to decide about a player |
| [W-DRAIN-003](#w-drain-003) | error | Asking plugins about a player failed |
| [W-DRAIN-004](#w-drain-004) | error | A player could not be drained |
| [W-DRAIN-005](#w-drain-005) | warning | A transfer was slow to write |
| [W-DRAIN-006](#w-drain-006) | error | A drain step failed |
| [W-DRAIN-007](#w-drain-007) | warning | SIGTERM cannot be caught |
| [W-DRAIN-008](#w-drain-008) | warning | SIGTERM again during the drain |
| [W-BOT-001](#w-bot-001) | error | The attack detector failed |
| [W-BOT-002](#w-bot-002) | warning | A plugin's anti-bot check failed |
| [W-BOT-003](#w-bot-003) | warning | Lockdown is on |
| [W-BOT-004](#w-bot-004) | warning | Bot attack detected |
| [W-BOT-005](#w-bot-005) | warning | Trusted identities were not saved on shutdown |
| [W-BOT-006](#w-bot-006) | warning | Saving trusted identities took too long |
| [W-BOT-007](#w-bot-007) | error | The anti-bot store's periodic work failed |
| [W-BOT-008](#w-bot-008) | warning | Trusted identities cannot be saved |
| [W-BOT-009](#w-bot-009) | warning | The anti-bot snapshot is corrupt |
| [W-BOT-010](#w-bot-010) | warning | The anti-bot journal was cut short |
| [W-BOT-011](#w-bot-011) | warning | The anti-bot token key cannot be rotated |
| [W-BOT-012](#w-bot-012) | warning | A verified player could not be transferred back |
| [W-LOG-001](#w-log-001) | warning | Log sinks receive nothing |
| [W-MGMT-001](#w-mgmt-001) | warning | A management request was refused |
| [W-MGMT-002](#w-mgmt-002) | warning | A renewed management certificate could not be read |
| [W-MGMT-003](#w-mgmt-003) | warning | GET /metrics is open to other machines |
| [W-MET-001](#w-met-001) | warning | A metric reached a cardinality cap |
| [W-MET-002](#w-met-002) | warning | A metrics collector failed |
| [W-MET-003](#w-met-003) | warning | GET /metrics could not be answered |
| [W-RCON-001](#w-rcon-001) | warning | The remote console streams nothing |
| [W-RCON-002](#w-rcon-002) | warning | A remote console token lacks a scope |
| [W-RCON-003](#w-rcon-003) | warning | A remote command arrived without TLS from an untrusted address |
| [W-RCON-004](#w-rcon-004) | warning | A wrong remote console token |
| [W-RCON-005](#w-rcon-005) | warning | An address was locked out of the remote console |
| [W-RCON-006](#w-rcon-006) | warning | A remote console token ran too many commands |
| [W-RCON-007](#w-rcon-007) | warning | The proxy runs no commands for this token |
| [W-RCON-008](#w-rcon-008) | warning | The remote console client cannot reach a proxy |
| [W-RCON-009](#w-rcon-009) | error | A proxy refused the remote console client's token |
| [W-RCON-010](#w-rcon-010) | warning | A proxy asked the remote console client to wait |
| [W-RCON-011](#w-rcon-011) | warning | A proxy answered the remote console client unexpectedly |
| [W-RCON-012](#w-rcon-012) | warning | The remote console client missed lines |
| [W-MSG-001](#w-msg-001) | warning | A BungeeCord sub-channel is not allowed |
| [W-MSG-002](#w-msg-002) | warning | BungeeCord Connect to an unknown server |
| [W-MSG-003](#w-msg-003) | warning | BungeeCord Forward to an unknown server |
| [W-MSG-004](#w-msg-004) | warning | BungeeCord Forward messages were dropped |
| [W-MSG-005](#w-msg-005) | warning | A BungeeCord message is too large |
| [W-MSG-006](#w-msg-006) | warning | A malformed BungeeCord message |
| [W-BACK-001](#w-back-001) | warning | A backend runs in online mode |
| [W-BACK-002](#w-back-002) | warning | A backend's compression threshold differs |
| [W-BACK-003](#w-back-003) | warning | A backend has compression off |
| [W-BACK-004](#w-back-004) | warning | A backend sent an unexpected packet |
| [W-BACK-005](#w-back-005) | warning | The player left during a server switch |
| [W-BACK-006](#w-back-006) | warning | A player could not join a server |
| [W-BACK-007](#w-back-007) | warning | No server available for a player |
| [W-BACK-008](#w-back-008) | warning | A server switch timed out |
| [W-BACK-009](#w-back-009) | warning | A held packet was dropped |
| [W-BACK-010](#w-back-010) | warning | A server group has no server available |
| [W-BACK-011](#w-back-011) | warning | A server is skipped by the balancers |
| [W-BACK-012](#w-back-012) | warning | A backend's login query was too large for a client |
| [W-BACK-013](#w-back-013) | warning | A backend left too many login queries unanswered |
| [W-AUTH-001](#w-auth-001) | warning | Players authenticate against a custom server |
| [W-AUTH-002](#w-auth-002) | warning | Profile keys are checked against a custom key |
| [W-AUTH-003](#w-auth-003) | error | Encryption could not be enabled |
| [W-AUTH-004](#w-auth-004) | warning | The session server failed |
| [W-AUTH-005](#w-auth-005) | warning | A login was refused |
| [W-NET-001](#w-net-001) | error | A connection failed |
| [W-NET-002](#w-net-002) | warning | A trusted proxy's connection was refused |
| [W-NET-003](#w-net-003) | warning | A connection from an untrusted address |
| [W-NET-004](#w-net-004) | warning | A client sent an unexpected packet |
| [W-NET-005](#w-net-005) | warning | An invalid keep-alive answer |
| [W-NET-006](#w-net-006) | warning | A disconnect reason is too large |
| [W-NET-007](#w-net-007) | warning | A boss bar name is too large |
| [W-NET-008](#w-net-008) | warning | A cookie was not stored |
| [W-NET-009](#w-net-009) | warning | Cookie requests went unanswered |
| [W-NET-010](#w-net-010) | warning | A transfer failed |
| [W-NET-011](#w-net-011) | warning | A player's command list could not be refreshed |
| [W-FLEET-001](#w-fleet-001) | warning | The fleet store is unreachable |
| [W-FLEET-002](#w-fleet-002) | error | Two proxies share a fleet node id |
| [W-FLEET-003](#w-fleet-003) | warning | A login was not settled with the fleet in time |
| [W-FLEET-004](#w-fleet-004) | warning | A fleet bus handler failed |
| [W-FLEET-005](#w-fleet-005) | warning | A malformed fleet message was dropped |
| [W-FLEET-006](#w-fleet-006) | warning | A proxy of the fleet stopped heartbeating |
| [W-FLEET-007](#w-fleet-007) | warning | A player is online on two proxies |

## Configuration

### W-CFG-001

**The configuration cannot be read** (error)

**Cause.** warp.conf (or a variable such as WARP_LOG_FORMAT) has invalid values, unknown keys or a syntax error. The message lists every problem with its file and line.

**Fix.** Fix each problem listed, then start Warp again. To start from scratch, move warp.conf away: Warp writes a fresh one with every setting explained.

### W-CFG-002

**The log format differs from logging.format** (warning)

**Cause.** Warp picks the log format before it reads warp.conf in full, so that every line has the same format. That early read failed (an invalid file), so the console uses the default.

**Fix.** Fix warp.conf, or set the WARP_LOG_FORMAT variable, which Warp reads first.

### W-CFG-003

**status.motd uses legacy colour codes** (warning)

**Cause.** The MOTD contains codes such as &a, which MiniMessage shows as typed.

**Fix.** Use MiniMessage tags instead, such as <green> or <gradient:gold:red>.

### W-CFG-004

**status.motd has more than two lines** (warning)

**Cause.** Clients show the first two lines of a MOTD only.

**Fix.** Keep the MOTD to two lines.

### W-CFG-005

**proxy-protocol.trusted-proxies trusts everyone** (warning)

**Cause.** A range such as 0.0.0.0/0 lets anyone who reaches Warp's port directly send a PROXY header and choose the address Warp sees, defeating bans and rate limits.

**Fix.** List only the addresses of your load balancers, and firewall Warp's port so that only they reach it.

### W-CFG-006

**A new forwarding secret was generated** (warning)

**Cause.** Modern forwarding needs a secret shared with every backend, and its file did not exist.

**Fix.** Give the secret to every backend (Paper: proxies.velocity.secret in config/paper-global.yml) and restart them, or players cannot join.

### W-CFG-007

**tcp-fast-open is on without epoll** (warning)

**Cause.** TCP Fast Open needs the epoll transport, which only Linux has; the setting is ignored here.

**Fix.** Set tcp-fast-open = false, or run Warp on Linux.

### W-CFG-008

**A console setting is invalid** (warning)

**Cause.** WARP_COLOR, WARP_CONSOLE, WARP_THEME, WARP_HYPERLINKS or console.theme has a value Warp does not know; it uses auto instead.

**Fix.** Use one of the values the message lists, or remove the setting.

### W-CFG-009

**A route points to an unknown server** (warning)

**Cause.** A routes entry of warp.conf sends players to a name that is neither a registered server nor a group: a capture such as $1 named something that does not exist, or the server was unregistered. Those players go to the default server instead.

**Fix.** Add the server or group, fix the entry, or point the host at an existing one.

## Startup

### W-START-001

**The listening address does not resolve** (error)

**Cause.** bind (or management.bind) names a host that DNS does not know.

**Fix.** Use an IP address of this machine, or 0.0.0.0 to listen on every address.

### W-START-002

**The port is already in use** (error)

**Cause.** Another program listens on the port: often another Warp, or a Minecraft server on the same machine.

**Fix.** Stop that program, or choose another port in bind (or management.bind). On Linux, ss -ltnp shows who holds a port.

### W-START-003

**Listening on the port is not permitted** (error)

**Cause.** Ports below 1024 need extra rights, and some systems reserve other ranges.

**Fix.** Use a port from 1024 up (25565 is Minecraft's), and forward the low port to it with your firewall if players must use it.

### W-START-004

**The listening address is not this machine's** (error)

**Cause.** bind names an IP address that no network interface of this machine has: often a public address behind NAT, or a container's address.

**Fix.** Use one of the machine's own addresses, or 0.0.0.0 to listen on all of them.

### W-START-005

**Warp cannot listen on its address** (error)

**Cause.** Opening the listening socket failed for a reason the message gives.

**Fix.** Check bind in warp.conf and the system's network settings.

### W-START-006

**The plugins directory cannot be read** (error)

**Cause.** plugins is a file, or the user running Warp may not read or create it.

**Fix.** Move the file away, or give the user running Warp read and write access to the directory.

### W-START-007

**Warp failed to start** (error)

**Cause.** An unexpected error stopped the startup; the stack trace says where.

**Fix.** Report it with the log file at https://github.com/NeosiaNexus/Warp/issues.

## The process

### W-SYS-001

**Uncaught exception** (error)

**Cause.** A thread ended with an exception that no code handled: a bug, in Warp or in a plugin (the stack trace names the classes).

**Fix.** Report it to the project the top frames belong to, with the log file.

### W-SYS-002

**Handling SIGTERM failed** (error)

**Cause.** Warp could not start the graceful stop it runs on SIGTERM (drain.on-sigterm).

**Fix.** Report it with the log file; meanwhile stop Warp with /shutdown or POST /drain.

### W-SYS-003

**The shutdown did not finish in time** (warning)

**Cause.** Stopping took longer than Warp waits: a plugin or a connection did not let go.

**Fix.** Look for the plugin or thread named in the lines before; a thread dump (jstack) shows what held the shutdown.

## Natives

### W-NAT-001

**Native compression could run better** (warning)

**Cause.** libdeflate is loaded, but the message suggests a setting that makes it faster or safer.

**Fix.** Apply the advice the message gives.

### W-NAT-002

**Native compression is unavailable** (warning)

**Cause.** libdeflate could not load on this system (the message says why), so Warp compresses with java.util.zip, which is slower.

**Fix.** Follow the advice in the message (often --enable-native-access=ALL-UNNAMED, or a supported platform). To keep java.util.zip quietly, start Warp with -Dwarp.natives.disable=true.

## Plugins

### W-PLG-001

**The plugins directory cannot be listed** (error)

**Cause.** Reading the plugins directory failed; no plugin is loaded.

**Fix.** Give the user running Warp read access to the directory and its jars.

### W-PLG-002

**Several jars declare the same plugin** (error)

**Cause.** Two or more jars in plugins declare the same id, often an old and a new version side by side; none of them is loaded.

**Fix.** Keep one jar per plugin.

### W-PLG-003

**A jar is not a Warp plugin** (error)

**Cause.** A jar in plugins has no warp-plugin.json: a plugin for another proxy or server, or a library.

**Fix.** Remove it, or use the plugin's Warp build. Libraries belong inside the plugin jar.

### W-PLG-004

**A plugin jar cannot be read** (error)

**Cause.** The jar is damaged (an interrupted download) or not readable by the user running Warp.

**Fix.** Download the plugin again, and check the file's permissions.

### W-PLG-005

**A plugin descriptor is invalid** (error)

**Cause.** warp-plugin.json in the jar is not valid JSON, or lacks a required field.

**Fix.** Report it to the plugin's author; the message names the problem.

### W-PLG-006

**A plugin cannot load** (error)

**Cause.** Something the plugin needs is missing: a dependency that is absent or failed, a newer Warp API, or a dependency cycle (the message says which).

**Fix.** Install or update what the message names.

### W-PLG-007

**A plugin's main class is missing** (error)

**Cause.** warp-plugin.json names a main class the jar does not contain: a broken build.

**Fix.** Report it to the plugin's author.

### W-PLG-008

**A plugin uses a class it cannot see** (error)

**Cause.** A plugin sees the Warp API, Adventure, SLF4J, its own jar and the plugins it depends on. It uses a class from elsewhere: an unshaded library, or Warp's internals.

**Fix.** Report it to the plugin's author: shade the library, or declare the dependency.

### W-PLG-009

**A plugin failed to load** (error)

**Cause.** The plugin threw an exception while it was constructed or enabled.

**Fix.** Report it to the plugin's author with the stack trace.

### W-PLG-010

**A plugin failed to disable cleanly** (error)

**Cause.** The plugin threw an exception while it was disabled at shutdown.

**Fix.** Report it to the plugin's author; Warp still stops.

### W-PLG-011

**A plugin's registration could not be undone** (warning)

**Cause.** Removing something a plugin registered (a listener, a command, a channel) failed while the plugin was unloaded.

**Fix.** Report it with the stack trace; restart Warp if the plugin's feature still shows.

### W-PLG-012

**A permission provider failed** (error)

**Cause.** The plugin that answers permission checks threw an exception; the check was denied.

**Fix.** Report it to the plugin's author.

### W-PLG-013

**A plugin failed to set up a connection** (error)

**Cause.** The plugin's pipeline handler threw while a connection was set up; the connection was closed.

**Fix.** Report it to the plugin's author.

### W-PLG-014

**A plugin changed handlers it does not own** (error)

**Cause.** A plugin may add handlers to a connection's pipeline, never remove, replace or move the proxy's or another plugin's; the connection was closed.

**Fix.** Report it to the plugin's author.

### W-PLG-015

**A plugin's log sink failed** (warning)

**Cause.** The plugin's log sink threw on a record; later failures are counted.

**Fix.** Report it to the plugin's author.

### W-PLG-016

**A plugin's log sink was removed** (warning)

**Cause.** The plugin's log sink cannot run at all, so Warp stopped feeding it.

**Fix.** Report it to the plugin's author.

### W-PLG-017

**A plugin's log sink lost records** (warning)

**Cause.** The plugin's log sink did not keep up, and records were dropped from its queue (the log file has them all).

**Fix.** Make the sink faster, or report it to the plugin's author.

### W-PLG-018

**Shutdown handlers did not finish in time** (warning)

**Cause.** Plugins' ProxyShutdownEvent handlers ran longer than Warp waits; Warp stopped anyway.

**Fix.** Find the slow plugin (it runs last in the log) and report it to its author.

### W-PLG-019

**A plugin changed a ping after the answer** (warning)

**Cause.** A ProxyPingEvent handler changed the response from a callback after Warp answered.

**Fix.** Report it to the plugin's author: the handler must return the callback's stage.

### W-PLG-020

**A server list entry is too long** (warning)

**Cause.** A plugin made the server list answer longer than a client reads, so Warp dropped a part.

**Fix.** Shorten what the plugin puts in the server list (sample players, MOTD, favicon).

## Events and plugin callbacks

### W-EVT-001

**An event handler held an event too long** (warning)

**Cause.** An asynchronous handler did not complete within its time-out, so the event went on without it.

**Fix.** Report it to the plugin's author; it can raise timeoutSeconds if it needs longer.

### W-EVT-002

**An event handler failed** (error)

**Cause.** A plugin's event handler threw an exception; the other handlers still ran.

**Fix.** Report it to the plugin's author with the stack trace.

### W-EVT-003

**An event handler was slow** (warning)

**Cause.** A handler blocked for long, and the player's later events waited for it.

**Fix.** Report it to the plugin's author: slow work belongs off the event.

### W-EVT-004

**Events were still running at shutdown** (warning)

**Cause.** Handlers were still running when the shutdown's grace period ended; they were interrupted.

**Fix.** Find the slow plugin and report it to its author.

### W-EVT-005

**Plugins held a player too long** (warning)

**Cause.** Plugins kept a player waiting (login, server choice) beyond their handlers' time-outs, so the player was disconnected.

**Fix.** Find the plugin that holds logins and report it to its author.

### W-EVT-006

**A plugin callback failed** (error)

**Cause.** A callback a plugin registered (such as a resource pack callback) threw an exception.

**Fix.** Report it to the plugin's author with the stack trace.

## Commands

### W-CMD-001

**A command failed** (error)

**Cause.** A command threw an exception; its sender was told it failed.

**Fix.** Report it to whoever owns the command (Warp, or the plugin) with the stack trace.

## Console

### W-CON-001

**The interactive console could not start** (warning)

**Cause.** The terminal could not be opened for the prompt (an unusual terminal, or a restricted environment); the console reads plain lines instead.

**Fix.** Nothing to do if plain lines suit you; WARP_CONSOLE=plain silences this.

### W-CON-002

**The console stopped reading commands** (warning)

**Cause.** Reading standard input failed; the proxy runs on, without console commands.

**Fix.** Use the management endpoint or an in-game account to run commands; restart to get the console back.

## Drain

### W-DRAIN-001

**The drain timed out** (warning)

**Cause.** Players were still connected when drain.timeout ended; they were disconnected.

**Fix.** Raise drain.timeout, or check why transfers did not happen (servers down, plugins).

### W-DRAIN-002

**Plugins were slow to decide about a player** (warning)

**Cause.** DrainEvent handlers did not decide where a player goes in time, so the default applied.

**Fix.** Report it to the plugin's author.

### W-DRAIN-003

**Asking plugins about a player failed** (error)

**Cause.** Firing the drain's event for a player failed; the default applied.

**Fix.** Report it with the stack trace.

### W-DRAIN-004

**A player could not be drained** (error)

**Cause.** Moving or disconnecting a player during the drain failed; the drain went on.

**Fix.** Report it with the stack trace.

### W-DRAIN-005

**A transfer was slow to write** (warning)

**Cause.** The player's connection did not take the transfer packet in time (a slow or stalled client); the drain moved on.

**Fix.** Nothing to do for one player; many point at the network.

### W-DRAIN-006

**A drain step failed** (error)

**Cause.** A scheduled step of the drain threw an exception.

**Fix.** Report it with the stack trace.

### W-DRAIN-007

**SIGTERM cannot be caught** (warning)

**Cause.** drain.on-sigterm is on, but this JVM does not let Warp handle SIGTERM, so SIGTERM stops the proxy at once.

**Fix.** Run Warp on a standard JDK (Temurin, for instance), or drain with POST /drain before stopping.

### W-DRAIN-008

**SIGTERM again during the drain** (warning)

**Cause.** A second SIGTERM stops the proxy at once, without finishing the drain: what orchestrators send once their grace period is over.

**Fix.** Raise the orchestrator's grace period (Kubernetes terminationGracePeriodSeconds) above drain.timeout.

## Anti-bot

### W-BOT-001

**The attack detector failed** (error)

**Cause.** The anti-bot's periodic check threw an exception.

**Fix.** Report it with the stack trace.

### W-BOT-002

**A plugin's anti-bot check failed** (warning)

**Cause.** A plugin's check threw an exception; the login went on as if it had passed.

**Fix.** Report it to the plugin's author.

### W-BOT-003

**Lockdown is on** (warning)

**Cause.** Only players who have played here before may log in, set by /warp antibot lockdown, the management endpoint or the anti-bot itself.

**Fix.** Turn it off with /warp antibot lockdown off once the attack is over.

### W-BOT-004

**Bot attack detected** (warning)

**Cause.** Connections or logins exceeded anti-bot.attack thresholds. Warp stops learning new identities from joins and summarises the attack periodically.

**Fix.** Usually nothing: the anti-bot copes. To let only known players in, /warp antibot lockdown on.

### W-BOT-005

**Trusted identities were not saved on shutdown** (warning)

**Cause.** Writing the anti-bot's store failed while Warp stopped; the changes since the last save are lost.

**Fix.** Check free disk space and the permissions of the anti-bot's directory.

### W-BOT-006

**Saving trusted identities took too long** (warning)

**Cause.** The anti-bot's store did not finish writing within 10 s of the shutdown.

**Fix.** Check the disk: a slow or full disk delays writes.

### W-BOT-007

**The anti-bot store's periodic work failed** (error)

**Cause.** Compacting or expiring the trusted identities threw an exception.

**Fix.** Report it with the stack trace.

### W-BOT-008

**Trusted identities cannot be saved** (warning)

**Cause.** Writing the anti-bot's store failed; identities stay in memory until it works again.

**Fix.** Check free disk space and the permissions of the anti-bot's directory.

### W-BOT-009

**The anti-bot snapshot is corrupt** (warning)

**Cause.** The snapshot fails its checksum (a crash or a disk fault); it was set aside and the journal replayed alone.

**Fix.** Nothing to do; keep the set-aside file if you want to investigate.

### W-BOT-010

**The anti-bot journal was cut short** (warning)

**Cause.** The journal ended mid-write (a crash); the incomplete change was dropped.

**Fix.** Nothing to do.

### W-BOT-011

**The anti-bot token key cannot be rotated** (warning)

**Cause.** The key verification tokens are signed under is due for rotation, but its file (anti-bot.verification.token.secret-file) cannot be written. The current key stays in use.

**Fix.** Check the directory's permissions and free space, or set WARP_ANTIBOT_TOKEN_SECRET to manage the key yourself.

### W-BOT-012

**A verified player could not be transferred back** (warning)

**Cause.** A player verified before authentication during an attack must be transferred back to the proxy, but its handshake named no usable host and anti-bot.verification.transfer is not set. The player was asked to reconnect instead.

**Fix.** Set anti-bot.verification.transfer.host and port to the address players connect to.

## Logging

### W-LOG-001

**Log sinks receive nothing** (warning)

**Cause.** The logging backend is not Log4j Core (another SLF4J binding is first on the class path), so plugins' log sinks get no records.

**Fix.** Run Warp from its own jar, without another logging backend.

## Management

### W-MGMT-001

**A management request was refused** (warning)

**Cause.** A POST to the management endpoint had no bearer token, or the wrong one.

**Fix.** Send Authorization: Bearer <management.token>; if you did not, someone else is trying.

### W-MGMT-002

**A renewed management certificate could not be read** (warning)

**Cause.** The files of management.tls changed, but the new certificate or key could not be read: a renewal caught halfway, a key not in PKCS#8, or a certificate that does not match the key. Warp keeps serving the previous certificate.

**Fix.** Check the files the message names: PEM, the key in PKCS#8 (openssl pkcs8 -topk8 -nocrypt, or privateKey.encoding: PKCS8 in cert-manager). Warp reads them again when they next change.

### W-MGMT-003

**GET /metrics is open to other machines** (warning)

**Cause.** management.bind is not a loopback address, and neither management.tls nor management.metrics.require-token is on: anyone who reaches the port reads the metrics (players online, servers, versions).

**Fix.** Bind the management server to loopback or a private network, enable management.tls, or set management.metrics.require-token = true and give Prometheus the token.

## Metrics

### W-MET-001

**A metric reached a cardinality cap** (warning)

**Cause.** A label of the metric had its 33rd distinct value (256th for the proxy's own metrics), or a plugin reached 2000 series, or the registry 10000. Further values are recorded as 'other' and counted in warp_metrics_label_overflow_total, so the series stay bounded; the metric loses detail, not correctness. Logged once per metric.

**Fix.** If a plugin's metric is named, report it to its author: a label must take values from a closed set (an outcome, a state), never a player, address or item name.

### W-MET-002

**A metrics collector failed** (warning)

**Cause.** A collector, or the supplier of a pull gauge, threw while GET /metrics was answered. It is skipped for that scrape and counted in warp_metrics_collector_errors_total; the other metrics are served. Logged once per collector.

**Fix.** If a plugin's collector is named, report it with the stack trace to its author.

### W-MET-003

**GET /metrics could not be answered** (warning)

**Cause.** Writing the metrics failed with an exception, so the scraper got a 500. The metrics are still collected; the next scrape is tried afresh.

**Fix.** Report it with the stack trace.

## Remote console

### W-RCON-001

**The remote console streams nothing** (warning)

**Cause.** The logging backend is not Log4j Core (another SLF4J binding is first on the class path), so the remote console has no log to stream; commands still run.

**Fix.** Run Warp from its own jar, without another logging backend.

### W-RCON-002

**A remote console token lacks a scope** (warning)

**Cause.** A valid token asked for an endpoint its scopes do not cover: running commands, completing them or reading the command tree needs the commands scope, the log stream needs logs.

**Fix.** Give that token the scope in management.console.tokens if it should have it, or attach with --read-only. Otherwise someone holding the token tried more than it allows.

### W-RCON-003

**A remote command arrived without TLS from an untrusted address** (warning)

**Cause.** The management server runs without TLS, and a command (or a completion) came from an address that is neither loopback nor in management.console.trusted-networks: its token would have crossed the network in plain text.

**Fix.** Set management.tls, come through kubectl port-forward or an SSH tunnel to 127.0.0.1, or list the TLS-terminating proxy in front of Warp in trusted-networks. Change the token if it crossed a network you do not trust.

### W-RCON-004

**A wrong remote console token** (warning)

**Cause.** A request to /console carried a bearer token that matches none of management.console.tokens. After lockout-after of them, the address is locked out.

**Fix.** Check the client's --token-file or WARP_CONSOLE_TOKEN. If no one of yours is attaching, someone is guessing: keep the port on loopback or behind TLS and a firewall.

### W-RCON-005

**An address was locked out of the remote console** (warning)

**Cause.** It sent lockout-after wrong tokens without a right one in between; its requests get 429 for lockout-duration, before their token is read. IPv6 addresses count by their /64.

**Fix.** If it is yours, fix its token and wait for the lockout to end (or restart Warp). If not, block it upstream.

### W-RCON-006

**A remote console token ran too many commands** (warning)

**Cause.** A token sent more commands in ten seconds than the remote console accepts; the extra ones were refused with 429 and not run.

**Fix.** Slow the script down, or run its commands in fewer requests.

### W-RCON-007

**The proxy runs no commands for this token** (warning)

**Cause.** The client attached with a prompt, but the proxy refused to list the commands of its token: the token lacks the commands scope, or commands without TLS are refused from this address. The log still streams, and /log filter and /log tail still work.

**Fix.** Use a token with the commands scope, connect over TLS (or through a tunnel to the proxy's loopback), or attach with --read-only.

### W-RCON-008

**The remote console client cannot reach a proxy** (warning)

**Cause.** The log stream of a proxy ended (the proxy stopped, a network or an ingress cut it), or the client cannot connect to it. It retries on its own, waiting from 1 s up to 30 s, and resumes where it left off.

**Fix.** Nothing, if the proxy is restarting. Otherwise check the --url, that management is enabled and listening there, and what lies between (firewall, port-forward, ingress timeouts).

### W-RCON-009

**A proxy refused the remote console client's token** (error)

**Cause.** The proxy answered 401 or 403 to the log stream: the token is wrong, lacks the logs scope, or the remote console is off. The client stops following that proxy and never retries, so that it cannot lock its operator out.

**Fix.** Check --token-file (or WARP_CONSOLE_TOKEN) against management.console.tokens, and that management.console.enabled is true.

### W-RCON-010

**A proxy asked the remote console client to wait** (warning)

**Cause.** The proxy answered 429: its address is locked out after wrong tokens, or the token has too many streams open (4 per token, 32 in all). The client waits as long as the proxy asks, then retries.

**Fix.** Close the clients you no longer use, or fix the token that locked the address out.

### W-RCON-011

**A proxy answered the remote console client unexpectedly** (warning)

**Cause.** The log stream got an HTTP status other than 200, 401, 403 or 429: the --url points at something other than Warp's management server, or a proxy in between answered. The client retries.

**Fix.** Check the --url (scheme, host, port) and what lies between the client and Warp.

### W-RCON-012

**The remote console client missed lines** (warning)

**Cause.** The client read too slowly and the proxy dropped lines for it, or it reconnected after the proxy had stopped keeping the lines it missed. The log file on the proxy has them all.

**Fix.** Narrow the stream (--level, --tags, --grep) on a busy proxy, or read logs/warp.log there.

## BungeeCord channel

### W-MSG-001

**A BungeeCord sub-channel is not allowed** (warning)

**Cause.** A server used a sub-channel its bungeecord-channel.allow rule does not allow.

**Fix.** Allow the sub-channel for that server if it needs it.

### W-MSG-002

**BungeeCord Connect to an unknown server** (warning)

**Cause.** A server asked to move a player to a server that is not registered.

**Fix.** Register the server in warp.conf, or fix the name in the backend's plugin.

### W-MSG-003

**BungeeCord Forward to an unknown server** (warning)

**Cause.** A server forwarded a message to a server that is not registered.

**Fix.** Register the server in warp.conf, or fix the name in the backend's plugin.

### W-MSG-004

**BungeeCord Forward messages were dropped** (warning)

**Cause.** Messages waited for a player on the target server that never came, or too many waited.

**Fix.** Check that the target server has players when the plugin forwards to it.

### W-MSG-005

**A BungeeCord message is too large** (warning)

**Cause.** The message is larger than a backend accepts from a client: forwarding it would disconnect the player carrying it.

**Fix.** Send smaller messages from the backend's plugin.

### W-MSG-006

**A malformed BungeeCord message** (warning)

**Cause.** A server sent a message that does not follow the BungeeCord channel's format.

**Fix.** Report it to the author of the backend's plugin.

## Backends

### W-BACK-001

**A backend runs in online mode** (warning)

**Cause.** The backend asked for encryption: it authenticates players itself, which only works without a proxy.

**Fix.** Set online-mode=false in the backend's server.properties and enable forwarding (Paper: proxies.velocity in config/paper-global.yml).

### W-BACK-002

**A backend's compression threshold differs** (warning)

**Cause.** The backend compresses from another size than players do, so packets between the two thresholds are re-encoded instead of forwarded as they are.

**Fix.** Set the backend's network-compression-threshold to the value the message gives.

### W-BACK-003

**A backend has compression off** (warning)

**Cause.** The backend sends uncompressed packets, so Warp compresses its traffic for every player.

**Fix.** Set network-compression-threshold on the backend to the value the message gives.

### W-BACK-004

**A backend sent an unexpected packet** (warning)

**Cause.** The backend sent a packet that does not belong where the connection stands; it was ignored.

**Fix.** Report it with the backend's software and version if it repeats.

### W-BACK-005

**The player left during a server switch** (warning)

**Cause.** The player's connection closed while Warp set up the backend's configuration phase.

**Fix.** Nothing to do for one player.

### W-BACK-006

**A player could not join a server** (warning)

**Cause.** Connecting to the backend failed: it is down, refused the player, or timed out (the message says which).

**Fix.** Check that the server runs and is reachable from Warp at its address in warp.conf.

### W-BACK-007

**No server available for a player** (warning)

**Cause.** Every server of the try list and of the fallbacks is down or refused the player.

**Fix.** Start the servers, or check the try list and fallback-order in warp.conf.

### W-BACK-008

**A server switch timed out** (warning)

**Cause.** The target server did not finish the player's join in time; the player stays where they were.

**Fix.** Check the target server's load and the network between it and Warp.

### W-BACK-009

**A held packet was dropped** (warning)

**Cause.** A packet waiting for a protocol phase that the connection never reached was dropped.

**Fix.** Report it with the client's and the backend's versions if it repeats.

### W-BACK-010

**A server group has no server available** (warning)

**Cause.** Every server of the group is unregistered, excluded for the player (the one it is leaving, or one that failed it already), or its balancer declined to pick one. The player goes to the next fallback, or is disconnected if there is none.

**Fix.** Start or register the group's servers, or add a fallback outside the group.

### W-BACK-011

**A server is skipped by the balancers** (warning)

**Cause.** The server did not answer its last ping within timeouts.ping, a player could not connect to it, or it refused the logins of three players in a row (a single refusal is taken as aimed at that player: a ban, a whitelist). Groups and fallbacks skip it for a cool-down, then let one player through to find out whether it is back; a successful ping brings it back at once.

**Fix.** Check that the server runs, is reachable from Warp and accepts logins from it (forwarding secret, whitelist). Raise timeouts.ping if it answers pings slowly under load. Nothing to do if it was restarting.

### W-BACK-012

**A backend's login query was too large for a client** (warning)

**Cause.** A backend sent a login plugin request over 1 MiB, the most a client accepts; Warp answered it as not understood instead of relaying it to the client, which would have dropped the connection.

**Fix.** Check the mods of that backend: Forge splits its registries into messages under the limit.

### W-BACK-013

**A backend left too many login queries unanswered** (warning)

**Cause.** A backend sent more than 256 login plugin requests before the client answered any; Warp dropped the connection to that backend, and the player falls back or is kicked.

**Fix.** Check the mods of that backend: a login-phase mod that floods queries is misbehaving.

## Authentication and login

### W-AUTH-001

**Players authenticate against a custom server** (warning)

**Cause.** A system property points authentication at another session server than Mojang's.

**Fix.** Expected with a custom authentication server; remove the property otherwise.

### W-AUTH-002

**Profile keys are checked against a custom key** (warning)

**Cause.** A system property replaces Mojang's key for the profile keys of 1.19 to 1.19.2 players.

**Fix.** Expected with a custom authentication server; remove the property otherwise.

### W-AUTH-003

**Encryption could not be enabled** (error)

**Cause.** Setting up a player's encryption failed; the player was disconnected with the reference the message gives.

**Fix.** Report it with the reference and the log file.

### W-AUTH-004

**The session server failed** (warning)

**Cause.** Mojang's session server (or the custom one) did not answer, or answered with an error.

**Fix.** Usually a Mojang outage: check its status. Persistent failures point at DNS or a firewall blocking sessionserver.mojang.com.

### W-AUTH-005

**A login was refused** (warning)

**Cause.** A player was disconnected during login for the reason the message gives, with a reference the player also sees.

**Fix.** Match the reference the player reports with this line.

## Connections

### W-NET-001

**A connection failed** (error)

**Cause.** An unexpected exception in a connection's pipeline closed it.

**Fix.** Report it with the stack trace if it repeats.

### W-NET-002

**A trusted proxy's connection was refused** (warning)

**Cause.** A load balancer sent no PROXY header, or an invalid one.

**Fix.** Enable the PROXY protocol (v1 or v2) on the load balancer, towards Warp.

### W-NET-003

**A connection from an untrusted address** (warning)

**Cause.** The PROXY protocol is on, and the address is not in proxy-protocol.trusted-proxies.

**Fix.** Add your load balancers' addresses to proxy-protocol.trusted-proxies; firewall the port from everyone else.

### W-NET-004

**A client sent an unexpected packet** (warning)

**Cause.** A client sent a packet that does not belong where its connection stands; it was ignored.

**Fix.** Report it with the client's version and mods if it repeats.

### W-NET-005

**An invalid keep-alive answer** (warning)

**Cause.** A client answered a keep-alive with an id Warp did not send: a modified client, or a bug.

**Fix.** Report it with the client's version and mods if it repeats.

### W-NET-006

**A disconnect reason is too large** (warning)

**Cause.** A kick message exceeds what the client accepts, so a short reason was sent instead.

**Fix.** Shorten the kick message (often a plugin's).

### W-NET-007

**A boss bar name is too large** (warning)

**Cause.** A boss bar's name exceeds what the client accepts, so it shows empty.

**Fix.** Shorten the boss bar's name (often a plugin's).

### W-NET-008

**A cookie was not stored** (warning)

**Cause.** Storing a cookie on a client failed (too large, or the client left).

**Fix.** Report it to the plugin storing the cookie if it repeats.

### W-NET-009

**Cookie requests went unanswered** (warning)

**Cause.** A client did not answer cookie requests in time, so it was disconnected.

**Fix.** Nothing to do for one client; many point at a modified client.

### W-NET-010

**A transfer failed** (warning)

**Cause.** Sending a player to another host (a 1.20.5+ transfer) failed.

**Fix.** Check the target host and port, and that the client is 1.20.5 or newer.

### W-NET-011

**A player's command list could not be refreshed** (warning)

**Cause.** Rebuilding the commands a player sees failed; the player keeps the previous list.

**Fix.** Report it with the stack trace.

## Fleet

### W-FLEET-001

**The fleet store is unreachable** (warning)

**Cause.** Warp cannot reach the store named in fleet.store (Valkey): it is down, the address or the credentials are wrong, or a firewall is in the way. This proxy keeps running alone: the other proxies' players are frozen as last seen, and logins follow fleet.on-store-down. Warp reconnects by itself and logs when the store is back.

**Fix.** Check that the store runs and that fleet.store.valkey.address, username and password are right. Nothing to do once the store is back: the proxies resynchronise.

### W-FLEET-002

**Two proxies share a fleet node id** (error)

**Cause.** Another proxy heartbeats under this proxy's fleet.node-id with a newer incarnation: two proxies have the same id, and would reap each other's players. This proxy left the fleet; its own players are unaffected, but it is invisible to the others.

**Fix.** Give each proxy a distinct fleet.node-id (or leave it empty, so that each uses its host name), then restart this proxy.

### W-FLEET-003

**A login was not settled with the fleet in time** (warning)

**Cause.** The store did not answer within fleet.claim-timeout whether the account is online on another proxy. The login followed fleet.on-store-down: let in (duplicates settled on this proxy alone) or refused.

**Fix.** A slow or overloaded store: check its latency, or raise fleet.claim-timeout.

### W-FLEET-004

**A fleet bus handler failed** (warning)

**Cause.** A plugin's listener or responder on the fleet bus threw an exception. The message was lost for that listener; a request got a failure in reply.

**Fix.** Report it to the plugin's author with the stack trace.

### W-FLEET-005

**A malformed fleet message was dropped** (warning)

**Cause.** A message on a fleet channel could not be read: another program publishes on Warp's channels, or two Warp versions with different frames share a network.

**Fix.** Run one Warp version per network, and keep other programs off the warp:<network>:* channels.

### W-FLEET-006

**A proxy of the fleet stopped heartbeating** (warning)

**Cause.** A proxy wrote no heartbeat for fleet.heartbeat-ttl: it crashed, hung, or lost the store. This proxy reaped its player records, so that its players can log in elsewhere.

**Fix.** Check that proxy. A clean stop or drain says goodbye and is not logged here.

### W-FLEET-007

**A player is online on two proxies** (warning)

**Cause.** After the store came back, a player connected here is claimed by another proxy: a write was lost during the outage (an asynchronous replica failing over). Both sessions stay until one leaves.

**Fix.** Nothing to do for one player. Repeated conflicts point at a store that loses writes on failover: check its replication.
