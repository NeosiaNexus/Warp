# Velocity Packet Architecture Deep Dive

Research date: 2026-04-01
Source: PaperMC/Velocity (GitHub, main branch clone)

---

## 1. Packet Definitions: Mutable Classes with Interface Contract

Every packet implements `MinecraftPacket`, a simple interface:

```java
public interface MinecraftPacket {
    void decode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion);
    void encode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion);
    boolean handle(MinecraftSessionHandler handler);
    // Optional size-hint methods with defaults:
    default int decodeExpectedMaxLength(...) { return -1; }
    default int decodeExpectedMinLength(...) { return 0; }
    default int encodeSizeHint(...) { return -1; }
}
```

**All packets are mutable classes** -- not records, not sealed interfaces. They use
getters/setters on mutable fields (`private String serverAddress; setServerAddress(...)`).
No records are used anywhere in the packet system.

Empty-body packets use a singleton pattern:
`StatusRequestPacket.INSTANCE`, `FinishedUpdatePacket.INSTANCE`, `BundleDelimiterPacket.INSTANCE`

Packets that carry raw payload extend `DeferredByteBufHolder` (a custom `ByteBufHolder`
that can accept its buffer lazily), e.g., `PluginMessagePacket`, `RegistrySyncPacket`.
This forces those packets to manage reference counting manually (`retain()`, `release()`,
`copy()`, `duplicate()`).

---

## 2. Organization by State and Direction

### States

`StateRegistry` is an **enum** with 5 values: `HANDSHAKE`, `STATUS`, `CONFIG`, `PLAY`, `LOGIN`.

Each enum value has two `PacketRegistry` instances:
```java
protected final PacketRegistry clientbound = new PacketRegistry(CLIENTBOUND, this);
protected final PacketRegistry serverbound = new PacketRegistry(SERVERBOUND, this);
```

### Direction

`Direction` is a trivial enum nested inside `ProtocolUtils`:
```java
public enum Direction { SERVERBOUND, CLIENTBOUND }
```

### Package structure

All packets live under one flat package: `proxy.protocol.packet.*`
with a few subdirectories for complex subsystems:
- `packet/chat/` (legacy, keyed, session chat variants)
- `packet/chat/builder/`
- `packet/config/` (configuration state packets)
- `packet/title/` (title variants)
- `packet/brigadier/` (command argument serializers)
- `packet/legacyping/`

There is **no organization by state or direction** in the package structure.
A `DisconnectPacket` is registered in both LOGIN and CONFIG clientbound,
and a `PluginMessagePacket` is registered in PLAY, CONFIG, both directions.
You cannot tell from the file alone which state or direction a packet belongs to.

### Registration style

Packets are registered in instance initializer blocks of each enum constant:
```java
HANDSHAKE {
    {
        serverbound.register(HandshakePacket.class, HandshakePacket::new,
            map(0x00, MINECRAFT_1_7_2, false));
    }
},
PLAY {
    {
        serverbound.fallback = false;
        clientbound.fallback = false;
        serverbound.register(TabCompleteRequestPacket.class, TabCompleteRequestPacket::new,
            map(0x14, MINECRAFT_1_7_2, false),
            map(0x01, MINECRAFT_1_9, false),
            // ... 11 more version mappings
            map(0x0F, MINECRAFT_26_1, false));
    }
}
```

### Stats

- **92 register() calls** across all states
- **517 map() calls** for version-specific ID mappings
- **109 Java files** under the packet directory (includes helpers, not all are packets)
- **67 handle() overloads** in MinecraftSessionHandler

---

## 3. Codec Architecture: Inline in Packet Classes

**Codecs are NOT separated from packet definitions.** Each packet class implements
`decode()` and `encode()` directly. The protocol version is passed as a parameter,
and each packet uses version branching internally:

```java
// KeepAlivePacket.decode()
if (version.noLessThan(MINECRAFT_1_12_2)) {
    randomId = buf.readLong();
} else if (version.noLessThan(MINECRAFT_1_8)) {
    randomId = ProtocolUtils.readVarInt(buf);
} else {
    randomId = buf.readInt();
}
```

Complex packets split into version-specific sub-methods:
```java
// JoinGamePacket.decode()
if (version.noLessThan(MINECRAFT_1_20_2)) {
    this.decode1202Up(buf, version);
} else if (version.noLessThan(MINECRAFT_1_16)) {
    this.decode116Up(buf, version);
} else {
    this.decodeLegacy(buf, version);
}
```

