# Warp

[![CI](https://github.com/NeosiaNexus/Warp/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/NeosiaNexus/Warp/actions/workflows/ci.yml?query=branch%3Amain)
[![E2E matrix](https://github.com/NeosiaNexus/Warp/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/NeosiaNexus/Warp/actions/workflows/e2e.yml?query=branch%3Amain)
[![Fuzz nightly](https://github.com/NeosiaNexus/Warp/actions/workflows/fuzz.yml/badge.svg?branch=main)](https://github.com/NeosiaNexus/Warp/actions/workflows/fuzz.yml?query=branch%3Amain)
[![Mutation testing](https://github.com/NeosiaNexus/Warp/actions/workflows/mutation.yml/badge.svg?branch=main)](https://github.com/NeosiaNexus/Warp/actions/workflows/mutation.yml?query=branch%3Amain)
[![CodeQL](https://github.com/NeosiaNexus/Warp/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/NeosiaNexus/Warp/actions/workflows/codeql.yml?query=branch%3Amain)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/NeosiaNexus/Warp/badge)](https://scorecard.dev/viewer/?uri=github.com/NeosiaNexus/Warp)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg?logo=openjdk)](https://adoptium.net/)

A high-performance Minecraft Java Edition proxy built on Java 25 and Netty. Warp relays the
packets it does not need to read as raw frames: no decoding and, when its compression threshold
matches the backends', no decompression or recompression either.

## Why Warp

Velocity solved BungeeCord's problems. Warp is designed to solve Velocity's.

| Problem | How Warp addresses it | Status |
|---|---|---|
| **Decompresses packets it never reads** | Blind forwarding: in play, Warp decodes only the few packet types it acts on, and relays every other frame undecoded, still compressed when both sides use the same threshold | Available |
| **Server switches that hang or crash the client** | A switch that does not complete within 30 s is ended instead of hanging on "Reconfiguring", no terminal packet is ever sent inside a bundle, and client settings are replayed to the new server | Available |
| **Decompression bombs** | Impossible compression ratios are refused before inflating, sizes are capped, and each connection has an inflate budget | Available |
| **No graceful drain** | Multi-phase drain with Transfer packet support, designed for rolling deployments and Kubernetes | Planned |
| **Shallow plugin DI** | Full dependency-injection graph via Guice with lifecycle hooks (provision, validate, start, ready, drain, stop) | Planned |
| **No health probes** | Built-in `/livez`, `/readyz`, `/startupz` endpoints, Prometheus metrics and OpenTelemetry tracing | Planned |
| **Undefined event ordering** | Deterministic, priority-based event dispatch, with no race between handlers | Planned |

## Status

> [!IMPORTANT]
> **Pre-release.** Warp is under active development and not ready for production. Players can
> join through it today (online or offline mode), play, switch servers and fall back when a server
> fails. The plugin API, operations features and the rest of the [roadmap](#roadmap) are not built
> yet. Releases are `0.1.0-beta` pre-releases;
> see the [changelog](CHANGELOG.md).

## Features

### Available today

**Proxying**

- **Minecraft Java Edition 1.7.2 to 26.3**: every release protocol in between, each one played
  end to end (see [below](#tested-on-every-minecraft-version)).
- **Online mode**: Mojang authentication and AES/CFB8 encryption. The session server call runs on a
  virtual thread and never blocks the event loop. On 1.19 to 1.19.2, the chat signing key a client
  sends is checked against Mojang's certificate, and the client proves it holds the key by signing
  the verify token, as vanilla servers and Velocity require. Offline mode is available for
  development; it ignores the key, as vanilla 1.19.1+ does.
- **Player info forwarding**: [Velocity modern forwarding](https://docs.papermc.io/velocity/player-information-forwarding)
  (HMAC-SHA256 signed, Minecraft 1.13+ backends such as Paper), or none.
- **Several backend servers**: `/server` lists them and switches between them, on every client
  version: through the configuration phase from 1.20.2, and before it with the new server's Join
  Game and a Respawn, clearing the old server's tab list, boss bars and titles.
- **Fallback**: when the server a player joins or switches to is unreachable or refuses the
  login, or kicks them during play, Warp tries the servers of `fallback-order` in turn before
  disconnecting the player.
- **BungeeCord plugin messaging channel** (`BungeeCord`, `bungeecord:main`): all 18 sub-channels
  Paper and Spigot plugins use to move players and query the network, answered byte for byte as
  BungeeCord and Velocity answer them. Connect goes through Warp's own switch and events; each
  server may only use the sub-channels `bungeecord-channel.allow` gives it; a Forward to an empty
  server waits briefly for a player instead of being lost; clients can never speak the channel.
- **Server list ping** answered by Warp itself (a fixed response for now).
- **PROXY protocol** v1 and v2 behind HAProxy, AWS NLB, Cloudflare Spectrum, TCPShield or a
  Kubernetes load balancer: the player's real address replaces the load balancer's everywhere,
  forwarding to the backends included. Only the load balancers listed in `trusted-proxies` may
  send the header; any other peer is refused, so nobody can spoof an address by sending one.
- **Connection and login limits**, on by default: per client address (IPv4, or IPv6 /64), new
  connections, server list pings and logins are token buckets, with separate budgets so that
  refreshing the server list never locks a player out; logins in progress per address and
  online-mode authentications in flight are capped, and slow logins time out. Refusals are counted
  and logged once a minute, never line by line.

**Performance**

- **Blind forwarding**: frames Warp does not inspect are never deserialized; they go from one
  connection to the other as raw bytes.
- **Compression passthrough**: Warp reads the packet id of a compressed frame without inflating it.
  When Warp and the backends use the same compression threshold (256 by default, as on Paper),
  every frame Warp does not inspect is forwarded verbatim in both directions: no inflate, no
  deflate, no copy. With different thresholds, only the frames between the two are re-encoded.
  In the [first micro-benchmarks](docs/benchmarks/2026-10-05-compression-passthrough.md),
  relaying a compressed chunk took 8.2 µs instead of 838 µs (96 µs instead of 856 µs with
  encryption). These are indicative numbers from a WSL2 machine and a synthetic corpus, not a
  published comparison: read the caveats in the report.
- **Both legs on one event loop**: a player's client and backend connections share an event loop,
  so forwarding involves no cross-thread hand-off. Writes are flushed once per read burst, with
  write-buffer back-pressure between the two sides.
- **Native transports**: epoll on Linux and kqueue on macOS, detected at startup, with NIO as the
  fallback.

**Engineering**

- **Java 25 LTS**: virtual threads, sealed interfaces, records and pattern matching.
- **Netty 4.2** with pooled buffers.
- **Modular architecture**: clean separation between API, protocol, proxy and native bindings.
- **Compile-time null safety and static analysis**: ErrorProne, NullAway, Checkstyle, Spotless and
  JaCoCo on every build.
- **Supply chain**: every third-party GitHub Action pinned to a commit; the release workflow
  publishes each jar with a `SHA256SUMS` file and a signed build provenance attestation.
- **Automated releases**: Conventional Commits, release-please, SemVer with pre-releases.

### Roadmap

Planned, not implemented yet:

- **Plugin system**: plugin loading, Guice dependency injection, lifecycle hooks, a deterministic
  event bus, commands and a scheduler. Today the API exposes the proxy version and `ServerInfo`.
- **Graceful drain** with Transfer packets, for rolling deployments and Kubernetes.
- **Observability**: `/livez`, `/readyz` and `/startupz` health probes, Prometheus metrics,
  OpenTelemetry tracing.
- **Configurable server list ping** (MOTD, player counts).
- **Native compression and cryptography** (libdeflate, AES): `jni/` is a placeholder today.
- **Plugin API on Maven Central.**

## Tested on every Minecraft version

Unit tests check that each piece does what its author meant, and
[mutation testing](CONTRIBUTING.md#mutation-testing) checks the unit tests: thousands of small bugs
are planted in the protocol and proxy code, and the share of them the tests catch can only go up,
which every pull request that changes that code is checked for. The
[end-to-end suite](e2e/README.md) checks that a player can actually play: real-protocol bots go
through Warp to real Paper or vanilla servers for every protocol from 1.7.2 to 26.3. They join,
receive chunks, switch servers, survive a fallback and stay connected. A run also fails
if Warp logs an error, leaks a Netty buffer, or a backend drops a connection with a protocol error.

| When | What runs |
|---|---|
| Every pull request | One version per era and the newest version, plus the online, offline and compression variants on the newest version the bots speak natively |
| Every push to `main`, nightly, on demand, and pull requests labelled `e2e: full` | Every protocol of the matrix |

Versions Warp does not fully support yet stay in the matrix as *known broken*: they run and are
reported without failing CI, and CI says when one starts passing. The
[E2E matrix badge](https://github.com/NeosiaNexus/Warp/actions/workflows/e2e.yml?query=branch%3Amain)
and [`e2e/versions.json`](e2e/versions.json) are the current answer to "does my version work?".

The decoders that read what players and servers send are also
[fuzzed](CONTRIBUTING.md#fuzz-tests): two minutes each on every pull request, ten every night,
against reference implementations, for every state and protocol version.

## Getting Started

### Requirements

- **To run Warp**: Java 25 or newer ([Adoptium Temurin](https://adoptium.net/) recommended).
- **To build it**: Git, and JDK 17 or newer to run Gradle (CI uses 21). Gradle compiles with
  JDK 25 through its toolchain support and downloads one if none is installed.

### Build from source

```bash
git clone https://github.com/NeosiaNexus/Warp.git
cd Warp
./gradlew build
```

The shadow JAR is produced at `proxy/build/libs/warp-<version>.jar`. Pre-built jars are attached
to the [releases](https://github.com/NeosiaNexus/Warp/releases); releases after 0.1.0-beta.5 also
come with a `SHA256SUMS` file and signed provenance
([how to verify](CONTRIBUTING.md#release-automation)). During the beta, `main` moves
well ahead of the releases: build from source to try the latest changes.

### Run

Warp reads `warp.conf` from its working directory, and creates it there on first start, together
with a random `forwarding.secret`. Start it from the directory that should hold them:

```bash
mkdir -p run && cd run
../bin/warp.sh                     # or ../bin/warp-dev.sh: paranoid leak detection, assertions
```

`bin/warp.sh` starts the most recently built `proxy/build/libs/warp-*.jar` with tuned JVM flags
(ZGC, async logging, and `sun.misc.Unsafe` access for Netty on Java 25). Set `WARP_JAR` to start
another jar, such as a downloaded release. The scripts use the `java` on your `PATH`, which must be
Java 25 or newer (the JDK that runs Gradle can be older). Without the scripts:

```bash
java --sun-misc-unsafe-memory-access=allow -jar /path/to/warp-<version>.jar
```

Coming from Velocity or BungeeCord? `java -jar warp.jar migrate /path/to/proxy --to .` converts its
configuration and reports what became of every setting and plugin: see
[docs/migration.md](docs/migration.md).

### Configure

`warp.conf` (HOCON) is commented; the essentials:

```hocon
bind = "0.0.0.0:25577"
online-mode = true

servers {
  lobby    { address = "localhost:25565" }
  survival { address = "localhost:25566" }
}
default-server = "lobby"
fallback-order = ["lobby", "survival"]

forwarding {
  mode = "velocity"                # or "none"
  secret-file = "forwarding.secret" # or the WARP_FORWARDING_SECRET environment variable
}

compression {
  threshold = 256
  passthrough = true
}

player-limit = -1                  # enforced at login; -1 (the default) for no limit

status {
  motd = ["<#38bdf8>A Warp Proxy", "<gray>Two lines at most"] # MiniMessage
  favicon = "server-icon.png"                                # a 64x64 PNG
  # max-players = 500                                        # only shown; defaults to player-limit
}

timeouts {
  connect = 5s                     # opening a connection to a backend
  read = 25s                       # a logged-in client that sends nothing is closed
  backend-read = 30s               # a backend that sends nothing is closed, in every state
  ping = 5s                        # RegisteredServer.ping() without a time-out of its own
}
tcp-fast-open = false              # Linux (epoll) only

logging {
  format = text                    # or json; the WARP_LOG_FORMAT variable takes precedence
  connections = true               # logins, departures, server switches
  pings = false                    # each server list ping
  commands = false                 # each command a player sends
}
```

The server list's `max-players` is only shown, as Velocity's `show-max-players`. The limit players
actually meet is `player-limit`, off by default: a player beyond it is refused with "The server is
full.", unless a plugin lets it in from `LoginEvent`. With a limit and no `max-players`, the server
list shows the limit.

`timeouts.read` watches clients, `timeouts.backend-read` watches backends. A backend may stay silent
longer than a client: while it logs a player in or prepares its world, Warp keeps the waiting client
connected with its own keep-alives, so `backend-read` can safely exceed the client's own 30 s. A
backend that times out sends its player down the fallback order.

Durations take a unit everywhere (`200ms`, `30s`, `5m`, `1h`, `30d`); a bare number other than `0`
is refused rather than guessed.

Any value can come from the environment with HOCON's optional substitution, which replaces the value
only when the variable is set:

```hocon
bind = "0.0.0.0:25577"
bind = ${?WARP_BIND}
drain { transfer-to = ${?WARP_PUBLIC_ADDRESS} }   # alone: the default when unset
```

Secrets never go in `warp.conf`: the forwarding secret comes from `forwarding.secret-file` or
`WARP_FORWARDING_SECRET`, the management token from `management.token-file` or
`WARP_MANAGEMENT_TOKEN`.

With `logging.format = json` (or `WARP_LOG_FORMAT=json`), standard output carries one JSON object
per line, from the very first line: `timestamp` (UTC), `level`, `logger`, `thread`, `message`,
`exception` (the stack trace) and `context` (such as `player` and `session` on the lines about a
player), the last two only when present. Plugin loggers go through the same pipeline. The log files
under `logs/` stay in text. A plugin that forwards records elsewhere (Sentry, Loki) registers a
`LogSink` through `PluginContext.logSinks()`: it receives structured records on its own thread,
from a bounded queue that drops (and counts) records rather than slow down logging, and is removed
with its plugin.

`logging.connections` and `logging.pings` never turn a flood into a flood of log lines: while the
anti-bot reports an attack, neither is logged one by one. One line at the start of the attack says
so, and its summaries count the lines held back. Out of an attack, both apply as configured.

On each backend, set `online-mode=false` in `server.properties` and keep
`network-compression-threshold` equal to Warp's threshold (256). For Velocity forwarding, enable
Velocity support with the content of `forwarding.secret` as its secret, as described in
[Paper's guide](https://docs.papermc.io/velocity/player-information-forwarding).

Behind a load balancer that sends the PROXY protocol, enable it and list the load balancers'
addresses; Warp refuses to start with `enabled = true` and no trusted proxy:

```hocon
proxy-protocol {
  enabled = true
  trusted-proxies = ["10.0.0.0/8"]  # CIDR ranges or addresses, IPv4 or IPv6
}
```

Every connection must then come from a trusted proxy and start with a header (v1 or v2): a peer
outside the list is reset before Warp reads anything from it, and a trusted connection without a
valid header within 5 seconds is closed. Both are logged at most once a minute, with the count of
the others. Health checks can send a v2 `LOCAL` header (HAProxy's `check-send-proxy`); a plain TCP
check that opens and closes the connection is not logged either. A probe that connects from an
address outside the list, such as a kubelet `tcpSocket` probe from the node, is refused like any
other untrusted peer.

The `limits` section works out of the box; every key is documented in `warp.conf`. Each rate is
a burst, then one more per interval (an interval of 0 lifts it):

```hocon
limits {
  exempt = ["127.0.0.0/8", "::1/128"]              # never limited
  connections  { burst = 10, interval = 200ms }     # new TCP connections per address
  status-pings { burst = 10, interval = 1s }        # server list pings, apart from logins
  logins {
    burst = 2, interval = 3s                       # the rate of Velocity's login-ratelimit = 3000
    max-concurrent-per-address = 2
    max-concurrent-authentications = 256           # online mode, whole proxy
    timeout = 30s
  }
}
```

Behind the PROXY protocol, limits count the players' real addresses, not the load balancer's.
Loopback is exempt so that local tunnels and sidecars, behind which every player shares one
address, are not throttled as a single client; set `exempt = []` to limit loopback too.

For an alternative authentication server, or a test harness standing in for Mojang, two system
properties replace Mojang's services: `-Dmojang.sessionserver=<hasJoined URL>` (the property
Velocity reads), and `-Dwarp.profilekeys.signer=<file>`, the RSA public key (PEM or DER) trusted to
sign the chat signing keys of 1.19 to 1.19.2 players instead of Mojang's (online mode). Warp warns
at startup when either is set.

### Remote console

Where no terminal is at hand (Kubernetes, `docker run` without `-it`, panels, a fleet of proxies),
the management server can stream the log and run commands. It is off by default:

```hocon
management {
  enabled = true
  bind = "127.0.0.1:9901"
  console {
    enabled = true
    tokens = [
      { name = "ops", scopes = [logs, commands], file = "console-ops.token" }  # openssl rand -hex 32
      { name = "dashboard", scopes = [logs], env = "WARP_CONSOLE_DASHBOARD_TOKEN" }
    ]
  }
}
```

Attach with the proxy's own jar: the log renders as on the proxy's console (colours, highlighting),
and the prompt completes commands, hints at their arguments and keeps a history:

```bash
java -jar warp.jar console attach --url http://127.0.0.1:9901 --token-file console-ops.token
java -jar warp.jar console attach --url eu=https://eu.example:9901 --url us=https://us.example:9901 \
    --commands eu --level warn --tags=-netty     # a fleet: lines prefixed, commands to eu only
java -jar warp.jar console run --url http://127.0.0.1:9901 glist all   # one command, for scripts
```

The token comes from `--token-file` or `WARP_CONSOLE_TOKEN`, never from the command line. The client
reconnects on its own and resumes where it left off; a refused token is not retried.

- **Scoped tokens.** Each token has a name, which the audit log shows, and scopes: `logs` reads the
  stream, `commands` runs commands. Tokens have at least 32 characters, can be given as their
  SHA-256 (`sha256 = "..."`) so that the proxy never stores them, and can never be the drain token.
- **Lockout.** Tokens are compared in constant time. An address that sends `lockout-after` (5)
  wrong tokens is locked out for `lockout-duration` (15 minutes); IPv6 clients count by their /64.
  Commands, completions and open streams are rate-limited per token.
- **TLS or a trusted path.** Warp refuses to start if a token may run commands while the port
  listens beyond loopback in plain text. Set `management.tls { certificate, key }` (PEM, the key in
  PKCS#8; renewed files are read again, as cert-manager writes them), bind to `127.0.0.1` and come
  through `kubectl port-forward` or an SSH tunnel, or list in `trusted-networks` the ingress that
  terminates TLS in front of Warp. Probes then use `scheme: HTTPS`.
- **Audit.** Every command is logged with who ran it, from where (the client named by a trusted
  proxy's `X-Forwarded-For`) and the operator the client names; so are attachments and refusals.
- **No secrets in the stream.** The tokens, the drain token and the forwarding secret are masked
  wherever they appear, as is whatever follows `Bearer`, `token=` or `password=`.
- **The local console's look.** Lines keep their theme (`WARP_THEME` on the client's side), the
  code of each warning (refusals, lockouts and the client's own warnings have `W-RCON-...` codes,
  see [docs/errors.md](docs/errors.md)) and their links; file paths are not linked, as they name
  files of the proxy's machine.
- **`/log` from afar.** `/log level` runs on the proxy, for a token with `commands`; `/log filter`
  and `/log tail` act on what the client shows, whatever the token's scopes.

The endpoints are plain HTTP for other tools: `GET /console/logs` (Server-Sent Events; `?level=`,
`?tags=`, `?grep=`, `?replay=N`, `Last-Event-ID` to resume), `POST /console/command` (the command as
the body, or `{"command": ...}`; answers its output as JSON), `POST /console/suggest` and
`GET /console/commands`, each with `Authorization: Bearer <token>`.

## Project Structure

```
api/         Public plugin API (Adventure, Guice, Configurate, SLF4J)
protocol/    Minecraft protocol codec and packet definitions (Netty)
proxy/       Core proxy implementation, produces the runnable shadow JAR
jni/         Native bindings for compression and cryptography (placeholder)
e2e/         End-to-end tests: real clients through Warp to real servers
build-logic/ Gradle convention plugins (java, spotless, checkstyle, jacoco, jmh, publish)
```

Dependencies flow: `api <- protocol <- proxy`, `jni <- proxy`.

## For Plugin Developers

Warp has no plugin loader yet, and the API is in its first steps: it exposes the proxy version
(`Warp`, `WarpProvider`) and `ServerInfo`. Events, commands, server and player management and a
scheduler are planned. To explore it, publish the `api` module to your local Maven repository:

```bash
./gradlew :api:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("dev.warp:api:<version>")
}
```

The API is designed around:

- **Adventure** for text components and audiences
- **Guice** for dependency injection
- **Configurate** (HOCON) for plugin configuration
- **SLF4J** for logging (backed by Log4j 2 at runtime)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build setup, code style, commit conventions, CI, the
security checks, and the end-to-end and fuzz tests. Everyone taking part is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).

- [Report a bug](https://github.com/NeosiaNexus/Warp/issues/new?template=bug_report.yml)
- [Request a feature](https://github.com/NeosiaNexus/Warp/issues/new?template=feature_request.yml)
- [Ask a question](https://github.com/NeosiaNexus/Warp/discussions/categories/q-a)

## Security

Please do not report vulnerabilities in public issues. Use
[private vulnerability reporting](https://github.com/NeosiaNexus/Warp/security/advisories/new);
see the [security policy](SECURITY.md).

## License

Warp is free software, licensed under the [GNU Affero General Public License v3.0](LICENSE) or (at
your option) any later version.
