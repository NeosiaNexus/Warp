# Transfers and the seamless handoff

Since Minecraft 1.20.5 a server can send a client to another address with a Transfer packet, and
store small cookies in the client that survive the transfer. Warp uses both:

- a draining proxy transfers its players to `drain.transfer-to` (the public address, so the load
  balancer picks another proxy), and plugins can transfer a player with `Player.transfer`;
- a proxy only lets transferred clients in when `accepts-transfers = true`;
- with the **seamless handoff**, the proxy a player leaves hands its session to the proxy it lands
  on, so that the player is back on the server it was playing on without a second Mojang
  authentication.

## Why a handoff

Measured with a premium 1.21.4 client on two Warp proxies in online mode: a drain transfer shows no
disconnect screen, but takes 1.1 to 1.5 s, and the player lands on the default server. The time
goes to the full login the receiving proxy runs again: the client's `join` call to Mojang, the
proxy's `hasJoined` call, and the anti-bot verification of an account that proxy may not know. The
player's place is lost because the receiving proxy knows nothing of the session.

## How it works

1. Before the Transfer packet, and only when the target is a peer (`drain.transfer-to` or an entry
   of `transfers.handoff.peers`), the leaving proxy stores two cookies in the client:
   - `warp:handoff`, the **ticket**: the player's UUID, name and profile properties as Mojang
     returned them, the client's protocol version and address, the server the player is on, whether
     the proxy authenticated the player and whether its anti-bot trusts the account, the address of
     the transfer, a 128-bit random nonce, the issue and expiry times. It is sealed with
     AES-256-GCM under a key the proxies share.
   - `warp:handoff_proof`, the **proof**: 16 random bytes, also written inside the ticket. Only for
     players the proxy authenticated, whose connection is encrypted.
2. The receiving proxy asks a transferred client for the ticket during login, before encryption.
   The request is sent while the anti-bot recognizes the login, so the two waits overlap.
3. If the ticket opens and fits the login, the proxy sends the Encryption Request with
   `shouldAuthenticate = false`: the client still sets up encryption, but skips its `join` call,
   and the proxy skips `hasJoined`.
4. Once the connection is encrypted, the proxy asks for the proof. If it is the ticket's, the
   player logs in with the ticket's profile, the anti-bot treats a trusted account as verified
   (`VerificationMethod.HANDOFF`), and the player is proposed the ticket's server first.
5. After Login Success the proxy empties both cookies in the client.

The same events fire as for any login (`PreLoginEvent`, `GameProfileRequestEvent`, `LoginEvent`,
`PlayerChooseInitialServerEvent`, `PostLoginEvent`). Plugins tell a handoff apart with
`InboundConnection.isHandedOff()` (also on `Player`). The ticket carries the profile Mojang
returned, not what plugins made of it on the first proxy: `GameProfileRequestEvent` handlers run
again on the receiving proxy, from the same input as on a normal login.

Any ticket that is absent, malformed, expired, replayed, sealed under another key, or written for
another player, address, protocol version or proxy address is ignored: the login goes on exactly as
without the handoff (Mojang, anti-bot, default server). The handoff never makes a login weaker than
it would have been without a ticket, with one deliberate exception described below (the proof).

## Configuration

Off by default. Turn it on on every proxy of the network and give them the same key.

```hocon
accepts-transfers = true          # on the proxies that receive players

drain {
  transfer-to = "play.example.com:25565"   # a peer: transfers there carry a ticket
}

transfers {
  handoff {
    enabled = true
    secret-file = "transfer.secret"   # or WARP_TRANSFER_SECRET
    max-age = 15s                     # 1s to 60s
    bind-address = true
    peers = []                        # other addresses of your proxies, for plugin transfers
  }
}
```

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Write tickets before transfers to peers, read them from transferred logins. |
| `secret-file` | `transfer.secret` | The key file, relative to the proxy's directory. Generated at first start with 32 random bytes, readable by its owner only. Copy the same file to every proxy. |
| `WARP_TRANSFER_SECRET` | unset | The key from the environment instead (16 characters or more, random). Takes precedence over the file, which is then neither read nor created. |
| `max-age` | `15s` | How long a ticket is valid. The receiving proxy also tolerates 5 s of clock skew, and never accepts more than 60 s whatever the ticket says. |
| `bind-address` | `true` | A ticket is only valid from the IP address its player had on the proxy that wrote it. |
| `peers` | `[]` | Addresses, besides `drain.transfer-to`, a transfer to which carries a ticket. Only addresses of proxies you run. |

The address of a peer must be the one clients connect to and name in their handshake: the ticket is
bound to it. A transfer to an IP address also matches the local address the client reached.

The key is never the forwarding secret (backends hold that one; Warp refuses to start if
`WARP_TRANSFER_SECRET` equals it). The AES key is derived from the secret with HMAC-SHA256 and a
fixed label, so the secret is not used raw.

With a generated key that was not copied, a proxy only accepts tickets it wrote itself: the other
proxies count `warp_transfer_handoffs_rejected_total{reason="key"}`, log `W-AUTH-006` once a minute
at most, and log players in normally.

## Security model

