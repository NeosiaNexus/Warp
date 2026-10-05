# Compression passthrough: prior art and lessons learned

> Research cycle #5 (2026-10-05) for Warp's compression passthrough: forward backend-compressed frames
> verbatim and only peek at the packet ID. Issue and PR numbers, commits and quotes were checked
> against the GitHub API or the source unless marked **[unverified]**. Numbers marked **[local]** come
> from quick benchmarks run during this research (§9); Warp's JMH suite (`protocol/src/jmh`)
> supersedes them.
>
> **Outcome:** implemented as `feat(protocol): forward compressed frames without recompressing them`,
> adopting innovations #1–#7, #9–#11 and #17 of §10 (#5 as "verify, then forward the original
> bytes"). See `deflate-peek-and-codec-performance.md` for the peek and codec measurements and
> `compression-frame-validation.md` for the vanilla validation rules.

---

## 0. TL;DR

- **Prior art in full proxies that can switch servers.**
  - **GoLilyPad** (Go, 2014-09, `ca98b8014f`) has forwarded unregistered packets verbatim since 1.8.
  - **IvanCord** (Waterfall fork, 2022-03, Janmm14's BungeeCord #3240 patch) skips the re-deflate.
  - **Infrarust v2** (Rust, 2026-07, `44c159742d`) skips the re-deflate when thresholds are equal and
    copies the backend's threshold to the client at first login.
  - **Relay** (Java/Netty, 2026-08, `08f65084a9`) skips the re-deflate when T_client ≤ T_backend. The
    author measured 95-99 µs → 42-52 µs of proxy CPU per frame on real Paper with 40 players.
  - **All four still inflate (or nearly inflate) every frame.** None of them does a true "peek the ID
    only" in production.
- **Peek-only attempts were never merged.** Velocity #357 (2020) was rejected by astei: backends
  should use `-1`, and libdeflate cannot stream. Velocity #1078 (2023) is open and stale; astei:
  "zlib decompression is actually _really_ cheap, the actual CPU-intensive part is compressing". It
  added a 2048 B carve-out. Velocity-CTD tried twice (#663 in 2025-09, #917 in 2026-05) and gave up:
  "the gains are probably close to none". No numbers were published.
- **Key lessons.**
  1. The win comes from skipping the **re-deflate**: 3x the inflate cost at 300 B, 17x at chunk size
     [local].
  2. Re-deflating must never depend on packet size.
  3. Threshold compatibility depends on direction and client version. Clients 1.17.1+ never validate;
     clients ≤ 1.17 and all servers do.
  4. Serverbound passthrough moves decompression-bomb risk onto the backends.
  5. Pipeline plugins (ViaVersion, PacketEvents) silently break.
  6. Ratio limits break vanilla traffic (Velocity #1792). Budget decompressed bytes per second instead.
  7. Once deflate is gone, AES/CFB8 is the next wall: about 48 MB/s on the JDK [local].
- **How Warp can do better than all of them:**
  - a real peek with a pure-Java, allocation-free decoder that coexists with libdeflate [local:
    1.4–3.3 µs flat, fuzzed OK];
  - an interest-set decode predicate (Kroxylicious-style);
  - version-aware threshold rules;
  - padded-VarInt-tolerant, byte-for-byte frame forwarding with run splicing;
  - claimed-size budgets plus a provable 1032:1 check;
  - native CFB8;
  - for PrimeMC's Minestom backends, which already compress once for N viewers, an optional
    "stored-block ID prefix" framing. It is decoded by stock zlib, adds 5.4 B per packet, and makes the
    peek free [local].

---

## 1. Velocity (PaperMC)

### 1.1 Issue #594 "Tighter pipeline control" — full content

- **Author: TheMode** (Minestom's creator), opened **2021-11-12**, still **open**, 1 comment.
  *Correction for `docs/research/performance-analysis.md`:* it attributes the "4-5x" figure to "5zig";
  the issue author is TheMode.
- Body (verbatim excerpts):
  > "Here are the hotspots with no plugins but a lot of active connections:
  > 1. Compression 2. Decompression 3. Pooled buffers management"
  >
  > "Those concerns could be highly mitigated by moving some of the work to the backend and warning
  > Velocity each time a packet has been modified by a third party. This warning is the most important
  > part of the suggestion since it allows:
  > - packets not to be re-compressed if unmodified
  > - packets not to be re-compressed if you are using the same version as the backend and ViaVersion is installed
  > - packet listening (providing the payload would likely be enough) without hurting the rest of the pipeline"
  >
  > "Decompression could be improved by allowing a third party to modify the packet header (to include
  > the packet id) and force others to explicitly register which packets they are interested in.
  > **Compression is still about x4-5 more expensive** making it a niche use-case where a fork could work just as well."
  >
  > "...a single class responsible for handling the current protocol & appending third party listeners,
  > using only a few ThreadLocal buffers (Potentially Netty's `FastThreadLocal`)"
- Read carefully, the "x4-5" sentence says: *removing decompression* is niche because compression
  costs 4-5x more than decompression — i.e. the real prize is skipping **re-compression**.
- Only comment — **Janmm14, 2023-12-20**: "Also one could stop decompression early once the packet id
  has been decompressed and nobody wants to edit the packet. Via-Plugins on proxy are not supported by
  modern tight anticheats anyway."
- Never acted upon by maintainers. No linked PR.

### 1.2 PR #357 "Skip unnecessary decompression of compressed packet from server/client" (2020) — REJECTED

- CatCoderr, 2020-08-19, closed same day. Design: inflate first 5 bytes (max VarInt) → if ID not in
  registry, write the compressed packet as-is. Tested on 1.16.2.
- **hugmanrique**: "Some servers prefer to handle all packet decompression at the proxy level (due to its
  better performance). Perhaps a config option could be added to preserve current behavior? Imo
  velocity-natives should stay protocol-agnostic and shouldn't assume the data to decompress is a packet
  starting with a varint id."
- **astei (Velocity lead) — "-1"**, key reasons (verbatim):
  > "The recommended setup is to have backend servers send uncompressed data to the proxy and let it compress data for you."
  >
  > "...it won't work with Velocity 1.1.0 because we switched to **libdeflate, which doesn't support
  > streaming (it is entirely block based)**. I'm not sure supporting this feature is worth losing the
  > performance gains of libdeflate."

### 1.3 PR #1078 "Pass compressed packets directly to the backend server/client" (2023) — OPEN, stale

- JNNGL, opened 2023-09-07, last update 2024-01-20, base `dev/3.0.0`, `mergeable_state: dirty`,
  +554/−201 over 28 files. 13 commits (`18a0d1f2` … `9c1b6eb4`).
- Design (read from the diff):
  - `MinecraftCompressAndIdDecoder` replaces `MinecraftCompressDecoder`; emits `IdentifiedPacket`
    (`UncompressedPacket(id, buf)` or `CompressedPacket(id, uncompressedLen, compressedBuf, compressor)`).
  - **Partial inflate uses a second, Java (`java.util.zip.Inflater`) compressor** —
    `JavaVelocityCompressor.FACTORY.create(1)` — because libdeflate cannot stream. New
    `VelocityCompressor.inflatePartial(src, dst, size)` default-throws `UnsupportedOperationException`.
  - New config `decompression-threshold = 2048`: claimed uncompressed size < 2048 → full inflate
    (and later full re-deflate); ≥ 2048 → peek 5 bytes, keep compressed.
  - `MinecraftDecoder` lazily decompresses a `CompressedPacket` only if the registry knows the ID.
  - `MinecraftCompressorAndLengthEncoder` (now `MessageToByteEncoder<IdentifiedPacket>`): for a
    `CompressedPacket`, if `uncompressedLength < threshold || threshold <= 0` → **decompress and send
    uncompressed** (threshold-mismatch safety), else write `varint(len) + varint(uncompressedLen) +
    compressedBuf` verbatim.
- Maintainer discussion (verbatim):
  - **astei, 2023-10-27**: "This has been proposed many times. By all means, this is not a bad idea. My
    main concern is that it could introduce overhead from decompressing to check the packet ID. **zlib
    decompression is actually _really_ cheap, the actual CPU-intensive part is compressing the packet.**
    Personally, I would rather have Mojang pull out the packet ID from compressed packets, but the window
    to do that was in 2013 and that's well past us now."
  - JNNGL: "Wouldn't that still be faster than recompressing every packet?"
  - **astei, 2023-10-30**: "Compressing small packets is still pretty cheap. Could we have a carveout for
    packets with a compressed size lower than, say, 2048 bytes? Larger packets will benefit from this much
    more than smaller packets."
  - ytnoos: "Maybe make a configurable amount" → became `decompression-threshold`.
- Never merged; no benchmark numbers ever posted.

### 1.4 Related Velocity history

| Ref | Date | What | Relevance |
|---|---|---|---|
| PR #491 (Leymooo) / **PR #493** (astei, merged 2021-05-09) | 2021 | Combine compression + length encoding in one handler; "specially optimized VarInt writer that always emits 21-bit numbers" (dummy 3-byte length, back-patched). | Warp's encoder should keep this trick for the slow path. |
| PR #974 "Igzip native compressor" (JNNGL, closed 2023-10-27) | 2023 | igzip claimed 3x faster than libdeflate (102 vs 303 "mbps", i9-9900k). **ebiggers** (libdeflate author): comparison invalid — Velocity default level -1→6 for libdeflate vs level 2 igzip; "libdeflate level 1 compresses more than any igzip level". | Compression *level* matters more than library. |
| astei in #974 (2023-03-15) | | "The default `compression-threshold` is 256 - **I have long believed 1024 is a more sensible setting (ideally the setting should be the minimum MTU of your clients, less some...)**" + "Setting [compression-level] to 1 should provide the fastest possible compression." | Threshold/level tuning is the maintainers' answer to CPU cost. |
| PR #1527 (jonesdevelopment, merged 2025-03) | 2025 | Validate that `dataLength==0` frames are actually < threshold. | **Broke modded servers** → #1556 (Carpet-TIS sends 16 KiB uncompressed on purpose); fixed by `-Dvelocity.skip-uncompressed-packet-size-validation`. Lesson: don't be stricter than vanilla. |
| Issue #1742 (WouterGritter, 2026-03-17, open) | 2026 | Real-world **decompression attack** OOM-killing proxies: floods of tiny frames claiming ~8 MiB (99% of cap). Leymooo: "probably ... or you have compression disabled between proxy <--> backend, so these buffers are stored inside netty's internal write queue" (no backpressure in config-state handlers). Root cause still disputed (adaptive vs pooled allocator). | Passthrough shifts where inflation happens; accounting must still happen at the proxy. |
| Commit `9890c429` (electronicboy, 2026-04-08) | 2026 | "Add compression ratio limiter" (default 64). | **Reverted** `ab8333d6` (2026-05-14) because vanilla book edits exceed it (#1792: 12451/132 B; 100-page book 25404/183 = **138.8:1**). electronicboy: "highly repeating patterns compress very well, only real fix to this would be to remove the compression threshold restriction". |
| **PR #1786** (merged 2026-05-24, ported from Velocity-CTD #871) | 2026 | `decompressed-bytes-per-second` limiter (default 5 MiB/s over a 7 s window = 35 MiB headroom); `packets-per-second` default 500 → disabled. "500 packets/s at 2 MiB each is 1 GiB/s of decompressed data". | The right metric = decompressed bytes/time. Can be computed from the **claimed** size without inflating. |
| PR #1743 (booky10, 2026-03-18) | 2026 | Size checks on config-phase packets, known-packet enforcement in login/config, bounded inbound queue. md5nake: the plugin-message limit broke Fabric/Cobblemon (1 MB plugin messages). | Size caps in CONFIG must be generous for modded traffic. |
| PR #1672 "Refactor Velocity networking to be closer to vanilla" (astei, 2025-10-19, open, +5587/−5040, 109 files, branch `astei/packet-immutable`) | 2025-26 | Immutable packets, split encode/decode codecs (`PacketDecoder`/`PacketEncoder`). **Does not touch `MinecraftCompressDecoder` / `MinecraftCompressorAndLengthEncoder`** (only `SetCompressionPacket` changed). | No passthrough in Velocity's roadmap as of Oct 2026. |

Current Velocity `MinecraftCompressDecoder` (dev/3.0.0): serverbound cap **2 MiB**, clientbound cap 8 MiB
(128 MiB with `-Dvelocity.increased-compression-cap`), checks `claimed >= threshold`, checks
`actual == claimed` after inflate, then accounts `claimedUncompressedSize` in the `PacketLimiter`.

### 1.5 Why Velocity never shipped it (synthesis)

1. **Official stance (2020)**: run backends with compression *off* (`-1`) and let the proxy compress →
   in that topology passthrough saves nothing.
2. **libdeflate is block-based** → no partial inflate; a second (zlib) inflater per connection is needed
   (memory + JNI per packet). Losing libdeflate was judged worse.
3. **"Decompression is really cheap; compressing small packets is cheap"** → expected gain concentrated in
   big packets → carve-out at 2048 → smaller win → never prioritised.
4. **Ecosystem**: ViaVersion and PacketEvents insert `MessageToMessageDecoder<ByteBuf>` handlers between
   the compression decoder and `minecraft-decoder` (ViaVersion `VelocityChannelInitializer` L51-52,
   `VelocityDecodeHandler` moves itself back after `COMPRESSION_ENABLED`; PacketEvents
   `ServerConnectionInitializer` L35-36). A `CompressedPacket` object silently bypasses them.
5. **Natives are protocol-agnostic by policy** (hugmanrique, #357).
6. Nobody posted numbers; maintainer time went to security (2025-26 decompression attacks).

---

## 2. BungeeCord / Waterfall / forks

| Project | Status | What they did |
|---|---|---|
| **BungeeCord PR #1504** (Moehritz, 2015-06-17, closed next day) | Rejected | Separate thresholds user↔bungee vs bungee↔server so servers can disable compression. Author after testing: "Wow. Just tested it with lots of players. You say 10Gbit Networking interface? Fuck that, you´ll need something like 500Gbit... Well then, trying something else :(". md_5: "Only really chunk packets get compressed, and they've been doing that for 6 years." — **the bandwidth cost of `-1` on backends is real at scale.** |
| **BungeeCord issue #2445** (2018) | Closed | Request for split compression config; Janmm14 explains backend↔proxy threshold is dictated by the server ("This would violate the protocol specifications... sanity checks present in vanilla server"). |
| **BungeeCord issue #3238** (2022-01-08) | Open | libdeflate request. Janmm14: "Bungeecord has to decompress and compress packets for every (!) player on the whole network ... We have to decompress to know the packet id, but if we see it is a packet we do not have to modify, like a chunk packet, we don't have to compress the just decompressed data, but can forward the original compressed packet". Also: "nearly nobody does it [disable compression on Spigot]. I know of a network who had very very high bandwidth usage with compression disabled on bukkit." vs andreasdc: "Nearly everybody does it. It's often recommended to disable compression on bukkit under proxy." — **the community is split on `-1`.** |
| **BungeeCord PR #3240 "Don't recompress unchanged packets"** (Janmm14, 2022-01-08) | **Open since 2022**, never reviewed by md_5 | `PacketWrapper` gains `ByteBuf compressed`; `PacketDecompressor` emits wrappers holding **both** decompressed and original compressed bytes; `MinecraftDecoder` drops the compressed copy for fully-decoded packets; `PacketCompressor` writes the original compressed bytes if present. **Still fully decompresses** (only skips re-compression). Most of the diff is EntityMap refactoring because entity-ID rewriting modifies many packets (must invalidate the compressed copy). Janmm14: "This patch could break stuff like ViaVersion-on-bungee or other bungee plugins which do not restrict themself to the bungee api, especially if they interact with the netty pipeline." Threshold semantics explained: bukkit 4000 / bungee 256 → packets 256-3999 compressed by bungee, ≥4000 passed through. |
| **IvanCord** (MrIvanPlays, Waterfall fork) | **Shipped 2022-03-03** (`dfdc7d70`, patch `0034-Don-t-recompress-unchanged-packets.patch`) | Janmm14's patch. First attempt: "I keep getting time outs. compression-threshold: 256 both on the server im connecting and bungee." → reimplemented, worked. Janmm14: "You need to invalidate the compressed buffer when entity rewriting changes the buffer contents." IvanCord already broke Via plugins. Low adoption (11★, last push 2023-06). The only **Java/Bungee-lineage** shipped compression reuse found (see §3.3 Relay and §4 GoLilyPad/Infrarust for the others) — and it is "skip re-deflate", not "skip inflate". |
| FlameCord | Unknown | linsaftw (2022-02-15): "I will implement this into FlameCord if you give me permission to" — permission granted. FlameCord is now closed-source (BuiltByBit); **could not verify** it shipped. |
| NullCordX / XCord / Travertine / Waterfall | No evidence | NullCordX changelog mentions only "enable compression only for players joining the antibot filter" (Geyser `disable-compression`). Travertine archived 2021; Waterfall EOL (no compression-reuse patch in its tracker). |
| **caoli5288 private BungeeCord branch** (comment on #3240, 2022-12-21) | Private, unverified | "Perhaps a tunneling protocol could also remove unnecessary decompression. `| magic numbers | varint packet type | compressed packet data |` I did this in my private branch with **cpu usage from 1000% to 200% cpu per thousand players**." — i.e. backend cooperates by putting the packet ID *outside* the zlib stream. Fork `caoli5288/BungeeCord` on GitHub has no such branch. |
| BungeeCord issue #3962 "Remove native zlib" (md_5, 2026-03-21, open) | Open | md_5 wants to drop the custom native zlib for Java 11+ `ByteBuffer` Inflater/Deflater. Outfluencer: "zlib ng should be faster (but for the minecraft protocol usecase libdeflate should be even faster)". |

---

## 3. Velocity forks and new proxies (2025-2026)

### 3.1 Velocity-CTD (GemstoneGG, 326★, very active) — **tried twice, abandoned**

- **PR #663** (R00tB33rMan, 2025-09-25 → closed 2025-09-29, not merged): port of Velocity #1078.
  Body: "this implementation is unusable". Closing comment: **"This isn't worthwhile. I did some extensive testing"**.
- **PR #917** "Refactored passing compressed packets directly to the backend/server" (2026-05-21 → closed
  2026-05-29, not merged, +802/−226):
  - Same design as #1078, cleaned up: Java `Inflater` for `inflatePartial` (loop until `size` bytes,
    `needsInput()`/`finished()` guards, `reset()` in finally), `decompression-threshold = 2048`,
    passthrough frames **accounted in the packet limiter by claimed size**, `handleUnknown(IdentifiedPacket)`
    in both play session handlers, size-aware flush (`LARGE_PACKET_THRESHOLD` / `MAXIMUM_PACKETS_TO_FLUSH`).
  - WouterGritter: "huge diff that will deviate us from upstream ... A marginal gain in performance might
    not be worth the future merge conflicts. We'd need to do some performance tests".
  - R00tB33rMan: "the current implementation that is now ~3 years old doesn't really showcase real/hard data."
  - Closing: **"This PR isn't entirely worth it — the gains are probably close to none."**
  - **No numbers published.** Their test topology (backend threshold? packet mix?) is unknown. See §9 for
    why "close to none" is plausible only if backends ran with compression off or the traffic was
    dominated by < 2048-byte packets.
- CTD also originated the decompression-attack fixes (commits `3fd2f11b`, `f0bd9314`, `c219ded5`;
  PR #842 mirroring VeloFlame's approach; PR #871 decompressed-bytes/s limiter → upstreamed as Velocity #1786).

### 3.2 Others found (low signal, 2026)

- **Velocity-Opt** (FalseSharing, 2026-09, 1★): README claims libdeflate + "Zero-Copy Packet Slicing:
  Bypasses heap allocations during high-frequency packet pass-through"; no compression passthrough.
- **Conduit** (tame-gg/conduit-native, created 2026-09-21, not Netty, bundles ViaVersion): "Backend Set
  Compression is consumed by Conduit and never forwarded to the client: forwarding it caused 26.2 vanilla
  `DataFormatException: incorrect header check`... Conduit unwraps compressed backend frames and
  re-compresses what it sends each client." Recommends backend `network-compression-threshold=-1`. Its
  perf notes list "Inflater per compressed packet — new Inflater/Deflater on every wrap/unwrap" as a fixed bug.
- VeloFlame (ArkFlame, Velocity-CTD fork, closed source) — packet guard/firewall; no evidence of passthrough.

### 3.3 Relay (wroughtworks/Relay, Java/Netty, created 2026-08-09, 0★) — **shipped, with numbers**

- 1.20.2–1.21.8 proxy with CONFIG-phase switching (same approach as Warp).
- Commit **`08f65084a9`** (2026-08-31) "Forward a frame in the compressed form it arrived in":
  - `CompressionDecoder` keeps the original frame; `MinecraftConnection.relayFrom` (L241-256) writes
    `new Precompressed(original)` if `mine.threshold() <= theirs.threshold()`; `Precompressed extends
    DefaultByteBufHolder` — "A distinct type rather than a flag, because the decision has to survive the
    trip down an outbound pipeline that otherwise sees only ByteBufs and would compress one."
  - **Still fully inflates** every frame (to read the ID); skips only the re-deflate.
  - Author's measurement, real Paper backend, 40 players, A/B in one session: **"deflating again 99.5,
    95.4 µs of proxy CPU per frame; passing through 51.5, 41.7"**; with a compression-level default
    change: **177 µs → ~47 µs per frame**. "compressed forwarding now costs roughly what uncompressed
    forwarding did, while sending a fraction of the bytes."
  - Safety rules worth copying: threshold rule is "a direction, not equality"; **identity-checked
    handover** ("The decoder returns the original only for the exact buffer it produced ... The failure
    mode is a lost optimisation, not a player receiving somebody else's packet"); explicit ownership;
    the CONFIG queued path deliberately does not use it ("a queued frame outlives the decode that
    produced it").
  - Javadoc: deflate is "about ten times the cost of the inflate".
- This is the only **Java** proxy found with a merged, measured implementation — and it directly
  contradicts Velocity-CTD's "close to none".

---


## 4. Non-Java proxies (Go / Rust / C# / C++)

| Project (rev read) | Mode | Inflates every packet? | Re-deflates? | Switching | Forwards backend-compressed frames verbatim? |
|---|---|---|---|---|---|
| **GoLilyPad** (Go, master `d3751e6`, 98★, last push 2024-05) | full | reads the ID **through the inflating reader** (Go flate inflates up to a block / 32 KiB window per Read → for most packets ≈ full inflate) | **No** for unregistered packets | **Yes** | **YES — since 2014** (`ca98b8014f`, 2014-09-03, "Do not compress/decompress packets that we do not care about") |
| **Infrarust v2** (Rust, `v2.0.0-beta.3` @ `1bde1fa`, 2026-09) | intercepted (`client_only`, `offline`) | **Yes, fully** (libdeflate default) | **No** when frame threshold == outgoing threshold and frame is canonical | **Yes** | **YES (skip-recompress only)** since `44c159742d` (2026-07-10) |
| Infrarust v2 | `passthrough` / `zero_copy` (splice, 64 KiB pipe) / `server_only` | No (raw TCP after handshake) | No | No | n/a (L4 relay) |
| Infrarust v2 | `Full` | — | — | — | declared, **not implemented** (falls back to passthrough, `server.rs:567-574`) |
| **Gate** (Go, master `c3c9b5f`) | full | Yes (`compress/zlib`, `decoder.go:168-195`) | **Yes, always** (`encoder.go:140-174`: "payload must not already be compressed") | Yes | **No** — only skips re-*serialization* of unknown packets |
| Gate **Lite** | handshake-only, then `io.Copy` ×2 (splice on Linux) | No | No | **No** ("backend server switching or proxy commands are no longer available in this mode") | n/a |
| Vine (Pumpkin-MC, Rust, `aa7579c`) | full | Yes | Yes | Yes | No |
| Void (C#, modded) | full | Yes | Yes | Yes | No (falls back to raw relay if it can't decrypt) |
| SniffCraft (C++ MITM sniffer) | 1:1 | Yes (to log) | Only rewritten packets | No | Yes, `transmit_original_packet = true` (single threshold by construction) |
| lazymc (Rust) lobby | lobby then raw splice | — | — | handoff only | Requires matching thresholds; hard-codes 256 ("TODO: read this from server.properties"), logs "does not match threshold from server, this may cause errors" |
| Summpot/prism (Rust tunnel, 2026) | L4 tunnel optimizer | **Yes on purpose** | Yes at far end (level 1) | No | **Opposite**: inflates MC frames so a continuous zstd stream (8 MB window, trained dictionaries) gets cross-packet redundancy; caps inflate at 16 MiB, "anti-zipbomb metrics" anchored to wire size (`28e941eb8d`); bug `68e0d2ca0c`: packet ID 0 mistaken for dataLength 0 before Set Compression |
| mc-router, infrared, hopper-rs, Ultraviolet | handshake router + raw relay | No | No | No | n/a |
| scrayosnet/passage (Rust) | auth then 1.20.5+ **Transfer** packet | — | — | — | avoids proxying entirely |

### 4.1 GoLilyPad — the 2014 prior art (verified in source)

- `packet/packet_codec_zlib.go` `Decode`: captures `rawBytes` (dataLength VarInt + zlib body) **before**
  inflating; wraps them in a `ZlibToggleReader`.
- The registry reads the packet-ID VarInt *through* the inflating reader. If the ID maps to the generic
  codec, `packet/minecraft/generic_packet.go` L346-349: `zlibReader.SetRaw(true); packetGeneric.compressed = true`
  → `Bytes` = original compressed body.
- `Encode`: `if raw, ok := packet.(PacketRaw); ok && raw.Raw() { buffer.WriteTo(writer) }` — written
  verbatim, threshold ignored.
- `PacketGeneric.Decompress()` (L50+) is called lazily, only when entity IDs must be swapped, and
  `SwapEntities` returns early when client and server entity IDs are equal → on the first server
  nothing is re-deflated; after a switch only entity-carrying packets are.
- **Weaknesses** (agent's code reading, not runtime-tested): Go's flate `Read` decodes a whole block
  (up to 32 KiB) → the "peek" is ≈ a full inflate; a new zlib reader per packet; on 1.9+ the client is
  told 256 at login, then `SessionOutBridge.EnsureCompression → Session.SetCompression(backendThreshold)`
  silently retargets the proxy's client-side codec to the backend threshold **without telling the
  client** (threshold -1 removes zlib from the client pipeline entirely) — works only because modern
  clients don't validate (§7.1). Early bug `fc5220f297` (2014): "Work around a bug in compress/zlib by
  buffering the data" (short reads broke codecs).

### 4.2 Infrarust v2 — the 2026 state of the art in Rust (verified in source)

- `crates/infrarust_protocol/src/io/frame.rs`: `PacketFrame { id, payload, raw: Option<RawWire{bytes, threshold}> }`;
  `raw_wire(threshold)` returns the original wire bytes (incl. length prefix) **only if the outgoing
  threshold equals the incoming one**.
- `decoder.rs` keeps the zero-copy `Bytes` slice of the whole frame, but only if canonical
  (`is_canonical` VarInts) and threshold-consistent (`should_compress` matches) — normalized otherwise
  (`7dde0bbf89`, 2026-08-23).
- `encoder.rs` `append_frame`: verbatim copy if `raw_wire` matches; else re-encode + re-deflate.
- Tests: `test_raw_forward_is_byte_identical_at_same_threshold`, `test_raw_forward_reencodes_on_threshold_mismatch`, …
- **Threshold policy**: at first login the backend's Set Compression is **mirrored onto the client**
  (`proxy_loop.rs:710-716`) → equal thresholds by construction. After a switch, the client keeps the
  first backend's threshold; if the new backend differs, *every* packet is re-encoded (cliff).
- Play hot path decodes only `CDisconnect` (+ `CCommands` when proxy commands are announced); a plugin
  `RawPacketEvent::Modify` builds a new frame without raw bytes → forced re-encode.
- Their published numbers (`docs/v2/reference/benchmarking.md`): re-encoded compressed 512 B packet
  **≈27 µs (flate2) / 6.3 µs (libdeflater)**; unmodified passthrough **≈120 ns at 512 B, ≈1 µs at
  16 KiB** "because the re-compression is skipped entirely and only the inflate cost remains".
  *Caveat*: 16 KiB inflated in ~1 µs implies a very compressible synthetic payload; my JDK-zlib
  measurement on chunk-like data is ~31 µs for 25 KB (§9).
- Infrarust encrypt vs decrypt (agent): ~58 MB/s encrypt vs ~360 MB/s decrypt for CFB8 — consistent
  with my ~48 MB/s JDK measurement: **encryption is the next wall**.
- v1.6.3 pitfall: hard-coded `enable_compression(256)` on packet 0x03 ignoring the real threshold.

### 4.3 Gate (verified by agent in source)

- Full mode: every compressed frame fully inflated (`decoder.go:168-195`, reused `zlib.Resetter`);
  strict checks both ways (uncompressed frame > threshold = error, `decoder.go:183-187` — stricter than
  vanilla, same trap as Velocity #1527); caps 8 MiB clientbound / 2 MiB serverbound.
- Unknown packets keep their decompressed payload "forwarded as is" → re-deflated by `writeCompressed`.
- Client threshold from Gate config; backend threshold from backend Set Compression; independent.
- No issue/PR/discussion about passthrough. Gate #322: "error decompressing payload: unexpected EOF"
  was actually a re-*encode* bug (brigadier argument property, fixed PR #527) — re-encode bugs
  masquerade as compression errors (a further argument for verbatim forwarding).
- The "mintlify" compression page is a **third-party auto-generated** site (mintlify.wiki), not official:
  "Gate's compression settings only apply to Gate ↔ Client connections. The Gate ↔ Backend compression
  is controlled by backend server settings"; LAN backends: `-1`; remote backends: keep compression.

---

## 5. Backends: what arrives at the proxy (Minestom, Paper, Limbo)

### 5.1 Minestom (master `fcd35c05d9`, 2026-10-05) — compresses once, sends N times

- **The cached object is the full compressed frame.** `CachedPacket` holds a `SoftReference<FramedPacket>`
  computed once (`computeCache`, synchronized) via `PacketWriting.allocateTrimmedPacket(state, packet,
  MinecraftServer.getCompressionThreshold())` → `writeFramedPacket` (outer length + dataLength + zlib).
  `FramedPacket` javadoc: "already framed. (packet id+payload) + optional compression. Can be used if you
  want to send the exact same buffer to multiple clients".
- `PlayerSocketConnection.writePacketSync` copies `FramedPacket`/`CachedPacket`/`BufferedPacket` bytes
  verbatim into the per-connection buffer; encryption is applied in place on that copy.
- **Broadcast paths**: `PacketSendingUtils.sendGroupedPacket` wraps in `CachedPacket` when
  `GROUPED_PACKET` (default on); `PacketViewableUtils.ViewableStorage.append` frames each entity packet
  once per chunk/`Viewable` into a shared buffer flushed at end of tick as `BufferedPacket` ranges
  (entity movement path, `Entity.java:1412-1427`).
- **Chunks**: `DynamicChunk.chunkCache = new CachedPacket(this::createChunkPacket)`; `LightingChunk`
  adds `partialLightCache`; invalidated on block change. One deflate per chunk version for all viewers.
- **Compression**: `NetworkBufferImpl.compress` — pooled JDK `java.util.zip.Deflater`, default level
  (-1 ≈ 6), single `deflate(ByteBuffer)` call. PR #3071 (2026-03-22) fixed dirty pooled deflaters causing
  `ZipDataFormatException` "at random moments" → **Minestom builds before 2026-03-22 could emit corrupt
  frames that a passthrough proxy forwards blindly**. Possible (unverified) edge: `compress()` never
  checks `deflater.finished()`; incompressible payloads near the end of a tight output buffer could be
  truncated.
- **Threshold**: `MinecraftServer.compressionThreshold = 256` by default, `setCompressionThreshold` before
  start; ≤ 0 disables. **No property/flag** for it. Compression is enabled for `Auth.Velocity` too
  (`ConnectionManager.transitionLoginToConfig`). Minestom's demo uses `setCompressionThreshold(0)` —
  **check what PrimeMC runs: at 0, there is nothing to pass through.**
- **Inbound**: `PacketReading.readFramedPacket` does **not** check `dataLength >= threshold` → serverbound
  passthrough to Minestom has no threshold constraint.
- **Wire quirk — critical for Warp**: Minestom writes **3-byte padded VarInts** (`VAR_INT_3`) for both the
  outer length and dataLength, compressed or not (`[L L L][0x80 0x80 0x00][id]` for raw frames).
  Velocity also pads compressed-frame lengths to 21 bits. → Warp's peek must accept non-minimal VarInts,
  and **must not copy Infrarust's `is_canonical` gate** (it would disable passthrough for every Minestom frame).
- **Batching**: `flushSync` packs many frames into a 16 383-byte pooled buffer per write; writer parks
  ~25 ms when idle (`minestom.new-socket-write-lock=false` default) → tick-aligned bursts of many frames
  per TCP read, frames spanning reads.
- Bundles: `BundlePacket` registered but never sent by core.
- Network history: #2324 (2024-08, virtual threads, PacketUtils split), #3209 (2026-06, FFM
  `MemorySegment`), #3281 (2026-07-10, single-flight `CachedPacket` miss: avoids to "multiply packet
  serialization, compression, allocation, and copying work across several threads"), #3354 (2026-09,
  `ServerProperties`). No Minestom issue/PR about libdeflate, zstd, compression level, or proxies.

### 5.2 Paper and forks

- Paper compresses **per connection** in Netty with Velocity natives (§6.1); chunk packets are built per
  player (`PlayerChunkSender.sendChunk`; Anti-Xray makes per-player variants). No compressed-frame
  sharing. Same for Folia, Purpur, Pufferfish, Leaf, Gale, DivineMC, Canvas, SparklyPaper (patch-name
  survey + code search; not every patch body read).
- Niche: ShinoyukiMiyako/Shinoyuki-Slipstream (1★, 2026-06) `BroadcastShare.java` "Serialize-once
  broadcast share … the chunk packet reuses the shared compressed frame (skipping the Deflater)" — unverified.
- → Passthrough with Paper backends saves the **proxy's** deflate; total cluster CPU ≈ unchanged.

### 5.3 LimboAPI / FastPrepareAPI / NanoLimbo (prior art for Warp's own limbo/queue)

- LimboAPI (`bfef5799f2`) + FastPrepareAPI 1.0.13: `PreparedPacket` pre-encodes each packet **per
  protocol version**, deduplicates identical versions (`buf.equals(prevBuf)`), compressing with Velocity's
  own `MinecraftCompressorAndLengthEncoder` (via `MethodHandles`, one per thread, Velocity natives,
  Velocity threshold/level). While a player is in limbo it **replaces Velocity's compression encoder with
  a no-op** and writes prepared frames after `minecraft-encoder` (`ByteBuf::copy` in online mode because
  the cipher works in place, `retainedDuplicate` otherwise); `fixCompressor` restores a fresh encoder on
  exit. "Compatibility mode" (ViaVersion/PacketEvents) disables the trick. LimboFilter pre-compresses
  captcha map packets.
- NanoLimbo: no compression at all; `PacketSnapshot` caches uncompressed bodies per version.
- → Pattern for Warp-originated broadcast/limbo content: pre-compress once per (version, threshold),
  send as the same `Precompressed` type the passthrough path uses.

### 5.4 Which backends can be forwarded verbatim

| Backend | Compressed? | Compressed once for N? | Verbatim to ≥ 1.17.1 client | Verbatim to ≤ 1.17 client |
|---|---|---|---|---|
| Minestom, threshold > 0 (default 256) | yes, padded VarInts | **yes** | yes, any client threshold | only if T_c ≤ Minestom threshold |
| Minestom, threshold ≤ 0 | no | — | forward raw frame (`dataLength=0` only if Warp compresses the client link) | same |
| Paper/vanilla/forks (libdeflate, per connection) | yes, minimal VarInts | no | yes (saves Warp's deflate only) | only if T_c ≤ `network-compression-threshold` |
| NanoLimbo | never | — | needs `dataLength=0` | same |

---

## 6. Paper and the `network-compression-threshold=-1` debate

### 6.1 Paper uses Velocity natives — **confirmed**

- `paper-server/patches/features/0008-Use-Velocity-compression-and-cipher-natives.patch` (author Andrew
  Steinborn, 2021-07-26). Patches vanilla `CompressionDecoder`, `CompressionEncoder`, `CipherDecoder`,
  `CipherEncoder`, `Connection.setupCompression/setEncryptionKey`.
- `Connection.setupCompression`: `Natives.compress.get().create(GlobalConfiguration.get().misc.compressionLevel.or(-1))`
  → libdeflate on Linux x86_64/aarch64 (Java `Deflater`/`Inflater` fallback elsewhere). Logs
  "Paper: Using <variant> compression from Velocity." One compressor **per connection**.
- Encoder `allocateBuffer`: `msg.readableBytes() + 1` (libdeflate.h: well-compressible data never exceeds input − 1).
- Paper config: `misc.compression-level` (paper-global.yml, default "default" = -1).
- Vanilla `validateDecompressed` semantics are preserved: **server side `true`**, client side `false` (see §7.1).

### 6.2 Official docs vs community advice

- **PaperMC docs** (repo `PaperMC/docs`): `server-properties.yml` just says "Setting to a negative disables
  compression"; Velocity's `why-velocity.md` sells libdeflate ("twice as fast as zlib while delivering a
  similar compression ratio"); Velocity `tuning.md` only says host on Linux for natives. **The docs do not
  recommend `-1` on backends.** The `-1` recommendation is astei's GitHub comment (PR #357, 2020) and
  community lore.
- **YouHaveTrouble/minecraft-optimization** (the most cited optimization guide): "If your server is in a
  network with a proxy or on the same machine (with less than 2 ms ping), disabling this (-1) will be
  beneficial, since internal network speeds can usually handle the additional uncompressed traffic."
- **Counter-evidence**: BungeeCord #1504 (2015, "you'll need something like 500Gbit"), Janmm14 in #3238
  ("a network who had very very high bandwidth usage with compression disabled on bukkit").
- **Conduit** (2026) docs require backend `-1`.

### 6.3 Trade-offs people state (and the ones they don't)

| `-1` on backends (status quo advice) | Backend compresses + proxy passthrough |
|---|---|
| + Backend saves deflate CPU (Netty threads, not main thread) | − Backend pays deflate (Paper: per connection; **Minestom: once per broadcast group**) |
| + Proxy saves inflate | + Proxy saves inflate **and** deflate for ~all large packets |
| − Proxy pays deflate for every packet of every player (the #1 hotspot per #594) | + Proxy CPU becomes ~cipher + syscalls |
| − 3-7x more bytes on the backend↔proxy link (chunks); matters cross-host / cross-AZ / k8s overlay networks | + Backend↔proxy bandwidth = client bandwidth |
| − Proxy holds uncompressed bytes in queues (Leymooo on #1742: unflushed uncompressed buffers in Netty's write queue during config) | + Queued/buffered data stays compressed (smaller memory, better under backpressure) |
| + Simple; works with Via/PacketEvents | − Requires an interest-set aware pipeline |

Nobody in the threads states the cluster-level math: with Paper backends, passthrough **moves** deflate
from proxy to backend (sum ≈ unchanged, proxy relieved); with **Minestom** backends that compress a
broadcast once for N viewers, passthrough **reduces total cluster CPU** — the proxy would otherwise
re-deflate N copies.

---

## 7. Pitfalls catalogue

### 7.1 Threshold mismatches ("Badly compressed packet")

Verified from decompiled vanilla 1.21.11 (`CompressionDecoder`, `ClientHandshakePacketListenerImpl`,
`ServerLoginPacketListenerImpl`):

```java
// ServerLoginPacketListenerImpl
connection.setupCompression(server.getCompressionThreshold(), true);    // server validates
// ClientHandshakePacketListenerImpl.handleCompression
connection.setupCompression(packet.getCompressionThreshold(), false);   // client does NOT validate
// CompressionDecoder.decode
if (validateDecompressed) {
  if (uncompressedLength < threshold) throw "Badly compressed packet - size of X is below server threshold of Y";
  if (uncompressedLength > 8388608)  throw "...larger than protocol maximum of 8388608";
}
... inflate into directBuffer(uncompressedLength)  // allocated from the CLAIMED size, before inflating
if (actual != uncompressedLength) throw "Badly compressed packet - actual length ... does not match declared size"; // always
```

- `validateDecompressed` did **not exist up to 1.17** (mappings.dev 1.17 / 1.15.2: `CompressionDecoder(int)`)
  and **appears in 1.17.1** (Forge javadoc 1.17.1/1.18.2: `CompressionDecoder(int, boolean)`; the
  Minestom-research agent confirmed with `javap` on the 1.17.1, 1.19.4, 1.20.2 client jars — `iconst_0`
  before `Connection.setupCompression(IZ)V` — and on 26.3 unobfuscated source; 1.8.9/1.12.2/1.16.5/1.17
  always validate). So clients
  **≤ 1.17 reject compressed frames below their threshold** (and > 2 MiB); clients ≥ 1.17.1 only check
  `actual == declared`. Warp registers 1.7.2 → 26.1, so both regimes matter.
- Uncompressed (`dataLength=0`) frames above threshold are accepted by vanilla on both sides
  (minecraft.wiki: "The vanilla server (but not client) rejects compressed packets smaller than the
  threshold. Uncompressed packets exceeding the threshold, however, are accepted.").
  Velocity #1527 made this stricter and broke Carpet-TIS (#1556).
- Consequences for passthrough (T_b = backend threshold, T_c = threshold Warp announced to the client):

| Direction | Safe to forward compressed frame of claimed size S verbatim iff | Otherwise |
|---|---|---|
| clientbound, client ≥ 1.17.1 | always (S ≤ 8 MiB) | — |
| clientbound, client ≤ 1.17 | S ≥ T_c (guaranteed when T_b ≥ T_c) and S ≤ 2 MiB | inflate, send `dataLength=0` (S < T_c is small by definition) |
| serverbound (any) | S ≥ T_b (guaranteed when T_c ≥ T_b) | inflate, send uncompressed |

  → **T_c == T_b is the only setting where every frame in both directions can pass through for every
  version.** T_c is fixed for the session (Set Compression exists only in LOGIN), but each backend
  announces its own T_b at proxy→backend login and can differ after a switch → per-backend mismatch
  slow path + a startup/connection warning + a metric.
- Prior-art policies: **Infrarust v2** mirrors the first backend's Set Compression threshold onto the
  client (equality by construction; after a switch to a backend with a different T_b, *every* packet is
  re-encoded). **Relay** uses the directional rule `T_client <= T_backend` ("a direction, not
  equality"). **GoLilyPad** silently retargets its client-side codec to the backend threshold without
  telling the client (works only for clients that don't validate). **lazymc** hard-codes 256 and warns.
- PR #1078's encoder already implements the "inflate and send raw" fallback; IvanCord's first attempt
  ("I keep getting time outs", both thresholds 256) shows how easy it is to get the framing wrong.
- Conduit (2026): forwarding the backend's Set Compression to the client broke 26.2 clients
  (`DataFormatException: incorrect header check`) — Set Compression is per-hop, never forward it.

### 7.2 Decompression bombs and memory

- Velocity #1742 (2026): real attacks OOM-killed proxies with tiny frames claiming ~8 MiB; mitigated by
  **decompressed-bytes-per-second** limiter (#1786), not by per-packet ratio limits (#1792 regression).
- Vanilla/Paper allocate the **claimed** size up front (`directBuffer(uncompressedLength)` /
  `preferredBuffer(..., uncompressedLength)`). If Warp passes client frames through without inflating,
  the backend becomes the inflater of untrusted data and allocates attacker-chosen sizes (≤ 8 MiB) per
  frame. → **Keep serverbound on the full-inflate path by default** (serverbound compressed traffic is
  rare and small: book edits, creative inventory, plugin messages — little to gain).
- Accounting can still be done on passthrough frames using the claimed size (CTD #917 does this):
  the endpoint enforces `actual == declared`, so lying only gets the sender kicked.
- **Ratio checks**: deflate cannot exceed ~**1032:1** ("The theoretical limit for the zlib format is
  1032:1", zlib.net/zlib_tech.html; practical ~1030.3:1). So `claimed > compressedLen * 1032 + ε` is a
  **provably false-positive-free**, allocation-free pre-check usable even on passthrough frames. Any
  lower ratio risks false positives: Velocity's 64 broke books (138.8:1 observed), Tor's 25x broke zstd
  consensus documents (tor#40739), Envoy's default 100. Warp's current `DEFAULT_MAX_COMPRESSION_RATIO =
  1024` is *slightly below* the theoretical max → should be 1032 (plus header slack) to be provably safe.
- Peek must be bounded by **input consumed** (an all-empty-stored-blocks or huge-dynamic-header stream
  makes "inflate 5 bytes" consume the whole frame).

### 7.3 ViaVersion / ViaBackwards / packet-library plugins

- ViaVersion (Velocity platform) inserts `ViaDecodeHandler` (`MessageToMessageDecoder<ByteBuf>`) between
  the compression decoder and `minecraft-decoder`, and re-positions itself on `COMPRESSION_ENABLED`
  (`VelocityDecodeHandler`). PacketEvents does the same (`ServerConnectionInitializer` L35-36) and even
  copies every packet (`ctx.alloc().buffer().writeBytes(byteBuf)`).
- A passthrough object (non-ByteBuf) silently skips them → wrong-version packets reach the client.
  Janmm14 (#3240) and IvanCord both acknowledged breaking Via-on-proxy.
- When client version ≠ backend version, **every** packet must be inflated + translated + re-deflated
  (Kafka's "down-conversion" cliff: 20% → 100% CPU). Passthrough applies only when versions match
  (TheMode #594: "packets not to be re-compressed if you are using the same version as the backend and
  ViaVersion is installed").
- Warp's architecture rule (plugins never see the Netty pipeline) is the structural fix: translation
  and packet listeners declare interest; the codec decides.

### 7.4 Packet bundling

- Bundle delimiter (PLAY, 1.19.4+) is a 1-byte, never-compressed packet. As long as the ID of every
  frame is known (peeked), the proxy can still track "inside bundle" and avoid injecting its own packets
  mid-bundle. No conflict with passthrough. (Velocity has `BundleDelimiterPacket` registered.)

### 7.5 Encryption interplay

- Order is `cipher(frameLength + [dataLength + zlib(...)])`; encryption is per hop (AES/CFB8, key per
  connection). Passthrough reuses the compressed bytes, not the ciphertext — the proxy must decrypt
  (to find frame boundaries) and re-encrypt anyway. Zero-copy/sendfile/kTLS are out (CFB8 is not a
  kTLS cipher; per-hop keys).
- **[local]** JDK `AES/CFB8/NoPadding` measured at **~48 MB/s** (3771-byte buffer, Ryzen 5 5500, JDK 21):
  encrypting one compressed 25 KB chunk (3.7 KB) costs **~78 µs** — once deflate is gone, **CFB8 becomes
  the dominant clientbound cost**. Warp's `jni/Natives.isNativeCryptoAvailable()` is still a TODO;
  Velocity/Paper use OpenSSL natives for exactly this reason. Passthrough and native CFB8 should ship
  together to realize the gain.

### 7.6 Other

- **libdeflate cannot stream** (astei #357; libdeflate README "There is currently no support for
  streaming") → partial inflate needs zlib/zlib-ng or a custom decoder. #1078/CTD keep a *second*
  per-connection Java `Inflater` (native z_stream + 32 KiB window lazily) — memory per connection.
- **Modded compression** (ZstdMc, zstdnet, Packet Diet, Hassium — 2025-26 mods replacing zlib with
  zstd, some with cross-packet batching): any inflating proxy breaks them; the peek will fail the zlib
  header check (CMF/FLG % 31) → fail loudly with a clear message.
- **Entity-ID rewriting** (BungeeCord EntityMap) was what made Bungee's patch big: every rewritten
  packet must drop its compressed copy. Warp's 1.20.2+ switching through CONFIG avoids entity rewriting,
  so the "modified" set is tiny. For older-version switching (if ever done via Respawn tricks), the
  rewritten IDs must be in the interest set.
- **Metrics blind spot**: without inflating, actual sizes are unknown; claimed size is trustworthy for
  accounting only because endpoints verify `actual == declared`.
- **Velocity-CTD's "close to none"**: a negative data point without numbers. Plausible explanations
  (unverified): backends at `-1` (nothing to pass through), traffic dominated by < 2048-byte packets
  (excluded by their carve-out, and re-deflated anyway), or Java `Inflater` JNI overhead on the
  partial-inflate path.

---

## 8. Outside Minecraft — transferable designs

| System | Mechanism | When it decompresses / recompresses | Transferable principle |
|---|---|---|---|
| **Kafka** (design doc "End-to-end Batch Compression"; topic `compression.type=producer`: "retain the original compression codec set by the producer") | Batch header (offsets, codec, CRC over *compressed* bytes) lives outside the compressed records. KIP-31 (relative offsets): "This KIP is trying to avoid server side recompression"; KIP-32: wrapper timestamp "to avoid recompression penalty". | `LogValidator`: always iterates (inflates) to validate, but **recompresses only if** (1) source ≠ target codec, (2) magic conversion needed, (3) target magic V0. KIP-390 (3.8): "If the producer used a different compression level, records are not recompressed." | One explicit, cheap per-frame passthrough rule; mutate only out-of-band metadata; **never recompress to normalize level/implementation**. |
| Kafka down-conversion | Old consumers force broker-side conversion | 0.10/0.11 upgrade notes: "CPU utilization going from 20% before to 100% after"; KIP-283 / KAFKA-6927: OOM → chunked lazy conversion (16 kB); **removed in Kafka 4.0 (KIP-896)** | Protocol translation (Via) = down-conversion: a **CPU cliff** — measure it, bound its memory, surface it. |
| Kafka KIP-712 "Shallow Mirroring" (Under Discussion) | Mirror without inflating | CPU "dropped from 50% to 15%"; decompression caused "2x-10x memory explosion"; recompression up to 40% CPU | Shallow relay wins are large *and* memory-relevant. |
| Kafka metrics | `MessageConversionsPerSec`, `MessageConversionsTimeMs`, `TemporaryMemoryBytes` | — | Ship slow-path metrics from day 1. |
| **Kroxylicious** (Java/Netty Kafka proxy) — verified in source | `DecodePredicate.shouldDecodeRequest/Response(apiKey, apiVersion)` = OR over filters' `shouldHandle*`; undecoded RPCs travel as `OpaqueRequestFrame` | Decodes only RPC types some filter subscribed to (per connection) | **Exactly Warp's model**: per-connection interest set over (state, version, packetId); opaque frame type otherwise. |
| **Envoy** compressor/decompressor | Compressor skips if `content-encoding` already present; decompressor is opt-in, "pass-through" when disabled; `x-envoy-compression-status` header explains why not | Only when a filter needs the body | Typed "already-compressed" marker so nothing double-compresses; expose a per-ID **"why not passthrough" reason**. Bomb defences: `max_inflate_ratio` (default 100), CVE-2022-29225 (accumulating buffers), CVE-2026-48044 (ratio check at wrong loop depth). |
| **HAProxy** | "If backend servers support HTTP compression, these directives will be no-op"; on `compression offload`: "strongly recommended not to do this because ... all the compression work will be done on the single point where HAProxy is located." | Never recompresses already-encoded responses | `-1` on backends is HAProxy's "offload" anti-pattern: centralizes all deflate on the proxy. |
| **NGINX** `gzip_static always` + `gunzip` | Compressed form is canonical; decompress only for clients that can't take gzip | Per-client capability | Compressed is canonical; inflate is the exception path (e.g. clients ≤ 1.17 with threshold mismatch, uncompressed client links, Via). |
| **Cloudflare / Fastly** | CF keeps origin encoding unless a content-altering feature is on (then "decompress the response and compress it again"); Fastly static (pre-cache) compression reused for all | One "innocent" feature forces transcode of the whole flow | A single plugin listener on a hot ID (chunks, entity movement) must be **visible** (warn + metric). |
| **gRPC** | 5-byte prefix: compressed-flag + length; "Compression contexts are NOT maintained over message boundaries"; per-message "MUST be sent uncompressed" switch (anti-CRIME) | grpc-go checks wire size before inflating, then `io.LimitReader(limit+1)`; PR #3048 presized from attacker-controlled ISIZE (removed by PR #8830, 2026) | MC = gRPC model (fresh context per frame) → frame-level forwarding/reordering is safe. **Never presize from claimed size of an untrusted peer.** |
| **WebSocket permessage-deflate** (RFC 7692) | Context takeover across messages unless `*_no_context_takeover`; window bits per hop | Proxies that inspect usually strip the extension | Passthrough exists only because MC has no context takeover — **never add cross-packet compression** (would also open CRIME, RFC 7692 §8). |
| HPACK/QPACK (RFC 7541 §7.1.3) | Stateful per-hop tables; "could inadvertently merge compression contexts" | Always re-encode | Don't merge different principals' data into one compressed unit. |
| MySQL compressed protocol / ProxySQL | 7-byte header with "length before compression" (0 = raw; < 50 bytes not compressed) — near-identical to MC | But one compressed frame may hold "a piece of a MySQL Packet to several" → no peek possible | **One frame = one packet** is the property that makes MC peekable; never coalesce frames. |
| memcached | Compression flag lives in opaque `flags` outside the value | Proxies forward blind | Metadata outside the payload = free routing (cf. caoli5288's tunnel header, §10). |
| OpenSSH 7.4 | Removed pre-auth compression: "clearly a bad idea in terms of both cryptography ... and attack surface" | — | Minimize inflate surface before authentication (LOGIN state). |
| Tor | `MAX_UNCOMPRESSION_FACTOR 25` → false positives on zstd consensus docs (tor#40739, 2025) → floor raised 64 KiB → 5 MB | — | Ratio limits break legit traffic; prefer absolute caps + per-connection inflate budget. |
| Video transmux vs transcode | "transcode once ... then transmux on the fly" | — | Peek = demux; full re-deflate = transcode; compress once, fan out. |
| Linux kTLS / sendfile | Kafka: sendfile disabled with TLS | — | AES-CFB8 per hop rules out zero-copy; passthrough saves zlib, not copies — optimize cipher throughput next. |

(Uncertain per agent: whether grpc-go raw-codec proxies still inflate; ProxySQL per-hop full decompress is inferred.)

---

## 9. Local measurements [local]

Quick single-file benchmarks run during this research. They are **not JMH**: single process, 6 rounds
with the first 2 discarded. Hardware: AMD Ryzen 5 5500 under WSL2, OpenJDK 21.0.12, JDK-bundled zlib.
Payloads are **synthetic**: "text" is JSON-ish component text; "chunk-like" is heightmaps, paletted
4-bit sections with runs, and light arrays. Treat the numbers as orders of magnitude.

| payload | raw B | zlib B | deflate L6 | full inflate | `Inflater` peek 5 B | pure-Java peek 5 B | pure-Java peek 1 B |
|---|---|---|---|---|---|---|---|
| text 300 B | 300 | 145 | 6.4 µs | 2.3 µs | 1.5 µs | 1.5 µs | 1.4 µs |
| text 2 KB | 2048 | 536 | 22.8 µs | 4.5 µs | 1.6 µs | 1.8 µs | 1.5 µs |
| chunk-like (8 sections) | 24 980 | 3 771 | 537 µs | 30.6 µs | 2.7 µs | 2.8 µs | 2.8 µs |
| chunk-like (24 sections) | 74 322 | 11 048 | 1 788 µs | 115 µs | 3.1 µs | 3.0 µs | 3.3 µs |

What the numbers say:

1. **Deflate costs 3x inflate at 300 B and 16-18x at chunk sizes** (zlib level 6). TheMode's "x4-5" and
   Relay's "about ten times" both fall inside that range. lzbench (silesia, EPYC 9555P) agrees:
   - libdeflate -6: 82 MB/s compress vs 820 MB/s decompress;
   - zlib -6: 26.8 vs 314 MB/s;
   - zlib-ng -6: 62.1 vs 498 MB/s.
2. **Peeking costs a flat 1.4–3.3 µs.** Most of that is parsing the dynamic-Huffman header. Two
   consequences:
   - Above about 2 KB, peeking costs ≤ 1% of inflate plus deflate.
   - At about 300 B, peeking costs about 65% of a full inflate. For small frames, a full inflate that
     keeps the original bytes is nearly free. Skipping the re-deflate is still the main saving.
3. The pure-Java peeker is a puff.c-style canonical decoder. It is about 150 lines, allocates nothing,
   and passed 20 000 randomized payloads at levels 0–9. It is about as fast as JNI `Inflater`, but needs
   no second native z_stream per connection and works next to libdeflate. The current version builds
   its tables naively, so table-driven decoding should make it faster.
4. **JDK AES/CFB8 encryption runs at about 48 MB/s**, roughly 78 µs per 3.7 KB compressed chunk. With
   deflate gone, **encryption becomes the largest clientbound cost**. Infrarust reports about 58 MB/s
   encrypt vs 360 MB/s decrypt.
5. **The stored-block packet-ID prefix works with stock zlib.** The layout is
   `[78 9C][00 LEN NLEN id-bytes][raw deflate of rest][adler32(all)]`. Both JDK zlib and system zlib
   (Python) decode it, verified on 5 000 packets. The ID sits at a fixed offset 7, so peeking costs
   **zero inflate work**. Size overhead is **+5.4 B per packet (0.043%)** compared with plain deflate.

What this implies for Velocity-CTD's "close to none": with a backend that compresses, passthrough on a
25 KB chunk replaces about 570 µs of zlib work with about 3 µs. The gain can only be near zero if few
compressed frames reached the proxy, for example backends at `-1`, or only the 2048+ carve-out
applied. Relay's real-world A/B (95-99 → 42-52 µs per frame) is consistent with this analysis. This
explanation is **inferred, not verified**.

---

## 10. Innovations to adopt

| # | Innovation | Source / prior art | Priority for Warp |
|---|---|---|---|
| 1 | Keep the original compressed frame and forward it verbatim when the packet is not modified. Use a distinct `Precompressed` message type that the encoder must not compress. | GoLilyPad 2014 `ca98b801`; IvanCord/BungeeCord #3240; Infrarust `RawWire`; Relay `Precompressed` (`08f65084`); Envoy/HAProxy "already encoded" | **P0** |
| 2 | **Separate "inflate?" from "re-deflate?"**. Re-deflate only when the packet was modified or thresholds are incompatible, never because of its size. #1078 and CTD mixed the two up with `decompression-threshold=2048`, which still re-deflated every 256–2047 B packet. | Lesson from Velocity #1078 / CTD #917 vs Relay / Infrarust | **P0** |
| 3 | A per-connection **decode predicate / interest set** over (state, protocol version, packet ID). Its inputs are Warp's registered packets, plugin listeners, and a "translation active" flag. Undecoded frames are opaque. | Kroxylicious `DecodePredicate` / `OpaqueRequestFrame`; TheMode #594 ("force others to explicitly register which packets they are interested in") | **P0** |
| 4 | **Threshold policy.** Announce T_c = T_b of the default backend (mirrored as Infrarust does), or the network minimum, so T_c ≤ every T_b. Use a version-aware rule: clientbound to clients ≥ 1.17.1 can always pass through; ≤ 1.17 needs S ≥ T_c; serverbound needs S ≥ T_b. Fall back per frame by inflating and sending `dataLength=0` (#1078 encoder). Warn when a backend's T_b differs. | Infrarust mirroring; Relay "≤" rule; vanilla `validateDecompressed` (1.17.1+) | **P0** |
| 5 | **Clientbound only by default.** Serverbound stays on full inflate, because it is untrusted, rare, and small, and backends allocate the claimed size up front. Offer an opt-in serverbound mode with header checks and claimed-size accounting. | Velocity #1742/#1786; vanilla `directBuffer(uncompressedLength)`; OpenSSH pre-auth lesson | **P0** |
| 6 | Accept **non-canonical (padded) VarInts** in frame headers and forward them unchanged. | Minestom `VAR_INT_3`; Velocity 21-bit lengths; anti-pattern: Infrarust `is_canonical` | **P0** (PrimeMC runs Minestom) |
| 7 | **Pure-Java allocation-free ID peeker** (puff.c-style) with event-loop-local scratch tables, input-bounded, and a fast path when the first block is stored. It avoids JNI and a second per-connection native inflater, and works with libdeflate. | New. #1078/CTD use a Java `Inflater` per connection; libdeflate cannot stream (astei #357) | **P1** (P0 if natives = libdeflate) |
| 8 | **Hybrid small/large path.** Below about 512 B claimed: full inflate (≈ peek cost) and keep the original. Above it: peek only. Neither path re-deflates unmodified frames. | New; §9 numbers | **P1** |
| 9 | **Forward frames byte-for-byte, length prefix included** (no re-framing). Splice contiguous runs of passthrough frames from one read into a single retained slice for the cipher and the write. | New. Kafka batch forwarding; Infrarust keeps the prefix | **P1** |
| 10 | Limits that hold under passthrough: account the **claimed** size in a decompressed-bytes-per-second budget, plus a **provable 1032:1 ratio pre-check** (claimed > compressed × 1032 + slack ⇒ the claim is false). No lower ratio caps. | Velocity #1786 (bytes/s), #1792 (64:1 broke books), zlib_tech "1032:1"; Tor #40739 | **P0** |
| 11 | Identity-checked buffer handover and explicit ownership, so a pipeline change can only lose the optimisation, never send the wrong bytes. | Relay `takeOriginalFor` | **P1** |
| 12 | **Observability.** Track the passthrough ratio per packet ID, slow-path reasons (modified, threshold, listener, translation, client version), peek failures, and claimed vs wire bytes. Warn when a listener lands on a hot ID. | Kafka conversion metrics; Envoy `x-envoy-compression-status`; Cloudflare "innocent feature" lesson | **P1** |
| 13 | **Native AES/CFB8** (OpenSSL) shipped alongside passthrough. Otherwise CFB8 takes over as the clientbound bottleneck at about 48 MB/s on the JDK. | Velocity/Paper natives; §9; Infrarust numbers | **P0 companion** |
| 14 | **Event-loop-local compressors**, O(threads) instead of O(connections), plus lazy deflater creation. This works because MC zlib streams are independent per packet. | New. Velocity, Paper and Minestom are per connection or pooled; gRPC "new context per message" | **P2** |
| 15 | **Warp-aware backend framing** for Minestom/PrimeMC: put the packet ID in a stored block at the start of the zlib stream. Vanilla-compatible (verified), +5.4 B per packet, zero-cost peek. Could ship as a Minestom extension or upstream flag. | New. Generalizes caoli5288's private tunnel header (1000% → 200% CPU, unverified); astei: "I would rather have Mojang pull out the packet ID" | **P2** (P1 if PrimeMC traffic is mostly small compressed packets) |
| 16 | Keep queued and buffered frames **compressed** during switching and backpressure. Gives a smaller memory footprint. | Leymooo on #1742 (uncompressed buffers in Netty write queue); Kafka KIP-712 "2x-10x memory explosion" | **P1** |
| 17 | Passthrough in the **CONFIG phase** too (registry data and tags are large). | Warp already blind-forwards CONFIG (#25/#26); Minestom compresses CONFIG | **P1** |
| 18 | Optional **sampled validation mode** that fully inflates 1/N passthrough frames to catch corrupt backend output, such as Minestom before #3071. | New; Gate #322 shows re-encode bugs look like zlib errors | **P2** |
| 19 | Pre-compressed Warp-originated content (limbo, queue, broadcasts), built once per (version, threshold) and sent as `Precompressed`. | LimboAPI/FastPrepareAPI `PreparedPacket`; Minestom `CachedPacket`; Fastly static compression | **P2** |
| 20 | Never be stricter than vanilla: accept uncompressed frames above the threshold, and don't use small ratio caps. | Velocity #1527 → #1556; #1792; Gate `decoder.go:183-187` | **P0** |
| 21 | Fail loudly on non-zlib payloads (CMF/FLG check). Modded zstd stacks cannot sit behind any inflating proxy. | ZstdMc, zstdnet, Packet Diet (2025-26 mods) | **P2** |
| 22 | (Optional) Trim padded VarInt headers without touching zlib (saves about 4 B per small Minestom frame). | Minestom quirk | **P3** |

---

## 11. Suggested shape for Warp (synthesis, not a spec)

```
backend → [cipher?] → FrameDecoder (retain whole frame incl. length prefix, padded varints OK)
        → CompressionDecoder:
             dataLength==0           → Raw(id, slice)
             claimed < SMALL (~512)  → full inflate → Decoded(id, payload, original)   // ≈ peek cost
             else                    → peek(id) (pure-Java, bounded) → Opaque(id, original, claimed)
             always: claimed-size budget + 1032:1 pre-check + caps
        → DecodePredicate(state, version, id, connFlags) ? full decode : pass object through
client encoder:
        Opaque/Decoded-unmodified && thresholdCompatible(T_c, T_b, clientVersion, S) → write original verbatim
        else → (inflate if needed) → (re)encode → deflate only if S ≥ T_c
serverbound: full inflate by default (security), same predicate for decode
```

Expected effect, per §9 and Relay: proxy zlib CPU for clientbound PLAY/CONFIG traffic drops by
roughly 90-99%. Once that happens, AES/CFB8, syscalls and allocation become the cost profile, so native
crypto is the companion feature.

---

## 12. Uncertainties and open questions

- **Velocity-CTD "gains close to none"** (#663, #917): no data or topology was published, so the cause
  is unknown. Relay's numbers are the author's own measurements on a 0★ project. Infrarust's numbers
  use synthetic payloads (1 µs to inflate 16 KiB implies very compressible data). My numbers are
  synthetic and not JMH. **A Warp-native JMH benchmark on captured real traffic (Minestom and Paper)
  is the first thing to build.**
- The client-validation boundary is **1.17.1**. Evidence: mappings.dev 1.17 has `CompressionDecoder(int)`
  while Forge javadoc 1.17.1 has `(int, boolean)`, and the agent ran `javap` on the 1.17.1 client jar
  (`iconst_0`). 1.18–1.21.10 were not all checked; sampled versions (1.19.4, 1.20.2, 1.21.11, 26.3)
  agree. Behaviour of modded clients and of ViaVersion/ViaProxy validation: **not verified**.
- GoLilyPad's real inflate cost and its threshold retargeting were read from source, not run.
- FlameCord is now closed source, so it is unknown whether it adopted the #3240 patch. NullCordX/XCord
  are closed source; no evidence either way.
- caoli5288's "1000% → 200% CPU" tunnel is a private, unverifiable claim.
- The possible truncation in Minestom's `compress()` (no `finished()` check) was inferred from code,
  not reproduced.
- The PrimeMC Minestom compression threshold is unknown. **If PrimeMC runs threshold 0, passthrough
  gains nothing until backends compress.**
- Whether passthrough plus backend compression beats `-1` plus proxy compression across the whole
  cluster depends on the backend type. It wins with Minestom (compress once); with Paper it moves the
  CPU cost rather than removing it.

---

## 13. Corrections to existing Warp research docs

- `docs/research/performance-analysis.md` L41 attributes "4-5x" to "5zig". The author of Velocity #594
  is **TheMode**. The quote is "Compression is still about x4-5 more expensive", and it argues that
  *skipping decompression* is a niche win compared with skipping compression.
- `consolidated-proxy-research.md` L25 says "4-5x gain possible with passthrough (maintainers'
  estimate)". That is a misreading: the figure is the deflate/inflate cost ratio, not a gain estimate.
  §9 measures 3x at 300 B and about 17x at chunk sizes.
- `CompressionDecoder.DEFAULT_MAX_COMPRESSION_RATIO = 1024` sits slightly *below* deflate's theoretical
  maximum of 1032:1. Using 1032 plus header slack would make it provably free of false positives. Its
  Javadoc also says "No upstream proxy validates this (Velocity #1742)", which is outdated. Velocity
  added a 64:1 limiter in `9890c429` (2026-04-08) and reverted it in `ab8333d6` (2026-05-14) because it
  broke vanilla books (#1792).

---

## 14. Key sources

- Velocity: #594, #357, #1078, #491/#493, #974, #1527, #1556, #1672, #1742, #1743, #1786, #1792; commits
  `9890c429`, `ab8333d6`; `MinecraftCompressDecoder.java` (dev/3.0.0).
- BungeeCord: #1504, #2445, #3238, #3240, #3962; IvanCord `dfdc7d70`.
- Velocity-CTD: #663, #917, #842, #871. Relay: `08f65084a9`. Conduit README.
- GoLilyPad `ca98b8014f`, `fc5220f297`; Infrarust `44c159742d`, `7dde0bbf89`,
  `docs/v2/reference/benchmarking.md`; Gate `pkg/edition/java/proto/codec/{decoder,encoder}.go`, `lite/forward.go`.
- Minestom `CachedPacket`, `PacketWriting`, `NetworkBufferImpl`, `PlayerSocketConnection`, PRs #2324,
  #3071, #3209, #3281, #3354. LimboAPI `LimboImpl`, FastPrepareAPI `PreparedPacket`.
- Paper `0008-Use-Velocity-compression-and-cipher-natives.patch`; PaperMC/docs; YouHaveTrouble
  minecraft-optimization.
- Vanilla 1.21.11 decompiled `CompressionDecoder`, `ClientHandshakePacketListenerImpl`,
  `ServerLoginPacketListenerImpl`; minecraft.wiki Java Edition protocol/Packets.
- Kafka design doc, KIP-31/32/98/283/390/712/896, KAFKA-6927/8106; Kroxylicious `DecodePredicate`; Envoy
  compressor/decompressor docs, CVE-2022-29225, CVE-2026-48044; HAProxy configuration.txt; NGINX gunzip;
  Cloudflare/Fastly compression docs; gRPC PROTOCOL-HTTP2.md and compression.md; RFC 7692, 7541,
  9204; zlib.net/zlib_tech.html; lzbench README; Tor compress.c (#40739).
