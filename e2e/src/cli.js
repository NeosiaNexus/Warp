// Warp end-to-end runner. See `e2e/run.sh --help`.
//
// For each requested Minecraft version: download the pinned server, boot two backends (lobby and
// survival), then for each variant (online/offline, passthrough, thresholds, forwarding) boot Warp,
// run the scenarios with real-protocol bots, and check Warp's log for errors and buffer leaks.
// Backends are shared by every variant that uses the same compression threshold and forwarding.
import { randomBytes } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, cpSync } from 'node:fs';
import { createServer } from 'node:net';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';

import { Backend, backendGroups, fetchPreseeded, fetchServerJar, serverCacheDir } from './backend.js';
import { connectClient } from './clients/index.js';
import { findJava } from './java.js';
import { startPackServer } from './packs.js';
import { killAll, run, sleep } from './proc.js';
import { SCENARIOS, features, startKeepAlive } from './scenarios.js';
import { startSessionServer } from './session.js';
import { variantStatus, versionStatus } from './status.js';
import { E2E_DIR, FORWARDING_MODES, findEntry, loadMatrix, resolveVariant } from './versions.js';
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
  --forwarding MODE       ad-hoc variant: player info forwarding, ${FORWARDING_MODES.join(' or ')} (default none)
  --scenarios LIST        subset of: ${SCENARIOS.map((s) => s.name).join(', ')}

Environment
  --jar PATH              Warp shadow jar (default: build it with Gradle)
  --bots N                bots in the crowd scenario (default 10)
  --port-base N           first of the 8 local ports used (default 26100)
  --out DIR               run directory (default e2e/build)
  --cache DIR             download cache (default $WARP_E2E_CACHE or ~/.cache/warp-e2e)
  --strict                treat known-broken versions and scenarios like any other
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
      forwarding: { type: 'string' },
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
  const packServer = await startPackServer(['lobby', 'survival']);
  try {
    for (const { entry, variants } of runs) {
      results.push(await runVersion({ matrix, entry, variants: adaptVariants(entry, variants), scenarios, ports, packs: packServer.packs, jar, opts }));
    }
  } finally {
    await packServer.close();
  }
  report(results, opts);
  return results.some((r) => r.status === 'fail') ? 1 : 0;
}

// ---------------------------------------------------------------------------
// One version: backends per threshold and forwarding, Warp per variant
// ---------------------------------------------------------------------------

async function runVersion({ matrix, entry, variants, scenarios, ports, packs, jar, opts }) {
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
    for (const group of backendGroups(entry, variants, { direct: opts.direct })) {
      if (group.skip) {
        for (const variant of group.variants) result.variants.push(skipVariant(variant, group.skip));
        continue;
      }
      // A fresh secret for each boot of forwarding backends, shared with the Warp instances.
      const forwarding = group.forwarding && { ...group.forwarding, secret: randomBytes(16).toString('hex') };
      const backends = await startBackends({ entry, serverJar, serverJava, preseeded, threshold: group.threshold, forwarding, packs, ports, runDir, logDir, cache });
      try {
        for (const variant of group.variants) {
          result.variants.push(await runVariant({ matrix, entry, variant, scenarios, backends, forwarding, packs, ports, jar, warpJava, runDir, logDir, opts }));
        }
      } finally {
        await stopBackends(backends, result);
      }
    }
  } catch (e) {
    result.variants.push({ name: 'setup', status: 'fail', scenarios: [], failures: [String(e.stack ?? e)] });
  }
  result.seconds = Math.round((Date.now() - started) / 1000);
  const ran = result.variants.filter((v) => v.status !== 'skip');
  result.status = ran.length ? versionStatus(ran.every((v) => v.status === 'pass'), knownBroken) : 'skip';
  writeFileSync(join(runDir, 'result.json'), `${JSON.stringify(result, null, 2)}\n`);
  endGroup();
  return result;
}

