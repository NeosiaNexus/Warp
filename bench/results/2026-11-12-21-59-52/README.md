# Warp macro benchmark - 2026-11-12-21-59-52

Profile: **smoke**. Proxies: Warp, Velocity 3.4.0, Gate 0.76.3, Gate Lite, BungeeCord #2111.

> Smoke profile: a handful of bots and windows of a few seconds. It proves that the harness runs end to end against each proxy; its figures are not benchmark results.

## How to read this

Every number is measured from outside the proxy (the kernel's view in /proc, the JVM's own heap accounting through jcmd, and client-side timing), never from the proxy's own metrics. Each scenario warmed up before measuring and was repeated; tables show the median of the repetitions. Raw per-second samples are in the CSV files next to this report, the full per-repetition records in `metrics/`, and the exact configs under `configs/`.

A figure is printed only when the measurement passed the validity gate: at least 99.5% of the offered connections established and kept, every Paper backend at 19 TPS or more with no overload warning and no error, the stub backend and the load generators not saturated (event loop at most 50 ms late at p99), and no proxy protection engaged. Otherwise the cell says INVALID and the reasons are listed under the table.

## Fairness and natives

**Warp** (forwarding: modern) - natives confirmed
  - compression: 22:05:01 DEBUG compress   Compression: libdeflate 1.26 (linux-x86_64) from /root/.cache/warp/natives/libdeflate-1.26-linux-x86_64-b935df31e97b25e8.so
  - transport: 22:05:01 DEBUG warp       Using epoll native transport

**Velocity 3.4.0** (forwarding: modern) - natives confirmed
  - transport: [22:07:48 INFO]: Connections will use epoll channels, libdeflate (Linux x86_64) compression, OpenSSL 3.x.x (Linux x86_64) ciphers
  - compression: [22:07:48 INFO]: Connections will use epoll channels, libdeflate (Linux x86_64) compression, OpenSSL 3.x.x (Linux x86_64) ciphers
  - cipher: [22:07:48 INFO]: Connections will use epoll channels, libdeflate (Linux x86_64) compression, OpenSSL 3.x.x (Linux x86_64) ciphers

**Gate 0.76.3** (forwarding: modern) - natives none to check (no native compression or transport to load; standard library)
  - listening: 2026-11-12T22:02:35.662Z	INFO	java	proxy/proxy.go:741	listening for connections	{"addr": "127.0.0.1:25565"}

**Gate Lite** (forwarding: none) - natives none to check (no native compression or transport to load; standard library)
  - listening: 2026-11-12T22:00:30.294Z	INFO	java	proxy/proxy.go:741	listening for connections	{"addr": "127.0.0.1:25565"}

**BungeeCord #2111** (forwarding: legacy) - natives NOT CONFIRMED (run marked unfair)
  - transport: 22:12:12 [INFO] Epoll is working, utilising it!
  - compression: NOT CONFIRMED (pattern /native compressor/i)
  - cipher: 22:12:12 [INFO] Using mbed TLS based native cipher.

Every proxy runs with equivalent protections, which means none: per-address rate limits and quotas are off for all of them and Warp's anti-bot (verification and attack detection, which no other proxy here has) is off. Each scenario starts a fresh proxy process.

CPU pinning: CPU pinning on (16 cores): proxy on 0-3, backends on 4-11, 3 load worker(s) on 12-14, latency probe on 15; the harness itself runs on the load cores.

Sizes: `{"idle":{"levels":[50,100],"warmupMs":3000,"measureMs":8000,"reps":1,"ratePerSec":100},"traffic":{"bots":10,"warmupMs":4000,"measureMs":12000,"reps":1,"ratePerSec":10},"latency":{"samples":100,"warmup":20,"intervalMs":20,"timeoutMs":2000,"backgroundBots":5,"ratePerSec":10},"switch":{"bots":4,"durationMs":15000,"pauseMs":1000,"ratePerSec":4},"storm":{"rates":[500],"durationMs":8000}}`

## Idle connections

N connections held in the PLAY state against the stub backend, which sends each of them one keep-alive every 15 seconds and nothing else: the connections are really idle, so this isolates what a connection at rest costs the proxy. "Connected" is the lowest count still connected at the end of a window, over the repetitions.

