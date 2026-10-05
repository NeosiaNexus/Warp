# Compression frame validation across Minecraft implementations (1.8 → 26.3)

Research for Warp **compression passthrough** (relaying `[PacketLength][DataLength][payload]` frames verbatim,
peeking only the packet id). Date: 2026-10-05. Latest release: 26.3 (protocol 777); latest snapshot checked: 26.4-snapshot-2.

Legend: **[V]** = verified from source/bytecode by this research, **[I]** = inference, **[?]** = unverified/uncertain.

> **Research cycle #5 — outcome.** Warp applies rule R1 (canonical frames only) and R2 (header-only
> decision) in `FrameForwarder`, R3's normalisations through the outbound encoders, R6–R7 in
> `FrameDecompressor` / `FrameDecoder`. Open: R8 (consume a 1.8 backend's PLAY-state Set Compression).


## TL;DR

1. **Vanilla clients ≤ 1.17 (protocol ≤ 755)** reject compressed frames whose DataLength < their threshold and > 2 MiB.
   **Since 1.17.1 (756)** the client passes `validateDecompressed=false` → no threshold/size checks on clientbound frames.
   **Vanilla servers (every version)** reject DataLength < threshold and > 2 MiB (≤1.17) / 8 MiB (≥1.17.1). [V, bytecode]
2. **No vanilla code, in any version or side, checks an uncompressed (DataLength=0) frame against the threshold.** [V]
3. **Krypton ≥ 0.2.9 (MC 1.21.5+) and Krypton Reno (all branches, incl. 1.20.1/1.21.1) clients DO reject DataLength=0 frames with payload ≥ threshold, unconditionally**
   (Velocity-derived check). Velocity itself does too. [V]
4. 1.20.2+ (764+) both sides reject inflated size < declared ("does not match declared size"). [V]
5. SetCompression is LOGIN-only since 1.9; 1.8 also had PLAY 0x46. 1.8 clients crash (ClassCastException) on a 2nd SetCompression ≥ 0. [V]
6. ⇒ Only **T_c == T_b** is universally passthrough-safe. Otherwise normalise per frame using header-only checks (§8).


---

## 0. Method (vanilla)

Vanilla facts below were verified **directly from Mojang's official client jars** (downloaded from
`piston-meta.mojang.com` version manifest), decompiled with Vineflower 1.11.1, and cross-checked with `javap -c`
bytecode. Obfuscated names were resolved with Mojang's official `client_mappings` (1.14.4+); 26.x jars ship
unobfuscated. The client jar contains both the client and the (integrated) server networking code, so client
and server call sites come from the same jar.

Versions decompiled: 1.8.9, 1.9.4, 1.12.2, 1.13.2, 1.16.5, 1.17, 1.17.1, 1.18, 1.18.2, 1.19, 1.19.2, 1.19.3,
1.19.4, 1.20, 1.20.1, 1.20.2, 1.20.4, 1.20.5, 1.21, 1.21.4, 1.21.5, 1.21.6, 1.21.8, 1.21.9, 1.21.10, 1.21.11,
26.1, 26.2, 26.3, 26.4-snapshot-2.

Client jar SHA-1 prefixes (reproducibility): 1.8.9 `3870888a6c3d`, 1.9.4 `4a61c873be90`, 1.12.2 `0f275bc1547d`,
1.13.2 `30bfe37a8db4`, 1.16.5 `37fd3c903861`, 1.17 `1cf89c77ed5e`, 1.17.1 `8d9b65467c79`, 1.20.1 `0c3ec587af28`,
1.20.2 `82d1974e75fc`, 1.20.4 `fd19469fed4a`, 1.20.5 `c6b92b2374a6`, 1.21.8 `a19d9badbea9`, 1.21.9 `ce92fd8d1b24`,
1.21.11 `ba2df812c2d1`, 26.1 `191771837687`, 26.2 `2dc72797acbc`, 26.3 `e877b6a07acd`.

Obfuscated class names of `CompressionDecoder` (for anyone re-checking): 1.8.9 `ei` (MCP `NettyCompressionDecoder`),
1.9.4 `ek`, 1.12.2 `gu`, 1.13.2 `hu`, 1.16.5 `nb`, 1.17/1.17.1 `oc`, 1.18 `pj`, 1.18.2 `pu`, 1.19/1.19.2 `qt`,
1.19.3 `rz`, 1.19.4 `so`, 1.20/1.20.1 `sb`, 1.20.2 `sk`, 1.20.4 `ue`, 1.20.5 `wi`, 1.21 `vr`, 1.21.4 `vg`,
1.21.5 `vt`, 1.21.6/1.21.8 `wb`, 1.21.9/1.21.10 `wi`, 1.21.11 `ws`, 26.x `net.minecraft.network.CompressionDecoder`.

Protocol numbers read from each jar's `version.json`: 1.16.5=754, 1.17=755, **1.17.1=756**, 1.18=757, 1.18.2=758,
1.19=759, 1.19.2=760, 1.19.3=761, 1.19.4=762, 1.20/1.20.1=763, **1.20.2=764**, 1.20.4=765, 1.20.5=766, 1.21=767,
1.21.4=769, 1.21.5=770, 1.21.6=771, 1.21.8=772, **1.21.9/1.21.10=773**, 1.21.11=774, 26.1=775, 26.2=776, 26.3=777.
(1.8.x = 47 per protocol docs.)

---

## 1. Vanilla decoder validation history

### 1.1 The code, per era [V]

**1.8.9 → 1.17 (protocol 47 → 755)** — single constructor `CompressionDecoder(int threshold)`, validation is
unconditional, so it runs on **both client and server**:

```java
// 1.8.9 `ei` (identical logic in 1.9.4, 1.12.2, 1.13.2, 1.16.5, 1.17); local names restored by hand
protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
   if (in.readableBytes() != 0) {
      PacketBuffer buf = new PacketBuffer(in);
      int dataLength = buf.readVarInt();
      if (dataLength == 0) {
         out.add(buf.readBytes(buf.readableBytes()));            // uncompressed: NO size check at all
      } else {
         if (dataLength < this.threshold)
            throw new DecoderException("Badly compressed packet - size of " + dataLength + " is below server threshold of " + this.threshold);
         if (dataLength > 2097152)
            throw new DecoderException("Badly compressed packet - size of " + dataLength + " is larger than protocol maximum of " + 2097152);
         byte[] compressed = new byte[buf.readableBytes()]; buf.readBytes(compressed);
         this.inflater.setInput(compressed);
         byte[] result = new byte[dataLength];
         this.inflater.inflate(result);                          // NO check of actual inflated length
         out.add(Unpooled.wrappedBuffer(result));
         this.inflater.reset();
      }
   }
}
```

