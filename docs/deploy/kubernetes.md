# Deploying Warp on Kubernetes

The `warp` Helm chart deploys a fleet of proxies whose rolling updates, scale-downs and node
drains move players instead of disconnecting them. It is published as an OCI chart on every
release:

```bash
helm install warp oci://ghcr.io/neosianexus/charts/warp --version <version> \
  --namespace minecraft --create-namespace \
  --set config.servers.lobby.address=lobby.minecraft.svc.cluster.local:25565 \
  --set drain.transferTo=play.example.com
```

The chart's source is [charts/warp](../../charts/warp); every value is documented in its
[values.yaml](../../charts/warp/values.yaml). It installs:

- a **Deployment** of proxies (2 by default) with `maxSurge: 1, maxUnavailable: 0`, startup,
  readiness and liveness probes on Warp's management server, a non-root, read-only root
  filesystem security context, and a `terminationGracePeriodSeconds` derived from the drain
  settings;
- a **Service** for the players (`LoadBalancer`, `externalTrafficPolicy: Local`) and a ClusterIP
  Service for the management port (Prometheus, the remote console), never exposed;
- a **ConfigMap** holding `warp.conf`, generated from the values (servers, groups, routes,
  forwarding, status, compression, PROXY protocol, and anything else under `config.extra`);
- a **Secret** with the forwarding secret, the management token and the anti-bot's shared keys,
  generated once and kept across upgrades, or yours through `secrets.existingSecret`;
- a **PodDisruptionBudget**, and optionally a **HorizontalPodAutoscaler**, a **ServiceMonitor**,
  a **NetworkPolicy** and a `helm test` Pod.

## Why a Deployment

A proxy is stateless: a player's session lives in memory and moves with the player, nothing is
worth a volume, and the Service, not a Pod's name, is what players connect to. A Deployment rolls
Pods in parallel and lets a scale-down pick any Pod; a StatefulSet would serialize updates and
always remove the last Pod, for stable names nobody uses. The anti-bot's store and the logs live in
an `emptyDir`; the anti-bot's keys are shared through the Secret, so that a player verified on one
proxy stays verified on the next one.

## How a rolling update keeps every player connected

This is the point of the chart. When a Pod must go (a new image, a configuration change, a
scale-down, a node drain), Kubernetes sends it SIGTERM and removes it from the Service's
endpoints at the same time. Warp's configuration in the chart says `drain.on-sigterm = true`, so
SIGTERM starts a drain instead of a shutdown:

1. **Readiness says no.** `/readyz` answers 503 at once; the Pod leaves the endpoints and the load
   balancer stops sending new players here. Warp waits `drain.propagationDelay` (10 s) for that to
   propagate, since kube-proxy and cloud balancers take a few seconds.
2. **Players are transferred.** Each player on a 1.20.5+ client receives a Transfer packet to
   `drain.transferTo`, the network's public address, one every `drain.transferInterval`
   (100 ms). The client reconnects there as if the player had typed the address, which lands on
   the Service, hence on a Pod that is Ready: a new one, since the surge Pod of the rolling update
   became Ready before this one started draining. The client shows a brief reconnection screen,
   keeps its settings and its chat, and the backend sees a new connection from the same player.
   Plugins may hold a player back (a match in progress) until they reach another server.
3. **The rest leave on their own.** Players Warp cannot transfer (clients older than 1.20.5, or
   no `transferTo`) stay on the proxy until they disconnect, at most `drain.timeout` (30 min), or
   are kicked right away with `drain.cannotTransfer: kick`.
4. **The Pod exits.** Once empty, Warp disables its plugins and exits 0; after the time-out it
   disconnects whoever is left and exits 2. Kubernetes removes the Pod. The chart sets
   `terminationGracePeriodSeconds` to `propagationDelay + timeout + gracePeriodMargin` (1870 s by
   default), so that Kubernetes never kills a proxy still holding players. The default Pod
   disruption budget (`minAvailable: 1`) keeps a node drain from taking every proxy at once.

