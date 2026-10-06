# End-to-end tests

Real-protocol bots connect through Warp to real Minecraft servers, for every protocol version Warp
supports. Unit tests check that each piece does what its author meant; these check that a player
can actually join, play, switch servers and survive a fallback, on every version.

```bash
e2e/run.sh --mc 1.21.4                                   # one version, default variant (online)
e2e/run.sh --mc 1.8.8,1.20.2 --variants online,offline   # several versions and variants
e2e/run.sh --mc 1.21.4 --passthrough off --scenarios login,switching
e2e/run.sh --mc 26.3 --strict                            # known-broken flags ignored: fails for real
e2e/run.sh --mc 1.21.1 --direct                          # control run without Warp
e2e/run.sh --list                                        # the whole matrix
e2e/run.sh --help
```

Requirements: Node.js 22+ and a JDK that runs Gradle. Everything else (Paper or vanilla servers,
the JDK each server needs, ViaProxy) is downloaded on first use, checked against a pinned checksum
and cached in `~/.cache/warp-e2e` (override with `WARP_E2E_CACHE`). Without `--jar`, Warp's shadow
jar is built with Gradle. The harness uses 8 local ports from 26100 (`--port-base` to move them).

The bots are [mineflayer](https://github.com/PrismarineJS/mineflayer) bots from 1.8.8, the oldest
version mineflayer loads. For 1.7 the harness drives
[minecraft-protocol](https://github.com/PrismarineJS/node-minecraft-protocol) alone
(`src/clients/legacy.js`), behind the same interface: it logs in, answers keep-alives and
teleports, sends a movement packet every tick and reads the chat, as the vanilla 1.7 client does.

The npm dependencies are pinned in `package-lock.json`. `package.json` overrides `uuid` to 11.1.1+
(GHSA-w5hq-g745-h8pq): the bots' Mojang and Microsoft login libraries still ask for older releases,
which the harness never uses (bots log in offline) but the dependency review rejects.

## What a run does

For each version: boot two backends (`lobby` in creative mode, `survival` in adventure mode, so
bots can tell them apart on any version), then for each variant boot Warp twice (a normal instance,
and one whose default server is a closed port) and run the scenarios:

| Scenario | Checks |
|---|---|
| `status` | Server list ping through Warp, advertising the protocol the bot speaks (else a client lists Warp as incompatible) |
| `login` | Join, receive chunks, land on the lobby; `/server` answers |
| `keepalive` | One bot stays connected through the whole run (at least 65 s, past Warp's first keep-alive time-out check) |
| `chat` | Chat lines before and after each `/server` that Warp answers itself (list, unknown server, current server): the bot must not be kicked. From 1.20, online, the bot signs its chat, so the lobby checks every acknowledgement (see below) |
| `switching` | Six `/server` switches back and forth (configuration phase from 1.20.2, Join Game and Respawn before) |
| `tab-list` | After a switch, the tab list lists none of the previous server's players: a witness stays on the lobby while another bot moves to survival. Each bot keeps the tab list as the vanilla client does (by name on 1.7, by UUID after, across a Join Game before 1.20.2), not as mineflayer does |
| `profile-key` | 1.19 to 1.19.2 only: a bot with a chat signing key, as every client of a Microsoft account, joins (online, it signs the verify token instead of encrypting it), chats and switches. Online, Warp refuses a key Mojang did not sign, an expired one and, from 1.19.1, a key issued to another player; offline, it ignores the key and lets the first two in |
| `crowd` | Ten bots at once, then half of them switch server at the same moment while the others stay on the lobby; those that switched must list none of those left behind |
| `fallback-unreachable` | Default server down: the player lands on the next one |
| `fallback-rejected` | Lobby refuses the login (whitelist): the player lands on survival |

A run fails if a scenario fails, and also if:

- Warp logs anything at ERROR or FATAL, an uncaught exception, or a JVM crash;
- Warp gets a wrong keep-alive answer or times a player out (bots always answer at once);
- Netty reports a leaked buffer (Warp runs with `-Dio.netty.leakDetection.level=paranoid` and a
  forced GC at the end);
- Warp does not shut down within 20 s (its thread dump is saved next to the logs);
- a backend drops a connection with a protocol error (`DecoderException`, bad compression);
- a bot cannot parse a packet. The protocol library is made strict: a packet with unread trailing
  bytes, or one it cannot read completely, is an error rather than a log line.

Strict parsing also catches errors in the bots' protocol data. Those are corrected in memory
(`correctProtocolData` in `src/clients/mineflayer.js`) once a `--direct` run proves the server
alone triggers them, never skipped: today, the recipe serializer ids of 1.20.5 to 1.21.1. Bots also
end their ticks as the game does from 1.21.2 (`endTicks`): mineflayer never sends a Client Tick End,
and a 26.3 server kicks a player that sends two positions in the same tick.

Logs and `result.json` go to `e2e/build/<version>/`.

## Signed chat

From 1.19.3 a server checks, with every chat message and command, which of the signed messages it
sent the player the client acknowledges; a proxy that keeps a command from the backend must pass
those acknowledgements on (#81). Servers only track signed messages, so the harness lets bots sign:
`src/mojang.js` is a mock Mojang, up for the whole run, that

- vouches for each player in online mode (`-Dmojang.sessionserver` on Warp), with the UUID an
  offline-mode backend gives it, so that the bot and the backend agree on the signer;
- signs the bots' profile keys with its own key pair, which Warp trusts through
  `-Dwarp.profilekeys.signer` in every variant (the 1.19 to 1.19.2 logins of `profile-key`);
- publishes that key as the services key set (`/publickeys`).

Backends from 1.20 (authlib 4) are started with the authlib host properties
(`-Dminecraft.api.<service>.host`, `AUTHLIB_HOSTS` in `src/backend.js`) pointing at it, so they
accept the chat session of a bot whose key it signed. A bot gets such a key in the `chat` scenario
of online variants (minecraft-protocol opens a chat session over an encrypted connection only).
Before 1.20, authlib bundles Mojang's key and no bot can sign: there, `chat` only checks that chat
still flows.

## Variants

Defined in `versions.json`: `online` (mock Mojang session server, encryption on), `offline`,
`transcode` (compression passthrough off), and backends compressing from a lower (`backend-lower`),
higher (`backend-higher`) or no (`backend-uncompressed`) threshold than Warp. Variants that share a
backend threshold share the backends; only Warp restarts between them. Ad-hoc flags (`--online`,
`--passthrough`, `--threshold`, `--backend-threshold`) build a one-off variant. 1.7 predates
compression: on 1.7.x the compression variants run uncompressed, like any 1.7 connection.

## The matrix (`versions.json`)

One entry per protocol number:

```json
{
  "version": "1.21.10",
  "protocol": 773,
  "java": 21,
  "client": "1.21.9",
  "server": { "type": "paper", "version": "1.21.10", "build": 130, "url": "…", "sha256": "…" }
}
```

- `client`: the version string the bot announces, when it differs from `version`. The protocol
  library silently maps version strings it has no data for to a neighbouring protocol, so the
  harness refuses any entry whose bot would announce a protocol other than `protocol`.
- `via`: for protocols the bots cannot speak (1.7.2 to 1.7.5, 1.9.1, 1.14.2, versions newer than
  the library), the version they speak to [ViaProxy](https://github.com/ViaVersion/ViaProxy), which
  translates to `version`. ViaProxy cannot authenticate against an online-mode proxy, so these
  entries run their online variants offline.
- `server.type` is `paper`, or `vanilla` for the few versions Paper never released (1.7.2, 1.9 to
  1.9.2, 1.11, 1.16). `server.preseed` lists files an old build expects before its first boot.
- `knownBroken`: why the version fails, with the issue. It still runs and is reported, but does
  not fail CI (it shows as ⚠️ known broken). When it starts passing, CI says so: remove the field.
- `knownBrokenScenarios`: the same for single scenarios, when the rest of the version works
  (`{"fallback-rejected": "no fallback before 1.20.2 (#44)"}`). The other scenarios must pass,
  and what Warp or a backend logs while a known-broken scenario runs is reported with it instead
  of failing the version.

`--strict` ignores both. `npm test` checks that each reason links an issue.

Tiers pick what runs where:

- `pr`, on every pull request (required): one version per protocol era among those that pass,
  every variant on the newest version the bots speak natively, and the newest version, through
  ViaProxy if need be (so offline, with the transcode and backend-lower variants). A known-broken
  version never goes there, a version with known-broken scenarios can. `npm test` fails when the
  newest version that passes, or a variant on the newest the bots speak, is missing from it.
- `full`, on every push to `main`, nightly, on demand, and on pull requests labelled
  `e2e: full`: every version, more variants at era boundaries, and everything `pr` runs.

## Adding a Minecraft version

1. Find the protocol number (it is in the client jar's `version.json`) and add it to
   `ProtocolVersion` in Warp, then run `npm run packet-reports -- <version>` in `e2e/` (see
   below) and give each packet Warp registers its id in that protocol. Then compare the format of
   every packet Warp decodes or encodes with the previous release: their codecs in the server jar
   (unobfuscated from 26.1, so `javap -c` reads them) and Velocity's packet classes. 26.2, for
   instance, added a session ID to Login Success and an online mode flag to Join Game.
2. Add the entry to `versions.json`, in protocol order. Take the latest **STABLE** Paper build from
   `https://fill.papermc.io/v3/projects/paper/versions/<version>/builds?channel=STABLE` (URL and
   sha256 are in `downloads["server:default"]`), and the server's Java from the version's
   `java.version.minimum`.
3. If the bot library has no data for it yet, add `"via": "<newest version it speaks>"`.
4. Run it: `e2e/run.sh --mc <version>`, and `npm test` in `e2e/` (checks the matrix).
5. Until Warp supports it fully, add `"knownBroken"` (or `"knownBrokenScenarios"`) with the
   issue; CI then reports it without failing. Once it passes, give the top-level `README.md` its
   new newest version (the supported range, and the versions the suite plays), and move the `pr`
   tier's newest version to it (`npm test` says what is missing).

The harness itself is tested with `npm test` (matrix consistency, failure patterns, downloads, the
report, protocol data corrections), which CI runs before every end-to-end matrix.

## Packet id references

Warp's `StateRegistryTest` checks every packet id Warp registers, in every state and direction,
against a checked-in reference, so a wrong id fails the unit tests rather than a player's session.

From 1.21 on, the reference is Mojang's own: the packets report of the vanilla server's data
generator. `npm run packet-reports -- <version>` downloads the release's server jar from Mojang's
version manifest (checked against its sha1), runs its data generator on the Java version Mojang
lists for it (from the cache, else downloaded like the servers' own), reads its protocol with
`unzip`, and writes `protocol/src/test/resources/dev/warp/protocol/packet/reports/<version>.json`:
the id of every packet under its Mojang name (`minecraft:keep_alive`). Game versions sharing a
protocol share every id, so there is one fixture per protocol, and the test fails when a protocol
from 1.21 on has none. Without a version it regenerates every fixture; `--check` writes nothing and
fails if a release's ids differ from the fixture of its protocol, or if no fixture has it. The
`Packet reports` workflow runs `--check` on every fixture when a pull request changes them (so none
can be edited by hand), and on the latest release every week, which flags a new Minecraft version.

Before 1.21, `npm run packet-ids` writes `protocol/src/test/resources/dev/warp/protocol/packet/packet-ids.txt`
from the pinned minecraft-data: the id of every packet at each protocol `ProtocolVersion` registers.
Run it again after bumping minecraft-data; the test reports a table that misses a protocol.