The JVM proxies run with `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` (the identical headline flags), so their resident memory sits near 2.3 GiB from start-up whatever they hold: for them, read the live heap (heap in use after a forced full collection, taken after each window). A Go proxy has no fixed heap: its resident memory is the figure to read.

| Proxy | offered | connected | RSS | live heap | FDs | CPU % |
|---|--:|--:|--:|--:|--:|--:|
| Warp | 50 | 50 | 2255 MiB | 17 MiB | 139 | 1 |
| Warp | 100 | 100 | 2256 MiB | 16 MiB | 240 | 1 |
| Velocity 3.4.0 | 50 | 50 | 2227 MiB | 14 MiB | 157 | 0 |
| Velocity 3.4.0 | 100 | 100 | 2234 MiB | 14 MiB | 258 | 0 |
| Gate 0.76.3 | 50 | 50 | 31 MiB | - | 108 | 0 |
| Gate 0.76.3 | 100 | 100 | 34 MiB | - | 208 | 0 |
| Gate Lite | 50 | 50 | 29 MiB | - | 108 | 0 |
| Gate Lite | 100 | 100 | 34 MiB | - | 208 | 0 |
| BungeeCord #2111 | 50 | 50 | 2217 MiB | 54 MiB | 214 | 0 |
| BungeeCord #2111 | 100 | 100 | 2232 MiB | 55 MiB | 315 | 0 |

Memory per connection, as the slope between the lowest and the highest valid level (one absolute figure would mostly show the fixed cost of the runtime):

| Proxy | levels | RSS per connection | live heap per connection |
|---|--:|--:|--:|
| Warp | 50 to 100 | 36.9 KiB | -12.3 KiB |
| Velocity 3.4.0 | 50 to 100 | 133.1 KiB | 10.2 KiB |
| Gate 0.76.3 | 50 to 100 | 71.7 KiB | - |
| Gate Lite | 50 to 100 | 96.3 KiB | - |
| BungeeCord #2111 | 50 to 100 | 307.2 KiB | 10.2 KiB |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="176" role="img" aria-label="Memory per idle connection (KiB): live heap for a JVM, RSS for a Go proxy">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">Memory per idle connection (KiB): live heap for a JVM, RSS for a Go proxy</text>
<line x1="150" y1="20" x2="150" y2="168" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="1.0" height="16" rx="3" fill="#3a7bd5"/>
<text x="157.0" y="41" font-size="12" fill="#1f2933">-12.3 KiB</text>
<text x="142" y="66" text-anchor="end" font-size="12" fill="#1f2933">Velocity 3.4.0</text>
<rect x="150" y="56" width="31.9" height="16" rx="3" fill="#2bb673"/>
<text x="187.9" y="69" font-size="12" fill="#1f2933">10.2 KiB</text>
<text x="142" y="94" text-anchor="end" font-size="12" fill="#1f2933">Gate 0.76.3</text>
<rect x="150" y="84" width="223.4" height="16" rx="3" fill="#e8833a"/>
<text x="379.4" y="97" font-size="12" fill="#1f2933">71.7 KiB</text>
<text x="142" y="122" text-anchor="end" font-size="12" fill="#1f2933">Gate Lite</text>
<rect x="150" y="112" width="300.0" height="16" rx="3" fill="#9b59b6"/>
<text x="456.0" y="125" font-size="12" fill="#1f2933">96.3 KiB</text>
<text x="142" y="150" text-anchor="end" font-size="12" fill="#1f2933">BungeeCord #2111</text>
<rect x="150" y="140" width="31.9" height="16" rx="3" fill="#d7504b"/>
<text x="187.9" y="153" font-size="12" fill="#1f2933">10.2 KiB</text>
</svg>

## Heavy traffic (movers streaming chunks)

Raw clients walk back and forth at sprint speed on one Paper backend, so the backend streams chunks through the proxy for the whole window. Throughput is the proxy's bytes written per second at the syscall boundary (writes alone: every forwarded byte is written exactly once by every proxy, while read accounting differs between a JVM and a Go runtime); "delivered" is what the clients received, counted on their sockets; CPU per MB is proxy CPU-seconds divided by MB written in the window. The comparison only holds because every proxy carried the same number of bots: a proxy that did not is INVALID.

