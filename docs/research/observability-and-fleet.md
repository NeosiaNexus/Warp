# Observability and fleet: metrics, GS4 query, multi-proxy

Status: design, 2026-11-09. Research plus decisions, no code yet.

This document supersedes section 1 (metrics) and sections 9 and 10 (comparison,
plan) of [observability-architecture.md](observability-architecture.md). That
older note stays as background for its other sections (admin API, health
checking, structured logging, tracing, JFR, SLOs); where the two disagree, this
document wins. The main reversal: no Micrometer. Warp ships its own small,
allocation-free metric registry, for reasons given in section 2.4.

Three gaps are closed here:

1. Metrics: Prometheus exposition on the existing management server, optional
   OTLP push, a plugin API, and a hot path that costs one striped counter add.
2. GS4 Query: the UDP server-list protocol, shipped behind config with a strict
   challenge, bounded state and rate limits.
3. Fleet: several Warp proxies acting as one network (global player list,
   cross-proxy messages and commands, duplicate-login policy, drain
   coordination) through a pluggable store, Valkey first.

Terminology: "node" is one running Warp proxy; "network" is the set of nodes
sharing one fleet store; "server" is a registered backend.

---

## 1. What Warp has today

Read from `proxy/src/main/java/dev/warp/proxy` on main at e699d6e.

| Area | Today | Relevant to |
|------|-------|-------------|
| Management HTTP server (`management/ManagementServer`, `ManagementHttpHandler`) | Own Netty HTTP/1.1 server, default bind `127.0.0.1:9901`, optional TLS (`management.tls`), bearer token from `management.token-file` or `WARP_MANAGEMENT_TOKEN`. Routes: `/livez`, `/startupz`, `/readyz`, `/drain` (GET status, POST needs token), `/antibot`, `/antibot/blocklist`, `/antibot/lockdown` (POST needs token), `/metrics`, `/console/**`. GET routes need no token today. Request limits: 4 KiB line, 8 KiB headers, 4 KiB body, 10 s read timeout. | Metrics exposition, query status snapshot, fleet endpoints |
| `/metrics` (`management/AntiBotEndpoints.metrics`) | Hand-written Prometheus text for the anti-bot only: `warp_antibot_level`, `warp_antibot_lockdown`, `warp_antibot_verified_identities`, `warp_antibot_signal_per_second{signal}`, `warp_antibot_signal_baseline{signal}`, `warp_connections_pending`, `warp_connections_refused_total{reason}`, `warp_auth_in_flight`, `warp_auth_lane_waiting`, `warp_auth_lane_wait_seconds` (sum and count), `warp_antibot_blocklisted`. Built from an `AntiBotStatus` snapshot record, so no hot-path cost beyond what the anti-bot already counts. | Becomes one collector among many; names are kept |
| Agent check (`management/AgentCheckHandler`) | HAProxy agent-check style TCP line for load balancers. | Fleet: external LB weight can use fleet state later |
| Remote console (`management/console`, `remote/`) | HTTP plus SSE, scoped tokens with lockout (`ConsoleAuth`), log history and streaming, command tree JSON. | Fleet admin commands run through the same console |
| Drain (`drain/`, `api/lifecycle/DrainStatus`) | Phased drain with transfer target from `DrainConfig` (host:port), `ProxyDrainEvent`, `PlayerDrainEvent`, status over `/drain`. `DrainStatus(phase, trigger, startedAt, elapsed, players, transferred, ...)`. | Metrics (drain gauges), fleet (pick a live peer as transfer target) |
| Anti-bot (`antibot/`, `api/antibot`) | `AntiBotStatus` snapshot, `VerifiedStore` SPI (`load`, `save`, `delete`) already pluggable, `sharedStore` flag. | Precedent for a store SPI; counters reused |
| Logging (`logging/`, `api/logging`) | `LogCode` markers, JSON layout, `LogSink` plugin API with subscriptions. | Metrics for sink drops; fleet log codes |
| `/warp` command (`command/WarpCommand`) | `warp` (version, Java, compression, memory), `warp plugins`, `warp startup`. Localised `Message` keys. | Gains `warp metrics`, `warp fleet`, `warp find`, `warp send`, `warp glist` |
| Config | HOCON `warp.conf`, sections include `status` (motd, max-players, show-player-count, version-name, favicon), `duplicate-login` (`AUTO`, `KICK_EXISTING`, `REFUSE_NEW`), `accepts-transfers`, `management`, `anti-bot`, `drain`, `limits`. | Query reuses `status`; fleet reuses `duplicate-login` |
| Player API (`api/player/Player`) | `transfer(host, port)` with `TransferResult`, `disconnect(Component)`, `currentServer()`. | Fleet send and drain |
| Events (`api/event`) | `LoginEvent`, `PostLoginEvent`, `DisconnectEvent`, `ServerConnectedEvent`, `ProxyPingEvent`, `ProxyDrainEvent`, `PlayerDrainEvent`. | Fleet presence hooks, query snapshot |

No general metric registry, no event loop or forwarding counters, no UDP
listener, no notion of another proxy.

---

## 2. Metrics

### 2.1 What competitors do