`ProtocolUtils` is a utility enum (zero instances) providing static helpers:
`readVarInt`, `writeVarInt`, `readString`, `writeString`, `readCompoundTag`,
`readStringArray`, etc. Also contains GsonComponentSerializer instances for
different version ranges.

### Netty Pipeline

- **MinecraftDecoder** (`ChannelInboundHandlerAdapter`): reads VarInt packet ID,
  looks up `registry.createPacket(id)`, calls `packet.decode()`, fires result.
  If packet ID is unknown: in PLAY state, fires raw ByteBuf; in other states, throws.
- **MinecraftEncoder** (`MessageToByteEncoder<MinecraftPacket>`): looks up packet ID
  via `registry.getPacketId(msg)`, writes VarInt ID, calls `packet.encode()`.
  Has `allocateBuffer()` override using `encodeSizeHint()` for better allocation.

---

## 4. Packet ID Registry System

### Architecture

```
StateRegistry (enum: HANDSHAKE, STATUS, CONFIG, PLAY, LOGIN)
  └── PacketRegistry (per direction: clientbound, serverbound)
       └── Map<ProtocolVersion, ProtocolRegistry>
            └── ProtocolRegistry (per version)
                 ├── IntObjectMap<Supplier<MinecraftPacket>>  packetIdToSupplier
                 └── Object2IntMap<Class<MinecraftPacket>>    packetClassToId
```

Each `ProtocolRegistry` holds two maps:
- **ID -> Supplier**: `IntObjectHashMap` (Netty) for decoding (packet ID -> packet factory)
- **Class -> ID**: `Object2IntOpenHashMap` (fastutil) for encoding (packet class -> packet ID)

### Registration mechanics

`PacketMapping` is a record-like class with: `(int id, ProtocolVersion version,
ProtocolVersion lastValidVersion, boolean encodeOnly)`.

The `register()` method iterates over mappings and fills each `ProtocolRegistry`
for the version range [from, next). If `encodeOnly=true`, the ID->Supplier map
is NOT populated (packet can be sent but not received/decoded). 146 out of 517
mappings use `encodeOnly=true`.

The `fallback` flag controls what happens for unknown packet IDs:
- HANDSHAKE, STATUS, CONFIG, LOGIN: `fallback = true` (default), unknown IDs
  fall back to MINIMUM_VERSION registry
- PLAY: `fallback = false`, unknown IDs result in raw ByteBuf passthrough

### Lookup path (decoding)

```
MinecraftDecoder.channelRead(ByteBuf)
  -> readVarInt(buf) to get packetId
  -> this.registry.createPacket(packetId)  // ProtocolRegistry lookup
  -> if null: fire raw ByteBuf (PLAY) or throw (other states)
  -> if found: packet.decode(buf, direction, version)
  -> fire decoded MinecraftPacket
```

---

## 5. Packet Dispatch: Visitor Pattern via Session Handler

`MinecraftConnection.channelRead()` dispatches:
```java
if (msg instanceof MinecraftPacket pkt) {
    if (!pkt.handle(activeSessionHandler)) {    // visitor dispatch
        activeSessionHandler.handleGeneric(pkt); // fallback for unhandled
    }
} else if (msg instanceof ByteBuf buf) {
    activeSessionHandler.handleUnknown(buf);     // raw passthrough
}
```

`MinecraftSessionHandler` is an interface with **67 overloaded `handle()` methods**,
one per packet type, all returning `boolean` with default `return false`.

Each packet calls the right overload:
```java
// HandshakePacket
public boolean handle(MinecraftSessionHandler handler) {
    return handler.handle(this);
}
```

The session handler implementations (e.g., `BackendPlaySessionHandler`,
`ClientPlaySessionHandler`) override only the handle() methods they care about.

For PLAY state, unhandled packets AND unknown ByteBufs are forwarded to the
opposite side:
```java
// BackendPlaySessionHandler
public void handleGeneric(MinecraftPacket packet) {
    playerConnection.delayedWrite(packet);
}
public void handleUnknown(ByteBuf buf) {
    playerConnection.delayedWrite(buf.retain());
}
```

---

## 6. Protocol Version Handling

`ProtocolVersion` is an **enum in the API module** with every known protocol version
from 1.7.2 to 26.1 (Minecraft snapshot naming). Each value holds:
- Protocol number (e.g., `47` for 1.8, `767` for 1.21)
- Human-readable version strings

