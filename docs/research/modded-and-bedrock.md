# Modded clients and Bedrock: Forge, NeoForge, Geyser and Floodgate through Warp

> **Research cycle (2026-11-09): modded and Bedrock.** Research and design only: nothing outside this
> file was changed. Sources: the wiki.vg Forge handshake page (now on minecraft.wiki), Forge's
> `NetworkContext` (1.20.x) and `FMLNetworkConstants` (1.16.x), NeoForge's `NetworkRegistry`
> (1.21.x), Velocity's `forge/` package and `HandshakeSessionHandler` (`dev/3.0.0`), BungeeCord's
> `ForgeConstants`, the Ambassador and Proxy-Compatible-Forge READMEs, the Geyser and Floodgate wikis,
> Geyser's `GeyserVelocityInjector` and `LocalSession`, Floodgate's `VelocityInjector`, Gate's Go
> port of the Floodgate protocol, the HeadlessMC and mc-runtime-test READMEs, and the Forge, NeoForge,
> Bedrock and Floodgate threads of the Velocity, Geyser and Floodgate trackers (38 sources, listed at
> the end). Warp's `main` at `e699d6e` and PR #179 (routes).
>
> Markers: **[verified]** read in source, a thread or an official page during this research;
> **[unverified]** reported by a third party or not checked; **[estimate]** a size or number to
> measure before relying on it. Issue references: `V#n` Velocity, `G#n` Geyser, `F#n` Floodgate,
> `Gate#n` Gate.
>
> **Outcome:** a design with its decisions taken (§7), a phased plan (§8) and a test plan (§9).
> Implementation is separate work. No Java code was written.

---

## 0. TL;DR

**Findings**

1. **Forge is three protocols, not one, and Velocity supports the first and the last.** 1.7 to 1.12
   negotiate on the `FML|HS` plugin channel in PLAY, with a `\0FML\0` hostname marker; 1.13 to 1.20.1
   negotiate in LOGIN with login plugin messages wrapped in `fml:loginwrapper`, marker `\0FML2\0`; from
   1.20.2 Forge negotiates in the CONFIGURATION phase with marker `\0FORGE[n]`, and NeoForge negotiates
   there too, with no hostname marker at all [verified]. Velocity handles legacy FML (state machine and
   reset packet) and detects the 1.20.2+ token; 1.13 to 1.20.1 fall through as "vanilla" [verified].
   The 1.13 to 1.20.1 gap stayed open for seven years (V#248, PRs V#690 and V#932 never merged, the
   community uses Ambassador plus a client mod).
