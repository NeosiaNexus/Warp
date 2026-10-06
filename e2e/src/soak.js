// Soak: a crowd of bots stays connected through Warp for a long time, switching servers and
// reconnecting, while the harness samples Warp's process (src/sampler.js). It fails on sustained
// growth of memory, file descriptors or threads (a leak), on connections or objects Warp still holds
// once every player has left, and on any bot failure (kick, protocol error, stalled connection).
// Warp's log (ERROR, Netty LEAK) and its shutdown are checked by the runner, as after every scenario.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { sleep } from './proc.js';
import { ProcessSampler, parseHistogram } from './sampler.js';
import { LOBBY_MODE, SURVIVAL_MODE, checkStatus } from './scenarios.js';

/** One churn cycle: a quarter of the bots switch server, a fifth reconnect, one server list ping. */
const CYCLE_MS = 10_000;
const SAMPLE_EVERY_MS = 5_000;
/** Each forced full GC measures the live heap, and lets Netty report the buffers leaked so far. */
const GC_EVERY_MS = 30_000;
/** Paper sends every player the time once a second: a bot that hears nothing for this long is stuck. */
const STALL_MS = 30_000;
const SWITCH_TIMEOUT_MS = 45_000;
/** Past this many bot failures Warp is clearly broken: the soak stops early, with its samples. */
const MAX_FAILURES = 25;
/** Once every bot has left, Warp has this long to close all of their connections. */
const DRAIN_TIMEOUT_MS = 30_000;

/**
 * JVM options of the Warp under soak. Its heap is fixed and touched up front, so that resident
 * memory only grows with native memory (direct buffers, threads, metaspace, code); native memory
 * tracking breaks that down, and a flight recording is kept for post-mortems.
 */
export function soakJvmArgs(recording) {
  return [
    '-Xms512m',
    '-Xmx512m',
    '-XX:+AlwaysPreTouch',
    '-XX:+UseG1GC',
    '-XX:NativeMemoryTracking=summary',
    `-XX:StartFlightRecording=filename=${recording},settings=default,dumponexit=true`,
  ];
}

/**
 * Resources checked for a leak over the steady phase. Each may grow by its limit at most (the larger
 * of `abs` and `rel` times its level), measured between the medians of the first and last thirds
 * of the phase. Growth is only sustained if the resource was still growing in the last third: a
 * pool that filled up early and then stayed full is not a leak.
 */
export const RESOURCES = [
  { key: 'rss', label: 'Resident memory', unit: 'MiB', abs: 32, rel: 0.05 },
  { key: 'heapLive', label: 'Live heap (after full GC)', unit: 'MiB', abs: 8, rel: 0.25 },
  { key: 'direct', label: 'Direct memory', unit: 'MiB', abs: 16, rel: 0.25 },
  { key: 'fds', label: 'File descriptors', unit: '', abs: 8, rel: 0.1 },
  { key: 'threads', label: 'Threads', unit: '', abs: 8, rel: 0.1 },
];

/**
 * Classes whose objects must not outlive the players: Warp's own (not the libraries it relocates
 * under dev.warp.libs) and Netty's socket channels.
 */
const TRACKED_CLASS = /^(?:dev\.warp\.(?!libs\.).*|io\.netty\.channel\..*(?<!Server)SocketChannel)$/;

/** Warm-up: a fifth of the soak, 1 to 5 minutes (at most half of it). The leak checks skip it. */
export function warmUpMs(durationMs) {
  return Math.min(Math.max(durationMs * 0.2, 60_000), 300_000, durationMs / 2);
}

// ---------------------------------------------------------------------------
// Scenario
// ---------------------------------------------------------------------------

/** The soak, as the runner's scenario list holds it (`--soak` replaces the other scenarios). */
export const SOAK = { name: 'soak', run: soak, requires: 'proxy' };

/**
 * Warm-up, intermission, steady phase, drain. At the intermission and at the end, every bot leaves:
 * Warp must close every connection, and the objects it holds then are compared. Both moments come
 * after the same code paths (protocol tables and per-thread state are built lazily, on the first
 * players), so only what Warp kept per connection in between shows.
 *
 * @param {object} ctx scenario context, with `soak: {minutes, dir}`, `warp.instance` (the Warp
 *   under test) and, on a degraded network, `netem`
 */
