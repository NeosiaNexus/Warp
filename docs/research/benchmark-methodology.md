# Warp vs Velocity (and others): Benchmark Methodology & Harness Design

> **Research cycle #5 — 2026-10-05.** Written before the first benchmark code landed; kept as the
> methodology reference. Status of the findings in §1 since then:
>
> - **F1** (no compression passthrough) — resolved by `feat(protocol): forward compressed frames
>   without recompressing them`.
> - **F4** (backend not on the client's event loop) — resolved by `perf(proxy): serve each player's
>   backend leg from its client event loop`.
> - **F8** (uncommitted JMH work) — landed as `warp.jmh-conventions` and `protocol/src/jmh`, with
>   `velocity-native` as the in-process competitor baseline (§2.1).
> - **JVM** — Warp now requires Java 25, as do Velocity 4.x and its natives; §6.2's "pinned Temurin
>   21" becomes "pinned Temurin 25" for both. On Java 25, Netty 4.2 disables `sun.misc.Unsafe`
>   unless `--sun-misc-unsafe-memory-access=allow` is passed: the flag must be identical for every
>   JVM proxy under test.
> - **F2/F3** (JDK zlib and AES vs natives) — measured by `CodecCostBenchmark`: libdeflate inflates
>   ~5.6× faster than the JDK's zlib on chunk-sized payloads, while AES/CFB8 encryption runs at the
>   same ~50 MB/s through the JDK and through Velocity's OpenSSL natives.

**Date:** 2026-10-05
**Status:** Research proposal. Nothing in the Warp repo was modified.
**Goal:** Build a benchmark of Warp against Velocity (and ideally Gate, BungeeCord, Infrarust) that is credible and reproducible, and that holds up when Velocity/Paper maintainers and r/admincraft look at it hard.

Confidence markers used throughout:
- **[verified]**: checked against source code, a registry, or an official doc during this research.
- **[likely]**: strong evidence but not directly verified.
- **[uncertain]**: an educated guess. Measure it before relying on it.

---

## 0. TL;DR (recommendation)

1. **Use four layers. Each one answers a different question. Do not mix their numbers.**
   - **L1, micro (JMH, in the Warp repo):** per-packet ns/op and **B/op** for each stage of the forwarding path, with Velocity's own `velocity-native` libdeflate/OpenSSL as the in-process baseline. Purpose: attribution and regression gates.
   - **L2, meso (in-process, real epoll sockets, Warp only):** costs that `EmbeddedChannel` cannot see, such as cross-event-loop hand-off, flush batching and back-pressure.
   - **L3, macro (separate neutral repo):** the proxy is a black box. A **trace-replay fake backend** and **lightweight sink clients** run in one load-generator process with one clock. Latency comes from open-loop in-band probes and HdrHistogram. CPU comes from cgroup v2. This produces every cross-proxy headline number.
   - **L4, validation:** real Paper backends with PureGero bots (plus a few SoulFire bots) at small scale. This is a qualitative sanity check that L3 conclusions hold with real software at both ends. It also gives **total-system CPU** (proxy + backends).
2. **Headline metrics:**
   - proxy **CPU core-seconds per GB forwarded** and **millicores per player** at a fixed offered load;
   - **max players at SLO for a fixed core budget** (1, 2 and 4 physical cores);
   - proxy-added one-way latency p50/p99/p99.9 **below the knee**;
   - **heap-live and RSS slope per player**;
   - **server-switch completion CDF** during switch storms.
3. **The scenario matrix must include Velocity's own recommended setup, which disables compression on the backend link.** Otherwise the first Velocity maintainer to look will dismiss the benchmark: Velocity's FAQ tells operators to disable proxy↔backend compression. Present compression passthrough honestly. It **moves compression off the proxy onto the backends**, which is a real scalability win because proxies are the shared bottleneck. It does **not** remove the compression work from the network as a whole.
4. **Do not publish a comparison yet.** The current Warp code (see §1) does not yet do compression passthrough. It inflates and re-deflates every frame with `java.util.zip`, and the native libdeflate path is a TODO. Today Warp would probably *lose* to Velocity on compressed traffic. Build the harness now, use it internally to drive the work, and publish once passthrough and natives land.
5. **Hardware:**
   - WSL2 is fine for developing the harness and for JMH **B/op**, but not for timing claims.
   - The minimum credible setup is **one bare-metal Linux host** with cgroup cpuset partitioning: proxy on isolated physical cores, load generator on the rest.
   - The gold standard is **two bare-metal hosts on ≥10 GbE**.
6. **Prior art:** no rigorous, reproducible cross-proxy benchmark has been published. Infrarust v2's layered suite (open-loop `mc-bench`, HdrHistogram, direct-baseline delta) is the best existing MC methodology, but it only measures Infrarust against itself.

---

## 1. Current Warp code: findings that change the benchmark plan

These come from reading the working tree on `main`. Some of them would decide the result of a benchmark run today.

| # | Finding | Evidence | Impact | Recommendation |
|---|---|---|---|---|
| F1 | **Compression passthrough is not implemented.** `CompressionDecoder` is inserted before `MinecraftDecoder`, so every compressed frame is inflated. `CompressionEncoder` (`MessageToByteEncoder<ByteBuf>`) then re-deflates blind buffers. | `LoginSessionHandler.enableCompression()` (~L407–431), `BackendLoginSessionHandler.handleSetCompression()` (~L135–148) [verified] | Today Warp does the same compression work as Velocity, with a slower compressor. | Benchmark M3 (below) should drive the passthrough design. Publish only after it lands. |
| F2 | **Only `JavaCompressor` (`java.util.zip`) exists.** `jni/Natives.isNativeCompressionAvailable()` returns `false` (TODO). | `protocol/compress/JavaCompressor.java`, `jni/Natives.java` [verified] | In S2 (backend uncompressed, the proxy must compress) Warp would compete with JDK zlib against Velocity's libdeflate, which Velocity's docs call "twice as fast as zlib". | Measure the gap in M4 first. Natives are probably required before publishing S2. |
| F3 | **The cipher is JDK `AES/CFB8/NoPadding`.** Velocity uses native OpenSSL and BungeeCord uses mbedTLS. | `CipherEncoder`/`CipherDecoder` [verified]; Velocity `jni_cipher_openssl.c` [verified] | Online-mode results (S4) may be dominated by AES-CFB8, which is sequential and costs one AES block per byte. The JDK-vs-OpenSSL gap is **[uncertain]**. | Measure in M5 before deciding whether a native cipher is a prerequisite. |
| F4 | **The backend channel is not bound to the client's event loop.** `BackendConnection.connect()` uses `.group(workerGroup)`, which assigns the next loop round-robin. Velocity does `server.createBootstrap(proxyPlayer.getConnection().eventLoop())`. | `BackendConnection.java` ~L96–97 [verified]; `VelocityServerConnection.java` L104 (Velocity `dev/4.0.0`) [verified] | Most forwarded buffers are probably written from a foreign thread. That means a Netty write task, an MPSC enqueue, a wakeup, and a pooled-buffer free on a different thread. **No `EmbeddedChannel` micro-benchmark can show this.** | Cheap fix: pass the client channel's `eventLoop()`. Quantify it with the meso benchmark (M8) and the macro harness. |
| F5 | **The Mojang session-server URL is hard-coded.** | `MojangSessionService` (constant `https://sessionserver.mojang.com/...`) [verified] | Online-mode load tests need a mock session server. Velocity honours `-Dmojang.sessionserver=` [verified]. BungeeCord hard-codes its URL [verified]. Gate appears to have a config key (`sessionServerUrl` shows up in `config.go`) [likely]. | Add a system property or config override in Warp. For BungeeCord, use a DNS override plus a custom CA. |
| F6 | **Warp's launch flags differ a lot from Velocity's recommended flags.** | `bin/warp.sh`: ZGC generational, `-Xmx512M`, `AlwaysPreTouch`, leak detection disabled, async logging [verified]. Velocity docs: `-XX:+UseG1GC -XX:G1HeapRegionSize=4M -XX:+UnlockExperimentalVMOptions -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:MaxInlineLevel=15` [verified] | The GC choice alone can swing CPU and RSS by large factors. | Define and publish Warp's recommended flags **before** benchmarking. For the headline, run both proxies with identical flags (§6.2). |
| F7 | **The two proxies use different allocators.** Warp forces `PooledByteBufAllocator.DEFAULT`. Velocity 4.x (Netty 4.2.18) sets no allocator, so it gets Netty 4.2's default `AdaptiveByteBufAllocator`. | `WarpServer`/`BackendConnection` [verified]; Velocity `ConnectionManager` has no ALLOCATOR option [verified]; Netty 4.2 changed the default to adaptive [verified] | This is a confounder. | Keep each proxy's default, record it, and run a sensitivity pass with `-Dio.netty.allocator.type=pooled|adaptive` on both. |
| F8 | **A JMH setup was in progress in the working tree (uncommitted) when this was written.** It includes `warp.jmh-conventions` (plugin 0.7.3, JMH 1.37, `-prof gc`, JSON results, NullAway `CustomInitializerAnnotations=@Setup`, errorprone off for generated code) and `protocol/src/jmh` with a synthetic `PacketCorpus`. | `git status` [verified] | This report is consistent with it. §2 lists additions. | Keep the synthetic corpus for regression tests. Add a **recorded** corpus (§4.3) for any number that gets published. |

One expectation to manage: Velocity **also** forwards unrecognised packets without deserialising them. It only decodes about 15 packet types (`docs/research/performance-analysis.md` §5). Blind forwarding on its own is therefore **not** a large differentiator against Velocity. The differentiators should be compression passthrough, zero allocation, and event-loop affinity and batching. Scenario S3 (no compression anywhere) isolates blind forwarding plus framing. Expect a modest gap there and report it honestly.

---

## 2. Layer 1: JMH micro-benchmarks

### 2.1 Gradle setup (Gradle 8.12, Java 21)

Verified versions:
- `me.champeau.jmh` **0.7.3** is the latest on the Gradle Plugin Portal (metadata `lastUpdated` 2025-01-30; GitHub release 2025-04-16). The 0.7.x line requires Gradle ≥ 8.0.
- **JMH 1.37** is the latest on Maven Central (2023-08).
- async-profiler **4.5** was released 2026-07-20.
- HdrHistogram (Java) is at **2.2.2**.

The WIP `warp.jmh-conventions` plugin is a good base. Suggested additions:

```kotlin
// build-logic/src/main/kotlin/warp.jmh-conventions.gradle.kts (additions)
jmh {
    // Same JVM shape for every benchmark unless a class overrides it with @Fork(jvmArgsAppend=...)
    jvmArgsAppend.addAll(
        "-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch",
        "-Dio.netty.leakDetection.level=disabled",   // timing runs only (see pitfall P5)
    )
    zip64 = true                                     // jmhJar gets large with Netty + natives
    duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}

// Checkstyle creates checkstyleJmh automatically. Benchmark classes rarely satisfy the Javadoc rules.
tasks.named("checkstyleJmh") { enabled = false }   // or relax the rules for src/jmh only
```

Running benchmarks:
- Use `./gradlew :protocol:jmh -Pjmh.includes=Forward` for routine runs. This is already supported by the WIP code.
- For anything with profilers or custom forks, use the fat jar directly:
  ```
  java -jar protocol/build/libs/protocol-*-jmh.jar 'Forward.*' -f 3 -prof gc -rf json -rff out.json
  java -jar ... -prof "async:libPath=/opt/async-profiler/lib/libasyncProfiler.so;output=flamegraph;dir=flame"
  java -jar ... -prof perfnorm      # bare metal only: instructions/op, cycles/op
  ```

**Velocity's natives as the in-process baseline.** `com.velocitypowered:velocity-native:4.2.0` is published on `repo.papermc.io` [verified]. `velocity-proxy` is not. Because `FAIL_ON_PROJECT_REPOS` is enforced, declare the repository centrally with a content filter, and keep the dependency on the `jmh` configuration only:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            content { includeGroup("com.velocitypowered") }
        }
    }
}
// protocol/build.gradle.kts (or in the convention): benchmark-only, never shipped
dependencies { jmh(libs.velocity.native) }
```

This lets M3, M4 and M5 compare against **exactly** the libdeflate and OpenSSL code paths that Velocity (and Paper, which has shipped Velocity's compression and cipher natives since a 2021 patch by A. Steinborn [likely]) run in production. That is far harder to dispute than a JNI binding we write ourselves. Licensing: Velocity is GPLv3, which is compatible with AGPLv3. Since the dependency only exists in benchmark sources and is never shipped, this is not a practical issue.

### 2.2 Where benchmarks live

| Location | Contents |
|---|---|
| `protocol/src/jmh/java/dev/warp/protocol/bench/` | VarInt, FrameDecoder, compression path (passthrough, peek, recompress), MinecraftDecoder blind vs decode, cipher |
| `protocol/src/jmh/resources/corpus/` | **Recorded** packet corpora extracted from traces (§4.3), stored next to the synthetic `PacketCorpus` |
| `proxy/src/jmh/java/dev/warp/proxy/bench/` | Composite forwarding path (session handler → `writeBlind` → encoders), plus meso benchmarks with real sockets |
| `jni/src/jmh/java/` (later) | Warp natives vs JDK vs velocity-native |

### 2.3 What to micro-benchmark on a proxy forwarding path

Each benchmark below answers one question. Report ns/op, **B/op** (`gc.alloc.rate.norm`), and where useful ns/byte (ns/op ÷ mean frame size, or `@AuxCounters` for bytes).

| ID | Benchmark | Question it answers | Parameters | Baseline / comparison |
|---|---|---|---|---|
| M1 | VarInt read/write | Regression guard for the codec primitive | 1–5 byte values, random mix | Previous commit |
| M2 | `FrameDecoder` on realistic socket reads | Cost per frame when a 16–64 KiB read holds many frames, some split across reads | Read size, corpus | Previous commit |
| M3 | **Clientbound compressed-frame path** (core claim) | How much cheaper is passthrough than inflate + deflate? | `mode ∈ {PASSTHROUGH, PEEK_ID_PARTIAL_INFLATE, RECOMPRESS_JDK, RECOMPRESS_LIBDEFLATE(velocity-native)}` × size bucket (300 B, 1 KiB, 4 KiB, 16 KiB, 64 KiB chunk) × corpus (recorded) | RECOMPRESS_LIBDEFLATE is "what Velocity pays per frame" |
| M3b | **Peek packet ID of a compressed frame** | Warp still has to know the ID of compressed frames, to catch JoinGame, Respawn, plugin messages and Disconnect. Is a *partial* inflate of the first ≤5 bytes cheap enough, compared with full inflate (what Infrarust does)? | Frame size | Full inflate |
| M4 | Raw compressor throughput | JDK zlib vs libdeflate inflate/deflate MB/s at levels 1, 4 and 6, on **real** chunk data | Level, size | velocity-native |
| M5 | AES/CFB8 throughput | JDK cipher vs velocity-native OpenSSL, encrypt and decrypt, per KiB | Size | velocity-native |
| M6 | `MinecraftDecoder` blind vs registered | Confirms the blind path is ~0 B/op and measures what an inspected packet costs | Corpus mix | — |
| M7 | Composite pipeline, single thread | ns/packet and B/packet for backend inbound → client outbound on a mixed trace | `compression ∈ {off, passthrough, recompress}`, allocator ∈ {pooled, adaptive} | Empty-pipeline baseline |
| M8 | **Meso: real epoll sockets in-process** | Cost of the backend on the *same* vs a *different* event loop (F4), of flush batching, and of back-pressure toggling | Connections, loop affinity | Same-loop variant |
| M9 | Switch-path cost (optional) | CPU and allocation per server switch (CONFIG relay), excluding I/O | — | — |

Make **allocation gates** CI-enforced: M3 PASSTHROUGH, M6 blind and M7 passthrough must stay at ~0 B/op. B/op is deterministic and does not depend on the machine, so this works even on GitHub runners and WSL2.

### 2.4 Benchmarking Netty pipelines: EmbeddedChannel vs alternatives

| Approach | Measures | Misses | Use for |
|---|---|---|---|
| **Direct handler call with a stub `ChannelHandlerContext`** (the pattern used by Netty's own `microbench` module: `EmbeddedChannelWriteReleaseHandlerContext` releases written messages) [verified] | Pure handler cost | Pipeline traversal, I/O | M2, M3, M6 |
| **`EmbeddedChannel`** | Handler composition, deterministic and single-threaded | Syscalls, epoll, cross-thread hops, writability and back-pressure, socket-level flush coalescing. It also *adds* its own costs (outbound message queue, promises, `runPendingTasks`, `checkException`). | M7. Always subtract an "empty pipeline" baseline and drain and release outputs on every op. |
| **Real sockets in-process** (epoll over loopback, two channels plus a Warp pipeline) | Syscalls, event-loop scheduling, cross-thread hand-off, flush batching | Noisier. Network effects are unrealistic. | M8, and validating F4 |

Netty's microbench module runs JMH with a custom executor (`AbstractMicrobenchmark.HarnessExecutor`, using `DefaultThreadFactory`, which produces `FastThreadLocalThread`s). Its base JVM args include `-dsa -da -ea:io.netty.microbench... -Dio.netty.leakDetection.level=disabled` [verified]. This matters because `FastThreadLocal` and `Recycler` take slower paths on plain JMH worker threads. Use the same trick (`-Djmh.executor=CUSTOM -Djmh.executor.class=...`) for pipeline benchmarks, so that recycled objects (`CodecOutputList`, pooled slices) behave as they do on an event loop.

### 2.5 JMH pitfalls checklist (proxy-specific)

- **P1 Dead-code elimination:** return the result or call `Blackhole.consume()`. For `ByteBuf` outputs, consume and then `release()`.
- **P2 Constant folding:** inputs live in non-final `@State` fields or `@Param`. Never use literal or `static final` inputs.
- **P3 Tiny corpora:** a 16-packet loop gets learned by the branch predictor and caches. Use ≥4096 frames, shuffled with a fixed seed, and `@OperationsPerInvocation(n)` when iterating. Monomorphic corpora (a single packet type) give optimistic inlining; also benchmark a mixed corpus.
- **P4 Per-invocation setup:** `Level.Invocation` adds timestamp overhead that dominates at ns scale. Reset `readerIndex(0)` inline instead. `duplicate()` allocates a wrapper and pollutes B/op.
- **P5 Leak detector:** disable it for timing runs. The default `SIMPLE` level samples allocations and adds cost. *Also* run each benchmark body once under `-Dio.netty.leakDetection.level=paranoid` in a JUnit smoke test, so that a leaking benchmark (whose pooled behaviour would be distorted) fails CI.
- **P6 Pooled allocator effects:** single-threaded JMH is the allocator's best case, because the thread cache always hits and memory is freed on the same thread. In production the buffer may be freed on a different event loop (see F4). Parameterise the allocator (pooled, adaptive, unpooled) and measure cross-thread behaviour in M8 or with JMH `@Group` asymmetric benchmarks (producer allocates and decodes, consumer releases).
- **P7 Heap vs direct buffers:** benchmark direct buffers, as in production. `java.util.zip.Deflater`/`Inflater` accept `ByteBuffer` (JDK 11+). Benchmark that path rather than `byte[]` copies.
- **P8 Data realism:** compression cost and ratio depend heavily on entropy. Random bytes are incompressible and zeros compress unrealistically well. **Use recorded chunk, entity and light packets** for any number that is published. Keep the synthetic corpus for regression only.
- **P9 Escape analysis:** EA can remove allocations in a small benchmark that survive in the real, larger inlined context. Confirm the zero-allocation claims with JFR allocation sampling in macro runs.
- **P10 Forks and warmup:** ≥3 forks, ≥5×2 s warmup, ≥10×2 s measurement. Never `-f 0`. Report the error. Keep the JSON.
- **P11 Timer granularity:** for operations under ~20 ns, use throughput mode or batching.
- **P12 Blackhole mode:** JMH ≥1.33 auto-selects *compiler* blackholes on JDK 17+ and prints "Blackhole mode: compiler (auto-detected)". Check that this line appears.
- **P13 Environment:** on bare metal, pin with `taskset`, use the `performance` governor and disable turbo. On WSL2 (which has no PMU), treat timings as indicative only. `-prof perfnorm` and `perfasm` need PMU access and are unavailable there [likely]. `perf` is not even installed on the dev box [verified].

### 2.6 Benchmark skeleton (shape, not final code)

```java
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
public class ClientboundForwardBenchmark {

