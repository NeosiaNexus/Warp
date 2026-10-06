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

## Continuous Integration

Every pull request runs the checks below. **CI OK** aggregates them: it is the one check that must
be green before merging.

| Check | What it verifies |
|---|---|
| **Build & test** | Spotless formatting, compilation with ErrorProne and NullAway, unit and integration tests, Checkstyle, JaCoCo coverage, shadow jar. Failed tests are annotated on the diff; the run summary shows test and coverage tables |
| **Lint workflows** | [actionlint](https://github.com/rhysd/actionlint) (with ShellCheck on `run:` scripts) and [zizmor](https://docs.zizmor.sh) at its strictest persona |
| **PR title** | Conventional Commits format (the title becomes the squash commit) |

Workflow conventions, enforced in review and by the linters:

- Third-party actions are pinned to a full commit SHA with the version in a comment; actions from
  this repository are referenced as `uses: $/.github/actions/…`.
- `permissions: {}` at the top of each workflow; each job asks for the least it needs, with a
  comment saying why.
- Every job has a `timeout-minutes`; runners are pinned (`ubuntu-24.04`), never `-latest`.
- Pull-request runs are cancelled by a newer push; runs on `main` always complete.

## Release Automation

[release-please](https://github.com/googleapis/release-please) maintains a release PR from the
Conventional Commits merged into `main`; merging it tags the release and attaches the jar and its
`SHA256SUMS`.

Pull requests opened with the default `GITHUB_TOKEN` wait for a manual approval before CI runs on
them, so the release workflow authenticates as a GitHub App when one is configured:

1. [Create a GitHub App](https://github.com/settings/apps/new) owned by the repository owner:
   - any unique name (e.g. `warp-release-<owner>`), homepage = the repository URL;
   - **Webhook**: untick *Active*;
   - **Repository permissions**: *Contents*, *Pull requests* and *Issues*: Read and write
     (*Metadata*: Read-only is implied);
   - **Where can this GitHub App be installed?** Only on this account.
2. Note the App's **Client ID**, then **Generate a private key** (a `.pem` file is downloaded).
3. **Install App** → *Only select repositories* → this repository.
4. Store the credentials in the repository:
   ```bash
   gh variable set RELEASE_APP_CLIENT_ID --body "<client id>"
   gh secret set RELEASE_APP_PRIVATE_KEY < path/to/private-key.pem
   ```
   then delete the local `.pem`.

Without these, the workflow falls back to `GITHUB_TOKEN` and CI on the release PR needs a manual
"Approve workflows to run".

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
