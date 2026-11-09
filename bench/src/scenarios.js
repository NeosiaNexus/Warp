// The benchmark scenarios. Each one warms up (so a JVM proxy's JIT is hot and a Go proxy is on equal
// footing), then measures over a fixed window, repeated a few times; the sampler records the proxy's
// resources throughout, tagged with the scenario, phase and offered load, and each scenario returns
// its own headline metrics (latency percentiles, switch counts, whether a real player joined). Sizes
// and durations come from the caller, so smoke mode runs the same code briefly with a handful of bots.
import { IdlePool, TrafficPool, SwitchPool, probeLatency, connectionStorm } from './load.js';
import { sleep } from './reuse.js';
import { median, percentile, trafficWindow } from './stats.js';

export { median, percentile };

/** Median of a sampler column over the rows of one phase. */
function columnMedian(rows, predicate, key) {
  const vals = rows.filter(predicate).map((r) => r[key]).filter((v) => typeof v === 'number');
  return median(vals);
}

/** Maximum of a sampler column over the rows of one phase, or null. */
function columnMax(rows, predicate, key) {
  const vals = rows.filter(predicate).map((r) => r[key]).filter((v) => typeof v === 'number');
  return vals.length ? Math.max(...vals) : null;
}

// ---------------------------------------------------------------------------
// Idle connections: how much a bare connection costs (RSS, FDs, CPU at rest)
// ---------------------------------------------------------------------------

export async function idleScenario(ctx, { levels, warmupMs, measureMs, reps, ratePerSec }) {
  const results = [];
  for (const level of levels) {
    const perLevel = { level, rss: [], fds: [], cpu: [], online: [], failures: 0 };
    for (let rep = 0; rep < reps; rep++) {
      const pool = new IdlePool({ port: ctx.port, prefix: `idle${level}r${rep}` });
      ctx.state({ scenario: 'idle', phase: 'ramp', offered: level, bots: 0 });
      await pool.fill(level, ratePerSec);
      perLevel.failures += pool.failures.length;
      ctx.state({ scenario: 'idle', phase: 'warmup', offered: level, bots: pool.online });
      await sleep(warmupMs);
      const from = ctx.now();
      ctx.state({ scenario: 'idle', phase: 'measure', offered: level, bots: pool.online });
      await sleep(measureMs);
      const to = ctx.now();
      const inWindow = (r) => r.scenario === 'idle' && r.phase === 'measure' && r.offered === level && r.t >= from && r.t <= to;
      perLevel.rss.push(columnMedian(ctx.rows(), inWindow, 'rssMiB'));
      perLevel.fds.push(columnMedian(ctx.rows(), inWindow, 'fds'));
      perLevel.cpu.push(columnMedian(ctx.rows(), inWindow, 'cpuPct'));
      perLevel.online.push(pool.online);
      await pool.drain();
      ctx.state({ scenario: 'idle', phase: 'drain', offered: level, bots: 0 });
      await sleep(1_000);
    }
    results.push({
      level,
      rssMiB: median(perLevel.rss.filter((v) => v != null)),
      fds: median(perLevel.fds.filter((v) => v != null)),
      cpuPct: median(perLevel.cpu.filter((v) => v != null)),
      online: median(perLevel.online),
      failures: perLevel.failures,
    });
  }
  return { levels: results };
}

// ---------------------------------------------------------------------------
// Heavy traffic: moving bots loading chunks (throughput and CPU per MB)
// ---------------------------------------------------------------------------

export async function trafficScenario(ctx, { bots, warmupMs, measureMs, reps, ratePerSec }) {
  const repResults = [];
  for (let rep = 0; rep < reps; rep++) {
    const pool = new TrafficPool({ port: ctx.port, version: ctx.version, prefix: `mv${rep}` });
    ctx.state({ scenario: 'traffic', phase: 'ramp', offered: bots, bots: 0 });
    await pool.fill(bots, ratePerSec);
    pool.startMoving();
    ctx.state({ scenario: 'traffic', phase: 'warmup', offered: bots, bots: pool.online });
    await sleep(warmupMs);
    const chunksBefore = pool.chunks;
    const from = ctx.now();
    ctx.state({ scenario: 'traffic', phase: 'measure', offered: bots, bots: pool.online });
    await sleep(measureMs);
    const to = ctx.now();
    const inWindow = (r) => r.scenario === 'traffic' && r.phase === 'measure' && r.t >= from && r.t <= to;
    const rows = ctx.rows().filter(inWindow);
    const window = trafficWindow(rows);
    repResults.push({
      online: pool.online,
      chunks: pool.chunks - chunksBefore,
      bytesPerSec: window.bytesPerSec,
      cpuPct: columnMedian(ctx.rows(), inWindow, 'cpuPct'),
      cpuPerMb: window.cpuPerMb,
      rssMiB: columnMedian(ctx.rows(), inWindow, 'rssMiB'),
    });
    await pool.drain();
    ctx.state({ scenario: 'traffic', phase: 'drain', offered: bots, bots: 0 });
    await sleep(1_000);
  }
  const pick = (k) => median(repResults.map((r) => r[k]).filter((v) => v != null));
  return {
    bots,
    online: Math.round(pick('online')),
    bytesPerSec: pick('bytesPerSec'),
    cpuPct: pick('cpuPct'),
    cpuPerMb: pick('cpuPerMb'),
    rssMiB: pick('rssMiB'),
    chunks: Math.round(pick('chunks')),
  };
}

