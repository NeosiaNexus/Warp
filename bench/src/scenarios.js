// The benchmark scenarios. Each one ramps its load in, warms up (so a JVM proxy's JIT is hot and a
// Go proxy is on equal footing), then measures over a fixed window, repeated a few times; the
// sampler records the proxy's resources throughout, tagged with the scenario, phase and offered
// load. Next to its headline metrics every scenario records what the validity gate needs (offered
// against achieved load, backend health, load-generator saturation, proxy protections) and stores
// the verdict with the result: see validity.js. Sizes and durations come from the caller, so smoke
// mode runs the same code briefly with a handful of bots.
import { sleep } from './reuse.js';
import { median, percentile, trafficWindow } from './stats.js';
import { checkLatency, checkStorm, checkSwitches, checkWindow, verdict } from './validity.js';

export { median, percentile };

const column = (rows, key) => rows.map((r) => r[key]).filter((v) => typeof v === 'number');
const round = (v, digits = 1) => (v === null || v === undefined ? null : Math.round(v * 10 ** digits) / 10 ** digits);
const medianOf = (items, key) => median(items.map((i) => i[key]).filter((v) => typeof v === 'number'));

/** Marks the logs of the backends and of the proxy, so health is read from this point on. */
async function markAll(ctx, backends) {
  return { backends: await Promise.all(backends.map((b) => b.mark())), proxy: ctx.proxy.mark() };
}

/** Backend health and proxy protections since `marks`. */
async function healthSince(ctx, backends, marks) {
  return {
    backends: await Promise.all(backends.map((b, i) => b.health(marks.backends[i]))),
    proxy: ctx.proxy.protections(marks.proxy),
  };
}

/**
 * Warms up, then measures: the sampler rows of the window, the load at both ends, the health of
 * the backends and the proxy since `marks`, and the JVM's live heap, read after the window so that
 * the forced collection never touches a measured second.
 */
async function measure(ctx, { scenario, offered, warmupMs, measureMs, backends, marks }) {
  ctx.state({ scenario, phase: 'warmup', offered });
  await sleep(warmupMs);
  const start = await ctx.fleet.status(); // also resets the workers' event-loop lag
  ctx.state({ scenario, phase: 'measure', offered, bots: start.online });
  const from = ctx.now();
  const t0 = performance.now();
  await sleep(measureMs);
  const seconds = (performance.now() - t0) / 1000;
  const to = ctx.now();
  const end = await ctx.fleet.status();
  ctx.state({ scenario, phase: 'settle', offered, bots: end.online });
  const health = await healthSince(ctx, backends, marks);
  const heapMiB = await ctx.heap();
  const rows = ctx.rows().filter((r) => r.scenario === scenario && r.phase === 'measure' && r.t >= from && r.t <= to);
  const reasons = checkWindow({
    load: { offered, onlineStart: start.online, onlineEnd: end.online },
    backends: health.backends,
    loadgen: { lagP99Ms: end.lagP99Ms },
    proxy: health.proxy,
  });
  return { rows, seconds, start, end, health, heapMiB, reasons };
}

/** The part of a backend's health worth keeping with a result. */
function backendSummary(backends) {
  return backends.map(({ name, kind, tps, msptAvg, msptMax, overloads, errors, players, refused, keepAliveTimeouts, lagP99Ms }) =>
    kind === 'stub' ? { name, kind, players, refused, keepAliveTimeouts, lagP99Ms } : { name, kind, tps, msptAvg, msptMax, overloads, errors },
  );
}

// ---------------------------------------------------------------------------
// Idle connections: what a connection at rest costs (memory, FDs, CPU)
// ---------------------------------------------------------------------------

export async function idleScenario(ctx, { levels, warmupMs, measureMs, reps, ratePerSec }) {
  const results = [];
  for (const level of levels) {
    const repResults = [];
    for (let rep = 0; rep < reps; rep++) {
      const marks = await markAll(ctx, ctx.backends);
      ctx.state({ scenario: 'idle', phase: 'ramp', offered: level, bots: 0 });
      const filled = await ctx.fleet.fill('idle', level, ratePerSec, { port: ctx.port, prefix: `i${level}r${rep}` });
      const w = await measure(ctx, { scenario: 'idle', offered: level, warmupMs, measureMs, backends: ctx.backends, marks });
      repResults.push({
        online: w.end.online,
        failures: filled.failed,
        failureSample: filled.failureSamples[0] ?? null,
        rssMiB: median(column(w.rows, 'rssMiB')),
        fds: median(column(w.rows, 'fds')),
        cpuPct: median(column(w.rows, 'cpuPct')),
        proxyClients: median(column(w.rows, 'clients')),
        heapMiB: w.heapMiB,
        lagP99Ms: w.end.lagP99Ms,
        backends: backendSummary(w.health.backends),
        reasons: w.reasons,
      });
      await ctx.fleet.drain();
      ctx.state({ scenario: 'idle', phase: 'drain', offered: level, bots: 0 });
      await sleep(2_000);
    }
    results.push({
      level,
      online: Math.min(...repResults.map((r) => r.online)),
      failures: repResults.reduce((s, r) => s + r.failures, 0),
      rssMiB: medianOf(repResults, 'rssMiB'),
      heapMiB: medianOf(repResults, 'heapMiB'),
      fds: medianOf(repResults, 'fds'),
      cpuPct: medianOf(repResults, 'cpuPct'),
      reps: repResults,
      validity: verdict(repResults.flatMap((r) => r.reasons)),
    });
  }
  return { levels: results };
}

