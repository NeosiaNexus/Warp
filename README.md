# Warp

[![CI](https://github.com/NeosiaNexus/warp/actions/workflows/ci.yml/badge.svg)](https://github.com/NeosiaNexus/warp/actions/workflows/ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://adoptium.net/)

A high-performance, cloud-native Minecraft proxy built on Java 25 and Netty.

## Why Warp

Velocity solved BungeeCord's problems. Warp is designed to solve Velocity's.

| Problem | How Warp addresses it |
|---|---|
| **Decompresses packets it never reads** | Blind forwarding — ~90% of PLAY-state packets are forwarded as raw bytes, skipping decompression and deserialization entirely |
| **No graceful drain** | First-class multi-phase drain with Transfer packet support, designed for rolling deployments and Kubernetes |
| **Shallow plugin DI** | Full dependency-injection graph via Guice with lifecycle hooks (provision, validate, start, ready, drain, stop) |
| **No health probes** | Built-in `/livez`, `/readyz`, `/startupz` endpoints, Prometheus metrics, and OpenTelemetry tracing |
| **Undefined event ordering** | Deterministic, priority-based event dispatch — no race conditions between handlers |

## Status

> **Pre-release** — Warp is under active development. The protocol foundation and build infrastructure are in place. Core proxy features (connection handling, server switching, blind forwarding) are being implemented. Not ready for production use.

## Features

- **Java 25 LTS** — virtual threads, sealed interfaces, pattern matching, generational ZGC
- **Netty 4.2** — native epoll/kqueue transports, BoringSSL, zero-copy where possible
- **Modular architecture** — clean separation between API, protocol, proxy, and native bindings
- **Production-grade build** — ErrorProne + NullAway (null safety at compile time), Checkstyle, Spotless, JaCoCo
- **Automated releases** — conventional commits, release-please, SemVer with pre-release support

## Getting Started

### Requirements

- JDK 25+ ([Adoptium Temurin](https://adoptium.net/) recommended)
- Git

### Build from source

```bash
git clone https://github.com/NeosiaNexus/warp.git
cd warp
./gradlew build
```

The shadow JAR is produced at `proxy/build/libs/warp-<version>.jar`.

### Run

```bash
java -jar proxy/build/libs/warp-*.jar
```

## Project Structure

```
api/         Public plugin API (Adventure, Guice, Configurate, SLF4J)
protocol/    Minecraft protocol codec and packet definitions (Netty)
proxy/       Core proxy implementation — produces the runnable shadow JAR
jni/         Native bindings for compression and cryptography
build-logic/ Gradle convention plugins (java, spotless, checkstyle, jacoco, publish)
```

Dependencies flow: `api <- protocol <- proxy`, `jni <- proxy`.

## For Plugin Developers

Warp's plugin API will be published to Maven Central. Until then, build from source and depend on the `api` module:

```kotlin
dependencies {
    compileOnly(project(":api"))
}
```

The API is designed around:
- **Adventure** for text components and audiences
- **Guice** for dependency injection
- **Configurate** (HOCON) for plugin configuration
- **SLF4J** for logging (backed by Log4j 2 at runtime)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build setup, code style, commit conventions, and quality gates.

**Quick links:**
- [Bug report](https://github.com/NeosiaNexus/warp/issues/new?template=bug_report.yml)
- [Feature request](https://github.com/NeosiaNexus/warp/issues/new?template=feature_request.yml)
- [Security vulnerability](https://github.com/NeosiaNexus/warp/security/advisories/new)

## License

[GNU Affero General Public License v3.0](LICENSE)