Comparison via `Ordered<ProtocolVersion>` interface with methods like
`noLessThan()`, `lessThan()`, `noGreaterThan()`.

The version enum is used at three levels:
1. **Registration**: `map(0x00, MINECRAFT_1_7_2, false)` binds IDs to version ranges
2. **Codec branching**: `if (version.noLessThan(MINECRAFT_1_16))` inside decode/encode
3. **Registry selection**: `MinecraftDecoder` holds a `ProtocolRegistry` selected at
   connection time via `setProtocolVersion()`

---

## 7. Known Weaknesses and Criticisms

### 7.1 Mutable Packets (Major)

All packets are mutable classes with getters/setters. This is acknowledged as problematic
even in Velocity's own code (`DeferredByteBufHolder` javadoc: "Velocity packets are, for
better or worse, mutable"). Implications:
- No thread safety -- packets can be modified between creation and encoding
- Cannot share packet instances across connections
- Makes reasoning about packet state difficult
- Reference counting on `PluginMessagePacket` leads to subtle leaks

### 7.2 Monster StateRegistry File (Major)

`StateRegistry.java` is **1145 lines** in a single enum with instance initializers.
517 `map()` calls hardcoded. Every version bump requires touching this one file,
adding lines to many packet registrations. Extremely error-prone; a single wrong
packet ID in one version mapping is difficult to spot in review.

### 7.3 God Interface: MinecraftSessionHandler (Major)

67 packet-specific `handle()` overloads in a single interface. Every new packet type
requires adding a method here AND in every implementation. This is the textbook
"fat interface" anti-pattern. It tightly couples packet definitions to the handler
interface.

### 7.4 Version Branching in Codecs (Moderate)

`JoinGamePacket` has **3 separate decode methods and 3 encode methods** for different
version ranges, with nested version checks inside each. The branching is manual,
imperative, and duplicated between encode and decode. Adding a new version means
finding every `if (version.noLessThan(...))` in every affected packet.

### 7.5 No Packet-Level Separation by State or Direction (Moderate)

`PluginMessagePacket` is used in PLAY serverbound, PLAY clientbound, CONFIG serverbound,
CONFIG clientbound. `DisconnectPacket` takes a `StateRegistry` in its constructor to
know which state it is in. You cannot know from the type alone which direction or
state a packet belongs to. This makes the type system weaker than it could be.

### 7.6 encodeOnly Flag Complexity (Minor)

146 mappings are `encodeOnly=true` -- packets the proxy can send but won't decode.
This is a workaround for the fact that Velocity registers all proxy-relevant
packets in a global registry, even for packets it only needs to send but never
receives. The flag adds cognitive overhead to every mapping.

### 7.7 Shallow Passthrough Still Decompresses (Moderate)

Even for "unknown" PLAY packets that pass through as raw ByteBuf, the data has
already been through `MinecraftCompressDecoder` (decompression) and
`MinecraftVarintFrameDecoder` (framing). The ByteBuf is re-compressed on the
outgoing side. This is not blind forwarding -- it is decompress-then-recompress
passthrough.

### 7.8 Packet Instantiation on Hot Path (Minor)

Every decoded packet calls `packetSupplier.get()` -- creating a new mutable
object. For packets like `KeepAlivePacket` that could be lightweight value types,
this is unnecessary allocation. Singleton pattern is used only for truly empty
packets (`StatusRequestPacket.INSTANCE`).

### 7.9 No Compile-Time Safety on Registration (Minor)

Nothing prevents registering a packet in the wrong state or direction. The
registration is purely imperative code in instance initializers. A `HandshakePacket`
could accidentally be registered in PLAY clientbound and the compiler would not
catch it.

---

## 8. What Warp Can Do Better

Based on this analysis, opportunities for Warp:

1. **Immutable packets as records** -- Java 21 records with colocated codec
2. **Separate codec from definition** -- packet is data, codec is behavior
3. **Type-safe state/direction encoding** -- `PlayClientbound`, `LoginServerbound`
   as distinct registration contexts
4. **True blind forwarding** -- skip decompression entirely for unregistered packets
5. **Sealed packet hierarchies** -- pattern matching dispatch instead of visitor
6. **Decentralized registration** -- packets self-register or use annotation processing
   instead of one monster file
7. **Version-stratified codecs** -- codec-per-version-range objects instead of
   branching inside a single method
8. **Zero-allocation for simple packets** -- flyweight/pooling for high-frequency packets
