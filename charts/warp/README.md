# warp

Helm chart for [Warp](https://github.com/NeosiaNexus/Warp), a high-performance Minecraft proxy
whose rolling updates move players instead of disconnecting them.

```bash
helm install warp oci://ghcr.io/neosianexus/charts/warp --version <version> \
  --namespace minecraft --create-namespace \
  --set config.servers.lobby.address=lobby.minecraft.svc.cluster.local:25565 \
  --set drain.transferTo=play.example.com
```

Every value is documented in [values.yaml](values.yaml); how the drain, the probes, the PROXY
protocol, the metrics and the autoscaling fit together is in
[docs/deploy/kubernetes.md](../../docs/deploy/kubernetes.md).

The chart's version follows Warp's: `version` and `appVersion` are both the release's, and the
image tag defaults to `appVersion` (`image.tag: <version>-alpine` for the musl variant). The files
under `ci/` are the values CI renders and validates the chart with.