A second SIGTERM stops the proxy at once: `kubectl delete pod --grace-period=0` still works.

Set `drain.transferTo` to the address players type (`play.example.com`, with the port if it is
not 25565). Without it, a rolling update takes as long as the last player stays, up to
`drain.timeout` per Pod, without disconnecting anyone. Transfers require the backends to accept
the players back without complaint: Warp passes the players' identity along, and the chart sets
`accepts-transfers = true` so that a player a draining proxy sends to the Service is let in by
the proxy that answers.

`drain.mode: preStop` triggers the drain from a `preStop` hook instead, which runs the image's
`warp drain --wait` against the management API with the management token and returns once every
player left; SIGTERM then finds an exiting proxy. The outcome is the same; choose it when your
platform expects the drain to be an explicit lifecycle hook.

## Players' addresses: `externalTrafficPolicy` and the PROXY protocol

With `service.externalTrafficPolicy: Local` (the default), a node forwards only to the proxies it
runs and keeps the players' source addresses, which Warp uses for its logs, its limits and the
address it forwards to the backends. The load balancer's health checks remove the nodes without a
proxy, so this also works with fewer proxies than nodes. `Cluster` spreads the load across nodes
but hides the players behind the nodes' addresses, unless the balancer sends the PROXY protocol:

```yaml
config:
  proxyProtocol:
    enabled: true
    trustedProxies: [10.0.0.0/8]     # the balancer's addresses, never the players'
service:
  externalTrafficPolicy: Cluster
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-type: nlb
    service.beta.kubernetes.io/aws-load-balancer-proxy-protocol: '*'
    # GKE: cloud.google.com/l4-rbs: enabled, then a BackendService with PROXY protocol
    # Hetzner: load-balancer.hetzner.cloud/uses-proxyprotocol: 'true'
    # Scaleway: service.beta.kubernetes.io/scw-loadbalancer-proxy-protocol-v2: 'true'
```

Warp then requires the header on every connection from a trusted proxy and refuses connections
from anywhere else. When the PROXY protocol is on, the health checks of the balancer must send it
too, or probe the node port of a Pod directly.

## Configuration

The chart renders `warp.conf` from `config.*` and mounts it at `/data/warp.conf`:

```yaml
config:
  servers:
    lobby-1: lobby-1.minecraft.svc.cluster.local:25565
    lobby-2: lobby-2.minecraft.svc.cluster.local:25565
    survival:
      address: survival.minecraft.svc.cluster.local:25565
  groups:
    lobbies:
      servers: [lobby-1, lobby-2]
      strategy: least-connections
  defaultServer: lobbies
  fallbackOrder: [lobbies]
  routes:
    survival.example.com: survival
  status:
    motd: '<gradient:#38bdf8:#a78bfa>My network</gradient>'
  extra: |
    limits { connections { burst = 20 } }
    anti-bot { level = "normal" }
```

Keys left out keep the defaults of Warp's reference configuration, which documents them all: run
`java -jar warp.jar` in an empty directory to get it, or read `defaultConfig()` in
`proxy/src/main/java/dev/warp/proxy/config/WarpConfig.java`. `config.raw` replaces the generated
file with yours, except for the `management` and `drain` blocks, which the chart's probes and
hooks rely on. A configuration change rolls the Pods (a checksum annotation), each draining in
turn. So does a change to the chart's Secret (`helm upgrade --set secrets.forwardingSecret=...`
after rotating it on the backends, a new management token): the proxies read their secrets from
the environment at start, and a Pod that is not rolled would keep the old ones. Rotating a key of
`secrets.existingSecret` is outside the chart: roll the Deployment yourself (`kubectl rollout
restart`).

Backends must accept Velocity modern forwarding with the chart's forwarding secret:

```bash
kubectl -n minecraft get secret warp -o jsonpath='{.data.forwarding-secret}' | base64 -d
```

