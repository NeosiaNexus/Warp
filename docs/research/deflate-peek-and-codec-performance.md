# Compression passthrough: packet-id peek, codec landscape and CFB8 (research for Warp)

Date: 2026-10-05. Everything below was measured on one machine. The code is in
benchmark programs that are not kept in the repository (see Appendix A).

> **Read this first: how far to trust the numbers.**
> - **Machine:** AMD Ryzen 5 5500 (Zen 3, 6C/12T, 10 vCPUs visible) under **WSL2** (Hyper-V guest, kernel 6.6).
> - **JDKs:** Ubuntu OpenJDK **21.0.12.1** (links the system zlib 1.3) is the main one. Temurin 21.0.12.1 JRE (bundled zlib 1.3.2) and Temurin **25.0.4.1** (final FFM API) were used for comparison.
> - **Load:** the box was shared with other agents running Gradle builds and JMH. Load average ranged from 4 to 40.
> - **Harness:** all tables use a noise-robust interleaved harness (`Interleaved.java`). Candidates run round-robin in batches of about 100 µs. I report **min**, and sometimes p10 or median, over 200–300 batches. JMH was run as a cross-check. JMH means were 20–40 % higher because of the load, but the ratios were the same.
> - **Payloads:** synthetic packets shaped like 1.21.x packets (chunk with light, light update, container contents, text components, player info with base64 textures, commands, random data). Real Paper traffic will differ in entropy.
> - **Bottom line:** treat absolute numbers as **indicative (±20–30 %)** and the ratios as solid.

---

## 0. TL;DR and recommendation

**Peek technique.** Use a **hand-written, table-less DEFLATE "first-literal" peek** in pure Java (`FastDeflatePeek`, about 300 lines, zero allocation, one instance per event loop). Fall back to a full inflate whenever the peek returns `FALLBACK`.

| first block type (who produces it) | hand-written peek | `Inflater` partial (setInput, inflate 1–2 B, reset) | native zlib partial, no JNI | full inflate, libdeflate | full inflate, JDK zlib 1.3 |
|---|---|---|---|---|---|
| fixed Huffman (~300 B packets, all compressors; zlib-ng level 1 always) | **16 ns** (9 ns simple variant) | 71–84 ns | 31 ns | 590 ns | 815 ns |
| stored (incompressible data) | **16 ns** | 71 ns | — | 1.2 µs | 1.1–2.2 µs |
| dynamic, text-1k (1 041 B raw / 408 B compressed) | **0.47 µs** | 1.43 µs | 1.30 µs | 2.39 µs | 2.97 µs |
| dynamic, container-3k | **0.79 µs** | 1.94 µs | 1.95 µs | 4.29 µs | 5.71 µs |
| dynamic, chunk 75 KiB (libdeflate level 6 / zlib level 6) | **0.70 / 1.04 µs** | 1.79 / 2.81 µs | 1.67 µs | 20.1 µs | 120 µs |

- **The peek's cost depends on the dynamic-header size (about 45–130 bytes), not on the packet size.** It is about 3× cheaper than the `Inflater` partial, because zlib builds its full decode tables (`inflate_table` ×3) even when asked for one byte. It is 5–30× cheaper than a full libdeflate inflate.
- **The peek also avoids re-compression**, which is the real prize. libdeflate level 6 costs about 7 µs for a 300 B packet and about 376 µs for a chunk. JDK zlib level 6 costs about 8 µs and about 950 µs.
- **Correctness.**
  - Valid streams: about 140 000 from zlib 1.3 (levels 0–9 plus FILTERED and HUFFMAN_ONLY), zlib-ng 2.3.3, libdeflate 1.26 (levels 0–12) and ISA-L (levels 0–3). Result: **0 wrong ids and 0 fallbacks**.
  - Streams split by `SYNC_FLUSH`/`FULL_FLUSH` inside the VarInt: about 35 000, all correct.
  - Corrupted streams: about 140 000, with **0 exceptions and 0 disagreements**. Whenever the peek answered, zlib produced the same first bytes.
- **Two-byte packet ids are real.** Since 1.21.2, clientbound PLAY ids go up to `0x85`:
  - 1.21.4: `0x80` set_projectile_power, `0x81` custom_report_details, `0x82` server_links.
  - 1.21.6+ adds `0x83` tracked_waypoint, `0x84` clear_dialog and `0x85` show_dialog.
  - The peek decodes up to two literals. A back-reference at byte #1 or #2 leads to `FALLBACK`. At byte #2 a back-reference would copy byte #1 (≥ 0x80), which would make a VarInt of 3 bytes or more, so it cannot be a valid id.

**Fallback codec.**
- Use **libdeflate through JNI** on Java 21 (the existing `jni/` module, statically linked and version-pinned). Use one compressor and one decompressor **per event loop, not per connection**. A libdeflate level-6 compressor is 656 KiB of native memory, and Velocity and Paper allocate one per connection.
- **Inflate:** libdeflate is 1.25× faster than JDK zlib at 1 KiB, 2.5× at 40 KiB and 6× at 75 KiB on chunks.
- **Deflate:** use level 1–4 for anything Warp re-compresses. On a chunk, level 1 takes 120 µs versus 376 µs at level 6, for about 10 % more bytes.
- **Pin and benchmark your own native build.** GCC 13 at `-O2` (auto-vectorisation) made libdeflate level-1 compression **1.8× slower**. `-fno-tree-vectorize` fixes it.
- **FFM** is preview-only on Java 21, so it is not usable in production. On 22+ it costs the same as JNI (2–5 ns per call), so it only matters once the baseline is JDK 25.

**Encryption becomes the bottleneck once compression is passed through.**
- AES/CFB8 runs at about **50 MB/s per core** in JDK 21, which has no CFB intrinsic and does one AES block per byte. Velocity's OpenSSL 3.0 natives were **no faster here** (48–53 MB/s; `openssl speed` agrees at 49 MB/s).
- A 60-line AES-NI implementation reached **85 MB/s for encrypt** (serial, limited by latency) and **730–750 MB/s for decrypt** (8-way parallel, because CFB8 decryption is parallel).
- Example: a 5.7 KB compressed chunk frame costs about 115 µs to encrypt with the JDK and 0.7 µs to peek.

---

## 1. Partial inflate with `java.util.zip.Inflater`

### 1.1 What zlib does when asked for one byte (verified in zlib 1.3.1 `inflate.c` and jdk21u `Inflater.c`/`Inflater.java`)