export async function soak(ctx) {
  const durationMs = ctx.soak.minutes * 60_000;
  const warmUp = warmUpMs(durationMs);
  const warp = ctx.warp.instance;
  const started = Date.now();
  const elapsed = () => Date.now() - started;
  const timeline = { phase: 'warm-up', steadyFrom: null, drainFrom: null };
  const crowd = new Crowd(ctx, { origin: started, phase: () => timeline.phase });
  const sampler = new ProcessSampler({
    pid: warp.process.pid,
    jcmd: (commands) => warp.jcmd(commands),
    port: ctx.warp.port,
    backends: [ctx.lobby.port, ctx.survival.port],
    origin: started,
    intervalMs: SAMPLE_EVERY_MS,
    gcEveryMs: GC_EVERY_MS,
    annotate: () => ({ phase: timeline.phase, bots: crowd.online }),
  });
  const network = ctx.netem ? { spec: ctx.netem.spec, before: await ctx.netem.stats() } : null;
  log(`soak: ${ctx.bots} bots for ${ctx.soak.minutes} min (warm-up ${clock(warmUp / 1000)})${network ? `, netem ${network.spec}` : ''}`);

  // Every bot leaves; Warp must close their connections, and what it holds then is measured.
  const histogram = async () => parseHistogram(await warp.jcmd(['GC.class_histogram']));
  const drains = [];
  const objects = { before: null, after: null };
  const release = async () => {
    await crowd.leave();
    drains.push(await waitForDrain(sampler));
    return histogram();
  };

  let error = null;
  await sampler.sample({ gc: true }); // Warp idle, before the first player
  sampler.start();
  try {
    await crowd.join();
    let reported = 0;
    while (elapsed() < durationMs) {
      if (timeline.phase === 'warm-up' && elapsed() >= warmUp) {
        objects.before = await release();
        await crowd.join();
        Object.assign(timeline, { phase: 'steady', steadyFrom: elapsed() / 1000 });
      }
      const cycle = Date.now();
      await crowd.cycle();
      if (!warp.process.alive) throw new Error('Warp exited during the soak');
      if (crowd.failures.length >= MAX_FAILURES) throw new Error(`${crowd.failures.length} bot failures, stopped early`);
      if (elapsed() - reported >= 60_000) {
        reported = elapsed();
        progress(sampler.samples.at(-1), crowd, elapsed(), durationMs);
      }
      await sleep(Math.min(cycle + CYCLE_MS, started + durationMs) - Date.now());
    }
    Object.assign(timeline, { phase: 'drain', drainFrom: elapsed() / 1000 });
    objects.after = await release();
  } catch (e) {
    error = e;
  } finally {
    timeline.phase = 'drain';
    timeline.drainFrom ??= elapsed() / 1000;
    await crowd.leave();
    await sampler.stop();
    if (warp.process.alive) await sampler.sample({ gc: true }); // idle again, as at the start
  }
  if (network) network.after = await ctx.netem.stats().catch(() => null);

  const report = summarize({ ctx, samples: sampler.samples, crowd, timeline, drains, objects, network, error });
  const base = join(ctx.soak.dir, `soak-${ctx.variant.name}`);
  writeFileSync(`${base}.json`, `${JSON.stringify({ ...report, samples: sampler.samples }, null, 2)}\n`);
  writeFileSync(`${base}.csv`, csv(sampler.samples));
  ctx.soak.report = report;
  console.log(consoleSummary(report));

  if (error) throw error;
  if (report.problems.length) throw new Error(report.problems.join('; '));
  const ops = report.phases.reduce((sum, p) => ({ switches: sum.switches + p.switches, joins: sum.joins + p.joins }), { switches: 0, joins: 0 });
  return `${ctx.bots} bots for ${ctx.soak.minutes} min: ${ops.switches} switches, ${ops.joins} joins, no leak, every connection and object released`;
}

/**
 * Polls Warp's connections until none is left.
 * @returns {Promise<{clients: number, backends: number, seconds: number|null}>} how long it took, or
 *   null seconds and what Warp still holds at the deadline
 */
