# Warp

[![CI](https://github.com/NeosiaNexus/Warp/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/NeosiaNexus/Warp/actions/workflows/ci.yml?query=branch%3Amain)
[![E2E matrix](https://github.com/NeosiaNexus/Warp/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/NeosiaNexus/Warp/actions/workflows/e2e.yml?query=branch%3Amain)
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
> join through it today (online or offline mode) and play; on 1.20.2 and newer clients they can
> also switch servers and fall back when a server fails. The plugin API, operations features and
> the rest of the [roadmap](#roadmap) are not built yet. Releases are `0.1.0-beta` pre-releases;
> see the [changelog](CHANGELOG.md).

## Features

### Available today

**Proxying**

- **Online mode**: Mojang authentication and AES/CFB8 encryption. The session server call runs on a
  virtual thread and never blocks the event loop. Offline mode is available for development.
- **Player info forwarding**: [Velocity modern forwarding](https://docs.papermc.io/velocity/player-information-forwarding)
  (HMAC-SHA256 signed, Minecraft 1.13+ backends such as Paper), or none.
- **Several backend servers**: `/server` lists them (1.19.3+ clients) and switches between them.
  Switching uses the configuration phase, so it needs 1.20.2+ clients.
- **Fallback** (1.20.2+ clients): when the server a player joins or switches to is unreachable or
  refuses the login, or kicks them during play, Warp tries the servers of `fallback-order` in turn
  before disconnecting the player. Older clients are disconnected instead.
- **Server list ping** answered by Warp itself (a fixed response for now).

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
- **Server switching and fallback for clients older than 1.20.2.**
- **Configurable server list ping** (MOTD, player counts).
- **Native compression and cryptography** (libdeflate, AES): `jni/` is a placeholder today.
- **Plugin API on Maven Central.**

## Tested on every Minecraft version

Unit tests check that each piece does what its author meant. The
[end-to-end suite](e2e/README.md) checks that a player can actually play: real-protocol bots go
through Warp to real Paper or vanilla servers for every protocol from 1.8 to 26.3. They join,
receive chunks, switch servers (1.20.2+), survive a fallback and stay connected. A run also fails
if Warp logs an error, leaks a Netty buffer, or a backend drops a connection with a protocol error.

| When | What runs |
|---|---|
| Every pull request | One version per era, plus the online, offline and compression variants on the reference version |
| Every push to `main`, nightly, on demand, and pull requests labelled `e2e: full` | Every protocol of the matrix |

Versions Warp does not fully support yet stay in the matrix as *known broken*: they run and are
reported without failing CI, and CI says when one starts passing. The
[E2E matrix badge](https://github.com/NeosiaNexus/Warp/actions/workflows/e2e.yml?query=branch%3Amain)
and [`e2e/versions.json`](e2e/versions.json) are the current answer to "does my version work?".

## Getting Started

### Requirements

- **To run Warp**: Java 25 or newer ([Adoptium Temurin](https://adoptium.net/) recommended).
- **To build it**: Git, and JDK 21 to run Gradle (CI uses 21; Gradle 8.12 runs on JDK 23 at most,
  not on 24 or newer). Gradle compiles with JDK 25 through its toolchain support and downloads one
  if none is installed.

### Build from source

```bash
git clone https://github.com/NeosiaNexus/Warp.git
cd Warp
./gradlew build
```

The shadow JAR is produced at `proxy/build/libs/warp-<version>.jar`. Pre-built jars are attached
to the [releases](https://github.com/NeosiaNexus/Warp/releases); releases after 0.1.0-beta.5 also
come with a `SHA256SUMS` file and a signed provenance attestation. During the beta, `main` moves
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
Java 25 or newer (not the JDK 21 that runs Gradle). Without the scripts:

```bash
java --sun-misc-unsafe-memory-access=allow -jar /path/to/warp-<version>.jar
```

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
```

On each backend, set `online-mode=false` in `server.properties` and keep
`network-compression-threshold` equal to Warp's threshold (256). For Velocity forwarding, enable
Velocity support with the content of `forwarding.secret` as its secret, as described in
[Paper's guide](https://docs.papermc.io/velocity/player-information-forwarding).

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

See [CONTRIBUTING.md](CONTRIBUTING.md) for build setup, code style, commit conventions, CI and the
end-to-end tests. Everyone taking part is expected to follow the
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
