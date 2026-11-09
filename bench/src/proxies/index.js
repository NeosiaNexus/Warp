// The proxies under test, behind one interface so the orchestrator treats them alike: install the
// artifact (checksum-verified), write an equivalent config, launch with identical JVM policy for the
// JVM proxies, and confirm from the start-up log that natives and the epoll transport actually
// loaded. A run that cannot confirm a proxy's natives is marked unfair rather than silently compared,
// exactly the objection the methodology (docs/research/benchmark-methodology.md, section 6.3) answers.
//
// Equivalence across proxies:
//   - offline mode (online-mode off) everywhere;
//   - the same compression threshold (256) and level (-1) on the client link;
//   - modern player-info forwarding for Warp, Velocity and Gate; legacy for BungeeCord;
//   - no plugins, telemetry off, per-IP connection and login rate limits off;
//   - each proxy's own default transport and event-loop count (Netty default = 2x cores);
//   - identical JVM flags for the three JVM proxies (G1, fixed heap, pre-touch, leak detection off).
import { WarpProxy } from './warp.js';
import { VelocityProxy } from './velocity.js';
import { GateProxy } from './gate.js';
import { BungeeProxy } from './bungee.js';

/** Identical headline JVM flags for every JVM proxy (methodology section 6.2). */
export const JVM_HEADLINE_FLAGS = [
  '-Xms2g',
  '-Xmx2g',
  '-XX:+UseG1GC',
  '-XX:+AlwaysPreTouch',
  '-Dio.netty.leakDetection.level=disabled',
  // Java 25 warns when a library loads natives without this; the same flag for every JVM proxy.
  '--enable-native-access=ALL-UNNAMED',
];

const REGISTRY = {
  warp: WarpProxy,
  velocity: VelocityProxy,
  gate: GateProxy,
  bungeecord: BungeeProxy,
  gatelite: GateProxy, // same binary, Lite config; selected by id
};

/**
 * Builds a proxy adapter.
 * @param {string} id one of warp, velocity, gate, gatelite, bungeecord
 * @param {object} spec its entry from versions.json (proxies.<id>), plus `lite` for gatelite
 * @param {object} common shared settings (ports, forwarding secret, compression, cacheDir, dirs)
 */
export function makeProxy(id, spec, common) {
  const Cls = REGISTRY[id];
  if (!Cls) throw new Error(`unknown proxy: ${id}`);
  return new Cls({ id, spec, ...common });
}

export const PROXY_IDS = ['warp', 'velocity', 'gate', 'gatelite', 'bungeecord'];