async function waitForDrain(sampler) {
  const started = Date.now();
  for (;;) {
    const { clients, backends } = sampler.connections();
    if (!clients && !backends) return { clients, backends, seconds: (Date.now() - started) / 1000 };
    if (Date.now() - started > DRAIN_TIMEOUT_MS) return { clients, backends, seconds: null };
    await sleep(250);
  }
}

// ---------------------------------------------------------------------------
// The crowd: bots that join, switch, reconnect and ping, and what came of it
// ---------------------------------------------------------------------------

class Crowd {
  /**
   * @param {object} ctx scenario context
   * @param {{origin: number, phase: () => string}} timeline time 0 of the soak, and its current phase
   */
  constructor(ctx, { origin, phase }) {
    this.ctx = ctx;
    this.origin = origin;
    this.phase = phase;
    this.names = Array.from({ length: ctx.bots }, (_, i) => `soak_${String(i).padStart(2, '0')}`);
    /** Connected bots by name. A name missing here (failed, or reconnecting) joins next cycle. */
    this.bots = new Map();
    /** Connections of failed bots being closed, by name: the name joins again once it is closed. */
    this.closing = new Map();
    this.ops = [];
    this.failures = [];
    this.cycles = 0;
  }

  get online() {
    return this.bots.size;
  }

  /** Every bot joins, one after the other. */
  async join() {
    for (const name of this.names) {
      await this.rejoin(name);
      await sleep(100);
    }
  }

  /**
   * One cycle: a quarter of the bots switch server and a fifth reconnect (each in turn, so that
   * every bot does both), bots that failed come back, and a server list ping goes through.
   */
  async cycle() {
    this.checkHealth();
    const size = this.names.length;
    const at = (offset, count) => Array.from({ length: Math.min(count, size) }, (_, i) => this.names[(offset + i) % size]);
    const switching = at(this.cycles * Math.ceil(size / 4), Math.ceil(size / 4)).filter((name) => this.bots.has(name));
    const rejoining = new Set([
      ...this.names.filter((name) => !this.bots.has(name)),
      ...at(Math.floor(size / 2) + this.cycles * Math.ceil(size / 5), Math.ceil(size / 5)).filter((name) => !switching.includes(name)),
    ]);
    this.cycles++;
    await Promise.all([...switching.map((name) => this.switchServer(name)), ...[...rejoining].map((name) => this.rejoin(name)), this.ping()]);
  }

  async switchServer(name) {
    const bot = this.bots.get(name);
    const [target, mode] = bot.gameMode() === SURVIVAL_MODE ? ['lobby', LOBBY_MODE] : ['survival', SURVIVAL_MODE];
    await this.op('switch', name, async () => {
      const chunks = bot.stats.chunks;
      await bot.command(`server ${target}`);
      await bot.waitForGameMode(mode, SWITCH_TIMEOUT_MS);
      await bot.waitForChunks(20, SWITCH_TIMEOUT_MS, chunks);
    });
  }

  async rejoin(name) {
    const previous = this.bots.get(name);
    this.bots.delete(name);
    // The backend must have seen the previous connection go, or it refuses a duplicate login.
    await (previous?.quit() ?? this.closing.get(name));
    this.closing.delete(name);
    await this.op('join', name, async () => {
      this.bots.set(name, await this.ctx.client.connect({ host: '127.0.0.1', port: this.ctx.warp.port, username: name }));
    });
  }

  ping() {
    return this.op('ping', null, async () => {
      const response = await this.ctx.client.ping({ host: '127.0.0.1', port: this.ctx.warp.port });
      checkStatus(response, { direct: false, protocol: this.ctx.client.protocol });
    });
  }

  /** Fails the bots that were kicked, saw a protocol error, lost their connection or stalled. */
  checkHealth() {
    for (const [name, bot] of this.bots) {
      try {
        bot.healthy();
        const silent = Date.now() - bot.stats.lastPacket;
        if (silent > STALL_MS) throw new Error(`${name}: no packet for ${Math.round(silent / 1000)} s`);
      } catch (e) {
        this.fail('health', name, e);
      }
    }
  }

