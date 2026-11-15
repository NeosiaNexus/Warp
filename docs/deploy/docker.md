# Running Warp with Docker

Warp publishes an official container image on every release and from every push to `main`:

| Image | Base | For |
|---|---|---|
| `ghcr.io/neosianexus/warp:<version>` | Eclipse Temurin 25 JRE on Ubuntu 24.04 (glibc) | Most deployments. |
| `ghcr.io/neosianexus/warp:<version>-alpine` | Eclipse Temurin 25 JRE on Alpine (musl) | A smaller image. Warp bundles libdeflate for musl too. |
| `ghcr.io/neosianexus/warp:edge`, `:edge-alpine` | The same bases | The latest commit on `main`, for trying what is not released yet. |

Both variants are built for `linux/amd64` and `linux/arm64`, run as user `warp` (uid 1000) with no
shell tools beyond the base image, and carry a SBOM and SLSA provenance in their manifest. A
stable release (no `-beta` suffix) is also tagged `latest` and `latest-alpine`.

## Quick start

```bash
docker run -d --name warp \
  -p 25565:25577 \
  -e WARP_LOBBY_ADDRESS=host.docker.internal:25566 \
  -e WARP_FORWARDING_SECRET="$(openssl rand -hex 32)" \
  -v warp-data:/data \
  ghcr.io/neosianexus/warp:latest
docker logs -f warp
```

The proxy listens on 25577 inside the container (published here as 25565), sends players to the
backend named by `WARP_LOBBY_ADDRESS`, and keeps what it writes under `/data` (the volume):
`warp.conf` on the first start, the logs, the anti-bot's store. Give the forwarding secret to every
backend (Paper: `proxies.velocity.secret` in `config/paper-global.yml`).

The container's command line is the jar's: `docker run --rm ghcr.io/neosianexus/warp:latest
console --help`, `... migrate --help`, `... health --help`.

## Configuration

Warp reads `/data/warp.conf`. On the first start, when the file does not exist, the image writes a
short one there, [docker/warp.conf](../../docker/warp.conf), which keeps every default of Warp's
reference configuration except these, and takes the values below from the environment when a
variable is set:

| Variable | Setting | Default in the image |
|---|---|---|
| `WARP_BIND` | `bind` | `0.0.0.0:25577` |
| `WARP_ONLINE_MODE` | `online-mode` | `true` |
| `WARP_LOBBY_ADDRESS` | `servers.lobby.address` | `lobby:25565` (a Compose service named `lobby`) |
| `WARP_DEFAULT_SERVER` | `default-server` | `lobby` |
| `WARP_FORWARDING_MODE` | `forwarding.mode` | `velocity` |
| `WARP_MANAGEMENT_BIND` | `management.bind` | `0.0.0.0:9901` (management on, metrics on) |
| `WARP_DRAIN_TRANSFER_TO` | `drain.transfer-to` | empty (players stay until they leave) |
| `WARP_DRAIN_TIMEOUT` | `drain.timeout` | `30m` |
| `WARP_PROXY_PROTOCOL` | `proxy-protocol.enabled` | `false` |
| `WARP_LOG_FORMAT` | `logging.format` | `text` (set `json` for a log collector) |

