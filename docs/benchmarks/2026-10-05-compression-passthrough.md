# Compression passthrough — micro-benchmark results (2026-10-05)

First measurements from the JMH suite (`protocol/src/jmh`), taken to decide and validate
compression passthrough. Raw JMH output is in [`results/2026-10-05/`](results/2026-10-05/).

## Read this first

- **Indicative, not publishable.** AMD Ryzen 5 5500 (6C/12T, 10 vCPUs exposed) under **WSL2**, so
  timings carry hypervisor noise. Use the ratios, not the absolute values. Published comparisons
  must come from bare metal; see [`../research/benchmark-methodology.md`](../research/benchmark-methodology.md).
- **JVM:** Temurin 25.0.2, Netty 4.2.12. Sections 2 and 3 ran each fork with `-Xms2g -Xmx2g`
  (`1g` for section 2) `-XX:+AlwaysPreTouch --sun-misc-unsafe-memory-access=allow
  -Dio.netty.leakDetection.level=disabled` on Netty-like `FastThreadLocalThread` workers. Section 1
  ran with the same heap and leak detection settings but on JMH's plain threads, and without
  `--sun-misc-unsafe-memory-access=allow`, so Netty did not use `sun.misc.Unsafe` (see the
  allocation table in section 1).
- **Statistics:** 5 warm-up and 10 measured iterations of 2 s per fork; 3 forks per configuration,
  2 for the passthrough and transcode columns of section 1. Errors are 99.9 % confidence intervals.
- **Synthetic corpus.** Deterministic packets shaped like vanilla traffic (`PacketCorpus`):
  - chunks modelled on terrain: ~61 KiB raw, ~3.9 KiB compressed;
  - entity movement packets;
  - a gameplay mix (60 % movement, 22 % metadata, 12 % block updates and player info, 4 % chunks).

  Recorded traces will replace it for published numbers.
- **In process.** `EmbeddedChannel` pipelines wired exactly like the proxy's, fed by 16 KiB reads. No
  syscalls and no cross-thread hand-off. Every trial first checks that the client-side bytes decode
  back to the original packets.

## 1. Relaying clientbound traffic (`ForwardingPathBenchmark`)

Cost per relayed packet, backend wire bytes in → client-ready bytes out. Thresholds are 256 on both
connections (Paper's and Minestom's default); the backend compresses at zlib level 6.

- **Before:** `main` with Java 25 (inflate every frame, re-deflate on the way out).
- **Passthrough:** this change.
- **Transcode:** the same build with passthrough switched off.

| Workload | Encrypted | Before | Passthrough | Transcode | Before → passthrough |
|---|---|---|---|---|---|
| Chunks | no | 838 ± 60 µs | **8.2 ± 1.2 µs** | 806 ± 51 µs | **×102** |
| Mixed | no | 46.4 ± 13.1 µs | **0.60 ± 0.04 µs** | 31.8 ± 1.5 µs | **×78** |
| Entity movement | no | 292 ± 9 ns | **113 ± 7 ns** | 407 ± 21 ns | ×2.6 |
| Chunks | yes | 856 ± 11 µs | **96 ± 9 µs** | 965 ± 24 µs | **×8.9** |
| Mixed | yes | 37.4 ± 0.9 µs | **7.0 ± 0.2 µs** | 43.8 ± 1.2 µs | **×5.3** |
| Entity movement | yes | 828 ± 32 ns | **745 ± 69 ns** | 1003 ± 30 ns | ×1.1 |

