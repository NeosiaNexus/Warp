# Warp macro benchmark

A public, reproducible macro benchmark of [Warp](../README.md) against Velocity, Gate (full and
Lite) and BungeeCord, run with one command on a single Linux machine (or two with `--load-host`).

```bash
bench/run.sh --smoke                 # sanity run of a few minutes (one proxy, a handful of bots) - try this first
bench/run.sh                         # full run: every proxy, every scenario
bench/run.sh --proxies warp,velocity # a subset
bench/run.sh --warp-jar path.jar     # benchmark a locally built Warp instead of the release
bench/run.sh --set traffic.bots=150  # override one size of the profile (repeatable)
bench/run.sh --report-only bench/results/<date>   # rebuild the report of an earlier run
bench/run.sh --help
```

Requirements: Node.js 22+. Everything else (the JDKs, the proxies, the Paper backend) is downloaded
on first use, checked against the SHA-256 pinned in [`versions.json`](versions.json), and cached.
The benchmark reuses the end-to-end harness's bot client and helpers, so it installs the `e2e`
dependencies the first time; it adds no npm dependencies of its own.

## What it measures

| Scenario | What it answers | Headline metric |
|---|---|---|
| Idle connections | Per-connection cost | live heap (JVM) or RSS (Go), file descriptors and CPU at 1000 / 5000 / 10000 connections at rest, and the memory slope between levels |
| Heavy traffic | Forwarding efficiency | bytes written per second, CPU seconds per MB written, with the same number of movers on every proxy |
| Added latency | The proxy's own cost on the path | PLAY ping round trip through the proxy minus direct (same background load on both), p50/p99 in fractional milliseconds |
| Switch storm | Robustness under churn | sustained switches per second, failures, switch latency |
| Connection storm | What a flood of connections costs | whether a real player still joins at 1k / 5k / 10k conn/s, CPU |

Each scenario warms up, then measures over a fixed window, repeated three times (once in smoke mode);
tables report the median. The proxy is sampled once a second from `/proc` (never from its own
metrics); bytes forwarded are the proxy's bytes written, since a JVM's socket reads are not
accounted in `/proc/<pid>/io` while a Go runtime's are. A JVM proxy's live heap is read with `jcmd`
after each window. The latency probe runs in a process and on a core of its own, waits for its join
burst to end, discards a warm-up, counts a timed-out round at the timeout, and charges a round that
starts behind schedule from its intended send time to guard against coordinated omission.

## When a number is printed, and when it is not

A measurement is only printed when it passed the validity gate (`src/validity.js`): the offered
connections were established and kept (99.5% at least), the backends held their tick rate and
logged no error, the load generators and the stub backend were not saturated, and the proxy engaged
no protection. Otherwise the report says **INVALID** and why. The backends are sized and tuned so
that this holds: the idle scenario runs against a minimal stub backend (`src/stubbackend.js`) that
keeps thousands of connections really idle, and the others against Paper servers pinned to half the
machine. The thresholds and the reasoning are in
[`docs/benchmarks/README.md`](../docs/benchmarks/README.md).

## How it stays fair

Read [`docs/benchmarks/README.md`](../docs/benchmarks/README.md) for the full fairness argument and
the prior-art survey. In short: equivalent configuration on every proxy (offline mode, the same
compression threshold, modern forwarding for Warp/Velocity/Gate and legacy for BungeeCord, per-IP
rate limits off, and Warp's anti-bot off since no other proxy has one); identical JVM flags for the three JVM proxies; Go runtime defaults for Gate; the
start-up log is checked to confirm natives and the epoll transport actually loaded, and a run that
cannot confirm them is flagged; proxies run in a shuffled order; and Gate Lite is labelled as a
different workload class (it only pipes bytes) rather than compared head to head.

## Output

Results land in `bench/results/<date>/`:

- `README.md` - the report, with tables and inline SVG charts;
- `metrics/<proxy>.json` - every measurement with its repetitions, the health of the backends and
  the validity verdict, written as soon as that proxy finishes;
- `csv/<proxy>.csv` - every per-second sample;
- `run.json` - the sizes, the CPU plan, the thresholds and the versions of the run;
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
    run.js            one proxy end to end: backends, a fresh proxy per scenario, sampling
    proxies/          one adapter per proxy (config, launch, natives check, protection lines)
    backend.js        Paper backends per forwarding mode, their health, the stub's process
    stubbackend.js    minimal backend that holds idle connections (idle scenario)
    rawclient.js      dependency-free client: idle, mover, ping probe (thousands per process)
    load.js           the load generators: raw pools, switch bots, probe, flood
    worker.js         one load-generator process; fleet.js drives them from the orchestrator
    scenarios.js      the five scenarios
    validity.js       the validity gate and its thresholds
    heap.js           live heap of a JVM proxy through jcmd, memory slope
    sample.js         per-second /proc sampler to CSV
    store.js          results on disk, report rebuilt from them
    report.js         metrics to Markdown and SVG
    env.js            host fingerprint and CPU-pinning plan
  test/               node --test unit tests (no network)
```
