// Warp end-to-end runner. See `e2e/run.sh --help`.
//
// For each requested Minecraft version: download the pinned server, boot two backends (lobby and
// survival), then for each variant (online/offline, passthrough, thresholds) boot Warp, run the
// scenarios with real-protocol bots, and check Warp's log for errors and buffer leaks. Backends are
// shared by every variant that uses the same compression threshold.
import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, cpSync } from 'node:fs';
import { createServer } from 'node:net';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';

import { Backend, fetchPreseeded, fetchServerJar, serverCacheDir } from './backend.js';
import { connectClient } from './clients/index.js';
import { findJava } from './java.js';
import { killAll, run, sleep } from './proc.js';
import { SCENARIOS, features, startKeepAlive } from './scenarios.js';
import { startSessionServer } from './session.js';
import { E2E_DIR, findEntry, loadMatrix, resolveVariant } from './versions.js';
import { Warp } from './warp.js';

const ROOT = resolve(E2E_DIR, '..');
const GITHUB = process.env.GITHUB_ACTIONS === 'true';

const HELP = `Usage: e2e/run.sh --mc <version>[,<version>…] [options]

Runs Warp between real-protocol bots and real Minecraft servers. Exits non-zero if a scenario
fails, if Warp logs an ERROR or leaks a buffer, or if Warp does not shut down cleanly.

Versions and variants
  --mc LIST               Minecraft versions (or protocol numbers) from e2e/versions.json
  --variants LIST         named variants from versions.json (default: online)
  --runs SPEC             per-version variants, e.g. "1.8.8=online,offline;1.12.2=online"
  --online                ad-hoc variant: online mode against a mock session server
  --passthrough on|off    ad-hoc variant: compression passthrough (default on)
  --threshold N           ad-hoc variant: Warp's compression threshold (default 256)
  --backend-threshold N   ad-hoc variant: backends' network-compression-threshold (default: Warp's)
  --scenarios LIST        subset of: ${SCENARIOS.map((s) => s.name).join(', ')}

Environment
  --jar PATH              Warp shadow jar (default: build it with Gradle)
  --bots N                bots in the crowd scenario (default 10)
  --port-base N           first of the 10 local ports used (default 26100)
  --out DIR               run directory (default e2e/build)
  --cache DIR             download cache (default $WARP_E2E_CACHE or ~/.cache/warp-e2e)
  --strict                treat known-broken versions like any other (fail on failure)
  --direct                control run: bots connect straight to the lobby, without Warp
  --list                  print the version matrix and exit
`;

