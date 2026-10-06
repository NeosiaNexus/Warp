# End-to-end tests

Real-protocol bots connect through Warp to real Minecraft servers, for every protocol version Warp
supports. Unit tests check that each piece does what its author meant; these check that a player
can actually join, play, switch servers and survive a fallback, on every version.

```bash
e2e/run.sh --mc 1.21.4                                   # one version, default variant (online)
e2e/run.sh --mc 1.8.8,1.20.2 --variants online,offline   # several versions and variants
e2e/run.sh --mc 1.21.4 --passthrough off --scenarios login,switching
e2e/run.sh --mc 26.3 --strict                            # a known-broken version, failing for real
e2e/run.sh --mc 1.21.1 --direct                          # control run without Warp
e2e/run.sh --list                                        # the whole matrix
e2e/run.sh --help
```

Requirements: Node.js 22+ and a JDK that runs Gradle. Everything else (Paper or vanilla servers,
the JDK each server needs, ViaProxy) is downloaded on first use, checked against a pinned checksum
and cached in `~/.cache/warp-e2e` (override with `WARP_E2E_CACHE`). Without `--jar`, Warp's shadow
jar is built with Gradle. The harness uses 8 local ports from 26100 (`--port-base` to move them).

The npm dependencies are pinned in `package-lock.json`. `package.json` overrides `uuid` to 11.1.1+
(GHSA-w5hq-g745-h8pq): the bots' Mojang and Microsoft login libraries still ask for older releases,
which the harness never uses (bots log in offline) but the dependency review rejects.

## What a run does

For each version: boot two backends (`lobby` in creative mode, `survival` in adventure mode, so
bots can tell them apart on any version), then for each variant boot Warp twice (a normal instance,
and one whose default server is a closed port) and run the scenarios:

| Scenario | Checks |
|---|---|
| `status` | Server list ping through Warp |
| `login` | Join, receive chunks, land on the lobby; `/server` answers (1.19.3+) |
| `keepalive` | One bot stays connected through the whole run (at least 65 s, past Warp's first keep-alive time-out check) |
| `switching` | Six `/server` switches back and forth (configuration phase from 1.20.2, Join Game and Respawn before) |
| `crowd` | Ten bots at once, then half of them switch server at the same moment |
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
alone triggers them, never skipped: today, the recipe serializer ids of 1.20.5 to 1.21.1.

Logs and `result.json` go to `e2e/build/<version>/`.

## Variants

Defined in `versions.json`: `online` (mock Mojang session server, encryption on), `offline`,
`transcode` (compression passthrough off), and backends compressing from a lower (`backend-lower`),
higher (`backend-higher`) or no (`backend-uncompressed`) threshold than Warp. Variants that share a
backend threshold share the backends; only Warp restarts between them. Ad-hoc flags (`--online`,
`--passthrough`, `--threshold`, `--backend-threshold`) build a one-off variant.

## The matrix (`versions.json`)

One entry per protocol number:

```json
{
  "version": "1.21.10",
  "protocol": 773,
  "java": 21,
  "client": "1.21.9",
  "server": { "type": "paper", "version": "1.21.10", "build": 130, "url": "…", "sha256": "…" },
  "knownBroken": "packet ids for 1.21.5+ not audited yet (#48)"
}
```

- `client`: the version string the bot announces, when it differs from `version`. The protocol
  library silently maps version strings it has no data for to a neighbouring protocol, so the
  harness refuses any entry whose bot would announce a protocol other than `protocol`.
- `via`: for protocols the bots cannot speak (1.9.1, 1.14.2, versions newer than the library),
  the version they speak to [ViaProxy](https://github.com/ViaVersion/ViaProxy), which translates
  to `version`. ViaProxy cannot authenticate against an online-mode proxy, so these entries run
  their online variants offline.
- `server.type` is `paper`, or `vanilla` for the few versions Paper never released (1.9 to 1.9.2,
  1.11, 1.16). `server.preseed` lists files an old build expects before its first boot.
- `knownBroken`: why the version fails, with the issue. It still runs and is reported, but does
  not fail CI (it shows as ⚠️ known broken). When it starts passing, CI says so: remove the field.
- `knownBrokenScenarios`: the same for single scenarios, when the rest of the version works
  (`{"fallback-rejected": "no fallback before 1.20.2 (#44)"}`). The other scenarios must pass,
  and what Warp or a backend logs while a known-broken scenario runs is reported with it instead
  of failing the version.

`--strict` ignores both. `npm test` checks that each reason links an issue.

Tiers pick what runs where:

- `pr`, on every pull request (required): one version per protocol era among those that pass,
  and the offline, transcode and backend-lower variants on the newest. A known-broken version never goes there, a version with
  known-broken scenarios can. Versions before 1.18 join it once they pass (#41).
- `full`, on every push to `main`, nightly, on demand, and on pull requests labelled
  `e2e: full`: every version, and more variants at era boundaries.

## Adding a Minecraft version

1. Find the protocol number (it is in the client jar's `version.json`) and add it to
   `ProtocolVersion` in Warp.
2. Add the entry to `versions.json`, in protocol order. Take the latest **STABLE** Paper build from
   `https://fill.papermc.io/v3/projects/paper/versions/<version>/builds?channel=STABLE` (URL and
   sha256 are in `downloads["server:default"]`), and the server's Java from the version's
   `java.version.minimum`.
3. If the bot library has no data for it yet, add `"via": "<newest version it speaks>"`.
4. Run it: `e2e/run.sh --mc <version>`, and `npm test` in `e2e/` (checks the matrix).
5. Until Warp supports it fully, add `"knownBroken"` (or `"knownBrokenScenarios"`) with the
   issue; CI then reports it without failing.

The harness itself is tested with `npm test` (matrix consistency, failure patterns, downloads, the
report, protocol data corrections), which CI runs before every end-to-end matrix.
