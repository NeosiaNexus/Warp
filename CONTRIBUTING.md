# Contributing to Warp

Thanks for your interest in contributing. This document covers everything you need to get started.

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Security
vulnerabilities are reported privately, never in an issue: see the [security policy](SECURITY.md).

## Getting Started

**Requirements:**
- Git
- JDK 17 or newer to run Gradle (CI uses 21). Gradle compiles and tests with JDK 25 through its
  toolchain support, and downloads one if none is installed.
- Node.js 22+, only for the [end-to-end tests](#end-to-end-tests)

**Build & test:**

```bash
git clone git@github.com:NeosiaNexus/Warp.git
cd Warp
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

We use [Conventional Commits](https://www.conventionalcommits.org/). Your **PR title** must follow this format: it becomes the commit message on `main` after squash merge.

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

- **Formatting** is enforced by Spotless (Google Java Format). Run `./gradlew spotlessApply`, never format manually.
- **Null safety**: use `@NullMarked` at package level, `@Nullable` on individual fields/params (JSpecify).
- **Javadoc**: all public types and methods must have Javadoc with `@param` and `@return` tags. Checkstyle enforces this.
- **Imports**: four groups separated by blank lines: `dev.warp` | `java` | `javax` | everything else. Spotless handles ordering.

### Testing

- JUnit 5 + Mockito
- Unit tests in `src/test/java/`, integration tests in `src/integrationTest/java/`
- All public API changes must include tests

## Continuous Integration

Every pull request runs the checks below. **CI OK** aggregates the build, the end-to-end tests and
the workflow lint into one check. It must be green before merging, as must **Conventional Commits**
and the security checks of the [next section](#security-and-dependencies).

| Check | What it verifies |
|---|---|
| **Build & test** | Spotless formatting, compilation with ErrorProne and NullAway, unit and integration tests, Checkstyle, JaCoCo coverage, shadow jar. Failed tests are annotated on the diff; the run summary shows test and coverage tables |
| **E2E** | Real clients through Warp to real servers: one Minecraft version per era, or every version when the pull request changes Warp's modules or the end-to-end harness (see [End-to-end tests](#end-to-end-tests)) |
| **Lint workflows** | [actionlint](https://github.com/rhysd/actionlint) (with ShellCheck on `run:` scripts) and [zizmor](https://docs.zizmor.sh) at its strictest persona |
| **Conventional Commits** | The PR title's format (the title becomes the squash commit) |

Pull requests that only touch documentation skip the build and the end-to-end tests.

Workflow conventions, enforced in review and by the linters:

- Third-party actions are pinned to a full commit SHA with the version in a comment; actions from
  this repository are referenced as `uses: $/.github/actions/…`.
- `permissions: {}` at the top of each workflow; each job asks for the least it needs, with a
  comment saying why.
- Every job has a `timeout-minutes`; runners are pinned (`ubuntu-24.04`), never `-latest`.
- Pull-request runs are cancelled by a newer push; runs on `main` always complete.

## Security and Dependencies

Pull requests also run these checks, each in its own workflow and outside **CI OK**:

| Check | What it verifies |
|---|---|
| **CodeQL** | Static analysis with the `security-and-quality` queries: the Java code as the build compiles it (every source set), the workflows and actions, and any JavaScript or Python. Alerts land in the Security tab; on a pull request, code scanning reports the ones it introduces |
| **Dependency review** | Fails on a known vulnerability (moderate or above) in any dependency the pull request adds or changes, and on a runtime dependency whose license [`.github/dependency-review-config.yml`](.github/dependency-review-config.yml) does not allow |
| **Dependency graph** | Resolves every Gradle dependency, transitive ones included, for the dependency review (and, on `main`, for Dependabot alerts) |

**CodeQL** (its `actions` and `java-kotlin` analyses, which run on every pull request) and
**Dependency review** must pass before merging. Code scanning also blocks a pull request that
introduces a CodeQL alert of error severity or a security alert of high or critical severity.

[OpenSSF Scorecard](https://scorecard.dev) grades the repository's supply-chain practices on every
push to `main` and weekly; its findings land in the Security tab too.

[Dependabot](.github/dependabot.yml) proposes updates every Monday for Gradle (version catalog,
settings plugins and wrapper), GitHub Actions and the e2e harness's npm packages, once a release is
a week old; security updates open right away. Minor and patch updates come grouped for related
libraries (Netty, Log4j, Adventure, testing, code quality), for all actions and for all npm
packages; any other update, majors included, gets a pull request of its own (action majors share
one). Titles are Conventional Commits that land in the matching changelog section: `fix(deps)` for
Gradle (build tools included), `ci(deps)` for actions, `test(deps)` for npm.

## End-to-end tests

[`e2e/`](e2e/README.md) runs real-protocol bots through Warp to real Paper or vanilla servers and
checks that players can join, play, chat, switch servers and fall back, that the servers' brand
and resource packs reach them, and that backends know them by the identity Warp forwards, for
every protocol from 1.8 to 26.3. Locally (Node.js 22+; servers, their JDKs and ViaProxy are
downloaded and cached on first use, and Warp's jar is built if needed):

```bash
e2e/run.sh --mc 1.21.4                                   # one version
e2e/run.sh --mc 1.8.8,1.20.2 --variants online,offline   # several versions and variants
e2e/run.sh --mc 1.21.4 --variants velocity               # Velocity modern forwarding
e2e/run.sh --list                                        # the whole matrix
```

| Where | What runs |
|---|---|
| Every pull request (**E2E**, part of **CI OK**) | One version per era, plus the online, offline, compression and Velocity forwarding variants on the reference version |
| Pull requests that change Warp's modules (`api/`, `protocol/`, `proxy/`, `jni/`) or the end-to-end harness, tests aside (**E2E**, part of **CI OK**; the paths are in [`.github/actions/changes`](.github/actions/changes/action.yml)) | Every protocol of the matrix instead, with more variants at era boundaries |
| Every push to `main`, nightly, on demand (`e2e.yml`) | Every protocol of the matrix |
| Pull requests labelled `e2e: full` | The whole matrix, for a pull request CI tests on one version per era only (a dependency, build or workflow change, say); ask a maintainer if you cannot set labels. On a pull request that already runs the whole matrix in CI, the label changes nothing |

Versions marked `knownBroken` in `e2e/versions.json` run and are reported without failing CI. To
add a Minecraft version, follow
[Adding a Minecraft version](e2e/README.md#adding-a-minecraft-version).

## Release Automation

[release-please](https://github.com/googleapis/release-please) maintains a release PR from the
Conventional Commits merged into `main`; merging it tags the release and attaches the jar, its
`SHA256SUMS` and its signed provenance.

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
4. Store the credentials in the `release` environment (usable from `main` only):
   ```bash
   gh variable set RELEASE_APP_CLIENT_ID --env release --body "<client id>"
   gh secret set RELEASE_APP_PRIVATE_KEY --env release < path/to/private-key.pem
   ```
   then delete the local `.pem`.

Without these, the workflow falls back to `GITHUB_TOKEN` and CI on the release PR needs a manual
"Approve workflows to run".

Every released jar carries a signed SLSA build provenance attestation, stored on the repository and
attached to the release as `warp-<version>.jar.intoto.jsonl` (a Sigstore bundle):

```bash
gh attestation verify warp-<version>.jar --repo NeosiaNexus/Warp
# or against the attached bundle, without querying the attestations API:
gh attestation verify warp-<version>.jar --repo NeosiaNexus/Warp --bundle warp-<version>.jar.intoto.jsonl
```

## Architecture

```
api/        Public plugin API, the stable contract for plugins
protocol/   Minecraft protocol codec and packets
proxy/      Core proxy implementation (shadow JAR)
jni/        Native bindings (compression, crypto)
e2e/        End-to-end tests (Node.js harness)
build-logic/ Gradle convention plugins
```

Dependencies flow: `api ← protocol ← proxy`, `jni ← proxy`. Never add reverse dependencies.

## License

By contributing, you agree that your contributions will be licensed under the [AGPL-3.0](LICENSE). All Java files must include the license header; Spotless adds it automatically via `./gradlew spotlessApply`.
