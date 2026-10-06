# Security Policy

Warp sits between the internet and your Minecraft servers: it authenticates players, handles
encryption and forwards player identities to the backends. Security reports are welcome and taken
seriously.

## Supported versions

Warp is in pre-release (`0.x`). Only the latest release receives security fixes: fixes land on
`main` first and ship in the next release, without backports to older releases.

| Version | Supported |
|---|---|
| Latest [release](https://github.com/NeosiaNexus/Warp/releases) | Yes |
| `main` | Yes, fixes land here first |
| Older releases | No, upgrade to the latest release |

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a vulnerability.**

Report it privately through GitHub's private vulnerability reporting:
**[Report a vulnerability](https://github.com/NeosiaNexus/Warp/security/advisories/new)** (also
under the repository's *Security* tab). Only the maintainer can see the report, and the
conversation and the fix are prepared in a private advisory.

## What to include

- The affected component (proxy, protocol codec, API) and the Warp version or commit: the first
  line Warp logs at startup, `Starting Warp <version> (commit: <sha>, branch: <branch>)`.
- The Minecraft client version and the backend software involved, and the relevant `warp.conf`
  settings (`online-mode`, forwarding mode, compression), **without the forwarding secret**.
- The impact: what an attacker can achieve, and from where (any client on the network, an
  authenticated player, a backend server).
- Steps to reproduce or a proof of concept. A minimal client, a script or a packet capture is ideal.
- A suggested fix, if you have one, and how you would like to be credited.

Please test only against Warp instances and servers that you run yourself.

## What to expect

Warp is maintained by one person, so these targets are ones that can be kept, not aspirational
ones:

| Step | Target |
|---|---|
| Acknowledgement of the report | Within 7 days |
| First assessment (confirmed or not, severity) | Within 14 days |
| Fix released, critical or high severity | Within 30 days of confirmation |
| Fix released, medium or low severity | Within 90 days of confirmation |

You will get an update in the advisory at least every 14 days until the issue is resolved. If a
target cannot be met, you will be told why and when to expect the next step.

## Coordinated disclosure

- Please keep the details private until a fix is released, or for 90 days after your report,
  whichever comes first. A different date can be agreed on in the advisory, for example for a fix
  that needs a protocol change.
- Once the fix is released, the maintainer publishes the GitHub security advisory, requests a CVE
  through GitHub when the issue warrants one, and credits you unless you prefer to stay anonymous.

## Scope

In scope:

- **The proxy** (`proxy/`): connection handling, login, Mojang authentication and encryption,
  player info forwarding, server switching and fallback, configuration loading.
- **The protocol codec** (`protocol/`): framing, compression and decompression, packet decoding and
  encoding. For example: crashes or memory and CPU exhaustion caused by crafted packets,
  decompression bombs, buffer leaks an attacker can trigger.
- **The plugin API** (`api/`).
- **The release jars** and the workflows that build and publish them.

Typical vulnerabilities: authentication bypass, impersonation of another player, forged or leaked
forwarding data, disclosure of the forwarding secret or of player data, remote denial of service
from a single client.

Out of scope:

- Vulnerabilities in third-party software: Minecraft clients and servers (vanilla, Paper and other
  backends), mods, plugins, other proxies. Please report them to their maintainers.
- Vulnerabilities in Warp's dependencies (Netty, Log4j and others) that Warp's use does not make
  exploitable: report them upstream. If Warp ships an affected version in an exploitable way, it is
  in scope.
- Deployments that let players reach a backend directly, bypassing Warp: keep backends behind a
  firewall and use forwarding.
- Impersonation when Warp runs with `online-mode = false`, which by design does not authenticate
  players.
- Volumetric network floods. Resource exhaustion caused by crafted traffic is in scope.
