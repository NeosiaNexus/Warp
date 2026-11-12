// Entry point for the macro benchmark. Resolves options, runs each proxy end to end in a shuffled
// order (so thermal drift does not favour whoever goes first), writes the raw per-second CSVs, the
// configs each proxy actually used, the host fingerprint and the Markdown report under
// bench/results/<date>/. One command, reproducible: see docs/benchmarks/README.md.
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { cpus as osCpus } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { cpuPlan, fingerprint } from './env.js';
import { Fleet, killFleets } from './fleet.js';
import { FULL, SMOKE, ALL_SCENARIOS, BACKEND_HEAP_MB, applyOverrides } from './profiles.js';
import { killAll } from './reuse.js';
import { runProxy } from './run.js';
import { saveResult, saveRun, writeReport } from './store.js';
import { THRESHOLDS } from './validity.js';
import { harnessFailures, invalidResults } from './verdict.js';
import { PROXY_IDS } from './proxies/index.js';

const BENCH_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_CACHE = process.env.WARP_BENCH_CACHE ?? join(process.env.HOME ?? '/tmp', '.cache', 'warp-bench');

function parseArgs(argv) {
  const opts = {
    smoke: false,
    proxies: null,
    scenarios: null,
    mc: '1.21.4',
    warpJar: null,
    pin: true,
    loadHost: null,
    out: null,
    proxyPort: 25565,
    backendBase: 25700,
    set: [],
    reportOnly: null,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--smoke': opts.smoke = true; break;
      case '--proxies': opts.proxies = next().split(',').map((s) => s.trim()); break;
      case '--scenarios': opts.scenarios = next().split(',').map((s) => s.trim()); break;
      case '--mc': opts.mc = next(); break;
      case '--warp-jar': opts.warpJar = next(); break;
      case '--no-pin': opts.pin = false; break;
      case '--pin': opts.pin = true; break;
      case '--load-host': opts.loadHost = next(); break;
      case '--out': opts.out = next(); break;
      case '--set': opts.set.push(next()); break;
      case '--report-only': opts.reportOnly = next(); break;
      case '--proxy-port': opts.proxyPort = Number(next()); break;
      case '--backend-base': opts.backendBase = Number(next()); break;
      case '--help': case '-h': opts.help = true; break;
      default: throw new Error(`unknown argument: ${a}`);
    }
  }
  return opts;
}

const HELP = `Warp macro benchmark

Usage: bench/run.sh [options]

  --smoke                 Two-minute sanity run: one proxy, ~50 bots. Use this first and in CI.
  --proxies a,b,c         Which proxies to run (default: warp,velocity,gate,gatelite,bungeecord;
                          with --smoke, default: warp). Ids: ${PROXY_IDS.join(', ')}.
  --scenarios a,b         Which scenarios (default: ${ALL_SCENARIOS.join(',')}).
  --mc 1.21.4             Minecraft version the bots speak (must match the backend).
  --warp-jar path.jar     Use a local Warp jar instead of the pinned release.
  --no-pin                Do not pin proxy/backends/bots to separate cores (taskset).
  --load-host host        Reserved: run the bots from another machine (single-machine is the default).
  --out dir               Where to write results (default: bench/results/<date>).
  --set scenario.key=v    Override one size of the profile, e.g. --set idle.levels=1000,2000
                          --set traffic.bots=150 (repeatable; recorded in the report).
  --report-only dir       Rebuild README.md from the files of an earlier run and exit.
  --help                  This text.

Every download is checked against the sha256 in bench/versions.json. Results, raw CSVs, the exact
configs and the host fingerprint are written under the results directory.`;

/** The harness commit, or "unknown" when this is not a git checkout (an archive, a tarball). */
function gitSha() {
  try {
    return execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: BENCH_DIR, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim() || 'unknown';
  } catch {
    return 'unknown';
  }
}

/** Moves this process (the orchestrator and its sampler) onto the load cores, off the proxy's. */
function pinSelf(cpus) {
  try {
    execFileSync('taskset', ['-cp', cpus.join(','), String(process.pid)], { stdio: 'ignore' });
  } catch {
    console.error('note: could not pin the harness itself (taskset failed); it floats over all cores.');
  }
}