// ---------------------------------------------------------------------------
// Added latency at low load (probe vs direct)
// ---------------------------------------------------------------------------

export async function latencyScenario(ctx, { samples, intervalMs, warmup = 0, timeoutMs = 8_000, backgroundBots, ratePerSec }) {
  // A little background load, below the knee, so the number is "added latency under light use". The
  // direct baseline runs the same probe against the forwarding-free clone backend with the same
  // background bots on it, so the difference is the proxy hop and nothing else.
  const probe = async (port, prefix) => {
    const bg = new TrafficPool({ port, version: ctx.version, prefix: `${prefix}_bg` });
    try {
      if (backgroundBots > 0) {
        await bg.fill(backgroundBots, ratePerSec);
        bg.startMoving();
      }
      ctx.state({ scenario: 'latency', phase: prefix, offered: backgroundBots, bots: bg.online });
      return await probeLatency({ port, version: ctx.version, samples, intervalMs, warmup, timeoutMs, username: `probe_${prefix}` });
    } finally {
      await bg.drain();
    }
  };
  const viaProxy = await probe(ctx.port, 'proxy');
  const direct = await probe(ctx.directPort, 'direct');
  const stat = (r) => ({
    p50: median(r.latencies),
    p99: percentile(r.latencies, 99),
    max: r.latencies.length ? Math.max(...r.latencies) : null,
    n: r.latencies.length,
    timeouts: r.timeouts,
    settled: r.settled,
  });
  const p = stat(viaProxy);
  const d = stat(direct);
  const added = (a, b) => (a != null && b != null ? Math.round((a - b) * 10) / 10 : null);
  return {
    viaProxy: p,
    direct: d,
    added: { p50: added(p.p50, d.p50), p99: added(p.p99, d.p99) },
    warmup,
    timeoutMs,
  };
}

// ---------------------------------------------------------------------------
// Switch storm: bots repeatedly change backend
// ---------------------------------------------------------------------------

export async function switchScenario(ctx, { bots, durationMs, ratePerSec }) {
  const pool = new SwitchPool({ port: ctx.port, version: ctx.version, servers: ctx.serverNames, prefix: 'sw' });
  ctx.state({ scenario: 'switch', phase: 'ramp', offered: bots, bots: 0 });
  await pool.fill(bots, ratePerSec);
  ctx.state({ scenario: 'switch', phase: 'storm', offered: bots, bots: pool.online });
  await pool.storm(durationMs);
  const result = {
    bots,
    online: pool.online,
    switches: pool.switches,
    switchesPerSec: Math.round((pool.switches / (durationMs / 1000)) * 10) / 10,
    failures: pool.switchFailures,
    p50Ms: median(pool.latencies),
    p99Ms: percentile(pool.latencies, 99),
    firstChunkP50Ms: median(pool.firstChunkLatencies),
  };
  await pool.drain();
  ctx.state({ scenario: 'switch', phase: 'drain', offered: bots, bots: 0 });
  await sleep(1_000);
  return result;
}

// ---------------------------------------------------------------------------
// Connection / status storm: hostile connections while a real player joins
// ---------------------------------------------------------------------------

export async function stormScenario(ctx, { rates, durationMs }) {
  const results = [];
  for (const rate of rates) {
    ctx.state({ scenario: 'storm', phase: 'flood', offered: rate, bots: 0 });
    const from = ctx.now();
    const r = await connectionStorm({ port: ctx.port, version: ctx.version, ratePerSec: rate, durationMs });
    const to = ctx.now();
    const inWindow = (row) => row.scenario === 'storm' && row.offered === rate && row.t >= from && row.t <= to;
    results.push({
      rate,
      opened: r.opened,
      joined: r.joinedDuringStorm,
      joinError: r.joinError,
      cpuPct: columnMedian(ctx.rows(), inWindow, 'cpuPct'),
      cpuPeakPct: columnMax(ctx.rows(), inWindow, 'cpuPct'),
      rssMiB: columnMedian(ctx.rows(), inWindow, 'rssMiB'),
    });
    ctx.state({ scenario: 'storm', phase: 'cool', offered: rate, bots: 0 });
    await sleep(2_000);
  }
  return { rates: results };
}