  @Param({"PASSTHROUGH", "PEEK_ID", "RECOMPRESS_JDK", "RECOMPRESS_LIBDEFLATE"})
  public ForwardMode mode;

  @Param({"recorded-mixed", "recorded-chunks"})
  public String corpus;

  private ByteBuf[] frames;      // wire-format frames (threshold 256), pooled direct, built once
  private ForwardPath path;      // the strategy under test
  private int cursor;

  @Setup(Level.Trial)
  public void setUp() { /* load corpus, pre-compress with libdeflate L6 (what Paper sends) */ }

  @Benchmark
  public void forwardOne(Blackhole bh) {
    ByteBuf in = frames[cursor++ & (frames.length - 1)];
    in.readerIndex(0);                 // cheap reset; no per-invocation setup
    ByteBuf out = path.forward(in);    // retained slice (passthrough) or new buffer (recompress)
    bh.consume(out);
    out.release();
  }

  @TearDown(Level.Trial)
  public void tearDown() { for (ByteBuf f : frames) f.release(); path.close(); }
}
```

### 2.7 Micro-benchmarks in CI

- **On every PR:** run a JMH smoke pass (`-f 1 -wi 1 -i 1`) to confirm benchmarks compile and run, plus the **B/op gate** (deterministic).
- **Nightly or on demand:** run the full suite on one dedicated self-hosted bare-metal runner. Archive the JSON and track trends with bencher.dev or `github-action-benchmark`, with a statistical threshold. Never compare timings across different machines.

---

## 3. Survey of macro load tools

| Tool | Stack | Versions | Online-mode / encryption | Per-bot cost and chunk handling | Scale | Fit for proxy benchmarking |
|---|---|---|---|---|---|---|
| **SoulFire** 2.10.2 (moved to `soulfiremc-com/SoulFire`, AGPL-3.0, active) [verified] | **Runs real Minecraft client code headless** (Fabric mod; bundles headless Vulkan/lavapipe for on-demand rendering); native MC 26.3 with older versions via ViaFabricPlus (translated on the bot side) [verified] | Very wide (Classic → 26.3, Bedrock) | Yes: Microsoft accounts and offline | **Heavy.** It is a real client world: chunk decoding, entity tracking, ticking, pathfinding. No published per-bot RAM/CPU figures [verified absent from docs]. | Unknown. Likely hundreds per machine at most **[uncertain]**. | Excellent for **realism and functional** tests (L4). **Poor for saturating a proxy**, because the bots' own CPU becomes the bottleneck. |
| **PureGero/minecraft-stress-test** (active, Aug 2026) [verified] | Hand-rolled Java/Netty 4.1 | **One version at a time** (hard-coded packet IDs, currently 1.21.x) | **Offline only.** It disconnects on EncryptionRequest [verified]. | **Light.** Handles about 6 play packets. Does **not** parse chunks, but **does inflate every compressed frame** (`java.util.zip.Inflater`). Bots fly around and force chunk generation and sending on the backend. | Probably thousands per box **[uncertain]**. The Paper backends become the bottleneck first. | **Best "real backend" driver** for L4. Not usable for steady-state saturation, because Paper world generation and chunk sending dominate. |
| **MCProtocolLib**-based (e.g. `crpmax/mc-bots`, MC 1.21.7, `-x` "most minimal" mode, online via Microsoft) [verified] | Java | One MC version per release | Yes | **Deserialises every packet into objects** (NBT, chunk payload as `byte[]`, light arrays). Medium to heavy. | Hundreds to low thousands **[uncertain]** | Fine for functional checks. Wasteful as a load sink. |
| **mineflayer** | Node.js | Many (minecraft-data) | Yes | Heavy (prismarine-chunk world model) | Tens to hundreds per process | Not suitable for load. |
| **Minecraft Holy Client** (C#) | .NET, "high-performance stress platform" | **[uncertain]** | **[uncertain]** | **[uncertain]** | **[uncertain]** | Worth a look, but niche and less active. |
| **Infrarust `tools/mc-bench`** [verified] | Rust. **Open-loop** echo-ping load generator plus mock backend, HdrHistogram, warmup window | **Protocol < 764 only** (pre-1.20.2, which skips the CONFIG phase) | No (offline) | Near zero: echoes opaque ping packets (IDs 0x40/0x41). No chunks or trace. | Thousands | A good **independent cross-check** of latency for small packets, if Warp handles 1.18.2 end to end **[uncertain]**. Not a realistic traffic mix and no CPU-efficiency metric. |
| Velocity's own testing | — | — | — | No benchmark or JMH directory in `PaperMC/Velocity` `dev/4.0.0` [verified]. Historically: production profiling (issue #594, spark/async-profiler). | — | No reusable harness. |
| Gate | Go | — | — | Only a couple of Go `Benchmark` functions (e.g. in `lite/match_test.go`, `proto/util/util_test.go`). No load tool [verified]. | — | No reusable harness. |

**What offline mode skips.** Almost every load test runs offline. Offline mode skips:
- (a) the **RSA-1024 decryption** of the shared secret per login, a real cost in login storms;
- (b) the **session-server HTTP round trip**, which affects login latency and async plumbing;
- (c) **AES-CFB8 on every byte** of the client↔proxy link in both directions. This is a large per-byte cost and is sequential on the encrypt side (one AES block per byte). Infrarust reports ~360 MB/s for decrypt and ~58 MB/s for encrypt on its reference machine (Rust) [verified as their claim];
- (d) chat signing and profile-key flows.

Once passthrough removes compression from the proxy, **AES becomes the dominant per-byte cost in online mode** (Amdahl's law). Warp's advantage will shrink in S4, and the benchmark has to show that. The harness can run online mode without Mojang accounts: a **mock session server** plus sink clients that do the client half of the key exchange (§4.7).

---

## 4. Layer 3: isolating the proxy (harness design)

### 4.1 Principles and precedents

- **Open loop, scheduled by intended time.** Following wrk2 (Gil Tene), latency is measured from the *intended* send time on a fixed schedule, so a stalled system cannot hide its stall (coordinated omission). Infrarust's `mc-bench` does the same with `sleep_until` grids [verified].
- **Never measure latency at saturation.** Measure latency below the knee and report capacity separately. Envoy's benchmarking guide says this explicitly, along with "prefer open-loop", "verify connections, errors and streams match expectations", and "examine perf profiles" [verified]. Nighthawk docs recommend `taskset`, disabling C-states, watching for thermal throttling, and separate machines for client and server [verified].
- **Throughput means maximum rate at zero loss, with latency measured by tagged frames at that rate.** This comes from RFC 2544, the classic network-device method. It maps directly onto "max players at SLO" and in-band probes.
- **Each project tunes its own entry.** TechEmpower publishes all implementations, configs, raw results and hardware, and runs continuously. Do the same: invite Velocity, Gate and Infrarust maintainers to PR their configs.
- **Avoid "benchmarking crimes"** (Heiser): no cherry-picked subsets, no unreported variance, no mismatched configs, no relative-only numbers without absolutes.
- **Use a single clock for latency.** Fake backends and fake clients run in the **same load-generator process** on the same host. One-way latency needs no clock sync (`System.nanoTime` is `CLOCK_MONOTONIC`).
- **The load generator must be cheap per byte.** The fake backend writes **pre-encoded, shared, read-only direct buffers** (`retainedDuplicate()`) and never compresses on the hot path. The sink client only splits frames. It inflates nothing except the rare probe or control frames.

### 4.2 Architecture

```
                 LOADGEN (one JVM, one clock; own cores or own host)                 SUT (isolated cores or own host)
 ┌──────────────────────────────────────────────────────────────────────┐        ┌──────────────────────────────┐
 │  SinkClient × N   (bind to 127.x.y.z / many source IPs)              │  TCP   │  :25565  proxy under test     │
 │   • handshake/login/config state machine (offline or encrypted)      │───────▶│  Warp | Velocity 3.x/4.x |    │
 │   • frame-split & discard; inflate only probe/control frames         │        │  Gate | BungeeCord |          │
 │   • echo KeepAlive/Ping, ChunkBatchReceived, movement @20 Hz         │◀───────│  Infrarust | HAProxy-L4 |     │
 │   • probe detect → HdrHistogram (one-way, intended-time based)       │        │  (none = direct baseline)     │
 │   • switch flow (StartConfiguration → Ack → config → FinishAck)      │        └──────────────┬───────────────┘
 │                                                                      │                       │
 │  ReplayBackend × B  (:30001..)                                       │◀──────────────────────┘
 │   • scripted login (+ velocity:player_info request, ignored)         │
 │   • CONFIG replay → FinishConfiguration → PLAY trace replay          │
 │   • open-loop scheduler (per-connection random start offset)         │
 │   • probes injected on an intended-time grid (seq, conn id)          │
 │   • self-health: schedule-lag histogram, send-queue depth            │
 │                                                                      │
 │  MockSessionServer (S4)      Metrics: HdrHistogram interval logs     │
 └──────────────────────────────────────────────────────────────────────┘
   Orchestrator (scripts): cgroup cpuset, start/verify SUT, phases, collect cpu.stat/memory/proc/perf, report