| Proxy | offered | connected | throughput | delivered | CPU % | CPU-s/MB | RSS | live heap | backend TPS |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| Warp | 10 | 10 | 15 KiB/s | 20 KiB/s | 12 | 6.763 | 2253 MiB | 15 MiB | 20.0 |
| Velocity 3.4.0 | 10 | 10 | 18 KiB/s | 20 KiB/s | 12 | 6.721 | 2235 MiB | 18 MiB | 20.0 |
| Gate 0.76.3 | 10 | 10 | 20 KiB/s | 20 KiB/s | 8 | 3.076 | 49 MiB | - | 20.0 |
| Gate Lite | 10 | 10 | 18 KiB/s | 17 KiB/s | 4 | 1.978 | 27 MiB | - | 20.0 |
| BungeeCord #2111 | 10 | 10 | 16 KiB/s | 21 KiB/s | 12 | 10.070 | 2221 MiB | 58 MiB | 20.0 |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="176" role="img" aria-label="CPU seconds per MB forwarded (lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">CPU seconds per MB forwarded (lower is better)</text>
<line x1="150" y1="20" x2="150" y2="168" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="201.5" height="16" rx="3" fill="#3a7bd5"/>
<text x="357.5" y="41" font-size="12" fill="#1f2933">6.763</text>
<text x="142" y="66" text-anchor="end" font-size="12" fill="#1f2933">Velocity 3.4.0</text>
<rect x="150" y="56" width="200.2" height="16" rx="3" fill="#2bb673"/>
<text x="356.2" y="69" font-size="12" fill="#1f2933">6.721</text>
<text x="142" y="94" text-anchor="end" font-size="12" fill="#1f2933">Gate 0.76.3</text>
<rect x="150" y="84" width="91.6" height="16" rx="3" fill="#e8833a"/>
<text x="247.6" y="97" font-size="12" fill="#1f2933">3.076</text>
<text x="142" y="122" text-anchor="end" font-size="12" fill="#1f2933">Gate Lite</text>
<rect x="150" y="112" width="58.9" height="16" rx="3" fill="#9b59b6"/>
<text x="214.9" y="125" font-size="12" fill="#1f2933">1.978</text>
<text x="142" y="150" text-anchor="end" font-size="12" fill="#1f2933">BungeeCord #2111</text>
<rect x="150" y="140" width="300.0" height="16" rx="3" fill="#d7504b"/>
<text x="456.0" y="153" font-size="12" fill="#1f2933">10.070</text>
</svg>