async function startBackends({ entry, serverJar, serverJava, preseeded, threshold, forwarding, packs, ports, runDir, logDir, cache }) {
  const template = join(serverCacheDir(entry.server, cache), 'template');
  const backends = ['lobby', 'survival'].map(
    (name, i) =>
      new Backend({
        name,
        dir: join(runDir, name),
        server: entry.server,
        protocol: entry.protocol,
        jar: serverJar,
        java: serverJava,
        port: ports[name],
        compressionThreshold: threshold,
        gameMode: i === 0 ? 1 : 2,
        resourcePack: packs[name],
        forwarding,
        logDir,
        logSuffix: `t${threshold}${forwarding ? `-${forwarding.mode}-${forwarding.online ? 'online' : 'offline'}` : ''}`,
      }),
  );
  const accepting = forwarding ? `, ${forwarding.mode} forwarding from an ${forwarding.online ? 'online' : 'offline'} proxy` : '';
  log(`booting backends (${entry.server.type} ${entry.server.version}, Java ${entry.java}, threshold ${threshold}${accepting})`);
  const booted = Date.now();
  const hung = (b) => (e) => {
    log(`${b.name} hung while booting: its threads go to its log, and it boots again (#97). ${firstLine(e)}`);
    if (GITHUB) console.log(`::warning title=E2E ${entry.version} ${b.name} hung while booting::booted again; its thread dump is in the logs (#97)`);
  };
  await Promise.all(backends.map((b) => b.boot(template, preseeded, 240_000, hung(b))));
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

/** The outcome of a variant the version cannot run, and why. */
function skipVariant(variant, reason) {
  log(`variant ${variant.name}: skipped, ${reason}`);
  return { name: variant.name, settings: describeVariant(variant), status: 'skip', reason, scenarios: [], failures: [] };
}

async function runVariant({ matrix, entry, variant, scenarios, backends, forwarding, packs, ports, jar, warpJava, runDir, logDir, opts }) {
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
    forwarding,
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
    features: features(entry.protocol, { direct: opts.direct, forwarding: variant.forwarding, online: variant.online }),
    client: connectClient(entry, { tools: matrix.tools, cache: opts.cache, java: warpJava, ports, targets: Object.values(targets), logDir, runDir }),
    warp: { port: targets.warp },
    warpAlt: { port: targets.warpAlt },
    lobby: backends.lobby,
    survival: backends.survival,
    packs,
    bots: Number(opts.bots),
    // Scenarios that fail on this version because of a known Warp bug: they run and are reported,
    // but do not fail the version. Not in a control run (no Warp) or with --strict.
    knownBrokenScenarios: opts.direct || opts.strict ? {} : (entry.knownBrokenScenarios ?? {}),
    // Every process whose log can fail the variant.
    processes: () => [...instances, backends.lobby, backends.survival, ...(ctx.client.bridges?.() ?? [])].map((x) => x.process).filter(Boolean),
  };
  try {
    for (const instance of instances) instance.prepare();
    await Promise.all(instances.map((instance) => instance.start()));
    await ctx.client.start?.();

    let finishKeepAlive = null;
    if (scenarios.some((s) => s.name === 'keepalive')) {
      const joined = await attempt('keepalive', async () => { finishKeepAlive = await startKeepAlive(ctx); });
      if (joined.status === 'fail') outcome.scenarios.push(record(joined));
    }
    for (const scenario of scenarios.filter((s) => !s.background)) {
      outcome.scenarios.push(await runScenario(scenario, ctx));
    }
    if (finishKeepAlive) outcome.scenarios.push(record(await attempt('keepalive', finishKeepAlive)));

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
  // Known-broken scenarios (xfail, xpass) do not decide the outcome.
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
  // `true`, or why this run lacks the feature the scenario needs.
  const support = scenario.requires ? ctx.features[scenario.requires] : true;
  if (support !== true) return record({ name: scenario.name, status: 'skip', seconds: 0, detail: support });
  const knownBroken = ctx.knownBrokenScenarios[scenario.name];
  const marks = knownBroken ? ctx.processes().map((p) => [p, p.mark()]) : [];
  const result = await attempt(scenario.name, () => scenario.run(ctx));
  await sleep(500);
  if (knownBroken) {
    // What the logs reported while it ran is part of the known failure: it goes with the scenario
    // instead of failing the variant. A scenario that passes with nothing logged is fixed.
    const excused = marks.flatMap(([p, from]) => p.excuse(from).map((line) => `${p.name}: ${line}`));
    Object.assign(result, { status: result.status === 'pass' && !excused.length ? 'xpass' : 'xfail', knownBroken, excused });
  }
  return record(result);
}

/** Runs a scenario step: a pass is detailed by what `body` returns, a fail by what it throws. */
async function attempt(name, body) {
  const started = Date.now();
  try {
    const detail = await body();
    return { name, status: 'pass', seconds: secondsSince(started), detail };
  } catch (e) {
    return { name, status: 'fail', seconds: secondsSince(started), detail: firstLine(e), stack: String(e?.stack ?? e) };
  }
}

/** Logs a scenario result (PASS, FAIL, SKIP, or XFAIL and XPASS for a known-broken one). */
function record(result) {
  const time = result.status === 'skip' ? '' : ` (${result.seconds}s)`;
  const excused = result.excused?.length ? `, ${result.excused.length} log failure(s) excused` : '';
  const known = result.knownBroken ? ` [known broken: ${result.knownBroken}${excused}]` : '';
  log(`  ${result.status.toUpperCase()} ${result.name}${time}: ${result.detail}${known}`);
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
  if (opts.forwarding) {
    if (!FORWARDING_MODES.includes(opts.forwarding)) throw new UsageError(`--forwarding takes ${FORWARDING_MODES.join(' or ')}`);
    adHoc.forwarding = opts.forwarding;
  }
  if (Object.keys(adHoc).length && opts.variants) throw new UsageError('use either --variants or ad-hoc flags, not both');
  if (Object.keys(adHoc).length) {
    const name = [
      adHoc.online ? 'online' : 'offline',
      adHoc.passthrough === false ? 'transcode' : null,
      adHoc.backendThreshold !== undefined ? `backend${adHoc.backendThreshold}` : null,
      adHoc.forwarding !== 'none' ? adHoc.forwarding : null,
    ]
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

const ICONS = { pass: '✅', fail: '❌', xfail: '⚠️ known broken', xpass: '🎉 fixed?', skip: '⏭️' };
const SCENARIO_ICONS = { pass: '✅', fail: '❌', xfail: '⚠️', xpass: '🎉', skip: '⏭️' };

function report(results, opts) {
  console.log('\nSummary');
  for (const r of results) {
    console.log(`  ${r.status.toUpperCase().padEnd(5)} ${r.version.padEnd(8)} ${r.seconds}s  ${r.variants.map((v) => `${v.name}:${v.status}`).join(' ')}`);
    if (!GITHUB) continue;
    if (r.status === 'xpass') {
      console.log(`::warning title=E2E ${r.version} passes::${r.version} is listed as known broken but passed: remove it from the list in e2e/versions.json`);
    }
    if (r.status === 'xfail') console.log(`::notice title=E2E ${r.version} known broken::${r.knownBroken}`);
    const fixed = new Set(r.variants.flatMap((v) => v.scenarios.filter((s) => s.status === 'xpass').map((s) => s.name)));
    for (const name of fixed) {
      console.log(`::warning title=E2E ${r.version} ${name} passes::${name} is listed as known broken on ${r.version} but passed: remove it from knownBrokenScenarios in e2e/versions.json`);
    }
  }
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown(results));
  writeFileSync(join(opts.out, 'summary.md'), markdown(results));
}

function markdown(results) {
  const rows = results.flatMap((r) =>
    r.variants.map((v) => {
      const cells = v.status === 'skip' ? v.reason : v.scenarios.map((s) => `${SCENARIO_ICONS[s.status]} ${s.name}`).join(' · ');
      return `| ${r.version} | ${r.protocol} | ${v.name} | ${ICONS[variantStatus(r, v)]} | ${cells}${v.failures?.length ? ` · ❌ ${v.failures.length} log failure(s)` : ''} | ${r.seconds}s |`;
    }),
  );
  return `| Version | Protocol | Variant | Result | Scenarios | Time |\n|---|---|---|---|---|---|\n${rows.join('\n')}\n`;
}

function printMatrix(matrix) {
  for (const v of matrix.versions) {
    const client = v.via ? `mineflayer ${v.via} → ViaProxy` : 'mineflayer';
    const broken = v.knownBroken ?? (v.knownBrokenScenarios ? Object.keys(v.knownBrokenScenarios).join(', ') : null);
    console.log(`${String(v.protocol).padStart(4)}  ${v.version.padEnd(8)} ${describeServer(v.server).padEnd(24)} Java ${String(v.java).padEnd(3)} ${client}${broken ? `  [known broken: ${broken}]` : ''}`);
  }
}

function describeServer(server) {
  return `${server.type} ${server.version}${server.build ? ` #${server.build}` : ''}`;
}

function describeVariant(v) {
  const forwarding = v.forwarding === 'none' ? '' : `, ${v.forwarding} forwarding`;
  return `${v.online ? 'online' : 'offline'}, passthrough ${v.passthrough ? 'on' : 'off'}, threshold warp=${v.threshold} backend=${v.backendThreshold}${forwarding}`;
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
