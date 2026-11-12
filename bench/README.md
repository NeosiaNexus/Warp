# Warp macro benchmark

A public, reproducible macro benchmark of [Warp](../README.md) against Velocity, Gate (full and
Lite) and BungeeCord, run with one command on a single Linux machine (or two with `--load-host`).

```bash
bench/run.sh --smoke                 # two-minute sanity run (one proxy, ~50 bots) - try this first
bench/run.sh                         # full run: every proxy, every scenario
bench/run.sh --proxies warp,velocity # a subset
bench/run.sh --warp-jar path.jar     # benchmark a locally built Warp instead of the release
bench/run.sh --help
```

Requirements: Node.js 22+. Everything else (the JDKs, the proxies, the Paper backend) is downloaded
on first use, checked against the SHA-256 pinned in [`versions.json`](versions.json), and cached.
The benchmark reuses the end-to-end harness's bot client and helpers, so it installs the `e2e`
dependencies the first time; it adds no npm dependencies of its own.

## What it measures

| Scenario | What it answers | Headline metric |
|---|---|---|
| Idle connections | Per-connection cost | RSS, file descriptors and CPU at 500 / 2000 / 5000 idle connections |
| Heavy traffic | Forwarding efficiency | bytes written per second, CPU seconds per MB written |
| Added latency | The proxy's own cost on the path | tab-complete round-trip through the proxy minus direct (same background load on both), p50/p99 |
| Switch storm | Robustness under churn | sustained switches per second, failures, switch latency |
| Connection storm | Anti-flood behaviour | whether a real player still joins at 1k / 5k / 10k conn/s, CPU, memory |

Each scenario warms up, then measures over a fixed window, repeated three times (once in smoke mode);
tables report the median. The proxy is sampled once a second from `/proc` (never from its own
metrics); bytes forwarded are the proxy's bytes written, since a JVM's socket reads are not
accounted in `/proc/<pid>/io` while a Go runtime's are. The latency probe waits for its join burst
to end, discards a warm-up, counts a timed-out round at the timeout, and measures from the intended
send time to guard against coordinated omission.

## How it stays fair

Read [`docs/benchmarks/README.md`](../docs/benchmarks/README.md) for the full fairness argument and
the prior-art survey. In short: equivalent configuration on every proxy (offline mode, the same
compression threshold, modern forwarding for Warp/Velocity/Gate and legacy for BungeeCord, per-IP
rate limits off); identical JVM flags for the three JVM proxies; Go runtime defaults for Gate; the
start-up log is checked to confirm natives and the epoll transport actually loaded, and a run that
cannot confirm them is flagged; proxies run in a shuffled order; and Gate Lite is labelled as a
different workload class (it only pipes bytes) rather than compared head to head.

## Output

Results land in `bench/results/<date>/`:

- `README.md` - the report, with tables and inline SVG charts;
- `csv/<proxy>.csv` - every per-second sample;
- `configs/<proxy>-*` - the exact config each proxy ran with;
- `env.json` - the host fingerprint (CPU, kernel, sysctls, tool versions).

No results are committed to the repository except the smoke run's output. The project does not
publish a cross-proxy comparison until Warp's compression passthrough and native path have landed and
the run is done on real hardware; see the methodology document.

## Layout

```
bench/
  run.sh              one command; installs e2e deps, runs the CLI
  versions.json       pinned artifacts with SHA-256
  src/
    cli.js            option parsing and orchestration
    run.js            one proxy end to end: backends, launch, sample, scenarios
    proxies/          one adapter per proxy (config, launch, natives check)
    backend.js        Paper backends configured per forwarding mode
    rawclient.js      dependency-free idle client (thousands per process)
    load.js           bot pools: idle, traffic, probe, switch, storm
    scenarios.js      the five scenarios
    sample.js         per-second /proc sampler to CSV
    report.js         CSV + metrics to Markdown and SVG
    env.js            host fingerprint and CPU-pinning plan
  test/               node --test unit tests (no network)
```