  async op(kind, name, body) {
    const started = Date.now();
    const record = { t: (started - this.origin) / 1000, phase: this.phase(), kind };
    try {
      await body();
      this.ops.push({ ...record, ms: Date.now() - started, ok: true });
    } catch (e) {
      this.ops.push({ ...record, ms: Date.now() - started, ok: false });
      this.fail(kind, name, e);
    }
  }

  /** Records a failure; a failed bot disconnects, and joins again at the next cycle. */
  fail(kind, name, error) {
    const now = Date.now() - this.origin;
    const message = String(error?.message ?? error).split('\n')[0];
    this.failures.push({ t: Math.round(now / 100) / 10, phase: this.phase(), op: kind, bot: name, error: message });
    log(`  ✗ ${clock(now / 1000)} ${kind}${name ? ` ${name}` : ''}: ${message}`);
    const bot = this.bots.get(name);
    if (!bot) return;
    this.bots.delete(name);
    this.closing.set(name, bot.quit());
  }

  /** Every bot quits; resolves once their connections, and those of failed bots, are closed. */
  async leave() {
    const closing = [...[...this.bots.values()].map((bot) => bot.quit()), ...this.closing.values()];
    this.bots.clear();
    this.closing.clear();
    await Promise.all(closing);
  }
}

// ---------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------

/**
 * Checks one resource for a leak over the steady phase `[from, to]` (seconds), see {@link RESOURCES}.
 * @returns {{status: 'pass'|'fail'|'skip', first?: number, middle?: number, last?: number, growth?: number, limit?: number}}
 */
export function checkGrowth(samples, resource, from, to) {
  const third = (to - from) / 3;
  const thirds = [0, 1, 2].map((i) =>
    samples
      .filter((s) => s[resource.key] !== null && s[resource.key] !== undefined)
      .filter((s) => s.t >= from + i * third && (i === 2 ? s.t <= to : s.t < from + (i + 1) * third))
      .map((s) => s[resource.key]),
  );
  if (thirds.some((values) => values.length === 0)) return { status: 'skip' };
  const [first, middle, last] = thirds.map(median);
  const growth = last - first;
  const limit = Math.max(resource.abs, resource.rel * first);
  const sustained = growth > limit && last - middle > growth / 4;
  return { status: sustained ? 'fail' : 'pass', first, middle, last, growth, limit };
}

/**
 * Objects of the tracked classes that outlived the players: instances once every player has left,
 * against before the first one joined. A class may keep up to one more per player (a cache keyed
 * by player), not more: that is an object per connection or per switch, kept.
 */
export function retainedObjects(before, after, players) {
  const grown = [...after]
    .filter(([name]) => TRACKED_CLASS.test(name))
    .map(([name, count]) => ({ name, before: before.get(name) ?? 0, after: count }))
    .map((c) => ({ ...c, growth: c.after - c.before }))
    .filter((c) => c.growth > 0)
    .sort((a, b) => b.growth - a.growth || a.name.localeCompare(b.name));
  const total = (histogram) => [...histogram].filter(([name]) => TRACKED_CLASS.test(name)).reduce((sum, [, count]) => sum + count, 0);
  const leaked = grown.filter((c) => c.growth > players);
  return { status: leaked.length ? 'fail' : 'pass', limit: players, before: total(before), after: total(after), leaked: leaked.length, classes: grown.slice(0, 10) };
}

/**
 * The soak's verdict and statistics, as stored in result.json and rendered in the summaries.
 * @param {object} run what the soak recorded: `timeline` (when the steady phase and the drain
 *   started, in seconds), `drains` (at the intermission and at the end, see {@link waitForDrain}),
 *   `objects` (class histograms at the same two moments)
 */
