# Warp macro benchmark - 2026-11-09-16-27-25

Profile: **smoke**. Proxies: Warp.

> This run was on WSL2. Timing numbers are not credible here (no CPU isolation, hypervisor scheduling noise). Treat this as a functional run only. See docs/benchmarks/README.md, hardware tiers.

## How to read this

Every number is measured from outside the proxy (the kernel's view in /proc and bot-side timing), never from the proxy's own metrics. Latency is measured from the intended send time to guard against coordinated omission. Each scenario warmed up before measuring and was repeated; tables show the median. Raw per-second samples are in the CSV files next to this report, and the exact configs under `configs/`.

## Fairness and natives

**Warp** (forwarding: modern) - natives confirmed
  - compression: 17:29:50 DEBUG compress   Compression: libdeflate 1.26 (linux-x86_64) from /home/neosianexus/.cache/warp/natives/libdeflate-1.26-linux-x86_64-b935df31e97b25e8.so
  - transport: 17:29:50 DEBUG warp       Using epoll native transport

CPU pinning: CPU pinning on: proxy on cores 0,1, backends on 2,3, bots on the rest.

## Idle connections

Resident memory, open file descriptors and CPU with N idle connections held in the PLAY state. This isolates the per-connection cost.

> Note: the JVM proxies run with `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` (the identical headline flags), so their resident memory is pinned near 2 GiB from start-up regardless of load. For the JVM proxies, read the growth between levels, not the absolute RSS; the absolute figure is meaningful only for the Go proxy (Gate), which sizes its heap to the live set. A later revision measures heap-live for the JVM proxies.

| Proxy | RSS @ 50 | FDs @ 50 | fail |
|---|--:|--:|--:|
| Warp | 2232 MiB | 128 | 0 |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="RSS at 50 idle connections (MiB)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">RSS at 50 idle connections (MiB)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">2232 MiB</text>
</svg>

## Heavy traffic (moving bots, loading chunks)

Bots walk and load chunks so the proxy forwards a real clientbound stream. Throughput is the proxy's read+write bytes per second at the syscall boundary; CPU per MB is proxy CPU-seconds divided by MB forwarded in the window.

| Proxy | bots | throughput | CPU % | CPU-s/MB | RSS |
|---|--:|--:|--:|--:|--:|
| Warp | 10 | 30 KiB/s | 6 | 2.075 | 2232 MiB |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="CPU seconds per MB forwarded (lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">CPU seconds per MB forwarded (lower is better)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">2.075</text>
</svg>

<svg xmlns="http://www.w3.org/2000/svg" width="560" height="200" role="img" aria-label="Proxy CPU during traffic">
<text x="8" y="14" font-size="13" font-weight="bold" fill="#1f2933">Proxy CPU during traffic (%)</text>
<line x1="36" y1="164" x2="550" y2="164" stroke="#d7dce2"/>
<line x1="36" y1="16" x2="36" y2="164" stroke="#d7dce2"/>
<text x="36" y="178" font-size="10" fill="#1f2933">0</text>
<text x="540" y="178" font-size="10" fill="#1f2933">185s</text>
<text x="4" y="24" font-size="10" fill="#1f2933">35</text>
<polyline fill="none" stroke="#3a7bd5" stroke-width="1.5" points="498.0,41.4 500.2,16.0 503.3,75.2 507.7,130.2 508.9,142.9 511.1,96.3 513.9,138.6 516.6,138.6 519.4,147.1 522.2,134.4 525.0,134.4 527.8,130.2 530.5,142.9 533.3,134.4 536.1,142.9 538.9,138.6 541.7,142.9 544.4,142.9 547.2,138.6 550.0,113.3"/>
<text x="440" y="20" font-size="11" fill="#3a7bd5">Warp</text>
</svg>

## Added latency at low load

Chat round-trip through the proxy versus straight to the backend. Added latency is the difference; it is the proxy's own cost on the path.

| Proxy | via proxy p50 | via proxy p99 | direct p50 | added p50 | added p99 |
|---|--:|--:|--:|--:|--:|
| Warp | 15.0 ms | 121.0 ms | 11.0 ms | 4.0 ms | 77.0 ms |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="Added p99 latency (ms, lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">Added p99 latency (ms, lower is better)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">77.0 ms</text>
</svg>

## Switch storm

Bots repeatedly run /server to change backend. Reports sustained switches per second, failures, and how long a switch took: from the command to the first chunk of the new backend (proxy hand-off plus backend login), and to five chunks (world arriving).

| Proxy | bots | switches | /s | failures | first chunk p50 | 5 chunks p50 | 5 chunks p99 |
|---|--:|--:|--:|--:|--:|--:|--:|
| Warp | 5 | 86 | 5.7 | 0 | 110 ms | 615 ms | 2480 ms |

## Connection / status storm

Hostile connections at a rising rate while a genuine player tries to join. The key question is whether the real player still gets in, and at what CPU and memory cost.

| Proxy | joined @ 500/s | peak CPU % |
|---|:--:|--:|
| Warp | yes | 26 |

## Exact versions

| Component | Version | SHA-256 |
|---|---|---|
| Backend | Paper 1.21.4 build 232 | `5ee4f542f628a14c...` |
| Warp | 0.1.0-beta.7 | `75ae9411d15d8429...` |
| Velocity 3.4.0 | 3.4.0 build 566 | `fb599cbda6a6d01d...` |
| Gate 0.76.3 | 0.76.3 | `35f01a9831594ebf...` |
| BungeeCord #2111 | 2111 | `1d9dbc31b57b22a3...` |

## Environment

```json
{
  "date": "2026-11-09T16:27:25.453Z",
  "host": "PC-TOUR-MATHEO",
  "platform": "linux",
  "arch": "x64",
  "kernel": "6.6.87.2-microsoft-standard-WSL2",
  "cpuModel": "AMD Ryzen 5 5500",
  "cpuCount": 10,
  "memTotalGiB": 23.5,
  "governor": null,
  "transparentHugepage": "always [madvise] never",
  "sysctl": {
    "net.ipv4.ip_local_port_range": "44620\t48715",
    "net.core.somaxconn": "4096",
    "fs.file-max": "9223372036854775807"
  },
  "ulimitNofile": "1048576",
  "node": "v22.22.3",
  "java": "",
  "isWsl": true,
  "harnessSha": "fc88a64",
  "proxies": {
    "warp": "0.1.0-beta.7",
    "velocity": "3.4.0-566",
    "gate": "0.76.3",
    "bungeecord": "2111"
  }
}
```
