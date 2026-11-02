# Native compression: libdeflate through the FFM API

> Research and implementation notes for `feat/native-compression` (2026-11-02). Issue numbers and
> quotes were checked against the GitHub API (`gh issue view`). Numbers marked **indicative** were
> measured on a shared, loaded WSL2 machine (below); rerun them with `bin/bench-natives.sh`.

## 0. Summary

- Warp compresses with **libdeflate 1.26**, built from the pinned release tarball (SHA-256 in
  `jni/native/libdeflate.env`) and bundled in the jar for Linux x86_64 and aarch64 (glibc and musl),
  macOS x86_64 and aarch64, and Windows x86_64. It is called through `java.lang.foreign`: no C glue.
- **Zero copy, zero allocation:** direct buffers are passed by address (`ByteBuf.memoryAddress()`),
  pointers cross as Java `long`s, method handles are `static final`, no arena or segment per call.
  Heap and composite buffers go through one temporary direct copy.
- **One compressor and one decompressor per event loop thread**, not per connection: native memory
  depends on the number of event loops, not of players. They are freed when the event loops shut
  down (Netty's `FastThreadLocal.removeAll`), or by a `Cleaner` for any other thread.
- **Bounded decompression:** libdeflate decompresses to exactly the declared size (no
  `actual_out_nbytes_ret`), writes nothing past it, and any other size is an error, after
  `FrameDecompressor`'s cap, ratio bound and rate budget, which run before anything is allocated.
- **Safe loading** into a private, content-addressed cache (`~/.cache/warp/natives`, or
  `-Dwarp.natives.dir`), a private temporary directory, or a Linux memory file; a self-test at start;
  `java.util.zip` with one log line saying why and what to do otherwise; `-Dwarp.natives.disable=true`
  forces it.
- **Encryption stays on the JDK** (section 4).

## 1. Design

### 1.1 Loading (`dev.warp.jni.NativeLoader`, `LibraryCache`, `Platform`)

1. **Platform.** `os.name`/`os.arch`, 64-bit pointers required (the bindings pass pointers as
   `long`). musl is detected from the JVM's own memory map (`/proc/self/maps` mentions
   `ld-musl-`), i.e. the C library actually loaded in this process; without `/proc`, from
   `/lib/ld-musl-*.so.1`. Only that platform's build is ever loaded: loading another C library's
   build "to see" can kill the JVM inside `dlopen` (netty/netty#17348).
2. **Install.** The file name carries the version, the platform and the first 16 hex digits of the
   library's SHA-256. In the first usable directory of: `-Dwarp.natives.dir` (alone when set), the
   user cache directory (`$XDG_CACHE_HOME`/`~/.cache` on Linux, `~/Library/Caches` on macOS,
   `%LOCALAPPDATA%` on Windows), a new owner-only directory under `java.io.tmpdir`:
   - the directory is created `rwx------`; once resolved (links followed), it must belong to the
     user Warp runs as and be writable by nobody else, or it is skipped;
   - an existing file is reused only if it is a regular file (not a link), owned by that user, of
     the right size and SHA-256;
   - otherwise the bytes go to an exclusively created temporary file of a random name in the same
     directory, are flushed, then renamed atomically over the final name. Two proxies starting
     together either find the complete file or rename identical bytes over it. Where the rename
     cannot replace a library another process holds (Windows), that process's copy is used after the
     same checks.
   - Checking size and digest before loading matters more than it looks: glibc's `dlopen` dies of
     `SIGBUS` on a truncated library (seen while writing the tests), it does not fail.
3. **Memory file.** On Linux, when no directory works (read-only root filesystem and `/tmp` mounted
   `noexec`, as in hardened containers), the library is written to a `memfd_create` file and opened
   with `dlopen` directly (`SymbolLookup.libraryLookup` and `System.load` resolve
   `/proc/self/fd/N` to a path that does not exist). Not used when a directory was set explicitly.
4. **Open.** `SymbolLookup.libraryLookup(path, Arena.global())`: never unloaded. When it fails, the
   loader asks `System.load` for the system loader's reason (`failed to map segment from shared
   object`, missing symbol...), which `SymbolLookup` does not report.
5. **Self-test** (`Natives.selfTest`), once, before the first connection: a 64 KiB round trip, both
   wrong sizes rejected with the right code, a corrupt Adler-32 rejected, and interoperability with
   `java.util.zip` in both directions.

### 1.2 Calls (`Libdeflate`, `LibdeflateCodec`, `ThreadContexts`)

- `libdeflate_zlib_compress` into a destination grown once to `libdeflate_zlib_compress_bound`
  (a `Linker.Option.critical` call, a few instructions); the encoder now allocates each frame at
  that size instead of growing Netty's default 256 bytes.
- `libdeflate_zlib_decompress(..., out_avail = declared, actual_out_nbytes_ret = NULL)`: exact size
  or `SHORT_OUTPUT` / `INSUFFICIENT_SPACE`; `BAD_DATA` otherwise. Errors are pre-allocated, stackless
  `DataFormatException`s. Bytes after the end of the stream are ignored, as `Inflater` ignores them.
- Contexts: a `FastThreadLocal` per thread holding one compressor per level used and one
  decompressor, allocated on first use. Event loop threads remove their `FastThreadLocal`s when they
  terminate, which frees the contexts; a `Cleaner` registered on the `Thread` frees those of any
  other thread once it is collected. Freed exactly once. `PacketCompressor.close()` frees nothing,
  so a handler removed early (plugins, server switches) can never free a context still in use.

### 1.3 Packaging and CI

- `jni/native/build-libdeflate.sh <platform> <dir>` downloads the tarball once, checks its SHA-256 at
  every build, compiles only the zlib format (no gzip, no CRC-32), exports only libdeflate's API, and
  checks what it built: needed libraries (the C library only; `KERNEL32.dll` only on Windows, whose
  C runtime is linked in), newest glibc symbol 2.17 or older, no executable stack (glibc 2.41
  refuses those), no gcc outline-atomics helpers on aarch64, the right architecture. Flags: `-O2`,
  plus `-fno-tree-vectorize` with gcc (gcc 12+ auto-vectorisation made level 1 1.8x slower,
  `deflate-peek-and-codec-performance.md` §4.4). No `-march`: libdeflate picks its SIMD code at run
  time.
- Gradle builds the machine's own library (`:jni:buildHostNative`) when no `-Pwarp.natives.dir` is
  given; without bash, curl or a C compiler, the jar has none and Warp uses `java.util.zip`.
  `-Pwarp.natives.required=true` (CI) makes that an error and forbids the native tests to skip.
- `.github/workflows/_natives.yml` builds each platform on its own runner (`ubuntu-24.04`,
  `ubuntu-24.04-arm`, Alpine 3.22 in Docker on both, `macos-15-intel`, `macos-15`, `windows-2025`),
  then loads it there with Warp's loader and self-test, twice (install, then reuse). Releases
  bundle the seven libraries and fail if one is missing.
- The shadow jar's manifest already has `Enable-Native-Access: ALL-UNNAMED`; `java -jar warp.jar`
  prints no native access warning. When the module lacks native access (Warp embedded, or started
  with `-cp`), the start-up log says to add `--enable-native-access=ALL-UNNAMED`.

## 2. Known issues in other projects, and what Warp does about each

| Issue | What happened | Warp |
|---|---|---|
| [PaperMC/Paper#11367](https://github.com/PaperMC/Paper/issues/11367) | A glibc build of Velocity's natives loaded on Alpine; the JVM died of SIGSEGV when a player joined. | musl builds made on Alpine, loaded and self-tested on Alpine x86_64 and aarch64 in CI; the JVM's own C library decides which build loads. |
| [netty/netty#17348](https://github.com/netty/netty/pull/17348) | A glibc `.so` on musl aarch64 crashes inside `dlopen` (gcc outline atomics call `__getauxval`). | Never retries with the other C library's build; aarch64 built with `-mno-outline-atomics`, and the build fails if such helpers appear. |
| [xerial/sqlite-jdbc#1475](https://github.com/xerial/sqlite-jdbc/pull/1475) | musl detected from `/etc/os-release`: wrong on glibc images built from Alpine. | Detects the C library mapped into the JVM process. |
| [SpigotMC/BungeeCord#3707](https://github.com/SpigotMC/BungeeCord/issues/3707), [#2015](https://github.com/SpigotMC/BungeeCord/issues/2015) | glibc-built natives segfaulted or tripped `ld.so` assertions on Alpine. | Same as above; and `-Dwarp.natives.disable=true` as a kill switch. |
| [PaperMC/Velocity#260](https://github.com/PaperMC/Velocity/issues/260), [netty/netty#6678](https://github.com/netty/netty/issues/6678) | Natives extracted to a `noexec` `/tmp` fail to load ("failed to map segment"). | Default is the user's cache directory, not `/tmp`; then a private temp directory; then a Linux memory file. Tested in Docker with a read-only root and a `noexec` tmpfs: loads from memory. |
| [netty/netty#15009](https://github.com/netty/netty/pull/15009) | An explicit native workdir that failed was silently replaced. | An explicit `-Dwarp.natives.dir` is the only place tried; its failure is logged with the reason. |
| [PaperMC/Velocity#695](https://github.com/PaperMC/Velocity/issues/695), [PaperMC/Paper#8186](https://github.com/PaperMC/Paper/issues/8186) | Fell back to Java compression without saying why; users and maintainers guessed. | One WARN line: why (each place tried and the loader's reason), what to do, how to silence it. |
| [SpigotMC/BungeeCord#3718](https://github.com/SpigotMC/BungeeCord/pull/3718), [PaperMC/Velocity#1546](https://github.com/PaperMC/Velocity/issues/1546) | A lazy check made the start-up log claim natives that were not used; a library that loaded was not usable. | Load and self-test at start, before the log line and the first connection. |
| [SpigotMC/BungeeCord#3717](https://github.com/SpigotMC/BungeeCord/issues/3717) | SIGILL on an old CPU: natives compiled for SSE4.2/PCLMUL. | No `-march`; libdeflate's run-time CPU dispatch ([ebiggers/libdeflate#180](https://github.com/ebiggers/libdeflate/issues/180)). |
| [PaperMC/Paper#6546](https://github.com/PaperMC/Paper/issues/6546), [luben/zstd-jni#331](https://github.com/luben/zstd-jni/issues/331), [#404](https://github.com/luben/zstd-jni/issues/404) | Removing a handler freed a native compressor still in use; use-after-free between close and compress. | Contexts belong to the event loop thread, not to handlers or connections; `close()` frees nothing; freed once, when the thread ends. |
| Velocity, Paper (`MinecraftConnection.setCompressionThreshold`) | A libdeflate compressor and decompressor per connection: about 650 MiB of native memory per 1000 players at level 6. | Per event loop: measured 14 MiB for 20 event loops, whatever the number of players (section 3.2). |
| [PaperMC/Velocity#1742](https://github.com/PaperMC/Velocity/issues/1742) | Small packets claiming 8 MiB, repeated, OOM-killed proxies. | `FrameDecompressor`'s 1032:1 bound, size cap and rate budget run before allocating; libdeflate never writes past the declared size (tested with a 1 MiB bomb declared as 1 KiB). |
| [SpigotMC/BungeeCord#3825](https://github.com/SpigotMC/BungeeCord/issues/3825), [#3827](https://github.com/SpigotMC/BungeeCord/issues/3827) | Natives chosen though buffers had no memory address (Java 24 without Unsafe): every connection threw. | Any buffer works: in place when it has an address (Netty 4.2.18 gives one on Java 25 even without Unsafe, checked), through a copy otherwise; tested with heap and composite buffers. |
| [SpigotMC/BungeeCord#3850](https://github.com/SpigotMC/BungeeCord/pull/3850), [PaperMC/Paper#10675](https://github.com/PaperMC/Paper/pull/10675) | JEP 472 native access warnings; libraries checking native access. | `Enable-Native-Access: ALL-UNNAMED` in the manifest; `Module.isNativeAccessEnabled()` checked, with advice in the log when it is off. |
| [luben/zstd-jni#415](https://github.com/luben/zstd-jni/issues/415), [SpigotMC/BungeeCord#880](https://github.com/SpigotMC/BungeeCord/issues/880) | A newer build host raised the glibc floor and broke older distributions. | The build fails if any symbol needs glibc newer than 2.17. |
| [fusesource/jansi#288](https://github.com/fusesource/jansi/issues/288), [#2](https://github.com/fusesource/jansi/issues/2) | Overwriting a world-writable file in `/tmp` allowed code injection; a stale cached DLL was reused. | Private directory checks, exclusive temporary file, atomic rename, size and SHA-256 checked before every load; the name carries version, platform and digest. |
| [luben/zstd-jni#295](https://github.com/luben/zstd-jni/issues/295), [#71](https://github.com/luben/zstd-jni/pull/71) | Windows: extracting over a DLL in use fails; loaded DLLs cannot be deleted. | One load per JVM; a rename that cannot replace a library in use falls back to that library once checked; the cache persists instead of deleting. |
| [astei/krypton#22](https://github.com/astei/krypton/issues/22), [Pumpkin-MC/Pumpkin#903](https://github.com/Pumpkin-MC/Pumpkin/issues/903), [ebiggers/libdeflate#106](https://github.com/ebiggers/libdeflate/issues/106) | libdeflate rejects streams that never end (flushed, not finished), which zlib-based peers accepted. | Warp's `java.util.zip` path already required the stream to end at the declared size, so both paths reject the same frames (tested; differential fuzzing against `java.util.zip`). |
| [ebiggers/libdeflate#64](https://github.com/ebiggers/libdeflate/issues/64) | A gcc release miscompiled libdeflate (false `BAD_DATA`). | Pinned toolchain per platform; the shipped binary itself is self-tested on its platform in CI and at every start. |
| [PaperMC/Waterfall#874](https://github.com/PaperMC/Waterfall/pull/874), [SpigotMC/BungeeCord#3962](https://github.com/SpigotMC/BungeeCord/issues/3962) | A native cipher slower than the JDK; native zlib questioned against Java's `ByteBuffer` API. | Both measured on JDK 25 before deciding (sections 3 and 4). |
| [netty/netty#13410](https://github.com/netty/netty/issues/13410) | A freshly copied dylib loads slowly on macOS. | The cached library is reused from the same file at every start. |

## 3. Measurements

### 3.1 Speed and allocation (indicative)

Machine: AMD Ryzen 5 5500 (Zen 3, 10 vCPUs) under WSL2 (Linux 6.6), Temurin 25.0.2, shared
with other agents' builds (load average 7 to 31 during the runs). Corpus: `PacketCorpus`
(synthetic 1.21-shaped packets): `CHUNK` (chunk packets, 60 KiB raw), `SMALL` (block updates and
player info, 0.25 to 3 KiB), `MIXED` (gameplay mix; compressed packets only). Level 6, zlib streams
from `java.util.zip` level 6 for inflate. Velocity = `velocity-native` 4.2.0 (libdeflate 1.21, gcc
9.4, JNI); Warp = libdeflate 1.26 (gcc 13.3, `-O2 -fno-tree-vectorize`, FFM).

**Interleaved timings** (`InterleavedCodecProbe`: each round runs every implementation once, in a
rotating order, so that load weighs on all of them alike; median per packet over the rounds).
Sequential JMH trials drifted by up to 40 % from one run to the next on this machine, so the
interleaved ratios are the numbers to trust here.

*One context for every packet* (best case for per-connection designs):

| Workload | Operation | JDK | Velocity | Warp | Warp / Velocity |
|---|---|---|---|---|---|
| CHUNK | deflate | 902 µs | 330 µs | 343 µs | 1.04 |
| CHUNK | inflate | 141 µs | 25.8 µs | 25.4 µs | 0.98 |
| SMALL | deflate | 36.7 µs | 18.7 µs | 19.7 µs | 1.05 |
| SMALL | inflate | 7.9 µs | 4.9 µs | 4.9 µs | 0.99 |
| MIXED | deflate | 112 µs | 31.9 µs | 34.7 µs | 1.09 |
| MIXED | inflate | 5.9 µs | 1.5 µs | 1.5 µs | 0.99 |

*Packets spread over 1000 connections* (as on a proxy with 1000 players: Velocity and
`java.util.zip` find a different context, cold in the CPU caches, for each packet; Warp's are per
thread):

| Workload | Operation | JDK | Velocity | Warp | Warp / Velocity |
|---|---|---|---|---|---|
| CHUNK | deflate | 874 µs | 328 µs | 330 µs | 1.01 |
| CHUNK | inflate | 170 µs | 32.3 µs | 29.8 µs | 0.92 |
| SMALL | deflate | 74.6 µs | 40.6 µs | **18.9 µs** | **0.47** |
| SMALL | inflate | 6.9 µs | 5.1 µs | 4.4 µs | 0.86 |
| MIXED | deflate | 62.1 µs | 20.8 µs | 17.8 µs | 0.86 |
| MIXED | inflate | 6.3 µs | 1.7 µs | 1.5 µs | 0.89 |

Reading: with one context the two libdeflate builds are the same code at the same speed (within
the noise; minimums match to 1 to 4 %). With a context per connection, compressing a small packet
costs Velocity twice as much as Warp: libdeflate's level 6 compressor is 656 KiB, and touching a
cold one per packet dominates when the packet itself takes microseconds. Against `java.util.zip`,
Warp is 1.9 to 3.9 times faster on deflate and 1.6 to 5.7 times on inflate.

**Library builds, without the JVM** (C harness, `dlopen` of each library, interleaved, single
context, median per packet): at level 6, Velocity's build and Warp's are within 5 % on deflate and
2 % on inflate, in both directions from run to run. At level 1, on chunks: Velocity 93.5 µs, Warp
91.6 µs, while the same libdeflate 1.26 built with plain `-O2` or `-O3` by gcc 13 takes 155 µs: the
`-fno-tree-vectorize` flag is what keeps Warp's build as fast as Velocity's.

**Allocation** (JMH `-prof gc`, escape analysis off, as the allocation guard measures):
Warp's native codec allocates 0.0 to 0.3 B per packet (5.4 B per chunk packet deflated), now
guarded in `protocol/src/jmh/alloc-baseline.json`. With escape analysis on, `java.util.zip` measured
36 to 286 B per packet and Velocity 0 to 18 B.

### 3.2 Native memory for 1000 players

`NativeMemoryProbe`: glibc `mallinfo2()` (bytes malloc has handed out, mmapped blocks included)
and resident set size, after each context compressed and decompressed one chunk packet; one
compressed connection per player, level 6.

| Implementation | malloc in use | resident set | after freeing |
|---|---|---|---|
| Velocity: libdeflate per connection | 642 MiB | 274 MiB | freed |
| Warp, `java.util.zip` fallback: `Inflater` + `Deflater` per connection | 264 MiB | 126 MiB | freed |
| Warp, libdeflate per event loop (20 event loops) | 13 to 14 MiB | 5 MiB | freed at event loop shutdown |

Velocity's figure doubles when the backend link is compressed too (a compressor per backend
connection). Warp's is about 0.7 MiB per event loop thread (one level 6 compressor, one
decompressor), whatever the number of players. The resident set grows towards the malloc figure as
contexts see more varied data.

## 4. Encryption (AES/CFB8)

Measured with the same corpus, as the client leg encrypts and the server leg decrypts every frame
of the compressed stream (interleaved, median per frame):

| Workload | Operation | JDK (`AES/CFB8/NoPadding`) | Velocity (OpenSSL 3.0.13 via JNI) |
|---|---|---|---|
| CHUNK | encrypt | 127 µs | 113 µs |
| CHUNK | decrypt | 118 µs | 138 µs |
| SMALL | encrypt | 18.9 µs | 19.0 µs |
| SMALL | decrypt | 19.8 µs | 19.9 µs |
| MIXED | encrypt | 6.3 µs | 5.8 µs |
| MIXED | decrypt | 5.9 µs | 5.8 µs |

Minimums agree within 2 % (CHUNK encrypt 86.6 vs 87.2 µs). OpenSSL's CFB8 goes through a generic
per-byte loop and Java 25 still has no CFB intrinsic, so both do one AES block per byte at about
50 MB/s per core, as in `deflate-peek-and-codec-performance.md` §5. **Decision: keep the JDK
cipher.** A native one would add a library and its failure modes for no speed. What would help is
a different algorithm: an AES-NI implementation of CFB8 measured 1.7 times faster to encrypt and
about 14 times faster to decrypt (same document), a separate piece of work. The JDK path does
allocate about 2 KiB per frame of temporary arrays on direct buffers (Velocity: none); a reusable
heap array would remove that without any native code.

## 5. Open points

- The six non-Linux-x86_64 libraries are built and load-tested by CI only; they could not be run on
  the development machine. Their first CI run is their first run.
- macOS libraries are ad-hoc signed, not notarised: fine for `dlopen` from the JVM, which does not
  check notarisation for libraries it loads itself.
- Velocity's level 6 is kept as the default. Level 1 to 4 would cut compression CPU a lot for a few
  percent more bytes (`deflate-peek-and-codec-performance.md` §6.5); worth a separate decision.
- The JDK cipher path allocates a temporary array per call on direct buffers (about 2 KiB per frame
  measured); a reusable heap array would remove that, independently of natives.
- `.claude/rules/jni.md` still describes JNI `native` methods; the module now uses the FFM API.
