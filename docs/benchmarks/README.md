# Macro benchmark: how to reproduce, and how results are published

This document covers the macro (whole-proxy) benchmark in [`bench/`](../../bench/README.md): how to
run it, the hardware it needs to produce credible numbers, how it stays fair, what other people have
published and where those attempts fell short, and how Warp's results are published. For the
micro-benchmarks (JMH, per-packet ns/op and B/op) and the four-layer measurement strategy, see
[`docs/research/benchmark-methodology.md`](../research/benchmark-methodology.md). This harness is
layer 3, the macro layer: the proxy is a black box, measured from the outside.

## Running it

```bash
bench/run.sh --smoke     # ~2 minutes, one proxy, ~50 bots: proves the harness works
bench/run.sh             # the full run
```

The full run, per proxy: boots three Paper backends configured for that proxy's forwarding, launches
the proxy, confirms its natives from the start-up log, samples it once a second while each scenario
runs, then tears everything down. Proxies run in a shuffled order. Everything downloaded is verified
against the SHA-256 in [`bench/versions.json`](../../bench/versions.json).

Options of note: `--proxies` to pick a subset, `--warp-jar` to benchmark a locally built Warp,
`--no-pin` to turn off CPU pinning, `--out` to choose the results directory. `--load-host` is
reserved for a second load-generation machine; single-machine mode is the default and uses `taskset`
to keep the proxy, the backends and the bots on separate cores when there are enough of them.

## Hardware tiers (what you may claim)

The methodology document's tiers apply here directly:

| Tier | Setup | What the numbers are good for |
|---|---|---|
| T0 (WSL2 / laptop) | develop and smoke-test the harness | nothing about timing: no CPU isolation, hypervisor noise. The report flags a WSL run. |
| T1 (minimum credible) | one rented bare-metal Linux host, >= 16 physical cores; proxy pinned to isolated cores, bots on the rest; many `127.x` source IPs | CPU-efficiency, capacity per core budget, relative latency. Loopback has no NIC cost. |
| T2 (gold) | two bare-metal hosts on >= 10 GbE; proxy alone on one, load on the other | every headline claim, including absolute latency over a real NIC |

The single-machine design (the default) targets T1. Run it on a dedicated server, not a shared VM or
WSL, for any number you intend to quote.

## How this harness avoids the known pitfalls

Prior MC-proxy numbers are weak for recurring reasons. Each row below is a documented pitfall and
what this harness does about it.

| Pitfall (seen in the wild) | What this harness does |
|---|---|
| **Synthetic or random payloads.** Velocity's maintainer (astei) dismissed a JMH compression benchmark because it compressed random data, which "cannot represent" real traffic ([Velocity #711](https://github.com/PaperMC/Velocity/issues/711)). | Real Paper backends send a real clientbound stream; bots move and load actual chunks. No synthetic packets in the traffic path. |
| **Coordinated omission.** Closed-loop generators back off during stalls, so latency looks far better than reality (Gil Tene, "How NOT to Measure Latency"). | The latency probe schedules sends at fixed intended times and measures from the intended time, not the actual send; it reports p50/p99/max, never a bare mean. |
| **No warm-up / runs too short.** JVM proxies need minutes of JIT warm-up; a 15 s run hides it (TechEmpower critiques). | Every scenario warms up before it measures, repeats, and the per-second CSV shows the ramp so a reader can see the JIT settle. |
| **Single run, best-case cherry-picking.** | Three repetitions per scenario (one in smoke), median reported, raw CSV published, proxies run in shuffled order. |
| **Undeclared memory metric.** Gate's "10 MB" is idle RSS; a JVM's heap is pre-sized. They are not comparable. | RSS is reported as RSS and labelled; the exact `-Xmx`, `-Xms` and GC are in the config and fingerprint. The idle scenario isolates per-connection growth. |
| **Asymmetric tuning / hidden natives.** "Velocity's natives weren't loaded" is the first objection to any Velocity comparison. | Identical headline JVM flags for the JVM proxies; the start-up log is parsed to confirm libdeflate and epoll, and a run that cannot confirm them is marked unfair in the report rather than compared. |
| **Comparing different workload classes.** Gate Lite only pipes bytes (no auth, no server switching). | Lite is labelled a reverse-proxy class and never placed head to head with the full proxies; its switch scenario is skipped, not reported as zero. |
| **Loopback inflating connection rates.** | Loopback is used for CPU-bound numbers; the report states that loopback has no NIC or IRQ cost, and T2 (two hosts) is the setup for latency claims. |
| **Idle bots proving nothing.** 500 idle bots can cost a proxy almost nothing. | Idle connections are a deliberate, separate scenario for per-connection memory; the throughput and latency numbers come from moving bots against a real backend whose TPS must stay healthy. |
| **Measuring the proxy from inside itself.** | Every number comes from the kernel's view in `/proc` and from bot-side timing. The proxy's own metrics are never the source. |
| **Bias not disclosed.** | The report states that the authors are Warp developers, and the harness is open for Velocity, Gate and BungeeCord maintainers to send config fixes. |

