# Frame Decoder/Encoder Research

Deep technical research on how Minecraft proxies and servers handle TCP packet frame decoding/encoding. This guides the implementation of Warp's frame codec.

## Table of Contents

1. [Minecraft Protocol Frame Format](#1-minecraft-protocol-frame-format)
2. [Velocity Frame Decoder](#2-velocity-frame-decoder)
3. [BungeeCord Frame Decoder](#3-bungeecord-frame-decoder)
4. [Gate (Go) Frame Decoder](#4-gate-go-frame-decoder)
5. [Netty ProtobufVarint32FrameDecoder](#5-netty-protobufvarint32framedecoder)
6. [Common Bugs and Pitfalls](#6-common-bugs-and-pitfalls)
7. [Netty ByteToMessageDecoder vs ReplayingDecoder](#7-netty-bytetomessagedecoder-vs-replayingdecoder)
8. [Performance Optimizations](#8-performance-optimizations)
9. [Design Decisions for Warp](#9-design-decisions-for-warp)

---

## 1. Minecraft Protocol Frame Format

### Uncompressed Mode (before Set Compression or threshold < 0)

```
+----------------+----------------+-----------+
| Length (VarInt) | Packet ID (VI) | Payload   |
+----------------+----------------+-----------+
```

- **Length**: VarInt encoding the byte count of `Packet ID + Payload` (NOT including itself)
- **Packet ID**: VarInt identifying the packet type within the current protocol state
- **Payload**: Variable-length, packet-specific data

### Compressed Mode (after Set Compression with threshold >= 0)

When `uncompressed_size(PacketID + Payload) >= threshold`:

```
+---------------------+---------------------+-------------------------------+
| Packet Length (VarInt) | Data Length (VarInt) | Compressed(PacketID + Payload) |
+---------------------+---------------------+-------------------------------+
```

When `uncompressed_size(PacketID + Payload) < threshold`:

```
+---------------------+-------------------+----------------+-----------+
| Packet Length (VarInt) | Data Length = 0 (VI) | Packet ID (VI) | Payload   |
+---------------------+-------------------+----------------+-----------+
```

- **Packet Length**: VarInt encoding `size(Data Length) + size(compressed_or_raw_payload)`
- **Data Length**: VarInt — if 0, the packet is NOT compressed; if > 0, it equals the uncompressed size of (PacketID + Payload)
- **Compressed data**: zlib-compressed (PacketID + Payload)

### Critical Size Constraints

| Constraint | Value | Notes |
|---|---|---|
| Max wire frame size | 2^21 - 1 = 2,097,151 bytes | Max value in a 3-byte VarInt |
| Length field VarInt width | **Max 3 bytes** | Even if the encoded value fits, >3 byte encoding is rejected |
| Overlong VarInt encoding | **Allowed up to 3 bytes** | e.g., `81 00` to encode 1 is valid |
| Serverbound decompressed max | 2^23 = 8,388,608 bytes (8 MiB) | Vanilla server enforces this |
| Clientbound decompressed max | **No limit in vanilla client** | Dangerous — compression bomb vector |
| Compression threshold | Typically 256 bytes | Configurable via Set Compression packet |

### Key Protocol Rules

1. **Data Length = 0** means the packet is uncompressed, even though compression is "enabled". This is an optimization to avoid compressing tiny packets.
2. **Vanilla server rejects** compressed packets with `Data Length < threshold` (shouldn't have been compressed).
3. **Vanilla server accepts** uncompressed packets with `Data Length = 0` even if their actual size exceeds the threshold. This is a known quirk.
4. **Vanilla client has NO limit** for decompressed incoming packets. This is a security concern.

---

## 2. Velocity Frame Decoder

### Class: `MinecraftVarintFrameDecoder extends ByteToMessageDecoder`

**Pipeline position**: First inbound handler after transport. Receives raw TCP bytes.

### Fast-Path VarInt Reading (`readRawVarInt21`)

Velocity uses a bit-manipulation trick originally from Netty's ProtobufVarint32FrameDecoder. When 4+ bytes are available, it reads a little-endian 32-bit integer and processes it with bitwise operations:

```java
private static int readRawVarInt21(ByteBuf buffer) {
    if (buffer.readableBytes() < 4) {
        return readRawVarintSmallBuf(buffer);
    }
    // Read 4 bytes as little-endian int
    int wholeOrMore = buffer.getIntLE(buffer.readerIndex());

    // Find the first byte with MSB=0 (stop byte)
    // In each VarInt byte, bit 7 is the continuation flag
    // After ~ inversion, a stop byte has bit 7 = 1
    int atStop = ~wholeOrMore & 0x808080;
    if (atStop == 0) {
        // No stop byte found in 3 bytes = VarInt > 21 bits
        throw VARINT_TOO_BIG;
    }

    // Count bits to the first stop (inclusive)
    int bitsToKeep = Integer.numberOfTrailingZeros(atStop) + 1;
    buffer.skipBytes(bitsToKeep >> 3); // advance by byte count

    // Create mask that preserves bytes up to and including stop byte
    int preservedBytes = wholeOrMore & (atStop ^ (atStop - 1));

    // Strip continuation bits: merge 7-bit groups
    // Step 1: handle pairs of bytes
    preservedBytes = (preservedBytes & 0x007F007F) | ((preservedBytes & 0x00007F00) >> 1);
    // Step 2: merge into final value
    preservedBytes = (preservedBytes & 0x00003FFF) | ((preservedBytes & 0x3FFF0000) >> 2);

    return preservedBytes;
}
```

**How it works**:
1. Loads 4 bytes as a single `int` via `getIntLE()` — one memory access instead of 1-3
2. The `~wholeOrMore & 0x808080` trick isolates continuation flags for the first 3 bytes
3. `numberOfTrailingZeros` finds the stop byte position in O(1)
4. Bit masking extracts only the data bits, stripping continuation bits
5. Two shift-and-merge steps combine the 7-bit groups into a contiguous value

**Why 21-bit limit**: The frame length in Minecraft is max 3-byte VarInt (21 data bits). The `0x808080` mask only checks 3 continuation bytes. If none has MSB=0, the VarInt exceeds 21 bits.

### Slow-Path (`readRawVarintSmallBuf`)

Used when < 4 bytes are readable. Classic byte-by-byte approach with `markReaderIndex`/`resetReaderIndex` for incomplete reads:

```java
private static int readRawVarintSmallBuf(ByteBuf buffer) {
    if (!buffer.isReadable()) return 0;
    buffer.markReaderIndex();
    byte tmp = buffer.readByte();
    if (tmp >= 0) return tmp;
    int result = tmp & 0x7F;
    if (!buffer.isReadable()) { buffer.resetReaderIndex(); return 0; }
    if ((tmp = buffer.readByte()) >= 0) return result | tmp << 7;
    result |= (tmp & 0x7F) << 7;
    if (!buffer.isReadable()) { buffer.resetReaderIndex(); return 0; }
    if ((tmp = buffer.readByte()) >= 0) return result | tmp << 14;
    return result | (tmp & 0x7F) << 14;
}
```

**Key**: Returns 0 when insufficient bytes (sentinel value meaning "not enough data yet").

### Decode Loop

```java
protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    // 1. Early exit for dead channels (BungeeCord fix)
    if (!ctx.channel().isActive()) { in.clear(); return; }

    // 2. Skip leading null bytes (legacy ping probes)
    int packetStart = in.forEachByte(FIND_NON_NUL);
    if (packetStart == -1) {
        in.clear();
        // Serverbound: max 16 null bytes before kicking
        if (direction == SERVERBOUND && wlen > 16) throw INVALID_PREAMBLE;
        return;
    }
    in.readerIndex(packetStart);
    in.markReaderIndex();

    // 3. Read VarInt frame length
    int length = readRawVarInt21(in);
    if (packetStart == in.readerIndex()) return; // incomplete VarInt

    // 4. Validate
    if (length < 0) throw BAD_PACKET_LENGTH;

    // 5. Extract frame or wait for more data
    if (in.readableBytes() < length) {
        in.resetReaderIndex();
    } else {
        out.add(in.readRetainedSlice(length));
    }
}
```

### Velocity Design Choices

- **Extends ByteToMessageDecoder** (not ReplayingDecoder) — full control over buffer management
- **Uses `readRetainedSlice()`** — zero-copy frame extraction, shares underlying memory
- **Null-byte skipping** — handles legacy Minecraft ping probes (clients send 0xFE 0x01)
- **16-byte null limit** — prevents DoS via infinite null bytes from serverbound connections
- **Dead channel check** — same fix as BungeeCord PR #2908
- **Cached exceptions** — static final exception instances to avoid stack trace generation on hot error path
- **21-bit VarInt limit** — rejects frame lengths > 2,097,151 bytes at the frame level (before decompression)

### Known Issues

- **Issue #1370**: `CorruptedFrameException: Bad VarInt decoded` — transient buffer state during LOGIN phase with Forge modded servers. `readVarIntSafely()` returns `Integer.MIN_VALUE` as sentinel when buffer incomplete; a retry reads successfully. Indicates timing sensitivity in the LOGIN-phase packet decoder (not the frame decoder itself).
- **Issue #1556**: Strict validation of uncompressed packet sizes disconnects players using mods that intentionally send large uncompressed packets (Carpet-TIS-Addition). Fixed with `-Dvelocity.skip-uncompressed-packet-size-validation=true`.

---

## 3. BungeeCord Frame Decoder

### Class: `Varint21FrameDecoder extends ByteToMessageDecoder`

```java
protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (!ctx.channel().isActive()) {
        in.skipBytes(in.readableBytes());
        return;
    }
    in.markReaderIndex();
    final byte[] buf = new byte[3];  // <-- HEAP ALLOCATION every decode call

    for (int i = 0; i < buf.length; i++) {
        if (!in.isReadable()) {
            in.resetReaderIndex();
            return;
        }
        buf[i] = in.readByte();
        if (buf[i] >= 0) {
            // VarInt complete — decode it
            int length = DefinedPacket.readVarInt(Unpooled.wrappedBuffer(buf));
            if (length == 0) throw new CorruptedFrameException("Empty Packet!");
            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }
            if (in.hasMemoryAddress()) {
                out.add(in.readRetainedSlice(length));
            } else {
                // FALLBACK: copy to direct buffer
                ByteBuf dst = ctx.alloc().directBuffer(length);
                in.readBytes(dst);
                out.add(dst);
            }
            return;
        }
    }
    throw new CorruptedFrameException("length wider than 21-bit");
}
```

### Weaknesses

1. **`new byte[3]` on every decode call** — heap allocation in the hot path. Creates GC pressure under high packet rates. Velocity and Netty both avoid this with bitwise tricks.

2. **`Unpooled.wrappedBuffer(buf)` allocation** — creates a temporary ByteBuf wrapper just to decode the VarInt through `DefinedPacket.readVarInt()`. Double indirection: read bytes into array, wrap array, then decode from wrapper.

3. **No negative length check** — only checks `length == 0` but not `length < 0`. A carefully crafted 3-byte VarInt that decodes to a negative value would pass validation and cause `readRetainedSlice()` to throw `IllegalArgumentException`.

4. **Non-direct buffer fallback** — when `!in.hasMemoryAddress()`, it allocates a new direct buffer and copies. This is a safety net but adds overhead. The static `DIRECT_WARNING` flag only logs once, masking recurring issues.

5. **No null-byte handling** — unlike Velocity, no stripping of leading null bytes for legacy ping probes.

6. **Dead connection handling was a late addition** — PR #2908 added the `!ctx.channel().isActive()` check after discovering that BungeeCord processed packets from disconnected clients, causing 550% CPU usage under attack. The fix halved CPU usage.

7. **No max packet limit at frame level** — relies on downstream decoders to reject oversized packets. The frame decoder will happily allocate up to 2 MiB for a single frame.

---

## 4. Gate (Go) Frame Decoder

### Architecture

Gate uses Go's `io.Reader` abstraction instead of Netty's ByteBuf pipeline. This is a fundamentally different model: blocking reads on goroutines rather than event-driven callbacks.

### Frame Reading

```go
func readVarIntFrame(rd io.Reader) (payload []byte, n int, err error) {
    length, n, err := util.ReadVarIntReturnN(rd)
    if err != nil {
        return nil, n, fmt.Errorf("error reading varint: %w", err)
    }
    if length == 0 {
        return // skip empty packet
    }
    if length < 0 || length > 1048576 { // 2^20 = 1 MiB
        return nil, n, fmt.Errorf("received invalid packet length %d", length)
    }
    payload = make([]byte, length)
    m, err := rd.Read(payload)
    return payload, n + m, nil
}
```

### Key Design Choices

1. **`io.ReadFull` wrapper** — `fullReader` struct wraps the raw `io.Reader` to guarantee complete reads. This handles TCP fragmentation transparently.

2. **1 MiB frame limit** — more conservative than the protocol's 2 MiB maximum. Reduces memory exposure.

3. **`make([]byte, length)` allocation** — allocates a new byte slice for every frame. Go's garbage collector handles cleanup, but this means no buffer reuse between frames.

4. **Mutex-protected decode** — `sync.Mutex` serializes all decode operations. Thread-safe but prevents concurrent decoding on the same connection (which is correct — TCP is ordered).

5. **Empty packet retry** — retries up to 10 times on empty packets before returning an error.

6. **Compression validation** — checks `claimedUncompressedSize` against threshold and a hard `UncompressedCap`.

### Comparison to Netty-Based Proxies

| Aspect | Gate (Go) | Velocity/BungeeCord (Netty) |
|---|---|---|
| I/O model | Blocking reads on goroutines | Event-driven ByteToMessageDecoder |
| Buffer management | GC-managed `[]byte` slices | Reference-counted ByteBuf |
| TCP fragmentation | `io.ReadFull` handles it | Cumulation buffer handles it |
| Zero-copy | No — allocates per frame | Yes — `readRetainedSlice()` |
| Memory control | Go runtime manages | Direct/pooled buffers, explicit release |
| Frame limit | 1 MiB | 2 MiB (protocol max) |

---

## 5. Netty ProtobufVarint32FrameDecoder

Netty's built-in reference implementation for VarInt-framed protocols. Velocity's fast path is derived from this.

### `readRawVarint32` — The Canonical Fast Path

```java
static int readRawVarint32(ByteBuf buffer) {
    if (buffer.readableBytes() < 4) {
        return readRawVarint24(buffer); // slow path
    }
    int wholeOrMore = buffer.getIntLE(buffer.readerIndex());
    int firstOneOnStop = ~wholeOrMore & 0x80808080;
    if (firstOneOnStop == 0) {
        return readRawVarint40(buffer, wholeOrMore); // 5-byte VarInt
    }
    int bitsToKeep = Integer.numberOfTrailingZeros(firstOneOnStop) + 1;
    buffer.skipBytes(bitsToKeep >> 3);
    int thisVarintMask = firstOneOnStop ^ (firstOneOnStop - 1);
    int wholeWithContinuations = wholeOrMore & thisVarintMask;
    wholeWithContinuations = (wholeWithContinuations & 0x7F007F)
        | ((wholeWithContinuations & 0x7F007F00) >> 1);
    return (wholeWithContinuations & 0x3FFF)
        | ((wholeWithContinuations & 0x3FFF0000) >> 2);
}
```

**Key differences from Velocity's version**:
- Uses `0x80808080` (4 bytes) vs Velocity's `0x808080` (3 bytes) — Netty supports full 5-byte VarInt, Velocity limits to 3-byte (21-bit) for frame lengths
- Has a `readRawVarint40` path for 5-byte VarInts
- The bit manipulation logic is identical in principle

### Decode Method

```java
protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    in.markReaderIndex();
    int preIndex = in.readerIndex();
    int length = readRawVarint32(in);
    if (preIndex == in.readerIndex()) return; // incomplete VarInt
    if (length < 0) throw new CorruptedFrameException("negative length: " + length);
    if (in.readableBytes() < length) {
        in.resetReaderIndex();
    } else {
        out.add(in.readRetainedSlice(length));
    }
}
```

**Notable**: Netty's reference implementation explicitly checks `length < 0`. This is a security measure against negative-length attacks.

---

## 6. Common Bugs and Pitfalls

### 6.1 Split VarInt Across TCP Segments

**Problem**: A 3-byte VarInt frame length can arrive as 1 byte + 2 bytes across two TCP segments. If the decoder throws an error instead of buffering, connections break.

**How Velocity handles it**: `readRawVarintSmallBuf()` returns 0 (sentinel) when insufficient bytes. The decode loop detects `packetStart == in.readerIndex()` and returns without output, letting ByteToMessageDecoder accumulate more data.

**How BungeeCord handles it**: `in.markReaderIndex()` / `in.resetReaderIndex()` — marks before read, resets if incomplete.

**Warp approach**: Use the same pattern. The ByteToMessageDecoder cumulation buffer automatically handles this. Return without modifying reader index or output list, and the framework will call decode() again when more data arrives.

### 6.2 Oversized Packet Attacks (DoS)

**Attack**: Send a frame length VarInt claiming the packet is 2,097,151 bytes (max 3-byte VarInt). The decoder allocates that much memory but the data never arrives, or the attacker sends many such frames.

**Mitigations**:
- Frame-level size limit (Velocity enforces at 2^21 - 1, Gate uses 2^20)
- Connection-level memory quota
- Read timeout — close connection if full frame doesn't arrive within N seconds
- Rate limiting — max frames per second per connection

### 6.3 Buffer Leak Patterns

**Pattern 1**: `readBytes(length)` creates a new buffer. If an exception occurs before it's added to the output list or released, it leaks.

**Pattern 2**: Decoder allocates decompression buffer, compression fails mid-way, buffer not released in catch/finally.

**Pattern 3**: ByteToMessageDecoder cumulation buffer not released on channel close.

**Mitigation**: Always use try/finally for buffer lifecycle:
```java
ByteBuf frame = in.readRetainedSlice(length);
try {
    // process
    out.add(frame);
} catch (Exception e) {
    frame.release();
    throw e;
}
```

Or better: use `readRetainedSlice()` and add directly to output list (Netty handles lifecycle).

### 6.4 Negative Length Attacks

**Attack**: Craft a VarInt that decodes to a negative 32-bit value. If the decoder passes this to `readBytes(length)` or `readRetainedSlice(length)`, Netty throws `IllegalArgumentException` — but the damage may be done (exception handling overhead, potential buffer state corruption).

**Mitigations**:
- Check `length < 0` immediately after VarInt decode (Netty reference does this)
- Use 21-bit VarInt limit at frame level (naturally prevents most negative values since 3-byte VarInts can only encode up to 2^21 - 1)
- However: a malformed 3-byte VarInt with specific bit patterns could still produce unexpected values — validate bounds strictly

### 6.5 Compression Bomb Attacks

**Attack**: Send a small compressed payload (< 2 MiB wire) that decompresses to a massive buffer (hundreds of MBs). Since the attacker controls the `Data Length` VarInt, they can claim any uncompressed size.

**Protocol limits**:
- Serverbound: vanilla server limits decompressed to 8 MiB
- Clientbound: **vanilla client has NO limit** (!)

**Velocity's defense**:
```java
static final int VANILLA_MAXIMUM_UNCOMPRESSED_SIZE = 8 * 1024 * 1024;  // 8 MiB
static final int HARD_MAXIMUM_UNCOMPRESSED_SIZE = 128 * 1024 * 1024;   // 128 MiB
// Configurable via system property
checkFrame(claimedUncompressedSize <= UNCOMPRESSED_CAP, ...);
```

**Additional defenses needed**:
- Validate that actual decompressed size matches `claimedUncompressedSize`
- Use incremental decompression with a hard byte limit — stop decompressing if output exceeds claimed size
- Set a ratio limit (e.g., max 100:1 compression ratio)

### 6.6 Dead Connection Packet Processing

**Attack**: Flood connections that disconnect immediately. The proxy continues processing buffered packets from dead connections, wasting CPU on exception generation.

**BungeeCord PR #2908 impact**: CPU dropped from 550% to 290% just by adding `!ctx.channel().isActive()` check.

**Warp approach**: Check channel activity as the very first operation in `decode()`.

---

## 7. Netty ByteToMessageDecoder vs ReplayingDecoder

### ByteToMessageDecoder (RECOMMENDED)

**Pros**:
- Full control over buffer reading and position management
- Predictable performance — no implicit replaying/retrying
- Supports two cumulation strategies (MERGE and COMPOSITE)
- Can decode multiple messages per `channelRead` call
- Industry standard for high-performance decoders

**Cons**:
- Must manually check `readableBytes()` before reading
- Must manually manage `markReaderIndex()`/`resetReaderIndex()` for incomplete reads

### ReplayingDecoder

**Pros**:
- Simpler code — reads as if all bytes are available
- Automatic retry on incomplete data via thrown Error

**Cons**:
- **Replays entire decode on incomplete reads** — can re-decode the same prefix repeatedly on slow networks
- Uses a special `ReplayingDecoderByteBuf` wrapper — overhead on every read
- Throws and catches Error objects (even if cached, the control flow is expensive)
- Not suitable for hot paths with variable-length prefixes

### Cumulation Strategies

**MERGE_CUMULATOR** (default):
- Copies new data into existing cumulation buffer
- Simple indexing — fast sequential reads
- Allocates/expands buffer when needed
- Best for: most use cases, especially when frames are typically complete in one read

**COMPOSITE_CUMULATOR**:
- Wraps buffers in a `CompositeByteBuf` — no memory copy
- Complex indexing — each `getByte()` must search component list
- Best for: large messages that arrive in many small fragments
- Worse for: small, frequent messages where copy overhead is minimal

**Recommendation for Warp**: Use MERGE_CUMULATOR (default). Minecraft frames are typically small (most packets are < 1 KiB) and usually arrive complete. The simpler indexing outweighs the occasional copy.

### Critical Threading Rule

ByteToMessageDecoder instances must NOT be `@Sharable`. Each connection needs its own instance because the cumulation buffer is stateful.

---

## 8. Performance Optimizations

### 8.1 Zero-Copy Frame Extraction

**`readRetainedSlice(length)`** — the gold standard. Creates a derived buffer that:
- Shares the same underlying memory (no copy)
- Increments reference count (prevents premature deallocation)
- Produces less GC garbage than `readSlice().retain()`
- Advances the reader index atomically

Used by: Velocity, Netty reference, and should be used by Warp.

**Do NOT use `readBytes(length)`** — allocates a new buffer and copies. Use only when you need an independent copy (e.g., for background processing).

### 8.2 Fast VarInt Reading (Batch Read)

The `getIntLE()` + bitwise trick (from Netty, adopted by Velocity):
- **One memory access** instead of 1-3 sequential byte reads
- **Branchless stop detection** via `~value & 0x808080`
- **Branchless byte count** via `numberOfTrailingZeros`
- **Parallel bit extraction** via shift-and-merge

For 21-bit VarInts (frame lengths), this is always a single `getIntLE()` + ~6 arithmetic ops.

### 8.3 Fixed-Size 3-Byte VarInt Encoding (Frame Encoder)

The Minecraft spec allows overlong VarInt encodings up to 3 bytes. This means the frame encoder can ALWAYS write a 3-byte VarInt for the length prefix, regardless of actual value:

```java
// Velocity's approach in MinecraftCompressorAndLengthEncoder
// Always reserves 3 bytes, writes with encode21Bit
buf.writeMedium(VarInt.encode21Bit(length));
```

**Benefits**:
- **No buffer shift needed** — length prefix size is known upfront
- **Single `writeMedium()` call** — one JNI/native call instead of 1-3 `writeByte()`
- **Simplifies buffer pre-allocation** — always `payload_size + 3`

This is valid because the spec states: "Unnecessarily long encodings at 3 bytes or below are still allowed."

### 8.4 Cached Exception Instances

```java
private static final DecoderException BAD_VARINT = new DecoderException("Bad VarInt") {
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // no-op — eliminates stack trace generation
    }
};
```

Stack trace generation is expensive (~1-2 microseconds). On error hot paths (invalid client connections, attacks), this adds up. Cached exceptions with no-op `fillInStackTrace()` reduce this to nanoseconds.

**Warp already does this** in `VarInt.java`.

### 8.5 Direct Buffer Preference

- Use direct (off-heap) buffers for network I/O — avoids JNI boundary copy
- Use heap buffers only when Java cipher/compression needs direct byte[] access
- Velocity conditionally selects based on `IS_JAVA_CIPHER`
- Warp's JNI natives should work with direct buffers natively

### 8.6 Avoid Allocations in Decode Loop

Per-decode allocations to avoid:
- `new byte[N]` arrays (BungeeCord's weakness)
- `Unpooled.wrappedBuffer()` (BungeeCord's weakness)
- `new Exception()` with stack traces
- Lambda captures or boxing

The decode loop runs for EVERY packet on EVERY connection. At 1000 players with 20 packets/tick, that's 400,000 decode calls per second.

### 8.7 Compression-Specific Optimizations

- **Pre-allocate decompression buffer** to `claimedUncompressedSize` — avoids dynamic expansion
- **Reuse compressor/decompressor instances** — JNI native z_stream is expensive to allocate
- **Threshold tuning** — default 256 is conservative; higher thresholds reduce CPU at cost of bandwidth
- **Skip compression for blind-forwarded packets** — if a packet is being forwarded without inspection, don't decompress/recompress (Warp's core innovation)

---

## 9. Design Decisions for Warp

Based on this research, Warp's frame codec should:

### Frame Decoder (`MinecraftFrameDecoder`)

1. **Extend `ByteToMessageDecoder`** with `MERGE_CUMULATOR` (default)
2. **Use the Netty/Velocity fast-path VarInt trick** — `getIntLE()` + `~value & 0x808080` for 21-bit frame lengths
3. **Use `readRetainedSlice()`** for zero-copy frame extraction
4. **Check `!ctx.channel().isActive()` first** — dead channel guard
5. **Reject negative lengths** — explicit `length < 0` check
6. **Reject zero lengths** — empty frames are invalid
7. **21-bit VarInt limit** enforced by the bitwise trick itself
8. **Handle split VarInts** — return without output when incomplete, let cumulation buffer accumulate
9. **Cached static exceptions** with no-op `fillInStackTrace()`
10. **No leading null-byte handling** — defer to a separate legacy ping handler if needed

### Frame Encoder (`MinecraftFrameEncoder`)

1. **Always write 3-byte overlong VarInt** via `VarInt.encode21Bit()` + `writeMedium()`
2. **Single buffer output** — prepend length directly (or use `CompositeByteBuf` if encoder output goes directly to transport)
3. **Check channel activity before encoding** — skip work for dead connections

### Compression Decoder (`MinecraftCompressionDecoder`)

1. **Extend `MessageToMessageDecoder<ByteBuf>`** — operates on already-framed packets
2. **Read `Data Length` VarInt** — if 0, pass through uncompressed (retain + add to out)
3. **Validate `Data Length` against threshold** — reject compressed-below-threshold
4. **Hard cap on decompressed size** — 8 MiB default, configurable up to 128 MiB
5. **Pre-allocate decompression buffer** to `claimedUncompressedSize`
6. **Try/finally for buffer lifecycle** — release on error
7. **Use Warp's JNI natives** for zlib/zstd decompression

### Compression Encoder (`MinecraftCompressionEncoder`)

1. **Reserve 3 bytes** for frame length (written after compression)
2. **Write `Data Length` VarInt** — 0 if below threshold, actual size if compressed
3. **Compress with JNI natives**
4. **Backfill frame length** with `encode21Bit()` + `setMedium()`
5. **Skip compression** for blind-forwarded packets (Warp's key optimization)

---

## Sources

- [Velocity GitHub Repository](https://github.com/PaperMC/Velocity)
- [Velocity MinecraftVarintFrameDecoder](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftVarintFrameDecoder.java)
- [Velocity Issue #1370 — Bad VarInt decoded](https://github.com/PaperMC/Velocity/issues/1370)
- [Velocity Issue #1556 — Uncompressed packet size validation](https://github.com/PaperMC/Velocity/issues/1556)
- [Velocity PR #631 — Missing check discussion](https://github.com/PaperMC/Velocity/pull/631)
- [BungeeCord Varint21FrameDecoder](https://github.com/SpigotMC/BungeeCord/blob/master/protocol/src/main/java/net/md_5/bungee/protocol/Varint21FrameDecoder.java)
- [BungeeCord PR #2908 — Dead connection fix](https://github.com/SpigotMC/BungeeCord/pull/2908)
- [Gate GitHub Repository](https://github.com/minekube/gate)
- [Minecraft Protocol Specification](https://minecraft.wiki/w/Java_Edition_protocol/Packets)
- [wiki.vg Protocol Reference](https://c4k3.github.io/wiki.vg/Protocol.html)
- [Minecraft Vulnerability Advisory](https://blog.ammaraskar.com/minecraft-vulnerability-advisory/)
- [Netty ByteToMessageDecoder API](https://netty.io/4.1/api/io/netty/handler/codec/ByteToMessageDecoder.html)
- [Netty ReplayingDecoder API](https://netty.io/4.1/api/io/netty/handler/codec/ReplayingDecoder.html)
- [Netty ProtobufVarint32FrameDecoder](https://github.com/netty/netty/blob/4.1/codec/src/main/java/io/netty/handler/codec/protobuf/ProtobufVarint32FrameDecoder.java)
- [Velocity Compression and Network Optimization](https://deepwiki.com/PaperMC/Velocity/6.2-compression-and-network-optimization)
- [Netty Codec Framework](https://deepwiki.com/netty/netty/5.1-codec-framework)