```

The harness should use **its own minimal codec**: VarInt, framing, zlib through velocity-native, AES. It should not depend on Warp's `protocol` module. This avoids shared-bug blind spots and keeps the harness credible as neutral. It needs roughly a few hundred lines of protocol code.

### 4.3 Recording realistic traces

**Recorder.** Use a tiny **recording tap relay**: an L4 Netty relay placed between a client and Paper.
- It parses framing and `SetCompression`.
- For each frame it stores a µs timestamp, the direction, the TCP read-batch boundary, the original wire bytes, and the decompressed packet (ID + payload).
- Record **without any proxy** (client → tap → Paper, offline mode, so the stream is cleartext), so that no proxy biases the trace.

Alternatives: `tcpdump` on the backend port followed by offline TCP reassembly (tool-neutral, more work), or importing ReplayMod `.mcpr` files (clientbound only, ms timestamps).

**Backend for recording.**
- Use a pinned Paper build at a protocol version **V supported by every proxy under test**. MC now uses year-based versions (26.x). Warp's newest registered version and SoulFire's native version are both 26.3, so pick the newest version that all of them speak at recording time.
- Use defaults: `view-distance=10`, `simulation-distance=10`, `network-compression-threshold=256`, anti-xray off.
- **Pre-generate the world** (e.g. with Chunky) so that world generation does not distort send timing.
- Disable plugins except a bot-population helper if needed.

**Archetypes.** Record several 10–15 minute subject sessions, each with co-located background bots (PureGero) to create entity fan-out:

| Archetype | Behaviour | Stresses |
|---|---|---|
| `hub-idle` | Stand in a crowded spawn with ~50 bots in view, chat, tab updates | Packet rate, small packets, entity moves |
| `survival` | Walk and explore at normal speed | Mixed traffic |
| `explore` | Fly or elytra in a straight line | Chunk bandwidth (large compressed frames) |
| `pvp` | ~20 bots fighting nearby | Dense entity, metadata, sound and particle packets |
| `join` | Login → CONFIG → first 30 s | Registry data, initial chunk burst |

**Segments.** Store `login+config`, `join-burst` and a loopable `steady` segment.
**Publishing.** Publish traces (CC0) with a `manifest.json` containing MC version, Paper build, configs, seed, archetype, byte rate and packets per second, and SHA-256.
**Corpus.** Extract a JMH corpus (§2.2) from the same traces.

**Does backend compression (Paper vs vanilla) matter? Yes, in four ways.**
1. **On or off** determines whether Velocity, Gate and BungeeCord must inflate *every* compressed backend frame (S1 vs S2).
2. **Threshold equality** between the backend link and the client link determines whether passthrough is possible at all. Add an S1b mismatch case (backend 256, client 512) to show Warp's fallback honestly.
3. **The compressor and level** the backend uses determine client wire bytes under passthrough. Paper uses Velocity's libdeflate natives [likely]; vanilla uses `java.util.zip`. Report **client-bound bytes on the wire per proxy**, so that nobody can claim a compression-level cheat in either direction.
4. **Where the CPU is spent.** Passthrough moves compression from the proxy to the backends. With fake backends, backend compression is pre-computed and therefore not counted. Report **total system CPU** (proxy + Paper) in the L4 validation, and estimate backend compression cost from M4 (libdeflate ns/byte × bytes) in the L3 report.

**Replay encoding.** At start-up the harness builds wire variants of the trace once: "as recorded" (Paper's own compressed bytes, when the threshold matches), "re-encoded at threshold T with libdeflate L6", and "uncompressed". It keeps them in shared direct buffers. There is no compression on the hot path.

### 4.4 Replay backend (fake server)

- **Login:** read Handshake and LoginStart. If configured, send a `velocity:player_info` LoginPluginRequest and accept any answer. Send `SetCompression(T)` and `LoginSuccess`, then wait for `LoginAcknowledged`.
- **CONFIG:** replay the recorded config packets (registry data, known packs, tags), send `FinishConfiguration`, then wait for the acknowledgement. Ignore unknown serverbound packets.
- **PLAY:** replay `join-burst`, then loop `steady`.
  - Give each connection a **random phase offset** to avoid synchronised thundering-herd bursts.
  - **Preserve recorded burst structure** (packets from the same tick and read batch go out in one flush). The proxy's syscall and flush behaviour depends on it.
- **Open-loop scheduling:** events have intended times. If the writer falls behind, record **schedule lag** and do not slow down. The orchestrator invalidates any run whose loadgen lag p99.9 exceeds ~100 µs.
- **Load scaling:** to increase offered load, increase N (players), not the per-player rate. Also allow a per-archetype rate multiplier for stress.
- **Probes:** see §4.5.

### 4.5 Sink client and latency probes

- **Minimal state machine:** handshake, login (optionally with encryption), config acknowledgements (`FinishConfiguration` ack, `KnownPacks` reply, keep-alive), then play: echo `KeepAlive`/`Ping`, send `ChunkBatchReceived` on batch finish, and send movement at 20 Hz (from the trace's serverbound side).
- **Switch flow:** `StartConfiguration` → `AcknowledgeConfiguration` → config → `FinishConfiguration` ack → wait for the first PLAY packet (`JoinGame`).
- **Frame handling:** read the VarInt length and skip. For compressed frames, read the clear-text `dataLength` VarInt without inflating, unless it equals the **probe sentinel length**.
- **Clientbound probes:** the backend injects two probe kinds per connection.
  - **Small probe** (~64 B, below the threshold, uncompressed): 10 per second. Its packet ID must be one that *every* proxy under test blind-forwards at version V. Verify this per proxy against their registries, and confirm it with a byte-exact check run.
  - **Large probe** (~2 KiB, compressed, at a unique sentinel `dataLength`): 1 per second, compressed at send time with libdeflate L1. That costs a few µs, which is negligible.
  - Both carry `(connId, seq)`. The intended send time comes from the backend's schedule (`t0 + seq·Δ`) and is held in shared memory, so **latency = t_recv − t_intended**.
- **Serverbound probes:** the same idea in the client→backend direction.
- **Histograms:** HdrHistogram `Recorder` per thread, with `HistogramLogWriter` interval logs (`.hlog`) for later merging and plotting.
- **Correctness gate:** a separate short "verify" run per proxy where sinks fully decode and checksum every packet against the trace, with a whitelist for packets proxies legitimately rewrite (e.g. brand plugin message, JoinGame on switch). A proxy that drops or reorders data must fail. This prevents "fast because it's broken".

### 4.6 Baselines

- **`direct`:** sinks connect straight to the replay backend. This gives the harness and kernel floor. Proxy-added latency ≈ proxy run − direct run.
  - Note: percentiles of a difference are not differences of percentiles. Show both full distributions, and present the delta as an approximation.
- **`l4-relay`:** HAProxy in `mode tcp` (or Gate Lite / Infrarust passthrough). This is the floor for any relay, and it allows the claim "Warp costs X% more than a dumb TCP relay while being a full MC proxy".

### 4.7 Online mode (encryption) without Mojang

- **MockSessionServer:** `hasJoined` always returns a fixed profile for the requested username. Run it on the loadgen.
- **Point each proxy at it:**
  - Velocity: `-Dmojang.sessionserver=http://loadgen:port/session/minecraft/hasJoined` [verified property];
  - Warp: add an override (F5);
  - Gate: config key [likely];
  - BungeeCord: `/etc/hosts` entry for `sessionserver.mojang.com` plus a self-signed certificate imported into the JVM truststore.