## Prior art (and why none of it settles the question)

No rigorous, reproducible, cross-proxy Minecraft benchmark has been published. What exists:

- **Velocity** ["Why Velocity"](https://docs.papermc.io/velocity/why-velocity/): libdeflate "twice as
  fast as zlib", "fewer resources than the competition". The only number is a library micro-benchmark,
  not a proxy measurement; no hardware or method. The page itself says it "may be somewhat biased".
- **Velocity [tuning page](https://docs.papermc.io/velocity/tuning/)**: documents the production JVM
  flags and sizing rules of thumb, with no measurement source. We use its flags in an appendix policy
  and the identical-flags policy for the headline.
- **The "disable backend compression" recommendation**: widely repeated, but not found on current
  Velocity docs; only Gate's docs make the "LAN backend, threshold -1" point. The scenario matrix in
  the methodology still includes an uncompressed-backend case, because that is where the folklore
  lives.
- **Gate** ["Why Gate"](https://gate.minekube.com/guide/why): "10MB RAM", "thousands of players". The
  10 MB is idle RSS with no load or config attached. The [Lite blog](https://connect.minekube.com/blog/gate-lite)
  shows two screenshots with no axes, versions or hardware.
- **"8x faster than BungeeCord"**: traced to hosting knowledge-base pages (e.g.
  [pufferfish.host](https://docs.pufferfish.host/general/velocity-vs-waterfall-vs-bungeecord/)), not
  to PaperMC. No baseline, workload or hardware. One hosting blog
  ([xgamingserver](https://xgamingserver.com/blog/bungeecord-vs-velocity/)) states outright that no
  independent benchmark numbers exist.
- **SoulFire blog**, ["Testing Minecraft Proxy Networks"](https://soulfiremc.com/blog/testing-minecraft-proxy-networks):
  "100+ connections per second, switches under 500ms, under 1GB RAM". Vendor blog for its own bot
  tool; no hardware, versions, run count, or how switch time and memory were measured.
- **Infrarust v2** `mc-bench`: the best MC methodology so far (open-loop, HdrHistogram, direct-baseline
  delta), but self-only, on one dev machine, with tiny opaque ping packets and no CPU-efficiency or
  capacity metric.
- **Bot load tools** (SoulFire, mc-bots, LambdaAttack, PureGero's stress test): all measure connection
  success, none produce a latency distribution or a CPU-per-byte figure. A Rust proxy's own README
  (bardiel) notes 500 fake players cost about 8% CPU, a reminder that idle bots measure little.

General load-testing references the harness takes seriously: Gil Tene on coordinated omission (open
loop, intended-time latency, HdrHistogram, full percentile spectrum) and the TechEmpower critiques
(averages are the wrong ranking statistic; runs must be long enough to expose GC; a standardised
client should enforce correct behaviour).

## What the live validation of each adapter found

Every adapter was run end to end in smoke mode on the development machine before the first rented
run, and each one needed a fix that a config-only review would have missed. Keep these in mind when
updating a proxy version:

- **Velocity**: without a `[forced-hosts]` table it fills in its example hosts, which name servers
  that do not exist, and refuses to start. bStats is opted out through `plugins/bStats/config.txt`.
  During a server switch Velocity kicked the bots with "A packet did not decode successfully":
  with `-Dvelocity.packet-decode-logging=true` it named a serverbound id 0x00 in the CONFIG state
  decoded as ClientSettings ("Bad VarInt"). The bot had sent a PLAY packet (a teleport confirmation
  is also 0x00) in the window after acknowledging the configuration re-entry, which a vanilla
  client never does. Warp drops such a packet; Velocity treats it as corrupt. The harness now
  guards every bot so that only configuration-phase packets leave it in that window
  (`guardConfigPhase` in `bench/src/load.js`). This is a bot behaviour, not a Velocity bug.
- **Gate (full and Lite)**: per-/24 connection and login quotas are on by default (burst 10), so a
  load generator on one address gets exactly ten connections in; the configs turn them off. Gate
  logs one line per connection at INFO, by default; that logging cost is part of what it measures.
- **BungeeCord**: boots with the mbed TLS native cipher and epoll confirmed from its log; it logs a
  WARNING per connection reset, which the storm scenario produces by design.
- **Warp**: its compressor and transport lines are logged at DEBUG, so the config raises those two
  loggers to make the natives check possible.

### Switch latency: where the time goes

The first Warp smoke run reported a 510 ms median switch, which looked slow. The harness measured a
switch from the `/server` command to five chunks of the new backend reaching the bot. Warp's own
millisecond log for the same run shows steady-state switches ("Switching" to "Server switch
complete") taking 80 to 180 ms, with one 1 s outlier when every bot switched at once on cold
backends. The remaining time is Paper logging the player in on the new backend and sending its first
chunks, plus the bot parsing them. The scenario now reports two numbers: time to the first chunk (proxy hand-off plus backend login) and
time to five chunks. Re-run with that split, the proxies agree on the hand-off: Warp 110 ms, Velocity
101 ms, BungeeCord 101 ms, Gate 301 ms to the first chunk (medians, smoke profile, WSL2). So the 510 ms
was not Warp's switch. The tail from the first to the fifth chunk differs (Warp 615 ms and Gate 502 ms
to five chunks against about 105 ms for Velocity and BungeeCord) and is governed by Paper's chunk
batching since 1.20.2, which paces chunk sends on the client's batch acknowledgements; why that pacing
lands differently behind different proxies is an open question for a tier T1 run, not something a WSL
smoke can settle. Compare the first-chunk column across proxies for the proxy's own cost, and treat
the five-chunk column as a backend-plus-client figure.

## Publishing

- Results are written under `bench/results/<date>/` with the report, raw CSVs, the exact configs and
  the host fingerprint. Only the smoke run's output is committed to this repository.
- A cross-proxy comparison is **not** published yet. Warp's compression passthrough and native path
  are still landing; until they do and the run is done on T1 or T2 hardware, the harness is used
  internally to drive the work. This mirrors the methodology document's recommendation.
- When results are published, they will be in a form a skeptic can re-run: pinned versions with
  checksums, full configs, the fingerprint, and the scenarios where Warp loses included.
- The smoke workflow (`.github/workflows/bench-smoke.yml`, weekly and `workflow_dispatch`) runs the
  harness in smoke mode against every proxy, one job each, and fails when a proxy does not launch,
  a scenario throws or no bot gets through, so the harness never rots.

## Updating the pinned versions

Edit [`bench/versions.json`](../../bench/versions.json). For each artifact, fetch it, record its
SHA-256, and update the entry:

- **Velocity**: the Paper fill API, `https://fill.papermc.io/v3/projects/velocity/versions/<ver>/builds/<n>`
  gives the URL and `checksums.sha256`. The pinned default is the latest 3.4.x build.
- **Gate**: the GitHub release's `gate_<ver>_linux_amd64` asset and its `checksums.txt`.
- **BungeeCord**: the SpigotMC Jenkins `lastSuccessfulBuild` artifact. BungeeCord publishes no
  checksum, so compute the SHA-256 yourself and pin it; the download is still verified against that
  pinned hash, and the report notes it is self-computed.
- **Paper backend**: the Paper fill API, like Velocity.
- **Warp**: the release asset on GitHub, or pass `--warp-jar` for a local build.