Secrets come from the environment too, never from a file in the image: `WARP_FORWARDING_SECRET`
(else `/data/forwarding.secret`, generated on the first start), `WARP_MANAGEMENT_TOKEN` (the token
`POST /drain` requires), `WARP_ANTIBOT_SECRET` and `WARP_ANTIBOT_TOKEN_SECRET` (to share the
anti-bot's keys between proxies). Docker reads them from a file with `--env-file`, Compose with
`secrets:` or `env_file:`.

For anything else (groups, routes, limits, the anti-bot, the remote console), mount your own
`warp.conf` at `/data/warp.conf`: Warp's reference configuration documents every key, and Warp
writes it for you when you start it in an empty directory. Your file may use the same
`key = ${?VARIABLE}` form to take values from the environment.

Plugins go under `/data/plugins`, message overrides under `/data/messages`.

### JVM flags

The image's `WARP_JVM_OPTS` size the heap at half of the container's memory limit
(`-XX:MaxRAMPercentage=50`; Netty's direct buffers, the metaspace and the threads take the other
half), run ZGC (sub-millisecond pauses, generational since JDK 24), exit on an `OutOfMemoryError`
rather than linger, and enable `sun.misc.Unsafe` access for Netty on Java 25. A proxy for a few
hundred players is comfortable with `--memory 1g`. Add flags with `JAVA_OPTS` (`-e
JAVA_OPTS=-Xlog:gc`), or replace them all with `WARP_JVM_OPTS`.

### Permissions

The proxy runs as uid 1000. A named volume (`-v warp-data:/data`) is owned correctly from the
start. A bind mount must be writable by that uid: `chown -R 1000:1000 ./warp` on the host, or run
with `--user "$(id -u):$(id -g)"` (then `/data` must be writable by you and Warp logs as that
user). A read-only root filesystem works (`--read-only --tmpfs /tmp`): everything Warp writes goes
under `/data`, and this platform's libdeflate is installed in the image, so nothing is extracted
at start.

## Health check

The image's `HEALTHCHECK` runs `java -jar warp.jar health`, which asks the management server
(there is no curl in the image) and exits 0 when the proxy answers 2xx. By default it asks
`/startupz`, which answers 200 once the proxy listens and its plugins are enabled, and keeps doing
so while the proxy drains, so that `depends_on: condition: service_healthy` holds in Compose.
`WARP_HEALTH_URL=http://127.0.0.1:9901/readyz` turns the container unhealthy while it drains
instead.

The same command works from outside: `docker exec warp /opt/warp/entrypoint.sh health`.

## Stopping without disconnecting anyone

`docker stop` sends SIGTERM, and the image's configuration (`drain.on-sigterm = true`) makes Warp
drain instead of stopping at once:

1. `/readyz` answers 503 and the HAProxy agent check says `drain`, for `drain.propagation-delay`
   (5 s): time for a load balancer to stop sending new players here.
2. Players on a 1.20.5+ client are transferred to `drain.transfer-to` (your public address: the
   load balancer or the DNS name players type, so that they land on another proxy), one every
   `transfer-interval`. Without `transfer-to`, or on an older client, players stay (or are kicked,
   with `cannot-transfer = kick`).
3. Warp waits for the players kept to leave, at most `drain.timeout` (30 min), disconnects whoever
   is left, and exits: code 0 once empty, 2 when the time-out expired.

Docker kills a container ten seconds after SIGTERM unless told otherwise. Give the drain its time:

```bash
docker stop -t 1830 warp          # propagation-delay + timeout + a margin
```

or `stop_grace_period: 31m` in Compose. A second SIGTERM (`docker stop` again) stops at once.
`docker exec warp /opt/warp/entrypoint.sh drain` starts the drain without stopping the container,
with `WARP_MANAGEMENT_TOKEN` set; add `--wait` to return once every player left.

## Docker Compose: a proxy and two backends

```yaml
# compose.yaml
services:
  warp:
    image: ghcr.io/neosianexus/warp:latest
    ports:
      - "25565:25577"
    environment:
      WARP_DRAIN_TRANSFER_TO: play.example.com   # your public address, for the drain's transfers
      WARP_LOG_FORMAT: json
    env_file: warp.env                            # WARP_FORWARDING_SECRET=..., WARP_MANAGEMENT_TOKEN=...
    volumes:
      - ./warp.conf:/data/warp.conf:ro            # the configuration below
      - warp-data:/data
    stop_grace_period: 31m                        # the drain's time-out, with a margin
    depends_on:
      lobby:
        condition: service_healthy
    restart: unless-stopped

  lobby:
    image: itzg/minecraft-server:java21
    environment: &paper
      EULA: "true"
      TYPE: PAPER
      ONLINE_MODE: "false"                        # Warp authenticates the players
      MEMORY: 2G
      # Velocity modern forwarding, with Warp's secret (the same value as warp.env)
      PAPER_VELOCITY_ENABLED: "true"            # itzg sets proxies.velocity.* in paper-global.yml
      PAPER_VELOCITY_ONLINE_MODE: "true"
    env_file: warp.env                            # PAPER_VELOCITY_SECRET=${WARP_FORWARDING_SECRET} in this file
    volumes:
      - lobby-data:/data
    restart: unless-stopped

  survival:
    image: itzg/minecraft-server:java21
    environment:
      <<: *paper
      LEVEL_TYPE: DEFAULT
    env_file: warp.env
    volumes:
      - survival-data:/data
    restart: unless-stopped

volumes:
  warp-data:
  lobby-data:
  survival-data:
```

```hocon
# warp.conf
config-version = 1
bind = "0.0.0.0:25577"

servers {
  lobby { address = "lobby:25565" }
  survival { address = "survival:25565" }
}
default-server = "lobby"
fallback-order = ["lobby"]

management {
  enabled = true
  bind = "0.0.0.0:9901"      # inside the Compose network only: never publish this port
}

drain {
  on-sigterm = true
  transfer-to = ${?WARP_DRAIN_TRANSFER_TO}
}
```

Keep `network-compression-threshold` at 256 on the backends (Paper's default), the same as Warp's
`compression.threshold`, so that compressed frames pass through unchanged. `docker compose
up -d`, then `docker compose logs -f warp`: the startup summary lists both backends as reachable.

The management port stays inside the Compose network. To read the metrics or attach the console
from the host, publish it on loopback only (`"127.0.0.1:9901:9901"`), or run the console client in
the network: `docker compose exec -e WARP_CONSOLE_TOKEN=... warp /opt/warp/entrypoint.sh console
attach --url http://127.0.0.1:9901` (with `management.console` enabled in `warp.conf`).

## Verifying an image

Every image is signed with cosign (keyless, by the release workflow's identity) and attested on
the repository:

```bash
cosign verify ghcr.io/neosianexus/warp:<version> \
  --certificate-identity-regexp 'https://github.com/NeosiaNexus/Warp/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify oci://ghcr.io/neosianexus/warp:<version> --repo NeosiaNexus/Warp
docker buildx imagetools inspect ghcr.io/neosianexus/warp:<version> --format '{{ json .SBOM }}'
```

## Building the image yourself

```bash
./gradlew :proxy:shadowJar                                       # this platform's libdeflate only
docker build --build-arg JAR_SOURCE=prebuilt -t warp .           # from proxy/build/libs/warp-*.jar
docker build -t warp .                                           # or build the jar inside (slower)
docker build --build-arg JAR_SOURCE=prebuilt \
  --build-arg BASE_IMAGE=eclipse-temurin:25-jre-alpine -t warp:alpine .
```

CI runs the same smoke test on every pull request
([.github/scripts/docker-smoke.sh](../../.github/scripts/docker-smoke.sh)): start, probes, metrics,
a server list ping, a console command, a drain through the API, a clean exit on SIGTERM, then a
Trivy scan that fails on fixed critical or high vulnerabilities.