- **Sink client:** generates the shared secret, RSA-encrypts it with the proxy's public key, skips the real `join` call, and enables AES-CFB8. Client-side AES is costly for the loadgen. Use velocity-native's OpenSSL cipher in the loadgen and budget extra loadgen cores.

### 4.8 Storms

- **Login storm (S5):** Poisson arrivals at a configured logins/s, from **many source IPs** (all of `127.0.0.0/8` works on Linux loopback; use NIC aliases for two hosts).
  - Disable per-IP limits everywhere: Velocity `login-ratelimit` defaults to **3000 ms per IP** [verified]; BungeeCord `connection_throttle` [likely]; Gate `quota` [likely].
  - Metrics: success rate, login time p50/p99 (TCP connect → first PLAY frame), proxy CPU-ms per login.
  - Run offline and online.
- **Switch storm (S6):** N players connected; M% send `/server <other>` (ChatCommand) within a 1 s window. Warp, Velocity, Gate and BungeeCord all have `/server` built in. Warp's is in `ClientPlaySessionHandler.handleChatCommand` [verified].
  - Metrics: switch completion CDF, failures and stuck players, proxy CPU and GC during the storm, and **collateral p99** for non-switching players.

### 4.9 L4 real-world validation

- Paper backends (pinned) plus PureGero bots (200–500, flying) behind each proxy, and a handful of SoulFire bots for realism.
- Measure proxy CPU, **Paper CPU** (total system), RSS and errors.
- The goal is to check that **rankings and rough ratios** match L3. These numbers are not the headline, because Paper itself becomes the bottleneck.

