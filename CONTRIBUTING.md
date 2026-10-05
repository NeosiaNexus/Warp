# Contributing to Warp

Thanks for your interest in contributing. This document covers everything you need to get started.

## Getting Started

**Requirements:**
- JDK 25+
- Git

**Build & test:**

```bash
git clone git@github.com:NeosiaNexus/warp.git
cd warp
./gradlew build
```

This compiles all modules, runs tests, checks formatting (Spotless), and produces the shadow JAR at `proxy/build/libs/warp-*.jar`.

## Development Workflow

1. Fork the repo and create a branch from `main`
2. Make your changes
3. Run `./gradlew build` to verify everything passes
4. Run `./gradlew spotlessApply` if formatting is off
5. Open a PR toward `main`

### Commit Messages

We use [Conventional Commits](https://www.conventionalcommits.org/). Your **PR title** must follow this format — it becomes the commit message on `main` after squash merge.

```
type(scope): description
```

| Type | When to use |
|---|---|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `perf` | Performance improvement |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `build` | Build system or dependency changes |
| `ci` | CI/CD changes |
| `chore` | Maintenance tasks |
| `revert` | Reverts a previous commit |

**Scopes** match module names: `api`, `protocol`, `proxy`, `jni`, `build`, `deps`, `project`.

### Code Style

- **Formatting** is enforced by Spotless (Google Java Format). Run `./gradlew spotlessApply` — never format manually.
- **Null safety** — use `@NullMarked` at package level, `@Nullable` on individual fields/params (JSpecify).
- **Javadoc** — all public types and methods must have Javadoc with `@param` and `@return` tags. Checkstyle enforces this.
- **Imports** — four groups separated by blank lines: `dev.warp` | `java` | `javax` | everything else. Spotless handles ordering.

### Testing

- JUnit 5 + Mockito
- Unit tests in `src/test/java/`, integration tests in `src/integrationTest/java/`
- All public API changes must include tests

## Code Quality Gates

Every PR must pass these automated checks before merge:

| Check | What it verifies |
|---|---|
| **Spotless** | Code formatting (Google Java Format) |
| **Checkstyle** | Naming, Javadoc, design conventions |
| **ErrorProne** | Common bug patterns |
| **NullAway** | Null safety violations |
| **JUnit** | All tests pass |
| **PR Title** | Conventional Commits format |

## Architecture

```
api/        Public plugin API — stable contract for plugins
protocol/   Minecraft protocol codec and packets
proxy/      Core proxy implementation (shadow JAR)
jni/        Native bindings (compression, crypto)
build-logic/ Gradle convention plugins
```

Dependencies flow: `api ← protocol ← proxy`, `jni ← proxy`. Never add reverse dependencies.

## License

By contributing, you agree that your contributions will be licensed under the [AGPL-3.0](LICENSE). All Java files must include the license header — Spotless adds it automatically via `./gradlew spotlessApply`.
