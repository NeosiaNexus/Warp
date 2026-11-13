// Runs the whole benchmark for one proxy. The backends are booted once: the stub backend for the
// idle scenario, three Paper servers configured for the proxy's forwarding for the others, and a
// forwarding-free Paper clone for the latency baseline, all pinned to the backend cores. Then every
// scenario gets a freshly launched proxy (so no scenario inherits the heap, the caches or the
// connections of the one before), pointed at the backends that scenario uses, sampled once a second
// from outside while the load workers drive it. Nothing here fabricates a number: a scenario that
// fails records the failure and the run goes on, an unfair run (natives unconfirmed) is flagged, and
// each scenario stores the verdict of the validity gate with its result.
//
// Layout of a proxy's working directory: `work/<proxy>/proxy` belongs to the proxy and is wiped at
// every launch; `work/<proxy>/backends/<name>` belongs to each backend. They must never nest: a
// proxy that wipes a directory holding a running backend deletes that server's world and
// configuration under it, and the backend then fails every save and every chunk load.
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';

import { Backend, bootBackends, fetchBackendJar, StubBackend } from './backend.js';
import { liveHeapMiB } from './heap.js';
import { makeProxy } from './proxies/index.js';
import { Sampler, toCsv } from './sample.js';
import { idleScenario, trafficScenario, latencyScenario, switchScenario, stormScenario } from './scenarios.js';
import { findJava, sleep } from './reuse.js';

const SERVER_NAMES = ['lobby', 'survival', 'mini'];
const CONFIG_FILES = ['warp.conf', 'velocity.toml', 'config.yml', 'config.toml'];

/**
 * @param {object} opts
 * @param {string} opts.proxyId warp | velocity | gate | gatelite | bungeecord
 * @param {object} opts.versions parsed versions.json
 * @param {object} opts.profile sizing for each scenario (full or smoke)
 * @param {string[]} opts.scenarios which scenarios to run
 * @param {object} opts.ports {proxy, backendBase}
 * @param {object} opts.dirs {cache, work, logs, results}
 * @param {{proxy: string[]|null, backends: string[]|null}} opts.cpus pinning plan
 * @param {import('./fleet.js').Fleet} opts.fleet the load workers
 * @param {string|null} opts.warpJar local Warp jar to use instead of the release
 * @returns {Promise<object>} the proxy's results (metrics, sampler rows, native-check notes)
 */
