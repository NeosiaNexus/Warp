# Anti-bot: what Sonar, LimboFilter and the proxies do, and the design that beats them

> **Research cycle (2026-11-02): anti-bot.** Research and design only: nothing outside this file was
> changed. Sources: the source code of Sonar 2.1.53 (tarball of `jonesdevelopment/sonar` at the commit
> pushed on 2026-10-27), Velocity (`PaperMC/Velocity` `dev/4.0.0`, same day), LimboFilter, LimboAPI,
> EpicGuard, Gate and Infrarust; every Sonar issue (138) and release note (93); the bot, flood and
> timeout threads of the Velocity, BungeeCord, Waterfall and Gate trackers; vendor documentation of the
> anti-bot plugins and L7 protection services; Warp's `main`, PR #122 (PROXY protocol), PR #123
> (connection throttling) and the `integration/primemc` branch.
>
> Markers: **[verified]** checked in source, a thread or an official page during this research;
> **[unverified]** reported by a third party or not checked; **[estimate]** a number to measure before
> relying on it. Issue references: `S#n` Sonar, `V#n` Velocity, `B#n` BungeeCord, `W#n` Waterfall,
> `G#n` Gate, `LF#n` LimboFilter, `LA#n` LimboAPI, `EG#n` EpicGuard. State and year follow the number
> where they matter. Quotes are short and in their original language.
>
> **Outcome:** a design with its decisions taken (§11) and a four-phase plan (§12). Implementation is a
> separate piece of work.

---

## 0. TL;DR

**Findings**

1. **Sonar, read from its source, protects less than its reputation.** On Velocity it removes itself
   for status connections and leaves floods, null pings and oversized frames to Velocity. Its
   "queue" is a hash map polled 30 times per second, in hash order, with an 8 s read timeout: beyond about
   240 queued connections, who gets verified is a lottery shared by real players and bots. Its verified
   cache is in memory by default (a restart sends every player through verification again), keyed by a
   reversible 32-bit hash of name and IP, expires 5 days after verification, and keys IPv6 per /128. It
   sends 175 to 280 KB of uncompressed registries per verification on 1.20.5+, and keeps two handlers
   on every session for its whole life, one of them writing shared static counters on every buffer.
