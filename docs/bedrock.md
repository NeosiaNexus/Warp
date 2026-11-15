# Bedrock players through Geyser

Warp lets Bedrock Edition players join a Java network through [Geyser](https://geysermc.org/) with
[Floodgate](https://geysermc.org/wiki/floodgate/)'s identity, without a Floodgate plugin on the
proxy: Warp reads, verifies and re-emits Floodgate's data itself. This page covers the supported
deployment, what Warp checks, what it tells backends, and what is still to come.

The design and its sources are in
[docs/research/modded-and-bedrock.md](research/modded-and-bedrock.md), sections 3 and 6.

## Deployment: standalone Geyser

```
Bedrock client  --UDP 19132-->  Geyser (standalone)  --TCP-->  Warp  --TCP-->  backends
                                auth-type: floodgate           bedrock.floodgate
                                key.pem  ============ same file ============  key.pem
```

1. Run Geyser standalone with `auth-type: floodgate` in its `config.yml`, and point its `remote`
   address at Warp. Geyser generates `key.pem` on its first start (Floodgate's key producer: a
   128-bit AES key, written as raw bytes).
2. Copy that `key.pem` into Warp's directory as it is, without converting it, and enable Floodgate in
   `warp.conf`:

   ```hocon
   bedrock {
     floodgate {
       enabled = true
       key-file = "key.pem"
       username-prefix = "."
       forward = false
     }
     geyser { trusted = ["127.0.0.1/32", "::1/128"] }
   }
   ```

3. List the address Geyser connects from in `bedrock.geyser.trusted` when it does not run on the same
   host as Warp (the loopback is trusted by default). Warp only takes the Bedrock player's own address
   from the Floodgate data when the connection comes from a trusted address; from anywhere else, the
   data must name the connection's own address, or the login is refused.

   Alternatively, enable `use-proxy-protocol` on Geyser's `remote` and list Geyser in
   `proxy-protocol.trusted-proxies`: Geyser then sends the Bedrock player's address in a PROXY
   protocol header, which the Floodgate data has to match.

4. Keep Geyser's UDP port behind your host's firewall or a Bedrock-aware filter (OVH and TCPShield
   both offer one): a public RakNet listener is an amplification target ([G#4527]), and that is
   Geyser's listener, not Warp's.

Geyser or Floodgate on another proxy (BungeeCord, Velocity) that forwards to Warp works the same way:
what reaches Warp is a Java connection whose handshake host name carries the Floodgate data, and the
key must be the one that encrypted it.

A Geyser plugin on a backend needs nothing from Warp: its Bedrock players never pass through the
proxy.

## What Warp verifies

Geyser appends Floodgate's data to the host name of its handshake, after a NUL:
`play.example.com\0^Floodgate^>` followed by Base64 of a 12-byte IV, `!`, and Base64 of the AES-GCM
ciphertext (128-bit tag). The plaintext is twelve NUL-separated fields: Geyser's version, the
gamertag, the XUID, the device OS, the language, the UI profile, the input mode, the player's address,
the linked Java account (or `null`), a from-proxy flag, and the skin uploader's id and code.
Verified against Floodgate's `FloodgateCipher`, `AesCipher`, `BedrockData` and `LinkedPlayer` (in the
Geyser repository, `common/src/main/java/org/geysermc/floodgate/`).

At the handshake, before the player sends anything else, Warp:

- decrypts the data with the key, which proves Geyser (or a proxy holding the same key) wrote it; a
  wrong key, an altered blob or an unknown version byte refuses the connection;
- refuses a blob it has already accepted in the last ten minutes: Floodgate's data has no timestamp,
  so this is what stops a captured handshake from logging in twice;
- checks the address the data names against the connection's, as described above;
- checks that the XUID, the address and the gamertag can make a player, and that a linked account's
  name is a Java name.

A refusal is never taken for a Java player: the connection is closed with a login disconnect the
Bedrock player sees ("Your Bedrock session could not be verified"), counted under
`floodgate_refused` in the limits report and the metrics, and logged once, then at most every five
minutes with the count in between ([W-BEDROCK-001](errors.md#w-bedrock-001)). Floodgate off, the
data is ignored and a name with the prefix is refused like any other invalid name.

## Identity

A verified player logs in as Floodgate would log it in:

| | Without a linked account | With a linked Java account |
|---|---|---|
| UUID | `00000000-0000-0000-0009-<xuid in hex>` (`new UUID(0, xuid)`) | the Java account's |
| Name | `<prefix><gamertag>`, spaces as `_`, cut to 16 characters | the Java account's |

The login is offline on Warp's side whatever `online-mode` says: Xbox Live authenticated the player
to Geyser and the key proves Geyser's word, so there is nothing to ask Mojang (and Geyser could not
answer an encryption request). A verified Bedrock player is not refused by the anti-bot's lockdown
either: its identity is as proven as an authenticated account's.

Only a verified Bedrock player may use a name with the prefix. In offline mode, a Java client logging
in as `.Steve` without Floodgate data is refused as an invalid name (Velocity's strict pattern
refused every Bedrock player instead, [V#1827]). Floodgate's default prefix is `.`, which no Java
name contains; with a prefix made of letters, digits or underscores, Java names starting with it are
reserved too, so prefer a punctuation character.

Plugins read the platform with `Player.platform()` (or `InboundConnection.platform()` during login):
a `ClientPlatform.Bedrock` with the XUID, gamertag, device OS, input mode, UI profile, language,
linked account and address. Unlike a Forge marker, it is a proof: nobody without the key can produce
it. `/find <player>` shows "Bedrock on Android" next to the version, and so does the login line.

## Routing

A route may apply to one platform:

```hocon
routes {
  "play.example.com"          = "lobby"
  "play.example.com@bedrock"  = "bedrock-lobby"
  "*@forge"                   = "modded"
}
```

`@bedrock`, `@java`, `@forge` and `@neoforge` go after the host (and after `:port`, if any); the
HOCON form `"play.example.com" @bedrock` is accepted too. A route with a platform wins over the same
host without one; a player whose platform has no entry takes the plain one.

## Anti-bot

The verification applies to Bedrock players as to anyone else: the probe, the budgets and the limits,
all under the player's own address. Two things change, both only with proof (the decrypted data,
never a `.name`, never a port of 0):

- the probe's deadlines are three times longer, as Geyser answers after its own translation and the
  Bedrock client's round trip (Sonar uses 45 s for 15 s);
- the world check, when the anti-bot gains one, is skipped: Geyser translates Bedrock movement,
  which is not Java physics (`AntiBotService.skipsWorldCheck`).

## Backends

Every backend receives the player's profile the usual way: with `forwarding.mode = "velocity"`, the
Velocity forwarding payload carries the UUID and the name above; with `none`, the backend derives an
offline UUID from the name as it does for every player.

A backend running Floodgate for its API (plugins asking `isFloodgatePlayer`, the device, the skin)
takes `floodgate = true`:

```hocon
servers {
  lobby    { address = "127.0.0.1:25566" }
  survival { address = "127.0.0.1:25567", floodgate = true }
}
```

Warp then appends the Floodgate data to that backend's handshake, re-encrypted under a fresh IV with
the same key and the from-proxy flag set, exactly as Floodgate on a proxy with `send-floodgate-data`
does: Floodgate on the backend, with the same `key.pem`, sees the player as Geyser presented it.
`bedrock.floodgate.forward = true` does it for every backend that does not say `floodgate = false`.
A backend without Floodgate ignores the suffix.

## Transfers

A Bedrock player transferred by a draining proxy logs in again through Geyser, with fresh Floodgate
data, under the rules above. The [seamless handoff](transfers.md) does not apply to it: Warp writes
no handoff ticket for a Bedrock player, and never asks a connection carrying Floodgate data for one.

## Not yet

- **Skins**: the profile has no textures yet, so Bedrock players appear with the default skin, and a
  Paper backend may query the session server for them (F#508). Floodgate's skin upload (Global API)
  comes with a later milestone.
- **Geyser in the same JVM**: the `LocalConnections` API and the `warp-geyser` plugin (design
  sections 6.1 and 6.2) are later milestones; standalone Geyser is the supported shape today.
- **End-to-end test**: the harness has no Bedrock client; the login is covered by unit tests driving a
  synthetic Floodgate blob through the real pipeline, and by byte-for-byte comparison of the cipher
  with Floodgate's.

## Sources

- Floodgate's format, read from the Geyser repository: `common/src/main/java/org/geysermc/floodgate/`
  (`crypto/FloodgateCipher.java`, `crypto/AesCipher.java`, `crypto/AesKeyProducer.java`,
  `util/BedrockData.java`, `util/LinkedPlayer.java`, `util/DeviceOs.java`, `util/InputMode.java`,
  `util/UiProfile.java`), and Floodgate's `core/src/main/java/org/geysermc/floodgate/util/Utils.java`
  (`getJavaUuid`) and `player/FloodgateHandshakeHandler.java` (how the host name is split, the result
  types; no replay check upstream).
- Geyser's `core/src/main/java/org/geysermc/geyser/session/GeyserSessionAdapter.java`: what Geyser
  writes in each field (address without IPv6 scope, `-1` and `null` without the skin uploader).
- Gate's Go port, `pkg/edition/bedrock/geyser/floodgate/` (`cipher.go`, `floodgate.go`,
  `device.go`, their tests): the second implementation the format was checked against.
- Floodgate's default `config.yml`: `username-prefix: "."`, `replace-spaces: true`, `key-file-name:
  key.pem`.
- Issue history: [V#1827] (strict names refuse `.name`), [G#6533] (plugins cannot detect Bedrock
  players without Floodgate data on backends), [F#508] (session-server lookups without properties),
  [G#4527] (RakNet amplification), [G#6679] and [F#645] (reflection and dependency breakages of the
  plugin shape, which native support avoids).

[V#1827]: https://github.com/PaperMC/Velocity/issues/1827
[G#6533]: https://github.com/GeyserMC/Geyser/issues/6533
[F#508]: https://github.com/GeyserMC/Floodgate/issues/508
[G#4527]: https://github.com/GeyserMC/Geyser/issues/4527
[G#6679]: https://github.com/GeyserMC/Geyser/issues/6679
[F#645]: https://github.com/GeyserMC/Floodgate/issues/645