2. **Warp today breaks every Forge version**, in two places [verified in Warp's source]: the backend
   handshake is rebuilt with the backend's address, so the marker never reaches the backend, and every
   login plugin request that is not `velocity:player_info` is answered "not understood". A Forge
   backend therefore sees a vanilla client and refuses it, or its `fml:loginwrapper` queries fail.
3. **1.20.2+ is almost free for Warp.** Warp switches servers through the configuration phase, which
   resets the client, and blind-forwards CONFIGURATION packets: the new backend re-negotiates mods and
   registries with the client directly. What remains is forwarding the marker, not capping what
   Velocity capped (V#1404 known packs, V#1556 uncompressed size) and tests.
4. **1.13 to 1.20.1 switching has no clean answer without a client mod**, which is why Velocity never
   shipped one (V#922 maintainer comment). Ambassador shows the workable middle: replay the client's
   recorded login-phase answers to the new server when the mod set matches, and only then move the
   client [verified in README, mechanism inferred]. Warp adopts that and does not ship a client mod.
5. **Geyser on a proxy works by reflection into private fields** (`cm`, `serverChannelInitializer`,
   `workerGroup`) to open a Netty `LocalChannel` into the proxy's own pipeline, with the real address
   spoofed and port 0 [verified in `GeyserVelocityInjector` and `LocalSession`]. Floodgate does the
   same. Every Velocity refactor breaks both (G#6679, F#645), and the "port 0" convention became the
   bypassable anti-bot heuristic documented in `anti-bot.md`.
6. **Floodgate's wire format is small and documented by two independent implementations**: an AES-GCM
   blob with a `^Floodgate^` header, Base64, appended to the handshake hostname after a `\0`, 12
   NUL-separated fields (version, username, xuid, device OS, language, UI profile, input mode, IP,
   linked player, from-proxy flag, subscribe id, verify code) [verified in Gate's port, cipher
   constants verified, field semantics from Gate's comments]. Gate implements it natively in Go.
7. **The recurring Bedrock complaints behind a proxy are identity, not transport**: backends do not
   see Bedrock players without Floodgate on every backend plus `send-floodgate-data` (G#6533), empty
   profiles trigger session-server lookups on every switch (F#508), skins depend on the Global API
   (F#182, F#147), strict name patterns reject every `.name` (V#1827).

**Decisions** (details in §7)

- **D1** Warp handles Forge and NeoForge natively for all three eras, with one small state object per
  connection; no companion mod is shipped, PCF stays the backend forwarding mod for Forge servers.
- **D2** Everything stays blind except two things Warp reads: the `fml:loginwrapper` login queries
  (1.13 to 1.20.1) and the `FML|HS` discriminator byte (1.7 to 1.12). Mod lists, registries and
  config data are never deserialized.
- **D3** Switching: 1.20.2+ through the configuration phase as today; 1.7 to 1.12 with the FML reset
  packet; 1.13 to 1.20.1 by replaying the recorded login answers when the new server's queries match,
  otherwise a clear kick naming the server to reconnect to.
- **D4** Bedrock: Floodgate's protocol implemented natively in Warp (read, verify, forward), a public
  `LocalConnections` API so Geyser injects sessions without reflection, and a `warp-geyser` plugin in
  the Warp repository that uses it. Standalone Geyser keeps working with the PROXY protocol.
- **D5** One `ClientPlatform` on every connection (Java, Forge, NeoForge, Bedrock) drives routing,
  anti-bot exemptions that require proof, name validation and what is forwarded to backends.

---

## 1. Where Warp stands

Facts read in Warp's source on `main` and PR #179.

- `HandshakeSessionHandler` / `WarpInboundConnection.setVirtualHost` keep the raw server address and
  expose a cleaned `virtualHost()`: lowercase, no port, no trailing dot, `\0FML\0`, `\0FML2\0`,
  `\0FML3\0` and BungeeCord payloads removed (PR #179, `HostRoutes.clean`). Routes match the clean
  host. The raw address is kept but not used for anything Forge-related.
- `BackendLoginSessionHandler.channelActive` writes `Handshake(protocol, backendHost, backendPort,
  2)`: the marker is lost. Forge 1.20.2+ reads the marker from the intention's host name and sets the
  connection to `VANILLA` when absent (`NetworkContext.processIntention`), so a Forge backend
  treats every player coming through Warp as vanilla. NeoForge does not use a marker, so NeoForge
  backends are unaffected by this particular bug.
- `BackendLoginSessionHandler.handleLoginPluginRequest` answers `velocity:player_info` and replies
  `LoginPluginResponse(id, false, null)` to every other channel. `fml:loginwrapper` queries are
  therefore refused on the proxy and never reach the client. Forge's wiki page says each request
  "needs exactly one reply, or login is rejected": the backend disconnects the player.
- `LoginSessionHandler` ignores `LoginPluginResponse` from the client and never sends a request.
- `ForwardingResponder` sits under plugin handlers and answers the forwarding query at frame level
  when plugins read backend packets; it passes every other frame on, so a relay of `fml:loginwrapper`
  can live beside it.
- `ServerSwitch` is sealed: `ReconfigurationSwitch` from 1.20.2 (logs in to the new backend while the
  player keeps playing, then `StartConfiguration`, the client resets, the new backend configures it),
  `RespawnSwitch` before 1.20.2 (Join Game + Respawn, proxy clears leftovers). `ClientConfigSessionHandler`
  relays configuration packets to the backend and only acts on `AcknowledgeFinishConfiguration`.
- `PlayPluginMessage` already reads Forge's three-byte 1.7 payload length ("extended Forge short").
- Forwarding modes are `NONE` and `VELOCITY` (`ForwardingMode`); `PlayerForwarding` builds version 1
  of Velocity's payload (address, UUID, name, properties).
- API: `InboundConnection` has `remoteAddress`, `virtualHost`, `protocolVersion`, `isActive`,
  `isTransferred` and (PR #179) the matched route. `dev.warp.api.network` exposes pipelines to plugins
  (`NetworkPipelines`, `ClientPipeline`, `PipelineInitializer`). `dev.warp.api.antibot` exposes the
  anti-bot and its verified identities.
- `anti-bot.md` §10 rules 5 to 7 and 11 already commit to: exemptions require proof (PROXY protocol
  from a trusted Geyser host, Floodgate's signed data), Bedrock skips the world check only, modded
  listeners disable the world check, the FML marker exempts nothing, a configurable Floodgate prefix
  in offline mode.
- e2e: mineflayer bots from 1.8.8, raw `minecraft-protocol` for 1.7, Paper or vanilla backends,
  ViaProxy for translation runs, a probe plugin per backend, Warp's own e2e plugin. No modded or
  Bedrock client.

---

## 2. Forge and NeoForge, era by era

### 2.1 FML, 1.7 to 1.12 (`\0FML\0`, `FML|HS` in PLAY)

[verified: minecraft.wiki Forge handshake page, BungeeCord `ForgeConstants`, Velocity
`LegacyForgeConstants` and `LegacyForgeHandshakeClientPhase`]

- The client appends `\0FML\0` to the handshake address. The server registers the channels `FML|HS`,
  `FML`, `FML|MP`, `FORGE`. The handshake runs in PLAY on `FML|HS`, discriminator first byte:
  ServerHello 0, ClientHello 1, ModList 2, RegistryData 3, HandshakeAck -1, HandshakeReset -2.
- Sequence: server ServerHello, client ClientHello and ModList, server ModList, client Ack
  (WAITINGSERVERDATA), server RegistryData until `hasMore` is false, then an Ack exchange. The wiki
  marks the final steps as "unexplained"; both proxies model the client side as NOT_STARTED, HELLO,
  MOD_LIST, WAITING_SERVER_DATA, WAITING_SERVER_COMPLETE, PENDING_COMPLETE, COMPLETE, each phase
  advancing on one discriminator.
- HandshakeReset (`FML|HS` payload `FE 00`) is never sent by a Forge server: it exists for proxies.
  The client redoes the whole handshake. Velocity sends it to a COMPLETE client when the next server is
  modded, and not when the next server is vanilla; BungeeCord defines the same constant.
- Velocity treats a 1.7.6 or older client as `UNDETERMINED_17` and sends the reset on every
  transition (code comment, [unverified]).
- Pitfalls: Velocity's legacy Join Game decoder sign-extended the dimension id and broke modded 1.7.10
  (V#1859, fixed 2026). Legacy and BungeeGuard forwarding lost the FML token (V#710, open since 2022;
  PR V#1785 open). Warp has no BungeeGuard mode, so only the first applies: `RespawnSwitch` reads
  Join Game and Respawn on 1.7 and must read the dimension as a signed byte or int as the version
  dictates. [to check in Warp's `JoinGame` codec during implementation]

### 2.2 FML2, 1.13 to 1.20.1 (`\0FML2\0`, `fml:loginwrapper` in LOGIN)

[verified: wiki page, `FMLNetworkConstants` 1.16.x, Ambassador README, PCF README]

- Marker `\0FML2\0`; status JSON gains `forgeData` with `fmlNetworkVersion: 2`. Forge 1.19 bumps the
  constant to 3 (`FMLNETVERSION = 3`, "S2CModList may include non-vanilla synced datapack registry
  ids") but keeps the login-phase mechanism.
- After Login Start the server sends Login Plugin Requests on `fml:loginwrapper`; the payload holds an
  inner channel (`fml:handshake`) and an inner packet: Mod List 1 (clientbound), Mod List Reply 2
  (serverbound), Server Registry 3, Configuration Data 4, Acknowledgement 99 (serverbound). Each
  request needs exactly one response. Only then does Login Success follow. PLAY has no further
  handshake.
- The mod list and registry snapshot are fixed at LOGIN. There is no way to reset a 1.13 to 1.20.1
  client back to LOGIN from PLAY: the maintainer of Velocity wrote that this "is going to cause issues
  with stuff like forge" and pointed to Ambassador "which requires a mod" (V#922, 2023). PR V#690 (2022)
  and PR V#932 (2023) added support through the "Forge Client Reset Packet" client mod, a packet that
  "resets the client's registries before allowing them to enter the server"; both were closed
  unmerged, the second by its author with no maintainer review.
- Ambassador (Velocity plugin, "only for 1.13-1.20.1") switches servers with no client mod "when the
  servers are similar (mods must match)", and uses the Client Reset Packet mod only for "instant
  server switching" between different mod sets. Its known failure is "stuck on Negotiating" when the
  reset mod conflicts with client-side mods. Player-info forwarding needs PCF on the backend.
  [verified README; the replay mechanism below is inferred from "mods must match"]
- Proxy-Compatible-Forge (backend mod) implements Velocity's MODERN forwarding versions 1 to 4,
  BungeeGuard and legacy, for Forge 1.7.2 to 26.2 and NeoForge 1.20.1 to 26.2. It is the backend
  side of forwarding for every modded server and Warp does not need to replace it.
- Gate's lesson: NeoForge modpacks send login plugin messages far above the usual size and Gate's
  decompressor cut them short (Gate#587, from `gate-deep-dive-analysis.md`). Warp's login frame limits
  must accept Forge's maximum payload (1 MiB login plugin message; Forge splits bigger registries).

### 2.3 Forge 1.20.2+ (`\0FORGE[n]`) and NeoForge (no marker), CONFIGURATION phase

[verified: Forge `NetworkContext` 1.20.x, NeoForge `NetworkRegistry` 1.21.x, Velocity
`ModernForgeConnectionType`, `ModernForgeConstants`, `HandshakeSessionHandler`]

- Forge: `enhanceHostName` joins the host and `FORGE` with `\0`; the server splits the intention host on
  `\0`, and any part starting with `FORGE` marks the connection `MODDED`, with an optional network
  version number after the word. No marker means `VANILLA`.
- NeoForge: no hostname marker. Negotiation runs in the configuration phase with built-in payloads
  (`ModdedNetworkQueryPayload`, `ModdedNetworkPayload`, `ModdedNetworkSetupFailedPayload`,
  `CommonVersionPayload`, `CommonRegisterPayload`, `minecraft:register`/`unregister`). A vanilla client
  on a NeoForge server is disconnected with a translatable "install NeoForge version %s" message;
  mismatches disconnect with `multiplayer.disconnect.incompatible` and a reason suffix ("No Channel for
  %s", "Invalid Protocol %s"). On a successful negotiation the server sends the setup payload, then
  `minecraft:register`.
- Velocity: PR V#1176 (merged 2024) added `ModernForgeConnectionType`, which keeps the raw host name
  and rebuilds a normalised `\0FORGE[n]` token for the backend handshake; nothing else is Forge-specific
  for these versions. Open reports after that: V#1404 "too many known packets" (213 packs with ATM10,
  closed by raising the limit), V#1556 uncompressed packet size validation kicks modded players,
  V#1511 "Incompatible client" with NeoForge 1.20.4 and 1.21.1 (closed as a forwarding
  misconfiguration, [unverified]), V#1463 "please reconnect" from Ambassador in a setup Velocity calls
  unsupported.
- Because the client is reset to CONFIGURATION on every switch, the new backend negotiates with the
  client itself. The proxy's only duties are to carry the marker, to carry every configuration payload
  untouched and unlimited, and to not inject its own configuration traffic in the middle of a
  negotiation.

### 2.4 Status pings

Forge clients read `forgeData` (1.13+) or `modinfo` (1.12) in the status JSON to decide whether the
server is modded and whether its mods match; a mismatch shows a red mark in the server list. Velocity
has `ping-passthrough = mods` for this (V#710 setup). Warp's status passthrough must copy these keys
from the routed backend's ping untouched. [verified for Velocity's option name; the JSON keys from the
wiki page]

---

## 3. Bedrock: Geyser, Floodgate and the proxies

### 3.1 Deployment shapes

[verified: Geyser setup wiki, Floodgate setup wiki, Gate Bedrock guide]

| Shape | What it is | Who runs it |
|---|---|---|
| Geyser standalone | own process, listens on UDP 19132 (RakNet, or `nethernet` transport), connects to a Java server over TCP as a client, `auth-type` online, offline or floodgate | Gate (managed child process with generated config), many networks |
| Geyser plugin on the proxy | same JVM as Velocity or BungeeCord; "Install Geyser only on the Velocity server!"; injects sessions into the proxy pipeline | most Velocity networks |
| Geyser plugin on a backend | same JVM as Paper, Fabric or NeoForge; Bedrock players bypass the proxy entirely | single servers |

Floodgate: "You only need to install Floodgate on the BungeeCord or Velocity proxy server", unless the
Floodgate API is wanted on backends, in which case Floodgate goes on every backend too, with the proxy's
`key.pem` copied over and `send-floodgate-data: true` on the proxy. The wiki warns the key "lets
Bedrock accounts bypass Java authentication" and must never be shared.

### 3.2 How Geyser and Floodgate attach to Velocity

[verified: `GeyserVelocityInjector`, `LocalSession`, Floodgate `VelocityInjector`]

- Geyser reads the private field `cm` of the proxy, calls `getServerChannelInitializer()` on it, reads
  `SERVER_WRITE_MARK` and `workerGroup`, and binds a `ServerBootstrap` on a `LocalServerChannelWrapper`
  whose child handler runs Velocity's own initializer and then adds a `geyser-compression-disabler`
  after `minecraft-encoder`. Each Bedrock player becomes a `LocalSession`: a `LocalChannelWithRemoteAddress`
  whose spoofed remote address is `new InetSocketAddress(clientIp, 0)`, with optional HAProxy support.
  This is where "Geyser players have port 0" comes from, the heuristic Sonar and LimboFilter rely on and
  that `anti-bot.md` rejects.
- Floodgate's injector does the same reflection on `cm` and `serverChannelInitializer` to add its own
  handler that reads the hostname blob.
- Consequence: a Velocity release that touches `ConnectionManager` breaks both plugins until they are
  rebuilt (G#6679 `NoSuchMethodError` on Velocity 4.x, F#645 Guice 7 and snakeyaml 2 on 3.5.0).

### 3.3 Floodgate's data and identity

[verified: Gate's `floodgate.go` and `cipher.go`; Floodgate features wiki; V#1827]

- Hostname: `original_hostname\0<encrypted>` where the encrypted part is `^Floodgate^` + one version
  byte (`0x3E` + version 0), then Base64 of IV (12 bytes) `!` (0x21 splitter) ciphertext, AES-GCM with
  the raw bytes of `key.pem` (16, 24 or 32 bytes).
- Plaintext: 12 NUL-separated fields: Floodgate version, username, xuid, device OS id, language, UI
  profile, input mode, IP, linked player, from-proxy flag, subscribe id, verify code.
- UUID: `00000000-0000-0000-0009-xxxxxxxxxxxx`, the xuid in the low 64 bits (wiki example
  `00000000-0000-0000-0009-01f64f65c7c3`); username prefixed, `.` by default, Gate uses `_%s`. V#1827:
  a strict Java name pattern "rejects essentially every Bedrock player".
- Linking: a Bedrock account may be linked to a Java account (Global API or local database); the
  linked profile then replaces the Bedrock identity. Gate calls `api.geysermc.org` for the promotion
  when `backendFloodgate` is enabled.
- Skins: Geyser uploads the Bedrock skin through the Global API and MineSkin; Floodgate applies the
  textures to the Java profile. A queue or a firewall rule on `api.geysermc.org` delays or loses skins
  (F#182 open since 2021, F#147).

### 3.4 Issue history behind a proxy

| Issue | What happened | Root cause |
|---|---|---|
| G#3019 (2022) | Bedrock players kicked when `force-key-authentication = true` | the Floodgate player has no profile key; fixed on Floodgate's side |
| G#4527 (2024) | "unknown connections crash Velocity", a UDP amplification of one RakNet packet | the Bedrock listener is public and unauthenticated |
| G#6533 (2026) | plugins cannot detect Bedrock players under Velocity 3.6 + Floodgate 2.2.5 | Floodgate missing on backends or `send-floodgate-data` off |
| G#6430 (2026) | "You are already connected to this server" on every switch after a Geyser update | duplicate identity on the backend (closed, cause [unverified]) |
| G#3703, G#5894 (2023, 2025) | real IP lost with PROXY protocol v2 in front of Geyser | address handling in the RakNet layer; a proxy behind it sees the wrong address |
| G#6679 (2026) | Geyser fails to load on Velocity 4.0 | reflection and shaded library changes |
| F#508 (2024) | session-server lookups on every switch | Floodgate forwarded a profile without properties; Paper fetched textures from Mojang |
| F#404 (2023) | Floodgate players seen as Java on backends | a BungeeCord change, then misconfiguration |
| F#182, F#147 | skins invisible to Java players | Global API queue or connectivity |
| F#645 (2026) | Floodgate does not load on Velocity 3.5.0 | Guice and snakeyaml breaking changes |
| V#1827 | strict username validation rejects `.name` | Velocity's pattern |

### 3.5 Gate's position

[verified: Gate Bedrock guide]

Gate runs Geyser as a managed child process (downloads the jar, writes its config, parses its stdout),
implements Floodgate natively, and presents Bedrock players to backends as plain Java connections
("no plugins required"), with an opt-in `backendFloodgate` that re-encrypts the data for allowed
backends sharing the key. Username format `_%s`, Geyser listens on `localhost:25567`. Gate does not
expose an API for Geyser to inject sessions: the Geyser process talks TCP to Gate.

---

## 4. What blind forwarding changes

Warp never deserializes PLAY packets it does not act on, and blind-forwards the CONFIGURATION phase.
That removes most of the proxy's historical modded problems and leaves a short list.

Already solved by the architecture:

- Mod payloads, registry snapshots, data pack registries, custom packet ids: Warp has no registry of
  "known" packets to be too small for a modpack (V#1404 does not apply), and no uncompressed size
  validation of its own on the passthrough path (V#1556 applies only to the transcode path, where the
  limit must follow the backend's).
- 1.20.2+ mod negotiation happens between client and backend in CONFIGURATION, which Warp relays.
- Server switch on 1.20.2+ resets the client through `StartConfiguration`; the new server negotiates
  from scratch.

Still the proxy's problem:

1. **The handshake marker** must reach the backend: `\0FML\0`, `\0FML2\0`, `\0FORGE[n]`, or the
   Floodgate blob. Today it is dropped.
2. **Login plugin requests** from a backend must be relayed to the client and the answers back, except
   the ones addressed to the proxy (`velocity:player_info`). Today they are refused.
3. **Legacy FML in PLAY**: `FML|HS` messages are plugin messages in PLAY, which blind forwarding passes
   through. Warp only has to watch the first byte of `FML|HS` payloads in both directions to know when
   the handshake is COMPLETE, and to inject one reset packet on switch.
4. **1.13 to 1.20.1 switching**: the new backend will send `fml:loginwrapper` queries during the login
   Warp performs on the player's behalf while the player is still playing on the old server. Warp
   must answer them itself, from what the client answered at first login.
5. **Status JSON**: copy `forgeData` and `modinfo` through.
6. **Limits**: login plugin message size, configuration-phase frame size and count must not be capped
   below what Forge and NeoForge send (login plugin payload up to 1 MiB, hundreds of known packs).
7. **The client's identity** for routing, anti-bot and forwarding needs a platform classification.

Nothing in this list touches the PLAY hot path except the `FML|HS` byte check, which applies only to
plugin messages on one channel of a legacy connection and is a branch on a packet Warp already
identifies (plugin messages are registered packets).

---

## 5. Design: modded clients

### 5.1 `ClientPlatform`

One sealed type, decided at the handshake, refined during login, exposed on `InboundConnection` and
`Player`:

```java
public sealed interface ClientPlatform {
  record Java() implements ClientPlatform {}
  record Forge(ForgeEra era, int netVersion) implements ClientPlatform {}   // LEGACY, FML2, MODERN
  record NeoForge() implements ClientPlatform {}                             // detected in CONFIGURATION
  record Bedrock(long xuid, String bedrockName, int deviceOs, int inputMode,
                 String language, Optional<UUID> linkedJava, InetAddress realAddress)
      implements ClientPlatform {}
}
```

Detection: `\0FML\0` and protocol below 1.13 gives `Forge(LEGACY)`, `\0FML2\0` or `\0FML3\0` gives
`Forge(FML2)`, a part starting with `FORGE` on 1.20.2+ gives `Forge(MODERN, n)`, a `^Floodgate^` blob
that decrypts gives `Bedrock`, a `LocalConnections` session is `Bedrock` by construction. NeoForge
cannot be detected at the handshake; it is set when the client's first configuration payload is a
NeoForge query (one channel name compare on a packet Warp already frames), and before that the
connection counts as `Java`. The platform is never a trust signal by itself (anti-bot rule 7); the
Bedrock variant is, because it required the key or an in-process session.

### 5.2 Marker forwarding

`BackendLoginSessionHandler` builds the backend handshake host as `backendHost + markers`, where
`markers` is the raw client suffix normalised: `\0FML\0`, `\0FML2\0`, `\0FORGE[n]` or nothing. The
Floodgate blob is handled by the Bedrock design (§6.3), never copied verbatim. A per-server
`modded = false` option strips the marker for a vanilla backend in a mixed network, matching Velocity's
behaviour of not sending the reset when the next server is vanilla.

### 5.3 Login plugin relay (1.13 to 1.20.1)

`BackendLoginSessionHandler.handleLoginPluginRequest` gains a third branch: any channel that is not
`velocity:player_info` is relayed to the client with a proxy-assigned message id (ids are per
connection, so the proxy maps them), and the client's `LoginPluginResponse` is relayed back under the
backend's id. Warp records, per connection, the sequence of (inner channel, inner packet id, response
bytes) for `fml:loginwrapper`; the Mod List Reply is what makes a later replay possible. Unknown
channels are relayed the same way, which also fixes every other login-phase plugin protocol without
Warp knowing them. When the player is a `LocalConnections` session or a 1.7 to 1.12 client (no login
queries exist), the request is refused as today, which is what the backend expects of a vanilla
client.

Frame limits in LOGIN rise to Forge's maximum for a modded connection only (1 MiB), the vanilla limit
stays for `Java`.

### 5.4 Switching

| Era | Mechanism | Fallback |
|---|---|---|
| 1.20.2+ (Forge, NeoForge) | `ReconfigurationSwitch` unchanged; the marker is sent to the new backend; CONFIGURATION relayed blind, no proxy traffic injected until the client's `AcknowledgeFinishConfiguration`; a configuration-phase disconnect from the new backend is shown to the player and the player stays on its server if it was still there | none needed |
| 1.7 to 1.12 | `RespawnSwitch` plus the FML state machine: the new backend's ServerHello is held until Warp has sent `FML|HS FE 00` to the client, then released; the client redoes the handshake with the new backend inside PLAY, as under BungeeCord | if the new backend is vanilla (`modded = false`), no reset, as Velocity |
| 1.13 to 1.20.1 | `RespawnSwitch` with **replay**: during the login to the new backend Warp answers each `fml:loginwrapper` query from the recorded answers (Mod List Reply byte-for-byte, Acknowledgement to Registry and Configuration Data); if the new server's Mod List, Registry or Configuration Data differs from the first server's (hash of the request payload), the switch is aborted before the client moved | the player is kicked with a message naming the target server and its address ("different mods on `survival`: reconnect to `survival.example.com`"), configurable; no client mod required and none shipped |

Why no client mod: Velocity refused two of them, Ambassador's optional reset mod fails with
client-side mods, and a network that needs different mod sets per backend is better served by a kick
with the right address than by a reset that works for some clients. The replay covers the common case
(one modpack, several backends) with nothing to install.

Replay correctness risk: Configuration Data carries server config values and Registry carries the
snapshot the client accepted; replaying "acknowledged" to a server whose snapshot differs would
desync the client. Hence the hash comparison on the request side, and abort on any difference,
including a different number of queries.

### 5.5 Status and mod mismatch messages

Status passthrough copies `forgeData` and `modinfo` from the routed backend's response. Mod mismatch
disconnects come from the backend in its own words (NeoForge's translatable keys, Forge's text) and are
relayed untouched in LOGIN and CONFIGURATION; Warp adds nothing, so a client sees the same screen as
without a proxy. Warp logs one WARN per backend when a modded client is refused by a backend during
configuration, with the platform and the first 200 characters of the reason, for operators.

### 5.6 What Warp does not do

- No mod list parsing, no registry awareness, no registry re-sync (NeoForge and Forge do it in
  CONFIGURATION, 1.13 to 1.20.1 is covered by replay or kick).
- No companion mod of any kind. PCF stays the backend forwarding mod and is documented as such.
- No support for Forge 1.7.2 to 1.7.10 beyond what the FML state machine gives; Velocity's
  `UNDETERMINED_17` reset-on-every-switch is adopted only if the e2e Forge 1.7.10 client needs it.

---

## 6. Design: Bedrock

### 6.1 Shape

Three supported shapes, in order of recommendation:

1. **`warp-geyser` plugin** in the Warp repository: Geyser core as a dependency from
   `repo.opencollab.dev`, a `GeyserBootstrap` for Warp, sessions opened through the public
   `LocalConnections` API (§6.2). Same JVM, no network hop, no reflection, Bedrock players counted in
   Warp's metrics.
2. **Geyser standalone** with `auth-type: floodgate`, `key.pem` shared with Warp, PROXY protocol
   enabled towards Warp from a listener Warp trusts (`proxy-protocol.trusted`), or Floodgate's IP field
   when the PROXY protocol is off. Works today with no Geyser-side code.
3. **Geyser on a backend**: nothing for Warp to do.

Warp does not run Geyser as a managed child process (Gate's model): it adds process supervision, stdout
parsing and a second JVM for no gain once shape 1 exists.

### 6.2 `LocalConnections` API

```java
public interface LocalConnections {
  /** Opens a client connection to this proxy from inside the JVM; the returned channel speaks the
   *  Java protocol from the handshake on, as a TCP client would. */
  ChannelFuture open(LocalConnectionSpec spec);
}

public record LocalConnectionSpec(
    InetSocketAddress realAddress,     // real Bedrock address and port, never 0
    ClientPlatform platform,           // Bedrock(...) filled by the caller
    Optional<GameProfile> profile) {}  // UUID, name and textures when the caller authenticated
```

The connection runs Warp's normal client pipeline (frame decoder, decompressor, session handlers) on
a Netty `LocalChannel` registered on Warp's worker group, minus TLS or PROXY protocol handlers. The
`platform` is trusted because only an in-process plugin can call this. The anti-bot sees a `Bedrock`
platform with proof and applies its Bedrock mode (§6.5). `remoteAddress()` returns `realAddress`, so
limits, logs and backend forwarding carry the Bedrock player's address. Geyser's existing
`LocalSession` maps onto this with a small adapter in `warp-geyser`.

### 6.3 Floodgate native

Warp implements Floodgate's protocol itself, as Gate does:

- **Read**: at the handshake, a `\0` part starting with `^Floodgate^` is Base64-decoded, verified and
  decrypted with the configured key, split into 12 fields; a failure (wrong key, bad tag, wrong field
  count) is a login refusal with a reason code, never a fallback to "Java player". The platform becomes
  `Bedrock`, the profile is `00000000-0000-0000-0009-<xuid>` and `<prefix><name>`, with the linked Java
  profile replacing both when the linked field is set.
- **Verify**: the IP field must equal the connection's address unless the connection comes from a
  trusted Geyser address, the from-proxy flag must match the deployment, and the version byte must be
  a known one.
- **Forward**: for backends listed in `bedrock.floodgate.forward-to`, Warp re-encrypts the data with
  the same key and appends it to the backend handshake host, so Floodgate on those backends exposes its
  API to plugins (answers G#6533 without asking operators to install anything new). For every backend,
  Warp's modern forwarding payload carries the profile with its properties, so Paper never queries the
  session server for a Bedrock player (F#508).
- **Skins**: `warp-geyser` receives the textures from Geyser's skin upload (Geyser already talks to the
  Global API); for the standalone shape, Warp fetches the textures once per login from the Global API
  when `bedrock.skins = true`, with a short timeout and no retry on the login path, and caches them per
  xuid. [estimate: the Global API endpoint and its rate limits to confirm during implementation]
- **Names**: offline name validation accepts the configured prefix for `Bedrock` platforms only
  (anti-bot rule 11); a Java connection using the prefix is refused.

### 6.4 Routing

`routes` gain a platform condition and `ServerPreConnectEvent` exposes the platform:

```hocon
routes {
  "play.example.com"            = "lobby"
  "play.example.com" @bedrock   = "bedrock-lobby"     # Bedrock players of the same host
  "*" @forge                    = "modded"
}
```

A route condition is evaluated on the decided platform; a `Java` default always exists. Groups and
balancers are unchanged.

### 6.5 Anti-bot

Applies `anti-bot.md` §10 rules 5 and 6 and D13, with the proof now defined:

- Proof of Bedrock is one of: a `LocalConnections` session, a Floodgate blob that decrypted with the
  key, or the PROXY protocol from an address in `bedrock.geyser.trusted`. Port 0 proves nothing.
- With proof: the world check is skipped (Geyser's movement is not Java physics), the probe, budgets
  and limits apply to the real Bedrock address, Bedrock-specific stage deadlines (Geyser needs longer:
  Sonar uses 45 s instead of 15 s).
- Without proof, a `.name` is a Java client claiming to be Bedrock: rejected in offline mode by the
  name rule, verified like any Java client otherwise.
- RakNet amplification (G#4527) is Geyser's listener problem; the documentation says to keep the
  Bedrock port behind the host's firewall and names the OVH and TCPShield Bedrock profiles from
  `anti-bot.md` §7.

### 6.6 Configuration sketch

```hocon
modded {
  forward-markers = true           # \0FML\0, \0FML2\0, \0FORGE[n] reach the backend
  legacy-reset = true              # FML|HS reset on switch, 1.7 to 1.12
  replay-login = true              # 1.13 to 1.20.1 switch by replaying recorded answers
  mismatch-message = "This server runs different mods. Connect to {address} directly."
}
servers {
  lobby    { address = "127.0.0.1:25566", modded = false }   # strip markers, no reset
  survival { address = "127.0.0.1:25567" }
}
bedrock {
  floodgate {
    key-file = "floodgate.pem"
    username-prefix = "."
    forward-to = ["survival"]      # backends with Floodgate installed and the same key
  }
  geyser { trusted = ["127.0.0.1/32"] }   # standalone Geyser sending the PROXY protocol
  skins = true
}
```

---

## 7. Decisions taken

**D1. Native Forge and NeoForge, three eras, no companion mod.** Velocity's gap is the 1.13 to 1.20.1
era and the lost marker in some modes; both are the proxy's job. A companion mod would repeat the
Ambassador and PR V#932 history. PCF remains the forwarding mod for modded backends and Warp documents
it.

**D2. Blind stays blind.** Warp reads two things: the inner channel and packet id of `fml:loginwrapper`
queries (LOGIN only) and the first byte of `FML|HS` payloads (legacy PLAY only). Mod lists, registries,
configuration data and every 1.20.2+ negotiation payload are relayed as bytes. The hot path gains a
channel compare on legacy connections only.

**D3. Switch per era.** 1.20.2+ through reconfiguration as today; 1.7 to 1.12 with the FML reset held
until the new backend's ServerHello; 1.13 to 1.20.1 by replay when the queries match byte for byte,
else abort before the client moves and kick with the target address. Never a silent registry desync.

**D4. Floodgate native plus a public injection API plus `warp-geyser`.** Warp reads, verifies and
re-emits Floodgate data itself, so backends need Floodgate only when their plugins use its API. Geyser
attaches through `LocalConnections` with the real address, not through reflection into private fields
and not through port 0. `warp-geyser` lives in the Warp repository against Geyser's published core;
a `bootstrap/warp` is offered upstream once the API has shipped in a stable Warp.

**D5. One `ClientPlatform`, proof-based.** Drives routing conditions, anti-bot mode, name validation,
what is forwarded and what is logged. Self-declared markers classify but never exempt; Bedrock exempts
the world check only, and only with proof.

**D6. Limits follow the platform.** Login plugin payloads up to Forge's 1 MiB and unlimited
configuration-phase traffic for modded platforms; vanilla limits for `Java`. No fixed "known packs" cap.

**D7. Errors are the backend's words.** Mod mismatch and NeoForge negotiation failures are relayed
unchanged; Warp adds a per-backend WARN with a reason code and never rewrites the message.

---

## 8. Phased plan

Sizes are [estimate], in working days for one engineer including tests.

| Phase | Content | Size | Depends on |
|---|---|---|---|
| M1 | `ClientPlatform` on `InboundConnection` and `Player`; marker detection and forwarding; per-server `modded`; status `forgeData`/`modinfo` passthrough; login frame limit per platform | 3 d | PR #179 (routes clean host) |
| M2 | Login plugin relay with id mapping and recording; refusal only for proxy channels and platforms without login queries; e2e with a Forge 1.20.1 and a NeoForge 1.21 client joining | 4 d | M1 |
| M3 | 1.20.2+ e2e: six switches with a NeoForge client, configuration disconnect relayed; audit that `ReconfigurationSwitch` injects nothing before the client's acknowledgement | 2 d | M2 |
| M4 | Legacy FML state machine, held ServerHello and reset on switch; 1.7.10 dimension signedness check; e2e with a Forge 1.12.2 client | 4 d | M1 |
| M5 | Replay switch for 1.13 to 1.20.1 with hash comparison and the kick fallback; e2e: same-mods switch passes, different-mods switch kicks with the address | 4 d | M2 |
| B1 | Floodgate native: read, verify, profile, name prefix, anti-bot proof; forwarding of properties to every backend; e2e with a synthetic blob built by the harness | 4 d | anti-bot phase that defines Bedrock mode |
| B2 | `LocalConnections` API and `@bedrock`/`@forge` route conditions; `ServerPreConnectEvent.platform()` | 3 d | B1 |
| B3 | Floodgate re-emission to `forward-to` backends; skins via Global API for the standalone shape | 3 d | B1 |
| B4 | `warp-geyser` module: Geyser bootstrap, `LocalSession` adapter, skins from Geyser's uploader, version pinned in the catalog; e2e smoke with a Bedrock protocol bot | 6 d | B2 |

M1 to M3 unblock 1.20.2+ networks, the majority of new modded servers, in about nine days. M4 and M5
are independent of each other. B1 alone makes Geyser standalone networks correct; B4 is the
differentiator.

---

## 9. Test plan

Unit tests mirror the components: marker normalisation, `fml:loginwrapper` id mapping and recording,
hash comparison and abort, FML state machine transitions on each discriminator, Floodgate decrypt and
re-encrypt against Gate's test vectors (`cipher_test.go`, `floodgate_test.go` exist upstream), the
UUID and name derivation, route conditions.

End to end, in `e2e/`:

- **Modded clients.** HeadlessMC launches Forge, NeoForge and Fabric clients headless and is made for
  CI runners; the mc-runtime-test action lists Forge and Fabric from 1.7.10 and NeoForge from 1.20.1,
  with Xvfb [verified READMEs]. Neither documents joining a multiplayer server; the plan is HeadlessMC
  with the vanilla game arguments (`--quickPlayMultiplayer host:port` from 1.20, `--server`/`--port`
  before) and an offline account, which HeadlessMC allows for CI [verified for offline accounts,
  unverified for the join arguments: to validate in the first spike of M2]. If joining cannot be
  driven by arguments, the e2e gets a 30-line client-side test mod per loader that connects on the
  title screen; it lives in `e2e/` and is never shipped. Backends: a Forge or NeoForge server with one
  content mod that adds registry entries, and a second backend with a different mod set for the M5
  kick scenario. New scenarios: `modded-login`, `modded-switching`, `modded-mismatch`,
  `modded-status`.
- **Bedrock without a Bedrock client (B1 to B3).** The harness owns a test `key.pem`, builds Floodgate
  blobs with a 20-line Node port of the cipher, and connects raw `minecraft-protocol` clients with
  `hostname\0<blob>`: valid blob, wrong key, tampered field count, IP mismatch, linked player; checks
  the UUID and name on the backend, the properties in the forwarding payload, the anti-bot world check
  skipped, the re-emitted blob accepted by Floodgate on a backend running the real plugin.
- **Bedrock with a Bedrock client (B4).** Geyser through `warp-geyser` with a `bedrock-protocol`
  (PrismarineJS) bot in offline mode [unverified that Geyser accepts an unauthenticated Bedrock login
  chain in `auth-type: offline`; if not, this scenario is manual and documented as such].
- **Regression.** The whole existing matrix stays green with `modded.forward-markers = true`: vanilla
  clients carry no marker, nothing changes for them.

---

## 10. Known issues of other projects that this design avoids

| Issue | Problem | Warp's answer |
|---|---|---|
| V#248, V#690, V#932 | 1.13 to 1.20.1 Forge unsupported for seven years; the only fix needed a client mod | login plugin relay (M2) and replay switch (M5), no mod |
| V#1176 | 1.20.2+ token support arrived five years after the marker changed | marker normalisation covers all three eras from M1 |
| V#710, V#1785 | FML token lost with legacy and BungeeGuard forwarding | one place builds the backend handshake; the marker is appended whatever the forwarding mode |
| V#922 | registry sync problems on modded Fabric; "cannot reset the client back into the login phase" | 1.20.2+ resets in CONFIGURATION; 1.13 to 1.20.1 aborts before any desync |
| V#1404 | "too many known packets" with 213 packs | no proxy-side cap on configuration traffic |
| V#1556 | uncompressed size validation kicks modded players | passthrough has no such check; transcode follows the backend's limit per platform |
| V#1463 | Ambassador's "please reconnect" | the kick names the server and address, and only when mods differ |
| V#1859 | legacy Join Game dimension sign extension broke modded 1.7.10 | checked in M4 with a real 1.7.10 Forge client |
| Gate#587 | NeoForge login plugin messages truncated | login frame limit raised to Forge's 1 MiB for modded platforms |
| G#6679, F#645 | Geyser and Floodgate break on every proxy refactor (reflection, shaded libraries) | `LocalConnections` is public API; Floodgate is native |
| G#6533, F#404 | plugins do not see Bedrock players behind the proxy | Floodgate re-emitted to listed backends with the same key; profile forwarded with properties |
| F#508 | session-server lookups on every switch | properties travel in Warp's forwarding payload |
| F#182, F#147 | skins missing | textures fetched once and cached per xuid; Geyser's uploader used in-process |
| V#1827 | `.name` rejected by the name pattern | prefix accepted for proven Bedrock platforms only |
| Sonar S#172, LimboFilter LF#47 | "port 0 means Geyser" exempts Java bots that bind port 0 behind a reverse proxy | real address and port on every injected session; proof-based exemption |
| G#3019 | `force-key-authentication` kicks Bedrock players | key enforcement skipped for proven Bedrock platforms, as for pre-1.19 clients |
| G#3703, G#5894 | real IP lost through PROXY protocol v2 | the Floodgate IP field is cross-checked with the connection's address; mismatches are logged and refused unless the source is trusted |

---

## 11. Risks

- **Replay semantics (M5).** The hash comparison is strict by design; a server whose Configuration
  Data includes a per-connection value would never match and every switch would kick. Measured on the
  e2e modpack; if it happens, the comparison excludes Configuration Data and relies on Mod List and
  Registry only, documented as such.
- **Headless modded clients in CI.** Launch times of several minutes per loader and version; the
  modded matrix runs nightly, not per PR, and on two versions per era.
- **Geyser's release cadence.** `warp-geyser` pins a Geyser version; a Bedrock client update needs a
  Warp release or a documented jar swap. Mitigation: the plugin loads any Geyser core on the classpath
  of its own folder when present [estimate], and the standalone shape never waits for Warp.
- **Global API dependency for skins.** Treated as best effort: no login waits on it.
- **NeoForge detection timing.** `NeoForge` is known only in CONFIGURATION, after routing: a `@neoforge`
  route condition cannot exist at the handshake and is not offered; `@forge` covers Forge's marker, and
  NeoForge networks use host routes.
- **Key handling.** The Floodgate key grants authentication bypass; Warp loads it with the same file
  permission checks as the forwarding secret and redacts it from the remote console.

---

## Sources

Forge and NeoForge

1. https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Minecraft_Forge_Handshake (FML and FML2 handshakes, discriminators, reset, `fml:loginwrapper` ids)
2. https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/src/main/java/net/minecraftforge/network/NetworkContext.java (`FORGE` marker, `enhanceHostName`, `processIntention`)
3. https://github.com/MinecraftForge/MinecraftForge/blob/1.16.x/src/main/java/net/minecraftforge/fml/network/FMLNetworkConstants.java (`FML2`, `fml:handshake`, `fml:play`)
4. https://github.com/MinecraftForge/MinecraftForge/blob/1.19.x/src/main/java/net/minecraftforge/network/NetworkConstants.java (`FML3` constant, synced datapack registries)
5. https://github.com/neoforged/NeoForge/blob/1.21.x/src/main/java/net/neoforged/neoforge/network/registration/NetworkRegistry.java (configuration-phase negotiation, payloads, disconnect keys)
6. https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/client/HandshakeSessionHandler.java (connection type detection)
7. https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/forge/modern/ModernForgeConnectionType.java
8. https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/forge/modern/ModernForgeConstants.java
9. https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/forge/legacy/LegacyForgeConstants.java
10. https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/forge/legacy/LegacyForgeHandshakeClientPhase.java
11. https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/forge/legacy/LegacyForgeUtil.java (reset packet)
12. https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/ConnectionTypes.java
13. https://github.com/PaperMC/Velocity/issues/248 (Forge 1.14+ support, open since 2019)
14. https://github.com/PaperMC/Velocity/pull/690 (Modern Forge 1.13+ support, closed unmerged)
15. https://github.com/PaperMC/Velocity/pull/932 (Modern Forge Support via client reset packet, closed by author)
16. https://github.com/PaperMC/Velocity/pull/1176 (ModernForgeConnectionType, 1.20.2+)
17. https://github.com/PaperMC/Velocity/issues/922 (registry sync, "cannot reset the client back into the login phase")
18. https://github.com/PaperMC/Velocity/issues/1404 (known packs limit)
19. https://github.com/PaperMC/Velocity/issues/1556 (uncompressed packet size validation)
20. https://github.com/PaperMC/Velocity/issues/1463 (Ambassador "please reconnect")
21. https://github.com/PaperMC/Velocity/issues/1511 (NeoForge "Incompatible client")
22. https://github.com/PaperMC/Velocity/pull/1785 and https://github.com/PaperMC/Velocity/issues/710 (FML token lost with legacy and BungeeGuard forwarding)
23. https://github.com/PaperMC/Velocity/pull/1859 (1.7.10 dimension sign extension)
24. https://github.com/SpigotMC/BungeeCord/blob/master/proxy/src/main/java/net/md_5/bungee/forge/ForgeConstants.java and https://github.com/SpigotMC/BungeeCord/blob/master/proxy/src/main/java/net/md_5/bungee/forge/ForgeClientHandshakeState.java
25. https://github.com/adde0109/Ambassador (README)
26. https://github.com/adde0109/Proxy-Compatible-Forge (README)
27. https://github.com/PaperMC/Waterfall (README: end of life, no Forge claim)

Bedrock

28. https://geysermc.org/wiki/geyser/setup/ (standalone, proxy and backend shapes, `auth-type`)
29. https://geysermc.org/wiki/floodgate/setup/ (proxy-only install, `send-floodgate-data`, key warning)
30. https://geysermc.org/wiki/floodgate/features/ (UUID example, prefix, skins via Global API)
31. https://github.com/GeyserMC/Geyser/blob/master/bootstrap/velocity/src/main/java/org/geysermc/geyser/platform/velocity/GeyserVelocityInjector.java (reflection into `cm`, `LocalServerChannelWrapper`)
32. https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/network/java/LocalSession.java (spoofed address with port 0, HAProxy support)
33. https://github.com/GeyserMC/Floodgate/blob/master/velocity/src/main/java/org/geysermc/floodgate/inject/velocity/VelocityInjector.java
34. https://github.com/minekube/gate/blob/master/pkg/edition/bedrock/geyser/floodgate/floodgate.go and https://github.com/minekube/gate/blob/master/pkg/edition/bedrock/geyser/floodgate/cipher.go (wire format, 12 fields, AES-GCM constants)
35. https://gate.minekube.com/guide/bedrock/ (managed Geyser, native Floodgate, `backendFloodgate`)
36. Geyser issues: https://github.com/GeyserMC/Geyser/issues/3019, /issues/4527, /issues/6533, /issues/6430, /issues/6679, /issues/3703, /issues/5894, /pull/1713
37. Floodgate issues: https://github.com/GeyserMC/Floodgate/issues/508, /issues/404, /issues/182, /issues/147, /pull/449, /pull/645

Test tooling

38. https://github.com/headlesshq/headlessmc and https://github.com/headlesshq/mc-runtime-test (headless modded clients in CI, loader matrix)

Warp: `docs/research/anti-bot.md` §7, §10, §11 (D13); `docs/research/gate-deep-dive-analysis.md` §6
and bug Gate#587; PR #179; `BackendLoginSessionHandler`, `ForwardingResponder`, `ServerSwitch`,
`ReconfigurationSwitch`, `ClientConfigSessionHandler`, `PlayPluginMessage`, `PlayerForwarding`.
