// Chooses how bots reach Warp for a matrix entry:
//   - natively: mineflayer speaks the entry's version itself;
//   - bridged ("via" in versions.json): mineflayer speaks an older version to ViaProxy, which
//     translates to the entry's version. Warp then sees genuine wire traffic of a version mineflayer
//     cannot speak yet (or never could, e.g. 1.9.1), and every packet Warp forwards is still parsed,
//     either by ViaVersion or by the bot.
import * as mineflayer from './mineflayer.js';
import { ViaProxy } from './viaproxy.js';

/**
 * @param {object} entry versions.json entry
 * @param {{tools: object, cache: string, java: string, ports: object, targets: number[], logDir: string, runDir: string}} env
 *   `targets` are the ports bots connect to (Warp instances, or the lobby in a --direct run)
 */
export function connectClient(entry, env) {
  const speaks = entry.via ?? entry.client ?? entry.version;
  const announced = mineflayer.announcedProtocol(speaks);
  if (!entry.via && announced !== entry.protocol) {
    throw new Error(
      `mineflayer cannot speak ${entry.version} (protocol ${entry.protocol}): it would announce ${announced}. ` +
        'Bridge it through ViaProxy with "via" in versions.json.',
    );
  }
  if (announced === null) throw new Error(`mineflayer has no data for ${speaks}`);

  if (!entry.via) {
    return {
      description: `mineflayer ${speaks}`,
      connect: (options) => mineflayer.connect({ ...options, version: speaks }),
      ping: (options) => mineflayer.ping({ ...options, version: speaks }),
    };
  }

  // One ViaProxy per target (Warp, Warp-alt), so that each bridge forwards to a fixed address.
  const viaPorts = [env.ports.via, env.ports.viaAlt];
  const bridges = new Map(
    env.targets.map((target, i) => [
      target,
      new ViaProxy({ name: i === 0 ? 'viaproxy' : `viaproxy-${i}`, port: viaPorts[i], target, version: entry.version, env }),
    ]),
  );
  const bridged = (options) => ({ ...options, port: bridges.get(options.port).port, version: speaks });
  return {
    description: `mineflayer ${speaks} → ViaProxy → ${entry.version}`,
    start: () => Promise.all([...bridges.values()].map((b) => b.start())),
    stop: () => Promise.all([...bridges.values()].map((b) => b.stop())),
    connect: (options) => mineflayer.connect(bridged(options)),
    ping: (options) => mineflayer.ping(bridged(options)),
    failures: () => [...bridges.values()].flatMap((b) => b.failures()),
    bridges: () => [...bridges.values()],
  };
}