### 4.10 Load-generator budget sanity check

The load generator must stay well below saturation, so that every limit observed belongs to the proxy.
- For each step, require loadgen CPU < 70% of its cores and schedule-lag p99.9 < 100 µs.
- Require zero unexpected disconnects and `nr_throttled = 0` for the SUT cgroup.
- To saturate a proxy without huge loadgen fleets, **constrain the proxy's cores** (1, 2 or 4 physical cores) rather than adding players without limit. Then show scaling across core counts separately.

---

## 5. Metrics: definitions and how to measure them

| Metric | Definition | How | Notes |
|---|---|---|---|
| **CPU per GB** | SUT core-seconds ÷ GB delivered (clientbound and serverbound reported separately) | cgroup v2 `cpu.stat usage_usec` delta over the measurement window (split into `user_usec` and `system_usec`); bytes counted by the harness from what backends *sent* | Process-agnostic, so it works for Go and Rust proxies too. The `system` share shows kernel networking cost. |
| **CPU per player** | millicores ÷ N at fixed offered load | Same as above | Report per archetype and for the mix |
| Instructions per byte | `instructions` ÷ bytes | `perf stat -e task-clock,cycles,instructions,context-switches,cpu-migrations -p PID -- sleep W` | Bare metal only (needs PMU). Much lower noise than time. |
| **Max players at SLO** | Largest N where p99 added clientbound latency < 5 ms **and** delivered ≥ 99.9% of offered **and** 0 disconnects **and** SUT CPU < 90% of budget | Step ramp of N (e.g. +250 every 3 min after a 2 min warmup), then bisect; per core budget C ∈ {1, 2, 4} | Confirm the headline points with fresh-JVM fixed-N runs |
| **Added latency** p50/p99/p99.9/max | One-way probe latency, proxy run vs direct baseline | HdrHistogram interval logs; open-loop intended-time | Below the knee only (e.g. 50% and 75% of capacity) |
| Allocation rate | Heap bytes allocated ÷ bytes forwarded, and MB/s | JFR `jdk.ThreadAllocationStatistics` / `ObjectAllocationSample` in **separate profiling runs**; GC log heap deltas in all runs | JVM proxies only |
| GC pauses | Pause p50/p99/max and GC CPU share | `-Xlog:gc*,safepoint:file=gc.log:uptime,level,tags` (negligible overhead, so enable it for all JVM proxies) | Gate: `GODEBUG=gctrace=1` |
| **Memory per player** | Slope of heap-live after GC, direct memory and RSS vs N (linear regression across plateaus) | `/proc/PID/smaps_rollup` (Rss/Pss), cgroup `memory.current`, `memory.stat` (anon); `jcmd GC.heap_info` at step boundaries; NMT `summary` **only in memory runs** (≈5–10% overhead) | With `AlwaysPreTouch`, RSS ≈ Xmx from start-up, so compare **heap-live** or run without it |
| Min heap | Smallest `-Xmx` that sustains N at SLO with GC CPU < 5% | Bisect Xmx (DaCapo-style min-heap) | The most honest JVM memory comparison |
| Per-thread CPU | Event loops vs GC vs JIT vs other | `pidstat -t -p PID 1` | Explains differences |
| Hotspots | Where time goes | async-profiler 4.5 (`asprof -e cpu`/`alloc`/`wall`, JFR output) in **separate profiling runs** | Never during headline runs |
| Wire bytes | Client-bound bytes per player-second | Counted at the sink | Detects compression-level differences |
| Switch time | Command sent → first PLAY frame from the new backend | Sink timestamps | Report the CDF, failures, and collateral p99 |
| Login cost | CPU-ms per login, login p99 | cgroup delta ÷ logins | Offline and online |
| Stability (soak) | RSS drift, FD count, heap-live trend, errors over 4 h | Periodic samples | Leak detection |
| Socket health | Send-queue growth, retransmits | `ss -tin`, `nstat` | Back-pressure behaviour |