- **JDK call path.** `Inflater.inflate(...)` → JNI `inflateBytesBytes`, `inflateBufferBytes`, `inflateBytesBuffer` or `inflateBufferBuffer` → `inflate(strm, Z_PARTIAL_FLUSH)`. The flush mode does not matter to inflate. Heap arrays are pinned with `GetPrimitiveArrayCritical`. Direct buffers go through `NIO_ACCESS.acquireSession` and the raw address. Every public method does `synchronized (zsRef)`.

- **Dynamic block: the full header and all tables are built before the first literal is emitted.** The state machine runs `TABLE → LENLENS → CODELENS` and calls `inflate_table` three times before `LEN` decodes anything:
  - `inflate_table(CODES, 19)` (7-bit table);
  - `inflate_table(LENS, nlen)` with a 9-bit root plus sub-tables (`ENOUGH_LENS` = 852 entries);
  - `inflate_table(DISTS, ndist)` with a 6-bit root (`ENOUGH_DISTS` = 592 entries).

  Code lengths are read with `PULLBYTE` one byte at a time. The fast path `inflate_fast` needs `have >= 6 && left >= 258`, so a 1–2 byte output always takes the slow path.
- **Fixed block:** no table build. `fixedtables()` points at the static `inffixed.h` tables, so it is cheap.
- **Stored block:** direct copy.
- **zlib decodes one symbol ahead.** After writing a literal it goes back to `LEN` and decodes the next code (including `LENEXT`/`DIST`/`DISTEXT` for a match, with the "too far back" check) **before** it notices `left == 0`. So a partial inflate can raise a `DataFormatException` for corruption located after the id. This was seen in 23–62 of 20 000–60 000 corrupted streams. `getBytesWritten()` still reports the bytes produced before the error. The consequence is harmless: such streams go to the full-inflate path, which would also fail.
- **Window:** the 32 KiB window is allocated on the first output (`updatewindow`) and kept across `inflateReset`. One `Inflater` is therefore about 7 KiB of state plus 32 KiB of window.
- **zlib-ng 2.x** has the same structure (the same `inflate_table`); only the table builder and fast loops differ.

### 1.2 Measured cost per call (JDK 21, direct input `ByteBuffer`, `byte[2]` output)

| | fixed (entity-300) | stored (random-4k) | dynamic text-1k | dynamic container-3k | dynamic light-6k | dynamic chunk (libdeflate 6) | dynamic chunk (zlib 6) |
|---|---|---|---|---|---|---|---|
| `setInput` + `reset` only (JNI + monitor floor) | 21 ns | 22 ns | 22 ns | 21 ns | 21 ns | 21 ns | 22 ns |
| inflate 1 B (+1 if continuation), direct input | 71 ns | 71 ns | 1 428 ns | 1 942 ns | 1 332 ns | 1 793 ns | 2 822 ns |
| inflate 2 B in one call, direct input | 74 ns | 70 ns | 1 434 ns | 1 937 ns | 1 335 ns | 1 788 ns | 2 805 ns |
| heap `byte[]` input | 83 ns | 81 ns | 1 459 ns | 1 969 ns | 1 361 ns | 1 835 ns | 2 865 ns |
| **native C, no JNI:** zlib 1.3.1 `inflate(avail_out=2)` + `inflateReset` | 31 ns | — | 1 301 ns | 1 947 ns | — | 1 674 ns | — |
| native C: libdeflate `zlib_decompress(out_avail=2)`, undocumented partial | 12 ns | — | 1 297 ns | 1 969 ns | — | 1 770 ns | — |
| full inflate: JDK zlib / libdeflate | 815 / 593 ns | 1 126 / 1 170 ns | 2 968 / 2 392 ns | 5 712 / 4 292 ns | 12 583 / 3 049 ns | 119 962 / 20 111 ns | 106 973 / 16 562 ns |

