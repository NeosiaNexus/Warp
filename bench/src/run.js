// Runs the whole benchmark for one proxy: boot three Paper backends configured for the proxy's
// forwarding, launch the proxy with equivalent settings, confirm its natives from the start-up log,
// sample it once a second while every scenario runs in turn, then tear everything down. The caller
// runs this for each proxy in a shuffled order and hands the collected results to the report
// generator. Nothing here fabricates a number: a scenario that fails records the failure and the run
// goes on, and an unfair run (natives unconfirmed) is flagged, never hidden.
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

import { Backend, bootBackends, fetchBackendJar } from './backend.js';
import { makeProxy } from './proxies/index.js';
import { Sampler, toCsv } from './sample.js';
import { idleScenario, trafficScenario, latencyScenario, switchScenario, stormScenario } from './scenarios.js';
import { findJava, sleep } from './reuse.js';

const SERVER_NAMES = ['lobby', 'survival', 'mini'];

/**
 * @param {object} opts
 * @param {string} opts.proxyId warp | velocity | gate | gatelite | bungeecord
 * @param {object} opts.versions parsed versions.json
 * @param {object} opts.profile sizing for each scenario (full or smoke)
 * @param {string[]} opts.scenarios which scenarios to run
 * @param {object} opts.ports {proxy, backendBase}
 * @param {object} opts.dirs {cache, work, logs, results}
 * @param {{proxy: string[]|null, backends: string[]|null, bots: string[]|null}} opts.cpus pinning plan
 * @param {string|null} opts.warpJar local Warp jar to use instead of the release
 * @returns {Promise<object>} the proxy's results (metrics, sampler rows, native-check notes)
 */
export async function runProxy(opts) {
  const { proxyId, versions, profile, scenarios, ports, dirs, cpus, warpJar, mcVersion, backendHeapMb } = opts;
  const spec = { ...(versions.proxies[proxyId] ?? versions.proxies[proxyId.replace('lite', '')]) };
  if (proxyId === 'gatelite') Object.assign(spec, versions.proxies.gate);
  const label = proxyId === 'gatelite' ? 'Gate Lite' : spec.label ?? proxyId;

  const workDir = join(dirs.work, proxyId);
  const logDir = join(dirs.logs, proxyId);
  mkdirSync(workDir, { recursive: true });
  mkdirSync(logDir, { recursive: true });

  const backendPorts = SERVER_NAMES.map((_, i) => ports.backendBase + i);
  const backends = Object.fromEntries(SERVER_NAMES.map((name, i) => [name, backendPorts[i]]));

  const proxy = makeProxy(proxyId, spec, {
    port: ports.proxy,
    backends,
    defaultBackend: SERVER_NAMES[0],
    compressionThreshold: versions.compressionThreshold,
    compressionLevel: versions.compressionLevel,
    secret: versions.forwardingSecret,
    cacheDir: dirs.cache,
    workDir,
    logDir,
    cpus: cpus.proxy,
    localJar: proxyId === 'warp' ? warpJar : null,
  });

  const result = { id: proxyId, label, forwarding: proxy.forwarding, metrics: {}, errors: [], natives: null };

  // Backends first (the proxy needs their ports), configured for this proxy's forwarding.
  const backendJar = await fetchBackendJar(versions.backend, dirs.cache);
  let bootedBackends = [];
  let directBackend = null;
  const directPort = ports.backendBase + 10;
  const origin = Date.now();
  let sampler = null;
  try {
    bootedBackends = await bootBackends({
      jar: backendJar,
      javaMajor: versions.backend.java,
      cacheDir: dirs.cache,
      baseDir: join(workDir, 'backends'),
      logDir,
      basePort: ports.backendBase,
      backend: versions.backend,
      forwarding: proxy.forwarding,
      secret: versions.forwardingSecret,
      compressionThreshold: versions.compressionThreshold,
      maxHeapMb: backendHeapMb,
    });

    // The latency probe needs a backend it can reach without a proxy. The real backends require this
    // proxy's forwarding, so a direct connection to them is rejected; this clone accepts plain
    // players and is not registered with the proxy. The added-latency number is then the proxy hop.
    if (scenarios.includes('latency')) {
      const java = await findJava(versions.backend.java, dirs.cache);
      directBackend = new Backend({
        name: 'direct',
        dir: join(workDir, 'backends', 'backend-direct'),
        jar: backendJar,
        java,
        port: directPort,
        protocol: versions.backend.protocol,
        compressionThreshold: versions.compressionThreshold,
        gameMode: 1,
        forwarding: 'none',
        secret: versions.forwardingSecret,
        logDir,
        maxHeapMb: backendHeapMb,
      });
      await directBackend.boot();
    }

    await proxy.launch();
    result.natives = proxy.verifyNatives();

    const state = { scenario: '', phase: 'idle', offered: 0, bots: 0 };
    sampler = new Sampler({
      pid: proxy.pid,
      port: ports.proxy,
      backends: backendPorts,
      origin,
      annotate: () => ({ proxy: proxyId, ...state }),
    }).start();

    const ctx = {
      port: ports.proxy,
      version: mcVersion,
      serverNames: SERVER_NAMES,
      backendPorts,
      directPort,
      rows: () => sampler.rows,
      now: () => Math.round((Date.now() - origin) / 100) / 10,
      state: (next) => Object.assign(state, next),
    };

    for (const name of scenarios) {
      try {
        result.metrics[name] = await runScenario(name, ctx, profile, proxyId);
      } catch (e) {
        result.errors.push(`${name}: ${e.message}`);
      }
    }
  } catch (e) {
    result.errors.push(`setup/launch: ${e.message}`);
  } finally {
    if (sampler) await sampler.stop();
    await proxy.stop().catch(() => {});
    await Promise.allSettled([...bootedBackends, directBackend].filter(Boolean).map((b) => b.stop()));
    await sleep(500);
  }

  result.rows = sampler ? sampler.rows : [];
  result.csv = toCsv(result.rows);
  result.proxyFailures = proxy.process?.failures?.() ?? [];
  return result;
}

function runScenario(name, ctx, profile, proxyId) {
  const p = profile[name];
  switch (name) {
    case 'idle':
      return idleScenario(ctx, p);
    case 'traffic':
      return trafficScenario(ctx, p);
    case 'latency':
      return latencyScenario(ctx, p);
    case 'switch':
      // Gate Lite has no /server command; skip switching rather than report zero.
      if (proxyId === 'gatelite') return Promise.resolve({ skipped: 'Gate Lite has no server-switch command' });
      return switchScenario(ctx, p);
    case 'storm':
      return stormScenario(ctx, p);
    default:
      throw new Error(`unknown scenario: ${name}`);
  }
}

export { SERVER_NAMES, findJava };