export function summarize({ ctx, samples, crowd, timeline, drains, objects, network, error }) {
  const end = samples.at(-1)?.t ?? 0;
  const steady = timeline.steadyFrom === null ? null : [timeline.steadyFrom, timeline.drainFrom];
  const resources = RESOURCES.map((resource) => {
    const values = samples.map((s) => s[resource.key]).filter((v) => v !== null && v !== undefined);
    const check = steady ? checkGrowth(samples, resource, ...steady) : { status: 'skip' };
    return { key: resource.key, label: resource.label, unit: resource.unit, start: values[0] ?? null, end: values.at(-1) ?? null, peak: values.length ? Math.max(...values) : null, ...check };
  });
  const heapPeak = Math.max(0, ...samples.map((s) => s.heapUsed ?? 0));
  const heapMax = Math.max(0, ...samples.map((s) => s.heapCommitted ?? 0));
  const phases = ['warm-up', 'steady', 'drain']
    .map((name) => phaseStats(name, samples.filter((s) => s.phase === name), crowd))
    .filter((p) => p.samples > 0);

  const problems = [];
  for (const r of resources.filter((x) => x.status === 'fail')) {
    problems.push(`${r.label} grew by ${amount(r.growth, r.unit)} over the steady phase (limit ${amount(r.limit, r.unit)})`);
  }
  const retained = objects.before && objects.after ? retainedObjects(objects.before, objects.after, ctx.bots) : null;
  if (retained?.status === 'fail') {
    const listed = retained.classes.filter((c) => c.growth > retained.limit).slice(0, 3).map((c) => `${c.name} +${c.growth}`);
    const more = retained.leaked > listed.length ? `, and ${retained.leaked - listed.length} more` : '';
    problems.push(`Warp kept objects of ${retained.leaked} class(es) through the steady phase, more than one per player: ${listed.join(', ')}${more}`);
  }
  drains.forEach((drain, i) => {
    if (drain.seconds !== null) return;
    const when = i === 0 && drains.length > 1 ? 'after the warm-up' : 'at the end';
    problems.push(`Warp still held ${drain.clients} client and ${drain.backends} backend connection(s) ${DRAIN_TIMEOUT_MS / 1000} s after every player left, ${when}`);
  });
  if (crowd.failures.length) problems.push(`${crowd.failures.length} bot failure(s), first: ${describeFailure(crowd.failures[0])}`);
  if (error) problems.unshift(String(error.message ?? error).split('\n')[0]);

  return {
    version: ctx.entry.version,
    variant: ctx.variant.name,
    bots: ctx.bots,
    minutes: ctx.soak.minutes,
    seconds: Math.round(end),
    status: problems.length ? 'fail' : 'pass',
    problems,
    resources,
    heap: { peak: heapPeak, max: heapMax },
    drains,
    objects: retained,
    phases,
    network: network && { spec: network.spec, ...difference(network.before, network.after) },
    failures: crowd.failures,
  };
}

function phaseStats(name, samples, crowd) {
  const ops = crowd.ops.filter((op) => op.phase === name);
  const done = (kind) => ops.filter((op) => op.kind === kind && op.ok);
  const latency = (kind) => {
    const ms = done(kind).map((op) => op.ms).sort((a, b) => a - b);
    return ms.length ? { p50: percentile(ms, 0.5), p95: percentile(ms, 0.95) } : null;
  };
  const values = (key) => samples.map((s) => s[key]).filter((v) => v !== null && v !== undefined);
  const max = (key) => (values(key).length ? Math.max(...values(key)) : null);
  const avg = (key) => (values(key).length ? values(key).reduce((a, b) => a + b, 0) / values(key).length : null);
  return {
    name,
    samples: samples.length,
    from: samples[0]?.t ?? null,
    to: samples.at(-1)?.t ?? null,
    bots: max('bots'),
    joins: done('join').length,
    switches: done('switch').length,
    pings: done('ping').length,
    failures: crowd.failures.filter((f) => f.phase === name).length,
    joinMs: latency('join'),
    switchMs: latency('switch'),
    rss: { avg: avg('rss'), max: max('rss') },
    heapLive: max('heapLive'),
    heapUsed: max('heapUsed'),
    direct: max('direct'),
    fds: max('fds'),
    threads: max('threads'),
    cpu: avg('cpu'),
    sendQueue: max('sendQueue'),
    receiveQueue: max('receiveQueue'),
  };
}

/** What netem handled between two of its statistics. */
function difference(before, after) {
  if (!before || !after) return {};
  return { packets: after.packets - before.packets, bytes: after.bytes - before.bytes, drops: after.drops - before.drops };
}