<svg xmlns="http://www.w3.org/2000/svg" width="560" height="200" role="img" aria-label="Proxy CPU during traffic">
<text x="8" y="14" font-size="13" font-weight="bold" fill="#1f2933">Proxy CPU during traffic (%)</text>
<line x1="36" y1="164" x2="550" y2="164" stroke="#d7dce2"/>
<line x1="36" y1="16" x2="36" y2="164" stroke="#d7dce2"/>
<text x="36" y="178" font-size="10" fill="#1f2933">0</text>
<text x="540" y="178" font-size="10" fill="#1f2933">94s</text>
<text x="4" y="24" font-size="10" fill="#1f2933">50</text>
<polyline fill="none" stroke="#3a7bd5" stroke-width="1.5" points="450.1,164.0 455.5,78.2 461.0,125.5 466.5,134.4 472.0,107.8 477.5,101.8 483.0,146.2 488.5,149.2 494.0,146.2 499.5,128.5 505.0,110.7 510.5,101.8 516.0,87.0 521.4,134.4 526.9,125.5 532.4,143.3 537.9,146.2 543.4,84.1 548.9,155.1"/>
<text x="440" y="20" font-size="11" fill="#3a7bd5">Warp</text>
<polyline fill="none" stroke="#2bb673" stroke-width="1.5" points="441.8,164.0 447.3,57.4 452.8,54.5 458.3,87.0 463.8,81.1 469.3,104.8 474.8,131.4 480.3,122.6 485.8,131.4 491.2,146.2 496.7,149.2 502.2,90.0 507.7,113.7 513.2,116.6 518.7,137.4 524.2,128.5 529.7,149.2 535.2,107.8 540.7,161.0"/>
<text x="440" y="34" font-size="11" fill="#2bb673">Velocity 3.4.0</text>
<polyline fill="none" stroke="#e8833a" stroke-width="1.5" points="419.9,164.0 425.3,104.8 430.8,98.9 436.3,84.1 441.8,122.6 447.3,134.4 452.8,140.3 458.3,143.3 463.8,140.3 469.3,143.3 474.8,146.2 480.3,146.2 485.8,140.3 491.2,143.3 496.7,131.4 502.2,140.3 507.7,137.4 513.2,164.0 518.7,164.0"/>
<text x="440" y="48" font-size="11" fill="#e8833a">Gate 0.76.3</text>
<polyline fill="none" stroke="#9b59b6" stroke-width="1.5" points="411.1,164.0 416.6,155.1 422.0,152.2 427.5,149.2 433.0,146.2 438.5,149.2 444.0,149.2 449.5,155.1 455.0,146.2 460.5,152.2 466.0,152.2 471.5,152.2 477.0,152.2 482.5,152.2 487.9,152.2 493.4,155.1 498.9,149.2 504.4,164.0 509.9,164.0"/>
<text x="440" y="62" font-size="11" fill="#9b59b6">Gate Lite</text>
<polyline fill="none" stroke="#d7504b" stroke-width="1.5" points="451.2,164.0 456.6,21.9 462.1,16.0 467.6,27.8 473.1,27.8 478.6,66.3 484.1,110.7 489.6,137.4 495.1,143.3 500.6,134.4 506.1,137.4 511.6,128.5 517.1,98.9 522.5,125.5 528.0,128.5 533.5,131.4 539.0,143.3 544.5,119.6 550.0,161.0"/>
<text x="440" y="76" font-size="11" fill="#d7504b">BungeeCord #2111</text>
</svg>

## Added latency at low load

Round trip of the PLAY ping request (which the server answers from its network thread, without waiting for a game tick), timed by a raw client with the monotonic nanosecond clock, through the proxy versus straight to a forwarding-free clone backend, with the same 5 background movers on both. The probe runs alone in its own process on its own core. Added latency is the difference of the percentiles; it is the proxy's own cost on the path. The probe waits for its join burst to end, discards 20 warm-up rounds, and counts a round slower than 2000 ms at that value (the timeouts column, through the proxy / direct). A direct median above 5 ms means the path itself was loaded and the result is INVALID.

| Proxy | via proxy p50 | via proxy p99 | direct p50 | direct p99 | added p50 | added p99 | n | timeouts |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| Warp | 0.561 ms | 1.015 ms | 0.378 ms | 0.577 ms | 0.183 ms | 0.438 ms | 100 | 0 / 0 |
| Velocity 3.4.0 | 0.592 ms | 2.443 ms | 0.370 ms | 3.432 ms | 0.222 ms | -0.989 ms | 100 | 0 / 0 |
| Gate 0.76.3 | 0.582 ms | 2.138 ms | 0.366 ms | 1.334 ms | 0.216 ms | 0.804 ms | 100 | 0 / 0 |
| Gate Lite | 0.573 ms | 1.774 ms | 0.389 ms | 0.696 ms | 0.184 ms | 1.078 ms | 100 | 0 / 0 |
| BungeeCord #2111 | 0.579 ms | 0.922 ms | 0.329 ms | 0.480 ms | 0.250 ms | 0.442 ms | 100 | 0 / 0 |