export async function runProxy(opts) {
  const { proxyId, versions, profile, scenarios, ports, dirs, cpus, fleet, warpJar, mcVersion, backendHeapMb } = opts;
  const spec = { ...(versions.proxies[proxyId] ?? versions.proxies[proxyId.replace('lite', '')]) };
  if (proxyId === 'gatelite') Object.assign(spec, versions.proxies.gate);
  const label = proxyId === 'gatelite' ? 'Gate Lite' : spec.label ?? proxyId;

  const workDir = join(dirs.work, proxyId);
  const logDir = join(dirs.logs, proxyId);
  rmSync(workDir, { recursive: true, force: true });
  mkdirSync(workDir, { recursive: true });
  mkdirSync(logDir, { recursive: true });

  const paperPorts = Object.fromEntries(SERVER_NAMES.map((name, i) => [name, ports.backendBase + i]));
  const directPort = ports.backendBase + 10;
  const stubPort = ports.backendBase + 20;

  const newProxy = (backends, logName) =>
    makeProxy(proxyId, spec, {
      port: ports.proxy,
      backends,
      defaultBackend: SERVER_NAMES[0],
      compressionThreshold: versions.compressionThreshold,
      compressionLevel: versions.compressionLevel,
      secret: versions.forwardingSecret,
      cacheDir: dirs.cache,
      workDir: join(workDir, 'proxy'),
      logDir,
      logName,
      cpus: cpus.proxy,
      localJar: proxyId === 'warp' ? warpJar : null,
    });
  const forwarding = newProxy(paperPorts, proxyId).forwarding;

  const result = { id: proxyId, label, forwarding, metrics: {}, errors: [], natives: null, proxyFailures: [] };
  const rows = [];
  const origin = Date.now();
  let paper = [];
  let direct = null;
  let stub = null;
  try {
    if (scenarios.some((s) => s !== 'idle')) {
      const jar = await fetchBackendJar(versions.backend, dirs.cache);
      const common = {
        jar,
        logDir,
        backend: versions.backend,
        secret: versions.forwardingSecret,
        compressionThreshold: versions.compressionThreshold,
        maxHeapMb: backendHeapMb,
        cpus: cpus.backends,
      };
      paper = await bootBackends({ ...common, javaMajor: versions.backend.java, cacheDir: dirs.cache, baseDir: join(workDir, 'backends'), basePort: ports.backendBase, forwarding });
      // The latency probe needs a backend it can reach without a proxy. The real backends require
      // this proxy's forwarding, so a direct connection to them is rejected; this clone accepts
      // plain players and is not registered with the proxy.
      if (scenarios.includes('latency')) {
        direct = new Backend({
          ...common,
          name: 'direct',
          dir: join(workDir, 'backends', 'backend-direct'),
          java: await findJava(versions.backend.java, dirs.cache),
          port: directPort,
          protocol: versions.backend.protocol,
          gameMode: 1,
          forwarding: 'none',
        });
        await direct.boot();
      }
    }
    if (scenarios.includes('idle')) {
      stub = await new StubBackend({
        name: 'stub',
        port: stubPort,
        forwarding,
        secret: versions.forwardingSecret,
        compressionThreshold: versions.compressionThreshold,
        logDir,
        cpus: cpus.backends,
      }).boot();
    }

    for (const name of scenarios) {
      // Gate Lite has no /server command; skip switching rather than report zero.
      if (name === 'switch' && proxyId === 'gatelite') {
        result.metrics.switch = { skipped: 'Gate Lite has no server-switch command' };
        continue;
      }
      const behind = { idle: [stub], traffic: [paper[0]], latency: [paper[0]], switch: paper, storm: [paper[0]] }[name];
      const backendPorts = name === 'idle' ? { [SERVER_NAMES[0]]: stubPort } : paperPorts;
      const proxy = newProxy(backendPorts, `${proxyId}-${name}`);
      let sampler = null;
      try {
        await proxy.launch();
        for (const f of CONFIG_FILES) {
          if (existsSync(join(proxy.workDir, f))) cpSync(join(proxy.workDir, f), join(dirs.results, 'configs', `${proxyId}-${f}`));
        }
        // Natives must be confirmed at every launch: one unconfirmed launch marks the proxy unfair.
        const natives = proxy.verifyNatives();
        if (!result.natives || !natives.ok) result.natives = natives;

        const state = { scenario: name, phase: 'start', offered: 0, bots: 0 };
        sampler = new Sampler({
          pid: proxy.pid,
          port: ports.proxy,
          backends: Object.values(backendPorts),
          origin,
          annotate: () => ({ proxy: proxyId, ...state }),
        }).start();
        const activeSampler = sampler;
        const ctx = {
          port: ports.proxy,
          version: mcVersion,
          serverNames: SERVER_NAMES,
          directPort,
          directBackend: direct,
          backends: behind,
          fleet,
          proxy,
          heap: () => liveHeapMiB(proxy.jcmd, proxy.pid),
          rows: () => activeSampler.rows,
          now: () => Math.round((Date.now() - origin) / 100) / 10,
          state: (next) => Object.assign(state, next),
        };
        result.metrics[name] = await runScenario(name, ctx, profile[name]);
      } catch (e) {
        result.errors.push(`${name}: ${e.message}`);
      } finally {
        await fleet.drain().catch(() => {});
        if (sampler) {
          await sampler.stop();
          rows.push(...sampler.rows);
        }
        result.proxyFailures.push(...(proxy.process?.failures?.() ?? []));
        await proxy.stop().catch(() => {});
        await sleep(1_000);
      }
    }
  } catch (e) {
    result.errors.push(`setup: ${e.message}`);
  } finally {
    await Promise.allSettled([...paper, direct, stub].filter(Boolean).map((b) => b.stop()));
    await sleep(500);
  }

  result.rows = rows;
  result.csv = toCsv(rows);
  return result;
}

function runScenario(name, ctx, p) {
  switch (name) {
    case 'idle':
      return idleScenario(ctx, p);
    case 'traffic':
      return trafficScenario(ctx, p);
    case 'latency':
      return latencyScenario(ctx, p);
    case 'switch':
      return switchScenario(ctx, p);
    case 'storm':
      return stormScenario(ctx, p);
    default:
      throw new Error(`unknown scenario: ${name}`);
  }
}

export { SERVER_NAMES, findJava };
