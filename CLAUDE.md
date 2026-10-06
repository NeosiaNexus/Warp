# Warp Proxy

High-performance Minecraft proxy in Java 25. Core innovation: **blind forwarding**, which skips decompression/deserialization for ~90% of PLAY-state packets.

## Build & Run

```bash
./gradlew build              # compile + test + spotlessCheck + shadowJar
./gradlew test               # unit tests only
./gradlew integrationTest    # integration tests only
./gradlew spotlessApply      # auto-format all sources
./gradlew spotlessCheck      # verify formatting (CI gate)
```

Shadow JAR output: `proxy/build/libs/warp-<version>.jar`

## Project Structure

```
api/        Public plugin API (Adventure, Guice, Configurate, SLF4J)
protocol/   Minecraft protocol codec and packet definitions (Netty)
proxy/      Core proxy implementation (entry point: WarpBootstrap)
jni/        Native bindings for compression and cryptography
build-logic/ Gradle convention plugins (java, spotless, publish)
```

## Architecture Rules

- **api/** is the public contract. No internal types leak here. Plugins depend only on this.
- **protocol/** owns packet definitions and codec. Depends on api/.
- **proxy/** is the implementation. Depends on everything. Produces the shadow JAR.
- **jni/** is standalone native bindings. No dependency on api/ or protocol/.
- Cross-module dependency direction: `api <- protocol <- proxy`, `jni <- proxy`

## Code Conventions

- **Java 25**: use records, sealed interfaces, pattern matching where appropriate
- **Null safety**: `@NullMarked` at package level (package-info.java), `@Nullable` on individual fields/params. JSpecify annotations, enforced by NullAway at compile time
- **New packages** must have a `package-info.java` with `@NullMarked` annotation and a one-line Javadoc
- **License header**: AGPL-3.0 header on every Java file. Spotless enforces via `config/license-header.txt`. Do not write headers manually, run `spotlessApply`
- **Formatting**: Google Java Format via Spotless. Never format manually
- **Imports**: four groups separated by blank lines: `dev.warp` | `java` | `javax` | everything else. Spotless enforces this
- **Logging**: SLF4J facade: `private static final Logger logger = LoggerFactory.getLogger(X.class);`
- **Constants**: `UPPER_SNAKE_CASE`, always `static final`
- **Thread safety**: `volatile` for shared mutable state, `final` wherever possible, immutable collections for public returns
- **Utility classes**: `final` class + `private` constructor
- **Section separators** in large classes:
  ```java
  // ---------------------------------------------------------------------------
  // Section name
  // ---------------------------------------------------------------------------
  ```

## Testing

- JUnit 5 + Mockito. Test classes mirror source structure.
- `@DisplayName` for readability, `@Nested` for grouping scenarios
- Integration tests go in `src/integrationTest/java/`
- All public API must have tests before merge

## Git Workflow

- **Conventional Commits** enforced on PR titles: `type(scope): description`
- **Squash merge only**: PR title becomes the commit on main
- **release-please** automates versioning from conventional commits
- Version lives in `version.txt` (single source of truth)
- See `docs/research/` for design decisions and research findings

## Performance

- Hot path = packet forwarding. Zero allocations, no blocking, no locks.
- Use Netty ByteBuf directly and avoid unnecessary copies or wrapping
- Blind packets (unregistered) = raw ByteBuf passthrough, never deserialized
- Profile before optimizing. Benchmarks live in integration tests.