Allocation per packet (`gc.alloc.rate.norm`). Both differences from the proxy's JVM noted above add
to these figures. On JMH's plain threads Netty's `Recycler` does not reuse objects such as
`ChannelOutboundBuffer` entries: run again with only the threads changed, the passthrough
allocates 44.3 B per entity movement packet instead of 104.2 B. Without `sun.misc.Unsafe`, Netty
copies through extra `ByteBuffer` views: run again with escape analysis off, so that the counts
repeat exactly, and only Unsafe changed, chunks drop from 102.8 to 16.3 B and the mix from 31.1 to
20.1 B. The benchmark now runs like the proxy on both counts, and the
[allocation guard](../../CONTRIBUTING.md#benchmarks) tracks its allocation on every pull request.

| Workload | Before | Passthrough |
|---|---|---|
| Chunks | 960 B | 184 B |
| Mixed | 599 B | 126 B |
| Entity movement | 409 B | 104 B |

Uncompressed packets benefit too: forwarded frames skip re-framing and the copy into a new buffer.
Once compression is gone, **AES/CFB8 accounts for ~90 % of an encrypted chunk's remaining cost.**

## 2. Learning the packet id of a compressed frame (`PacketIdPeekBenchmark`)

Per compressed frame. Payloads are the corpus packets at or above the threshold, compressed at zlib
level 6.

| Method | Chunks | Mixed | Allocation |
|---|---|---|---|
| **`DeflatePeek`** (Warp) | **1.69 ± 0.09 µs** | **1.22 ± 0.05 µs** | 0 B |
| JDK `Inflater`, 3 bytes (unmerged Velocity PR #1078) | 3.09 ± 0.20 µs | 2.24 ± 0.19 µs | 128 B |
| Full JDK inflate (every other proxy) | 121 ± 11 µs | 31.0 ± 0.9 µs | 256 B |

The first `DeflatePeek`, which decoded bit by bit from the `ByteBuf`, took 6.0 µs per chunk. That
was slower than the JDK, and the benchmark exposed it. The current version copies a 1 KiB window
once, reads it 64 bits at a time and table-decodes the code-length code.

## 3. Codec primitives, Warp's JDK vs Velocity's natives (`CodecCostBenchmark`)

Per corpus packet; zlib work only counts packets at or above the threshold. `VELOCITY_NATIVE` is
`com.velocitypowered:velocity-native:4.2.0` (libdeflate, OpenSSL 3), which is what Velocity and
Paper run in production.

| Operation | Workload | JDK | Velocity natives |
|---|---|---|---|
| inflate | Chunks | 105 ± 4 µs | 18.7 ± 0.5 µs |
| inflate | Mixed | 4.2 µs | 1.0 µs |
| deflate (level 6) | Chunks | 1.74 ± 0.60 ms\* | 0.83 ± 0.31 ms\* |
| deflate (level 6) | Mixed | 47 ± 13 µs\* | 18 ± 7 µs\* |
| encrypt (AES/CFB8) | Chunks | 80.1 ± 1.4 µs | 78.0 ± 1.9 µs |
| encrypt (AES/CFB8) | Mixed | 5.3 µs | 6.4 ± 1.9 µs |

\* These runs overlapped with compilation and are noisy. An earlier quiet smoke run measured
701 µs (JDK) vs 254 µs (native) per chunk.

## What this means

**Against Velocity on the path measured here (backends compress, as Paper and Minestom do by
default).** Velocity inflates and re-deflates every compressed frame with its natives. Adding its own
measured primitives gives roughly:

- ≈ 270 µs per chunk without encryption, ≈ 350 µs with it;
- ≈ 20–25 µs per mixed-traffic packet with encryption.

Warp's measured passthrough costs 8 µs and 96 µs per chunk, and 7 µs per mixed packet. That is
**~3–4× less CPU with encryption and ~30× less without.** This is a reconstruction from measured
parts, not a head-to-head run; the trace-replay harness will produce the head-to-head numbers.

**Where Warp does not win yet:**

- **Backends with compression off** (Velocity's historical advice). Warp must then compress every
  large packet, and the JDK's deflate is ~2.8× slower than libdeflate. Native libdeflate (via FFM on
  Java 25), with one context per event loop, is the next step.
- **Encryption.** AES/CFB8 costs the same ~50 MB/s on the JDK and on OpenSSL. Encryption is serial
  (one AES block per byte), but a hand-written AES-NI path measured ~1.7× faster for encryption and
  ~15× for decryption during research.

## Reproduce

```bash
./gradlew :protocol:jmhJar
java -jar protocol/build/libs/protocol-*-jmh.jar 'ForwardingPath|PacketIdPeek|CodecCost' -prof gc -rf json
```