// ---------------------------------------------------------------------------
// Heavy traffic: movers streaming chunks through the proxy (throughput and CPU per MB)
// ---------------------------------------------------------------------------

export async function trafficScenario(ctx, { bots, warmupMs, measureMs, reps, ratePerSec }) {
  const repResults = [];
  for (let rep = 0; rep < reps; rep++) {
    const marks = await markAll(ctx, ctx.backends);
    ctx.state({ scenario: 'traffic', phase: 'ramp', offered: bots, bots: 0 });
    const filled = await ctx.fleet.fill('mover', bots, ratePerSec, { port: ctx.port, prefix: `mv${rep}` });
    const w = await measure(ctx, { scenario: 'traffic', offered: bots, warmupMs, measureMs, backends: ctx.backends, marks });
    const traffic = trafficWindow(w.rows);
    repResults.push({
      online: w.end.online,
      failures: filled.failed,
      failureSample: filled.failureSamples[0] ?? null,
      bytesPerSec: traffic.bytesPerSec,
      deliveredBytesPerSec: Math.round((w.end.bytesIn - w.start.bytesIn) / w.seconds),
      framesPerSec: Math.round((w.end.frames - w.start.frames) / w.seconds),
      chunkFramesPerSec: Math.round((w.end.compressedFrames - w.start.compressedFrames) / w.seconds),
      corrections: w.end.teleports - w.start.teleports,
      cpuPct: median(column(w.rows, 'cpuPct')),
      cpuPerMb: traffic.cpuPerMb,
      rssMiB: median(column(w.rows, 'rssMiB')),
      heapMiB: w.heapMiB,
      lagP99Ms: w.end.lagP99Ms,
      backends: backendSummary(w.health.backends),
      reasons: w.reasons,
    });
    await ctx.fleet.drain();
    ctx.state({ scenario: 'traffic', phase: 'drain', offered: bots, bots: 0 });
    await sleep(2_000);
  }
  const pick = (k) => medianOf(repResults, k);
  return {
    bots,
    online: Math.min(...repResults.map((r) => r.online)),
    failures: repResults.reduce((s, r) => s + r.failures, 0),
    bytesPerSec: pick('bytesPerSec'),
    deliveredBytesPerSec: pick('deliveredBytesPerSec'),
    chunkFramesPerSec: pick('chunkFramesPerSec'),
    cpuPct: pick('cpuPct'),
    cpuPerMb: pick('cpuPerMb'),
    rssMiB: pick('rssMiB'),
    heapMiB: pick('heapMiB'),
    backendTps: Math.min(...repResults.flatMap((r) => r.backends.map((b) => b.tps ?? 0))),
    reps: repResults,
    validity: verdict(repResults.flatMap((r) => r.reasons)),
  };
}

// ---------------------------------------------------------------------------
// Added latency at low load (ping round trip through the proxy vs direct)
// ---------------------------------------------------------------------------