function shuffle(array) {
  const a = [...array];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(HELP);
    return 0;
  }
  if (opts.loadHost) {
    console.log(`note: --load-host ${opts.loadHost} is reserved; this run uses single-machine mode.`);
  }

  if (opts.reportOnly) {
    const found = writeReport(opts.reportOnly);
    console.log(`Report rebuilt from ${found.length} proxies: ${join(opts.reportOnly, 'README.md')}`);
    return 0;
  }

  const versions = JSON.parse(readFileSync(join(BENCH_DIR, 'versions.json'), 'utf8'));
  const profileName = opts.smoke ? 'smoke' : 'full';
  const profile = applyOverrides(opts.smoke ? SMOKE : FULL, opts.set);
  const scenarios = opts.scenarios ?? ALL_SCENARIOS;
  const defaultProxies = opts.smoke ? ['warp'] : PROXY_IDS;
  const proxies = opts.proxies ?? defaultProxies;

  const date = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
  const resultsDir = opts.out ?? join(BENCH_DIR, 'results', date);
  const dirs = {
    cache: DEFAULT_CACHE,
    work: join(resultsDir, 'work'),
    logs: join(resultsDir, 'logs'),
    results: resultsDir,
  };
  for (const d of Object.values(dirs)) mkdirSync(d, { recursive: true });
  mkdirSync(join(resultsDir, 'csv'), { recursive: true });
  mkdirSync(join(resultsDir, 'configs'), { recursive: true });

  const cores = osCpus().length;
  const plan = cpuPlan(cores, opts.pin);
  if (plan.bots) pinSelf(plan.bots);
  const env = fingerprint({ harnessSha: gitSha(), proxies: Object.fromEntries(Object.entries(versions.proxies).map(([k, v]) => [k, `${v.version}${v.build ? `-${v.build}` : ''}`])) });
  writeFileSync(join(resultsDir, 'env.json'), JSON.stringify(env, null, 2));
  saveRun(resultsDir, { date, profileName, profile, overrides: opts.set, scenarios, proxies, cpuNote: plan.note, cpuPlan: plan, thresholds: THRESHOLDS, versions });

  console.log(`Warp macro benchmark (${profileName}) on ${cores} cores. ${plan.note}`);
  console.log(`Proxies: ${proxies.join(', ')} | scenarios: ${scenarios.join(', ')}`);
  if (opts.set.length) console.log(`Overrides: ${opts.set.join(' ')}`);
  console.log(`Results: ${resultsDir}\n`);

  const fleet = new Fleet({ workerCpus: plan.workers, probeCpu: plan.probe });
  const results = [];
  try {
    for (const proxyId of shuffle(proxies)) {
      console.log(`=== ${proxyId} ===`);
      const started = Date.now();
      const result = await runProxy({
        proxyId,
        versions,
        profile,
        scenarios,
        ports: { proxy: opts.proxyPort, backendBase: opts.backendBase },
        dirs,
        cpus: { proxy: plan.proxy, backends: plan.backends },
        fleet,
        warpJar: opts.warpJar,
        mcVersion: opts.mc,
        backendHeapMb: BACKEND_HEAP_MB[profileName],
      });
      results.push(result);
      // Metrics, raw samples and the report so far, on disk before the next proxy starts: a run
      // that dies later keeps everything measured up to here.
      saveResult(resultsDir, result);
      writeReport(resultsDir);
      const secs = Math.round((Date.now() - started) / 1000);
      console.log(`    done in ${secs}s, ${result.errors.length} errors, natives ${result.natives?.ok ? 'ok' : 'unconfirmed'}`);
      for (const line of invalidResults([result])) console.log(`    INVALID ${line}`);
      console.log('');
    }
  } finally {
    fleet.stop();
  }
  console.log(`Report written: ${join(resultsDir, 'README.md')}`);

  // A non-zero exit when a proxy did not launch, a scenario threw, or no bot got through, so CI
  // notices a broken adapter or bot path even when the report was written. A measurement the
  // validity gate rejected is a finding about the machine or the sizes, stated in the report, not
  // a failure of the harness.
  const failures = harnessFailures(results);
  if (failures.length) {
    console.error(`FAILED:\n  ${failures.join('\n  ')}`);
    return 1;
  }
  return 0;
}

// Handled, so that an interrupted or killed run still stops the proxy and the Paper backends (which
// would otherwise keep their ports and a few GiB until killed by hand); SIGHUP: its terminal closed.
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, () => {
    if (interrupted) process.exit(130);
    interrupted = true;
    console.error(`\n${signal}: stopping the proxy and backends...`);
    killFleets();
    killAll('SIGTERM');
    setTimeout(() => { killAll('SIGKILL'); process.exit(130); }, 15_000).unref();
  });
}

// killAll on the way out: a child that survived its stop() would otherwise keep the process alive.
main().then(
  (code) => { killFleets(); killAll(); process.exit(code ?? 0); },
  (e) => {
    console.error(e.stack ?? e.message);
    killFleets();
    killAll();
    process.exit(1);
  },
);