A ticket is a bearer credential that stands in for Mojang's authentication, so the design assumes
the client and the network between client and proxies are hostile.

| Threat | Mitigation |
|---|---|
| A client forges a ticket, or changes its own (another UUID, name, skin, server, trust flag) | AES-256-GCM under a key only the proxies hold: any changed bit fails authentication. The header (version, key id, IV) is authenticated too. |
| A client that never came from a peer wants a ticket | Tickets are written only before a transfer this proxy sends to a configured peer, for the player on that connection, as this proxy authenticated it. |
| Someone reads the ticket on the wire (it is requested before encryption) and replays it | The ticket alone grants nothing: the proof is stored and read over encrypted connections only, and a ticket without its proof is refused. The ticket is also single-use, expires within `max-age`, and is bound to the client's address. |
| The legitimate ticket is replayed (same proxy) | Each proxy remembers the nonces of the tickets it used until they expire, in a bounded lock-free table (100 000 entries). When the table is full, new tickets are refused (full login) rather than old ones forgotten. |
| A ticket is taken to another machine | `bind-address`: the receiving proxy only takes the ticket from the address the writer saw. |
| A ticket is presented under another name, UUID or client version, or at another address than the transfer's | Each is compared; a mismatch is a full login. |
| A player authenticated by an offline-mode proxy is handed to an online-mode one | The ticket says the writer did not authenticate the player and carries no proof: the receiving proxy authenticates with Mojang, and the ticket only names the server. |
| A ticket reaches an offline-mode proxy | The player gets the offline identity that proxy gives any login; the ticket's profile is not used and it vouches for nothing to the anti-bot. |
| A ticket names a server the receiving proxy does not have, or one that is down | The server is looked up by name on the receiving proxy and must have its circuit breaker closed; otherwise normal routing applies. `PlayerChooseInitialServerEvent` still fires. |
| An oversized profile | A ticket that would exceed the 5120-byte cookie limit is not written: the transfer goes without a handoff. A signed textures property (about 2.5 kB) fits. |
| Leaks through logs or metrics | Neither the ticket, the proof, the nonce nor the key is ever logged or exported; rejection reasons are counted by label. The remote console masks `WARP_TRANSFER_SECRET`. |
| A weak or shared secret | The environment secret must be 16 characters or more; the forwarding secret is refused. |

### Encryption on a handed-off connection

A handed-off connection is encrypted exactly like a normal online-mode connection: the Encryption
Request and Response still happen (one round trip between client and proxy), only the two Mojang
calls are skipped (`shouldAuthenticate = false`, which exists in the protocol since 1.20.5 for this
purpose). Skipping the Encryption Request as well would save one more client round trip but leave
the session in clear, and would leave nothing to protect the ticket against a passive observer.

### What the handoff gives up

- **An active man in the middle on the second connection.** Mojang's `join`/`hasJoined` exchange
  binds the session to the proxy's public key, so an attacker who intercepts the connection cannot
  log in as the player. With `shouldAuthenticate = false` the client does not check whom it sets up
  encryption with. An attacker who can intercept the client's connection to the receiving proxy
  within the ticket's lifetime, terminate encryption itself, and connect to the real proxy from the
  client's address, can take over that session. `bind-address` stops attackers that are not on the
  client's path (a hijacked DNS answer pointing to another machine, for example); it does not stop
  one that sits on the path and can use the client's address. Networks for which that is not
  acceptable leave the handoff off.
- **`bind-address = false`** removes the address check, for networks whose proxies do not see the
  same client address (a load balancer without PROXY protocol on one path, IPv4 on one proxy and
  IPv6 on the other). The ticket then relies on its seal, its proof, its single use and its expiry.
  Check `warp_transfer_handoffs_rejected_total{reason="address"}` before turning it off.
- **A wrong or missing proof refuses the login** instead of falling back: the client was already
  told not to authenticate with Mojang, so there is nothing to fall back to. The player sees
  "Your transfer could not be verified" and reconnects normally. This only happens to a ticket
  presented without its proof, which a legitimate client never does.
- **Single use is per proxy.** A ticket for an address several proxies answer could be accepted
  once by each within its lifetime. That needs the proof too, so only the player itself (or the on
  path attacker above) can do it, and it gains nothing over logging in twice.
- **Clocks.** Proxies must agree on the time within 5 s plus `max-age`; `reason="clock"` and
  `reason="expired"` count the tickets refused for it.

## Observing it

| Metric | Meaning |
|---|---|
| `warp_transfers_total{direction,handoff}` | Transfers sent (`out`) and transferred logins (`in`), with a ticket `used`, `absent` or `invalid`. |
| `warp_transfer_handoffs_rejected_total{reason}` | Tickets not used: `malformed`, `version`, `key`, `tampered`, `expired`, `clock`, `target`, `protocol`, `address`, `name`, `uuid`, `replayed`, `busy`, `proof`. |
| `warp_transfer_handoff_login_duration_seconds` | Handshake to Login Success for handed-off logins; compare with `warp_login_duration_seconds`. |

The connection log line of a handed-off player ends with `handed off by <node> without Mojang`.
