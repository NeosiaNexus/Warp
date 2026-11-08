# Migrating from Velocity or BungeeCord

`warp migrate` turns a Velocity or BungeeCord (Waterfall) installation into a Warp configuration,
and says what became of every setting and every plugin. It never touches the source installation.

```bash
java -jar warp.jar migrate /srv/velocity --to /srv/warp        # velocity.toml detected
java -jar warp.jar migrate /srv/bungee --to /srv/warp          # config.yml detected
java -jar warp.jar migrate --from bungeecord /srv/proxy --dry-run   # print warp.conf, write nothing
```

| Option | Meaning |
|---|---|
| `SOURCE` | the proxy's directory (default: the current directory) |
| `--from velocity` or `--from bungeecord` | the source proxy, when both `velocity.toml` and `config.yml` are present |
| `--to DIR` | where to write `warp.conf` (default: the current directory) |
| `--dry-run` | print `warp.conf` on standard output, the report on standard error, write nothing |
| `--force` | overwrite an existing `warp.conf` and `forwarding.secret` |

Exit codes: `0` done, `1` the source cannot be read or the result cannot be written, `2` a usage
mistake, `3` the target exists (pass `--force`).

## What it writes

- `warp.conf`, with a comment above each value naming the setting it came from. Every key left out
  keeps Warp's default: start Warp once in an empty directory to read the default `warp.conf`, which
  documents them all.
- `forwarding.secret`, copied from Velocity's secret file (or the inline `forwarding-secret` of a
  Velocity 1.x file), readable by its owner only. BungeeCord has none: Warp generates one on its
  first start.

## The report

Every setting of the source lands in one of two lists, so nothing is lost silently:

- **Migrated**: the source key, its value, and the Warp key it became.
- **Dropped**: the source key and why Warp has no use for it ("Warp always does this", "not
  available yet", "Velocity's own"...). A key the tool does not know is dropped as "not a Velocity
  setting Warp knows" rather than ignored.

Then the **warnings** to read before starting Warp, the **plugins** found in `plugins/` with their
fate on Warp (built in, replaced by a Warp plugin, no Warp build yet, unknown), and the **next
steps**.

## Mapping

| Velocity | BungeeCord | Warp |
|---|---|---|
| `bind` | `listeners[0].host` | `bind` |
| `motd` (MiniMessage) | `listeners[0].motd` (legacy `&` codes) | `status.motd`, in MiniMessage, one string per line |
| `show-max-players` | `listeners[0].max_players` | `status.max-players` (only shown) |
| | `player_limit` | `player-limit` (enforced at login) |
| `online-mode` | `online_mode` | `online-mode` |
| `player-info-forwarding-mode` | `ip_forward` | `forwarding.mode`: `none` stays `none`; `modern`, `legacy`, `bungeeguard` and `ip_forward: true` become `velocity` (see below) |
| `forwarding-secret-file`, `forwarding-secret` | | `forwarding.secret` (the file) |
| `servers.<name>` | `servers.<name>.address` | `servers.<name>.address` |
| `servers.try` | `listeners[0].priorities` | `default-server` (the first) and `fallback-order` |
| `forced-hosts` | `listeners[0].forced_hosts` | `routes { "host" = "server" }`; a host naming several servers gets a `groups` entry of them (round-robin) and the route points at the group |
| `kick-existing-players` | | `duplicate-login`: `kick-existing` or `refuse-new` |
| `sample-players-in-ping = false` | | `status.sample-size = 0` |
| `accepts-transfers` | | `accepts-transfers` |
| `advanced.compression-threshold`, `compression-level` | `network_compression_threshold` | `compression.threshold`, `compression.level` |
| `advanced.login-ratelimit` (ms) | | `limits.logins { burst = 2, interval = <ms> }` |
| | `connection_throttle`, `connection_throttle_limit` | `limits.connections { burst = <limit>, interval = <ms> }` |
| `advanced.connection-timeout` | `server_connect_timeout` | `timeouts.connect` |
| `advanced.read-timeout` | `timeout` | `timeouts.backend-read`, and `timeouts.read` when below the client's own 30 s |
| `advanced.haproxy-protocol` | `listeners[0].proxy_protocol` | `proxy-protocol.enabled`, with an empty `trusted-proxies` list to fill in |
| `advanced.tcp-fast-open` | | `tcp-fast-open` |
| `advanced.bungee-plugin-message-channel` | | `bungeecord-channel.enabled` |
| `advanced.show-ping-requests` | `log_pings` | `logging.pings` |
| `advanced.log-command-executions` | `log_commands` | `logging.commands` |
| `advanced.log-player-connections` | | `logging.connections` |

Dropped, with the reason in the report: `force-key-authentication` and `enforce_secure_profile`
(Warp always checks the key on 1.19 to 1.19.2), `failover-on-unexpected-server-disconnect` and
`announce-proxy-commands` (always), `prevent-client-proxy-connections` and
`prevent_proxy_connections` (Warp's built-in anti-bot takes another approach), `announce-forge` and
`forge_support` (no Forge handshake yet), `ping-passthrough` and `remote_ping_*` (Warp answers the
server list itself), `packet-limiter`, the command rate limits and Waterfall's packet limits (not
yet), `query` (no GameSpy query), `metrics` and `stats` (no bStats), `enable-reuse-port`,
`enable-player-address-logging`, BungeeCord's `permissions`, `groups` and `disabled_commands`, the
listener's `tab_list`, `tab_size`, `force_default_server` and `bind_local_address`, and the servers'
`motd` and `restricted`. Only the first BungeeCord listener is migrated: Warp binds one address.

## Forwarding

Warp forwards player information with Velocity's modern protocol (signed with the secret) or not
at all. BungeeCord's legacy forwarding and BungeeGuard can be spoofed by a client that reaches a
backend, so they become modern forwarding, with a warning: enable Velocity support on every backend
with the content of `forwarding.secret` (Paper: `proxies.velocity` in `config/paper-global.yml`)
and set `online-mode=false` there. Backends before Minecraft 1.13 cannot use it. If the source had
no secret, Warp generates one on its first start.

## Plugins

Velocity and BungeeCord plugins do not run on Warp. The report says, for each jar in `plugins/`:

| Status | Meaning |
|---|---|
| `built-in` | Warp does it itself: Sonar, LimboFilter and other anti-bots (the `anti-bot` block), BungeeGuard |
| `replaced` | a Warp plugin does it: ViaVersion, ViaBackwards and ViaRewind are bundled in Warp's `warp-via` plugin, shipped with each release |
| `unavailable` | no Warp build yet: LuckPerms, SkinsRestorer, Geyser and Floodgate, RedisBungee, LiteBans, Plan... |
| `unknown` | not in the list; ask its author for a Warp build |

The list lives in `proxy/src/main/resources/dev/warp/proxy/migrate/plugins.properties`, one line per
plugin.

## Checked for you

Pitfalls of hand migrations the tool handles: a forced host or `try` entry naming a server that does
not exist is left out with a warning instead of stopping the proxy at startup; both shapes of
Velocity's `ping-passthrough` (a string before 3.4, a table since) are read; keys missing from an
old file take the source proxy's defaults, which Warp's own defaults match; a value of an unexpected
kind is reported, not guessed; the PROXY protocol is not enabled for the whole Internet, since Warp
only accepts the header from `trusted-proxies`.