---

## 6. Fairness and reproducibility

### 6.1 Proxies and configuration normalisation

| Proxy | Version policy | Notes |
|---|---|---|
| Warp | Commit SHA under test | Recommended flags published beforehand |
| Velocity | **Latest 4.x release (4.2.0 on fill API today) and latest 3.x (3.5.1)** [verified both exist]. Which line PaperMC currently recommends as stable is **[uncertain]**; check papermc.io downloads. | The default branch is `dev/4.0.0`, on Netty 4.2.18 [verified] |
| Gate | Latest release, **full mode** (Lite mode only as an L4 reference) | Uses Go stdlib `compress/zlib` [verified] |
| BungeeCord | Latest build | Natives: cloudflare-zlib and mbedTLS [verified] |
| Infrarust v2 | Latest, `offline` intercepted mode (passthrough mode as L4 reference) | Its "forward unmodified frames as original bytes" behaves like passthrough, so it is the most relevant competitor on this axis |
| Waterfall | **End of life** (README caution banner) [verified] | Appendix or historical only |
| HAProxy `mode tcp` | Pinned | L4 floor |

Normalise across all proxies:
- `online-mode=false` (true only in S4, through the mock);
- forwarding: modern for Warp, Velocity and Gate; legacy for BungeeCord;
- compression threshold **256** and level **−1** (Velocity defaults [verified]; Warp's defaults are the same [verified]);
- no plugins, bStats and telemetry off, Gate Connect off;
- identical read timeouts;
- per-IP limits off (Velocity `login-ratelimit=0`) *and* clients spread across many source IPs;
- each proxy's default transport and event-loop count (Netty default 2×cores; Velocity io_uring only with `-Dvelocity.enable-iouring-transport` [verified]);
- default logging, but turn per-connection logging off on all of them for login storms.

**Scenario matrix:**

| ID | Backend link | Client link | Encryption | Shape | Why |
|---|---|---|---|---|---|
| S1 | compress 256 | compress 256 | off | N sweep, archetype mix | Headline for passthrough (default Paper config) |
| S1b | compress 256 | compress 512 | off | N sweep | Threshold mismatch, so Warp's fallback is shown honestly |
| **S2** | **uncompressed (−1)** | compress 256 | off | N sweep | **Velocity's recommended setup.** Mandatory for credibility. |
| S3 | −1 | −1 | off | N sweep | Pure framing and blind forwarding; compare with the L4 relay |
| S4 | 256 (and −1) | 256 | **on** (mock) | N sweep | Amdahl: AES share |
| S5 | 256 | 256 | off and on | Logins/s ramp | Connection setup cost |
| S6 | 256 | 256 | off | N connected, M% switch in 1 s | Switch robustness and latency |
| S7 | 256 | 256 | off | 70% of S1 capacity for 4 h | Leaks and drift |
| S8 | Paper default | 256 | off | 200–500 PureGero bots | Real-world validation and total system CPU |

### 6.2 JVM policy (Warp vs Velocity vs BungeeCord)

- **Same JDK build** for all JVM proxies (e.g. pinned Temurin 21.0.x; record `java -XshowSettings:properties -version`). Velocity 4.x's minimum Java version is **[uncertain]**.
- **Headline policy, identical flags:** `-Xms2g -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch -Dio.netty.leakDetection.level=disabled` plus GC logging. Netty reads the leak-detection property, so it applies equally to everyone.
- **Appendix policy, vendor-recommended flags:** Velocity's documented G1 set vs Warp's published recommendation (currently ZGC generational in `bin/warp.sh`).
- **Sensitivity checks:** G1 vs generational ZGC on both; allocator pooled vs adaptive on both (F7).
- Use **cpusets, never CFS quotas** (`docker --cpus` / `cpu.max`). Quota throttling produces artificial p99 spikes. Java 21 reads cpusets for `availableProcessors()`, so event-loop counts follow automatically.

### 6.3 Verify natives and transports, or abort the run

The orchestrator greps start-up logs and fails the run if the expected line is missing.
- Velocity prints `Connections will use {epoll} channels, {libdeflate ...} compression, {OpenSSL ...} ciphers` [verified format].
- BungeeCord prints native cipher and compressor lines.
- Warp must print equivalent lines. Add them if missing.
- Record the SHA-256 of every jar or binary.

### 6.4 Host preparation (scripted and fingerprinted)

- **CPU:** `performance` governor; turbo or boost disabled (or fixed and reported); SMT off, or SUT siblings left idle; SUT cores within one NUMA node or CCD.
- **Isolation:** cgroup v2 cpuset partition (`cpuset.cpus.partition=isolated`) or `isolcpus`; IRQ affinity away from SUT cores (two-host setup).
- **Memory:** THP setting recorded (and kept the same); swap off.
- **Limits:** `ulimit -n 1048576`; `net.ipv4.ip_local_port_range=1024 65535`; raised `somaxconn` and `tcp_max_syn_backlog`; identical sysctls for every proxy.
- **Fingerprint:** `env.json` with CPU model, microcode, kernel, sysctls, governor, SMT, THP, JDK/Go versions, harness SHA, trace SHA, proxy SHA.

### 6.5 Run protocol

1. Pre-flight checks: idle host, governor, no swap, SUT cgroup empty.
2. For repetition r in 1..R (**R ≥ 5**), for each proxy **in shuffled order** (interleaving spreads thermal and drift effects):
   - fresh process;
   - verify natives;
   - ramp connections (e.g. 50/s);
   - **warm up for 120 s** at target load, checking there is no CPU trend across 10 s buckets;
   - **measure for 180 s**;
   - collect;
   - stop and cool down for 30 s.
3. Validity checks (loadgen health, `nr_throttled`, errors). Invalid runs are kept in the raw data with a reason, never silently dropped.

### 6.6 Statistics and reporting

- **Per configuration:** median over runs with a 95% bootstrap confidence interval. Show every run as a dot.
- **Comparisons:** ratio of medians with a bootstrap CI. Call a difference "meaningful" only if the CI excludes 1.0 and the effect is above ~5%.
- **Latency:** merged HDR percentile plots (log scale) plus the spread of p99 across runs.
- **Required plots:** latency vs offered load (the knee), CPU/GB bars with CIs, memory vs N with slopes, switch CDFs.
- **Pre-register** the hypotheses and scenario list before the first publishable run, and publish the scenarios where Warp loses.

### 6.7 Hardware tiers

| Tier | Setup | Allowed claims |
|---|---|---|
| T0 (WSL2 dev: Ryzen 5 5500 6C/12T, 10 vCPUs exposed, Microsoft hypervisor) [verified] | Develop the harness, functional runs, JMH **B/op** | None about timing. Hyper-V scheduling noise; vCPUs are not pinned to physical cores, so `taskset` does not truly isolate; no PMU counters (WSL2 does not expose them [likely]); Windows background load. |
| **T1 (minimum credible)** | One bare-metal Linux host with ≥16 physical cores (rented dedicated server). SUT on 4 isolated physical cores, loadgen on the rest. Loopback with many `127.x` source IPs. | CPU-efficiency comparisons, capacity per core budget, relative latency. Note that loopback has no NIC or IRQ costs. |
| **T2 (gold)** | Two identical bare-metal hosts on ≥10 GbE (25 GbE preferred), same switch or rack. Proxy alone on the SUT host; loadgen on the other (single clock). Cloud metal in a cluster placement group also works (e.g. AWS `*.metal`). Hourly billing keeps a full matrix to an estimated tens to low hundreds of USD **[uncertain]**. | Every headline claim, including absolute latency over a real NIC |
| Cloud VMs (dedicated vCPU) | Acceptable for trend tracking with many repetitions | Not for headline numbers |

Bandwidth check: offered load = N × (trace byte rate). Keep it under 50% of link capacity on T2. Measure trace byte rates; don't assume them. Existing estimates (0.02–0.08 Mbps per player on average, much more during chunk loading) are too loose to plan with.

### 6.8 Docker Compose vs plain scripts

Recommendation: **plain scripts plus pinned artifacts (URL + SHA-256) are the canonical path.** Use cgroups through `systemd-run --scope -p AllowedCPUs=… -p MemoryMax=…`. Docker or Compose images are an optional convenience, and must use `network_mode: host`, `cpuset`, **no `cpus:` quota**, and images pinned by digest. Bridge or NAT networking adds veth and conntrack overhead and must not be used for measurements.

### 6.9 Openness

- Publish a **separate neutral repository** containing the harness, configs, traces, raw results (`.hlog`, `cpu.stat` samples, logs, `env.json`) and the report generator.
- Version the benchmark specification (v1.0) so results stay comparable over time.
- Disclose that the authors are Warp developers.
- Give Velocity, Gate and Infrarust maintainers a review window to PR their configs (the TechEmpower model).
- Re-run on every Warp release.

---

## 7. Existing published MC proxy benchmarks

| Source | What was measured or claimed | Numbers | Flaws |
|---|---|---|---|
| Velocity docs: "Why Velocity" [verified] | libdeflate "twice as fast as zlib"; "fewer resources than the competition" | Library-level only | No proxy-level data or methodology |
| Velocity FAQ [verified] | "Most CPU time is spent processing packets (especially decompressing and recompressing)"; recommends **disabling backend compression** | — | Qualitative |
| Velocity issue #594 (in Warp's existing research) | Maintainer profiling: compression ≈4–5× costlier than decompression; buffer management is a hotspot | Qualitative | Production anecdote |
| Hosting and blog posts (wisehosting, mineguard, pufferfish docs, SoulFire blog) | "Up to 8× faster than BungeeCord", "1000+ players", "100+ conn/s, switches < 500 ms, < 1 GB RAM" | Marketing | No methodology or hardware. Original source of "8×" not found **[uncertain]**. |
| Gate docs [verified] | "Minimal resource footprint (10 MB RAM)" | One number | No methodology. Only a couple of Go micro-benchmarks in the repo. |
| **Infrarust v2** `docs/v2/reference/benchmarking.md` + `tools/mc-bench` [verified] | Layered suite: A (filter chain), B (frame codec, zlib, AES), C (full intercepted pipeline), D (open-loop end-to-end with HdrHistogram and a direct-baseline delta), E (live per-filter timing) | Unmodified compressed 512 B frame ≈ **120 ns** through the pipeline vs **≈6.3 µs** re-encoded with libdeflate (27 µs with flate2); 16 KiB forward ≈ 1 µs; AES-CFB8 decrypt ≈ 360 MB/s, encrypt ≈ 58 MB/s; loopback ping p50 ≈ 120 µs | Best MC methodology so far, but: **self-only**, a single dev machine, tiny opaque ping packets (no realistic mix), protocol < 764 only, RTT only, **no CPU-efficiency or capacity metric**, no cross-proxy comparison |
| bVelocity (Velocity fork) [verified] | Compression-level sweep on 32 KiB samples × 64 rounds (libdeflate) | L6: 63% savings in 339 µs; L11: 2.29 ms | Synthetic, compression only, no proxy comparison |
| NullCordX (commercial BungeeCord fork) | "100k conn/s anti-bot at 5% CPU" | Vendor claim | Closed and unverifiable |
| Warp `docs/research/performance-analysis.md` | 2–3× system-level gain *estimate* | Estimates | Not measurements. This harness should replace them. |

**Conclusion:** there is still **no rigorous, reproducible, cross-proxy benchmark** in the public domain. This is consistent with Warp's earlier research. Caveat: results posted only on Discord or forums may exist and would not show up in web search.

---

## 8. Proposed repository layout

**In the Warp repo (L1/L2):**
```
build-logic/src/main/kotlin/warp.jmh-conventions.gradle.kts    (WIP exists; add §2.1 items)
protocol/src/jmh/java/dev/warp/protocol/bench/                 M1–M6
protocol/src/jmh/resources/corpus/                             recorded corpora (+ README with provenance)
proxy/src/jmh/java/dev/warp/proxy/bench/                       M7–M9 (incl. real-socket meso)
.github/workflows/bench-smoke.yml                              JMH smoke + B/op gate on PRs
```

**Separate neutral repo (L3/L4), e.g. `mc-proxy-bench`:**
```
README.md                     methodology, spec version, how to reproduce
loadgen/                      Java 21 + Netty fat jar (own minimal codec; velocity-native for zlib/AES)
  backend/ReplayBackend       scripted login/config, open-loop trace replay, probe injection
  client/SinkClient           state machine, frame-split, probes → HdrHistogram, switch flow
  auth/MockSessionServer
  trace/{TraceReader,WireVariants,Manifest}
tracetool/                    recording tap relay, .mcpr/pcap import, trace stats, corpus export
traces/                       *.wtrace + manifest.json (release assets or LFS; CC0)
proxies/<name>/               fetch.sh (pinned URL + sha256), config/, run.sh, expect-log.txt
  warp/ velocity-3/ velocity-4/ gate/ gate-lite/ bungeecord/ infrarust/ haproxy-l4/ direct/
scenarios/                    s1-steady.yaml … s8-paper.yaml (N steps, durations, compression, crypto, mix, core budget)
host/                         tune.sh, check.sh (fingerprint → env.json), cpuset.sh
orchestrator/                 run.py (stdlib only), collect.py, report.py (plots + summary.json)
results/<date>-<host>-<scenario>/raw/{*.hlog,cpu.stat.csv,pidstat.txt,perf-stat.txt,gc.log,proxy.log,env.json}
docker/                       optional Compose (host network, cpuset, digests)
```

---

## 9. Phased plan

| Phase | Content | Exit criteria | Effort (rough, [uncertain]) |
|---|---|---|---|
| **0. Prerequisites in Warp** (in parallel with phase 1) | Session-server override (F5); backend on the client event loop (F4); natives log line; publish recommended JVM flags; choose protocol version V; decide the passthrough design (M3b result) | Merged PRs | Days |
| **1. Micro** | Finish the JMH convention (§2.1); M1–M7; velocity-native baselines; B/op CI gate; first JDK-vs-native compression and AES numbers | JSON baselines archived; gate green; a written verdict on whether natives are a hard prerequisite | 1–2 weeks |
| **2. Traces** | Tap relay; record archetypes on Paper at version V; publish trace set v1 plus manifest; export JMH corpus | Traces reproducible from the manifest | ~1 week |
| **3. Macro MVP** | Loadgen (replay, sink, probes, HdrHistogram), orchestrator, `direct` and `l4-relay` baselines, S1–S3 for Warp vs Velocity; T0 for function, then T1 | Coefficient of variation < 5% on CPU metrics over 5 repetitions; byte-exact verify run passes for both proxies | 2–3 weeks |
| **4. Coverage** | S4–S7; adapters for Gate, BungeeCord and Infrarust; T2 runs; S8 with Paper + PureGero (+ SoulFire); optional `mc-bench` cross-check | Full matrix on T2 | 2–3 weeks |
| **5. Publication** | Maintainer review window, publish repo + data + write-up, continuous re-runs per Warp release (T1 nightly as a release gate) | Public report | — |

**Publication gate:** passthrough implemented (F1), and either natives present (F2/F3) or S2/S4 results that Warp is willing to publish as they stand.

---

## 10. Objections skeptics will raise, and the planned answers

| Objection | Answer built into the design |
|---|---|
| "Nobody runs Velocity with backend compression on." | S2 is mandatory and reported next to S1 |
| "You just moved compression to the backend." | Yes, and it is stated openly. Total system CPU is reported in S8. The value is in unloading the shared bottleneck. |
| "Velocity's natives weren't loaded." | Start-up log verification aborts the run |
| "Different heap/GC/flags." | Identical-flags headline, vendor flags in an appendix, GC and allocator sensitivity runs |
| "Your bots aren't real players." | Recorded real traces; S8 with real Paper |
| "Loopback isn't a network." | T2 two-host runs |
| "Cherry-picked single run." | R ≥ 5, interleaved order, CIs, raw data, pre-registered scenarios |
| "Coordinated omission." | Open-loop intended-time probes, HdrHistogram |
| "Latency at saturation is meaningless." | Latency below the knee; capacity reported separately |
| "Your harness shares Warp's codec bugs." | Independent minimal codec; byte-exact verification run |
| "Compression-level cheat." | Wire bytes per player reported |
| "Warp has fewer features." | State feature parity explicitly. Re-run Warp with event listeners on the hot path once the API exists. |
| "Per-IP rate limits throttled Velocity." | Limits disabled and many source IPs used |

---

## 11. Uncertainties (explicit)

- Per-bot resource cost of **SoulFire**, PureGero and MCProtocolLib bots is not documented. Measure it before planning L4 scale.
- **JDK AES/CFB8 vs OpenSSL** and **JDK zlib vs libdeflate** factors on the target hardware. Measure with M4 and M5.
- Which Velocity line (3.5.x or 4.2.x) PaperMC currently presents as the recommended stable release.
- Whether Paper's compression level equals −1/6 through velocity-native in current builds.
- Gate's exact config keys for the session-server URL and per-IP quotas.
- Whether Warp handles pre-1.20.2 protocols end to end, which the `mc-bench` cross-check needs.
- **Netty adaptive vs pooled** allocator performance for this workload.
- Cloud metal pricing and availability. The rented bare-metal tier is the cheaper option.
- The real-world archetype mix. Publish per-archetype results so readers can re-weight.
- Search coverage: unpublished or Discord-only benchmarks may exist.

---

## 12. Sources

- jmh-gradle-plugin: https://github.com/melix/jmh-gradle-plugin · Plugin Portal: https://plugins.gradle.org/plugin/me.champeau.jmh (metadata: https://plugins.gradle.org/m2/me/champeau/jmh/me.champeau.jmh.gradle.plugin/maven-metadata.xml)
- JMH on Maven Central: https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-core/ · JMH samples (pitfalls): https://github.com/openjdk/jmh/tree/master/jmh-samples
- Netty microbench (`AbstractMicrobenchmark`, `EmbeddedChannelWriteReleaseHandlerContext`): https://github.com/netty/netty/tree/4.2/microbench
- Netty 4.2 migration guide (adaptive allocator default): https://netty.io/wiki/netty-4.2-migration-guide.html · https://netty.io/news/2025/04/03/4-2-0.html
- async-profiler: https://github.com/async-profiler/async-profiler · HdrHistogram: https://github.com/HdrHistogram/HdrHistogram · wrk2 (coordinated omission): https://github.com/giltene/wrk2
- Envoy, "How do I benchmark Envoy?": https://www.envoyproxy.io/docs/envoy/latest/faq/performance/how_to_benchmark_envoy · Nighthawk: https://github.com/envoyproxy/nighthawk
- RFC 2544 (network device benchmarking): https://datatracker.ietf.org/doc/html/rfc2544 · Heiser, "Systems Benchmarking Crimes": https://gernot-heiser.org/benchmarking-crimes.html · TechEmpower: https://www.techempower.com/benchmarks/
- Velocity: source https://github.com/PaperMC/Velocity (`dev/4.0.0`: `TransportType`, `ConnectionManager`, `VelocityServerConnection`, `MinecraftCompressDecoder`, `InitialLoginSessionHandler`, `VelocityConfiguration`) · Tuning: https://docs.papermc.io/velocity/tuning/ · Config: https://docs.papermc.io/velocity/configuration/ · Why Velocity: https://docs.papermc.io/velocity/why-velocity/ · FAQ: https://github.com/VelocityPowered/documentation/blob/master/users/frequently-asked-questions.rst · Versions: https://fill.papermc.io/v3/projects/velocity · Maven: https://repo.papermc.io/repository/maven-public/com/velocitypowered/velocity-native/
- Paper "Use Velocity compression and cipher natives" patch (mirror): https://ayakael.net/mirrors/papermc/commit/fcb6c72bc95ff62b9cd78dec457f485dafcc70a4
- Gate: https://github.com/minekube/gate · https://gate.minekube.com/guide/why
- BungeeCord: https://github.com/SpigotMC/BungeeCord · Waterfall (EOL): https://github.com/PaperMC/Waterfall
- Infrarust: https://github.com/Shadowner/Infrarust (`docs/v2/reference/benchmarking.md`, `tools/mc-bench/README.md`, `crates/infrarust-core/benches/intercepted_pipeline.rs`) · v2.0.0-beta.2 notes: https://newreleases.io/project/github/Shadowner/Infrarust/release/v2.0.0-beta.2
- bVelocity: https://github.com/coralundersea/bVelocity
- SoulFire: https://github.com/soulfiremc-com/SoulFire · https://soulfiremc.com/docs/usage/versions · https://soulfiremc.com/blog/testing-minecraft-proxy-networks · https://soulfiremc.com/blog/stress-testing-minecraft-servers
- PureGero minecraft-stress-test: https://github.com/PureGero/minecraft-stress-test · mc-bots: https://github.com/crpmax/mc-bots · MCProtocolLib: https://github.com/GeyserMC/MCProtocolLib · mineflayer: https://github.com/PrismarineJS/mineflayer · Holy Client: https://github.com/Titlehhhh/Minecraft-Holy-Client
- Hosting/blog claims: https://wisehosting.com/glossary/velocity · https://mineguard.pro/en/blog/velocity-vs-bungeecord-why-switch · https://docs.pufferfish.host/general/velocity-vs-waterfall-vs-bungeecord/
- WSL2 PMU limitation (secondary sources): https://github.com/rr-debugger/rr/issues/2506
- Warp internal: `docs/research/performance-analysis.md`, `bin/warp.sh`, and the source files cited in §1.
