// Entry point for the macro benchmark. Resolves options, runs each proxy end to end in a shuffled
// order (so thermal drift does not favour whoever goes first), writes the raw per-second CSVs, the
// configs each proxy actually used, the host fingerprint and the Markdown report under
// bench/results/<date>/. One command, reproducible: see docs/benchmarks/README.md.
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { cpus as osCpus } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { cpuPlan, fingerprint } from './env.js';
import { FULL, SMOKE, ALL_SCENARIOS, BACKEND_HEAP_MB } from './profiles.js';
import { buildReport } from './report.js';
import { killAll } from './reuse.js';
import { runProxy } from './run.js';
import { harnessFailures } from './verdict.js';
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
  --help                  This text.

Every download is checked against the sha256 in bench/versions.json. Results, raw CSVs, the exact
configs and the host fingerprint are written under the results directory.`;

function gitSha() {
  try {
    return execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: BENCH_DIR, encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
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

  const versions = JSON.parse(readFileSync(join(BENCH_DIR, 'versions.json'), 'utf8'));
  const profile = opts.smoke ? SMOKE : FULL;
  const profileName = opts.smoke ? 'smoke' : 'full';
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
  const env = fingerprint({ harnessSha: gitSha(), proxies: Object.fromEntries(Object.entries(versions.proxies).map(([k, v]) => [k, `${v.version}${v.build ? `-${v.build}` : ''}`])) });
  writeFileSync(join(resultsDir, 'env.json'), JSON.stringify(env, null, 2));

  console.log(`Warp macro benchmark (${profileName}) on ${cores} cores. ${plan.note}`);
  console.log(`Proxies: ${proxies.join(', ')} | scenarios: ${scenarios.join(', ')}`);
  console.log(`Results: ${resultsDir}\n`);

  const results = [];
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
      cpus: { proxy: plan.proxy, backends: plan.backends, bots: plan.bots },
      warpJar: opts.warpJar,
      mcVersion: opts.mc,
      backendHeapMb: BACKEND_HEAP_MB[profileName],
    });
    results.push(result);
    // Raw CSV and the config the proxy used, for the record.
    writeFileSync(join(resultsDir, 'csv', `${proxyId}.csv`), result.csv);
    const workConf = join(dirs.work, proxyId);
    if (existsSync(workConf)) {
      for (const f of ['warp.conf', 'velocity.toml', 'config.yml', 'config.toml']) {
        if (existsSync(join(workConf, f))) cpSync(join(workConf, f), join(resultsDir, 'configs', `${proxyId}-${f}`));
      }
    }
    const secs = Math.round((Date.now() - started) / 1000);
    console.log(`    done in ${secs}s, ${result.errors.length} errors, natives ${result.natives?.ok ? 'ok' : 'unconfirmed'}\n`);
  }

  // Report in the proxy order given (stable), not the shuffled run order.
  const ordered = proxies.map((id) => results.find((r) => r.id === id)).filter(Boolean);
  const report = buildReport({ results: ordered, env, versions, profileName, date, cpuNote: plan.note });
  writeFileSync(join(resultsDir, 'README.md'), report);
  console.log(`Report written: ${join(resultsDir, 'README.md')}`);

  // A non-zero exit when a proxy did not launch, a scenario threw, or no bot got through, so CI
  // notices a broken adapter or bot path even when the report was written.
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
    killAll('SIGTERM');
    setTimeout(() => { killAll('SIGKILL'); process.exit(130); }, 15_000).unref();
  });
}

// killAll on the way out: a child that survived its stop() would otherwise keep the process alive.
main().then(
  (code) => { killAll(); process.exit(code ?? 0); },
  (e) => {
    console.error(e.stack ?? e.message);
    killAll();
    process.exit(1);
  },
);