Notes on the table:
- The JNI transition, the monitor and the reset together cost about 21 ns. The rest is zlib work.
- For dynamic blocks, about 1.3–1.9 µs is header decoding plus three table builds. That is **about 45 % of a complete inflate of a 1 KiB packet**.
- libdeflate's "partial" mode (ask for 2 bytes and get `LIBDEFLATE_INSUFFICIENT_SPACE`) does write the first bytes. That behaviour is undocumented, and it is no cheaper, because libdeflate builds even larger tables (an 11-bit lit/len table). Do not rely on it.
- **Prior art:** Velocity PR [#1078](https://github.com/PaperMC/Velocity/pull/1078), "Pass compressed packets directly…", is still open. It does exactly this: a separate `JavaVelocityCompressor` (`java.util.zip.Inflater`) does `inflatePartial(…, 5 bytes)`, because libdeflate cannot stream. It also adds a "decompression threshold" below which it fully inflates.

### 1.3 Which zlib does `java.util.zip` actually use? (verified with `readelf`, `/proc/self/maps` and strings)

| JDK | zlib | how verified |
|---|---|---|
| Ubuntu 24.04 `openjdk-21` | **system** `libz.so.1.3` | `ldd libzip.so`, `/proc/self/maps` |
| Temurin 21.0.12.1+1 and 25.0.4.1+1 (and therefore the `eclipse-temurin` Docker images) | **bundled zlib 1.3.2**, statically linked into `libzip.so` (no `NEEDED libz`) | `readelf -d`, `strings` shows "inflate 1.3.2" |
| Fedora 40+ distro OpenJDK | system zlib, which is **zlib-ng-compat** since the accepted [F40 change](https://fedoraproject.org/wiki/Changes/ZlibNGTransition) | change page. *I did not verify Fedora's java package linkage directly.* |
| distro JDK with `LD_LIBRARY_PATH` set to a zlib-ng-compat build | zlib-ng 2.3.3 | `/proc/self/maps` (used for the zlib-ng numbers below) |

Implication: Warp cannot control which zlib `java.util.zip` uses. On the most common container JDK (Temurin) it is plain zlib, the slowest option measured. This is another reason to ship a native codec.

### 1.4 Gotchas

- **Use direct buffers.** Heap input/output uses `GetPrimitiveArrayCritical`. On JDK 21 G1 this goes through GCLocker and can delay GC; region pinning only arrived in JDK 22. It was also about 10 ns slower here.
- **Netty `ByteBuf.nioBuffer()` allocates** a duplicate on every call. Use `internalNioBuffer(index, len)`, which reuses a cached instance for pooled and unpooled direct buffers, and never keep the result.
- **Always `reset()` in a `finally`.** `setInput(ByteBuffer)` keeps a reference to the buffer until reset. If the pooled `ByteBuf` is released while the `Inflater` still points at it, a later `inflate` without a new `setInput` would read freed memory.
- **Call `end()`** when the event loop shuts down to free native memory deterministically. The `Cleaner` is only a backstop. After `end()`, every method throws.
- **Thread safety:** the class is internally synchronized, but it is one stream. Use **one `Inflater` per event loop** (FastThreadLocal or a handler-shared instance). Never share it across loops, and never allocate one per connection.
- `inflate()` returns 0 both for "needs input" and for "needs dictionary" (FDICT). Reject FDICT up front.
- JMH `-prof gc` shows **0 B/op** for every peek variant: Inflater direct, Inflater heap, the hand-written peek with direct or heap buffers, and libdeflate through FFM.

---

## 2. Hand-written DEFLATE first-literal peek

### 2.1 Algorithm (puff.c-style canonical decoding; no full tables)

1. **zlib header.**
   - CM must be 8, CINFO ≤ 7, and `(CMF·256 + FLG) % 31 == 0`.
   - **FDICT set → fallback.** A preset dictionary would allow a back-reference as the first symbol.
2. **Copy the first bytes into a reusable scratch `byte[]`.**
   - The copy size depends on BTYPE, which is bits 1–2 of the first deflate byte: 320 bytes if dynamic, otherwise 32.
   - The copy is followed by 16 zero bytes. If decoding runs past the window and the stream is longer, it retries once with 1 024 bytes, which covers the theoretical maximum header of about 562 bytes.
   - Decoding then uses 64-bit little-endian loads (`VarHandle`) and a libdeflate-style branch-free refill (`bitbuf |= load64 << bitsleft; p += (63-bitsleft)>>3; bitsleft |= 56`).
3. **Block loop**, at most 4 blocks. BFINAL and BTYPE:
   - **Stored (00):** align, check that `LEN == ~NLEN`, then the bytes are the output.
   - **Fixed (01):** a static 512-entry table indexed by the next 9 bits. Symbols 286 and 287 are invalid → fallback.
   - **Dynamic (10):**
     - HLIT/HDIST/HCLEN; reject `HLIT > 286` or `HDIST > 30`, as zlib does.
     - Build a **128-entry table only for the 19-symbol code-length code**. It must be complete, as zlib requires for `CODES`.
     - Decode all `HLIT+HDIST` code lengths with repeat codes 16/17/18. Fall back on a repeat with no previous length or on overflow.
     - Count non-zero lengths inline. Zero runs are only filled, never counted, which avoids a store→load dependency chain.
     - Validate like `inflate_table`: lens[256] ≠ 0; neither the lit/len nor the distance code may be over-subscribed; an incomplete code is only allowed when its maximum length is 1.
     - Decode **one or two symbols** with canonical decoding, one bit at a time (puff.c `decode()`), then map the rank to a symbol by scanning the code lengths. Neither a lit/len table nor a distance table is ever built.
   - **BTYPE 11:** fallback.
4. **Symbol handling.**
   - A literal is accepted. One with bit 7 clear ends the VarInt; one with bit 7 set needs a second literal.
   - **End-of-block** (256) continues with the next block. Encoders that flush inside the VarInt are handled; this was tested with `SYNC_FLUSH`/`FULL_FLUSH` after 0–2 bytes.
   - **A length code means fallback.** At byte #1 it is impossible, because there is nothing to copy. At byte #2 it would copy byte #1, which is ≥ 0x80, giving a VarInt of 3 bytes or more, which is not a valid id.
5. **Overrun accounting.** Bytes past the copied window read as zero. Every decision point checks `consumedBits > n*8`. Failure exits that are within 16 bits of the window end are retried with the large window, so the final answer is exact.

What it does **not** validate (by design): the rest of the stream and the Adler-32. In passthrough mode a corrupt backend frame is forwarded and the client disconnects, which is the same behaviour as blind forwarding. Backends are trusted. For the serverbound direction (an untrusted client), the backend's own decompression limits still apply; Warp's `CompressionDecoder` ratio and size checks on the declared `dataLen` can still run without inflating.

**Correctness and safety arguments.**
- All array indices are bounded by construction: `lens` index < 316, `clTable` index masked with `& 127`, `FIXED_LIT` index masked with `& 511`, counts < 32, and scratch reads ≤ n + 8 + 8.
- All loops are bounded: 4 blocks, 316 lengths, 15 bits, at most 2 stored bytes.
- The only exceptions possible are Netty's own bounds checks if the caller passes an invalid `index`/`length`.

**Prior art.** zlib's `contrib/puff/puff.c` (Mark Adler) is the reference for table-free canonical decoding: count arrays plus bit-by-bit decode. I found no existing "first-literal peek" library, and none in Velocity, Gate or BungeeCord forks. Velocity PR #1078 uses an `Inflater` partial instead. *Uncertain: something may exist that I did not find.*

### 2.2 Results (JDK 21; ns per call, min over 300 interleaved batches; the "simple" variant reads the `ByteBuf` directly with the state in fields)

| payload / first block | fast peek, direct buffer | fast peek, heap buffer | simple peek | Inflater partial (best) | speed-up vs Inflater |
|---|---|---|---|---|---|
| entity-300 / fixed | 16.0 | 15.8 | 9.1 | 70.9 | 4.4× (fast), 7.8× (simple) |
| random-4k / stored | 15.9 | 15.2 | 13.2 | 70.1 | 4.4× |
| text-1k / dynamic (272+20 lengths, 379 bits) | 468 | 463 | 856 | 1 428 | 3.1× |
| container-3k / dynamic (285+23, 728 bits) | 791 | 783 | 1 238 | 1 937 | 2.4× |
| light-6k / dynamic (286+23, 449 bits) | 517 | 507 | 985 | 1 332 | 2.6× |
| chunk 75 KiB / dynamic, libdeflate 6 (276+27, 657 bits) | 697 | 688 | 1 160 | 1 788 | 2.6× |
| chunk 75 KiB / dynamic, zlib 6 (286+30, 927 bits) | 1 044 | 1 038 | 1 355 | 2 805 | 2.7× |

- JMH cross-check (heavier load, means): fast peek 15.9 ns / 693 ns / 1 039 ns versus Inflater 104 ns / 1 902 ns / 2 627 ns for entity / text-1k / chunk.
- JDK 25 gives the same picture: fast peek 16 / 491 / 746 ns versus Inflater 78 / 1 448 / 1 708 ns.
- **Ablation (text-1k):** inline counting costs about 70 ns and the rank→symbol scan about 30 ns. The rest is spread over the code-length decode loop (~80 code-length symbols), the code-length table fill and the copy.
- **Possible further micro-optimisations** (not done; maybe 15–25 % more):
  - 4 code-length symbols per refill;
  - split count arrays to break dependency chains;
  - a SWAR rank scan;
  - for fixed and stored blocks, decode straight from the `ByteBuf` without copying (the simple variant is 9 ns versus 16 ns).
- **This is far below the other per-packet costs** (encryption, see section 5), so stopping here is reasonable.

---

## 3. Which compressor emits which first block

First-block type and compressed size as % of raw. **F** = fixed, **D** = dynamic, **S** = stored. The full CSVs are in `results/survey-*.csv`.

| compressor | entity 291 B | container 662 B | text 1 041 B | playerinfo 1 074 B | container 3 000 B | text 4 149 B | light 8 241 B | chunk 76 718 B | map 16 396 B | text 16 395 B | commands 40 kB | random 300 B | random 4 KiB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JDK zlib 1.3, levels 1–9 | F 88–89 | D 52–54 | D 40–42 | D 82 | D 34–37 | D 28–30 | D 3–4 | D 7–9 | D 64–66 | D 20–23 | D 43–48 | S 104 | S 100 |
| zlib-ng 2.3.3, **level 1** (`deflate_quick`) | F 89 | **F 61** | **F 53** | **F 100** | **F 42** | **F 38** | F 4 | **F 11** | F 84 | F 29 | F 56 | **F 108** | **F 106** |
| zlib-ng 2.3.3, levels 2–9 | F 88–89 | D 51–52 | D 39–41 | D 82 | D 34–36 | D 27–30 | D 3 | D 7–8 | D 64–71 | D 19–22 | D 43–46 | S 104 | S 100 |
| libdeflate 1.21 / 1.26, levels 1–12 (Velocity and Paper use level 6) | F 88–89 | D 51–52 | D 39–40 | D 82 | D 34–36 | D 27–29 | D 3–4 | D 6–8 | D 61–70 | D 18–21 | D 42–46 | S 104 | S 100 |
| libdeflate 1.26, level 0 | S 104 | S 102 | S 101 | S 101 | S 100 | S | S | S | S | S | S | S 104 | S 100 |
| ISA-L 2.32, level 0 | **S 104** | D 72 | D 56 | S 101 | D 41 | D 34 | D 6 | D 10 | D 86 | D 25 | D 54 | S 104 | S 100 |
| ISA-L 2.32, levels 1–3 | F 90 | D 53–54 | D 41 | D 82 | D 37 | D 29–30 | D 4 | D 9 | D 72–73 | D 21–22 | D 46–47 | S 104 | S 100 |

**Fixed→dynamic crossover** (`results/crossover.csv`, 20 seeds per size):
- High-entropy binary packets (entity metadata) stay fixed up to about 450–500 B and are all dynamic from about 700 B.
- Text, component and container-like data is **dynamic even at 256 B** (0–10 % fixed).
- The threshold for zlib level 6 and libdeflate level 6 is nearly identical.

Conclusions:
- **Vanilla and Minestom** (`new Deflater()` = zlib level 6) and **Paper and Velocity** (`velocity-native` = libdeflate 1.21 at level 6; Paper uses `misc.compression-level` = default → -1 → 6) produce **dynamic blocks for nearly every compressed packet larger than about 500 B**. So the dynamic-header path is the one that matters, and the peek is built for it.
- **Fixed blocks only appear for small, high-entropy packets**, or always with **zlib-ng level 1**. zlib-ng level 1 even "compresses" random data to 106–108 %.
- **libdeflate and zlib (levels ≥ 1) emit stored blocks for incompressible data** (+4 B header/trailer overhead plus 5 B per block). ISA-L level 0 does so too.
- **ISA-L level 0 emits dynamic blocks** with a precomputed static header, so the peek still parses a header there.
- **Dynamic header size:** 357–1 019 bits (45–128 bytes), with HLIT 257–286 and HDIST 15–30. This is what drives the peek's cost.

---

## 4. Full-codec landscape for the fallback path

Min ns per call. `level` = 6 unless stated. FFM and JNI wrap the same libdeflate. "Velocity" means the shipped `velocity-native` 3.5.1 .so (identical to 4.2.0: libdeflate 1.21, JNI, Netty `ByteBuf`).

### 4.1 Inflate (known output size)

| impl | entity 291 B | text 1 041 B | container 3 000 B | light 8 241 B | text 16 KiB | commands 40 kB | chunk 75 KiB |
|---|---|---|---|---|---|---|---|
| JDK + system zlib 1.3 | 852 | 3 061 | 6 056 | 10 370 | 25 066 | 125 163 | 122 372 |
| JDK (Temurin) + bundled zlib 1.3.2 | 865 | 2 971 | 5 867 | 11 360 | 17 427 | 122 443 | 122 113 |
| JDK + **zlib-ng 2.3.3** (via `LD_LIBRARY_PATH`) | 826 | 2 661 | 4 693 | **2 060** | 10 363 | 50 621 | **18 515** |
| libdeflate 1.21 via FFM | 600 | 2 361 | 4 516 | 2 935 | **8 484** | **48 091** | 18 415 |
| libdeflate 1.26 via JNI | **594** | **2 353** | **4 489** | 2 958 | 8 504 | 49 431 | 19 795 |
| Velocity natives (libdeflate 1.21, JNI) | 608 | 2 387 | 4 521 | 3 032 | 8 815 | 50 877 | 20 480 |
| ISA-L 2.32 `isal_inflate_stateless` (JNI) | 1 400 | 2 723 | 5 699 | 3 691 | 10 444 | 54 716 | 37 844 |

### 4.2 Deflate (compressed size in bytes in brackets)

| impl | entity 291 B | text 1 041 B | container 3 000 B | light 8 241 B | text 16 KiB | commands 40 kB | chunk 75 KiB |
|---|---|---|---|---|---|---|---|
| JDK zlib 1.3, level 6 | 8 185 (255) | 11 342 (411) | 41 481 (1 017) | 38 746 (241) | 209 285 (3 225) | 894 540 (17 132) | 950 402 (5 741) |
| JDK zlib 1.3.2 bundled (Temurin), level 6 | 8 756 | 11 818 | 29 324 | 50 491 | 214 425 | 1 009 063 | 1 109 155 |
| JDK zlib-ng, level 6 | 7 511 (258) | 10 448 (413) | 23 297 (1 022) | 18 978 (236) | 103 652 (3 221) | 497 971 (17 562) | 479 982 (5 502) |
| libdeflate 1.26, level 6 (FFM/JNI; Velocity same ±5 %) | 7 090 (255) | 9 442 (408) | 19 702 (1 017) | 25 857 (249) | 123 663 (3 194) | 424 059 (17 292) | 373 229 (5 677) |
| JDK zlib 1.3, level 1 | 7 580 (258) | 8 742 (435) | 19 504 (1 107) | 9 601 (351) | 74 641 (3 716) | 412 329 (19 066) | 236 936 (7 228) |
| **JDK zlib-ng, level 1** (fixed Huffman only) | **2 880** (260) | **3 760** (556) | **6 549** (1 259) | 3 124 (373) | **20 733** (4 729) | 131 653 (22 257) | **45 271** (8 538) |
| libdeflate 1.26, level 1 (`-fno-tree-vectorize`) | 5 408 (259) | 6 146 (421) | 9 641 (1 069) | 10 992 (297) | 34 604 (3 452) | 179 574 (18 340) | 120 003 (6 243) |
| ISA-L, level 0 | **611** (302) | **1 646** (580) | **3 786** (1 242) | **1 784** (450) | 16 630 (4 160) | **87 852** (21 671) | 44 761 (7 938) |
| ISA-L, level 1 | 6 248 (261) | 5 520 (430) | 11 376 (1 103) | 5 688 (302) | 21 171 (3 562) | 99 273 (18 793) | 63 527 (6 791) |
| ISA-L, level 3 | 6 929 (261) | 7 228 (426) | 17 580 (1 099) | 26 416 (364) | 67 141 (3 497) | 190 965 (18 512) | 255 236 (6 759) |

### 4.3 Per-call overhead (what dominates at MC sizes)

- **Deflate has a 5–8 µs floor per call** for zlib, libdeflate and ISA-L levels 1–3, even for 291 bytes. It comes from clearing or initialising the match-finder hash tables:
  - zlib `deflateReset` clears a 64 KiB `head[]`;
  - libdeflate level 6 initialises the hc_matchfinder (the compressor is 656 KiB in total);
  - ISA-L uses a 276–340 KiB level buffer.
- zlib-ng level 1 (2.9 µs) and ISA-L level 0 (0.6 µs) avoid most of that floor, at the cost of +20–40 % bytes.
- **For a 300 B packet, re-compression costs 450–900 times as much as the fixed-block peek** (16 ns).
- **Inflate per-call floor:** about 0.6 µs for libdeflate and about 0.85 µs for zlib at 291 B (this includes the decode). ISA-L's is about 1.4 µs (`inflate_state` is 85 KiB).

**Native memory per context** (measured with `mallinfo2`):

| context | size |
|---|---|
| libdeflate compressor, level 0 | 6.6 KiB |
| libdeflate compressor, level 1 | 200 KiB |
| libdeflate compressor, levels 2–9 | **656 KiB** |
| libdeflate compressor, levels 10–12 | 8.6 MiB |
| libdeflate decompressor | 11.3 KiB |
| zlib deflate (wbits 15, memLevel 8) | 262 KiB |
| zlib inflate | 7 KiB + 32 KiB window |
| ISA-L | 85 KiB inflate + 80 KiB stream + 276–340 KiB level buffer |

**Velocity** (`MinecraftConnection.setCompressionThreshold` → `Natives.compress.get().create(level)`) **and Paper allocate a compressor per connection.** That is about 0.67 MiB of native memory per player at level 6, or about 650 MiB for 1 000 players. Because MC compression is stateless per packet, **Warp should keep one compressor and one decompressor per event-loop thread.**

### 4.4 Building the native library is part of the result

- My first libdeflate builds (GCC 13.3, `-O2`, and `-O3`) produced **byte-identical output** to Velocity's build, but **level-1 compression was 1.8× slower**: light-6k 21.6 µs versus 11.2 µs, chunk 199 µs versus 120 µs.
- With `-O2 -fno-tree-vectorize` the speed matches Velocity's build exactly. GCC ≥ 12 auto-vectorises at `-O2` and slows `ht_matchfinder` down.
- Level 6 and inflate were not affected.
- **Lesson:** for Warp's `jni/` module, pin the libdeflate version and compiler flags, and run a codec benchmark in CI.

### 4.5 JNI vs FFM (Panama)

| call | JMH, JDK 21 (ns/op) | interleaved, JDK 25 (min ns) |
|---|---|---|
| Java baseline (`x+1`) | 0.9 | 3.4 (harness overhead included) |
| JNI no-op | 6.1 | 6.8 |
| FFM downcall, pointer passed as `long` | 5.1 | 5.6 |
| FFM with `isTrivial` (21) / `critical(false)` (22+) | 3.0 | 6.1 (noisy) |

- On codec calls (≥ 590 ns) **FFM, JNI and Velocity's JNI are indistinguishable** (within ±3 %) on both JDK 21 and JDK 25.
- **JDK 21: FFM is a preview API** ([JEP 442](https://openjdk.org/jeps/442)). It needs `--enable-preview` at compile time and run time, and the class files only run on 21, so it is not usable in a production library.
- **FFM became final in JDK 22** ([JEP 454](https://openjdk.org/jeps/454)). `Linker.Option.critical(true)` even allows passing heap segments (`byte[]`) without copying.
- Since JDK 24, JNI library loading and FFM restricted methods both warn unless `--enable-native-access` is set ([JEP 472](https://openjdk.org/jeps/472)).
- **Zero allocation with FFM:** declare pointer parameters as `JAVA_LONG` (same register class on x86-64 SysV) and hold the `MethodHandle`s in `static final` fields. No `MemorySegment` is created per call.
- **Recommendation:** use JNI now in `jni/`, ship libdeflate statically, and add a pure-Java fallback. Revisit FFM (no C glue, heap access) when the baseline becomes JDK 25 LTS, for example as an MR-JAR `versions/22` implementation.

### 4.6 The others, briefly

- **zlib-ng.**
  - Inflate is as fast as libdeflate on large frames (chunk 18.5 µs; light-6k 2.06 µs, which is even better) and about 1.1× slower on ≤ 3 KiB frames.
  - Level 1 deflate is the fastest real compressor here, but it is fixed-Huffman only (+30–50 % bytes).
  - Warp gets zlib-ng through `java.util.zip` only on Fedora-like distro JDKs. Shipping it would mean another JNI binding (the native `zng_*` API, to avoid symbol clashes).
  - libdeflate's whole-buffer API fits MC's known-size frames better.
- **ISA-L (igzip).**
  - Level 0 is extremely cheap (0.6–1.8 µs on small packets, 45 µs on a chunk) but produces +20–40 % bytes.
  - Inflate was *slower* than libdeflate here (chunk 38 µs versus 20 µs; 1.4 µs floor).
  - It needs nasm, assembly per architecture, and a large per-context state.
  - It is not recommended as the default. It could be an optional "cheap level 0" path if CPU matters more than bandwidth.
  - Literature reports large gains on big streaming buffers and poorer ratios on small flushed buffers ([Intel](https://www.intel.com/content/www/us/en/developer/articles/technical/intel-isa-l-and-intel-integrated-performance-primitives-zlib-solutions.html), [isa-l#9](https://github.com/intel/isa-l/issues/9)).
- **Chromium zlib:** not measured. Its known improvements are SIMD adler32/crc32, chunked copies in `inflate_fast` and faster `fill_window`; the reported gains are mostly for large streams. zlib-ng contains equivalent work, so *expect roughly zlib-ng-like inflate (unverified)*.

---

## 5. Bonus: AES-128-CFB8 (stays on the hot path)

**JDK 21 intrinsics** (jdk21u `vmIntrinsics.hpp`): `AESCrypt.encryptBlock/decryptBlock`, CBC, **ECB**, CTR and GCM. **There is no CFB intrinsic.** `CipherFeedback` with `numBytes = 1` does, for every byte, `embeddedCipher.encryptBlock` (single-block AES-NI) plus a 15-byte `System.arraycopy` plus an XOR.

| implementation | 64 B | 1 KiB | 16 KiB | throughput |
|---|---|---|---|---|
| JDK `AES/CFB8/NoPadding` encrypt (heap in place = Velocity's Java path) | 1 263 ns | 20 073 ns | 321 988 ns | **≈ 51 MB/s** |
| JDK decrypt | 1 248 ns | 19 050 ns | 323 208 ns | ≈ 51–54 MB/s |
| Velocity natives, OpenSSL 3.0.13 `EVP_aes_128_cfb8`, encrypt | 1 329 ns | 21 376 ns | 338 218 ns | **≈ 48 MB/s** |
| Velocity natives, OpenSSL decrypt | 1 221 ns | 19 416 ns | 309 187 ns | ≈ 53 MB/s |
| `openssl speed -evp aes-128-cfb8` (encrypt / decrypt) | | | | 49.5 / 47 MB/s |
| **AES-NI, serial encrypt** (`c/cfb8.c`, JNI) | 753 ns | 12 032 ns | 189 314 ns | **≈ 85 MB/s** (latency-bound: 10 rounds × ~4 cycles) |
| **AES-NI, 8-way parallel decrypt** | 104 ns | 1 400 ns | 21 737 ns | **≈ 730–750 MB/s** |
| Reference: JDK AES/ECB over 16× the bytes (the work CFB8 implies) | 302 ns | 5 615 ns | 95 862 ns | 2.8 GB/s of AES → upper bound of about 170 MB/s for a pure-Java "ECB-batched" CFB8 decrypt |
| Reference: `openssl speed` CBC encrypt / CTR | | | | 1.49 GB/s / 10.3 GB/s |

The AES-NI implementation was checked bit-for-bit against the JDK across 200 random chunk sizes (encrypt and decrypt, state carried across calls).

Takeaways:
- **On Zen 3 with OpenSSL 3.0, Velocity's native cipher is no faster than the JDK.** OpenSSL's CFB8 goes through generic `CRYPTO_cfb128_8_encrypt` with per-byte overhead. *Uncertain: OpenSSL 1.1.x or BoringSSL, or Intel CPUs, may differ.*
- **CFB8 encryption is serial by construction**: each register depends on the previous ciphertext byte. Only about 1.7× is available (85 MB/s). **Decryption is embarrassingly parallel**, because all ciphertext is known (14×).
- **Further idea, not tested:** multi-buffer encryption that interleaves the AES pipelines of N connections on the same event loop could approach the decrypt numbers for the encrypt direction.
- **CPU budget after passthrough:** a clientbound chunk frame of about 5.7 KB costs about 0.7 µs to peek but about 115 µs to encrypt with the JDK (67 µs with AES-NI). Encryption dominates; the codec no longer does. Inbound (serverbound) traffic is small, but parallel decrypt makes it almost free.

---

## 6. Concrete design suggestions for Warp

1. **Clientbound frame, compressed (`dataLen > 0`):**
   - Run `FastDeflatePeek.peekPacketId(frame, idx, len)`.
   - If the id is not intercepted in the current state, forward the frame verbatim.
   - If it is intercepted, or the peek returned `FALLBACK`, take the full path: libdeflate inflate, decode, then re-encode.
   - Keep the peek instance per event loop.
2. **Uncompressed frames (`dataLen == 0`)** carry the id in plain bytes. This is the common case for most small PLAY packets.
3. **Passthrough requires the client-side threshold to equal the backend threshold.** Otherwise, frames with a size between the two thresholds must be re-framed. *Out of scope here; flagged.*
4. **The native module** should provide libdeflate (pinned version, `-O2 -fno-tree-vectorize`) for inflate and deflate, plus AES-NI CFB8 (serial encrypt, parallel decrypt). Guard it with `Natives.isAvailable()`, with Java fallbacks: `java.util.zip` and the JDK `Cipher`. Use per-event-loop contexts.
5. **Re-compression level** for packets Warp itself produces or modifies: use libdeflate level 1–4, not 6. On a chunk, level 1 costs about 3× less time for +10 % bytes. Level 4 was not measured, so measure L2–L4 before choosing a default.
6. **Keep a test corpus.** Add the fuzzer from `Survey.java` (valid, flush-split and corrupted streams cross-checked against `java.util.zip.Inflater`) as a unit or integration test, and add real captured frames from the testbed.

---

## 7. Uncertainties and open points

- **Environment:** WSL2 on a shared, loaded machine and one CPU (Zen 3). Intel CPUs with a different AES latency, AVX-512 or VAES would change the CFB8 and libdeflate numbers somewhat. Bare metal would lower all absolute numbers.
- **Payloads are synthetic.** Real chunk packets with block entities, real Paper entity metadata and real registry and tag data may have different header sizes. Header size, not payload size, drives the peek's cost, and it is bounded at ≤ 562 bytes. *Next step: capture real frames in `~/DEV/warp-testbed` and re-run `Survey` and `Runs peek`.*
- **Velocity's numbers** come from the shipped `velocity-native` .so (focal/gcc-9 build). Paper uses `velocity-native` 4.1.0. I checked that 3.5.1 and 4.2.0 ship byte-identical `.so` files and assume 4.1.0 is the same.
- **Fedora's OpenJDK** linkage to zlib-ng-compat is inferred from the accepted F40 change, not verified on a Fedora system. Alpine and Oracle JDK zlib linkage were not checked.
- **ISA-L** was built from source with default flags and only its stateless APIs were tested. A stateful or tuned use might behave differently.
- **Chromium zlib** was not measured.
- **The JDK 25 FFM `critical` call result** (6.1 ns) is within noise. The JMH run on JDK 21 (3.0 ns) is the more reliable number.
- **The interleaved harness** calls each operation through a megamorphic `LongSupplier`. That adds about 2–4 ns to every measured operation, which only matters for the fixed or stored peeks (about 10–16 ns) and for the call benchmarks.

---

## Appendix A: benchmark sources

The throwaway benchmark programs used for this research (peek variants, `Inflater` partial inflate,
JNI libdeflate/zlib-ng/ISA-L bindings, CFB8 implementations, fuzzers) are not kept in the
repository. Warp's JMH suite (`protocol/src/jmh`: `PacketIdPeekBenchmark`, `CodecCostBenchmark`,
`ForwardingPathBenchmark`) reproduces the measurements that drive decisions.

## Appendix B: `FastDeflatePeek.java` (benchmark-quality reference for a future optimisation of `DeflatePeek`)

```java
package bench;

import io.netty.buffer.ByteBuf;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Allocation-free peek of the leading packet-id VarInt of a zlib stream (RFC 1950/1951) without
 * inflating it. Same validation as zlib for everything parsed; any doubt -> FALLBACK (caller must
 * fully inflate). Not thread-safe: one instance per event loop (~1.9 KiB scratch).
 */
public final class FastDeflatePeek {

  public static final int FALLBACK = -1;
  static final int WINDOW = 1024;
  private static final int NEED_MORE = -3;
  static final int SMALL_WINDOW = 320;

  private static final VarHandle LE_LONG =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
  private static final byte[] CL_ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
  /** Fixed-Huffman lit/len table indexed by next 9 bits: (sym << 4) | len. */
  private static final short[] FIXED_LIT = new short[512];

  static {
    byte[] fixed = new byte[288];
    for (int i = 0; i < 144; i++) fixed[i] = 8;
    for (int i = 144; i < 256; i++) fixed[i] = 9;
    for (int i = 256; i < 280; i++) fixed[i] = 7;
    for (int i = 280; i < 288; i++) fixed[i] = 8;
    if (!fillTable(FIXED_LIT, 9, fixed, 288, new int[16], new int[16])) throw new AssertionError();
  }

  private final byte[] in = new byte[WINDOW + 16]; // +16: 8-byte loads past the end read zeros
  private final byte[] lens = new byte[286 + 30];
  private final short[] clTable = new short[128];
  private final byte[] clLens = new byte[19];
  private final int[] cnt = new int[32]; // [0..15] lit/len counts, [16..31] distance counts
  private final int[] tmp = new int[16];
  private final int[] next = new int[16];

  /** @return packet id (0..16383) or FALLBACK */
  public int peekPacketId(ByteBuf src, int index, int length) {
    if (length < 3) return FALLBACK;
    int cmf = src.getUnsignedByte(index);
    int flg = src.getUnsignedByte(index + 1);
    if ((cmf & 0x0F) != 8 || (cmf >>> 4) > 7 || ((cmf << 8) | flg) % 31 != 0 || (flg & 0x20) != 0) {
      return FALLBACK; // not deflate / bad check / FDICT
    }
    int avail = length - 2;
    byte[] b = in;
    int firstWindow = ((src.getUnsignedByte(index + 2) >>> 1) & 3) == 2 ? SMALL_WINDOW : 32;
    int n = Math.min(avail, firstWindow);
    src.getBytes(index + 2, b, 0, n);
    LE_LONG.set(b, n, 0L);
    LE_LONG.set(b, n + 8, 0L);
    int r = decode(b, n, avail);
    if (r != NEED_MORE) return r;
    n = Math.min(avail, WINDOW); // rare: header larger than the small window
    src.getBytes(index + 2, b, 0, n);
    LE_LONG.set(b, n, 0L);
    LE_LONG.set(b, n + 8, 0L);
    r = decode(b, n, avail);
    return r == NEED_MORE ? FALLBACK : r;
  }

  private int decode(byte[] b, int n, int avail) {
    final int bail = n < avail ? NEED_MORE : FALLBACK;
    long bb = 0; int bc = 0; int p = 0; int b0 = -1;
    for (int block = 0; block < 4; block++) {
      if (p > n + 8) return bail;
      bb |= (long) LE_LONG.get(b, p) << bc; p += (63 - bc) >>> 3; bc |= 56;
      int bfinal = (int) bb & 1;
      int btype = (int) (bb >>> 1) & 3;
      bb >>>= 3; bc -= 3;
      if (btype == 0) {                                   // stored
        int drop = bc & 7; bb >>>= drop; bc -= drop;
        if (bc < 32) {
          if (p > n + 8) return bail;
          bb |= (long) LE_LONG.get(b, p) << bc; p += (63 - bc) >>> 3; bc |= 56;
        }
        int len = (int) bb & 0xFFFF;
        int nlen = (int) (bb >>> 16) & 0xFFFF;
        bb >>>= 32; bc -= 32;
        if (len != (~nlen & 0xFFFF)) return fb(p, bc, n, bail);
        for (int k = 0; k < len; k++) {
          if (bc < 8) {
            if (p > n + 8) return bail;
            bb |= (long) LE_LONG.get(b, p) << bc; p += (63 - bc) >>> 3; bc |= 56;
          }
          int lit = (int) bb & 0xFF; bb >>>= 8; bc -= 8;
          if ((long) p * 8 - bc > (long) n * 8) return bail;
          if (b0 < 0) { if ((lit & 0x80) == 0) return lit; b0 = lit; }
          else return (lit & 0x80) != 0 ? FALLBACK : (b0 & 0x7F) | (lit << 7);
        }
      } else if (btype == 1 || btype == 2) {
        int nlenSyms = 0;
        if (btype == 2) {                                 // dynamic header
          int hlit = (int) bb & 31, hdist = (int) (bb >>> 5) & 31, hclen = (int) (bb >>> 10) & 15;
          bb >>>= 14; bc -= 14;
          nlenSyms = hlit + 257;
          int ndist = hdist + 1, ncode = hclen + 4;
          if (nlenSyms > 286 || ndist > 30) return fb(p, bc, n, bail);
          byte[] cl = clLens;
          for (int k = 0; k < 19; k++) cl[k] = 0;
          for (int k = 0; k < ncode; k++) {
            if (bc < 3) {
              if (p > n + 8) return bail;
              bb |= (long) LE_LONG.get(b, p) << bc; p += (63 - bc) >>> 3; bc |= 56;
            }
            cl[CL_ORDER[k]] = (byte) (bb & 7); bb >>>= 3; bc -= 3;
          }
          short[] ct = clTable;
          if (!fillTable(ct, 7, cl, 19, tmp, next)) return fb(p, bc, n, bail);
          int[] c = cnt;
          for (int k = 0; k < 32; k++) c[k] = 0;
          byte[] l = lens;
          int total = nlenSyms + ndist, i = 0;
          while (i < total) {
            if (bc < 14) {
              if (p > n + 8) return bail;
              bb |= (long) LE_LONG.get(b, p) << bc; p += (63 - bc) >>> 3; bc |= 56;
            }
            int e = ct[(int) bb & 127]; int el = e & 15; bb >>>= el; bc -= el;
            int sym = e >>> 4;
            if (sym < 16) {
              l[i] = (byte) sym;
              if (sym != 0) c[sym | (((nlenSyms - 1 - i) >>> 31) << 4)]++;
              i++;
              continue;
            }
            int val, rep;
            if (sym == 16) {
              if (i == 0) return fb(p, bc, n, bail);
              val = l[i - 1]; rep = 3 + ((int) bb & 3); bb >>>= 2; bc -= 2;
            } else if (sym == 17) {
              val = 0; rep = 3 + ((int) bb & 7); bb >>>= 3; bc -= 3;
            } else {
              val = 0; rep = 11 + ((int) bb & 127); bb >>>= 7; bc -= 7;
            }
            if (i + rep > total) return fb(p, bc, n, bail);
            if (val == 0) { for (int k = 0; k < rep; k++) l[i + k] = 0; i += rep; }
            else for (int k = 0; k < rep; k++, i++) { l[i] = (byte) val; c[val | (((nlenSyms - 1 - i) >>> 31) << 4)]++; }
          }
          if ((long) p * 8 - bc > (long) n * 8) return bail;
          if (l[256] == 0) return fb(p, bc, n, bail);                 // missing end-of-block
          if (!checkCode(c, 0) || !checkCode(c, 16)) return fb(p, bc, n, bail);
        }
        for (int s = 0; s < 2; s++) {                     // up to two symbols
          if (bc < 15) {
            if (p > n + 8) return bail;
            bb |= (long) LE_LONG.get(b, p) << bc; p += (63 - bc) >>> 3; bc |= 56;
          }
          int sym;
          if (btype == 1) {
            int e = FIXED_LIT[(int) bb & 511]; int el = e & 15; bb >>>= el; bc -= el;
            sym = e >>> 4;
            if (sym >= 286) return fb(p, bc, n, bail);
          } else {                                        // canonical, bit by bit (puff.c)
            int[] c = cnt; int code = 0, first = 0, len = 1; sym = -1;
            for (; len <= 15; len++) {
              code |= (int) (bb >>> (len - 1)) & 1;
              int k = c[len];
              if (code - k < first) {
                int rank = code - first;
                byte[] l = lens;
                for (int q = 0; q < nlenSyms; q++) if (l[q] == len && rank-- == 0) { sym = q; break; }
                break;
              }
              first = (first + k) << 1; code <<= 1;
            }
            if (sym < 0) return fb(p, bc, n, bail);
            bb >>>= len; bc -= len;
          }
          if ((long) p * 8 - bc > (long) n * 8) return bail;
          if (sym < 256) {
            if (b0 < 0) { if ((sym & 0x80) == 0) return sym; b0 = sym; }
            else return (sym & 0x80) != 0 ? FALLBACK : (b0 & 0x7F) | (sym << 7);
          } else if (sym == 256) {
            break;                                         // end of block -> next block
          } else {
            return fb(p, bc, n, bail);                     // back-reference where a literal is required
          }
        }
      } else {
        return fb(p, bc, n, bail);                         // BTYPE 3
      }
      if (bfinal == 1) return fb(p, bc, n, bail);
    }
    return fb(p, bc, n, bail);
  }

  /** Failure near/past the copied window end -> retry with the large window; else FALLBACK. */
  private static int fb(int p, int bc, int n, int bail) {
    return (long) p * 8 - bc + 16 > (long) n * 8 ? bail : FALLBACK;
  }

  /** zlib inflate_table() acceptance: not over-subscribed; incomplete only if max length <= 1. */
  private static boolean checkCode(int[] c, int off) {
    int left = 1, max = 0;
    for (int len = 1; len <= 15; len++) {
      int k = c[off + len];
      left = (left << 1) - k;
      if (left < 0) return false;
      if (k != 0) max = len;
    }
    return left == 0 || max <= 1;
  }

  /** Direct lookup table for a complete canonical code with max length <= bits. */
  private static boolean fillTable(short[] table, int bits, byte[] l, int n, int[] c, int[] nx) {
    for (int k = 0; k < 16; k++) c[k] = 0;
    for (int k = 0; k < n; k++) c[l[k]]++;
    c[0] = 0;
    int left = 1;
    for (int len = 1; len <= 15; len++) {
      left = (left << 1) - c[len];
      if (left < 0 || (len > bits && c[len] != 0)) return false;
    }
    if (left != 0) return false;
    nx[1] = 0;
    for (int len = 1; len < 15; len++) nx[len + 1] = (nx[len] + c[len]) << 1;
    int size = 1 << bits;
    for (int s = 0; s < n; s++) {
      int len = l[s];
      if (len == 0) continue;
      int rev = Integer.reverse(nx[len]++) >>> (32 - len);
      short entry = (short) ((s << 4) | len);
      for (int j = rev; j < size; j += 1 << len) table[j] = entry;
    }
    return true;
  }
}
```

**AES-NI CFB8 core** (`c/cfb8.c`):
- **Encrypt** is serial: `ks = AES(reg); ct = pt ^ ks[0]; reg = insert(srli(reg,1), ct, 15)`.
- **Decrypt** processes the buffer from the end towards the start in groups of 8, each `AES(loadu(d + i - 16))` independent. Processing back to front means every window still holds ciphertext. The first 16 bytes come last, from a stitched copy that includes the previous 16 ciphertext bytes.

## Sources

- zlib 1.3.1 `inflate.c`/`inftrees.h`; jdk21u `Inflater.java`, `Inflater.c`, `vmIntrinsics.hpp`, `CipherFeedback.java`; libdeflate 1.21/1.26 sources and NEWS; Velocity `native/` C sources and build scripts; Paper `0008-Use-Velocity-compression-and-cipher-natives.patch` (read directly).
- Velocity PR #1078: https://github.com/PaperMC/Velocity/pull/1078
- Fedora zlib-ng transition: https://fedoraproject.org/wiki/Changes/ZlibNGTransition and https://discussion.fedoraproject.org/t/f40-change-proposal-transitioning-to-zlib-ng-as-a-compatible-replacement-for-zlib-system-wide/95807
- ISA-L context: https://www.intel.com/content/www/us/en/developer/articles/technical/intel-isa-l-and-intel-integrated-performance-primitives-zlib-solutions.html, https://github.com/intel/isa-l/issues/9
- FFM vs JNI background: https://mail.openjdk.org/pipermail/panama-dev/2020-July/009723.html, https://inside.java/2023/08/29/ffm-strings-performance
- Packet ids: PrismarineJS minecraft-data `pc/1.21.4` and `pc/1.21.8` `protocol.json`