// ---------------------------------------------------------------------------
// Output
// ---------------------------------------------------------------------------

const COLUMNS = [
  ['time_s', 't'],
  ['phase', 'phase'],
  ['bots', 'bots'],
  ['rss_mib', 'rss'],
  ['heap_used_mib', 'heapUsed'],
  ['heap_committed_mib', 'heapCommitted'],
  ['heap_live_mib', 'heapLive'],
  ['direct_mib', 'direct'],
  ['metaspace_mib', 'metaspace'],
  ['fds', 'fds'],
  ['sockets', 'sockets'],
  ['client_connections', 'clients'],
  ['backend_connections', 'backends'],
  ['send_queue_kib', 'sendQueue'],
  ['receive_queue_kib', 'receiveQueue'],
  ['threads', 'threads'],
  ['cpu_percent', 'cpu'],
];

/** The time series, one row per sample. */
export function csv(samples) {
  const rows = samples.map((s) => COLUMNS.map(([, key]) => s[key] ?? '').join(','));
  return `${[COLUMNS.map(([name]) => name).join(','), ...rows].join('\n')}\n`;
}

function progress(sample, crowd, elapsedMs, durationMs) {
  if (!sample) return;
  const ok = (kind) => crowd.ops.filter((op) => op.kind === kind && op.ok).length;
  const parts = [
    `${sample.bots} bots`,
    `RSS ${amount(sample.rss, 'MiB')}`,
    `heap ${amount(sample.heapUsed, 'MiB')}`,
    `direct ${amount(sample.direct, 'MiB')}`,
    `${sample.fds} fds`,
    `${sample.threads} threads`,
    `${ok('switch')} switches`,
    `${ok('join')} joins`,
    `${crowd.failures.length} failures`,
  ];
  log(`  soak ${clock(elapsedMs / 1000)}/${clock(durationMs / 1000)} ${sample.phase}: ${parts.join(', ')}`);
}

/** Plain-text summary for the console. */
export function consoleSummary(report) {
  const lines = [`Soak ${report.status.toUpperCase()}: ${report.bots} bots, ${report.minutes} min${report.network ? `, netem ${report.network.spec}` : ''}`];
  for (const r of report.resources) {
    const steady = r.status === 'skip' ? 'not enough samples' : `${amount(r.first, r.unit)} → ${amount(r.last, r.unit)} (limit +${amount(r.limit, r.unit)})`;
    lines.push(`  ${r.status === 'fail' ? '✗' : '✓'} ${r.label.padEnd(26)} start ${amount(r.start, r.unit).padStart(8)}  peak ${amount(r.peak, r.unit).padStart(8)}  end ${amount(r.end, r.unit).padStart(8)}  steady ${steady}`);
  }
  const drains = report.drains.map((d) => (d.seconds === null ? `${d.clients + d.backends} still open` : `closed in ${d.seconds.toFixed(1)} s`));
  lines.push(`  ${report.drains.some((d) => d.seconds === null) ? '✗' : '✓'} ${'Connections, all left'.padEnd(26)} ${drains.join(', then ') || 'not measured'}`);
  if (report.objects) {
    const [top] = report.objects.classes;
    const largest = top ? `, largest growth ${top.name} +${top.growth}` : '';
    lines.push(`  ${report.objects.status === 'fail' ? '✗' : '✓'} ${'Objects, all left'.padEnd(26)} ${report.objects.before} after the warm-up, ${report.objects.after} at the end${largest} (limit +${report.objects.limit} per class)`);
  }
  for (const p of report.phases) {
    lines.push(`  ${p.name.padEnd(8)} ${clock(p.from)}-${clock(p.to)}  ${p.joins} joins, ${p.switches} switches, ${p.pings} pings, ${p.failures} failures`);
  }
  for (const problem of report.problems) lines.push(`  ✗ ${problem}`);
  return lines.join('\n');
}

/**
 * The soak's section of the run summary (GitHub step summary, summary.md).
 * @param {object} result the version's result
 * @param {object} variant one of its variants, with `soak` (see {@link summarize}), its log failures
 *   and its shutdown
 */
