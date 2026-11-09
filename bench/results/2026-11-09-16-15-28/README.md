# Warp macro benchmark - 2026-11-09-16-15-28

Profile: **smoke**. Proxies: Velocity 3.4.0.

> This run was on WSL2. Timing numbers are not credible here (no CPU isolation, hypervisor scheduling noise). Treat this as a functional run only. See docs/benchmarks/README.md, hardware tiers.

## How to read this

Every number is measured from outside the proxy (the kernel's view in /proc and bot-side timing), never from the proxy's own metrics. Latency is measured from the intended send time to guard against coordinated omission. Each scenario warmed up before measuring and was repeated; tables show the median. Raw per-second samples are in the CSV files next to this report, and the exact configs under `configs/`.

## Fairness and natives

**Velocity 3.4.0** (forwarding: modern) - natives confirmed
  - transport: [17:17:39 INFO]: Connections will use epoll channels, libdeflate (Linux x86_64) compression, OpenSSL 3.x.x (Linux x86_64) ciphers
  - compression: [17:17:39 INFO]: Connections will use epoll channels, libdeflate (Linux x86_64) compression, OpenSSL 3.x.x (Linux x86_64) ciphers

CPU pinning: CPU pinning on: proxy on cores 0,1, backends on 2,3, bots on the rest.

## Idle connections

Resident memory, open file descriptors and CPU with N idle connections held in the PLAY state. This isolates the per-connection cost.

> Note: the JVM proxies run with `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` (the identical headline flags), so their resident memory is pinned near 2 GiB from start-up regardless of load. For the JVM proxies, read the growth between levels, not the absolute RSS; the absolute figure is meaningful only for the Go proxy (Gate), which sizes its heap to the live set. A later revision measures heap-live for the JVM proxies.

| Proxy | RSS @ 50 | FDs @ 50 | fail |
|---|--:|--:|--:|
| Velocity 3.4.0 | 2224 MiB | 133 | 0 |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="RSS at 50 idle connections (MiB)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">RSS at 50 idle connections (MiB)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Velocity 3.4.0</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">2224 MiB</text>
</svg>

## Heavy traffic (moving bots, loading chunks)

Bots walk and load chunks so the proxy forwards a real clientbound stream. Throughput is the proxy's read+write bytes per second at the syscall boundary; CPU per MB is proxy CPU-seconds divided by MB forwarded in the window.

| Proxy | bots | throughput | CPU % | CPU-s/MB | RSS |
|---|--:|--:|--:|--:|--:|
| Velocity 3.4.0 | 10 | 20 KiB/s | 6 | 4.018 | 2227 MiB |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="CPU seconds per MB forwarded (lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">CPU seconds per MB forwarded (lower is better)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Velocity 3.4.0</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">4.018</text>
</svg>

<svg xmlns="http://www.w3.org/2000/svg" width="560" height="200" role="img" aria-label="Proxy CPU during traffic">
<text x="8" y="14" font-size="13" font-weight="bold" fill="#1f2933">Proxy CPU during traffic (%)</text>
<line x1="36" y1="164" x2="550" y2="164" stroke="#d7dce2"/>
<line x1="36" y1="16" x2="36" y2="164" stroke="#d7dce2"/>
<text x="36" y="178" font-size="10" fill="#1f2933">0</text>
<text x="540" y="178" font-size="10" fill="#1f2933">172s</text>
<text x="4" y="24" font-size="10" fill="#1f2933">63</text>
<polyline fill="none" stroke="#3a7bd5" stroke-width="1.5" points="490.5,135.8 493.2,16.0 496.1,133.5 499.1,107.6 502.1,140.5 505.1,98.2 508.1,135.8 511.1,147.6 514.1,149.9 517.1,145.2 520.1,147.6 523.1,138.2 526.1,149.9 529.1,154.6 532.0,149.9 535.0,149.9 538.0,149.9 541.0,152.3 544.0,157.0 548.2,161.7 550.0,98.2"/>
<text x="440" y="20" font-size="11" fill="#3a7bd5">Velocity 3.4.0</text>
</svg>

## Added latency at low load

Chat round-trip through the proxy versus straight to the backend. Added latency is the difference; it is the proxy's own cost on the path.

| Proxy | via proxy p50 | via proxy p99 | direct p50 | added p50 | added p99 |
|---|--:|--:|--:|--:|--:|
| Velocity 3.4.0 | 17.0 ms | 414.0 ms | 17.0 ms | 0.0 ms | 355.0 ms |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="Added p99 latency (ms, lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">Added p99 latency (ms, lower is better)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Velocity 3.4.0</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">355.0 ms</text>
</svg>

## Switch storm

Bots repeatedly run /server to change backend. Reports sustained switches per second, failures, and how long a switch took: from the command to the first chunk of the new backend (proxy hand-off plus backend login), and to five chunks (world arriving).

| Proxy | bots | switches | /s | failures | first chunk p50 | 5 chunks p50 | 5 chunks p99 |
|---|--:|--:|--:|--:|--:|--:|--:|
| Velocity 3.4.0 | 5 | 57 | 3.8 | 0 | 101 ms | 107 ms | 10200 ms |

## Connection / status storm

Hostile connections at a rising rate while a genuine player tries to join. The key question is whether the real player still gets in, and at what CPU and memory cost.

| Proxy | joined @ 500/s | peak CPU % |
|---|:--:|--:|
| Velocity 3.4.0 | yes | 45 |

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
  "date": "2026-11-09T16:15:29.009Z",
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