| Proxy or server | Native metrics | Notes and issues |
|-----------------|----------------|------------------|
| Velocity | None. bStats telemetry only. | Issue search for "prometheus OR metrics" returns no exporter work; only bStats PRs (#421) and "bStats' random intervals are too random" (#476, closed out of scope) [S8]. Community exporter plugins exist but the two most-cited repositories now return 404, so none is a dependable reference. Users are left to write their own. |
| BungeeCord | None. | Same model as Velocity. |
| Gate | OpenTelemetry push only. `OTEL_EXPORTER_OTLP_ENDPOINT` (default `localhost:4317`, gRPC), `OTEL_EXPORTER_OTLP_METRICS_PERIOD` 30 s, configured by environment, not the YAML [S1, S2]. | No scrape endpoint, so a plain Prometheus setup needs a collector in between. The guide lists no metric names; the sample dashboard shows only `proxy_player_count` and Go runtime gauges and tells users to build their own [S1]. No published per-server or per-outcome metrics. |
| Paper (plugins) | `minecraft-prometheus-exporter`: `/metrics` on `localhost:9940`, `mc_players_online_total`, `mc_tps`, `mc_tick_duration_*`, `mc_jvm_*`, optional per-player series [S5]. Spark: TPS, MSPT, CPU, memory, GC, ping, from JMX and `/proc`, no export format [S7]. | The exporter README states per-player metrics are "against Prometheus best-practices" and create a series "for each player that has ever been seen"; open bugs include "/ by zero collecting TickDurationAverage" (#310) and "Failed to read server statistic: null" (#314) [S5, S6]. Spark notes CPU and memory are misreported in containers [S7]. |

Lessons: ship scrape and push, never per-player series, publish the metric
catalogue with the release, read container limits not host values.

### 2.2 Non-Minecraft references

- Envoy: `cluster.<name>.upstream_cx_total`, `upstream_cx_active`,
  `upstream_cx_connect_fail`, `upstream_cx_rx_bytes_total`,
  `upstream_cx_connect_ms`, `outlier_detection.ejections_active`,
  `circuit_breakers.<priority>.cx_open`; `listener.<addr>.downstream_cx_total`,
  `downstream_cx_active`, `downstream_cx_overflow` [S9, S10]. The
  downstream/upstream split and breaker-as-gauge are adopted.
- HAProxy: `haproxy_frontend_bytes_in_total`, `haproxy_server_status`,
  `haproxy_server_check_status`, `haproxy_backend_current_queue`; scopes
  frontend, backend, server; `_total` and `_seconds` suffixes [S11]. Adopted:
  one `server` label for everything about a backend.
- Caddy: `caddy_http_requests_total`, `caddy_reverse_proxy_upstreams_healthy{upstream}`;
  the docs warn per-host labels carry "potential infinite cardinality risk" and
  disable them by default; OTLP export defaults to HTTP/protobuf [S12].
  Adopted: virtual host never becomes a label.
- OpenTelemetry semantic conventions: UCUM units, seconds for durations,
  hierarchical names (`jvm.gc.*`), `rpc.server.call.duration` histogram with
  bounds from 0.0001 s to 60 s, unknown methods reported as `_OTHER` [S13, S14,
  S15]. Adopted: the OTLP exporter maps `warp_x_y_total` to `warp.x.y` with unit
  metadata; bounded enum labels, unknown values collapse to `other`.
- Prometheus guidance: snake_case, application prefix, base units, `_total` for
  counters, `_info` for info metrics, "keep the cardinality of your metrics
  below 10" per label and investigate above 100 series, export 0 for known
  series, failure counters next to attempt counters [S16, S17]. OpenMetrics
  1.0 content type `application/openmetrics-text; version=1.0.0`, `_created`
  SHOULD for counters, info metrics with value 1 [S18].
- OTLP: HTTP on 4318, `/v1/metrics`, protobuf or JSON body, servers SHOULD
  accept both; retry only on 429, 502, 503, 504 [S23]. Micrometer's OTLP
  registry defaults to HTTP/protobuf, cumulative temporality, 1 minute step
  [S22]; the Prometheus Java client pushes OTLP every 60 s by default [S20].

### 2.3 Metric set

Prefix `warp_`. Labels are closed enums or config-bounded names. The `server`
label is the registered server name from `servers` in `warp.conf` (bounded by
configuration, removed on unregister). The whole default set stays under 300
series for a proxy with 10 servers.

Downstream (clients):

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_connections_accepted_total` | counter | `intent` (status, login, transfer) | TCP connections that reached a handshake. |
| `warp_connections_active` | gauge | `state` (handshake, status, login, config, play) | Live client connections by protocol state. |
| `warp_connections_refused_total` | counter | `reason` | Already exists (anti-bot). Kept, now in the registry. |
| `warp_logins_total` | counter | `outcome` (success, refused_antibot, refused_duplicate, refused_full, auth_failed, auth_timeout, encryption_invalid, protocol_unsupported, cancelled_plugin, disconnected, other) | One per login attempt. Failure counters sit next to the success counter as Prometheus recommends [S17]. |
| `warp_login_duration_seconds` | histogram | `outcome` (success, failed) | Handshake to login success, buckets 0.05 to 30 s. |
| `warp_status_pings_total` | counter | `cached` (true, false) | Server-list pings, cache hit or miss. |
| `warp_client_protocol_version` | gauge | `version` (protocol number as string, bounded by `ProtocolVersion` registry) | Players online per protocol version. |
| `warp_players_online` | gauge | none | Players in PLAY on this node. |
| `warp_players_online_network` | gauge | none | Fleet-wide players (section 4). Equals the local value without a fleet store. |

Forwarding (hot path):

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_forwarded_packets_total` | counter | `direction` (c2s, s2c), `mode` (blind, decoded) | Blind means raw passthrough, decoded means the proxy read the packet. |
| `warp_forwarded_bytes_total` | counter | `direction`, `mode` | Wire bytes forwarded. |
| `warp_compression_passthrough_total` | counter | `direction`, `result` (passthrough, recompressed) | Frames passed compressed versus re-encoded. |
| `warp_compression_bytes_total` | counter | `direction`, `side` (compressed, raw) | Enough to derive the ratio; no `ratio` gauge, which cannot be aggregated. |
| `warp_packet_drops_total` | counter | `reason` (oversized, invalid_frame, state) | Packets refused by the frame decoder. |

Upstream (servers):

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_server_connections_active` | gauge | `server` | Players attached to each backend. |
| `warp_server_connects_total` | counter | `server`, `outcome` (success, refused, timeout, reset, protocol, kicked, other) | Backend connection attempts. |
| `warp_server_connect_duration_seconds` | histogram | `server` | TCP connect plus backend login, buckets 0.005 to 10 s. |
| `warp_server_switches_total` | counter | `outcome` (success, failed, cancelled) | Server switches, no `from`/`to` pair (quadratic cardinality). |
| `warp_server_bytes_total` | counter | `server`, `direction` | Bytes per backend. |
| `warp_server_rtt_seconds` | gauge | `server` | Last round trip of the proxy's own status ping to the backend (`status-pings` config). |
| `warp_server_health` | gauge | `server` | 1 up, 0 down, from the status-ping loop. |
| `warp_server_breaker_state` | gauge | `server` | 0 closed, 1 half open, 2 open. Lands with the routing and load balancing design (branch of the routing-lb work); the metric name is reserved here. |

Proxy internals:

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_event_loop_lag_seconds` | histogram | none | A 1 s scheduled probe per loop measures scheduled time minus run time. Buckets 0.001 to 5 s. The single most useful saturation signal. |
| `warp_event_loop_pending_tasks` | gauge | `loop` (index 0..n, bounded by thread count) | Netty `pendingTasks()` sampled at scrape. |
| `warp_direct_memory_bytes` | gauge | none | Netty pooled direct memory in use. |
| `warp_drain_phase` | gauge | none | `DrainPhase` ordinal, 0 when idle. |
| `warp_drain_players_remaining` | gauge | none | From `DrainStatus.players()`. |
| `warp_drain_transfers_total` | counter | `outcome` (sent, unsupported, not_ready, disconnected) | Maps `TransferResult`. |
| `warp_drain_duration_seconds` | gauge | none | Elapsed of the current or last drain. |
| `warp_antibot_*` | as today | as today | Unchanged names. |
| `warp_plugin_event_duration_seconds` | histogram | `event` (event class simple name, bounded by registered events) | Plugin handler time per event type. |
| `warp_plugin_handler_errors_total` | counter | `plugin` (plugin id) | Uncaught exceptions from handlers. |
| `warp_log_sink_dropped_total` | counter | `sink` (plugin id) | Records a slow `LogSink` lost. |
| `warp_fleet_*` | section 4.6 | | |
| `warp_query_requests_total` | counter | `type` (handshake, basic, full), `outcome` (answered, no_token, rate_limited, malformed) | Section 3. |
| `warp_info` | gauge | `version`, `commit`, `java` | Always 1. |
| `warp_start_time_seconds` | gauge | none | Unix time; uptime is derived in queries. |

JVM: `jvm_memory_used_bytes{area,pool}`, `jvm_gc_pause_seconds` (histogram via
GC notifications), `jvm_threads_live`, `process_cpu_seconds_total`,
`process_open_fds`, all read from JMX at scrape time with the standard names
Prometheus Java clients use. Container-aware CPU and memory come from the JDK's
cgroup support (`OperatingSystemMXBean` with container awareness on), avoiding
the Spark problem [S7].

Cardinality rules, enforced at registration:

- Label values must be from a closed set, a config-bounded name (`server`,
  plugin id), or a small integer index. The registry rejects a label named
  `player`, `uuid`, `name`, `address`, `host`, `ip`, `virtual_host`.
- Hard caps: 32 label values per label (further values collapse to `other` and
  increment `warp_metrics_label_overflow_total{metric}`), 2 000 series per
  plugin, 10 000 series total. Hitting a cap logs once at WARN with a
  `LogCode`.
- No pairwise labels (`from` x `to`).
- Histogram bucket sets are fixed per metric and documented; plugins choose
  from three presets (`fast`: 1 ms to 2.5 s, `network`: 5 ms to 30 s, `long`:
  0.1 s to 10 min).

### 2.4 Implementation: own registry, not Micrometer

The older note chose Micrometer. This document reverses that:

- Hot path cost. Micrometer and client_java counters are `LongAdder` or
  `AtomicLong` behind a `Meter` lookup keyed by tags; the tag lookup and the
  `Tags` objects allocate when labels vary. Warp's forwarding path forbids
  allocation. Warp counters are resolved once at handler construction to a
  `Counter.Child` holding a per-event-loop slot, so the increment is a plain
  `long` add on the loop's own array (no CAS, no false sharing, no allocation).
  Off-loop increments (plugins, virtual threads) use a `LongAdder` fallback on
  the same child. Scrape sums the slots with acquire reads.
- Jar weight and relocation. Micrometer Prometheus pulls client_java 1.x and
  its exposition formats; the OTLP registry pulls OpenTelemetry proto classes
  [S21, S22]. Warp already shades Gson, Caffeine and Configurate and the
  RedisBungee history shows how relocation collides with plugins (ValioBungee
  #126, 49 comments) [S33]. A registry of about 1 500 lines with no
  dependencies avoids both.
- Exposition is small. Prometheus text 0.0.4 and OpenMetrics 1.0 differ in a
  content type, `# EOF`, and optional `_created` lines. OTLP/HTTP accepts JSON
  [S23], which Gson writes; Gate pushes to the same collectors, so this is a
  proven path. A hand-written protobuf encoder (about 300 lines for
  `ExportMetricsServiceRequest`) is phase 2b if a target rejects JSON.
- Plugins want one API from Warp, not a choice of Micrometer versions. Plugins
  that already use Micrometer can bridge with a `MeterRegistry` adapter they
  ship themselves; Warp exposes `Metrics.collect(Consumer<Sample>)` for that.

Design:

```
dev.warp.api.metrics
  Metrics            counter(name, help, labels...) / gauge(...) / histogram(name, help, Buckets, labels...)
                     collect(SampleVisitor)       read every sample (for bridges and tests)
  Counter            labels(values...) -> Counter.Child   (resolved once, cached per label tuple)
  Counter.Child      inc(), add(long)
  Gauge              labels(values...) -> Gauge.Child ; or gauge(name, help, LongSupplier) for pull gauges
  Histogram          labels(values...) -> Histogram.Child ; observe(double seconds) / observeNanos(long)
  Buckets            FAST, NETWORK, LONG, of(double...)
  MetricNames        validation (regex, reserved labels), unit suffix check
```

Plugin metric names are prefixed with the plugin id: a plugin passes `lookups_total`
and the exposed name is `<id>_lookups_total`, for example `luckperms_lookups_total`.
A plugin cannot register a name starting with `warp_` or `jvm_` or `process_`.
Metrics registered by a plugin are removed when the plugin unloads.

Hot-path mechanics, proxy side:

- `Striped` holds `long[] slots` sized `eventLoops * 16` (one cache line per
  loop). The event loop index is stored in a `FastThreadLocal` at loop start.
  `inc()` reads the index, adds to `slots[index * 16]` with a plain write, and
  the scraper reads with `VarHandle.getAcquire`. A lost update is impossible
  because only the owning loop writes its slot; the acquire read gives the
  scraper a value at least as fresh as the previous scrape.
- Histograms keep `long[buckets]` per loop plus a `double` sum per loop.
  `observeNanos` does one bucket lookup (binary search over at most 16 bounds)
  and two adds.
- Gauges are pull: the scraper calls the supplier. No background sampling
  except the event loop lag probe, which is itself an event loop task.
- Scrape runs on a virtual thread, never on an event loop, and writes the text
  into a reusable `ByteBuf` (about 50 KiB for the default set).

### 2.5 Exposition and transport

- `GET /metrics` on the management server replaces the anti-bot-only text.
  Content negotiation: Prometheus text 0.0.4 by default, OpenMetrics 1.0 when
  `Accept` asks for it. Same bind, TLS and token rules as the other GET
  routes. New option `management.metrics.require-token` (default `false`
  because the default bind is loopback). When the bind is not loopback and
  neither TLS nor `require-token` is on, Warp logs one WARN at startup. Gzip
  when `Accept-Encoding` allows (Prometheus sends it).
- `GET /metrics?filter=warp_server_` restricts by prefix, following HAProxy's
  `scope` filter idea [S11], for cheap per-dashboard scrapes.
- OTLP push, optional:

```hocon
management {
  metrics {
    enabled = true               # registry always exists; this controls /metrics
    require-token = false
    otlp {
      endpoint = ""              # "http://otel-collector:4318/v1/metrics" enables push
      interval = 60s
      headers = {}               # e.g. Authorization
      encoding = json            # json | protobuf (phase 2b)
      timeout = 10s
    }
  }
}
```

  Push runs on a virtual thread with `java.net.http.HttpClient`, cumulative
  temporality, retries only on 429/502/503/504 with backoff capped at the
  interval [S23]. Resource attributes: `service.name=warp`,
  `service.instance.id=<fleet proxy id>`, `service.version`.

- `warp metrics` console command prints the few numbers an operator wants at a
  glance (players, connections by state, blind ratio over the last minute,
  event loop lag p99, servers up/down). Same snapshot feeds the remote console.

### 2.6 Competitor issues avoided

| Issue | Problem | Warp's answer |
|-------|---------|---------------|
| Velocity and BungeeCord have no metrics [S8] | Every network writes or finds an exporter plugin; the two most-cited are gone (404). | Built in, documented catalogue, stable names under semver. |
| Gate OTLP only [S1] | Needs a collector even for a one-node Prometheus; env-only configuration; undocumented names. | Scrape and push, HOCON config, names in this document and in `docs/metrics.md` at release. |
| Per-player series [S5] | Unbounded cardinality, "against best-practices" by the authors' own admission. | Reserved label names rejected at registration; caps with `other` collapse. |
| Division by zero and null statistics in exporter plugins [S6] | Scrape fails entirely when one collector throws. | Each collector is isolated; a throwing collector is skipped, counted in `warp_metrics_collector_errors_total{collector}` and logged once. |
| Container CPU and memory misreported [S7] | Alerts on host values. | JDK container-aware beans; `warp_info` carries the cgroup limit as a separate gauge `process_memory_limit_bytes`. |
| Micrometer tag allocation on hot paths | Per-packet `Tags` objects. | Children resolved once, per-loop striped slots. |

### 2.7 Phases

| Phase | Content | Size |
|-------|---------|------|
| M1 | `dev.warp.api.metrics` API, striped registry, Prometheus and OpenMetrics text, `/metrics` replaces the anti-bot text (anti-bot names kept), JVM collectors, `warp_info`. Tests: text format golden files, striping under concurrent loops, cardinality caps. | M (4 days) |
| M2 | Instrument the proxy: connections, logins, forwarding, compression, servers, drain, event loop lag, plugin events. JMH check that the blind path shows no measurable regression (threshold 1 percent on the existing forwarding benchmark). | M (4 days) |
| M3 | OTLP/HTTP JSON push, `warp metrics` command, Grafana dashboard JSON in `docs/dashboards/`, alert rules example. | S (2 days) |
| M4 (optional) | Protobuf encoder for OTLP, exemplars when tracing lands. | S |

Risks: a mis-sized stripe array when event loops are resized (Warp sizes loops
at startup only, so this is static); plugins registering in hot paths (the API
documents that `labels(...)` must be called once and cached, and the lookup is
a `ConcurrentHashMap` so a careless plugin is slow, not broken).

---

## 3. GS4 Query

### 3.1 Protocol

GS4 Query runs over UDP. Magic `FE FD`, type `09` handshake, type `00` stat,
int32 session id. The handshake reply is a null-terminated challenge token in
decimal; the token "lasts up to 30 seconds" and is bound to the sender's IP
and port; a wrong token gets no reply. Basic stat returns MOTD, gametype, map,
numplayers, maxplayers, hostport (little-endian), hostip. Full stat (request
padded to 8 bytes) returns the key-value block (hostname, gametype, game_id,
version, plugins, map, numplayers, maxplayers, hostport, hostip) and the
player name list; the vanilla server caches it for 5 seconds [S24].

### 3.2 Existing implementations

- Velocity (`GameSpyQueryHandler`): Caffeine cache `InetAddress -> Integer`
  with `expireAfterWrite(30 s)` and no maximum size; token is `random.nextInt()`
  keyed by address only (not address plus port); a stat with a wrong token is
  dropped; `ProxyQueryEvent` lets plugins edit the response; plugins listed
  when `show-plugins` is on; no rate limit and no response size cap [S25].
  Open bug: "GS4 query handler throws on short/malformed datagrams instead of
  dropping them" (#1828) with fix PRs #1849 and #1857 still open [S26].
  Config `[query]`: `enabled`, `port`, `map`, `show-plugins`; docs say "You
  can usually leave this false" [S27].
- BungeeCord (`QueryHandler`): Guava cache per address, 30 s, no maximum size;
  missing session throws `IllegalStateException("No session!")`; world name
  `BungeeCord_Proxy`; no rate limit [S28].
- Gate: `query: enabled: false, port: 25577, showPlugins: false` in the full
  config template, no `map` option [S2].

Shared weaknesses: unbounded challenge caches fed by spoofed handshakes (a
handshake needs no token, so an attacker can fill the cache from any source
address), exceptions on malformed datagrams, no per-source rate limit, player
name list unbounded.

### 3.3 Amplification

UDP services answer unauthenticated requests, so a spoofed source gets the
reply. Cloudflare's reflection overview gives the general picture (DNS, NTP,
SSDP, game servers such as SRCDS) and the standard mitigations: do not answer
large replies to unverified sources, rate limit per source, drop by default
[S29]. For GS4 the handshake reply is small (7 byte request, about 5 to 16 byte
reply, factor under 3) and the stat reply, which can reach several kilobytes
with a player list, requires a token that was delivered to the real source
address. The protocol therefore resists amplification if, and only if, the
server never answers a stat without a valid token bound to the sender, and
keeps the handshake path cheap and bounded. Neither existing implementation
caps the cache or the full-stat size.

### 3.4 Decision: ship it, off by default, strict

Why ship: server lists, hosting panels and monitoring agents still speak
GS4 (it is what `enable-query` turns on in a vanilla server), and a proxy that
cannot answer it forces operators to expose a backend port. Off by default,
matching Velocity and Gate.

```hocon
query {
  enabled = false
  bind = ""                 # empty: same address as `bind` with the same port, UDP
  map = "Warp"
  show-plugins = false
  player-names = 0          # names in a full stat; 0 omits the list, max 1000
  rate-limit {
    per-address = 5         # handshakes per second per source address
    burst = 10
    global = 2000           # datagrams per second, all sources
  }
}
```

Rules:

- Challenge bound to (address, port) as the wiki specifies [S24], not address
  alone. Token from `SecureRandom` seeded `SplittableRandom`, 31 bits.
- Bounded challenge table: an open-addressing array of 65 536 entries keyed by
  a hash of (address, port), each slot holding token and expiry; a new
  handshake overwrites the slot. Fixed memory, no allocation, no eviction
  thread. Expiry 30 s.
- Per-address token bucket on handshakes and a global bucket on all datagrams,
  both checked before any parsing. The per-address table is also fixed-size
  (hash of address, 16 384 slots). Over the limit, the datagram is dropped and
  `warp_query_requests_total{outcome="rate_limited"}` increments.
- Every malformed datagram (short, wrong magic, unknown type, wrong token) is
  dropped silently and counted; no exception leaves the handler (Velocity
  #1828 [S26]).
- Response built from a snapshot refreshed at most every 2 s: `ServerPing`
  from the `status` config, after `ProxyPingEvent` fired with a synthetic
  query origin so plugins that shape the MOTD need no new code. Fields: MOTD
  plain text (Adventure plain serializer), gametype `SMP`, game_id
  `MINECRAFT`, version from `status.version-name` or the newest supported
  protocol's name, plugins `Warp <version>: <plugin names>` only when
  `show-plugins`, map from config, numplayers and maxplayers from the same
  values the status ping shows (fleet-wide when the fleet is on), hostport
  and hostip from the listener bind.
- Full stat size cap: player names limited by `player-names` and the datagram
  truncated to 1 400 bytes to stay under a typical MTU; a reply never exceeds
  the request by more than this fixed bound, and only after a valid token.
- Runs on the proxy event loop group with a single `EpollDatagramChannel`
  (NIO fallback), reading into a pooled direct buffer, writing the response
  into a reusable buffer per loop. No per-datagram allocation except the
  Netty `DatagramPacket` envelope, which Netty pools.
- A `QueryEvent` for plugins (edit map, plugins, players) is deferred; the
  `ProxyPingEvent` path covers the common case.

### 3.5 Competitor issues avoided

| Issue | Problem | Warp's answer |
|-------|---------|---------------|
| Unbounded challenge cache (Velocity, BungeeCord) [S25, S28] | Spoofed handshakes grow heap until GC pressure or OOM. | Fixed-size table, overwrite on collision. |
| Token keyed by address only [S25, S28] | NAT users share tokens; spec binds to address plus port. | Key is (address, port). |
| Malformed datagrams throw (Velocity #1828) [S26] | Exception per packet under a flood, log spam. | Validate length and magic first, drop and count. |
| No rate limit | A query flood costs a status build per datagram. | Two token buckets before parsing; snapshot refreshed at most every 2 s so a stat is a buffer copy. |
| Player list unbounded | Multi-kilobyte replies on a large network. | `player-names` default 0, hard cap 1 000, 1 400 byte truncation. |

### 3.6 Phases

| Phase | Content | Size |
|-------|---------|------|
| Q1 | Datagram handler, bounded challenge table, rate limits, basic and full stat from the status snapshot, config, metrics, unit tests with `EmbeddedChannel` and golden datagrams from the wiki. Shipped in `proxy/.../query` (`feat/query`): the per-address bucket applies to every datagram, not handshakes alone, since a stat without a token costs a table lookup too; the fixed-size buckets replace the Caffeine `RateLimiter`, whose cache allocates per key. | S (2 days) |
| Q2 | E2E check with `mcstatus` style query client in the testbed, fleet-wide counts when section 4 lands. | S |

Risk: hosts behind a UDP-unaware load balancer see `hostip` as the proxy's
private address; `query.bind` plus a `query.advertise-host` override (added
in Q1) handle that.

---

## 4. Fleet: several proxies as one network

### 4.1 Prior art

RedisBungee (today the ProxioDev/ValioBungee fork; the original is "no longer
maintained") is the de facto answer for Velocity and BungeeCord [S30]. Model,
from source [S34, S35] and docs [S31, S32]:

- Each proxy publishes a heartbeat payload (`epochSecond`, player count) over
  Redis pub/sub and keeps a `ConcurrentHashMap` of peers' heartbeats; a peer
  is dead when `Instant.now() - heartbeat > 30 s` (`PROXY_TIMEOUT = 30`).
- Players are stored in a set `redisbungee::<network>::proxies::<id>::online-players`
  plus per-player hashes. A "correction task" runs every minute and repairs the
  local proxy's own set when it diverges from local state ("De-synced players
  set detected correcting....").
- Dead proxies that never sent a death payload are cleaned by whichever proxy
  notices the timeout in its correction task.
- Requires NTP: the install page says it breaks when a proxy's clock is about
  30 s behind another's [S31]. Supports Redis 6.2 and 7; Valkey and KeyDB are
  named alternatives; config notes Redis 7.2.4 as the last open-source
  release [S31, S32].
- Issue history: "Player count not syncing" (#130), "Player count
  synchronization failing" (#121), "Player count not updating after a reboot"
  (#109), "kick-when-online: false Issue" (#113, "You are already logged in"
  at the title screen), "Connecting players to another proxy in event of proxy
  crash/shutdown" (#63, no built-in answer), "more relocations issues" (#126,
  49 comments), "ValioBungee loses connection to Redis Server" (#129) [S33].

Velocity closed "add multi-velocity support" (#627) with maintainers stating
it "should be done by a plugin or via user implementation" and that any core
implementation "would be too broad" [S36]. The 2024 request for multiple
listeners (#1046) again points users at RedisBungee [S37]. Gate documents
running several instances behind one Connect endpoint for load balancing but
no shared player list or cross-instance commands [S38].

PrimeMC today runs its own Valkey-backed plugin on Warp: a presence store,
duplicate-login handling across two proxies and drain-time transfers were all
exercised in the 9/9 real-client E2E run referenced in Warp PR #158. That is
the workload this design must absorb natively so PrimeMC's plugin shrinks to
game logic.

Infrastructure facts that shape the design:

- Valkey pub/sub is at-most-once and unpersisted; a disconnected subscriber
  loses messages; sharded pub/sub (`SSUBSCRIBE`) exists since Redis OSS 7.0;
  streams are the durable alternative [S39].
- `EXPIRE` is wall-clock on the server, survives server restarts, is cleared by
  `SET`/`DEL` and kept by `HSET`/`INCR`; expiry runs lazily and at about 10 Hz
  in the background [S40].
- Locks: `SET NX PX` on one primary, `DELIFEQ` on Valkey 9, Lua before; the
  distlock page itself warns about async replication after failover and that
  TTL uses wall clock, and recommends fencing tokens [S41].
- Client-side caching with `CLIENT TRACKING` (RESP3 push, or `BCAST PREFIX`)
  invalidates local copies on write and expiry; flush local state when the
  invalidation connection drops [S42].
- Redis 8 added AGPLv3 as a licence option after the 2024 SSPL move; Valkey is
  BSD-3 [S43]. Lettuce is MIT and Netty-based; multiple threads may share one
  connection outside blocking and transactional commands [S44].
- Kubernetes headless services give per-pod A/AAAA and SRV records; Leases in
  `coordination.k8s.io` carry `holderIdentity`, `leaseDurationSeconds` and
  `renewTime` for heartbeats [S45, S46]. Session stores on Redis use a hash
  per session with a sliding `EXPIRE` [S47].

### 4.2 Decision: native fleet API with a store SPI, Valkey first

Why native: the three recurring RedisBungee failures (stale counts after a
restart, ghosts after a crash, duplicate-login kicks) all come from doing
presence outside the component that owns the connection. Warp owns login,
disconnect, transfer and drain, so it can make presence transactional with
those events, choose transfer targets from live peers, and apply one
duplicate-login policy network-wide. Velocity's "too broad" objection is
answered by a narrow core (presence, counts, bus, four commands, duplicate
login, drain targets) and a store SPI, with everything else left to plugins.

```
dev.warp.api.fleet
  Fleet                 local() : ProxyNode ; nodes() : Collection<ProxyNode>
                        players() : Collection<FleetPlayer> ; player(UUID) / player(String name) : Optional<FleetPlayer>
                        playerCount() : int ; playerCount(String server) : int
                        send(UUID player, String server) : CompletableFuture<SendResult>
                        kick(UUID player, Component reason) : CompletableFuture<Boolean>
                        message(UUID player, Component message) : CompletableFuture<Boolean>
                        bus() : FleetBus
                        storeState() : StoreState   (CONNECTED, DEGRADED, SINGLE_NODE)
  ProxyNode             id(), incarnation(), publicAddress(), players(), state() (READY, DRAINING, DOWN), lastSeen(), version()
  FleetPlayer           uuid(), name(), proxyId(), server() : Optional<String>, since()
  FleetBus              publish(String channel, byte[] payload) ; subscribe(String channel, Consumer<FleetMessage>) : Subscription
                        request(String proxyId, String channel, byte[] payload, Duration timeout) : CompletableFuture<byte[]>
  FleetMessage          channel(), senderProxyId(), payload()
  events                FleetNodeJoinEvent, FleetNodeLeaveEvent(reason: SHUTDOWN, EXPIRED), FleetPlayerJoinEvent, FleetPlayerLeaveEvent,
                        FleetPlayerServerChangeEvent, FleetStoreStateEvent
  spi                   FleetStore (section 4.4) for alternative stores
```

Plugins receive `Fleet` by injection like `AntiBot` today. With no store
configured, `Fleet` is backed by `SingleNodeStore`: `nodes()` is the local
node, `players()` is the local list, `send` to another proxy fails with
`SendResult.UNKNOWN_PLAYER`. Plugin code written against `Fleet` works on one
node and on fifty.

### 4.3 Data model in the store

Key prefix `warp:<network>:`. `network` defaults to `main` (RedisBungee's
default, eases migration).

| Key | Type | TTL | Content |
|-----|------|-----|---------|
| `node:<id>` | hash | `heartbeat-ttl` (default 10 s) | `inc` (incarnation, monotonically increasing per id, from a store `INCR` at start), `addr` (public address for transfers), `state` (ready, draining), `players` (count), `version`, `started`. Refreshed every `heartbeat` (default 2 s) with `HSET` plus `EXPIRE`. |
| `node:<id>:players` | set | none | UUIDs on that node. Dies with the node by the reaper, not by TTL. |
| `nodes` | set | none | Known ids, reconciled by the reaper. |
| `player:<uuid>` | hash | none | `name`, `node`, `inc`, `server`, `since`. Ownership is (node, inc). |
| `name:<lowercase name>` | string | none | UUID, for `/find name`. |
| `bus:<channel>` | pub/sub | | Messages (section 4.5). |
| `node:<id>:inbox` | pub/sub | | Targeted requests. |
| `nodes:events` | pub/sub | | `join`, `leave`, `reaped`, `drain` notices. |

Why no TTL on player keys: refreshing N player keys every heartbeat is
O(players) writes per node per 2 s; refreshing one node hash is O(1). A
player is online if and only if its `player:<uuid>` hash exists and the node
it names is alive with the same incarnation. Readers join at read time; the
local replica (section 4.4) does this in memory.

Atomic operations are Valkey functions (`FUNCTION LOAD`, with `EVAL` fallback
for Redis 6.2), each keyed under `warp:<network>` so cluster mode keeps them on
one slot by hash tag `{warp:<network>}`:

- `claim(uuid, name, node, inc, policy)`: if no owner, or owner node is absent
  or has a different incarnation, write the player hash and name key, `SADD`
  into the node set, return `CLAIMED`. If owned by a live node, return
  `OWNED <node> <inc>`. The caller then applies the duplicate-login policy
  (section 4.5).
- `release(uuid, node, inc)`: delete the hash and name key only if still owned
  by (node, inc). Prevents a late disconnect on an old incarnation from
  deleting a fresh login.
- `move(uuid, node, inc, server)`: update `server` if owned.
- `reap(node, inc)`: for every UUID in `node:<id>:players`, delete the player
  hash if its owner is exactly (node, inc); delete the set and the node hash;
  `SREM nodes`; publish `reaped`. Idempotent, so any number of peers may run
  it.

### 4.4 Consistency model and local replica

- The store is the source of truth; each node holds an in-memory replica
  (`ConcurrentHashMap<UUID, FleetPlayer>`, `Map<String, ProxyNode>`) read by
  the API without I/O. The API is therefore safe to call from event handlers.
- Replica updates come from three sources: the node's own writes (applied
  locally first, then to the store), pub/sub notices from peers (`join`,
  `leave`, `move`, `reaped`), and a full reconcile every `reconcile` (default
  30 s) that reads `nodes`, every `node:<id>` and `node:<id>:players` plus
  the player hashes of any difference. Reconcile also repairs the node's own
  set, like RedisBungee's correction task, but it runs on every node for every
  node, so the network converges within one interval even after lost pub/sub
  messages.
- Liveness is decided only by key existence (TTL on the store's clock). No
  node ever compares its own clock to a peer's timestamp, which removes
  RedisBungee's NTP requirement [S31]. `lastSeen` in the API is the local
  monotonic time of the last observed heartbeat and is informational.
- Reaping: a node whose heartbeat expires is detected by the next reconcile
  on any peer, or sooner when the store supports keyspace notifications
  (`notify-keyspace-events Ex`, enabled by Warp at connect when permitted, and
  `expired` events on `node:*`). Detection is at most `heartbeat-ttl` plus
  `reconcile` after the crash, 40 s worst case with defaults, typically under
  12 s. A node restarting with the same id takes a new incarnation and reaps
  its previous one immediately, so a crash plus fast restart leaves no ghosts.
- Guarantees stated to plugins: `players()` is eventually consistent with a
  bound of one reconcile interval; `player(uuid)` after `claim` on the same
  node is immediately consistent; cross-node messages are at-most-once;
  `request` is at-most-once with a timeout and an explicit failure.

### 4.5 Behaviours

Presence: `LoginEvent` allowed -> `claim`; `PostLoginEvent` -> publish `join`;
`ServerConnectedEvent` -> `move`; `DisconnectEvent` -> `release` plus `leave`.
All store I/O runs on the fleet client's own Netty channel on the proxy event
loop group, asynchronous; login waits on `claim` with `fleet.claim-timeout`
(default 2 s).

Duplicate login across proxies, reusing `DuplicateLoginPolicy`:

- `claim` returns `OWNED <node> <inc>`.
- `KICK_EXISTING` (default in online mode): send `kick` to the owner's inbox
  with the UUID and incarnation; the owner disconnects the player (reason:
  "You logged in from another location") and `release`s; the newcomer's node
  retries `claim` when it sees `leave` or after 500 ms, up to the claim
  timeout; if the owner never answers (dead but not yet expired), the newcomer
  is refused with a localised "already online, retry in a few seconds". No
  ghost can block a player for more than `heartbeat-ttl`.
- `REFUSE_NEW`: newcomer refused with the existing localised message.
- The policy lives in one place for local and remote duplicates, which fixes
  the class of ValioBungee #113 (`kick-when-online` not behaving as told)
  [S33].

Counts: `playerCount()` sums `players` from live node hashes in the replica;
the status ping and GS4 reply use it when `fleet.status.network-count = true`
(default `true` when a store is configured). This is what ValioBungee #109,
#121 and #130 report as broken; here the count is derived from the same
heartbeat that defines liveness, so a restarted node cannot leave a stale
contribution.

Commands (console and remote console, permission `warp.fleet`): `warp glist`
(players per node and per server), `warp find <player>`, `warp send <player|all|server:<name>|proxy:<id>> <server>`,
`warp fleet` (nodes, state, incarnation, address, players, store state).
`send` to a player on another node is a `request` to that node's inbox; the
owner performs `connectTo` and answers `SendResult`.

Bus: `FleetBus.publish` maps to `PUBLISH warp:<net>:bus:<channel>`; payloads
are opaque bytes with a 32 KiB cap; sender id and a 64-bit message id are
framed in front. `request` publishes to `node:<id>:inbox` and awaits a reply
on the requester's inbox with the message id; one retry after `timeout / 2`
if the target is alive, then failure. Plugin messages between backends are
unaffected (they ride the player connection).

Drain coordination: a draining node sets `state=draining` in its heartbeat
and publishes `drain`. `DrainController` picks transfer targets from
`nodes()` filtered to `READY`, least `players` first, using `publicAddress`,
when `drain.transfer-target = fleet` (new value beside the explicit host:port).
Peers stop counting a draining node for new logins when an external balancer
asks the agent check, which now reports `drain` for a draining node. Answers
ValioBungee #63 natively [S33].

Anti-bot: `VerifiedStore` can be backed by the same Valkey connection
(`anti-bot.verified.store = fleet`), so an identity verified on one node is
trusted on all. Lockdown stays per node in this phase; a `lockdown` notice on
`nodes:events` for fleet-wide lockdown is phase F3.

### 4.6 Store SPI and the Valkey client

```
dev.warp.api.fleet.spi
  FleetStore            connect(FleetStoreContext) : CompletableFuture<Void> ; close()
                        heartbeat(NodeRecord) ; nodes() ; players(nodeId)
                        claim(...) ; release(...) ; move(...) ; reap(...)
                        publish(channel, bytes) ; subscribe(channel, listener)
                        state() ; onStateChange(listener)
  FleetStoreContext     networkId, nodeId, incarnation supplier, event loop group, metrics, logger
```

Shipped implementations: `SingleNodeStore` (default) and `ValkeyFleetStore`.
A plugin may provide another (NATS, etcd, a Kubernetes-only store on Leases
and EndpointSlices) by registering a `FleetStore` under a name used in
`fleet.store.type`.

Valkey client: a minimal RESP3 client on Netty inside the proxy module
(`dev.warp.proxy.fleet.valkey`), about 800 lines: `HELLO 3` with `AUTH`,
TLS through Netty's `SslContext`, one command connection with pipelining and
one subscriber connection, `FCALL`/`EVALSHA` with `SCRIPT LOAD` fallback,
reconnect with jittered backoff from 200 ms to 10 s, then a full resync
(re-`HELLO`, re-subscribe, replay the local player set with `claim`,
reconcile). Commands used: `HELLO`, `AUTH`, `PING`, `HSET`, `HGETALL`,
`EXPIRE`, `SADD`, `SREM`, `SMEMBERS`, `SCAN`, `DEL`, `INCR`, `PUBLISH`,
`SUBSCRIBE`, `SSUBSCRIBE`, `FUNCTION LOAD`, `FCALL`, `EVALSHA`, `SCRIPT LOAD`,
`CONFIG SET notify-keyspace-events` (best effort), `CLIENT TRACKING` is not
used (the replica is driven by notices and reconcile).

Why not Lettuce or Jedis: Lettuce would need reactor-core shaded next to
Netty and brings an API surface Warp uses one percent of; Jedis is blocking
and caused the relocation fights seen in ValioBungee #126 [S33]. The command
surface above is small, the RESP3 parser is simpler than the Minecraft frame
decoder Warp already owns, and running on the proxy's event loop group keeps
thread accounting honest. Cluster mode: supported by hash-tagging every key
with `{warp:<network>}` so one slot holds the network; `MOVED` redirects are
followed once and the connection re-targets. Sentinel is not supported in
phase F1 (document: use a Valkey service address or a cluster).

Config:

```hocon
fleet {
  store {
    type = none               # none | valkey | <plugin name>
    valkey {
      address = "valkey:6379"
      tls = false
      username = ""
      password-file = ""      # or WARP_FLEET_PASSWORD
      database = 0
    }
  }
  network = "main"
  node-id = ""                # default: hostname (pod name on Kubernetes)
  public-address = ""         # host:port other nodes send transfers to; default: first bind with a public host
  heartbeat = 2s
  heartbeat-ttl = 10s
  reconcile = 30s
  claim-timeout = 2s
  on-store-down = allow       # allow | refuse-new-logins
  status { network-count = true }
}
```

Metrics: `warp_fleet_store_up` (gauge), `warp_fleet_nodes` (gauge, `state`),
`warp_fleet_store_commands_total{command,outcome}`,
`warp_fleet_store_latency_seconds` (histogram), `warp_fleet_claims_total{outcome}`
(claimed, owned_kicked, owned_refused, timeout, store_down),
`warp_fleet_reaped_players_total`, `warp_fleet_bus_messages_total{direction}`,
`warp_fleet_reconcile_corrections_total{kind}`.

### 4.7 Failure handling

| Failure | Behaviour |
|---------|-----------|
| Store unreachable at start | Node starts in `DEGRADED`, readiness unaffected unless `on-store-down = refuse-new-logins`; reconnect loop; one WARN with a `LogCode`, then INFO on recovery. |
| Store lost at runtime | Presence writes queue up to 10 000 operations in memory and are replayed after reconnect in order; `claim` during the outage follows `on-store-down` (`allow`: local duplicate policy only); replica frozen with a `storeState()` of `DEGRADED`; `playerCount()` returns the local count plus the last known peers for `heartbeat-ttl`, then local only. |
| Pub/sub message lost | Reconcile repairs within 30 s. |
| Node crash | Peers reap after TTL; a restart with the same id reaps immediately through the new incarnation. |
| Store failover loses recent writes (async replication) | Reconcile re-asserts every node's own set; `claim` conflicts after a lost write resolve by incarnation and policy. |
| Two nodes with the same id | The second `INCR` incarnation wins; the first sees its heartbeat overwritten with a higher incarnation and shuts down its fleet participation with an ERROR (misconfiguration, not silently tolerated). |
| Clock skew | Irrelevant: liveness is store TTL only. |
| Slow store | All calls are asynchronous with timeouts; the event loop never blocks; login waits on a future with `claim-timeout`. |

### 4.8 Kubernetes

Node id defaults to the hostname, which is the pod name, so a restarted pod in
a StatefulSet keeps its id and reaps itself on start; a Deployment pod gets a
new id and the old one is reaped by TTL. `public-address` can be set from the
downward API (`status.podIP` or a per-pod Service). Discovery needs nothing
beyond the store; a headless service remains useful for operators to reach
individual nodes' management ports [S45]. A Lease-based store [S46] is listed
as a plugin candidate for clusters without Valkey, not shipped.

### 4.9 What stays plugin territory

Parties, friends, cross-proxy chat, punishments, queues, per-player data,
server selection policies, MOTD per virtual host, fleet-wide lockdown
policies beyond the notice. Plugins build them on `Fleet`, `FleetBus`,
`FleetPlayer` and the events, with one store connection owned by Warp. Warp
does not expose a raw store handle; a plugin that needs its own keys opens its
own client, which keeps Warp's key space and connection budget predictable.

### 4.10 Competitor issues avoided

| Issue | Problem | Warp's answer |
|-------|---------|---------------|
| Velocity #627 "should be done by a plugin" [S36] | Every network depends on one third-party plugin for a core property (who is online). | Narrow native core plus SPI; plugins keep the rest. |
| ValioBungee #109, #121, #130 counts wrong after restart [S33] | Counts derived from state that outlives the proxy that wrote it. | Counts come from live heartbeat hashes; a dead node contributes nothing after TTL. |
| Ghost players after crash (30 s timeout plus 1 min correction) [S34, S35] | Up to 90 s of "already online". | Incarnation plus reaper; same-id restart reaps at once; expired-key notice when available. |
| NTP requirement, 30 s skew breaks liveness [S31] | Operators debug phantom deaths. | No cross-node clock comparison. |
| `kick-when-online` surprises (#113) [S33] | Two code paths for duplicates. | One `DuplicateLoginPolicy` for local and remote. |
| No answer to "move players when a proxy dies" (#63) [S33] | Manual scripts. | Drain picks live peers; crash recovery is the client's reconnect to the balancer, documented. |
| Relocation conflicts (#126) [S33] | Shaded Jedis fights plugins. | No client library; RESP3 on Netty inside Warp. |
| Pub/sub only (lost messages) [S39] | Permanent divergence until restart. | Store is truth, replica reconciles every 30 s. |
| Redis licence drift [S43] | Legal uncertainty for operators. | Valkey (BSD) first-class; Redis 6.2+ compatible through the same commands. |

### 4.11 Phases

| Phase | Content | Size |
|-------|---------|------|
| F1 | `dev.warp.api.fleet` with `SingleNodeStore`; `Fleet` injected; `warp glist`, `warp find`, `warp fleet` on one node; events. Tests for the API contract. | S (2 days) |
| F2 | RESP3 Netty client, `ValkeyFleetStore` with functions, heartbeat, replica, reconcile, reaper, duplicate login, counts in status and query, `warp send`, bus and request. Integration tests against a Valkey container (Testcontainers in `integrationTest`), E2E in the testbed with two proxies and a killed node. | L (8 days) |
| F3 | Drain transfer target `fleet`, agent check reports drain, `VerifiedStore` on the fleet store, fleet-wide lockdown notice, metrics and dashboard panels. | M (3 days) |
| F4 | PrimeMC migration: its plugin drops presence and duplicate handling and uses `Fleet`; cluster mode validation; docs page `docs/fleet.md`. | M (3 days) |

Risks: Valkey functions unavailable on Redis 6.2 (fallback to `EVALSHA` is
in F2 from the start); keyspace notifications disabled on managed services
(design does not depend on them); large networks (10 000 players) make a full
reconcile read 10 000 hashes every 30 s per node, so reconcile reads
`node:<id>:players` sets first and fetches hashes only for differences.

---

## 5. Summary of decisions

1. Own allocation-free metric registry with per-event-loop striping; Prometheus
   text and OpenMetrics on the existing `/metrics`; OTLP/HTTP JSON push as an
   option; plugin API under `dev.warp.api.metrics`; cardinality caps enforced
   at registration. Micrometer rejected.
2. GS4 Query shipped, off by default, challenge bound to address and port in a
   fixed-size table, two rate limits, no exceptions on bad input, replies from
   a 2 s status snapshot, player list capped.
3. Native fleet with `dev.warp.api.fleet`, store SPI, Valkey first through an
   in-house RESP3 client on Netty, liveness by store TTL plus incarnation,
   atomic claim and reap functions, local replica with reconcile, one
   duplicate-login policy network-wide, drain targets from live peers.

Order of work: M1, M2 (metrics are needed to observe everything else), Q1,
F1, F2, M3, F3, Q2, F4. Total about 30 working days.

---

## 6. Sources

- [S1] Gate, OpenTelemetry guide: https://gate.minekube.com/guide/otel
- [S2] Gate, configuration reference (query block, no metrics key): https://gate.minekube.com/guide/config/
- [S3] Gate, self-hosted dashboard example: https://gate.minekube.com/guide/otel/self-hosted/dashboard.html
- [S4] Gate issues matching metrics or prometheus: https://github.com/minekube/gate/issues?q=metrics+OR+prometheus
- [S5] minecraft-prometheus-exporter README: https://github.com/sladkoff/minecraft-prometheus-exporter
- [S6] minecraft-prometheus-exporter issues (#310, #314): https://github.com/sladkoff/minecraft-prometheus-exporter/issues
- [S7] Spark, about spark metrics: https://spark.lucko.me/docs/misc/About-spark-metrics
- [S8] Velocity issues matching prometheus or metrics (#421, #476): https://github.com/PaperMC/Velocity/issues?q=prometheus+OR+metrics
- [S9] Envoy cluster statistics: https://www.envoyproxy.io/docs/envoy/latest/configuration/upstream/cluster_manager/cluster_stats
- [S10] Envoy listener statistics: https://www.envoyproxy.io/docs/envoy/latest/configuration/listeners/stats
- [S11] HAProxy Prometheus exporter README: https://github.com/haproxy/haproxy/blob/master/addons/promex/README
- [S12] Caddy metrics: https://caddyserver.com/docs/metrics
- [S13] OpenTelemetry semantic conventions, general metrics: https://opentelemetry.io/docs/specs/semconv/general/metrics/
- [S14] OpenTelemetry semantic conventions, RPC metrics: https://opentelemetry.io/docs/specs/semconv/rpc/rpc-metrics/
- [S15] OpenTelemetry semantic conventions, general attributes: https://opentelemetry.io/docs/specs/semconv/general/attributes/
- [S16] Prometheus, metric and label naming: https://prometheus.io/docs/practices/naming/
- [S17] Prometheus, instrumentation practices: https://prometheus.io/docs/practices/instrumentation/
- [S18] OpenMetrics 1.0 specification: https://prometheus.io/docs/specs/om/open_metrics_spec/
- [S19] Prometheus Java client 1.x: https://prometheus.github.io/client_java/
- [S20] Prometheus Java client, OTLP export: https://prometheus.github.io/client_java/otel/otlp/
- [S21] Micrometer Prometheus registry: https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html
- [S22] Micrometer OTLP registry: https://docs.micrometer.io/micrometer/reference/implementations/otlp.html
- [S23] OTLP specification: https://opentelemetry.io/docs/specs/otlp/
- [S24] Minecraft Wiki, Query protocol: https://minecraft.wiki/w/Query
- [S25] Velocity `GameSpyQueryHandler`: https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/GameSpyQueryHandler.java
- [S26] Velocity #1828, GS4 handler throws on malformed datagrams: https://github.com/PaperMC/Velocity/issues/1828
- [S27] Velocity configuration reference, query section: https://docs.papermc.io/velocity/configuration/
- [S28] BungeeCord `QueryHandler`: https://github.com/SpigotMC/BungeeCord/blob/master/query/src/main/java/net/md_5/bungee/query/QueryHandler.java
- [S29] Cloudflare, reflections on reflection attacks: https://blog.cloudflare.com/reflections-on-reflections/
- [S30] RedisBungee repository (redirects to ValioBungee): https://github.com/proxiodev/RedisBungee
- [S31] ValioBungee wiki, installation (Redis versions, NTP): https://github.com/ProxioDev/ValioBungee/wiki/Installation
- [S32] ValioBungee wiki, configuration: https://github.com/ProxioDev/ValioBungee/wiki/Configuration
- [S33] ValioBungee issues (#63, #109, #113, #121, #126, #129, #130): https://github.com/ProxioDev/ValioBungee/issues?q=is%3Aissue
- [S34] ValioBungee `ProxyDataManager` (heartbeats, correction task, dead proxies): https://github.com/ProxioDev/ValioBungee/blob/master/api/src/main/java/com/imaginarycode/minecraft/redisbungee/api/ProxyDataManager.java
- [S35] ValioBungee `RedisUtil` (`PROXY_TIMEOUT = 30`): https://github.com/ProxioDev/ValioBungee/blob/master/api/src/main/java/com/imaginarycode/minecraft/redisbungee/api/util/RedisUtil.java
- [S36] Velocity #627, add multi-velocity support (closed, plugin territory): https://github.com/PaperMC/Velocity/issues/627
- [S37] Velocity #1046, multiple listeners: https://github.com/PaperMC/Velocity/issues/1046
- [S38] Gate Connect guide: https://gate.minekube.com/guide/connect
- [S39] Valkey pub/sub: https://valkey.io/topics/pubsub/
- [S40] Valkey `EXPIRE`: https://valkey.io/commands/expire/
- [S41] Valkey distributed locks: https://valkey.io/topics/distlock/
- [S42] Valkey client-side caching: https://valkey.io/topics/client-side-caching/
- [S43] Redis, AGPLv3 licence option from Redis 8: https://redis.io/blog/agplv3/
- [S44] Lettuce: https://github.com/redis/lettuce
- [S45] Kubernetes headless services: https://kubernetes.io/docs/concepts/services-networking/service/#headless-services
- [S46] Kubernetes Leases: https://kubernetes.io/docs/concepts/architecture/leases/
- [S47] Redis session store pattern: https://redis.io/docs/latest/develop/use-cases/session-store/
- Warp: PR #158 body (PrimeMC real-client E2E, duplicate logins across two proxies): https://github.com/NeosiaNexus/Warp/pull/158
