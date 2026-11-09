# Warp macro benchmark - 2026-11-09-15-43-14

Profile: **smoke**. Proxies: Warp.

> This run was on WSL2. Timing numbers are not credible here (no CPU isolation, hypervisor scheduling noise). Treat this as a functional run only. See docs/benchmarks/README.md, hardware tiers.

## How to read this

Every number is measured from outside the proxy (the kernel's view in /proc and bot-side timing), never from the proxy's own metrics. Latency is measured from the intended send time to guard against coordinated omission. Each scenario warmed up before measuring and was repeated; tables show the median. Raw per-second samples are in the CSV files next to this report, and the exact configs under `configs/`.

## Fairness and natives

**Warp** (forwarding: modern) - natives confirmed
  - compression: 16:44:27 DEBUG compress   Compression: libdeflate 1.26 (linux-x86_64) from /home/neosianexus/.cache/warp/natives/libdeflate-1.26-linux-x86_64-b935df31e97b25e8.so
  - transport: 16:44:27 DEBUG warp       Using epoll native transport

CPU pinning: CPU pinning on: proxy on cores 0,1, backends on 2,3, bots on the rest.

## Idle connections

Resident memory, open file descriptors and CPU with N idle connections held in the PLAY state. This isolates the per-connection cost.

> Note: the JVM proxies run with `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` (the identical headline flags), so their resident memory is pinned near 2 GiB from start-up regardless of load. For the JVM proxies, read the growth between levels, not the absolute RSS; the absolute figure is meaningful only for the Go proxy (Gate), which sizes its heap to the live set. A later revision measures heap-live for the JVM proxies.

| Proxy | RSS @ 50 | FDs @ 50 | fail |
|---|--:|--:|--:|
| Warp | 2226 MiB | 128 | 0 |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="RSS at 50 idle connections (MiB)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">RSS at 50 idle connections (MiB)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">2226 MiB</text>
</svg>

## Heavy traffic (moving bots, loading chunks)

Bots walk and load chunks so the proxy forwards a real clientbound stream. Throughput is the proxy's read+write bytes per second at the syscall boundary; CPU per MB is proxy CPU-seconds divided by MB forwarded in the window.

| Proxy | bots | throughput | CPU % | CPU-s/MB | RSS |
|---|--:|--:|--:|--:|--:|
| Warp | 10 | 26 KiB/s | 7 | 2.230 | 2227 MiB |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="CPU seconds per MB forwarded (lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">CPU seconds per MB forwarded (lower is better)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">2.230</text>
</svg>

<svg xmlns="http://www.w3.org/2000/svg" width="560" height="200" role="img" aria-label="Proxy CPU during traffic">
<text x="8" y="14" font-size="13" font-weight="bold" fill="#1f2933">Proxy CPU during traffic (%)</text>
<line x1="36" y1="164" x2="550" y2="164" stroke="#d7dce2"/>
<line x1="36" y1="16" x2="36" y2="164" stroke="#d7dce2"/>
<text x="36" y="178" font-size="10" fill="#1f2933">0</text>
<text x="540" y="178" font-size="10" fill="#1f2933">112s</text>
<text x="4" y="24" font-size="10" fill="#1f2933">38</text>
<polyline fill="none" stroke="#3a7bd5" stroke-width="1.5" points="463.1,160.1 467.2,16.0 471.8,105.6 476.4,97.8 481.0,125.1 485.6,136.7 490.2,136.7 494.8,144.5 499.4,136.7 504.0,140.6 508.6,136.7 513.2,136.7 517.8,136.7 522.4,148.4 527.0,136.7 531.6,144.5 536.2,140.6 540.8,144.5 545.4,148.4 550.0,121.2"/>
<text x="440" y="20" font-size="11" fill="#3a7bd5">Warp</text>
</svg>

## Added latency at low load

Chat round-trip through the proxy versus straight to the backend. Added latency is the difference; it is the proxy's own cost on the path.

| Proxy | via proxy p50 | via proxy p99 | direct p50 | added p50 | added p99 |
|---|--:|--:|--:|--:|--:|
| Warp | 21.0 ms | 155.0 ms | 16.0 ms | 5.0 ms | 86.0 ms |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="64" role="img" aria-label="Added p99 latency (ms, lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">Added p99 latency (ms, lower is better)</text>
<line x1="150" y1="20" x2="150" y2="56" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="300.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="456.0" y="41" font-size="12" fill="#1f2933">86.0 ms</text>
</svg>

## Switch storm

Bots repeatedly run /server to change backend. Reports sustained switches per second, failures, and how long a switch took (command sent to chunks from the new backend).

| Proxy | bots | switches | /s | failures | p50 | p99 |
|---|--:|--:|--:|--:|--:|--:|
| Warp | 3 | 59 | 3.9 | 2 | 510 ms | 1685 ms |

## Connection / status storm

Hostile connections at a rising rate while a genuine player tries to join. The key question is whether the real player still gets in, and at what CPU and memory cost.

| Proxy | joined @ 500/s | peak CPU % |
|---|:--:|--:|
| Warp | yes | 24 |

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
  "date": "2026-11-09T15:43:14.558Z",
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
  "harnessSha": "6856300",
  "proxies": {
    "warp": "0.1.0-beta.7",
    "velocity": "3.4.0-566",
    "gate": "0.76.3",
    "bungeecord": "2111"
  }
}
```