<svg xmlns="http://www.w3.org/2000/svg" width="520" height="176" role="img" aria-label="Added p50 latency (ms, lower is better)">
<text x="8" y="16" font-size="13" font-weight="bold" fill="#1f2933">Added p50 latency (ms, lower is better)</text>
<line x1="150" y1="20" x2="150" y2="168" stroke="#d7dce2"/>
<text x="142" y="38" text-anchor="end" font-size="12" fill="#1f2933">Warp</text>
<rect x="150" y="28" width="219.6" height="16" rx="3" fill="#3a7bd5"/>
<text x="375.6" y="41" font-size="12" fill="#1f2933">0.183 ms</text>
<text x="142" y="66" text-anchor="end" font-size="12" fill="#1f2933">Velocity 3.4.0</text>
<rect x="150" y="56" width="266.4" height="16" rx="3" fill="#2bb673"/>
<text x="422.4" y="69" font-size="12" fill="#1f2933">0.222 ms</text>
<text x="142" y="94" text-anchor="end" font-size="12" fill="#1f2933">Gate 0.76.3</text>
<rect x="150" y="84" width="259.2" height="16" rx="3" fill="#e8833a"/>
<text x="415.2" y="97" font-size="12" fill="#1f2933">0.216 ms</text>
<text x="142" y="122" text-anchor="end" font-size="12" fill="#1f2933">Gate Lite</text>
<rect x="150" y="112" width="220.8" height="16" rx="3" fill="#9b59b6"/>
<text x="376.8" y="125" font-size="12" fill="#1f2933">0.184 ms</text>
<text x="142" y="150" text-anchor="end" font-size="12" fill="#1f2933">BungeeCord #2111</text>
<rect x="150" y="140" width="300.0" height="16" rx="3" fill="#d7504b"/>
<text x="456.0" y="153" font-size="12" fill="#1f2933">0.250 ms</text>
</svg>

## Switch storm

Bots repeatedly run /server to change backend, resting between two switches so that the backends (a switch is a full login and a view distance of chunks for one of them) are not the limit. Reports sustained switches per second, failures, and how long a switch took: from the command to the first chunk of the new backend (proxy hand-off plus backend login), and to six chunks (world arriving). "Backend TPS" is the lowest of the three backends.

| Proxy | offered | connected | switches | /s | failures | first chunk p50 | first chunk p99 | 6 chunks p50 | 6 chunks p99 | backend TPS |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| Warp | 4 | 4 | 38 | 2.5 | 0 | 321 ms | 613 ms | 597 ms | 912 ms | 20.0 |
| Velocity 3.4.0 | 4 | 4 | 38 | 2.5 | 0 | 330 ms | 685 ms | 559 ms | 829 ms | 20.0 |
| Gate 0.76.3 | 4 | 4 | 39 | 2.6 | 0 | 333 ms | 624 ms | 562 ms | 878 ms | 20.0 |
| Gate Lite | - | - | - | - | - | - | - | - | - | Gate Lite has no server-switch command |
| BungeeCord #2111 | 4 | 4 | 39 | 2.6 | 0 | 335 ms | 686 ms | 567 ms | 890 ms | 20.0 |

## Connection / status storm

Hostile connections (a handshake and a status request each) at a rising rate while a genuine player tries to join. The key question is whether the real player still gets in, and at what CPU cost. The flood and the player come from the same loopback address, so the per-address protections of every proxy are off here as in the other scenarios (with them on, each proxy would throttle the genuine player together with the flood); what this measures is the cost of the connections themselves.

Median CPU is the highest per-rate median over the flood; peak CPU is the highest one-second sample in any flood window.

| Proxy | joined @ 500/s | median CPU % | peak CPU % |
|---|:--:|--:|--:|
| Warp | yes | 58 | 85 |
| Velocity 3.4.0 | yes | 66 | 124 |
| Gate 0.76.3 | yes | 39 | 44 |
| Gate Lite | yes | 54 | 60 |
| BungeeCord #2111 | yes | 64 | 86 |

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
  "date": "2026-11-12T21:59:52.460Z",
  "host": "redacted",
  "platform": "linux",
  "arch": "x64",
  "kernel": "6.8.0-137-generic",
  "cpuModel": "AMD EPYC-Milan Processor",
  "cpuCount": 16,
  "memTotalGiB": 61.3,
  "governor": null,
  "transparentHugepage": "always [madvise] never",
  "sysctl": {
    "net.ipv4.ip_local_port_range": "1024\t65535",
    "net.core.somaxconn": "65535",
    "fs.file-max": "9223372036854775807",
    "net.ipv4.ip_local_reserved_ports": "25565,25700-25720"
  },
  "ulimitNofile": "1048576",
  "node": "v22.21.1",
  "java": null,
  "isWsl": false,
  "harnessSha": "unknown",
  "proxies": {
    "warp": "0.1.0-beta.7",
    "velocity": "3.4.0-566",
    "gate": "0.76.3",
    "bungeecord": "2111"
  }
}
```