export function soakMarkdown(result, variant) {
  const soak = variant.soak;
  const network = soak.network ? 'degraded network' : 'clean network';
  const lines = [`## Soak: Minecraft ${result.version}, ${variant.name}, ${soak.minutes} min, ${network}`, ''];

  const ops = soak.phases.reduce((sum, p) => ({ joins: sum.joins + p.joins, switches: sum.switches + p.switches, pings: sum.pings + p.pings }), { joins: 0, switches: 0, pings: 0 });
  const failed = soak.failures.length ? `**${soak.failures.length} failed**` : 'none failed';
  const crowd = `${soak.bots} bots for ${clock(soak.seconds)}: ${count(ops.switches)} server switches, ${count(ops.joins)} joins, ${count(ops.pings)} pings, ${failed}.`;
  const logFailures = (variant.failures ?? []).filter((f) => !/did not shut down/.test(f));
  const shutdown = variant.shutdown?.[0];
  const kept = soak.drains.find((d) => d.seconds === null);
  const warp = [
    kept
      ? `**still held ${kept.clients + kept.backends} connection(s) ${DRAIN_TIMEOUT_MS / 1000} s after every player left**`
      : soak.drains.length && 'closed every connection as soon as every player left',
    logFailures.length ? `**logged ${logFailures.length} error(s) or buffer leak(s)**` : 'logged no error or buffer leak',
    shutdown && (shutdown.forced ? '**did not shut down within 20 s** (thread dump in the logs)' : `shut down in ${shutdown.seconds} s`),
  ].filter(Boolean);
  const verdict = variant.status === 'pass' ? '✅ **Passed.**' : '❌ **Failed.**';
  lines.push(`${verdict} ${crowd} Warp ${warp.slice(0, -1).join(', ')} and ${warp.at(-1)}.`, '');

  lines.push('| Resource | Idle at start | Steady state (first → last third) | Peak | Idle at end | Steady growth limit | |');
  lines.push('|---|--:|--:|--:|--:|--:|:-:|');
  for (const r of soak.resources) {
    const steady = r.status === 'skip' ? '_too short_' : `${amount(r.first, r.unit)} → ${amount(r.last, r.unit)} (${signed(r.growth, r.unit)})`;
    const icon = { pass: '✅', fail: '❌', skip: '⏭️' }[r.status];
    lines.push(`| ${r.label} | ${amount(r.start, r.unit)} | ${steady} | ${amount(r.peak, r.unit)} | ${amount(r.end, r.unit)} | ${r.status === 'skip' ? '' : `+${amount(r.limit, r.unit)}`} | ${icon} |`);
  }
  lines.push(`| Heap used | | | ${amount(soak.heap.peak, 'MiB')} of ${amount(soak.heap.max, 'MiB')} | | | |`, '');

  // The two moments every player has left: after the warm-up, and at the end.
  const [intermission, last] = soak.drains;
  const held = (d) => (d ? (d.seconds === null ? `❌ ${d.clients + d.backends}` : d.seconds < 0.1 ? '0' : `0 after ${d.seconds.toFixed(1)} s`) : '');
  const objects = soak.objects;
  lines.push('| Once every player had left | After the warm-up | At the end | Allowed | |', '|---|--:|--:|--:|:-:|');
  lines.push(`| Connections Warp still held | ${held(intermission)} | ${held(last)} | none | ${kept || !soak.drains.length ? '❌' : '✅'} |`);
  if (objects) {
    lines.push(`| Objects of Warp and Netty channels | ${count(objects.before)} | ${count(objects.after)} | +${objects.limit} per class | ${objects.status === 'pass' ? '✅' : '❌'} |`);
  }
  lines.push('');
  if (objects?.classes.length) {
    lines.push(`<details${objects.status === 'fail' ? ' open' : ''}><summary>Classes with more objects at the end than after the warm-up</summary>`, '');
    lines.push('| Class | After the warm-up | At the end | |', '|---|--:|--:|:-:|');
    for (const c of objects.classes) lines.push(`| \`${c.name}\` | ${count(c.before)} | ${count(c.after)} | ${c.growth > objects.limit ? '❌' : '✅'} |`);
    lines.push('', '</details>', '');
  }

  const phases = soak.phases;
  const row = (label, cell) => lines.push(`| ${label} | ${phases.map((p) => cell(p) ?? '').join(' | ')} |`);
  lines.push(`| | ${phases.map((p) => `**${p.name}** (${span(p.to - p.from)})`).join(' | ')} |`);
  lines.push(`|---|${phases.map(() => '--:').join('|')}|`);
  row('Bots online (max)', (p) => p.bots);
  row('Joins (p50 / p95)', (p) => withLatency(p.joins, p.joinMs));
  row('Server switches (p50 / p95)', (p) => withLatency(p.switches, p.switchMs));
  row('Server list pings', (p) => count(p.pings));
  row('Bot failures', (p) => (p.failures ? `❌ ${p.failures}` : '0'));
  row('Resident memory (avg / max)', (p) => `${amount(p.rss.avg, 'MiB')} / ${amount(p.rss.max, 'MiB')}`);
  row('Heap used (max)', (p) => amount(p.heapUsed, 'MiB'));
  row('Live heap (max)', (p) => amount(p.heapLive, 'MiB'));
  row('Direct memory (max)', (p) => amount(p.direct, 'MiB'));
  row('File descriptors (max)', (p) => p.fds);
  row('Threads (max)', (p) => p.threads);
  row('CPU, one core = 100 % (avg)', (p) => (p.cpu === null ? '' : `${Math.round(p.cpu)} %`));
  row('Queued toward the bots (max)', (p) => amount(p.sendQueue, 'KiB'));
  row('Received, not yet read by Warp (max)', (p) => amount(p.receiveQueue, 'KiB'));
  lines.push('');

  if (soak.network) {
    const n = soak.network;
    const loss = n.packets ? ` (${((100 * n.drops) / (n.packets + n.drops)).toFixed(2)} %)` : '';
    lines.push(`**Network:** tc netem \`${n.spec}\` on Warp's ports, both ways: ${count(n.packets)} packets (${amount(n.bytes / 1024 / 1024, 'MiB')}), ${count(n.drops)} dropped${loss}.`, '');
  }
  if (soak.problems.length || logFailures.length) {
    lines.push('<details open><summary><b>Problems</b></summary>', '');
    for (const problem of soak.problems) lines.push(`- ${escape(problem)}`);
    for (const failure of soak.failures.slice(0, 20)) lines.push(`- ${clock(failure.t)} (${failure.phase}) ${escape(describeFailure(failure))}`);
    for (const failure of logFailures.slice(0, 20)) lines.push(`- ${escape(failure.split('\n')[0])}`);
    lines.push('', '</details>', '');
  }
  return `${lines.join('\n')}\n`;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

export function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

function percentile(sorted, q) {
  return sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
}

function amount(value, unit) {
  if (value === null || value === undefined || Number.isNaN(value)) return '';
  const rounded = Math.abs(value) >= 10 || !unit ? Math.round(value) : Math.round(value * 10) / 10;
  return unit ? `${rounded} ${unit}` : String(rounded);
}

const describeFailure = (f) => `${f.op}${f.bot ? ` ${f.bot}` : ''}: ${f.error}`;
const signed = (value, unit) => `${value >= 0 ? '+' : '−'}${amount(Math.abs(value), unit)}`;
const count = (n) => (n ?? 0).toLocaleString('en-US');
const withLatency = (n, ms) => (ms ? `${count(n)} (${ms.p50} / ${ms.p95} ms)` : count(n));
/** Makes text safe in a Markdown table cell or list: backslashes first, then pipes and tags. */
const escape = (text) => String(text).replace(/\\/g, '\\\\').replace(/\|/g, '\\|').replace(/</g, '&lt;').slice(0, 300);
/** A duration: seconds under a minute, else minutes and seconds. */
const span = (seconds) => (seconds < 60 ? `${Math.max(1, Math.round(seconds))} s` : clock(seconds));
const clock = (seconds) => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
const log = (message) => console.log(`[${new Date().toISOString().slice(11, 19)}] ${message}`);