Paper reads it from `proxies.velocity.secret` in `config/paper-global.yml`; the
[itzg/minecraft-server](https://github.com/itzg/docker-minecraft-server) image takes
`PAPER_VELOCITY_SECRET`, which can come from the same Secret with `envFrom`. Keep
`network-compression-threshold` at 256 (Paper's default) so that compressed frames pass through.

## Metrics and autoscaling

With the Prometheus Operator, `management.metrics.serviceMonitor.enabled: true` scrapes
`/metrics` from the management Service (`labels` selects the Prometheus instance); the catalogue is
in [docs/metrics.md](../metrics.md). Without the operator,
`management.metrics.podAnnotations: true` adds the `prometheus.io/*` annotations.

The HPA scales on CPU by default. To scale on players, expose `warp_players_online` to the HPA
through the [Prometheus adapter](https://github.com/kubernetes-sigs/prometheus-adapter) (a rule
mapping the metric to the Pods) or KEDA, then:

```yaml
autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetPlayersPerPod: 300
```

Scaling down removes a Pod, which drains like any other: the chart's HPA behaviour removes one
Pod at a time, after a ten-minute stabilization window, so that a flash crowd leaving does not
bounce the fleet. Scale down slowly, drain fully.

## Remote console

`management.console.enabled: true` with a token lets you attach the console from your machine,
without a shell in the Pod:

```yaml
management:
  console:
    enabled: true
    tokens:
      - name: ops
        scopes: [logs, commands]
        secretKey: console-ops-token
        generate: true          # created in the chart's Secret
```

```bash
kubectl -n minecraft port-forward svc/warp-management 9901
kubectl -n minecraft get secret warp -o jsonpath='{.data.console-ops-token}' | base64 -d > ops.token
java -jar warp.jar console attach --url http://localhost:9901 --token-file ops.token
```

Port-forwarding arrives on the Pod's loopback, which Warp trusts for commands without TLS. To reach
the console across the cluster, give the management server a certificate
(`management.tls.secretName`, a cert-manager Secret) or list the trusted networks. Issue the
certificate for `<management service>.<namespace>.svc` (`warp-management.minecraft.svc` here): the
ServiceMonitor and the helm test verify that name. Clients that connect over loopback (the preStop
drain hook, a port-forwarded console with `--ca-file`) verify the certificate against the CA only,
not its name, since the peer can only be the local proxy; the certificate needs no IP address.

## Security

The Pods run as uid 1000 with a read-only root filesystem, no capabilities and the runtime's
seccomp profile; `/data` and `/tmp` are `emptyDir` volumes. The management port is never exposed
outside the cluster. `networkPolicy.enabled: true` allows the players' port from anywhere, the
management port from the selectors in `networkPolicy.managementFrom` (Prometheus), and egress to
DNS, the backends (`networkPolicy.egressTo`) and public addresses on 443 (Mojang's session
servers). The kubelet's probes come from the node and need no rule.

## Compatibility with Minecraft operators

The chart is standalone: it needs nothing but Kubernetes. It also fits alongside the operators of
the ecosystem:

- **Agones** fleets of game servers are regular backends: give the chart their Service addresses,
  or let a plugin register the allocated `GameServer`s at runtime (`servers: {}` with
  `config.raw`). The proxies themselves are not Agones `GameServer`s: a proxy is never allocated
  to a match, and the drain, not Agones's shutdown, is what empties it.
- **Shulker** manages its own proxy fleet (an Agones `Fleet` of the itzg image with a Velocity
  agent); Warp is not a `ProxyFleet` type there yet. Shulker's `MinecraftServerFleet`s are
  reachable backends for this chart through their Services; `commonLabels` adds the labels an
  operator selects by.

## Verifying the chart

```bash
cosign verify ghcr.io/neosianexus/charts/warp:<version> \
  --certificate-identity-regexp 'https://github.com/NeosiaNexus/Warp/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
helm show values oci://ghcr.io/neosianexus/charts/warp --version <version>
```

CI lints the chart, renders it with every values file under `charts/warp/ci/`, validates the
manifests with kubeconform against the Kubernetes and Prometheus Operator schemas, and checks that
the schema refuses a typo or a bad duration and that the grace period follows the drain settings.