2. **Fake authentication is the online-mode attack, and every proxy gave up on it.** Each attempt costs
   two RSA decryptions and a Mojang request; at volume Mojang blocks the proxy and real players cannot
   log in (W#528). Velocity closed it as "won't fix" (V#568). Only verification *before*
   authentication protects against it, which is why Sonar kicks every new player after verification.
   LimboFilter moves verification before authentication under load, but with its defaults and no auth
   plugin it can then admit players unauthenticated.
3. **Physics checks stop headless bots, nothing more, and they are the main source of false
   positives.** SoulFire passes Sonar (S#530); map captchas fall to decoders and vision models (S#531,
   S#575). Bedrock, modded clients, Via clients, lag and version edges failed Sonar's and LimboFilter's
   checks for years, and exemptions keyed on heuristics (source port 0) became bypasses (2.0.8, PR
   S#547).
4. **Version churn is the real maintenance cost.** Sonar ships first support within 0 to 28 days, but
   per-version bugs live 46 to 210 days; LimboAPI took 82 days for 1.20.2 and 71 for 1.21.9 because its
   registries are hand-written. Neither uses Known Packs.
5. **Warp has its own gaps today**: up to 2 MiB buffered per unauthenticated connection (Velocity closed
   the same hole on 2026-10-06), one log line per hostile connection (time-outs, unknown states,
   failed authentications), no deadline per pre-login state, no global cap on connections not yet
   playing. Logging is the measured CPU cost of attacks (S#194: 40% with console logging, 10 to 20%
   without). Four citations in older Warp documents are wrong (§6.8), including the BungeeCord "200-300
   pings per second crash" (B#2854, deleted).

**Design** (§9, decisions in §11): built into the core. Every connection goes through bounded admission,
per-state frame caps and deadlines, a cached status and aggregated logging. Only **unknown identities**
are verified: the account in online mode, the name with its /24 or /48 prefix in offline mode, stored as
keyed hashes in a persisted, bounded store that expires 30 days after the last join. Verification is a
configuration probe (1.20.2+) or pre-join probe, then a virtual world check (teleport echo, gravity,
collision, ping), with a map captcha only on escalation. It runs after Login Success in offline mode,
after authentication in online mode, and **before authentication during an attack**, detected from
learned baselines of unknown-identity signals. The player then goes on to the backend **in the same
session** through the switch machinery; only the before-authentication path ends in a transfer (with a
signed cookie token, 1.20.5+) or a reconnect. Verified players never pay anything, and nothing stays on
the PLAY path. Bounds, metrics, commands, a blocklist export for nftables or XDP, and an API with SPIs
complete it.

**Plan** (§12): (1) hardening, attack detection, verified store and API, with no new packet; (2) the
probes, before-auth verification with transfer tokens, the fair queue; (3) the virtual world for 1.7.10
to 26.3, also used to hold players when backends are down; (4) captcha, SPIs, fleets, real-client
false-positive matrix.

---

## 1. Where Warp stands

### 1.1 What exists

| Layer | `main` | PR #122 / #123 (open) | PR #134 (open) |
|---|---|---|---|
| Real client address | socket address | PROXY protocol v1/v2, only from `trusted-proxies`; other peers reset unread | |
| Connections per address | none | GCRA token bucket, burst 10 per 200 ms, reset before any handler is installed | |
| Address keys | | IPv4 /32, IPv6 /64, IPv4-mapped as IPv4 | |
| Status pings | answered | own budget per address (10 per s); over it, closed without an answer | cached status block, legacy (1.6) ping answered, legacy login refused with a message |
| Logins per address | none | rate (burst 2 per 3 s), 2 in progress per address, 30 s login timeout | |
| Online-mode authentication | one virtual thread per login | at most 256 in flight, counted from the Encryption Response (before RSA), so idle bots cannot hold slots | |
| Duplicate logins | `DuplicateLoginPolicy.AUTO`: kick the old session in online mode, refuse the newcomer in offline mode | | |
| Usernames | `USERNAME_PATTERN` at Login Start | | |
| Refusal logging | | one aggregated INFO line per `report-interval` | |
| State machine | strict per state, unknown packets in HANDSHAKE/STATUS/LOGIN close | | |
| Frames | 3-byte VarInt length, up to 2 MiB in every state, stackless cached exceptions, empty frames dropped | | |
| Timeouts | read timeout 30 s in every state | | |

### 1.2 Gaps found while reading Warp's code [verified]

These are attack surfaces today, independent of any anti-bot:

1. **2 MiB of buffering before the handshake.** `FrameDecoder` accepts a declared length up to
   2,097,151 bytes in every state and cumulates until the frame is complete. An unauthenticated
   connection that declares a large first frame and trickles bytes (one byte every 29 s defeats the 30 s
   read timeout) holds up to 2 MiB, and its cumulation buffer can be larger. Velocity closed exactly this
   on 2026-10-06 (commit `374acad83`, "Validate pre-login frame sizes in status and login states"): its
   frame decoder checks each pre-login frame against the minimum and maximum length of the packet it
   announces, "rather than letting an unauthenticated connection make us buffer up to 2MiB".
2. **One log line per hostile connection.** `MinecraftConnection.exceptionCaught` logs every read
   time-out at INFO (PR #100); `HandshakeSessionHandler` logs at WARN every unknown `nextState`, every
   unexpected packet in HANDSHAKE and every login with an unsupported protocol; `LoginSessionHandler`
   logs every failed authentication at INFO. All of them can be triggered at will, so a null-ping flood
   or a fake-login flood is also a log flood. Sonar users measured what that costs: 40% CPU with
   console logging, 10 to 20% without (S#194).
3. **No time limit per pre-login state.** A connection may sit 30 s in HANDSHAKE before sending a byte,
   and 30 s again in STATUS. A vanilla client sends its handshake immediately.
4. **No global bound on pre-login connections.** Per-address limits (PR #123) do not bound a botnet that
   uses many addresses; nothing caps the number of connections that are not yet playing.
5. **No verification of any kind.** Offline mode admits any name that matches the pattern; online mode
   admits any account. Nothing tells a client from a bot.

Smaller points: PR #134's `LegacyPingDecoder` already avoids the bug of V PR#1908 (a modern frame of
length 254 starting with `0xFE`) by looking at the third byte, but it answers a lone `0xFE` at once,
where Velocity's fix waits up to 100 ms for the rest of a fragmented frame; and the read timeout sits
before the frame decoder, so any byte resets it (BungeeCord moved its own after the decoder in B PR#3066
so that only complete frames count).

---

## 2. Threat model

Each row says who can mount the attack, what it costs the attacker, what it costs a proxy without
defence, and which layer has to handle it.

| # | Attack | Online mode | Offline mode | Attacker pays | Undefended proxy pays | Handled by |
|---|---|---|---|---|---|---|
| A1 | Volumetric L3/L4 (UDP, SYN, ACK floods, amplification) | same | same | rented bandwidth (botnets, "stressers"; Cloudflare attributed 29.7 Tbps and then 31.4 Tbps attacks to the Aisuru botnet in late 2025) | the uplink saturates before Java runs | **out of scope**: upstream filtering (§7) |
| A2 | TCP connection flood (connect, send nothing or close) | same | same | one SYN/ACK per connection, no state | a socket, a channel, a pipeline, 30 s of read timeout, and today one INFO line | Warp S0, S1 (§9.3) |
| A3 | Slowloris (connect, trickle bytes) | same | same | a socket per connection | today up to 2 MiB of buffer each (§1.2) | S1 frame caps and state deadlines |
| A4 | Status ping flood, legacy ping, "null ping" (handshake then nothing) | same | same | a few bytes per ping | JSON serialisation per ping (with Velocity's `ping-passthrough`, a backend ping per client ping), a socket held 30 s by a status that never sends its ping, a log line | S1 cached status bytes, per-address budget, silence |
| A5 | Malformed or invalid packet flood | same | same | a few bytes | exception creation and stack traces, log lines | S1 static exceptions, aggregated counters |
| A6 | Login flood that stalls (Login Start, then nothing) | same | same | a few bytes | a login slot for 30 s | S2 per-address and global caps |
| A7 | **Fake authentication** (Login Start, a valid Encryption Response for a random secret, no session) | **main threat** | n/a | one RSA public-key operation | two RSA private-key decryptions and one Mojang `hasJoined` request per attempt; at volume, Mojang blocks the proxy's address and **real players can no longer log in** (W#528: "blocked by mojang for a bit due to too many requests"); Velocity declared it unfixable (V#568) | S2 auth budget, priority lane; pre-auth verification in attack mode (§9.6) |
| A8 | Join bots without accounts (random names) | blocked by authentication | **main threat** | nothing (names are free) | a full join: backend login, entity, chunks, plugins' join handlers, chat spam | verification (S3, S4) |
| A9 | Join bots with real accounts (alts, stolen accounts) | the remaining threat | same as A8 | one account per concurrent bot | same as A8 | verification keyed by account; bans by UUID |
| A10 | Real-client automation (SoulFire, a modded client, mineflayer with physics) | rare, needs accounts | possible | CPU per bot and engineering | same as A8 | physics checks do not stop it (S#530: "SoulFire bypasses Sonar's verification; it's literally just Minecraft without the UI"); attack-mode challenge and rate budgets per prefix |
| A11 | Chat and command spam from joined bots | needs accounts | free | a joined bot | backend CPU, moderation | prevented upstream by A8/A9 defences; message-level filtering stays with the backend or a plugin (§9.12) |
| A12 | Offline-mode name squatting (join as an existing player's name to kick them) | n/a | free | one login | a kicked player | `DuplicateLoginPolicy.AUTO` already refuses the newcomer in offline mode |

Two consequences drive the design:

- **In online mode the expensive attack is A7, not A8.** Authentication already stops bots without
  accounts, but every fake attempt costs the proxy RSA work and a Mojang request. Anything that runs
  *after* authentication cannot protect against it.
- **In offline mode identity is free.** The verified cache cannot be keyed by name alone, and every
  first join must prove it is a client.

---

## 3. Sonar, read from its source

Sonar is GPL-3.0, 169 Java files, about 15,300 lines. One `common` module carries the whole verification;
`velocity`, `bungeecord`, `bukkit` and `paper` only inject it. Every file and line below is from the
tarball of `jonesdevelopment/sonar` at the commit pushed on 2026-10-27.

### 3.1 How it plugs into Velocity

- `VelocityInjector` replaces Velocity's server channel initializer by reflection
  (`VelocityServer.cm`, `ServerChannelInitializerHolder.initializer`). The wrapper calls the original
  `initChannel` through a `MethodHandle`, then adds `sonar-inbound-handler` first in the pipeline and a
  `VelocityInboundHandler` right after Velocity's `minecraft-decoder`.
- It therefore sees **decoded** Velocity packets. For a status handshake it removes itself: "We don't
  care about server pings; remove the handler". **Sonar does nothing against ping floods, null pings,
  legacy pings, slowloris or oversized frames**: all of that is left to Velocity.
- It intercepts `ServerLoginPacket` (Login Start) **before** Velocity's own login handler, so before
  `PreLoginEvent`, before encryption and before Mojang authentication. Verification is always pre-auth.
- Coupling: every Velocity release that renames `cm` or the holder field breaks the injection. The same
  pattern exists on BungeeCord (`BungeeInjector`) and Bukkit.

### 3.2 What happens at Login Start (`InboundHandlerAdapter.handleLogin`)

In this order, each failure being a login disconnect with a prepared message:

1. Handshake seen, protocol known (unknown protocol IDs, snapshots included, are refused).
2. One verification per IP at a time (`connected` map): a second player behind the same address is
   refused ("already verifying") until the first one is done.
3. `blacklisted-protocols` (static list).
4. Blacklist: an IP that failed `blacklist-threshold` (2) verifications is refused for `blacklist-time`
   (10 min).
5. Verification disabled (`timing: DURING_ATTACK` and no attack) → the login continues.
6. Geyser and Eaglercraft connections skip verification unless `check-geyser-players` /
   `check-eagler-players` (both off). Geyser is recognised by a source port of 0 or Floodgate's channel
   attribute (`GeyserUtil`).
7. Verified cache hit on the **fingerprint** (§3.5) → the login continues.
8. Rate limit per IP (`rejoin-delay`, 5 s) through a Caffeine cooldown (`CaffeineCacheRatelimiter`,
   "Idea taken from Velocity"): the second attempt within 5 s is refused, whatever the burst.
9. Sonar strips **every other handler** from the pipeline (except `geyser*`, `floodgate*`, `eagler*`),
   installs its own frame codec and an `IdleStateHandler` (read 8 s, write 10 s), then queues the
   connection.

### 3.3 The queue (`LoginQueue`)

- A `ConcurrentHashMap<InetAddress, Runnable>`, polled once per second, `max-polls` (30) entries per
  poll, by a fixed thread pool.
- **It is not a queue.** `players.entrySet().iterator()` walks a hash map in hash order, not arrival
  order. And the 8 s read timeout installed before queueing applies while queued: a vanilla client
  sends nothing after Login Start, so it is closed after 8 s. With the defaults the queue serves 30
  connections per second while each can wait 8 s: once more than about 240 are queued, hash order
  decides who is served before timing out, for real players and bots alike.
- This is also what bounds Sonar's CPU and bandwidth under attack: 30 verifications per second.

### 3.4 The checks, in order (`common/verification/*`)

| Stage | What is sent | What must come back | Versions |
|---|---|---|---|
| Login | Login Success with a cached profile (name `Sonar`, random UUID fixed at startup), no encryption, no compression | 1.8 to 1.20.1: a Keep Alive sent **before** Join Game, echoed with its ID. 1.7: skipped (100 ms timer) | all |
| Configuration (1.20.2+) | Keep Alive, then registries (full NBT, no Known Packs), tags (26.1+ only), Finish Configuration | Keep Alive echo, Login Acknowledged once, Client Information (view distance ≥ 2), brand (`^[!-~ §]+$`, 2 to 64 bytes, once), Finish Configuration | 1.20.2+ |
| Name | nothing | name matches `^[a-zA-Z0-9_]+$` | all |
| Gravity and collision (`GravityHandler`) | Join Game, spawn position, a teleport to Y ≈ 3000, a relative teleport to the fall start, empty chunks (9 on 1.20.3+, 1 before), Game Event 13 (1.20.3+), an 8 × 8 platform of one random block type (`UpdateSectionBlocks`) | teleport confirm with the expected ID (1.9+), first position exactly at the teleport target and not on ground, then each Y delta equal to `(lastDeltaY - 0.08) * 0.98f` within 1e-7 for `max-movement-ticks` (8) ticks, then on-ground exactly at platform Y + block height; tick-end ordering on 1.21.2+ | all (no teleport confirm before 1.9, legacy teleport on 1.7) |
| Protocol (`ProtocolHandler`) | Transaction/Ping with a random ID, an invalid held slot (-1) then a random slot, an arm swing | the transaction echoed and accepted, the slot echoed, an arm swing back (not on 26.3+, not Geyser) | all |
| Vehicle (`VehicleHandler`) | spawn a boat at the fall height, mount the player; then a minecart | Keep Alive echo per state change, paddle/vehicle-move/rotation/input packets in a consistent order and count (`minimum-packets` 2), boat gravity `lastMotion - 0.04`, falling after dismount | Java only |
| Map captcha (`CaptchaHandler`, off by default) | a filled map in the hotbar or offhand, an XP-bar countdown, chat prompts | the code typed in chat within 30 s, 3 tries | all |

Success: the fingerprint is cached, then the player is **disconnected** ("verified, please reconnect"),
or transferred back (1.20.5+, `transfer.enabled`, off by default). A failure counts towards the IP's
blacklist score.

### 3.5 The verified cache (`VerifiedPlayerController`, `FingerprintingUtil`)

- Key: `hex(name.hashCode() >> 4) + hex((name.hashCode() + ip.hashCode()) << 2) + hex(ip.hashCode() >> 4)`.
  That is two 32-bit `String.hashCode()` values: collisions are easy to build for names
  (`"Aa".hashCode() == "BB".hashCode()`), and hashing the 2^32 IPv4 strings narrows the address down to
  a handful of candidates. It is pseudonymisation, not protection.
- In memory: `Collections.synchronizedSet(new HashSet<>())`, no size bound, no TTL in memory.
- Persistence: **none by default** (`database.type: NONE`). Every restart forgets every verified player,
  so a restart during an attack sends the whole player base through verification again. Optional
  MySQL, MariaDB, PostgreSQL or H2 through ORMLite, with drivers downloaded at runtime.
- Expiry: `maximum-age` (5 days) counted from the **verification**, not from the last visit. A player
  who joins every day is verified again every 5 days.

### 3.6 The attack tracker (`AttackTracker`)

- Checked every 250 ms. An attack starts when joins per second, players in verification **or** queued
  players exceed `min-players-for-attack` (8), and ends after 30 s minimum and 3 s of calm.
- A fixed absolute threshold of 8: a network with more than 8 legitimate joins per second (any restart
  or event) is "under attack" all the time; a small server under a slow drip never is.
- Rates are counted by **inserting one Caffeine entry per connection and per login** into caches that
  expire after 1 s (`GlobalSonarStatistics`), then reading `estimatedSize()`. Under 100k connections
  per second that is 100k cache nodes allocated and expired per second, on the event loops, just to
  count.

### 3.7 Costs that stay after verification

- `BandwidthHandler` is added first in the pipeline of **every** login connection at the handshake and
  never removed. On each inbound and outbound buffer of every player, for the whole session, it adds to
  `static long` fields from all event loops at once: an unsynchronised data race (lost updates) and a
  cache line bounced between cores on the PLAY hot path.
- `InboundHandler` also stays for the whole session (it decrements the per-IP online count on close).

### 3.8 The verification world

- Prepared once at startup into `SonarPacketSnapshot`s: each packet encoded for every protocol, the
  bytes copied into the outgoing buffer on every send. Versions are deduplicated by
  `ByteBuf.hashCode()`, a 32-bit content hash: a collision would silently send one version's bytes to
  another.
- Registries: gzip NBT resources per version (`codec_1_16` … `codec_26_3`, `tags_26_1` … `tags_26_3`),
  sent **in full, uncompressed**: about 175 KB for 1.21 and about 170 KB + 107 KB of tags for 26.3 per
  verification (uncompressed sizes of the gzip resources). No Known Packs negotiation, so the client
  cannot use its built-in data, and no Set Compression.
- Positions, teleport IDs, the pre-join Keep Alive ID and the platform height are random **per process
  start** (`static final` fields), not per player: a bot that watches one verification knows them
  until the next restart. Only the transaction ID, the held slot, the vehicle Keep Alive IDs and the
  block type are drawn per player.
- Protocol surface: 32 PLAY packets, 3 configuration packets, 3 login packets, an 879-line packet ID
  table, 5,626 lines in `common/protocol` alone, 16 registry files and 3 tag files.

### 3.9 What Sonar gets right

- Pre-auth verification in online mode: bots never cost an RSA decryption or a Mojang request.
- Tick-based physics, not timing-based: high latency does not fail the gravity check.
- One prepared world for 1.7.2 to 26.3, already updated for 26.3 (`codec_26_3.nbt`, 26.3 swing and
  teleport changes).
- Quiet exceptions (`QuietDecoderException.INSTANCE`), per-version prepared bytes, a small API
  (`UserVerifyJoinEvent`, `UserVerifySuccessEvent`, `UserVerifyFailedEvent`, `UserBlacklistedEvent`,
  `AttackDetectedEvent`, `AttackMitigatedEvent`, custom captcha generators).
- Geyser off by default, Eaglercraft off by default, 1.20.5+ transfer instead of a reconnect.

---

## 4. Sonar, read from its issues and releases

All 138 issues were read with their comments, about 75 human PRs, the 93 release notes (2.0.0 on
2023-09-22 to 2.1.53 on 2026-10-17), the docs at `docs.jonesdev.xyz` and the `sonar.top` pages. The
Discussions tab is enabled but empty; the project's support happens on Discord, which was not read.
Every issue cited is closed.

### 4.1 False positives

| Who | Evidence | Outcome |
|---|---|---|
| 1.7 and 1.8 clients | S#20 (chunk packet), S#51 "sometimes fail the verification for invalid y gravity" | gravity check rewritten (PR S#53) |
| Bedrock (Geyser) | S#85 (no brand), S#172 (Geyser detected by **source port 0**: "If the player joins through a reverse proxy, their port won't be 0"), S#380 "illegal packet order; i/v 0/1", PR S#281 "Bedrock is UDP... we basically can't really check for packet order" | exemptions added check by check (2.1.8, 2.1.11), then 2.1.32: "Geyser (Bedrock) players are now skipped by default... Official support for Geyser will no longer be provided" |
| Floodgate | S#152: Sonar's Velocity injection replaced Floodgate's, giving "invalid IP addresses and player profiles" | injection reverted |
| Modded clients | S#454 (NeoForge refuses the vanilla-looking limbo, wontfix: "out-of-scope for Sonar 2.0"), S#458 (a modpack's 4,691-byte payload over the size limit), S#414 (Tekkit 2 times out in the minecart), S#585 (BoatHud crashes: "If the mod knowingly modifies vanilla behavior, it is unsupported by design") | limits raised; mods unsupported |
| Via clients | PR S#496 ViaFabric/ViaProxy "has never worked properly on sonar"; PR S#430 ViaFabricPlus 1.21.4 "cannot pass vehicle verification" | open |
| High latency | S#271 "Confirmed issue: players timing out can fail the verification" (fixed by PR S#276: a disconnect is no longer a failure); PR S#496 strict 1.21.2+ tick check: "I've had some issues with high ping"; 2.1.11 "false positives and timeouts related to lag have been fixed" | lag compensation is a Sonar 3.0 (paid) feature |
| Vanilla edge cases | S#395 "slot out of range" for about 1 in 20 to 30 players (closed: "not vanilla behaviour"); 2.1.38 disabled "expected position but got client tick end" for 1.21.7/1.21.8; 2.1.35 removed the locale check "due to a Minecraft bug"; 2.1.43 removed the duplicate-slot check | checks removed one by one |
| Silent kick | S#648 (2026): opening the F3+3 network graph disconnects any 26.1+ client, because PlayerInput was mapped to the Ping Request ID; "`SonarPacketDecoder` checked `byteBuf.isReadable()`, threw `QuietDecoderException`... silently closed the channel... without any server-side stack trace" (PR S#649) | fixed only in a debug build, not in a release |
| Kick message lost | S#352, S#476: players see "Connection reset" instead of the reason. PR S#616: `fail()` queues a write-and-close, then throws, and `TailExceptionsHandler` closes again, racing the write | fixed in 2.1.51, two years later |

No issue mentions colour blindness, screen readers or small screens with the map captcha, nor names
rejected by the name regex.

### 4.2 Version support lag

| Minecraft | Released | First Sonar release | Lag | Latent defects |
|---|---|---|---|---|
| 1.20.2 | 2023-09-20 | 2.0.4 (2023-10-18), partial | 28 d | gravity and collision skipped for 1.20.2 until 2.0.9 (63 d) |
| 1.20.3/1.20.4 | 2023-12-04/07 | 2.0.10 (2023-12-15) | 11/8 d | |
| 1.20.5/1.20.6 | 2024-04-23/29 | 2.0.28 (2024-05-03) | 10/4 d | |
| 1.21 | 2024-06-13 | 2.0.33 (2024-06-18) | 5 d | dev builds through a Discord ticket only (S#334) |
| 1.21.2/1.21.3 | 2024-10-22/23 | 2.1.11 (2024-10-23) | 1/0 d | |
| 1.21.4 | 2024-12-03 | 2.1.19 (2024-12-06) | 3 d | block state IDs wrong until 2.1.22 (46 d) |
| 1.21.5 | 2025-03-25 | 2.1.28 (2025-03-30) | 5 d | |
| 1.21.6/1.21.7 | 2025-06-17/30 | 2.1.33/2.1.34 | 3/1 d | 1.21.7/1.21.8 client issues until 2.1.38 (75 d) |
| 1.21.9/1.21.10 | 2025-09-30/10-07 | 2.1.39 (2025-10-04) | 4 d | captcha `filled_map` ID wrong until 2.1.41 |
| 1.21.11 | 2025-12-09 | 2.1.42 (2025-12-09) | 0 d | |
| 26.1 | 2026-03-24 | 2.1.46 (2026-03-30) | 6 d | PlayerInput ID wrong (S#648), unfixed in a release after 7 months |
| 26.2 | 2026-06-16 | 2.1.48 (2026-06-20) | 4 d | captcha showed "a pink dye instead of the filled map" until 2.1.51 (82 d) |
| 26.3 | 2026-09-15 | 2.1.52 (2026-09-27) | 12 d | |

First support usually lands within days, mostly from one contributor (FallenCrystal); per-version bugs
then survive 46 to 210 days. On top, platform churn broke Sonar several times: Velocity builds b266,
3.3.0, b347 and b360 (S#61, S#135, S#137, S#198) and the Adventure 5 migration (S#597 and six more).

### 4.3 Under attack

- No benchmark is published for 2.x; the site claims "Zero noticeable effect on your server's
  performance, even during bot attacks" and verification "typically 1-2 seconds". The maintainer
  refused to publish peak CPS because Sonar was used "to advertise their illegal botting or (D)DoSing
  tools" (S#155).
- The measured cost under attack was logging: "The CPU utilization was above 40%, and after filtering
  the console, it was only 10% -20%" (S#194, mcstorm against an i9-13900K). Console floods: S#371
  (deferred to 3.0), S#256.
- "If I hit my server with a botnet, it will die. And a ping check won't change that fact" (PR S#174);
  "If the server can't handle these connections, it will lag, not matter what anti-bot you have
  installed" (S#250, which points to TCPShield or NeoProtect).
- Memory: OOM at startup from cached map data (S#292), a ByteBuf leak on BungeeCord (S#325), unbounded
  structures moved to Caffeine (PR S#187), exceptions "pretty heavy to construct" replaced by stackless
  ones (S#372), pre-encoded packets "to reduce CPU usage" (PR S#286, idea from NanoLimbo).

### 4.4 Bypasses

- SoulFire passes everything (S#530). Map captchas fall to decoders and vision models (S#531: "Map
  CAPTCHA decoders have existed for years at this point"; S#575: "Bots work by reading the map data
  packet").
- A "ttProxy" bypass was patched with swing, minecart and slot checks (PR S#378, 2.1.6); the slot check
  had "never actually work[ed]".
- **Exemptions were bypasses**: "Fix Geyser exemption sometimes skipping Java players" (2.0.8); the
  Eagler exemption "caused all players to bypass protection" (PR S#547, shipped in 2.1.44, fixed in
  2.1.45).

### 4.5 Attack tracker

- `joinsPerSecond > minPlayers || verifyingPlayers > minPlayers || queuedPlayers > minPlayers`
  (PR S#174); pings and connections per second are deliberately not counted.
- The tracker "never ends attack" on BungeeCord (S#175); webhook statistics were absolute rather than
  per attack (S#106). Lockdown mode was removed in 2.0.10 (PR S#125) and is now a Sonar 3.0 feature
  (S#589).

### 4.6 Verified cache in practice

- Key: "a combination of the UUID and the IP address... If your IP or username changes, you must
  re-verify" (S#165); since 2.1.7, a hash of name and IP (§3.5 shows how weak). The docs still say
  "IP addresses are stored in plain text".
- Re-verification loops when the address is wrong: through the TCPShield plugin each join arrives from
  another address, so the player is verified on every join (S#555); "verified 4 times before joining"
  (S#611: "do not use the TCPShield plugin").
- No multi-proxy story: "a local cache is used... The cache does not receive any updates from other
  server instances" (S#366). Database bugs: wrong timestamp so that "when I rebooted the server all the
  players checking again" (S#331), H2 "randomly corrupts" (S#374), MySQL drops (S#428, S#446, S#513).
  The maintainer's own advice: "It doesn't really give you any advantage as IP addresses rotate on a
  daily basis" (S#513).

### 4.7 The reconnect, and what a plugin cannot do

- Verification always ends in a kick or a transfer: "Sonar is explicitly made to __not__ let the server
  know that the player has connected" (S#262). Skipping verification for premium accounts or by
  permission is impossible: "You'd have to authenticate before checking them" (S#489), "Sonar cannot
  act after the server loads player permissions" (S#537). The 1.20.5+ transfer requires lowering
  Velocity's login rate limit (S#365) and does nothing below 1.20.5 (S#433).
- Injection conflicts: LibreLogin (S#163), Protocolize (S#171), PacketEvents (PR S#347), other
  injectors causing transaction false positives (S#391: "this can't be fixed in Sonar 2.0 simply
  because of the way it injects").
- A wrong client address (HAProxy or FRP turning everyone into 127.0.0.1) makes per-IP limits and the
  blacklist hit real players (S#499, S#622).

### 4.8 Sonar 3.0

2.x is feature-frozen since S#416 (2024-11): "New feature requests for Sonar 2.0 will be closed, as 3.0
is in the making". Sonar 3.0 is a rewrite sold as a subscription (5, 10 and 20 EUR per month, capped at
150, 300 and 600 players), with a web captcha, cloud verification, lag compensation, multi-server,
IP reputation, a connection queue and lockdown. Whether it is closed-source or obfuscated is
[unverified] (one user in S#625: "3.0? it's obfuscated"). The open, free reference is therefore a
frozen plugin, and the features its users asked for most are behind a paywall.

---

## 5. The other anti-bots

Source read at pinned commits: LimboFilter `7d96b5b001`, LimboAPI `cd34c9c3b2`, EpicGuard (4drian3d)
`d890c92876`. BuiltByBit refuses automated fetches, so the BotSentry and nAntiBot store pages are
[unverified]; their own documentation sites were read.

### 5.1 LimboFilter and LimboAPI (Elytrium, AGPL-3.0)

**Checks** (`BotFilterSessionHandler`, `Settings`):

- Four modes: `ONLY_POSITION`, `ONLY_CAPTCHA`, `CAPTCHA_POSITION` (default) and
  `CAPTCHA_ON_POSITION_FAILED`; Geyser players have their own mode.
- Falling check: spawn in the void at Y=512, each Y delta compared with a precomputed table
  `-((0.98^t - 1) * 3.92)` within **0.01** (Sonar: 1e-7), up to 10 off-position moves and 10 frozen
  ticks tolerated, 128 ticks, 15 s overall (45 s for Geyser). Teleport confirmation ID fixed at 44. No
  collision, vehicle or transaction check.
- Map captcha: 3 characters, 2 attempts, a pool of 1,000 pre-rendered images regenerated hourly, so every
  player shares the same 1,000 answers for an hour [inference: solvable once, replayable].
- Client settings must arrive; the brand must not be on a block list. The maintainer: "It's easy to spoof
  brand packet and bypass everything" (LF#12).
- An experimental libpcap "TCP listener" compares the kernel RTT with the Keep Alive RTT to spot proxied
  bots; it needs `CAP_NET_RAW`, "doesn't work if Velocity is behind reverse-proxy", and shares plain
  `HashMap`s between the pcap thread and Netty threads ("TCP Listener too experimental", LF#48).

**Placement, adaptive: the prior art for §9.6.** All thresholds compare a decaying connection counter
(300 s unit) with fixed values: under `online-mode-bypass` (49) online-mode players skip the checks;
above `online-mode-verify` (79) unverified players are **forced into offline mode, so Mojang
authentication is skipped until they pass**; above `need-to-reconnect` (129) players must reconnect
after passing (below it, LimboAPI hands them to the backend in the same session); above `disable-log`
(129) the **root log4j level is set to OFF for every plugin** during the attack. The maintainers keep
the checks on outside attacks because bots would "join once an hour" otherwise (LF#2).

A trap in those defaults: `online-mode-verify` is documented as "Only works if you have an auth plugin
installed. In other cases you should configure need-to-reconnect parameter". With the defaults (79 and
129) and no auth plugin, a player forced into offline mode between the two thresholds passes the check
and is sent on to the backend **unauthenticated** (`BotFilterSessionHandler` line 339: below
`need-to-reconnect` it leaves the limbo instead of disconnecting) [inference from source and comments,
not tested]. Warp's pre-auth path therefore always ends in a transfer or a reconnect (§9.6).

**Verified cache:** `ConcurrentHashMap<username, (ip, expiry)>`, a hit needs the same name and the exact
IP, 1 h TTL (up to about 2 h with the hourly purge), memory only, cleared on reload and restart. A
shared cache for several proxies has been requested since 2022 (LF#8).

**LimboAPI's world:** packets prepared once per protocol version and pre-compressed (FastPrepareAPI),
Join Game for about 10 version ranges, 1.20.2+ configuration with Start Configuration to go back from
PLAY, versions 1.7.2 to 26.3. It **never uses Known Packs**: the full registry NBT is sent for every
version from 1.20.5, with hand-written registries in Java. That hand-maintained data broke 1.21.11
clients ("Registry must be non-empty: zombie_nautilus_variant", LA#226, open) and is behind its longest
lags. Abuse caps exist (chat 256, brand 64, unknown packets 2,048 B). It injects into Velocity internals
by reflection and needed fixes for Velocity builds b426, b439, b582, b584 and b586 (its commit log),
and again for build 555 (LF#117, open).

**Version lag** (first support on master): 0 to 13 days for most releases, **82 days for 1.20.2**,
21 days for 1.20.3, **71 days for 1.21.9** (only a CI build meanwhile, LA#220), 1 day for 26.3. No
tagged LimboAPI release between 1.1.26 (2024-09-03) and 1.2.0 (2026-11-01); LimboFilter's last tag is
1.1.18 (2024-06-19).

**The benchmark everyone quotes** (README since 2021-12-30): "i7-3770 ... OpenJDK 11 ... Attack: Motd +
Join bot attack (100k joins per seconds, 1.17 Protocol)": Velocity + LimboFilter 20% CPU, plain Velocity
offline 55%, online 70%, BotSentry 9.2 70%. No tool, no source address count, no bandwidth, never re-run.
Part of the 20% is plausibly the logging switched off above [inference].
`security-hardening-architecture.md` §4.1 repeats it as "3.5x plus efficace": it is a single
unreproduced 2021 run.

**False positives and bugs:** Geyser detected by `remote port == 0` with at least five open Bedrock issues
(LF#47, LF#107, LF#108, LF#127: Bedrock players "always fail the Falling Check"); captcha invisible on
1.12.2 and 1.8 (LF#75, LF#84, LF#125, LA#265); keep-alive kicks for laggy players ("change hosting or
tune max-ping", LF#36; "kicked after 1 second" open 16 months, LA#77); Forge 1.20.1 (LA#197); "Never
install ViaVersion on proxy!" (LF#45). Captcha memory: 16 KiB × 1,000 images × 5 palettes, about
78 MiB, and about 420 MiB with packets prepared for 27 versions, doubled during a reload; an OOM from a
flood of invalid protocol numbers (LA#108).

### 5.2 EpicGuard (GPL-3.0, discontinued)

- 4drian3d/EpicGuard is archived ("discontinued; the last published version will be 7.6.1", 2025-08-29);
  a fork, EpicGuardPlus, continues with no users to speak of.
- No limbo: a per-IP pipeline at pre-login: GeoIP (off), proxy/VPN through a **synchronous HTTP call to
  proxycheck.io** inside the pre-login continuation, 3 accounts per IP (1 during an attack), Client
  Settings within 5 s, a name regex `(?i).*(bot|mcdown).*`, name similarity (off), reconnect check and
  "pinged the server list before joining" check (both during attacks), lockdown.
- Attack mode at **6 connections per second**, left only when a check every 80 s sees fewer;
  `lockdownOnAttack = true` with auto-whitelist off means an attack kicks every non-whitelisted player,
  regulars included [inference from defaults].
- Storage: an unbounded Caffeine cache with an entry, saved to the database, **for every connecting
  IP**; an unbounded, non-thread-safe `HashSet` for pings; a reconnect set never cleared.
- Issues: the ping-first check fails players who did ping (EG#65, open); bots that join "once every 5-10
  minutes" evade per-second attack detection (awumii/EpicGuard#160, open).

### 5.3 Paid and closed: BotSentry, nAntiBot, Sonar 3.0

- **BotSentry** (closed, obfuscated [unverified]): "Really smart % system to differentiate between
  players and bots", bad-packet prevention, Redis for several BungeeCords, a web captcha on the vendor's
  domain "to join during a really strong attack", "stopping speed attacks with more than 30.000 bots per
  second". The only independent number is LimboFilter's 2021 run: 70% CPU, the same as plain online-mode
  Velocity.
- **nAntiBot** (free with a premium tier): attack thresholds per kind (30 connections, 20 handshakes,
  5 logins or 15 pings per second) with separate durations; a "strange name" regex listing known botnet
  name patterns (MCStorm, MCDown, FlexBot...); map captcha with `max-sessions: 2000`, falling back to a
  **web captcha** when full; a **cloud verdict** waited for up to 1 s for at most 25 concurrent players;
  premium **OS firewall** through `ipset` and an `iptables -t raw` DROP rule (root, Linux);
  `collect-data: true` by default, including "samples of: packets, messages and commands received from
  players". "A rework of nAntiBot is underway."
- **Sonar 3.0**: §4.8.

Others seen in 2025-2026, none relevant at scale: UltimateAntiBot (licence unclear, last update 2024),
AlixSystem (proprietary), justcaptcha (a map captcha on LimboAPI), Jarvis AntiVPN, BloomAB (feeds a
host's edge filter, "not meant to block very slow bot attacks").

### 5.4 What the attack tools do

A public stresser README (2022) lists 24 methods, which map one to one onto the threat model: `join`,
`botjoiner`, `doublejoin`, `legitnamejoin`, `pingjoin` (A8 with realistic names); `ping`, `legacyping`,
`nullping`, `query` (A4); `handshake`, `bighandshake`, `longhost`, `localhost` (A3, A5); `bigpacket`,
`emptypacket`, `randompacket`, `unexpectedpacket`, `invaliddata`, `memory` (A3, A5); `spoof`,
`invalidspoof` (forged forwarding in the host field); `invalidnames`, `longnames`. Real-client bots are
mainstream: SoulFire (AGPL-3.0, active, Microsoft and offline accounts, SOCKS and HTTP proxies, its README
sponsored by proxy sellers) and mineflayer (1.8 to 26.1, with a physics engine).

---

## 6. What the proxies do natively, and what their trackers say

About 215 threads were read in full (110 BungeeCord, 61 Velocity, 22 Waterfall, 17 Gate, 3 Netty),
with the patches of the commits maintainers cite as fixes.

### 6.1 Ten years of "use a plugin, a firewall or TCPShield", then rate limits by default anyway

- Every request for a built-in anti-bot was refused: B#44 (2012, "This shouldn't be a feature, but a
  plugin to Bungee"), B#1657 (2015, packet limiter: "if there were then a targeted fix would be more
  appropriate"), B PR#3257 (2022, throttle event: "The point of the throttle is to do as little as
  possible and close"), W#528 (2020: "everybody is going to want different behaviors"), W#565 and W#607
  ("firewall"), V#568 (2021, below), V PR#338 (2020: "That is an attack that really needs to be
  mitigated at a higher layer", reverted as "creating more problems than it actually solves"), G#96
  (2022-2024, LimboFilter-style checks: "Checkout the Gate Lite mode in combination with OVH DDoS
  protection"). No tracker has a proposal for a built-in limbo or captcha.
- And yet all three Java proxies now ship limits on by default: BungeeCord's packet limiter (B PR#3733,
  2025: 4,096 packets/s and 32 MiB/s, measured over 8 h on a PvP network, reversing B#1657) and stricter
  status refunds (B PR#3815, 2025); Velocity's packet limiter (`0219993c8`, 2025) and decompressed-bytes
  limit (V PR#1786, 2026, 5 MiB/s); Gate's connection and login quotas, packet limiter (G PR#691, 2026)
  and PROXY allow-list (G PR#936, 2026).

### 6.2 Fake authentication: the attack the proxies declared unfixable

- **V#568 (closed won't fix, 2021) "Denial of Service using Mojang isJoined"**. astei: "we have no
  choice but to do an RSA decryption"; "the only practical mitigation on our end is a global rate limit
  on login attempts, which would protect the server at the cost of frustrating legitimate users.
  Therefore the only good solution is to use a Minecraft-specific protection solution, such as TCPShield
  or Cosmic Guard."
- W#528 (2020): 50 to 700 connections per second with random names got the proxy's address "blocked by
  mojang for a bit due to too many requests", then an OOM. It is the only explicit report of Mojang
  blocking a proxy in the four trackers; no thread mentions HTTP 429.
- Gate's login quota exists for this reason (code comment: "preventing too fast logins hitting the
  Mojang API"): 0.4 logins per second, burst 3, per IPv4 /24 or IPv6 /64.

Warp's answer (§9.4, §9.6) is the one astei did not consider: the global budget **plus a priority lane
for known identities** and **verification before authentication during an attack**, so the budget does
not frustrate legitimate users.

### 6.3 Ping floods, null pings, legacy pings

- "nullping" or "bungeesmasher": B PR#2908 (2020, astei): a 1,024-byte read can carry 512 framed
  invalid packets, each throwing after the channel is closed; CPU fell from 550% to about 290% once dead
  channels stopped being decoded. Netty keeps decoding after close by design (N#10417: "this is expected
  as otherwise you may would loose data"). Velocity answered by strictly validating the status state
  machine with static exceptions (`c3dcf9b1dd`, 2020).
- Status floods that never send the ping: "300.000 connections established in a 10.000 connections per
  second attack", each waiting 30 s (B PR#2908 thread). BungeeCord's refund of the throttle now requires
  a real status response and ping (B PR#3815, 2025); refusing handshake, status and ping in one read was
  rejected as trivially bypassed (B#3818).
- Legacy ping: V PR#1908 (open, 2026) found that a modern frame of length 254 or 382 starts with `0xFE`
  and was parsed as a 1.6 ping (Floodgate hostnames trigger it). Legacy ping also serves as an HAProxy
  layer-7 health check (B#3689), so it must stay answerable.
- Log spam from pings is the oldest complaint (B#401 in 2013, `log_pings` in B PR#2494, V#484, V#1597:
  "especially for network level stuff where the trace provides no real context"); Gate fixed its own in
  G PR#910 (2026).
- UDP query floods (W#565, 200k spoofed packets per second) were declared a firewall matter; Velocity's
  GS4 handler still logs at WARN on every short datagram (V#1828, open).

### 6.4 Slowloris and oversized frames

- W#607 (open, 2021): "bungeecord is still waiting for the 'Login Start' packet... to finish over the
  wire... The problem is the Varint21FrameDecoder". W PR#609 limited the first four frames and timed out
  incomplete ones: "The attack sends a large packet size and sends the data very slowly but frequently
  enough to not trigger a timeout"; it died over modpack compatibility.
- B PR#3066 (2021) moved BungeeCord's read timeout after the frame decoder so that only complete frames
  reset it. W#627 (2021): "40 requests" filled 1 GB of direct memory through 16 MiB pooled chunks
  ("akin to the slowloris attacks done against apache").
- Velocity added upfront size checks for login packets in 2021 (`5ceac16a`) and per-packet pre-login
  frame validation on 2026-10-06 (`374acad83`).
- Gate had **no timeouts at all**: a peer that connects and sends nothing "parks a goroutine and fd
  forever" (G PR#936), and configured timeouts were multiplied by a millisecond twice, so `readTimeout:
  30000` meant 347 days (G PR#1342, open).
- V#1819 (open, 2026): Velocity's 30 s read timeout equals the client's own, so it "effectively does
  nothing in some scenarios".

### 6.5 Exceptions, memory and leaks under attack

- md-5's benchmark (B#2743, 2020): at least 5,000 requests per second on four cores "regardless of what
  type of 'exploit' is used"; logging was the cost, and "throwing exceptions is not a bottleneck" once
  Netty stopped calling `getStackTrace()` in `inExceptionCaught` (N#10165).
- V#1742 (open, 2026): proxies OOM-killed by floods of claimed 8,380,416-byte decompressed sizes; the
  decompressed-bytes limit (V PR#1786) is "more workaround then fix. Root cause is still unknown"
  (Leymooo). A ratio limit tried on the way kicked vanilla book writers (V#1792, ratio 138.8).
- V PR#1715 (2026): a ByteBuf leak on the frame decoder's exception path was "being abused by a
  malicious actor with the intent of crashing Velocity servers for ransom", found with Netty's leak
  detector under attack. V PR#1800: the pre-join plugin-message queue was unbounded (about 1 GB per
  minute could be queued). B PR#3742: a client that does not answer a cookie request can re-send Login
  Start and re-fire events.

### 6.6 Keys, PROXY protocol and offline mode

- BungeeCord's throttle keys the full address, and is **turned off entirely** when the PROXY protocol is
  on (B#2590: "it is expected that you disable the throttle and let haproxy or iptables handle it";
  B#2599, commit `1a06ebeee0`).
- Velocity's login limiter keys the full `InetAddress`: on IPv6 it "is effectively a NOP" (V PR#1897,
  open, adds /64 and /48 buckets). Gate keyed IPv6 /120 until G PR#938 (now /64, IPv4 /24). Gate's LRU of
  1,000 entries per quota can be churned by more than 1,000 prefixes (inference, not from a thread).
- PROXY headers must come only from trusted peers, or an attacker can "assert an innocent third party's
  IP and get THEM banned" (G PR#936).
- Offline mode is unsupported by Velocity's and BungeeCord's maintainers ("We don't support garbage
  that only exists for piracy", V#1195). Name checks: a strict `[A-Za-z0-9_]{1,16}` "rejects essentially
  every Bedrock player" (V#1827, Floodgate prefixes); control characters should be rejected (V PR#1885).
  Duplicate-name races reproduced with "4 or more bots at a time with the same nickname" (B PR#3438).
  Gate reserves names against cracked logins (G#980, G#1118).

### 6.7 Native features at a glance, and Infrarust

- **Velocity** (`default-velocity.toml`, `dev/4.0.0`): `login-ratelimit` 3,000 ms per exact address for
  login intents only (pings are not limited), a 30 s login timeout, a 30 s read timeout in every state,
  `show-ping-requests` off, a packet limiter whose only default limit is 5 MiB/s of decompressed bytes
  over 7 s, and pre-login frame validation. Status: one request per connection, no cache;
  `ping-passthrough` pings backends for every client ping with no cache [inference from source]. No
  anti-bot beyond that.
- **BungeeCord**: `connection_throttle` 4,000 ms with `connection_throttle_limit` 3, at channel init, per
  address, **disabled when any listener uses the PROXY protocol**; a completed ping refunds the throttle;
  `log_pings` on by default; a packet limiter since 2025.
- **Gate**: token buckets per IPv4 /24 or IPv6 /64 for connections (5/s, burst 10) and logins (0.4/s,
  burst 3) in LRUs of 1,000 entries (an evicted bucket comes back full [inference]); a per-connection
  packet limiter of 500 packets/s **on by default**; Lite mode's ping cache (10 s, singleflight per
  backend and protocol, fixed from a sliding TTL in G PR#916); trusted PROXY peers. No verification of
  any kind.
- **Infrarust** (v2 beta, AGPL-3.0): per-IP login and status rate limiters, **off by default**, in a map
  never trimmed [inference: unbounded]; CIDR allow and deny lists; bans by IP, range, name or UUID in a
  JSON file; a 5 s status cache whose `max_entries` "does not currently enforce a hard eviction limit"
  (its docs). Its limbo engine captures registries from real backends (§9.8.2).

### 6.8 Corrections to earlier Warp documents

- **B#2854 no longer exists** (404, deleted around 2020-06). `security-hardening-architecture.md` and
  `edge-cases-production-horror-stories.md` cite it as "crash at 200-300 req/s" of pings; that cannot be
  checked, and md-5's own benchmark in B#2743 reports more than 5,000 requests per second.
- **B#3358 is not about slowloris** ("Bungeecord Stops Working Randomly", a panel and plugin problem).
  The slowloris references are W#607, W PR#609, B PR#3066 and B PR#2908.
- **B#1328 is not about UUID spoofing** but a forwarding redesign for FML; the spoofing references are
  B#3180, B#2174, B PR#2549 and W#833.
- PR #123 cites B#2570 for "1.7 clients ping then connect": that reasoning is in B#1514, B#1655 and
  B#1665; B#2570 is the CPU "crasher" whose fix moved the throttle to accept time and refunds it after a
  status response.
- V#1229 is a misconfiguration (Velocity waiting for a PROXY header HAProxy did not send), not a
  Velocity bug; V#1699 is not a PROXY protocol bug.
- `security-hardening-architecture.md` §4.1 calls LimboFilter "3.5x plus efficace en CPU" than
  online-mode Velocity: that is LimboFilter's single, unreproduced 2021 run (§5.1), with logging
  switched off during attacks. Its §4.3 ("Warp ne DOIT PAS intégrer de CAPTCHA dans le core") is
  superseded by decision D1.

---

## 7. L4 and L7 protection services: what they leave to the proxy

### 7.1 What the services do

| Service | L7 filtering for Minecraft (their docs) | Real client address | What it costs |
|---|---|---|---|
| TCPShield | "checks against incoming Minecraft clients... ping attacks, encryption attacks, and large amounts of server joins"; premium: above a connections-per-second threshold (around 5 for small servers) "we issue a challenge to all incoming connection[s]", with ban and allow durations; VPN, IP, ASN and country firewalls | PROXY protocol v2 (since 2022) **or** the RealIP plugin: the handshake host carries `host///ip:port///timestamp///signature`, an ECDSA (SHA512) signature checked against a bundled key, 3 s of clock skew, no nonce [inference: replayable within 3 s] | free 1 TB; 25, 100, 250 USD per month; the L7 mitigation panel from Premium |
| NeoProtect | an edge anti-bot that "verifies that each connection is a real player", status cached at the edge for "a few seconds" per domain | PROXY protocol | anti-bot from the Universal plan, anti-VPN from Neo |
| Infinity-Filter | per-IP rate, per-network connection rate, protocol fingerprinting ("handshake patterns, packet ordering, version probes"); modes Off, Default ("a basic rejoin check"), Hardcore (web verification); they warn of a "brief antibot kick storm" after every proxy restart | PROXY protocol or their plugin | |
| CosmicGuard | a filter that "knows what real Minecraft traffic looks like" (a host's documentation) | PROXY protocol | pay-as-you-go [unverified] |
| Papyrus | "Some of our plans are able to filter Layer7 Attacks (Minecraft bots)"; on the top plan "AntiBot" is off by default because "the machine would not handle it very well" | PROXY protocol | 10 to 50 USD |
| OVH Game firewall | protocol-aware profiles for Minecraft Java (TCP) and Bedrock (RakNet cookies); no bot verification | none needed (same host) | included with OVH game servers |

### 7.2 What they leave to the proxy

- Everything inside the encrypted session: Infinity-Filter says so explicitly, and online-mode traffic is
  encrypted from the Encryption Response on.
- Slow, low-rate bots that pass a rejoin check, and bots with real accounts.
- The verified state of players across restarts (the "kick storm"), and anything that needs the game's
  state machine past the handshake.
- Hygiene behind them: accepting PROXY headers only from their ranges, and refusing direct connections to
  the origin.

### 7.3 Recommendations for L3 and L4 (out of Warp's scope)

- Upstream filtering sized for volumetric attacks: a Minecraft-aware service (TCPShield, NeoProtect,
  Infinity-Filter) or a host with a game firewall (OVH Game). A JVM cannot drop packets before the
  kernel has spent resources on them, and the uplink saturates first anyway (astei in V PR#338: "It's much
  more expensive for the proxy to block (or drop) the connections than have the kernel deal with it").
- Kernel settings in Warp's documentation (`security-hardening-architecture.md` §2.1 already lists SYN
  cookies and backlog sizes).
- Behind a service: PROXY protocol on, `trusted-proxies` set to the service's published ranges (PR #122
  supports CIDR lists), and the origin firewalled to those ranges only. Warp does not implement
  TCPShield's signed-hostname plugin format: PROXY protocol v2 is the standard path and needs no shared
  key.
- Self-hosted: feed Warp's blocklist endpoint (§9.13) to an nftables set or an XDP program, which is
  what nAntiBot premium does with `ipset` and what BungeeCord contributors do with XDP (B PR#3857,
  B#3818), without giving Warp root.

---

## 8. Comparison

"Proxy" in a plugin's column means the plugin does nothing and leaves it to the proxy it runs on.

| | Sonar 2.1.53 | LimboFilter | EpicGuard 7.6.1 | Velocity | BungeeCord | Gate | **Warp (this design)** |
|---|---|---|---|---|---|---|---|
| Form | plugin, injects by reflection | plugin on LimboAPI, injects by reflection | plugin, pre-login events | native | native | native | native |
| Connections at accept | proxy | proxy | proxy | none | 3 per 4 s per IP, **off behind PROXY** | 5/s burst 10 per /24 or /64, LRU of 1,000 | GCRA per /32 or /64 (PR #123) + global cap on connections not yet playing |
| Pre-login frame caps | proxy | proxy | proxy | per packet since 2026-10-06 | first packets only | payload caps (G PR#689) | per packet, on the frame header |
| Ping floods | nothing (removes itself on status) | drops the favicon above 25 | ping-first check in attacks | one request per connection, no cache, no limit | throttle, refund after a real ping | connection quota, Lite cache with singleflight | cached bytes, own per-address budget, 5 s deadline |
| Login rate | 1 per 5 s per exact IP | none | reconnect check in attacks | 1 per 3 s per exact address (IPv6 /128) | throttle | 0.4/s burst 3 per /24 or /64 | burst 2 per 3 s per /32 or /64, 2 in progress, 30 s timeout |
| Fake authentication (A7) | always verified before auth | forced offline above a counter (needs an auth plugin, §5.1) | proxy | "won't fix" (V#568) | none | login quota | auth budget + priority lane for known players + verification before auth during attacks |
| Protocol probe | Keep Alive echo, configuration, brand, Client Information | Client Information present, brand block list | Client Information within 5 s | none | none | none | configuration probe (1.20.2+), pre-join Keep Alive (1.8+), Known Packs, cookie |
| Physics | gravity (1e-7), collision, vehicle, transaction, slot, swing | gravity (0.01) | none | none | none | none | teleport echo, gravity (1e-7), collision, ping/transaction |
| Captcha | map, off by default | map, on by default | none | none | none | none | map, escalation only; SPI for web or Discord verification |
| Attack detection | joins, verifying or queued > 8 | 8 fixed thresholds on a decaying counter | 6 connections/s, lockdown on attack | none | none | none | learned baseline on unknown identities, levels, manual lockdown |
| Verified cache | 32-bit name+IP fingerprint, 5 d from verification, **memory by default** | name + exact IP, 1 to 2 h, memory only | IP whitelist (off), a DB row per IP | none | none | none | account or name+/24 (/48), keyed HMAC, 30 d after last join, persisted, bounded |
| Several proxies | no (S#366) | no (LF#8) | DB | | | | shared-store SPI + signed cookie tokens |
| Reconnect after verification | always (kick, or transfer 1.20.5+) | none below 129 on the counter, then always | n/a | | | | never, except before-auth verification during attacks (transfer 1.20.5+) |
| Bytes per verification (1.21) | about 175 KB, uncompressed | full registry NBT, pre-compressed | n/a | | | | under 4 KiB [estimate], Known Packs |
| Left on the PLAY path | two handlers, one writing shared statics per buffer | not measured | event listeners | packet limiter | packet limiter | packet limiter (500 pps) | nothing |
| Versions and lag | 1.7.2 to 26.3; first support 0 to 28 d, latent bugs 46 to 210 d | 1.7.2 to 26.3; 0 to 82 d | plugin-level | 1.7.2 to 26.3 | | | 1.7.10 to 26.3; generated tables, e2e matrix per release |
| Under attack, visibility | logs per verification (off in attacks), action bar, webhook | **all logging switched off** above 129 | logs | quiet decode errors by default (V#1597) | `log_pings` true by default | an INFO line per quota refusal | metrics, one summary line per interval, `trace`, blocklist endpoint |
| Licence, state | GPL-3.0, frozen; 3.0 is paid | AGPL-3.0, active, rare releases | GPL-3.0, archived | | | | AGPL-3.0 |

---

## 9. The design

### 9.1 Principles

1. **The attacker pays more than the proxy at every step.** Cheap, stateless checks come before
   expensive, stateful ones. Nothing expensive (RSA, a Mojang request, a backend login, a plugin event)
   happens for a connection that has not passed the checks that apply to it.
2. **Verified players never pay.** Once verified: no extra round trip, no extra latency, no handler left
   in the pipeline, nothing on the PLAY path.
3. **Identity is what the attacker must pay for.** In online mode that is the account. In offline mode
   it is the pair (name, address prefix), because names are free.
4. **Everything is bounded.** Every map, queue, buffer and timer has a hard limit and a defined overflow
   behaviour, chosen so that overflowing hurts unknown connections first and verified players last.
5. **Measure, then escalate.** Attack mode comes from measured rates and failure ratios against a
   learned baseline, not from a fixed number. Escalation follows what the attack does.
6. **No reconnect unless authentication forces it.** Warp owns the connection, so a verified player
   goes on to the backend in the same session.
7. **Never fail silently.** Every refusal has a reason code, a counter and a place in an aggregated log
   line; an operator can trace one address end to end.

### 9.2 The pipeline at a glance

```
accept ── S0 admission ── S1 handshake ──┬─ STATUS ── cached bytes, per-address budget
                                         └─ LOGIN ── S2 login hygiene ── identity lookup
                                                       │
                     known identity ───────────────────┼──── (online: authenticate) ── backend
                                                       │
                     unknown identity ── S3 probe ── S4 world ── [S5 challenge] ── store ── backend
                                                     (placement per mode and attack state: §9.6)
```

| Stage | Who | Protocol surface | Stops |
|---|---|---|---|
| S0 admission | every connection | none | A2, part of A3 |
| S1 pre-login hygiene | every connection | frame headers, Handshake, Status | A3, A4, A5 |
| S2 login hygiene | every login | Login Start, encryption | A6, A7 (budget), A12 |
| S3 probe | unknown identities | login and configuration packets (1.20.2+), one Keep Alive (1.8 to 1.20.1) | stateless and protocol-incomplete bots (A7 in attack mode, part of A8) |
| S4 world check | unknown identities | a virtual world for 1.7.10 to 26.3 (§9.8) | headless bots without physics (A8, A9) |
| S5 challenge | escalation only | a map item and chat | bots that simulate physics (part of A10) |

### 9.3 S0 and S1: admission and pre-login hygiene (every connection)

- **Per-address budgets** stay as PR #123 built them (GCRA, IPv4 /32, IPv6 /64, separate budgets for
  connections, pings and logins, Caffeine maps capped at 100,000 keys).
- **A global cap on connections that are not playing yet** (default 8,192): over it, the new socket is
  reset before any handler is installed. It bounds what a botnet with many addresses can hold, which
  per-address budgets cannot.
- **Per-state frame caps, checked on the frame header.** As soon as the length and packet ID are
  readable, the frame is checked against the largest legal packet of that ID in that state (the
  approach of Velocity's `374acad83`): a handshake is at most about 0.8 KiB (a 255-character
  hostname), a status request 1 byte, a ping 9 bytes, a Login Start a few KiB (the 1.19 profile key is
  the largest field), a Login Plugin Response 1 MiB (vanilla's bound; Forge's negotiation needs it). A
  frame announcing more is refused before a byte of its payload is buffered.
- **State deadlines instead of one 30 s read timeout:** 5 s from accept to the handshake (PROXY header
  included), 5 s for a whole status exchange, then the existing 30 s login timeout. A vanilla client
  sends its handshake at once and finishes a ping in one round trip. These are absolute deadlines, so
  trickling bytes does not extend them; after login, the idle timer moves after the frame decoder and
  counts complete frames only (B PR#3066).
- **Status from bytes.** The status answer (and the legacy ping answer) is served from the
  pre-serialised block of PR #134; a ping costs a buffer duplicate.
- **Silence.** Pre-login exceptions are the cached, stackless instances Warp already uses; time-outs,
  unknown `nextState`, unsupported protocols and failed authentications become counters in the
  aggregated report line, never one line each (§1.2). An unsupported protocol gets the vanilla
  "Outdated client/server" login disconnect, which costs nothing and helps real players.

### 9.4 S2: login hygiene and the authentication budget (every login)

- Name pattern (exists), duplicate-login policy (exists, `AUTO` refuses the newcomer in offline mode),
  login rate and logins in progress per address (PR #123), login timeout (PR #123).
- **Login Start UUID as a signal.** From 1.20.2 every client sends a UUID in Login Start (optional from
  1.19.1). A vanilla client sends the UUID of its session's profile; bot libraries often send a random
  or offline UUID. In online mode, a mismatch with the authenticated UUID is recorded as a signal that
  sends the identity to the world check even when verification would otherwise be skipped. It is never
  a verdict on its own.
- **A priority lane for authentication.** The 256 authentications in flight (PR #123) are the scarce
  resource during a fake-authentication flood (A7). When they are all taken, a login whose
  (name, prefix) key is in the verified store gets the next free slot first; unknown logins wait in a
  bounded FIFO (default 1,024 entries, 10 s at most), then are told "the server is busy, try again in N
  seconds". Returning players log in during a flood; unknown ones wait their turn instead of timing out.

### 9.5 Identities and the verified store

**Keys.** A key is the first 16 bytes of HMAC-SHA256 over a domain-separated input, with an
installation secret generated at first start:

| Key | Input | Used for |
|---|---|---|
| K1 | `"account:" + uuid` | online mode, after authentication: the identity |
| K2 | `"name:" + lowercase(name) + "@" + prefix(address)` | offline mode: the identity. Online mode: recognising a returning player **before** authentication (priority lane, skipping pre-auth verification in attack mode) |

`prefix` is the IPv4 /24 or the IPv6 /48. Address churn inside a provider's pool must not force a new
verification (the loops of S#555 and S#611), and loosening the prefix gives an attacker nothing: in
offline mode one name holds one session (`AUTO` refuses a second), and in online mode a K2 hit only
saves the pre-auth check; the login still has to authenticate as that account.

The store holds no names and no addresses in clear, unlike Sonar's reversible fingerprint (§3.5). An
operator looks an identity up by name and address; Warp computes the key. In online mode, every
verification and every join of an account also stores the K2 of its current name and prefix, which is
what lets a returning player be recognised before authentication.

**Record and capacity.** A record is 32 bytes (key, last seen and verified-at in minutes, method,
flags), in primitive arrays with open addressing: no object per entry, no GC pressure. Default capacity
250,000 identities, about 10 MiB [estimate]. When full, the least recently seen of a random sample of 8
slots is evicted (the sampled LRU of Redis). Reads at login are lock-free; writes happen once per
verification and once per join (last-seen refresh).

**Expiry.** Sliding: 30 days after the **last join**, not after the verification (Sonar's 5 days from
verification re-verifies a daily player every 5 days).

**Persistence.** On by default, no database: a snapshot file plus an append-only journal in Warp's data
directory, flushed every second and on shutdown. A crash loses at most one second of verifications; a
restart during an attack loses none (Sonar's default `NONE` and LimboFilter's memory-only cache lose
all; Infinity-Filter documents a "brief antibot kick storm" after every proxy restart for the same
reason).

**Fleets.** Two complementary mechanisms:

- a `VerifiedStore` SPI, so a plugin can back the store with Redis or a database shared by every proxy
  (the local store then caches it);
- a **signed verification token** stored in the client as a cookie (`warp:verified`, 1.20.5+): version,
  issue time, K2, flags, and an HMAC-SHA256 tag truncated to 16 bytes, valid 10 minutes, under a secret
  shared by the fleet (two secrets accepted during a rotation). It carries a verification across the
  transfer hop of §9.6, whichever proxy behind the load balancer receives the player, with no shared
  state at all.

### 9.6 Where verification runs

| | Offline mode | Online mode, normal | Online mode, under attack |
|---|---|---|---|
| Unknown identity | Login Success (offline profile), S3, S4, then the backend in the same session | authenticate, then S3, S4 for an unknown K1, then the backend in the same session | **before authentication**: S3, S4 for an unknown K2, then a Transfer back with the signed token (1.20.5+) or a "verified, please reconnect" disconnect (older). The next login arrives with the transfer intent and its K2 is now known on this proxy, and a login-state Cookie Request returns the token on any other proxy behind the load balancer; it skips S3/S4, authenticates, and its K1 is stored |
| Known identity | straight to the backend | authenticate, straight to the backend | known K2: priority lane, authenticate, straight to the backend |

Why the placement moves in online mode:

- In normal times, verifying after authentication costs no reconnect, and its subject is the account,
  the thing a bot farm must buy. Fake-authentication attempts are bounded by the per-address limits and
  the authentication budget.
- During an attack, fake authentication (A7) is what hurts. The attack detector (§9.10) moves
  verification of unknown players before authentication, as Sonar always does. Only players who are
  new **and** arrive during an attack pay the transfer hop; everyone known goes straight through.
- A player verified before authentication is **never** admitted without authenticating: that path
  always ends in a transfer or a reconnect, and the next login authenticates normally. LimboFilter's
  `online-mode-verify` is the prior art for moving verification before authentication under load, but
  with its defaults and no auth plugin, players forced into offline mode can reach the backend
  unauthenticated (§5.1), and its trigger is a fixed counter rather than measured signals.

**The hand-off.** The verification world is a virtual server inside Warp's switch machinery. A 1.20.2+
client goes back to CONFIGURATION through Start Configuration (`ReconfigurationSwitch`); an older
client receives the backend's Join Game followed by a Respawn (`RespawnSwitch`), the path every server
switch already takes. The backend connection is opened **after** the verification passes: a bot never
costs a backend login, a plugin join handler or a chunk.

### 9.7 S3: the probes

**Configuration probe (1.20.2+).** It runs in the client's first CONFIGURATION phase, before Warp opens
the backend connection, with no world:

1. The client sends its brand and Client Information on entering the phase (vanilla always does; Warp
   already caches Client Information for replay). Required before the deadline; the brand must be
   printable UTF-8 of at most 128 bytes, the view distance at least 2. No allow-list of brands, no
   locale check (Sonar removed its own "due to a Minecraft bug", 2.1.35).
2. No Select Known Packs of the proxy's own. The idea was to offer `minecraft:core` at the client's
   version and compare the answer, but node-minecraft-protocol (mineflayer, and every bot and tool
   built on it) answers one offer per configuration phase (`client.once('select_known_packs')`): the
   probe's offer spent that answer, the backend's offer then waited forever, and every 1.20.5+ client
   of the e2e matrix hung in configuration. A probe must ask nothing a server asks too.
3. A Ping and a Keep Alive with random IDs, echoed. The ping also measures the round-trip time used to
   size every later deadline (§10).
4. When the verification runs before authentication (online mode under attack) and passes, Store
   Cookie writes the signed token (1.20.5+) just before the Transfer; the next login reads it back with
   a login-state Cookie Request (§9.6).

A wrong echo, a packet that is illegal in the state, or a missing brand or Client Information at the
deadline fails the probe. It costs a handful of small packets and one or two round trips.

**Pre-join probe (1.8 to 1.20.1).** After Login Success and before Join Game, a Keep Alive with a random
ID must be echoed: Sonar relies on this behaviour on every version from 1.8, and skips it on 1.7 (it
waits 100 ms instead). The brand and client settings of these versions arrive after Join Game: until the
world check exists (phase 3), Warp checks them passively during the first seconds on the backend, from
packets it already decodes.

The probe is not enough alone: a scripted bot that echoes IDs passes it. It removes stateless floods
and incomplete bots at almost no cost, and it is the whole of what phase 2 adds.

### 9.8 S4: the virtual world check

**Sequence**, with every random value drawn **per session** (Sonar draws positions, teleport IDs and the
pre-join Keep Alive ID once per process start, §3.8):

1. 1.20.2+: registries and tags, then Finish Configuration (§9.8.2).
2. Join Game (flat void overworld, adventure mode), Default Spawn Position (1.19.3+), a teleport to a
   random height with a random teleport ID, Game Event 13 "start waiting for chunks" (1.20.3+), an
   empty chunk column under the player (nine on 1.20.3+, one before, as Sonar does), and a platform of
   one block type drawn from a set with different collision heights (full block, slab, carpet, snow
   layers), at a random height.
3. Expected back: the teleport confirmation with that ID (1.9+); a first position exactly at the
   teleport target and not on the ground; Y deltas that follow `v' = (v - 0.08) * 0.98f` within 1e-7
   for a random number of ticks between 8 and 12; then a position on the ground exactly at the top of
   the platform. A Ping (1.17+) or Confirm Transaction (1.7 to 1.16.5) with a random ID is interleaved
   and must be echoed. Tick End (1.21.2+) cadence is a signal only (Sonar had to disable its strict
   version for 1.21.7/1.21.8).
4. On a physics mismatch: one retry from a new random height, then the challenge if enabled, otherwise a
   failure without a blacklist strike.

Checks Sonar has and Warp does not take: the boat and minecart check, the arm swing, the held-slot
echo. Each produced false positives (S#414, S#430, S#395, the 26.3 swing change) and needs a large
per-version surface (entity spawn layouts, entity type IDs, vehicle packets) for little gain: a bot
that simulates gravity simulates a boat too.

#### 9.8.1 Protocol surface for 1.7.10 to 26.3

| Packet | Direction | In Warp today | Layout changes to cover |
|---|---|---|---|
| Login Success, Set Compression | S→C | yes | none new |
| Select Known Packs, Registry Data, Finish Configuration | S→C | yes (Registry Data decoded/forwarded, not built) | a builder for ID-only entries (1.20.5+) and for the single-NBT form (1.20.2 to 1.20.4) |
| Update Tags | S→C | no | 1.20.2+ (needed from 26.1, per Sonar) |
| Configuration Ping/Keep Alive, Cookie Request | both | Cookie yes (integration branch), Ping/Keep Alive no | 1.20.2+ |
| Join Game | S→C | full codec before 1.20.2, opaque after | an encoder for 1.20.2, 1.20.5, 1.21.2, 26.2, 26.3 layouts |
| Player Position (teleport) | S→C | no | 1.7 (absolute, stance), 1.8 (relative flags), 1.9 (teleport ID), 1.17 (dismount flag), 1.19.3 (flag removed), 1.21.2 (velocity, int flags) |
| Default Spawn Position, Game Event | S→C | no | 1.19.3+, 1.20.3+ (event 13) |
| Chunk Data (empty column) | S→C | no | about 12 layouts: 1.7, 1.8, 1.9, 1.9.4, 1.13, 1.14, 1.15, 1.16, 1.16.2, 1.17, 1.18, 1.20, 1.21.2, 1.21.5, 26.1 (Sonar's encoder is 150 lines) |
| Multi Block Change (platform) | S→C | no | 1.7, 1.8, 1.16.2 (section-based), 1.20 |
| Ping / Confirm Transaction, Keep Alive | both | Keep Alive yes | Transaction 1.7 to 1.16.5, Ping 1.17+ |
| Teleport Confirm, Player Position/Rotation/On Ground, Tick End | C→S | no | 1.7 stance field, 1.9 teleport confirm, 1.21.2 flag byte and Tick End |
| Client Information, brand, Disconnect, System Chat, Transfer, Store Cookie | both | yes | none new |
| Map Data, Set Container Slot, Set Held Item (challenge only) | S→C | no | item stack format in 1.13 and 1.20.5, map layout changes |

About 15 new packet codecs for the world check and three more for the challenge, all small, most of
them already written once in Sonar and LimboAPI (to be re-derived from Mojang's protocol, not copied:
Sonar is GPL-3.0, Warp AGPL-3.0, compatible but a different codebase).

#### 9.8.2 Registries

| Versions | What the client needs | How Warp gets it |
|---|---|---|
| 1.7 to 1.15 | nothing (numeric dimension) | |
| 1.16 to 1.20.1 | a dimension/registry codec in Join Game | generated once (these versions are frozen) |
| 1.20.2 to 1.20.4 | one Registry Data NBT | generated once |
| 1.20.5+ | one Registry Data per registry | **Known Packs**: with `minecraft:core` acknowledged, entries are sent as IDs without data and the client uses its built-in copy. One small ID table per release |
| 26.1+ | tags too (Sonar: required) | generated per release, sent pre-compressed |

Tables come from the vanilla server's data generator (`--reports`) per version, committed as compact
resources; a test fails when a supported version has no table. Learning registries from backends
(Infrarust's limbo captures Registry Data, Known Packs and tags from real backend configuration phases,
with an embedded fallback) was rejected as the primary source: an unverified player has not reached a
backend yet, a fresh proxy has captured nothing, and a backend running ViaVersion shows translated data.
The embedded tables are needed anyway, so they are the only source. LimboAPI's hand-written registries
are the counter-example: they caused its 82-day and 71-day lags and broke 1.21.11 clients (LA#226).

#### 9.8.3 Cost per verification

- Every static packet (Join Game, registries, tags, chunks, platform) is encoded **and compressed** once
  per protocol version at startup into a direct buffer and sent with `retainedDuplicate()`: no copy, no
  encoding, no compression per player. Per-session packets (teleport, ping, keep-alive) are about 20
  bytes each.
- Egress [estimate, to measure]: under 2 KiB before 1.16; 4 to 10 KiB for 1.16 to 1.20.4 (compressed
  codecs); under 4 KiB for 1.20.5 to 1.21.x (ID-only registries); 20 to 25 KiB for 26.1+ (tags). Sonar
  sends about 175 KB (1.21) to 280 KB (26.3) uncompressed per verification (§3.8).
- CPU [estimate, to measure with JMH]: decoding 30 to 60 small serverbound packets and a few
  multiplications, under 50 µs per verification, plus AES/CFB8 on the egress in online mode after
  authentication.

#### 9.8.4 Is the world worth its surface?

Yes, as phase 3, for three reasons:

1. In offline mode it is the only thing that tells a client from a protocol script; the probe alone
   falls to the first bot that echoes IDs.
2. The same `VirtualWorld` holds players when every backend is down or draining, instead of
   disconnecting them, and later carries a login queue. Its cost is shared.
3. Warp already maintains packet IDs for every version and an end-to-end matrix from 1.7 to 26.3; the
   world adds about 15 packets and one generated table per release.

### 9.9 S5: the challenge (escalation only)

- **Map code captcha**, the de facto standard, shown only: during an attack at the "bots are passing"
  level (§9.10) to unknown identities from low-share versions, then to all unknown identities if bots
  keep passing; and after a second physics mismatch. Never to verified players, never by default.
- It is visual only and falls to decoders and vision models (S#531, S#575): it raises the price of an
  attack, nothing more. That is why it is an escalation step and not a gate.
- **Accessibility:** the `Challenge` SPI lets an operator plug an out-of-band verification (a website,
  a Discord bot) that marks an identity verified through the API, and `/warp antibot verify <name>`
  lets staff do it by hand. A dialog-based text challenge (1.21.6+, narrator-friendly) was considered
  and rejected as a built-in: its content is machine-readable in the packet. It remains possible as an
  SPI implementation.

### 9.10 Attack detection and escalation

**Signals**, counted with `LongAdder`s into 120 one-second slots (a fixed few KiB, nothing allocated per
event, unlike Sonar's one Caffeine entry per connection): accepted connections, Login Starts from unknown
identities, verifications passed and failed by reason, authentication failures, refusals by reason,
connections not yet playing.

**Baseline**: an exponentially weighted mean of each signal over 30 minutes, updated only outside
attacks.

**Levels:**

| Level | Enters when (sustained 3 s) | Leaves when | Actions |
|---|---|---|---|
| NORMAL | | | per-address budgets, verification of unknown identities per §9.6 |
| ATTACK | unknown logins > max(10/s, 4 × baseline), or authentication failures > max(5/s, 4 × baseline), or failure ratio > 50% over 20+ verifications in 10 s, or connections not yet playing > 50% of their cap | every signal under half its threshold for 60 s | online mode: verification before authentication for unknown K2 (§9.6); budgets per IPv4 /24 and IPv6 /48 for unknown identities; priority lane; one summary line per interval |
| ATTACK, bots passing | in ATTACK, verifications passed by unknown identities > max(5/s, 3 × the baseline of new players) for 30 s | back to ATTACK when the pass rate returns under its threshold | challenge for unknown identities from versions with under 1% of verified joins, then for all unknown identities |
| LOCKDOWN | an operator command or an API call | the same | only verified identities may join; unknown ones are told so |

Counting only **unknown** identities is what keeps a proxy restart (every player reconnecting at once,
all of them verified) from looking like an attack, which Sonar's fixed threshold of 8 joins per second
cannot do. The floors and factors are defaults to tune with the soak tests of §12.

**Version-share gating.** Warp keeps the share of each protocol version among verified joins over 30
days. Attack traffic usually uses one version, often an old one with weaker checks (no teleport confirm
before 1.9). During an attack, unknown identities from a version with under 1% of verified joins get the
next stage up. This replaces Sonar's static `blacklisted-protocols` list with a measured one.

### 9.11 Bounds under attack

| Resource | Bound (default) | When exceeded |
|---|---|---|
| Connections not playing yet | 8,192, plus PR #123's per-address budgets | reset at accept |
| Bytes buffered per pre-login connection | the largest legal packet of the announced ID (about 0.8 KiB for a handshake) | closed, counted |
| Time in HANDSHAKE / STATUS / LOGIN | 5 s / 5 s / 30 s | closed, counted, never logged one by one |
| Authentications in flight | 256 (PR #123) | priority lane, then a FIFO of 1,024 for 10 s, then "busy, retry in N s" |
| Verification sessions | 2,048 concurrent | FIFO of 4,096, 20 s at most, then "busy, retry in N s"; verified identities never queue |
| One verification | 300 serverbound packets, 32 KiB per frame, a deadline per stage (§10) and 30 s in all | failed with a reason (a time-out is not a blacklist strike) |
| Verified store | 250,000 identities, about 10 MiB | sampled LRU eviction |
| Failure blacklist, per-prefix budgets | Caffeine, 100,000 keys each, W-TinyLFU (as PR #123) | eviction |
| Signal counters | 120 slots per signal | ring buffer |
| Logs | one summary line per interval during an attack, one start and one end line | |

With these bounds, an attack costs Warp at most about 8,192 sockets, 2,048 verification sessions of a
few hundred bytes of state each, and the pre-encoded world buffers shared by all of them [estimate:
under 50 MiB beyond Netty's own per-socket cost]. Sonar's equivalent bound is its 30 polls per second,
paid by real players (§3.3).

### 9.12 Nothing on the PLAY path

- The verification handlers exist only while a session is being verified. When it passes, the session
  gets exactly the pipeline it would have had without the anti-bot; a known identity never sees them.
- No per-connection bandwidth counter, no handler kept for bookkeeping (Sonar keeps two for the whole
  session, one of them writing shared static fields on every read and write, §3.7). Per-address session
  counts are already decremented by the limits on close.
- Packet floods from players who passed (verified bots, compromised accounts) are a separate concern:
  per-session packet and decompressed-byte budgets in the frame decoder, as Velocity's packet limiter
  does (5 MiB/s of decompressed bytes by default), amortised per read batch rather than per packet.
- **Chat spam stays out of the anti-bot.** Verification removes join-bot spam waves at the door; judging
  messages is a backend or plugin job (signed chat from 1.19 cannot be edited by a proxy anyway). The
  API gives chat plugins the facts they need: verified since when, by which method.

### 9.13 Metrics, logs and admin visibility

- **Metrics** (names in the scheme of `observability-architecture.md`):
  `warp_antibot_level`, `warp_antibot_verifications_total{stage, result, reason}` (bounded enums),
  `warp_antibot_verifying`, `warp_antibot_queue_length`, `warp_antibot_verified_identities`,
  `warp_antibot_signal_per_second{signal}`, `warp_auth_lane_wait_seconds`, and the existing
  `warp_connections_refused_total{reason}` from the limits.
- **Logs:** one WARN line when an attack starts (which signal, its rate, its baseline), one INFO summary
  per interval during it, one INFO line when it ends with its totals: peak rates, verified, failed by
  reason, refused by reason, top prefixes. Nothing per connection.
- **Commands:** `/warp antibot` (level, rates, queue, verifying, failures by reason),
  `/warp antibot verify|unverify|check <name> [address]`, `/warp antibot lockdown on|off`,
  `/warp antibot trace <address>` (the full packet sequence of the next verifications from that address,
  the tool Sonar users lacked in S#648), and a live action-bar view for staff who ask for it.
- **Management endpoint** (the existing management server): `/antibot` (JSON status) and
  `/antibot/blocklist`, the prefixes currently blacklisted, as plain CIDR lines. A ten-line script can
  feed it to an nftables set or an XDP filter, so the kernel drops what Warp has already judged.

### 9.14 Plugin API

Interfaces and records only, in `api/` (`dev.warp.api.antibot`):

| Type | Purpose |
|---|---|
| `AntiBot` | `level()`, `currentAttack()` (an `AttackReport` record), `verified()` (check, add, remove an identity), `lockdown(boolean)` |
| `Identity` (sealed: `Account(UUID)`, `NameAtAddress(String, InetAddress)`) | what is verified |
| `VerificationCheck` (SPI) | an extra check for unknown identities only (IP reputation, VPN, GeoIP), asynchronous with a deadline the proxy enforces; returns a sealed `Verdict`: `Pass`, `Fail(reason)`, `Escalate`, `Abstain` |
| `Challenge` (SPI) | a custom challenge (web or Discord verification, another captcha) |
| `VerifiedStore` (SPI) | a shared store for fleets |
| `AttackStartedEvent`, `AttackEndedEvent`, `IdentityVerifiedEvent`, `VerificationFailedEvent` | aggregated or post-verification events |

Rules: no plugin event fires for a connection before it has passed the stages that apply to it (an event
per connection at 100,000 connections per second is itself a denial of service, and plugins doing a
database lookup in `PreLoginEvent` would be hammered by bots). Concretely, `PreLoginEvent` fires after
S2, and during an attack after the before-authentication verification of an unknown identity;
`LoginEvent` and everything after it fire only for verified identities. SPIs run only for unknown
identities, with deadlines; a slow SPI counts as `Abstain`, never as a stall.

### 9.15 Configuration sketch

```hocon
anti-bot {
  enabled = true
  verification {
    offline-mode = always        # always | attack | never: when unknown identities are verified
    online-mode = always         # after authentication normally, before it during an attack
    world-check = true           # off for listeners whose backends are modded (§10)
    challenge = escalation       # escalation | never
    max-concurrent = 2048
    queue { size = 4096, max-wait = 20s }
  }
  verified {
    expire-after-last-join = 30d
    capacity = 250000
    prefix { ipv4 = 24, ipv6 = 48 }
  }
  attack {
    floors { unknown-logins = 10, authentication-failures = 5 }   # per second
    baseline-factor = 4
    calm-for = 60s
    prefix-budget { burst = 4, interval = 15s }                   # per /24 or /48, during attacks
  }
  exempt = []                    # CIDRs never verified: a staff VPN, a Geyser host
  token-secret = ""              # shared by every proxy of a fleet; generated when empty
}
```

---

## 10. False positives: the rules

Every rule below answers a false positive another project shipped.

1. **Hard verdicts only on protocol impossibilities**: a wrong echoed ID, a packet that is illegal in
   the state, a first position that is not the teleport target. Physics mismatches get a retry from a
   new height, then the challenge; they never add a blacklist strike on their own. Sonar's physics and
   ordering checks broke real clients on version edges again and again (S#51, S#380, PR S#496, 2.1.38).
2. **A disconnect during verification is not a failure** (S#271), and neither is a time-out: only a
   wrong answer counts towards the blacklist.
3. **Deadlines follow the measured round trip.** The probe's ping gives the RTT; each stage gets
   5 s + 4 × RTT, the whole verification 30 s. The physics checks are tick-based, so latency and jitter
   change nothing else (TCP keeps order).
4. **Unknown packets are ignored and counted, never a silent close** (S#648). Every failure has a reason
   code in the metrics and the summary line; `/warp antibot trace <address>` records the full sequence.
   The kick is written once and the channel closed on the write's completion, one close path only
   (PR S#616).
5. **Exemptions require proof**: configured `exempt` CIDRs, a Geyser host that sends the PROXY protocol
   from a trusted address, Floodgate's signed data once Warp supports Floodgate. Never a heuristic such
   as a source port of 0 (S#172). Every exemption has a test proving a Java client cannot use it (2.0.8,
   PR S#547).
6. **Bedrock players skip the world check**, not the limits: Geyser translates Bedrock movement, which is
   not Java physics (PR S#281). Their limits use the real Bedrock address carried by the PROXY protocol
   or Floodgate.
7. **Modded clients.** Forge and NeoForge clients refuse a vanilla-looking world (S#454). A listener or
   forced host whose backends are modded sets `world-check = false` and relies on the probe and the
   budgets. The FML marker in a handshake is self-declared, so it never exempts anything by itself.
8. **Brands are not allow-listed**, the brand check is printable UTF-8 up to 128 bytes (NoRisk, Lunar,
   Feather and Fabric brands all vary; Sonar widened its regex in 2.1.43). No locale check.
9. **Address churn never re-verifies a known player**: online identity is the account; offline identity
   uses a /24 or /48 prefix (S#555, S#611, S#513).
10. **A broken client address is reported, not punished.** When a large share of logins comes from one
    address (HAProxy or FRP not sending the PROXY protocol, S#499, S#622), Warp logs once that the
    address looks like a misconfigured front. It does not lift penalties automatically, which an attacker
    could exploit.
11. **Names: strict where they are free, permissive where Mojang issues them.** Warp's pattern today is
    `^[a-zA-Z0-9_]{3,16}$` for both modes. In offline mode it stays (plus a configurable prefix
    character for Floodgate's Bedrock names, which a strict pattern "rejects essentially every Bedrock
    player", V#1827). In online mode the name is checked only for length and control characters before
    authentication (it keeps junk off the session server, B#3266); the session server is the
    authority on what a valid account name is (BungeeCord's check for `.` and `-`, B PR#3256, existed
    for accounts Mojang later renamed itself, and was reverted, B PR#3622).
12. **The false-positive rate is measured.** The end-to-end harness gains a verification scenario:
    mineflayer with physics and raw `minecraft-protocol` clients as bot baselines (the first should pass
    the world check, which documents its limit; the second must fail), and real vanilla clients run
    headless (HeadlessMC is one option [unverified for CI]) on every version family, which must all pass.

---

## 11. Decisions taken

Each decision is final for the design; the rejected alternatives are listed with the reason.

**D1. Built into the core, not a plugin.**
Why: only the proxy owns the pipeline. It can verify without a reconnect, sees the real address from the
PROXY protocol, leaves nothing in the pipeline afterwards, and cannot conflict with another injector
(Sonar: S#152, S#163, S#171, S#391, S#454; "this can't be fixed in Sonar 2.0 simply because of the way
it injects"). Rejected: a plugin on an API (what `security-hardening-architecture.md` §4.3 proposed, "Warp
ne DOIT PAS intégrer de CAPTCHA dans le core"). That section is superseded: the checks and the map
captcha are core, custom challenges and IP intelligence are SPIs.

**D2. Only unknown identities are verified; verified identities never pay.**
Why: the attacker's cost is per identity, the legitimate cost is per first visit. Rejected: verifying
every join (latency for everyone, no gain), verifying nobody outside attacks in offline mode (the first
wave gets in before detection, and identities are free).

**D3. Identity: the account in online mode, (name, /24 or /48) in offline mode, as keyed HMACs.**
Why: the attacker must pay for the account; address churn must not re-verify; no clear-text personal
data at rest. Rejected: Sonar's exact name and IP (re-verification loops S#555, S#611, a reversible
32-bit fingerprint), the address alone (CGNAT and households share it), the name alone in offline mode
(free), a UUID in offline mode (derived from the name, so free too).

**D4. Placement: offline after Login Success; online after authentication in normal times and before
it during an attack.**
Why: no reconnect for anyone in offline mode or in normal online times, and full protection of RSA and
the Mojang session server when fake authentication is the attack. The before-auth path never admits an
unauthenticated player (it ends in a transfer or reconnect). Rejected: always before authentication
(Sonar: every new player reconnects, every time, and permission or premium logic is impossible, S#489,
S#537); always after (A7 unprotected); LimboFilter's version of the switch (a fixed counter, and an
unauthenticated admission window with its defaults, §5.1).

**D5. The backend is opened only after verification, and the hand-off is a server switch.**
Why: bots cost no backend login, no plugin join handler, no chunk; players see one continuous join.
LimboAPI already proves the same-session hand-off works on Velocity (LimboFilter uses it below its
`need-to-reconnect` threshold); Warp does it without reflection, through the switch code every server
change already uses. Rejected: disconnect and reconnect (Sonar always, LimboFilter above its
threshold), connecting the backend in parallel (wasted on every bot).

**D6. Verified store: embedded snapshot and journal, sliding 30-day expiry, bounded capacity, SPI for
shared stores, signed cookie tokens for transfer hops.**
Why: survives restarts with no dependency; bounded memory; fleets get a shared store when they want
one and stateless transfers always. Rejected: memory only (Sonar's default loses everyone on restart),
SQL or H2 with drivers fetched at runtime (S#374, S#428, S#446, S#513, S#331), Redis built in (a
dependency for every small server), expiry from verification time (a daily player re-verifies every 5
days).

**D7. Checks: the probe (configuration or pre-join), the world (teleport, gravity, collision,
ping/transaction), the map captcha on escalation. No vehicle, swing or held-slot checks.**
Why: each kept check is a protocol fact every vanilla client of every version satisfies; the dropped
ones produced false positives and need the most per-version surface (§9.8). Rejected: a captcha gate by
default (annoys every new player and falls to decoders anyway), physics checks without the probe (the
probe is free and removes most junk before a world is built).

**D8. Attack detection on unknown-identity signals against a learned baseline, with hysteresis, levels
and a manual lockdown.**
Why: a restart or an event must not look like an attack, a slow drip on a small server must. Rejected:
Sonar's fixed threshold of 8 joins per second, connection rate alone (connection floods are S0's
problem and do not say anything about bots that join).

**D9. A bounded FIFO queue sized by verification capacity; verified identities never queue.**
Why: fairness and a predictable wait, with real players returning untouched. Rejected: Sonar's
hash-ordered map polled 30 times per second with an 8 s timeout (§3.3), unbounded queues.

**D10. Every structure bounded; counters are `LongAdder` rings.**
Why: an attack must not be able to grow memory. Rejected: one cache entry per event (Sonar's
statistics), unbounded sets (Sonar's verified cache).

**D11. Per-state frame caps, state deadlines and aggregated logging come first, as phase 1.**
Why: they close Warp's current gaps (§1.2) and cost no protocol surface. Rejected: shipping an anti-bot
on top of a proxy that buffers 2 MiB per unauthenticated socket and logs every time-out.

**D12. Version-share gating during attacks instead of a static protocol blacklist.**
Why: attackers pick the version with the weakest checks; the legitimate share of each version is
measurable. Rejected: Sonar's `blacklisted-protocols` (static, set by hand, usually after the attack).

**D13. Bedrock skips the world check on proof; modded listeners disable it; brands are never
allow-listed.**
Why: §10 rules 5 to 8. Rejected: port heuristics (S#172), brand allow-lists (false positives with every
new client).

**D14. Layer 3 and 4 stay out of the proxy; Warp exports its blocklist.**
Why: a JVM process cannot filter packets before the kernel spends resources on them, and the uplink
saturates first anyway. The CIDR list on the management endpoint lets nftables or XDP drop what Warp has
judged. Rejected: embedding eBPF/XDP in Warp (root privileges, Linux only, a second product).

**D15. No per-connection plugin events before verification; SPIs with deadlines; aggregated events.**
Why: plugins must not become the amplifier. Rejected: firing login events for connections that have not
passed the stages in force (during an attack, every bot would reach every plugin), synchronous SPIs
without deadlines.

**D16. Chat spam is not an anti-bot concern.**
Why: verification stops join-bot waves; message filtering needs game context the proxy does not have and
cannot edit signed chat. Rejected: decoding chat in the core for spam scoring (breaks blind forwarding).

**D17. The world is pre-encoded and pre-compressed, registries go through Known Packs as IDs, and the
per-version data is generated.**
Why: zero per-player encoding, a few KiB per verification instead of hundreds, and one generator run per
Minecraft release. Rejected: Sonar's full NBT sent uncompressed, learning registries from backends.

**D18. Defaults: verification `always` in both modes, world check on, challenge on escalation only,
persistence on.**
Why: in offline mode bots are free; in online mode verification after authentication is seamless and
happens once per account per 30 days of absence. Rejected: Sonar-style `ALWAYS` with a reconnect (the
cost that makes operators turn it off), `attack` only (the first wave gets in).

**D19. No per-address cap on concurrent sessions by default.**
Why: carrier-grade NAT, schools and households share addresses, and a botnet has many addresses, so the
cap mostly hurts real players (S#530, S#622). During an attack the per-prefix budgets on unknown
identities do this job. Rejected: Sonar's `max-online-per-ip: 3` on by default.

**D20. The PROXY protocol is the only way to learn a client address from a front; no vendor formats.**
Why: every service in §7 supports PROXY protocol v2, PR #122 already restricts it to trusted peers, and
it needs no shared key. Rejected: TCPShield's signed-hostname format (vendor-specific, a 3 s window with
no nonce, and it rides in the handshake hostname, which the frame caps keep small). This supersedes
"Support natif TCPShield (PROXY protocol + validation signature)" in
`edge-cases-production-horror-stories.md` §4.2.

---

## 12. Phased plan

Ordered by protection per unit of protocol surface. Each phase ships on its own and is useful alone.

### Phase 1: hardening and the frame of the anti-bot (no new packet)

- Merge PR #122, #123 and #134 (PROXY protocol, limits, cached status and legacy ping).
- Per-state frame caps checked on the frame header; HANDSHAKE and STATUS deadlines; the global cap on
  connections not yet playing.
- Aggregate the log lines of §1.2 (time-outs before PLAY, unknown `nextState`, unsupported protocol,
  unexpected HANDSHAKE packet, failed authentication); the vanilla "Outdated" disconnect.
- The attack detector, its metrics, the summary lines, `/warp antibot`, the management JSON and the
  blocklist export.
- The verified store (keys, snapshot, journal, expiry, capacity), the identity model and the API types
  (`AntiBot`, `Identity`, the events), with lockdown working from day one (verified identities only).
- The authentication priority lane.

Stops: A2 to A6 completely, A7 partly (budget and lane), A12 already. Tests: unit tests on injectable
clocks like PR #123; an e2e flood scenario (raw sockets: null pings, slowloris, oversized frames, fake
authentications against the mock session server) asserting bounded memory, no per-connection log line,
and a real bot still joining.

### Phase 2: the probes (login and configuration packets only)

- Configuration probe for 1.20.2+ (brand, Client Information, Ping, Keep Alive) before the
  backend connection, and the token cookie; pre-join Keep Alive for 1.8 to 1.20.1; passive brand and settings
  deadline on the backend for older clients.
- Verification before authentication during attacks, with Transfer and the signed token (1.20.5+) or
  the reconnect message (older).
- The verification FIFO, version-share gating, per-prefix budgets during attacks, the blacklist of
  failures.

New codecs: configuration Ping and Keep Alive. Stops: A7 from every bot without a configuration state
machine during attacks, the bulk of cheap join bots (A8 without a protocol state machine). Tests: e2e with `minecraft-protocol` clients that skip the
echo (must fail) and every supported version of mineflayer (must pass).

**Delivered** (`feat(proxy): verify unknown players with login and configuration probes before
authentication`):

- The configuration probe (`Probe`, `ProbeSessionHandler`): Ping and Keep Alive echoed, Client
  Information required, the brand read in place from the client's plugin message (a `PacketWatch`, the
  frame still forwarded untouched), a random `warp:probe` cookie stored and asked back (1.20.5+). No
  Select Known Packs offer: the e2e matrix showed node-minecraft-protocol answers one offer per
  configuration phase, so the proxy's offer left the server's unanswered (see §10, item 2). The last answer closes the probe (clients answer in order), the deadline is
  `probe-timeout` + 4 RTT after the pong, 30 s at most; once the Keep Alive is echoed, the cookie (optional,
  flagged when missing) gets only max(1 s, 4 RTT) more, so a client without cookies (node-minecraft-protocol
  1.68 has none) is let in without paying the whole `probe-timeout` (the full e2e matrix hit its 30 min job
  budget when every bot login waited 5 s). Hard verdicts only on a wrong echo, a packet
  illegal in the state, a changed cookie, or no echo and no settings at the deadline; everything else
  is a flag (`Probe.Flag`), and a pass with two flags or more lets the player in without trusting it.
- The pre-join probe (`PreJoinProbeSessionHandler`): one Keep Alive before Join Game for 1.8 to 1.20.1;
  1.7 is not probed. The passive brand and settings check on the backend for these versions is left to
  phase 3's world, which makes it moot.
- Verification before authentication during attacks: Login Success under the offline profile, no
  player registered and no plugin event, the probe, then Store Cookie `warp:verified` and a Transfer
  back to the handshake's host and port (or `verification.transfer`), or the "verified, please
  reconnect" disconnect before 1.20.5. The token (`VerificationTokens`) is version, key id, issue
  time, K2, flags and a 16-byte HMAC-SHA256 tag; valid `token.lifetime` (10 min), bound to the name
  and network, two keys accepted across a rotation, the key generated into `anti-bot/token.key` and
  rotated every `rotate-after` or by `/warp antibot token rotate`, or shared by a fleet through
  `WARP_ANTIBOT_TOKEN_SECRET`. The handshake accepts a transfer intent while verified players are
  due back even with `accepts-transfers = false`; the login then has to present a valid token. A
  pass also trusts K2 on the proxy that ran it, so the same proxy needs no cookie.
- The queue (`VerificationQueue`): `max-concurrent` (2,048) verifications at once, the others in
  1,024 FIFO buckets keyed by a seeded hash of the network and served round-robin (stochastic fair
  queueing), `queue.size` (4,096) places with at most a sixteenth per network, `queue.max-wait` (20 s),
  then "busy, retry in N s". Every operation is O(1); known identities never reach it.
- Signals `VERIFICATIONS_PASSED` and `VERIFICATIONS_FAILED` in the detector, with the failure-ratio
  trigger of §9.10 (over half of 20+ verifications in 10 s). `VerificationFailedEvent`,
  `VerificationMethod.PROBE` and `TOKEN`, `VerificationFailure`. Metrics `warp_antibot_verifying`,
  `warp_antibot_queue_length`, `warp_antibot_queue_wait_seconds`, `warp_antibot_verifications_total`,
  `warp_antibot_tokens_total`; the JSON status and `/warp antibot` show the same.
- Per-prefix budgets during attacks were already phase 1's (`limits.logins.under-attack`). A failed
  probe strikes the blocklist like any protocol violation; a time-out never does.

**Left for later**: version-share gating (it escalates to a stage that exists from phase 3 on), the
e2e scenario with `minecraft-protocol` and mineflayer clients (the unit and pipeline tests cover every
packet of the probe; the headless harness run is the next step), `/warp antibot trace <address>`.

### Phase 3: the virtual world

- `VirtualWorld` for 1.7.10 to 26.3: the packets of §9.8.1, the generated registry and tag tables, the
  pre-encoded, pre-compressed buffers per version.
- The world check and its hand-off through `ReconfigurationSwitch` and `RespawnSwitch`.
- Reuse: hold players in the world when every backend is down or draining instead of disconnecting them.

Stops: headless bots without physics (A8, A9). Tests: raw clients and mineflayer without physics fail,
mineflayer with physics passes (documented limit), real vanilla clients pass on every version family;
JMH for the cost per verification; a soak test at the bounds of §9.11.

**Delivered** (`feat(proxy): virtual world with the anti-bot world check, the hold for players
without a server and a plugin API`):

- The world (`dev.warp.proxy.world`, `WorldLayouts`): a void with one 3x3 stone platform in the chunk
  at (0, 0), for every version from 1.7.2 to 26.3. Registries as §9.8.2 decided, with one change of
  source: no generated table per release. From 1.20.5 the proxy offers Select Known Packs with
  `minecraft:core` at every release of the client's protocol (1.21.7 and 1.21.8 share one; the
  client acknowledges the one that is its own) and sends three Registry Data packets whose entries
  are ids without data: `minecraft:overworld`, `minecraft:plains`, and the damage types the client
  resolves when it creates its level (the 23 of 1.19.4, `campfire` from 1.21), which must exist or
  the client crashes on join (the LimboAPI 1.19.4 lesson). The client's built-in pack supplies the
  data, so a release adds nothing until Mojang adds an eagerly resolved entry. 1.16 to 1.20.4 get
  a hand-written minimal codec (one dimension, one biome, chat types from 1.19, the eager damage
  types and empty trim registries from 1.19.4), inside Join Game until 1.20.1 and as one nameless
  Registry Data in 1.20.2 to 1.20.4. 1.7 to 1.15 need nothing. A client that acknowledges none of
  the offered packs (a version-translating mod) is let through without the world, flagged.
- Packets (`protocol`): the 1.20.2+ Join Game body is built verbatim per layout (1.20.2, 1.20.5,
  1.21.2 sea level, 26.2 online mode); encode-only clientbound `SyncPlayerPosition`,
  `SetDefaultSpawnPosition`, `GameEvent`, `EmptyChunk` (fourteen layouts, 1.7 to 1.21.5),
  `BlockUpdate`, `PlayPing`, `WindowConfirmation`, config `RegistryData` and `KnownPacks`; the
  serverbound `TeleportConfirm`, the four `ClientMovement` packets, `PlayPong`, `TickEnd` and the
  client's Known Packs live in a separate registry (`StateRegistry.world`) that a decoder reads only
  while its client stands in the world (`MinecraftDecoder.useRegistry`): nothing is registered for
  players on servers, so the PLAY path is untouched. Every id is checked against Mojang's reports
  (1.21 to 26.3) and minecraft-data (1.7.6 to 26.1) by `StateRegistryTest`.
- The check (`WorldCheck`, `WorldSessionHandler`, `WorldConfigSessionHandler`): teleport to a random
  height over a platform at a random height, Default Spawn Position (1.19.3+), Game Event 13
  (1.20.3+), one empty chunk (Sonar's nine were not needed: the client waits for the chunk under
  it only), nine Block Updates, a Ping (1.17+) or Confirm Transaction (1.7 to 1.16.5). Expected: the
  confirmation (1.9+), a first position exactly at the target and off the ground, `v' = (v - 0.08)
  * 0.98f` per position within 1e-6 (1.8 multiplied by 0.98 as a double), a landing exactly on the
  platform 8 to 12 ticks later, the echo. Only one block type: a slab, carpet or snow layer has a
  different block state id in every release since 1.13, so the platform is stone (state 1 in every
  version) and the random heights carry the entropy. Verdicts per §10: a wrong id, a packet illegal
  in the state or a first position elsewhere fail and strike; a wrong fall gets one retry from a new
  height and then fails under `Refusal.WORLD_CHECK_FAILED`, which never strikes; a time-out never
  strikes; a late echo or the retry are flags, two flags across probe and world let the player in
  without trusting it. Deadlines are `probe-timeout + 4 RTT` per stage, 30 s in all. Tick End is
  counted only. Modded and Bedrock platforms skip the world (`anti-bot.world.check` for listeners
  whose backends are modded). The identity is trusted only after the world, so a probe pass alone
  no longer stores it when the check is on.
- The hand-off: the player's first server is a switch from the world. A 1.20.2+ client goes through
  `ReconfigurationSwitch` (Start Configuration from the world, the acknowledgement read by the
  world's handler, then the server's configuration); an older client through `RespawnSwitch`, whose
  Join Game and Respawn replace the world. The backend is opened only after the pass.
- The hold (`anti-bot.world.hold`): a player with no server left (none at login, every fallback
  failed) is put in the world instead of being disconnected, from the configuration phase, from a
  game (Start Configuration, then the world's registries) or as a respawn before 1.20.2. It is told
  once in chat, sees the time left in its action bar every second (chat every 15 s on 1.7), keeps
  the proxy's commands, and the proxy tries its route's or the default server again every
  `retry-every` (5 s) while it is healthy, up to `max-wait` (5 min), then disconnects it with its
  own reason. `warp_antibot_holds_total` and `warp_antibot_world_players` count them.
- The API (`dev.warp.api.world.VirtualWorld`, `Warp.world()`): `supports`, `hold` (from anywhere,
  the proxy's own retries stop), `holds`, `players`, `release(player, server)` and `release(player)`
  (the server the player would have joined). A held player is a full `Player`: messages, titles,
  boss bars and sounds reach it.

**Left for later**: pre-compressing the static packets per version (they are pre-encoded and go
through the connection's compressor like any packet; the chunk is about 300 bytes before 1.18 and
under 1 KiB after); Update Tags for 26.1+ (Sonar sends them; the e2e matrix will say whether the
client needs any without a server); a Bedrock proof (`ClientPlatform.Bedrock` exists and skips the
world, nothing sets it yet); the e2e scenario with real clients on every version family, which is
the verification of the 26.x layouts and of the known-packs path against real clients; the JMH cost
measurement; events for the world (none in §9.14, none added).

### Phase 4: escalation and fleets

- Map captcha (Map Data, Set Container Slot, Set Held Item per version), the "bots passing" level, the
  `Challenge` and `VerificationCheck` SPIs.
- The `VerifiedStore` SPI and a reference Redis plugin outside the core; token secret rotation.
- A real-client false-positive matrix in CI and a nightly attack soak with SoulFire, mineflayer and raw
  clients, next to the existing nightly soak.

Stops: part of A10 (raises its price), fleets share verifications.

What Sonar 2 offers today maps to phases 1 to 4 together. Phases 1 and 2 already beat it on floods
(which Sonar leaves to Velocity), on restarts, on IPv6, on fairness under attack, on several proxies and
on the cost to verified players, and match its before-authentication protection against protocol
scripts; phase 3 matches its physics with a fraction of its bandwidth and without its reconnect; phase 4
adds what Sonar sells in 3.0.

---

## 13. Known issues of other projects that this design avoids

| Issue | Problem | This design |
|---|---|---|
| V#568, W#528 | fake authentication drives RSA and `hasJoined`; Mojang blocks the proxy; Velocity: "won't fix" | authentication budget with a priority lane for known identities; verification before authentication during attacks (§9.4, §9.6) |
| S#262, S#489, S#537, S#433 | every new player is kicked or transferred after verification; no premium or permission bypass | hand-off through the switch machinery in the same session; verification after authentication in online mode outside attacks (§9.6) |
| S#555, S#611, S#513 | re-verification on every address change, loops behind TCPShield | account identity in online mode, /24 or /48 prefix in offline mode (§9.5) |
| S#331, S#374, S#428, S#446, default `NONE` | verified players lost on restart, database corruption and drops | embedded snapshot and journal, on by default, no database (§9.5) |
| S#366 | no verification shared between proxies | `VerifiedStore` SPI and stateless signed cookie tokens (§9.5) |
| §3.3 (Sonar source) | the "queue" is a hash map polled 30 per second with an 8 s timeout | bounded FIFO sized by capacity; verified identities never queue (§9.11) |
| §3.6, S#175 | fixed attack threshold of 8 joins per second; tracker that never ends | learned baseline on unknown identities only, hysteresis (§9.10) |
| §3.6 | one Caffeine entry per connection to count rates | `LongAdder` rings (§9.10) |
| §3.7 | two handlers kept for the whole session, one writing shared statics on every buffer | nothing left in the pipeline after verification (§9.12) |
| §3.8 | 175 to 280 KB of uncompressed registries per verification; IDs random per process | Known Packs ID-only registries, pre-compressed buffers, per-session randomness (§9.8) |
| §3.5 | reversible 32-bit fingerprint, docs claiming plain-text IPs | keyed HMAC-SHA256 keys, nothing in clear (§9.5) |
| IPv6 /128 keys in Sonar, Velocity (V PR#1897), Gate /120 (G PR#938) | one subscriber dodges every per-address limit | /64 per address (PR #123), /48 per prefix during attacks (§9.10) |
| S#648, S#352, S#476, PR S#616 | silent closes, kick messages lost to a double close | unknown packets ignored and counted, reason codes, one close path, `trace` (§10) |
| S#271, PR S#496, S#51, S#380 | lag, version edges and Bedrock fail physics or ordering checks | hard verdicts only on protocol impossibilities, RTT-sized deadlines, retry then challenge, Bedrock exempt on proof (§10) |
| S#172, 2.0.8, PR S#547 | exemptions by heuristic became bypasses | exemptions on proof only, with negative tests (§10) |
| S#414, S#430, S#395, PR S#635 | vehicle, held-slot and swing checks fail real clients | not taken (§9.8) |
| S#454 | modded clients cannot enter a vanilla limbo | `world-check = false` per listener or forced host (§10) |
| S#194, B#2743, V#1597, B#401 | logging is the CPU cost of an attack | aggregated summary lines, nothing per connection (§9.3, §9.13) |
| S#499, S#622, B#2590, B#2599 | wrong client address turns limits against real players; BungeeCord disables its throttle behind PROXY | limits on the PROXY address, trusted peers only (PR #122/#123); misconfigured fronts reported (§10) |
| W#607, W PR#609, B PR#3066, Warp §1.2 | slow-drip large first frames, idle timers reset by any byte | per-state frame caps on the header, absolute state deadlines, idle timer after the frame decoder (§9.3) |
| B PR#2908, N#10417 | decoding continues on closed channels | Warp's dead-channel guard (exists) plus pre-login caps |
| Gate G PR#936, G PR#1342 | no header timeout, timeouts scaled to 347 days | absolute deadlines tested on an injectable clock, as PR #123's limiters are |
| V#1742, V#1792, V PR#1800 | decompression floods, ratio limits that kick vanilla players, unbounded pre-join queues | per-session decompressed-byte budgets (no ratio), bounded queues (§9.11, §9.12) |
| B PR#3742 | a client that ignores a cookie request re-fires login events | strict login state machine; no event before the stages that apply (§9.14) |
| G#96, B#44, B PR#3257 | built-in protection refused, "use a plugin" | built in, with SPIs for what must stay pluggable (D1, §9.14) |
| S#416, S#589 | lockdown, lag compensation, multi-server sold in Sonar 3.0 | lockdown, RTT-sized deadlines and fleet tokens in the open core (§9.10, §10, §9.5) |

---

## Sources

**Source code** (GitHub tarballs downloaded on 2026-11-02; line references in the text are from them):

- Sonar 2.1.53: <https://github.com/jonesdevelopment/sonar> (`common/verification`, `common/protocol`,
  `api/database`, `api/tracker`, `velocity/antibot`, `assets/config/en.yml`)
- Velocity: <https://github.com/PaperMC/Velocity> (`MinecraftVarintFrameDecoder`, commits `374acad83`
  "Validate pre-login frame sizes in status and login states" and `0219993c8` "Add basic packet
  limiter", `default-velocity.toml`, `LoginPluginResponsePacket`, `HandshakePacket`)
- LimboFilter `7d96b5b001`: <https://github.com/Elytrium/LimboFilter>; LimboAPI `cd34c9c3b2`:
  <https://github.com/Elytrium/LimboAPI>; FastPrepareAPI: <https://github.com/Elytrium/FastPrepareAPI>
- EpicGuard `d890c92876`: <https://github.com/4drian3d/EpicGuard> (archived)
- BungeeCord `55e1b0bbe7`: <https://github.com/SpigotMC/BungeeCord>
- Gate `72adf40256`: <https://github.com/minekube/gate> (`pkg/internal/addrquota`, `config.yml`, Lite)
- Infrarust `64f7da31bf`: <https://github.com/Shadowner/Infrarust>
- TCPShield RealIP `e20f2df589`: <https://github.com/TCPShield/RealIP>
- Warp: `main` (`FrameDecoder`, `ServerChannelInitializer`, `HandshakeSessionHandler`,
  `MinecraftConnection`, `LoginSessionHandler`, `JoinGame`, `LoginStart`, `KnownPacks`), PR #122,
  PR #123, PR #134, `integration/primemc`

**Issue trackers** (every number cited was read, body and comments):
<https://github.com/jonesdevelopment/sonar/issues>, <https://github.com/PaperMC/Velocity/issues>,
<https://github.com/SpigotMC/BungeeCord/issues>, <https://github.com/PaperMC/Waterfall/issues>,
<https://github.com/minekube/gate/issues>, <https://github.com/Elytrium/LimboFilter/issues>,
<https://github.com/Elytrium/LimboAPI/issues>, <https://github.com/4drian3d/EpicGuard/issues>,
<https://github.com/awumii/EpicGuard/issues>, <https://github.com/netty/netty/issues> (N#10165,
N#10417, N#3410)

**Documentation and vendor pages**:

- Sonar: <https://docs.jonesdev.xyz/administration/configuring-sonar>, <https://sonar.top>, release notes
  <https://github.com/jonesdevelopment/sonar/releases>
- BotSentry: <https://docs.cyberdevelopment.es>; nAntiBot: <https://docs.nickuc.com/nantibot>
- TCPShield: <https://docs.tcpshield.com>, <https://tcpshield.com/plans>; NeoProtect:
  <https://docs.neoprotect.net/gameshield/features>; Infinity-Filter:
  <https://docs.infinity-filter.com/features/anti-bot>; OVHcloud game firewall:
  <https://docs.ovhcloud.com> (firewall-game-ddos)
- Attack tooling: <https://github.com/KucingSMPNetwork/mc-stresser> (method list),
  <https://github.com/soulfiremc-com/SoulFire>, <https://github.com/PrismarineJS/mineflayer>
- Aisuru: Cloudflare figures reported by BleepingComputer,
  <https://www.bleepingcomputer.com/news/security/aisuru-botnet-behind-new-record-breaking-297-tbps-ddos-attack/>
  and <https://bleepingcomputer.com/news/security/aisuru-botnet-sets-new-record-with-314-tbps-ddos-attack>
- Mojang release dates: <https://piston-meta.mojang.com/mc/game/version_manifest_v2.json>

**Related Warp documents**: `security-hardening-architecture.md` (§2, §3.5, §3.6, §4, §8),
`edge-cases-production-horror-stories.md` (§3.2, §4.2, §4.3, §6.2), `community-proxy-pain-points.md`
(§6.3), `observability-architecture.md` (metric naming), `multi-version-protocol-strategy.md`.