async function main() {
  const { values: opts } = parseArgs({
    options: {
      mc: { type: 'string' },
      variants: { type: 'string' },
      runs: { type: 'string' },
      online: { type: 'boolean' },
      passthrough: { type: 'string' },
      threshold: { type: 'string' },
      'backend-threshold': { type: 'string' },
      scenarios: { type: 'string' },
      jar: { type: 'string' },
      bots: { type: 'string', default: '10' },
      'port-base': { type: 'string', default: '26100' },
      out: { type: 'string', default: join(E2E_DIR, 'build') },
      cache: { type: 'string', default: process.env.WARP_E2E_CACHE ?? join(process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'), 'warp-e2e') },
      strict: { type: 'boolean', default: false },
      direct: { type: 'boolean', default: false },
      list: { type: 'boolean' },
      help: { type: 'boolean', short: 'h' },
    },
  });
  const matrix = loadMatrix();
  if (opts.help) return void process.stdout.write(HELP);
  if (opts.list) return void printMatrix(matrix);
  if (!opts.mc && !opts.runs) throw new UsageError('--mc is required');

  // --runs "1.8.8=online,offline;1.12.2=online" gives each version its own variants (CI shards).
  const runs = opts.runs
    ? opts.runs.split(';').map((run) => {
        const [key, names] = run.split('=');
        return { entry: findEntry(matrix, key.trim()), variants: names.split(',').map((n) => resolveVariant(matrix, n.trim())) };
      })
    : opts.mc.split(',').map((key) => ({ entry: findEntry(matrix, key.trim()), variants: resolveVariants(matrix, opts) }));
  const scenarios = selectScenarios(opts.scenarios);
  const ports = await reservePorts(Number(opts['port-base']));
  const jar = opts.jar ? resolve(opts.jar) : await buildWarp();
  mkdirSync(opts.out, { recursive: true });

  const results = [];
  for (const { entry, variants } of runs) {
    results.push(await runVersion({ matrix, entry, variants: adaptVariants(entry, variants), scenarios, ports, jar, opts }));
  }
  report(results, opts);
  return results.some((r) => r.status === 'fail') ? 1 : 0;
}

// ---------------------------------------------------------------------------
// One version: backends per threshold, Warp per variant
// ---------------------------------------------------------------------------

async function runVersion({ matrix, entry, variants, scenarios, ports, jar, opts }) {
  const cache = opts.cache;
  const runDir = join(opts.out, entry.version);
  rmSync(runDir, { recursive: true, force: true });
  const logDir = join(runDir, 'logs');
  mkdirSync(logDir, { recursive: true });
  const started = Date.now();
  const result = { version: entry.version, protocol: entry.protocol, server: describeServer(entry.server), knownBroken: entry.knownBroken ?? null, variants: [] };
  const knownBroken = Boolean(entry.knownBroken) && !opts.strict;

  group(`Minecraft ${entry.version} (protocol ${entry.protocol}) on ${result.server}`);
  try {
    const [serverJava, warpJava, serverJar, preseeded] = await Promise.all([
      findJava(entry.java, cache),
      findJava(25, cache),
      fetchServerJar(entry.server, cache),
      fetchPreseeded(entry.server, cache),
    ]);
    const thresholds = [...new Set(variants.map((v) => v.backendThreshold))];
    for (const backendThreshold of thresholds) {
      const backends = await startBackends({ entry, serverJar, serverJava, preseeded, backendThreshold, ports, runDir, logDir, cache });
      try {
        for (const variant of variants.filter((v) => v.backendThreshold === backendThreshold)) {
          result.variants.push(await runVariant({ matrix, entry, variant, scenarios, backends, ports, jar, warpJava, runDir, logDir, opts }));
        }
      } finally {
        await stopBackends(backends, result);
      }
    }
  } catch (e) {
    result.variants.push({ name: 'setup', status: 'fail', scenarios: [], failures: [String(e.stack ?? e)] });
  }
  result.seconds = Math.round((Date.now() - started) / 1000);
  result.status = classify(result.variants.every((v) => v.status === 'pass'), knownBroken);
  writeFileSync(join(runDir, 'result.json'), `${JSON.stringify(result, null, 2)}\n`);
  endGroup();
  return result;
}

async function startBackends({ entry, serverJar, serverJava, preseeded, backendThreshold, ports, runDir, logDir, cache }) {
  const template = join(serverCacheDir(entry.server, cache), 'template');
  const backends = ['lobby', 'survival'].map(
    (name, i) =>
      new Backend({
        name,
        dir: join(runDir, name),
        server: entry.server,
        jar: serverJar,
        java: serverJava,
        port: ports[name],
        compressionThreshold: backendThreshold,
        gameMode: i === 0 ? 1 : 2,
        logDir,
        logSuffix: `t${backendThreshold}`,
      }),
  );
  log(`booting backends (${entry.server.type} ${entry.server.version}, Java ${entry.java}, threshold ${backendThreshold})`);
  const booted = Date.now();
  for (const b of backends) {
    b.prepare(template, preseeded);
    b.start();
  }
  await Promise.all(backends.map((b) => b.ready(240_000)));
  log(`backends ready in ${((Date.now() - booted) / 1000).toFixed(1)} s`);
  saveTemplate(backends[0].dir, template);
  return { lobby: backends[0], survival: backends[1] };
}

/** Keeps what the server downloaded or patched on first start, so later runs skip it. */
function saveTemplate(serverDir, template) {
  if (existsSync(template)) return;
  for (const dir of ['cache', 'libraries', 'versions', 'bundler']) {
    if (existsSync(join(serverDir, dir))) cpSync(join(serverDir, dir), join(`${template}.tmp`, dir), { recursive: true });
  }
  if (existsSync(`${template}.tmp`)) cpSync(`${template}.tmp`, template, { recursive: true });
  rmSync(`${template}.tmp`, { recursive: true, force: true });
}

async function stopBackends(backends, result) {
  const exits = await Promise.all(Object.values(backends).map((b) => b.stop()));
  const forced = Object.values(backends).filter((b, i) => exits[i]?.forced).map((b) => b.name);
  if (forced.length) log(`backend(s) ${forced.join(', ')} ignored "stop" and were killed`);
}

async function runVariant({ matrix, entry, variant, scenarios, backends, ports, jar, warpJava, runDir, logDir, opts }) {
  const label = `${entry.version} ${variant.name}`;
  log(`variant ${variant.name}: ${describeVariant(variant)}`);
  const outcome = { name: variant.name, settings: describeVariant(variant), scenarios: [], failures: [], stacks: [] };
  const marks = { lobby: backends.lobby.process.mark(), survival: backends.survival.process.mark() };
  const session = variant.online ? await startSessionServer(ports.session) : null;
  const common = {
    jar,
    java: warpJava,
    online: variant.online,
    sessionServer: session?.url ?? null,
    passthrough: variant.passthrough,
    threshold: variant.threshold,
    logDir,
  };
  // --direct: control run without Warp, to tell a proxy bug from a client or server quirk.
  const instances = opts.direct
    ? []
    : [
        new Warp({
          ...common,
          name: `warp-${variant.name}`,
          dir: join(runDir, `warp-${variant.name}`),
          port: ports.warp,
          servers: { lobby: ports.lobby, survival: ports.survival },
          fallbackOrder: ['lobby', 'survival'],
        }),
        // Same backends, but the default server is a closed port: every join must fall back.
        new Warp({
          ...common,
          name: `warp-alt-${variant.name}`,
          dir: join(runDir, `warp-alt-${variant.name}`),
          port: ports.warpAlt,
          servers: { unreachable: ports.closed, survival: ports.survival },
          fallbackOrder: ['unreachable', 'survival'],
        }),
      ];
  const targets = opts.direct ? { warp: ports.lobby } : { warp: ports.warp, warpAlt: ports.warpAlt };
  const ctx = {
    entry,
    variant,
    direct: opts.direct,
    features: opts.direct ? { proxy: false, proxyCommands: false, switching: false } : { proxy: true, ...features(entry.protocol) },
    client: connectClient(entry, { tools: matrix.tools, cache: opts.cache, java: warpJava, ports, targets: Object.values(targets), logDir, runDir }),
    warp: { port: targets.warp },
    warpAlt: { port: targets.warpAlt },
    lobby: backends.lobby,
    survival: backends.survival,
    bots: Number(opts.bots),
  };
  try {
    for (const instance of instances) instance.prepare();
    await Promise.all(instances.map((instance) => instance.start()));
    await ctx.client.start?.();

    let finishKeepAlive = null;
    if (scenarios.some((s) => s.name === 'keepalive')) {
      finishKeepAlive = await startKeepAlive(ctx).catch((e) => {
        outcome.scenarios.push(fail('keepalive', Date.now(), e));
        return null;
      });
    }
    for (const scenario of scenarios.filter((s) => !s.background)) {
      outcome.scenarios.push(await runScenario(scenario, ctx));
    }
    if (finishKeepAlive) {
      const t = Date.now();
      outcome.scenarios.push(await finishKeepAlive().then((detail) => pass('keepalive', t, detail), (e) => fail('keepalive', t, e)));
    }

    // Leaked buffers are only reported once collected and Netty allocates again.
    if (instances.length) {
      await instances[0].collectGarbage();
      await ctx.client.ping({ host: '127.0.0.1', port: ctx.warp.port }).catch(() => {});
      await sleep(1_000);
    }
  } catch (e) {
    outcome.failures.push(`setup: ${firstLine(e)}`);
    outcome.stacks.push(String(e?.stack ?? e));
  } finally {
    await ctx.client.stop?.();
    for (const line of ctx.client.failures?.() ?? []) outcome.failures.push(line);
    for (const instance of instances) {
      const exit = await instance.stop();
      if (exit?.forced) outcome.failures.push(`${instance.name} did not shut down within 20 s (thread dump in logs/)`);
      for (const line of instance.failures()) outcome.failures.push(`${instance.name}: ${line}`);
    }
    await session?.close();
  }
  for (const [name, backend] of Object.entries(backends)) {
    for (const line of backend.protocolErrors(marks[name])) outcome.failures.push(`${name}: ${line}`);
  }
  const passed = outcome.scenarios.every((s) => s.status !== 'fail') && outcome.failures.length === 0;
  outcome.status = passed ? 'pass' : 'fail';
  for (const failure of outcome.failures) log(`  ✗ ${failure.split('\n')[0]}`);
  if (!passed && GITHUB && !entry.knownBroken) {
    const first = outcome.scenarios.find((s) => s.status === 'fail')?.detail ?? outcome.failures[0] ?? '';
    console.log(`::error title=E2E ${label}::${first.split('\n')[0]}`);
  }
  return outcome;
}

async function runScenario(scenario, ctx) {
  if (scenario.requires && !ctx.features[scenario.requires]) {
    const result = { name: scenario.name, status: 'skip', seconds: 0, detail: `needs ${scenario.requires} (not in protocol ${ctx.entry.protocol})` };
    log(`  SKIP ${scenario.name}: ${result.detail}`);
    return result;
  }
  const started = Date.now();
  try {
    return pass(scenario.name, started, await scenario.run(ctx));
  } catch (e) {
    return fail(scenario.name, started, e);
  } finally {
    await sleep(500);
  }
}

function pass(name, started, detail) {
  const result = { name, status: 'pass', seconds: secondsSince(started), detail };
  log(`  PASS ${name} (${result.seconds}s): ${detail}`);
  return result;
}

function fail(name, started, error) {
  const result = { name, status: 'fail', seconds: secondsSince(started), detail: firstLine(error), stack: String(error?.stack ?? error) };
  log(`  FAIL ${name} (${result.seconds}s): ${result.detail}`);
  return result;
}

// ---------------------------------------------------------------------------
// Options
// ---------------------------------------------------------------------------

class UsageError extends Error {}

function resolveVariants(matrix, opts) {
  const adHoc = {};
  if (opts.online) adHoc.online = true;
  if (opts.passthrough) {
    if (!['on', 'off'].includes(opts.passthrough)) throw new UsageError('--passthrough takes on or off');
    adHoc.passthrough = opts.passthrough === 'on';
  }
  if (opts.threshold) adHoc.threshold = Number(opts.threshold);
  if (opts['backend-threshold']) adHoc.backendThreshold = Number(opts['backend-threshold']);
  if (Object.keys(adHoc).length && opts.variants) throw new UsageError('use either --variants or ad-hoc flags, not both');
  if (Object.keys(adHoc).length) {
    const name = [adHoc.online ? 'online' : 'offline', adHoc.passthrough === false ? 'transcode' : null, adHoc.backendThreshold !== undefined ? `backend${adHoc.backendThreshold}` : null]
      .filter(Boolean)
      .join('-');
    return [{ ...resolveVariant(matrix, 'offline', adHoc), name }];
  }
  return (opts.variants ?? 'online').split(',').map((name) => resolveVariant(matrix, name.trim()));
}

/**
 * ViaProxy cannot authenticate against an online-mode server without a real Microsoft account, so
 * bridged versions run their online variants offline (said so in the variant's name).
 */
function adaptVariants(entry, variants) {
  if (!entry.via) return variants;
  const adapted = variants.map((v) => (v.online ? { ...v, online: false, name: v.name === 'online' ? 'offline' : `${v.name}-offline` } : v));
  const unique = [...new Map(adapted.map((v) => [v.name, v])).values()];
  if (variants.some((v) => v.online)) log(`${entry.version} is bridged through ViaProxy: online variants run offline`);
  return unique;
}

function selectScenarios(list) {
  if (!list) return SCENARIOS;
  const names = list.split(',').map((s) => s.trim());
  for (const name of names) {
    if (!SCENARIOS.some((s) => s.name === name)) throw new UsageError(`unknown scenario "${name}"`);
  }
  return SCENARIOS.filter((s) => names.includes(s.name));
}

/** Fixed ports from `base`; fails early if one is taken (a dev testbed, another run…). */
async function reservePorts(base) {
  const ports = { lobby: base, survival: base + 1, warp: base + 2, warpAlt: base + 3, session: base + 4, closed: base + 5, via: base + 6, viaAlt: base + 7 };
  for (const [name, port] of Object.entries(ports)) {
    const free = await new Promise((done) => {
      const probe = createServer().once('error', () => done(false)).listen(port, '127.0.0.1', () => probe.close(() => done(true)));
    });
    if (!free) throw new UsageError(`port ${port} (${name}) is in use: pick other ports with --port-base`);
  }
  return ports;
}

async function buildWarp() {
  log('building Warp (./gradlew :proxy:shadowJar)');
  await run(join(ROOT, 'gradlew'), ['--quiet', ':proxy:shadowJar'], { cwd: ROOT });
  const version = readFileSync(join(ROOT, 'version.txt'), 'utf8').trim();
  return join(ROOT, 'proxy', 'build', 'libs', `warp-${version}.jar`);
}

// ---------------------------------------------------------------------------
// Reporting
// ---------------------------------------------------------------------------

function classify(passed, knownBroken) {
  if (knownBroken) return passed ? 'xpass' : 'xfail';
  return passed ? 'pass' : 'fail';
}

const ICONS = { pass: '✅', fail: '❌', xfail: '⚠️ known broken', xpass: '🎉 fixed?', skip: '⏭️' };

function report(results, opts) {
  console.log('\nSummary');
  for (const r of results) {
    console.log(`  ${r.status.toUpperCase().padEnd(5)} ${r.version.padEnd(8)} ${r.seconds}s  ${r.variants.map((v) => `${v.name}:${v.status}`).join(' ')}`);
    if (r.status === 'xpass' && GITHUB) {
      console.log(`::warning title=E2E ${r.version} passes::${r.version} is listed as known broken but passed: remove it from the list in e2e/versions.json`);
    }
    if (r.status === 'xfail' && GITHUB) console.log(`::notice title=E2E ${r.version} known broken::${r.knownBroken}`);
  }
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown(results));
  writeFileSync(join(opts.out, 'summary.md'), markdown(results));
}

export function markdown(results) {
  const rows = results.flatMap((r) =>
    r.variants.map((v) => {
      const cells = v.scenarios.map((s) => `${s.status === 'pass' ? '✅' : s.status === 'skip' ? '⏭️' : '❌'} ${s.name}`).join(' · ');
      const status = r.knownBroken && v.status === 'fail' ? 'xfail' : r.knownBroken ? 'xpass' : v.status;
      return `| ${r.version} | ${r.protocol} | ${v.name} | ${ICONS[status]} | ${cells}${v.failures?.length ? ` · ❌ ${v.failures.length} log failure(s)` : ''} | ${r.seconds}s |`;
    }),
  );
  return `| Version | Protocol | Variant | Result | Scenarios | Time |\n|---|---|---|---|---|---|\n${rows.join('\n')}\n`;
}

function printMatrix(matrix) {
  for (const v of matrix.versions) {
    const client = v.via ? `mineflayer ${v.via} → ViaProxy` : 'mineflayer';
    console.log(`${String(v.protocol).padStart(4)}  ${v.version.padEnd(8)} ${describeServer(v.server).padEnd(24)} Java ${String(v.java).padEnd(3)} ${client}${v.knownBroken ? `  [known broken: ${v.knownBroken}]` : ''}`);
  }
}

function describeServer(server) {
  return `${server.type} ${server.version}${server.build ? ` #${server.build}` : ''}`;
}

function describeVariant(v) {
  return `${v.online ? 'online' : 'offline'}, passthrough ${v.passthrough ? 'on' : 'off'}, threshold warp=${v.threshold} backend=${v.backendThreshold}`;
}

const firstLine = (e) => String(e?.message ?? e).split('\n')[0];
const secondsSince = (t) => Math.round((Date.now() - t) / 100) / 10;
const log = (message) => console.log(`[${new Date().toISOString().slice(11, 19)}] ${message}`);
const group = (title) => console.log(GITHUB ? `::group::${title}` : `\n=== ${title}`);
const endGroup = () => GITHUB && console.log('::endgroup::');

// ---------------------------------------------------------------------------

let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (interrupted) process.exit(130);
    interrupted = true;
    console.error(`\n${signal}: stopping servers…`);
    killAll('SIGTERM');
    setTimeout(() => { killAll('SIGKILL'); process.exit(130); }, 15_000).unref();
  });
}

main().then(
  (code) => { killAll(); process.exit(code ?? 0); },
  (e) => {
    console.error(e instanceof UsageError ? `error: ${e.message}\n\n${HELP}` : e.stack ?? e);
    killAll();
    process.exit(2);
  },
);
