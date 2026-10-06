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
e2e/run.sh --mc 1.21.4 --soak 30 --bots 20               # half an hour under load (see Soak)
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
| `status` | Server list ping through Warp, advertising the protocol the bot speaks (else a client lists Warp as incompatible) |
| `login` | Join, receive chunks, land on the lobby; `/server` answers |
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

## Soak

```bash
e2e/run.sh --mc 1.21.4 --soak 30 --bots 20
e2e/run.sh --mc 1.21.4 --soak 30 --bots 20 --netem "delay 50ms 20ms distribution normal loss 1%"
```

`--soak MINUTES` replaces the scenarios with a long run that looks for leaks. A crowd of `--bots`
bots joins Warp and stays for MINUTES; every 10 s a quarter of them switch server (and must receive
the new world), a fifth reconnect, and a server list ping goes through. A bot that is kicked, sees
a protocol error or receives nothing for 30 s is a failure; it reconnects at the next cycle.

Every 5 s the harness samples Warp's process: resident memory, file descriptors, connections to
clients and to backends, threads and CPU from `/proc`; heap and native memory through `jcmd`. Every
30 s the sample forces a full GC first: the heap left is the live heap, and Netty reports the
buffers leaked so far rather than only at the end. This Warp runs with a fixed, pre-touched heap
(`-Xms512m -Xmx512m -XX:+AlwaysPreTouch`), so that resident memory only moves with native memory,
which native memory tracking breaks down; direct memory is its "Other" category, Netty's buffers.
It also keeps a flight recording, `logs/warp-<variant>.jfr`.

The soak runs in four steps: the warm-up (a fifth of it, 1 to 5 minutes, while the JIT, pools and
caches fill up); an intermission, where every bot leaves and then comes back; the steady phase; and
the drain, where every bot leaves for good. Besides what fails any run (an ERROR, a buffer leak, a
hung shutdown), a soak fails when:

| Check | Fails when |
|---|---|
| Resident memory | it grows over the steady phase by more than 32 MiB or 5 %, whichever is larger |
| Live heap | it grows over the steady phase by more than 8 MiB or 25 % |
| Direct memory | it grows over the steady phase by more than 16 MiB or 25 % |
| File descriptors | they grow over the steady phase by more than 8 or 10 % |
| Threads | they grow over the steady phase by more than 8 or 10 % |
| Connections | Warp still holds a client or backend connection 30 s after every bot left, at the intermission or at the end |
| Objects | a class of Warp's own, or a Netty socket channel, has more instances at the end than at the intermission, by more than the number of bots |
| Bots | any bot fails, in any phase |

Growth is measured between the medians of the first and last thirds of the steady phase, and is
only sustained if the resource was still growing in the last third: a pool that fills up early and
stays full is not a leak. A soak shorter than about 5 minutes is too short for the live heap check,
which it then skips. The limits come from the nightly soak of a clean Warp on a GitHub runner: over
its 25 minutes of steady state, resident memory grows by about 7 MiB (the JIT at work) and nothing
else grows at all, varying by about 1 MiB of live heap, 2 file descriptors and 3 threads (those of
the HTTP client of online mode). The limits leave several times that.

A trend only shows a leak big enough to stand out of the noise. The objects check does not depend
on one: a class histogram of Warp (`jcmd GC.class_histogram`, after a full GC) is taken at the
intermission and at the end, both times with every bot gone. A cache with an entry per player may
keep one object per bot; an object kept per connection or per switch outnumbers them within
minutes. Both histograms come after the same code paths: Warp builds its protocol tables and some
per-thread state on its first players (about 1,700 objects of its own), which a histogram taken
before the first player would mistake for a leak. A Warp patched to keep a reference to every
player that ever joined passes every trend check (the 65 players of a 5-minute soak add 0.3 MiB of
live heap), and fails this one.

Results go to `e2e/build/<version>/`: `soak-<variant>.csv` and `soak-<variant>.json` (the time
series, a row every 5 s), and the summary in `summary.md` and the GitHub run summary: each check,
the classes that gained objects, then each phase (joins and switches with their latency, failures,
memory, descriptors, threads, CPU, and the TCP queues: bytes on their way to the bots, bytes Warp
has not read yet).

### Degraded network

`--netem SPEC` puts [netem](https://man7.org/linux/man-pages/man8/tc-netem.8.html) between the bots
and Warp, with any run: latency, jitter, loss, duplication, reordering, rate (not corruption:
loopback does not check TCP checksums). Only TCP traffic to and from Warp's ports goes through it,
both ways (a `prio` qdisc on `lo` whose fourth band, reached by `u32` port filters only, holds
netem); Warp's connections to its backends and the rest of the machine's loopback traffic are
untouched. The run removes it when it ends, Ctrl-C and crashes included, and replaces what a killed
run left behind. A soak reports what netem handled and dropped.

It needs Linux, and root or passwordless sudo. Without either, a user namespace has a loopback
interface of its own; it has no network either, so every download must already be cached and the
jar built:

```bash
unshare --map-root-user --net sh -c 'ip link set lo up &&
  e2e/run.sh --mc 1.21.4 --soak 5 --jar proxy/build/libs/warp-*.jar --netem "delay 50ms 20ms loss 1%"'
```

[`soak.yml`](../.github/workflows/soak.yml) soaks 1.21.4 every night for 30 minutes with 20 bots, on
a clean network and on a degraded one (`delay 50ms 20ms distribution normal loss 1%`) in parallel,
and on demand for a duration and a network of your choice. The run summary has both reports; the
time series, logs and flight recordings are its artifacts.

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
   `ProtocolVersion` in Warp, then run `npm run packet-ids` in `e2e/` (see below).
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

## Packet id reference

`npm run packet-ids` writes `protocol/src/test/resources/dev/warp/protocol/packet/packet-ids.txt`:
the id of every packet, in every state and direction, at each protocol `ProtocolVersion` registers,
taken from the pinned minecraft-data. Warp's `StateRegistryTest` checks every packet id Warp
registers against it, so a wrong id fails the unit tests rather than a player's session. Run it
again after adding a protocol to `ProtocolVersion` or bumping minecraft-data; the test reports a
table that misses a protocol.
