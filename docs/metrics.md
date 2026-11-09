# Metrics

Warp exposes its metrics on the management server, `GET /metrics`, in the Prometheus text format
(version 0.0.4) or in OpenMetrics 1.0 when the `Accept` header asks for it. Prometheus and the
OpenTelemetry collector scrape it as they would any exporter. The registry is Warp's own: no
Micrometer, no client library, and nothing allocated per packet on the forwarding path (see
[the design](research/observability-and-fleet.md), section 2).

```hocon
management {
  enabled = true
  bind = "127.0.0.1:9901"
  metrics {
    enabled = true               # GET /metrics answers
    require-token = false        # Authorization: Bearer <management token> to read them
    server-ping-interval = 15s   # backend pings for warp_server_rtt_seconds; 0 turns them off
  }
}
```

The endpoint follows the management server's bind, TLS and token rules. The default bind is
loopback; when it is not, and neither `management.tls` nor `require-token` is on, Warp logs
`W-MGMT-003` at startup. `?filter=warp_server_` restricts the answer to one prefix, for cheap
per-dashboard scrapes, and the answer is gzipped when `Accept-Encoding` allows.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: warp
    static_configs:
      - targets: ["proxy-1:9901", "proxy-2:9901"]
    # authorization: { credentials_file: /etc/prometheus/warp.token }   # with require-token
```

## Rules

- Prefix `warp_`. Units are base units in the suffix (`_seconds`, `_bytes`); counters end with
  `_total`.
- Labels come from a closed set (an outcome, a state), a configuration-bounded name (the `server`
  label is the registered backend name) or a small index. No metric has a label per player or
  address.
- One label has at most 32 distinct values for a plugin's metric (256 for Warp's own); further
  values are recorded as `other`, counted in `warp_metrics_label_overflow_total`, and logged once
  (`W-MET-001`). A plugin has at most 2 000 series, the registry 10 000.
- Each collector and pull gauge is isolated: one that throws is skipped for that scrape, counted in
  `warp_metrics_collector_errors_total` and logged once (`W-MET-002`); the other metrics are served.
  A plugin collector's samples must belong to the family it started, with valid label names:
  anything else fails the collector, never the scrape.
- Names are stable: a metric that goes away is removed in a major version, never renamed in place.

Histogram buckets are fixed per metric. Durations use one of three sets: `fast` (1 ms to 2.5 s),
`network` (5 ms to 30 s) and `long` (0.1 s to 10 min).

## Catalogue

### Clients

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_connections_accepted_total` | counter | `intent` (`status`, `login`, `transfer`) | TCP connections that reached a handshake. |
| `warp_connections_active` | gauge | `state` (`handshake`, `status`, `login`, `configuration`, `play`) | Live client connections by protocol state. |
| `warp_connections_pending` | gauge | | Connections not logged in yet (anti-bot). |
| `warp_connections_pending_max` | gauge | | The cap on connections not logged in; 0 for none. |
| `warp_connections_refused_total` | counter | `reason` | Connections refused or closed before login, by reason (anti-bot and limits). |
| `warp_logins_total` | counter | `outcome` (`success`, `refused_antibot`, `refused_duplicate`, `refused_full`, `auth_failed`, `auth_timeout`, `protocol_unsupported`, `cancelled_plugin`, `disconnected`, `other`) | One per login attempt. |
| `warp_login_duration_seconds` | histogram (`network`) | `outcome` (`success`, `failed`) | From the handshake to the login's end. |
| `warp_status_pings_total` | counter | `cached` (`true`, `false`) | Server list pings: answered from the cache, or built for a plugin listening to `ProxyPingEvent`. |
| `warp_client_protocol_version` | gauge | `version` (protocol number) | Players online by their client's protocol version. |
| `warp_players_online` | gauge | | Players in PLAY on this proxy. |
| `warp_players_online_network` | gauge | | Players on the whole network: the sum of the live fleet nodes' heartbeats, this proxy's count alone without a fleet store. |