**1.17.1 → 1.20.1 (protocol 756 → 763)** — `validateDecompressed` flag introduced (Mojang name, field `e`), max raised
to 8 MiB, both checks moved under the flag:

```java
// 1.17.1 `oc`  (mappings: int MAXIMUM_COMPRESSED_LENGTH -> a = 2097152; int MAXIMUM_UNCOMPRESSED_LENGTH -> b = 8388608;
//               boolean validateDecompressed -> e; void setThreshold(int,boolean) -> a)
public CompressionDecoder(int threshold, boolean validateDecompressed) { ... }
...
} else {
   if (this.validateDecompressed) {
      if (dataLength < this.threshold)  throw new DecoderException("Badly compressed packet - size of " + dataLength + " is below server threshold of " + this.threshold);
      if (dataLength > 8388608)         throw new DecoderException("Badly compressed packet - size of " + dataLength + " is larger than protocol maximum of 8388608");
   }
   byte[] compressed = ...; inflater.setInput(compressed);
   byte[] result = new byte[dataLength]; inflater.inflate(result);   // still no length-match check
   ...
}
```

**1.20.2 → 26.3 / 26.4-snapshot-2 (protocol 764 → 777)** — NIO rewrite + an **unconditional** length-match check
(runs on both sides):

```java
// 26.3 net.minecraft.network.CompressionDecoder (unobfuscated; byte-identical decompile in 26.1, 26.2, 26.4-snapshot-2)
public static final int MAXIMUM_COMPRESSED_LENGTH = 2097152;
public static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8388608;

protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
   int uncompressedLength = VarInt.read(in);
   if (uncompressedLength == 0) {
      out.add(in.readBytes(in.readableBytes()));
   } else {
      if (this.validateDecompressed) {
         if (uncompressedLength < this.threshold) throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is below server threshold of " + this.threshold);
         if (uncompressedLength > 8388608)        throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is larger than protocol maximum of 8388608");
      }
      this.setupInflaterInput(in);
      ByteBuf output = this.inflate(ctx, uncompressedLength);
      this.inflater.reset();
      out.add(output);
   }
}

private ByteBuf inflate(ChannelHandlerContext ctx, int uncompressedLength) throws DataFormatException {
   ByteBuf output = ctx.alloc().directBuffer(uncompressedLength);
   ByteBuffer nioBuffer = output.internalNioBuffer(0, uncompressedLength);
   int pos = nioBuffer.position();
   this.inflater.inflate(nioBuffer);
   int actualUncompressedLength = nioBuffer.position() - pos;
   if (actualUncompressedLength != uncompressedLength)
      throw new DecoderException("Badly compressed packet - actual length of uncompressed payload " + actualUncompressedLength + " is does not match declared size " + uncompressedLength);
   ...
}
```

Minor change in **1.21.9 (773)**: the `if (in.readableBytes() != 0)` guard was removed from the decoder, and
`Varint21FrameDecoder` gained `throw new CorruptedFrameException("Frame length cannot be zero")` (absent in 1.21.8
and earlier). [V]

### 1.2 Who validates — call sites [V]

Verified by bytecode (`iconst_0` / `iconst_1` before `invokevirtual Connection.setupCompression(IZ)V`) in **every**
version 1.17.1 → 1.21.11, and by decompilation in 26.1/26.2/26.3/26.4-snapshot-2:

```java
// Client — ClientHandshakePacketListenerImpl.handleCompression (26.3)
if (!this.connection.isMemoryConnection()) {
   this.connection.setupCompression(packet.getCompressionThreshold(), false);   // client: NO validation
}
// Server — ServerLoginPacketListenerImpl.verifyLoginAndFinishConnectionSetup (26.3)
if (this.server.getCompressionThreshold() >= 0 && !this.connection.isMemoryConnection()) {
   this.connection.send(new ClientboundLoginCompressionPacket(this.server.getCompressionThreshold()),
      PacketSendListener.thenRun(() -> this.connection.setupCompression(this.server.getCompressionThreshold(), true)));  // server: validates
}
```

In 1.16.5 and 1.17 both call `setupCompression(int)` (no flag) → the decoder validates on both sides.

`Connection.setupCompression(threshold, validate)` (26.3): `threshold >= 0` → update the existing decoder/encoder in
place (`setThreshold`) or add them; `threshold < 0` → remove both `decompress` and `compress` handlers.

### 1.3 Answers to Q1 a–e

| Q | Answer | Status |
|---|---|---|
| a. below-threshold check | **≤1.17 (≤755): both client and server.** **1.17.1+ (756+): server only** (client passes `validateDecompressed=false`, server `true`). The flag was introduced in **1.17.1** (absent in 1.17, present in 1.17.1 — both bytecode-verified). | [V] |
| b. max uncompressed size | **≤1.17: 2 097 152 (2 MiB), both sides. 1.17.1+: 8 388 608 (8 MiB), server only** (under the same flag). The 1.17.1+ client has **no** cap on declared size (it will try to allocate it). | [V] |
| c. uncompressed (DataLength=0) frame ≥ threshold | **No such check in any vanilla version, either side.** The `0` branch passes the bytes straight through. Only bound is the 21-bit frame length (≤ 2 097 151 bytes). | [V] |
| d. inflated size == declared | **≤1.20.1: not checked** (inflate into `byte[declared]`; shortfall leaves trailing zeros which the `PacketDecoder` then rejects as "was larger than I expected, found N bytes extra" or fails on parse). **1.20.2+ (764+): checked unconditionally, both sides**, but only detects *actual < declared*: the output buffer is exactly `declared` long, so a stream that would inflate to *more* fills the buffer and the remainder is silently dropped by `inflater.reset()`. | [V] |
| e. compressed payload size | `MAXIMUM_COMPRESSED_LENGTH = 2097152` exists as a constant since 1.17.1 but the decoder never tests it. The effective bound is the frame decoder: `Varint21FrameDecoder` rejects a length VarInt wider than 3 bytes ("length wider than 21-bit"), so a whole frame ≤ 2 097 151 bytes. The encoder side (`Varint21LengthFieldPrepender`) refuses to write longer frames. | [V] |