export async function latencyScenario(ctx, { samples, intervalMs, warmup = 0, timeoutMs = 8_000, backgroundBots, ratePerSec }) {
  // A light background load, far below what the backend or the proxy can take, so the number is
  // "added latency under light use". The direct baseline runs the same probe against the
  // forwarding-free clone backend with the same background movers on it, so the difference is the
  // proxy hop and nothing else.
  const leg = async (port, prefix, backends) => {
    const marks = await markAll(ctx, backends);
    try {
      ctx.state({ scenario: 'latency', phase: `${prefix}-ramp`, offered: backgroundBots, bots: 0 });
      const filled = backgroundBots > 0 ? await ctx.fleet.fill('mover', backgroundBots, ratePerSec, { port, prefix: `bg${prefix}` }) : await ctx.fleet.status();
      ctx.state({ scenario: 'latency', phase: prefix, offered: backgroundBots, bots: filled.online });
      const r = await ctx.fleet.probe({ port, samples, intervalMs, warmup, timeoutMs, username: `probe_${prefix}` });
      const end = await ctx.fleet.status();
      const health = await healthSince(ctx, backends, marks);
      const reasons = checkWindow({
        load: { offered: backgroundBots, onlineStart: filled.online, onlineEnd: end.online },
        backends: health.backends,
        loadgen: { lagP99Ms: Math.max(end.lagP99Ms, r.lagP99Ms) },
        proxy: prefix === 'proxy' ? health.proxy : undefined,
      });
      return {
        p50: round(median(r.latencies), 3),
        p90: round(percentile(r.latencies, 90), 3),
        p99: round(percentile(r.latencies, 99), 3),
        max: r.latencies.length ? round(Math.max(...r.latencies), 3) : null,
        n: r.latencies.length,
        timeouts: r.timeouts,
        settled: r.settled,
        background: end.online,
        backends: backendSummary(health.backends),
        reasons,
      };
    } finally {
      await ctx.fleet.drain();
      await sleep(1_000);
    }
  };
  const viaProxy = await leg(ctx.port, 'proxy', ctx.backends);
  const direct = await leg(ctx.directPort, 'direct', [ctx.directBackend]);
  const added = (a, b) => (a != null && b != null ? round(a - b, 3) : null);
  return {
    viaProxy,
    direct,
    added: { p50: added(viaProxy.p50, direct.p50), p90: added(viaProxy.p90, direct.p90), p99: added(viaProxy.p99, direct.p99) },
    samples,
    warmup,
    timeoutMs,
    backgroundBots,
    validity: verdict([...viaProxy.reasons, ...direct.reasons.map((r) => `direct leg: ${r}`), ...checkLatency({ samples, viaProxy, direct })]),
  };
}

// ---------------------------------------------------------------------------
// Switch storm: bots repeatedly change backend
// ---------------------------------------------------------------------------

export async function switchScenario(ctx, { bots, durationMs, pauseMs, ratePerSec }) {
  const marks = await markAll(ctx, ctx.backends);
  ctx.state({ scenario: 'switch', phase: 'ramp', offered: bots, bots: 0 });
  const filled = await ctx.fleet.fill('bot', bots, ratePerSec, { port: ctx.port, version: ctx.version, servers: ctx.serverNames, prefix: 'sw' });
  ctx.state({ scenario: 'switch', phase: 'storm', offered: bots, bots: filled.online });
  const storm = await ctx.fleet.switchStorm(durationMs, pauseMs);
  const end = await ctx.fleet.status();
  const health = await healthSince(ctx, ctx.backends, marks);
  const heapMiB = await ctx.heap();
  const result = {
    bots,
    online: end.online,
    connectFailures: filled.failed,
    failureSample: filled.failureSamples[0] ?? null,
    switches: storm.switches,
    switchesPerSec: round(storm.switches / (durationMs / 1000), 1),
    failures: storm.failures,
    firstChunkP50Ms: round(median(storm.firstChunkLatencies), 1),
    firstChunkP99Ms: round(percentile(storm.firstChunkLatencies, 99), 1),
    p50Ms: round(median(storm.latencies), 1),
    p99Ms: round(percentile(storm.latencies, 99), 1),
    heapMiB,
    lagP99Ms: end.lagP99Ms,
    backends: backendSummary(health.backends),
    validity: verdict([
      ...checkWindow({
        load: { offered: bots, onlineStart: filled.online, onlineEnd: end.online },
        backends: health.backends,
        proxy: health.proxy,
      }),
      ...checkSwitches(storm),
    ]),
  };
  await ctx.fleet.drain();
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
    const mark = ctx.proxy.mark();
    const from = ctx.now();
    // Halfway through the flood, a genuine player tries to join and play.
    const [flood, join] = await Promise.all([
      ctx.fleet.flood({ port: ctx.port, ratePerSec: rate, durationMs }),
      sleep(durationMs / 2).then(() => ctx.fleet.join({ port: ctx.port, version: ctx.version, username: 'storm_survivor' })),
    ]);
    const to = ctx.now();
    const rows = ctx.rows().filter((row) => row.scenario === 'storm' && row.offered === rate && row.t >= from && row.t <= to);
    // A flood is where a proxy's own protection may engage (a cap on connections that are not
    // logged in, an attack mode). That is a reaction to report, not a reason to reject the row.
    const reaction = ctx.proxy.protections(mark);
    results.push({
      rate,
      attempted: flood.attempted,
      opened: flood.opened,
      protections: reaction.protections,
      protectionSample: reaction.protectionSample,
      joined: join.joined,
      joinError: join.error,
      cpuPct: median(column(rows, 'cpuPct')),
      cpuPeakPct: column(rows, 'cpuPct').length ? Math.max(...column(rows, 'cpuPct')) : null,
      rssMiB: median(column(rows, 'rssMiB')),
      validity: verdict([...checkStorm({ rate, durationMs, attempted: flood.attempted, opened: flood.opened }), ...(ctx.proxy.protections(0).dead ? ['the proxy was not running at the end of the flood'] : [])]),
    });
    ctx.state({ scenario: 'storm', phase: 'cool', offered: rate, bots: 0 });
    await sleep(2_000);
  }
  return { rates: results };
}