### Forwarding

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_forwarded_packets_total` | counter | `direction` (`c2s`, `s2c`), `mode` (`blind`, `decoded`) | Packets handled in PLAY: `blind` were forwarded as the frames they arrived in, `decoded` were read by the proxy. |
| `warp_forwarded_bytes_total` | counter | `direction`, `mode` | Wire bytes of those packets. |
| `warp_compression_passthrough_total` | counter | `direction`, `result` (`passthrough`, `recompressed`) | Compressed frames forwarded as received, or inflated and compressed again because the other side's threshold differs. |
| `warp_compression_bytes_total` | counter | `direction`, `side` (`compressed`, `raw`) | Bytes of the frames received compressed, on the wire and once inflated: `compressed / raw` is the ratio. |
| `warp_packet_drops_total` | counter | `reason` (`oversized`, `invalid_frame`, `state`) | Frames the decoder refused. |

`direction` is the packet's direction: `c2s` from the client towards a backend, `s2c` from a
backend towards the client. The blind ratio of a proxy is
`rate(warp_forwarded_packets_total{mode="blind"}[5m]) / rate(warp_forwarded_packets_total[5m])`.

### Backends

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_server_connections_active` | gauge | `server` | Players attached to each backend. |
| `warp_server_connects_total` | counter | `server`, `outcome` (`success`, `refused`, `timeout`, `reset`, `protocol`, `kicked`, `cancelled`, `other`) | Connection attempts to backends. |
| `warp_server_connect_duration_seconds` | histogram (`network`) | `server` | From the attempt to the backend's acceptance. |
| `warp_server_switches_total` | counter | `outcome` (`success`, `failed`, `cancelled`) | Server switches of connected players; no `from` x `to` pair, which would be quadratic. |
| `warp_server_bytes_total` | counter | `server`, `direction` | Wire bytes forwarded to and from each backend. |
| `warp_server_rtt_seconds` | gauge | `server` | Round trip of the proxy's last status ping to the backend (`server-ping-interval`). |
| `warp_server_health` | gauge | `server` | 1 when the last ping was answered, 0 otherwise. |
| `warp_server_breaker_state` | gauge | `server` | Reserved: 0 closed, 1 half open, 2 open. Lands with the routing and load balancing work. |