Other vanilla facts [V]:
- `CompressionEncoder`: `if (uncompressedLength < threshold) { VarInt.write(out, 0); out.writeBytes(...) } else { compress }`
  — identical comparison in every version (so size == threshold is compressed; the decoder's `<` test accepts it).
  Since **1.20.5** the encoder also throws `IllegalArgumentException("Packet too big (is X, should be less than 8388608)")`.
- `new Inflater()` / `new Deflater()` with no arguments in every version → **zlib format (RFC 1950 header + Adler-32), not raw
  deflate**, default level, no preset dictionary.
- `VarInt.read` accepts up to 5 bytes and does **not** reject over-long (non-canonical) encodings; values can be negative.
- `PacketDecoder` rejects leftover bytes after parsing ("Packet X/Y (Z) was larger than I expected, found N bytes extra
  whilst reading packet Y") — present in 1.8.9 and 26.3.

---

## Validation matrix — vanilla (version × side) [V]

"Rx" = the side decoding the frame. Client-Rx = clientbound frames decoded by the client; Server-Rx = serverbound frames decoded by the server.

| Versions (protocol) | Side | DataLen>0 && DataLen < T rejected? | Max declared DataLen | DataLen=0 && len ≥ T rejected? | Inflated ≠ declared rejected? | Zero-length frame |
|---|---|---|---|---|---|---|
| 1.8 – 1.17 (47 – 755) | Client-Rx | **YES** | **2 MiB** (2 097 152) | no | no (trailing zeros → PacketDecoder "bytes extra" / parse error) | ignored |
| 1.8 – 1.17 (47 – 755) | Server-Rx | **YES** | **2 MiB** | no | no (same) | ignored |
| 1.17.1 – 1.20.1 (756 – 763) | Client-Rx | **no** (`validateDecompressed=false`) | none (allocates declared size) | no | no (same) | ignored |
| 1.17.1 – 1.20.1 (756 – 763) | Server-Rx | **YES** | **8 MiB** (8 388 608) | no | no (same) | ignored |
| 1.20.2 – 1.21.8 (764 – 772) | Client-Rx | no | none | no | **YES if actual < declared** | ignored |
| 1.20.2 – 1.21.8 (764 – 772) | Server-Rx | **YES** | **8 MiB** | no | **YES if actual < declared** | ignored |
| 1.21.9 – 26.3 (773 – 777), 26.4-snapshot-2 | Client-Rx | no | none | no | YES if actual < declared | **rejected** ("Frame length cannot be zero") |
| 1.21.9 – 26.3 (773 – 777), 26.4-snapshot-2 | Server-Rx | **YES** | **8 MiB** | no | YES if actual < declared | **rejected** |

All versions: frame-length VarInt ≤ 3 bytes (frame ≤ 2 097 151 bytes), zlib format, encoder compresses iff `size >= threshold`.

Non-vanilla receivers that matter (details §3–§5):

| Receiver | DataLen>0 && < T | DataLen=0 && len ≥ T | Max DataLen | Mismatch |
|---|---|---|---|---|
| Paper/Spigot/Purpur/Folia (server) | = vanilla server of that version | no | = vanilla | natives: libdeflate exact; Java fallback accepts short |
| Minestom (server) | **no** | no | `minestom.max-packet-size` = 2 097 151 (PLAY), 8 192 pre-auth | yes (exact) |
| **Krypton ≥0.2.9 / Krypton Reno (client)** | no | **YES (always)** | none | natives-dependent |
| Krypton ≥0.2.9 / Reno (Fabric/NeoForge server) | YES | **YES** | 8 MiB (Reno 1.21.11+: 128 MiB) | natives-dependent |
| ViaFabricPlus → ≤1.17 target (client) | YES | no | 8 MiB | vanilla |
| ViaProxy | YES | no | 8 MiB / 2 MiB (<1.17.1) | no |
| Geyser / MCProtocolLib (client) | only if opted-in | no | 8 MiB if opted-in | undersize only |
| Velocity (proxy, both legs) | YES | **YES** (unless `-Dvelocity.skip-uncompressed-packet-size-validation`) | 8 MiB clientbound / 2 MiB serverbound | yes |
| BungeeCord | no | no | 8 MiB | yes |

---

## 2. Threshold-mismatch consequences for verbatim relay

Notation: **T_c** = threshold the proxy sent the client; **T_b** = threshold the backend sent the proxy.
A conforming encoder (vanilla, Paper, Minestom, Velocity) emits a *compressed* frame iff `size ≥ T`, else DataLength=0.
So a backend frame is "canonical for T_b": compressed ⇒ DataLength ≥ T_b; uncompressed ⇒ len < T_b.

### 2.1 Clientbound (backend frames relayed verbatim to the client)

| Client | T_b == T_c | T_b > T_c | T_b < T_c | T_b = −1 (backend sends no DataLength) |
|---|---|---|---|---|
| Vanilla ≤ 1.17 (≤755), ViaFabricPlus→≤1.17, ViaProxy | OK | OK (uncompressed frames ≥ T_c accepted by vanilla) | **BREAKS**: compressed frames with T_b ≤ DataLength < T_c → "Badly compressed packet - size of X is below server threshold of T_c" | must re-frame: prepending a `0x00` DataLength to every frame is accepted by vanilla (no deflate needed) |
| Vanilla ≥ 1.17.1 (≥756), Forge/NeoForge/Fabric without Krypton, Geyser | OK | OK | **OK** (no client validation) | prepend `0x00` is enough (no deflate needed) |
| **Krypton ≥0.2.9 / Krypton Reno clients** | OK | **BREAKS**: uncompressed frames with T_c ≤ len < T_b → "Actual uncompressed size X is greater than threshold T_c" | OK | **prepend-`0x00`-only BREAKS** on first packet ≥ T_c → must deflate those |

### 2.2 Serverbound (client frames relayed verbatim to the backend)

Client frames are canonical for T_c (vanilla/Krypton encoders).

| Backend | T_b == T_c | T_b > T_c | T_b < T_c | T_b = −1 |
|---|---|---|---|---|
| Vanilla / Paper / Spigot / Purpur / Folia (all versions) | OK | **BREAKS**: compressed frames with T_c ≤ DataLength < T_b → backend kick "Badly compressed packet … below server threshold" | OK (uncompressed frames ≥ T_b accepted) | strip the DataLength=0 byte; DataLength>0 frames must be inflated |
| Minestom | OK | OK (no below-threshold check) | OK | same as left |
| Fabric/NeoForge server with Krypton ≥0.2.9 / Reno | OK | BREAKS (below-threshold) | **BREAKS** (uncompressed ≥ T_b) | same as left |

Caps: vanilla backend rejects DataLength > 8 MiB (≥1.17.1) / > 2 MiB (≤1.17); Minestom > 2 097 151 by default. Same as a direct connection — passthrough doesn't change it.

### 2.3 Consequences

- **T_c == T_b is the only setting that is passthrough-clean in both directions for every known client and backend.**
- Any mismatch is detectable **from the frame header alone** (DataLength VarInt + frame length), no inflation needed.
- Because SetCompression is LOGIN-only (1.9+), T_c is frozen for the session. When a later backend has a different T_b, Warp needs a
  per-frame normaliser: re-encode only frames that are non-canonical for the *outgoing* leg's threshold.
- **T_b = −1** (still recommended by many guides behind Velocity) means *every* clientbound frame must be re-framed, and every frame
  ≥ T_c must be **deflated by Warp** (Krypton clients), i.e. compression passthrough brings no gain on such backends.
  Warp should log/recommend `network-compression-threshold` = Warp's threshold for Paper backends and `MinecraftServer.setCompressionThreshold(T)` for Minestom.

---

## 3. Paper / Spigot / Purpur / Folia (server side) — and proxies for comparison

### 3.1 Paper [V by sub-agent at pinned SHAs; not re-run by me unless noted]
- Current Paper main `4728a906` (mcVersion 26.3): the natives live in feature patch
  [`0008-Use-Velocity-compression-and-cipher-natives.patch#L120-L140`](https://github.com/PaperMC/Paper/blob/4728a906edb501c3749dfcc437a419094a95a2c7/paper-server/patches/features/0008-Use-Velocity-compression-and-cipher-natives.patch#L120-L140).
  The hunk is inserted **after** the vanilla `if (this.validateDecompressed) {...}` block, i.e. Paper keeps vanilla's below-threshold
  and 8 MiB checks and adds no new validation of its own; when the velocity-native compressor is used it replaces vanilla's
  `inflate()` (so vanilla's "does not match declared size" message is not hit on that path).
- Paper still calls `setupCompression(compressionThreshold, true)` server-side:
  [`ServerLoginPacketListenerImpl.java.patch#L162-L170`](https://github.com/PaperMC/Paper/blob/4728a906edb501c3749dfcc437a419094a95a2c7/paper-server/patches/sources/net/minecraft/server/network/ServerLoginPacketListenerImpl.java.patch#L162-L170).
  Paper also writes SetCompression and enables compression in the same event-loop task (workaround for MC-308621, a
  dedicated-server compression-setup race).
- Size-mismatch behaviour then depends on velocity-native 4.1.0 (sub-agent ran it): **libdeflate** (Linux x86_64/aarch64, macOS arm64)
  requires the exact size both ways ("uncompressed size is inaccurate"); the **Java fallback** (Windows, macOS x86) rejects
  over-long output but **accepts a stream that ends early** (declared 1000, produced 500 → accepted). Irrelevant for verbatim relay of
  well-formed frames.
- Encoder: Velocity's deflater is only used inside vanilla's `size >= threshold` branch → Paper never emits a compressed frame below
  its threshold, never an uncompressed one at/above it. zlib format.
- Paper 1.20.4 (`7263a87`): same structure ([patch#L129-L157](https://github.com/PaperMC/Paper/blob/7263a87e9c65cf7a9cfe1cf5900a3236fd3dc20d/patches/server/Use-Velocity-compression-and-cipher-natives.patch#L129-L157)).
  Paper-archive ver/1.12.2 and ver/1.16.5: no natives patch, no decompressor patch → vanilla (always validate, 2 MiB).
  Natives patch first appears in ver/1.17.1.
- **Uncompressed (DataLength = 0) frames are not checked by Paper** (vanilla branch untouched).
- Nothing in Paper auto-disables compression when `proxies.velocity`/`bungeecord` is enabled.
- **Default threshold: 256** (`DedicatedServerProperties`: `network-compression-threshold` default 256; negative disables).

### 3.2 Spigot / Purpur / Folia [V by sub-agent]
- CraftBukkit (Stash `c4db4c38`, 26.3) and Spigot (`bed90fca`): no patch touches `CompressionDecoder`/`CompressionEncoder`/`setupCompression`.
- Purpur ver/26.3 (`ad20ae97`) and Folia ver/26.2.x (`acf6733d`): no compression changes → behave exactly like Paper.

### 3.3 "Set the backend to −1 behind Velocity" advice [V by sub-agent]
- Exists only in Velocity's **old, archived Sphinx docs** (last commit 2020-09-28),
  [frequently-asked-questions.rst#L51-L62](https://github.com/VelocityPowered/documentation/blob/4f26cded8140532223e9fc3c2ec1b3adc3a7a352/users/frequently-asked-questions.rst#L51-L62):
  > "Disable compression between the proxy and your backend server — If your backend server has compression enabled (by default,
  > Minecraft servers compress packets larger than 256 bytes), then Velocity is forced to decompress the packets from servers so it
  > can process them, usually only to compress then shortly afterwards because it did not find anything interesting. To eliminate
  > this inefficiency, you should disable compression on your backend server, so that only Velocity is responsible for compressing
  > packets. To disable compression, simply set network-compression-threshold=-1 in your server.properties, and then reboot your server."
- Current docs.papermc.io (PaperMC/docs `3d540521`): the Velocity tuning page and FAQ do **not** contain it (never did, per git history);
  the config reference only says "compression-threshold … Minecraft uses 256 bytes by default". [I] The advice still circulates widely
  in community guides, so **T_b = −1 must be treated as a common, first-class case**.

### 3.4 Velocity (proxy, for comparison) [V — cloned `PaperMC/Velocity@7fc49913`, 2026-09-29]
[`MinecraftCompressDecoder.java`](https://github.com/PaperMC/Velocity/blob/7fc49913145fc6195db95995eb622bf9f5592876/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftCompressDecoder.java):
```java
if (claimedUncompressedSize == 0) {
  if (!SKIP_COMPRESSION_VALIDATION) {          // -Dvelocity.skip-uncompressed-packet-size-validation
    checkFrame(actualUncompressedSize < threshold, "Actual uncompressed size %s is greater than threshold %s", ...);
  }
  ...
}
checkFrame(claimedUncompressedSize >= threshold, "Uncompressed size %s is less than threshold %s", ...);   // both directions
CLIENTBOUND cap 8 MiB, SERVERBOUND cap 2 MiB (128 MiB with -Dvelocity.increased-compression-cap)
checkFrame(uncompressed.writerIndex() == claimedUncompressedSize, "Decompressed size %s does not match claimed uncompressed size %s", ...);
```
History (git log): "Validate uncompressed packet size (#1527)" 2025-03-14; skip property added 2025-06-14 after it broke a backend
that sent 16 384-byte DataLength=0 frames with T=256 (Velocity #1556 / Carpet-TIS #209); "reduce clientbound compression limits"
and ratio limiter 2026-04. Velocity always decompresses and re-compresses (no passthrough).

### 3.5 BungeeCord / Waterfall [V by sub-agent]
- BungeeCord (`acc3dbd`) `PacketDecompressor`: **no threshold check at all**, max `1<<23` (8 MiB), `checkState(readableBytes == size, "Decompressed packet size mismatch")`.
  Always re-compresses (EntityMap rewrites ids in decompressed buffers).
- Waterfall (EOL) patch 0021 adds `checkArgument(size >= compressionThreshold)`.

---

## 4. Minestom (backend of the main consumer) [V — read at `Minestom/Minestom@fcd35c05`, 2026-10-05]

**Serverbound decoding** — [`PacketReading.readFramedPacket` L233-L265](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/packet/PacketReading.java#L233-L265):
```java
final int dataLength = buffer.read(VAR_INT);
if (dataLength == 0) return readPayload(buffer, registry, packetReader);          // no size-vs-threshold check
if (dataLength < 0 || dataLength > maxPacketSize)
    throw new DataFormatException("Invalid decompressed length: " + dataLength);    // no below-threshold check
final long written = buffer.decompress(buffer.readIndex(), buffer.readableBytes(), slice);
if (written != dataLength)
    throw new DataFormatException("Decompressed length mismatch: expected " + dataLength + ", got " + written);
```
- Frame length is checked against the same limit ("Packet too large"), [L211-L212](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/packet/PacketReading.java#L211-L212).
- `maxPacketSize` = `minestom.max-packet-size` **2 097 151** in CONFIGURATION/PLAY and `minestom.max-packet-size-pre-auth` **8 192** in
  HANDSHAKE/STATUS/LOGIN ([ServerProperties L51-L52](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/property/ServerProperties.java#L51-L52), [PacketReading L292-L297](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/packet/PacketReading.java#L292-L297)).
  → Minestom rejects serverbound packets whose *uncompressed* size exceeds 2 MiB−1, stricter than vanilla's 8 MiB.
- Verdict: no below-threshold check, no uncompressed-size check, exact mismatch check → **the most permissive server for passthrough**.

**Threshold** — default **256** ([MinecraftServer L90](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/MinecraftServer.java#L90)); **0 means disabled** (Javadoc "0 to disable compression";
[ConnectionManager L208-L211](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/ConnectionManager.java#L208-L211): `if (threshold > 0) socketConnection.startCompression();`).
Minestom therefore cannot express vanilla's "threshold 0 = compress everything". Nothing auto-disables compression when Velocity/Bungee forwarding is enabled.
SetCompression is sent in LOGIN by `startCompression()` ([PlayerSocketConnection L235-L241](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/player/PlayerSocketConnection.java#L235-L241)).

**Clientbound framing** — [`PacketWriting.writeCompressedFormat` L91-L119](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/packet/PacketWriting.java#L91-L119):
```java
final long compressedIndex = buffer.advanceWrite(3);      // Packet Length placeholder (3 bytes)
final long uncompressedIndex = buffer.advanceWrite(3);    // Data Length placeholder (3 bytes)
...
final boolean compressed = packetSize >= compressionThreshold;   // same rule as vanilla
if (compressed) input.compress(0, packetSize, buffer);           // java.util.zip.Deflater::new → zlib
buffer.writeAt(compressedIndex,   NetworkBuffer.VAR_INT_3, (int) (buffer.writeIndex() - uncompressedIndex));
buffer.writeAt(uncompressedIndex, NetworkBuffer.VAR_INT_3, compressed ? (int) packetSize : 0);
```
- **Both length fields are always written as fixed-width, non-canonical 3-byte VarInts** ([VarInt3Type L294-L306](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/NetworkBufferTypeImpl.java#L294-L306)):
  DataLength 0 is on the wire as `80 80 00`. Legal for vanilla (`VarInt.read` accepts over-long encodings) — **Warp's header peek must
  accept it and must not "normalise" it** (or must recompute the Packet Length if it does).
- Consequence of VAR_INT_3 for DataLength: Minestom cannot send a packet whose uncompressed size ≥ 2^21 (`Check.argCondition` throws). [V]
- Deflater: `ObjectPool.pool(Deflater::new)` / `Inflater::new` ([NetworkBufferImpl L301-L302](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/NetworkBufferImpl.java#L301-L302)) → zlib, default level.
- Per-connection: `compressionThreshold = compressed ? MinecraftServer.getCompressionThreshold() : 0` ([PlayerSocketConnection L401](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/player/PlayerSocketConnection.java#L401));
  `compressed` flips after the SetCompression packet has been queued (`sentPacketCounter > compressionStart`, L486).
- **Pre-framed packets**: `CachedPacket` frames once with the *global* threshold ([CachedPacket L167](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/network/packet/server/CachedPacket.java#L167)),
  broadcast/viewable packets are framed once with the global threshold and reused for every viewer ([PacketViewableUtils L88](https://github.com/Minestom/Minestom/blob/fcd35c05d91add21fc063fa5689bbf8d85d46692/src/main/java/net/minestom/server/utils/PacketViewableUtils.java#L88)),
  `FramedPacket`/`BufferedPacket` bodies are copied verbatim into the socket buffer. Since the threshold is global and immutable after start,
  all frames are canonical for that threshold. [I] A cached frame written to a not-yet-compressed connection would be malformed, but caches are
  PLAY-state packets sent after compression is on.
- Bundles: `BundlePacket` (delimiter) is a normal empty-payload PLAY packet → frame-level transparent.
- **Verdict**: Minestom frames are exactly what a vanilla encoder with the same threshold emits (modulo 3-byte padded VarInts) → accepted by every
  client in §5 **iff T_c == Minestom's threshold** (Krypton clients reject its uncompressed frames if T_c < T_b; ≤1.17 clients reject its compressed
  frames if T_c > T_b). Passthrough-friendly. Minestom's `0 = off` must be mapped to "backend uncompressed" (T_b = −1 semantics) in Warp.

---

## 5. Client loaders and mods

| Implementation | Rejects DataLen>0 && DataLen < T? | Rejects DataLen=0 && len ≥ T? | Max declared size | Mismatch check | Source |
|---|---|---|---|---|---|
| **Krypton ≥ 0.2.9 (MC 1.21.5+, incl. 0.3.x for 26.x)** — client | no (`validate=false` passed through) | **YES, unconditionally** ("Actual uncompressed size %s is greater than threshold %s") | none on client | libdeflate natives: exact both ways; Java fallback: none | [V, re-checked by me] [MinecraftCompressDecoder.java@bc08466](https://github.com/astei/krypton/blob/bc08466ff900d97b27bfabcde92005b4774717e8/src/main/java/me/steinborn/krypton/mod/shared/network/compression/MinecraftCompressDecoder.java#L41-L56); added in [6f096d3](https://github.com/astei/krypton/commit/6f096d3fe979cb8be4025bfe65479c2fbb088dc8) |
| Krypton ≥ 0.2.9 — on a Fabric **server** (`validate=true`) | YES | **YES** | 8 MiB (128 MiB with `-Dkrypton.permit-oversized-packets`) | as above | same file |
| Krypton 0.1.x – 0.2.8 (≤1.21.4) | only with validate (server) | no | 8 MiB w/ validate | natives-dependent | [V by sub-agent] |
| Krypton 0.1.x for MC 1.17 | YES (always) | no | 2 MiB | natives-dependent | [V by sub-agent] |
| **Krypton Reno / "krypton-fnp"** (Forge/NeoForge/Fabric, ~6 M downloads), all branches incl. 1.20.1, 1.21.1 | client: no; server: yes | **YES, unconditionally** | 128 MiB unconditional on 1.21.11+ | natives-dependent | [V by sub-agent] [KryptonReno@1501190](https://github.com/404Setup/KryptonReno/blob/15011908d3e7ec03342fdf0063022682bae7e811/common/src/main/java/one/pkg/kreno/shared/network/compression/MinecraftCompressDecoder.java) |
| KryptonFoxified (NeoForge 1.21.1) | client: no | no | — | — | [V by sub-agent] |
| KryptonHybrid | client: no | **no (check deliberately removed, citing the "peer with higher threshold" case)** | — | — | [V by sub-agent] |
| NeoForge (26.3.x `6edb30d`, 1.21.1, 1.20.4), Forge (26.3 `939fb13`, 1.21.11, 1.20.1), Fabric API | vanilla | vanilla | vanilla | vanilla | [V by sub-agent]: only an encoder debug-log patch (>8 MiB); `GenericPacketSplitter` works on packet objects (wire-transparent) |
| **ViaFabricPlus** targeting ≤1.17 servers | **YES** (forces `setupCompression(t, targetVersion <= 1.17)`; 1.8 PLAY SetCompression also with `true`) | no | 8 MiB (vanilla decoder of the host client) | host client's | [V by sub-agent] [MixinClientHandshakePacketListenerImpl@9913d32](https://github.com/ViaVersion/ViaFabricPlus/blob/9913d32266be9243ee31fc84539913ae79ae6aa2/src/main/java/com/viaversion/viafabricplus/injection/mixin/features/v1_17/MixinClientHandshakePacketListenerImpl.java) |
| ViaProxy (NetMinecraft 3.1.7) | YES always | no | 8 MiB (2 MiB for <1.17.1) | no | [V by sub-agent] |
| MCProtocolLib / **Geyser** (`19783c2`) | only if `validateDecompression` (client default false; Geyser: `-DGeyser.ValidateDecompression`, default false) | no | 8 MiB if validating | yes, undersize only (since 2026-02) | [V by sub-agent] [PacketCompressionCodec.java@19783c2](https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/network/netty/PacketCompressionCodec.java) |
| node-minecraft-protocol 1.68.0 (mineflayer) | no | no | none (unreleased 8 MiB drop) | logged only | [V by sub-agent] |
| Raknetify | n/a — UDP transport with `raknet;` prefix, own compression; a TCP proxy never sees it | | | | [V by sub-agent] |
| Sodium / Lithium / FerriteCore | no networking-compression code | | | | [V by sub-agent, by file paths only] |
| Lunar / Badlion / Feather | closed source | | | | [?] 1.8.9 bases presumably strict vanilla 1.8.9 |

Krypton also replaces the frame decoder (3-byte max VarInt, skips leading `0x00` bytes, ignores zero-length frames) [V by sub-agent].
Known interaction bug: Krypton + ViaFabricPlus pipeline order (krypton#151, ViaFabricPlus#1045) [V by sub-agent, not re-read].

**Decisive consequence:** Krypton ≥0.2.9 and Krypton Reno are very common on Fabric/NeoForge clients and enforce the *inverse* rule
of vanilla: an **uncompressed frame whose payload is ≥ T_c kills the connection**. So when T_b > T_c, or when T_b = −1 and the
proxy merely prefixes `0x00`, those clients disconnect on the first large packet (chunks, recipes, tags, …).

---

## 6. SetCompression: state, multiplicity, PLAY-state variant

| Fact | Status |
|---|---|
| **1.8.x has a PLAY-state Set Compression (clientbound 0x46)** in addition to the LOGIN one. In the 1.8.9 jar, the PLAY clientbound registration (enum `el$2`, 71st clientbound entry = id 0x46) maps to class `gl` (`readVarInt` threshold), handled by `NetHandlerPlayClient` (`bcy`): `if (!netManager.isLocalChannel()) netManager.setCompressionThreshold(packet.getThreshold());` — executed directly on the Netty thread (no main-thread hop). | [V] |
| Removed in **1.9**: in 1.9.4 and 1.12.2 the only callers of `NetworkManager.setCompressionThreshold(int)` are the login client handler and the login server listener (bytecode scan of all classes). ViaVersion confirms: `ClientboundPackets1_8.SET_COMPRESSION, // 0x46` is listed under "Removed packets" in the 1.8→1.9 protocol and it throws `"PLAY state Compression packet is unsupported"` by default ([PlayerPacketRewriter1_9.java#L331-L340](https://github.com/ViaVersion/ViaVersion/blob/309c3e57c1b5ec5e4a1faa9333dda62cbafca7ea/common/src/main/java/com/viaversion/viaversion/protocols/v1_8to1_9/rewriter/PlayerPacketRewriter1_9.java#L331-L340), [CompressionProvider.java#L36-L38](https://github.com/ViaVersion/ViaVersion/blob/309c3e57c1b5ec5e4a1faa9333dda62cbafca7ea/common/src/main/java/com/viaversion/viaversion/protocols/v1_8to1_9/provider/CompressionProvider.java#L36-L38)). | [V] |
| **1.9 → 26.3: SetCompression exists only in LOGIN.** In 26.3 the class `ClientboundLoginCompressionPacket` is referenced only by `LoginProtocols`, `LoginPacketTypes`, `ClientLoginPacketListener`, `ClientHandshakePacketListenerImpl`, `ServerLoginPacketListenerImpl`; `setupCompression` is only called by the two login listeners. Hence **the client's threshold is fixed for the lifetime of the TCP connection** once LOGIN ends (CONFIGURATION/PLAY round-trips in 1.20.2+ keep the same compression handlers). | [V] |
| **Sending it more than once (1.9+)** is handled gracefully: `setupCompression` updates the existing decoder/encoder (`setThreshold`) if present; a negative threshold removes both handlers (compression off). Vanilla servers only send it once and never send a negative value. | [V] code / [I] that no client misbehaves |
| **1.8.x bug: a second SetCompression with threshold ≥ 0 while compression is active throws `ClassCastException`.** 1.8.9 `NetworkManager.setCompressionTreshold`: `if (pipeline.get("compress") instanceof NettyCompressionEncoder) ((NettyCompressionEncoder) pipeline.get("decompress")).setCompressionTreshold(t);` — casts the *decoder* to the encoder type. Fixed by 1.9.4 (casts `get("compress")`). Disabling with a negative value (removes handlers) and re-enabling afterwards does not hit the bug. | [V] |
| Ordering: the vanilla server sends `ClientboundLoginCompressionPacket` and enables its own compression **in the send-completion listener** (`PacketSendListener.thenRun(...)`), i.e. the SetCompression frame itself is uncompressed-format, everything after it uses the compressed format. The client switches immediately upon handling it (handled on the Netty thread). | [V] |

---

## 7. Other things that affect verbatim relaying (vanilla facts)

1. **zlib, not raw deflate** — `new Inflater()`/`new Deflater()` (nowrap = false) in every version → RFC 1950 (2-byte header, Adler-32 trailer).
   No preset dictionary is ever set; a stream with FDICT would make `inflate` return 0 bytes → 1.20.2+ "does not match declared size",
   ≤1.20.1 trailing zeros → "bytes extra" / parse error. Passthrough never alters the stream, so this only matters for frames the proxy itself produces. [V]
2. **VarInt widths** — `VarInt.read` accepts non-canonical (over-long) encodings up to 5 bytes (Data Length) and the frame splitter
   accepts up to 3 bytes (Packet Length). Implementations that pad VarInts (e.g. fixed 3-byte Packet Length) are legal. A passthrough
   parser must accept non-minimal VarInts and must re-emit the frame header **only** if it re-frames; otherwise copy as-is. [V]
3. **Negative Data Length** — decodes as a negative int: ≤1.17 rejected by the `< threshold` test; 1.17.1–1.20.1 client →
   `NegativeArraySizeException`; 1.20.2+ client → `directBuffer(negative)` → `IllegalArgumentException`; server always rejects
   (`< threshold`). Every vanilla endpoint disconnects; a proxy should reject such frames itself. [V code / I for exact exception path]
4. **Zero-length frames** — ≤1.21.8: an empty frame passes the splitter and is skipped by the decompressor/decoder guards.
   **1.21.9+ (773+): `CorruptedFrameException("Frame length cannot be zero")`.** A proxy that drops a packet must drop the whole frame,
   never emit an empty one. [V]
5. **Data Length = 0 with a payload that is actually zlib** → treated as raw packet bytes → garbage packet id → error. [V by code path]
6. **Trailing data after the zlib stream** inside a frame is ignored (`inflater.reset()`), and in 1.20.2+ a declared size *smaller*
   than the real stream is not detected (buffer filled exactly). Irrelevant for verbatim relay. [V]
7. **Bundles** (1.19.4+) are packet-level (`ClientboundBundleDelimiterPacket` frames around normal frames; `BundlerInfo.BUNDLE_SIZE_LIMIT = 4096`
   enforced after decoding). No frame-level aggregation → no impact on passthrough, except that the proxy must not inject its own
   packets *inside* an open bundle unless intended. [V constant / I advice]
8. **Encryption** (AES-128-CFB8) is applied to the byte stream *outside* framing (pipeline: decrypt → splitter → decompress → decoder).
   Passthrough still decrypts/re-encrypts per leg; compressed payloads are unaffected. [V pipeline names / I]
9. **Peeking the packet id of a compressed frame requires inflating its first bytes** (the id is the first VarInt *inside* the zlib stream).
   A bounded partial inflate (≤5 output bytes, then `reset()`) suffices; the cost is header + Huffman table parse for dynamic blocks. [I]
10. **Size limits are version-dependent** and only the vanilla *server* enforces them in 1.17.1+: serverbound declared size ≤ 8 MiB
    (≤ 2 MiB for ≤1.17 servers, which also enforce it client-side). Frames are always ≤ 2 097 151 bytes on the wire. [V]

---

## 8. Safe passthrough rules implied by the above

**R1 — Canonical-frame invariant (the single rule that satisfies every receiver found).** On a leg whose threshold is T ≥ 0,
emit only frames where
`(DataLength == 0 && payloadLen < T) || (T ≤ DataLength ≤ cap)`.
This is exactly what vanilla's own encoder produces. It satisfies vanilla (all versions, both sides), Paper/Spigot/Purpur/Folia,
Minestom, Krypton/Krypton Reno (client & server), ViaFabricPlus, ViaProxy, Velocity, BungeeCord, MCProtocolLib.

**R2 — Verbatim relay decision is header-only.** For each incoming compressed-format frame, read Packet Length + DataLength (accept
over-long VarInts, e.g. Minestom's `80 80 00`), and relay verbatim iff the frame satisfies R1 for the *outgoing* leg's threshold.
`payloadLen = frameLen − sizeof(DataLength VarInt as encoded)`. No inflation needed for this decision.

**R3 — Normalise violators (rare when thresholds match):**
- DataLength > 0 && DataLength < T_out → inflate, send as DataLength=0 (payload < T_out, so the result is canonical for every receiver).
- DataLength == 0 && payloadLen ≥ T_out → deflate at the proxy (zlib, *not* raw deflate), DataLength = payloadLen.
- T_in = −1 (backend uncompressed, or Minestom threshold 0) and T_out ≥ 0 → re-frame every frame: `0x00` + payload if < T_out, deflate otherwise.
- T_out = −1 → inflate every compressed frame and strip the DataLength field.

**R4 — Configuration:** make **T_c == T_b** the default and warn loudly otherwise; recommend backends use Warp's threshold
(Paper `network-compression-threshold`, Minestom `MinecraftServer.setCompressionThreshold`) rather than the legacy Velocity "−1" advice.
Since T_c is frozen after LOGIN (1.9+), per-backend thresholds that differ from T_c must go through R3 on every server switch.

**R5 — Peeking the packet id of a compressed frame** needs a bounded partial inflate (first ≤5 output bytes) — use a dedicated
`Inflater`, `reset()` per frame; never feed the relayed `ByteBuf` into a stateful stream shared with other frames. [I]

**R6 — Validation Warp should still do on frames it relays without inflating** (cheap, header-only):
negative DataLength → reject; DataLength > cap (8 MiB serverbound to vanilla ≥1.17.1, 2 MiB for ≤1.17 and Minestom) → reject early
(the backend would kick anyway); frame length ≤ 2 097 151 / ≤ 3-byte VarInt. Note that decompression-bomb and ratio checks are
impossible without inflating — for passthrough frames they are delegated to the backend (vanilla server enforces 8 MiB; the client is
the victim for clientbound, as with any direct connection).

**R7 — Never emit zero-length frames** (1.21.9+ clients and servers reject them) and never split/merge frames; a dropped packet
removes its whole frame.

**R8 — 1.8.x specifics:** never send a second SetCompression with threshold ≥ 0 to a 1.8 client while compression is active
(ClassCastException); if a 1.8 backend sends PLAY-state Set Compression (0x46), Warp must consume it and treat it as a T_b change
(not forward it, or forward only with the −1-then-new workaround). [V for the bug; I for the handling advice]

**R9 — Inject proxy-originated packets with the client leg's encoder (threshold T_c)**, as whole frames between relayed frames,
and not inside an open bundle unless intentional.

**Notes on Warp's current `protocol/.../netty/CompressionDecoder` (read-only observation):** it already accepts DataLength=0 frames of any
size (matches vanilla) and rejects DataLength < threshold on *both* legs (stricter than a 1.17.1+ client on the backend leg, harmless
with vanilla/Paper/Minestom backends whose frames are canonical). Its 1024:1 ratio limit is just below deflate's theoretical maximum
(~1032:1), so a legitimate, almost-all-zero packet could in principle trip it [I]; irrelevant for frames relayed without inflation.

---

## 9. Uncertainties / not verified

- Mojang's rationale/bug ticket for the 1.17.1 `validateDecompressed` change was not located (Mojira legacy site unreachable).
  The *behaviour* is bytecode-verified; the motivation is not.
- Paper/Spigot/Purpur/Folia, Krypton forks, NeoForge/Forge, ViaFabricPlus, ViaProxy, MCProtocolLib, node-minecraft-protocol,
  BungeeCord/Waterfall findings come from sub-agents reading source at the pinned SHAs. I re-checked only Krypton's decoder,
  Velocity's decoder and Minestom myself.
- velocity-native Java-fallback "accepts short stream" was established by a sub-agent running it; not re-run by me.
- Closed-source clients (Lunar, Badlion, Feather, LabyMod internals) are unverified. Lunar/Badlion 1.8.9 are presumably vanilla-strict
  (always validate below-threshold, 2 MiB cap) — **so treat 1.8.9 PvP clients as "strict": T_c must equal T_b.**
- Snapshot 26.4-snapshot-2 is identical to 26.3 for the three classes checked; future 26.4 changes are unknown.
- zlib Adler-32 verification behaviour when the output buffer is exactly filled is inferred from zlib's state machine, not tested.
  Irrelevant for verbatim relay.
- No exhaustive search of every Fabric/Forge networking mod; "Packet Fixer" only raises limits (per sub-agent).

---

## 10. Sources

- Mojang client jars & mappings: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` (versions listed in §0), decompiled with Vineflower 1.11.1.
- minecraft.wiki, Java Edition protocol / Packets (former wiki.vg): "The vanilla server (but not client) rejects compressed packets smaller than the threshold. Uncompressed packets exceeding the threshold, however, are accepted." — https://minecraft.wiki/w/Java_Edition_protocol/Packets
- ViaVersion 1.8→1.9 removed packet: https://github.com/ViaVersion/ViaVersion/blob/309c3e57c1b5ec5e4a1faa9333dda62cbafca7ea/common/src/main/java/com/viaversion/viaversion/protocols/v1_8to1_9/rewriter/PlayerPacketRewriter1_9.java#L331-L340
- Velocity decoder: https://github.com/PaperMC/Velocity/blob/7fc49913145fc6195db95995eb622bf9f5592876/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftCompressDecoder.java
- Velocity legacy docs (−1 advice): https://github.com/VelocityPowered/documentation/blob/4f26cded8140532223e9fc3c2ec1b3adc3a7a352/users/frequently-asked-questions.rst#L51-L62
- Paper natives patch (26.3): https://github.com/PaperMC/Paper/blob/4728a906edb501c3749dfcc437a419094a95a2c7/paper-server/patches/features/0008-Use-Velocity-compression-and-cipher-natives.patch#L120-L140
- Paper login patch (26.3): https://github.com/PaperMC/Paper/blob/4728a906edb501c3749dfcc437a419094a95a2c7/paper-server/patches/sources/net/minecraft/server/network/ServerLoginPacketListenerImpl.java.patch#L162-L170
- Paper natives patch (1.20.4): https://github.com/PaperMC/Paper/blob/7263a87e9c65cf7a9cfe1cf5900a3236fd3dc20d/patches/server/Use-Velocity-compression-and-cipher-natives.patch#L129-L157
- Krypton decoder: https://github.com/astei/krypton/blob/bc08466ff900d97b27bfabcde92005b4774717e8/src/main/java/me/steinborn/krypton/mod/shared/network/compression/MinecraftCompressDecoder.java#L41-L56 ; check introduced in https://github.com/astei/krypton/commit/6f096d3fe979cb8be4025bfe65479c2fbb088dc8
- Krypton Reno: https://github.com/404Setup/KryptonReno/blob/15011908d3e7ec03342fdf0063022682bae7e811/common/src/main/java/one/pkg/kreno/shared/network/compression/MinecraftCompressDecoder.java
- ViaFabricPlus: https://github.com/ViaVersion/ViaFabricPlus/blob/9913d32266be9243ee31fc84539913ae79ae6aa2/src/main/java/com/viaversion/viafabricplus/injection/mixin/features/v1_17/MixinClientHandshakePacketListenerImpl.java
- MCProtocolLib: https://github.com/GeyserMC/MCProtocolLib/blob/19783c29ece24bc3f07f8ff08628549527e3de20/protocol/src/main/java/org/geysermc/mcprotocollib/network/netty/PacketCompressionCodec.java
- Minestom: https://github.com/Minestom/Minestom/tree/fcd35c05d91add21fc063fa5689bbf8d85d46692 (files linked in §4)