### The proxy

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_info` | gauge | `version`, `commit`, `java` | Always 1. |
| `warp_start_time_seconds` | gauge | | When the proxy started, Unix time. |
| `warp_event_loop_lag_seconds` | histogram (`fast`) | | How late a task scheduled every second on each worker loop ran: the loops' saturation, the first signal of an overloaded proxy. |
| `warp_event_loop_pending_tasks` | gauge | `loop` (index) | Tasks waiting on each worker loop, at scrape time. |
| `warp_direct_memory_bytes` | gauge | | Direct memory Netty's pooled allocator holds. |
| `warp_drain_phase` | gauge | | 0 idle, then the drain's phases in order. |
| `warp_drain_players_remaining` | gauge | | Players the drain still waits for. |
| `warp_drain_duration_seconds` | gauge | | How long the current or last drain has run. |
| `warp_drain_transfers_total` | counter | `outcome` (`sent`, `unsupported`, `not_ready`, `disconnected`) | Transfers the drain attempted, by `TransferResult`. |
| `warp_plugin_event_duration_seconds` | histogram (`fast`) | `event` (the API event's name) | Time one handler took on one event. |
| `warp_plugin_handler_errors_total` | counter | `plugin` | Uncaught exceptions from event handlers. |
| `warp_metrics_label_overflow_total` | counter | `metric` | Label values recorded as `other`. |
| `warp_metrics_collector_errors_total` | counter | `collector` | Scrapes a collector failed and was skipped from. |
| `warp_antibot_level`, `warp_antibot_lockdown`, `warp_antibot_verified_identities`, `warp_antibot_verified_capacity`, `warp_antibot_blocklisted` | gauge | | The anti-bot, as before. |
| `warp_antibot_signal_per_second`, `warp_antibot_signal_baseline` | gauge | `signal` | Each anti-bot signal over the last second, and its learned baseline. |
| `warp_auth_in_flight`, `warp_auth_lane_waiting` | gauge | | Online-mode authentications running and waiting in line. |
| `warp_auth_lane_wait_seconds` | summary | | How long logins granted an authentication from the line waited. |
| `warp_antibot_verifying`, `warp_antibot_queue_length` | gauge | | Verifications of unknown identities running, and logins waiting in line for one. |
| `warp_antibot_queue_wait_seconds` | summary | | How long logins granted a verification from the line waited. |
| `warp_antibot_verifications_total` | counter | `result` | Verifications by result: `passed`, `escalated`, `busy`, or `failed_<reason>`. |
| `warp_antibot_tokens_total` | counter | `result` | Verification tokens `issued` to transferred clients, and `accepted` or `rejected` on return. |

### The fleet

Present always; on a single node the store is up, there is one node and nothing remote.

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `warp_fleet_store_up` | gauge | | 1 while the fleet store answers (always 1 on a single node), 0 while it is unreachable. |
| `warp_fleet_nodes` | gauge | `state` (`ready`, `draining`, `down`) | Proxies of the fleet, this one included, by the state of their last heartbeat. |
| `warp_fleet_players_remote` | gauge | | Players on the other proxies, as the local replica knows them. |
| `warp_fleet_claims_total` | counter | `outcome` (`claimed`, `owned_kicked`, `owned_refused`, `timeout`, `store_down`) | Logins settled against the fleet: the account was free, held elsewhere and kicked, held elsewhere and refused, not settled in `claim-timeout`, or settled locally because the store was down. |
| `warp_fleet_bus_messages_total` | counter | `direction` (`in`, `out`) | Messages on the fleet's channels: presence notices, requests and replies, plugin messages. |
| `warp_fleet_reaped_players_total` | counter | | Player records this proxy removed for a proxy whose heartbeat expired, or for its own previous life. |
| `warp_fleet_reconcile_corrections_total` | counter | `kind` (`node_added`, `node_removed`, `player_added`, `player_removed`, `player_moved`, `own_replayed`, `own_released`) | What a refresh or a reconcile repaired: non-zero means a notice was lost or a node died without a goodbye. |
| `warp_fleet_store_commands_total` | counter | `command`, `outcome` (`ok`, `failed`) | Calls to the store: `heartbeat`, `nodes`, `players`, `claim`, `release`, `move`, `reap`, `incr`. |
| `warp_fleet_store_latency_seconds` | histogram | `command` | How long the store takes to answer each command. |

### The JVM and the process

Read from JMX at scrape time, under the names the Prometheus Java clients use, so that the
standard JVM dashboards work. CPU and memory limits come from the JDK's container support: in a
container they are the cgroup's, not the host's.

| Metric | Type | Labels |
|--------|------|--------|
| `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes` | gauge | `area` (`heap`, `nonheap`), `pool` |
| `jvm_gc_collections_total`, `jvm_gc_collection_seconds_total` | counter | `gc` |
| `jvm_gc_pause_seconds` | histogram (`fast`) | `gc` |
| `jvm_threads_live`, `jvm_threads_daemon`, `jvm_threads_peak`, `jvm_classes_loaded` | gauge | |
| `jvm_info` | gauge | `version`, `vendor`, `runtime` |
| `process_start_time_seconds`, `process_cpu_count`, `process_memory_limit_bytes`, `process_open_fds`, `process_max_fds` | gauge | |
| `process_cpu_seconds_total` | counter | |

## Plugins

A plugin registers its metrics through `PluginContext.metrics()`. Names are prefixed with the
plugin's id (`my-plugin` registers `lookups_total` as `my_plugin_lookups_total`), never with
`warp_`, `jvm_` or `process_`, and everything a plugin registered is removed when it is disabled.

```java
Metrics metrics = context.metrics();
Counter lookups = metrics.counter("lookups_total", "Permission lookups.", "result");
Counter.Child hits = lookups.labels("hit");     // resolve once, keep it
Counter.Child misses = lookups.labels("miss");
Histogram load = metrics.histogram("load_seconds", "Data loads.", Buckets.LONG);
metrics.gauge("cache_entries", "Cached permissions.", cache::size);   // read when scraped

hits.inc();                       // on any thread; a plain add on an event loop
load.labels().observeNanos(System.nanoTime() - start);
```

Resolve children once, when the code that updates them is set up: `labels(...)` is a map lookup,
the increment is not. A `Collector` bridges another library's registry, for plugins that already
use Micrometer: it writes its samples when scraped, under the plugin's prefix.

## Cost

Counter and histogram children keep one slot per worker event loop, 128 bytes apart: an increment
on the loop is a plain read and an opaque write to the loop's own slot, with no allocation, lock,
atomic instruction or false sharing. Threads without a slot (virtual threads running plugin code)
add through a `LongAdder`. The scrape sums the slots. `MetricsAllocationTest` checks with the
thread's allocation counter that increments, observations and the forwarding observers allocate
nothing; `CounterBenchmark` (`./gradlew :proxy:jmh -Pjmh.includes=CounterBenchmark`) compares the
striped increment with `LongAdder` and `AtomicLong` under contention.

## Status

Milestones M1 (registry, exposition, JVM collectors) and M2 (instrumentation of the proxy) of the
design are shipped. M3 (OTLP push, the `warp metrics` command, Grafana dashboards) is not: the
registry's `Metrics.collect(SampleVisitor)` is the seam an OTLP exporter reads from.
