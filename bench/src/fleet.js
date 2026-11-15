// The orchestrator's side of the load generators: a fleet of worker processes (worker.js), each
// pinned to its own core when pinning is on, plus one more that only ever runs the latency probe and
// the storm's genuine player, so that what it times is never delayed by other clients sharing its
// event loop. The fleet splits a requested load evenly over the workers and adds their answers up.
import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const WORKER = join(dirname(fileURLToPath(import.meta.url)), 'worker.js');
const live = new Set();

class Worker {
  constructor(cpu) {
    const [command, args] = cpu === null ? [process.execPath, [WORKER]] : ['taskset', ['-c', cpu, process.execPath, WORKER]];
    this.child = spawn(command, args, { stdio: ['ignore', 'inherit', 'inherit', 'ipc'] });
    this.pending = new Map();
    this.nextId = 1;
    this.exited = false;
    this.child.on('message', ({ id, ok, result, error }) => {
      const waiter = this.pending.get(id);
      if (!waiter) return;
      this.pending.delete(id);
      if (ok) waiter.resolve(result);
      else waiter.reject(new Error(error));
    });
    this.child.on('exit', (code, signal) => {
      this.exited = true;
      live.delete(this);
      for (const waiter of this.pending.values()) waiter.reject(new Error(`load worker exited (${code ?? signal})`));
      this.pending.clear();
    });
    live.add(this);
  }

  call(cmd, args) {
    if (this.exited) return Promise.reject(new Error('load worker is not running'));
    return new Promise((resolve, reject) => {
      const id = this.nextId++;
      this.pending.set(id, { resolve, reject });
      this.child.send({ id, cmd, args });
    });
  }

  stop() {
    if (!this.exited) this.child.kill('SIGKILL');
  }
}

/**
 * Splits `count` clients over `parts` workers: client `i` goes to worker `i % parts`.
 * @returns {number[][]} for each worker, the fleet-wide indexes of its clients
 */
export function split(count, parts) {
  const shares = Array.from({ length: parts }, () => []);
  for (let i = 0; i < count; i++) shares[i % parts].push(i);
  return shares;
}

/** Adds up the workers' status answers; lag is the worst of them. */
export function mergeStatus(statuses) {
  const total = { online: 0, failed: 0, failureSamples: [], bytesIn: 0, frames: 0, compressedFrames: 0, teleports: 0, kicked: 0, lagP99Ms: 0, lagMaxMs: 0 };
  for (const s of statuses) {
    for (const key of ['online', 'failed', 'bytesIn', 'frames', 'compressedFrames', 'teleports', 'kicked']) total[key] += s[key] ?? 0;
    total.failureSamples.push(...(s.failureSamples ?? []));
    total.lagP99Ms = Math.max(total.lagP99Ms, s.lagP99Ms ?? 0);
    total.lagMaxMs = Math.max(total.lagMaxMs, s.lagMaxMs ?? 0);
  }
  total.failureSamples = total.failureSamples.slice(0, 3);
  return total;
}

export class Fleet {
  /**
   * @param {object} options
   * @param {(string|null)[]} options.workerCpus one entry per load worker: the core it is pinned to, or null
   * @param {string|null} options.probeCpu the core of the probe worker, or null
   */
  constructor({ workerCpus, probeCpu }) {
    this.workers = workerCpus.map((cpu) => new Worker(cpu));
    this.prober = new Worker(probeCpu);
  }

  /**
   * Ramps `count` clients in at `ratePerSec` overall and resolves once each is in PLAY or failed.
   * @param {'idle'|'mover'|'bot'} kind raw idle clients, raw movers, or mineflayer bots
   * @returns {Promise<object>} the merged status
   */
  async fill(kind, count, ratePerSec, { port, version, servers, prefix }) {
    const shares = split(count, this.workers.length);
    await Promise.all(
      this.workers.map((worker, w) =>
        shares[w].length
          ? worker.call('fill', {
              kind,
              port,
              version,
              servers,
              indexes: shares[w],
              names: shares[w].map((i) => `${prefix}_${i}`),
              ratePerSec: ratePerSec / this.workers.length,
            })
          : null,
      ),
    );
    return this.status();
  }

  /** The merged status of every worker; reading it resets the workers' event-loop lag. */
  async status() {
    return mergeStatus(await Promise.all(this.workers.map((w) => w.call('status'))));
  }

  async switchStorm(durationMs, pauseMs) {
    const parts = await Promise.all(this.workers.map((w) => w.call('status').then((s) => (s.online ? w.call('switchStorm', { durationMs, pauseMs }) : null))));
    const merged = { switches: 0, failures: 0, latencies: [], firstChunkLatencies: [] };
    for (const part of parts.filter(Boolean)) {
      merged.switches += part.switches;
      merged.failures += part.failures;
      merged.latencies.push(...part.latencies);
      merged.firstChunkLatencies.push(...part.firstChunkLatencies);
    }
    return merged;
  }

  /** Opens hostile connections at `ratePerSec` overall for `durationMs`. */
  async flood({ port, ratePerSec, durationMs }) {
    const parts = await Promise.all(this.workers.map((w) => w.call('flood', { port, ratePerSec: ratePerSec / this.workers.length, durationMs })));
    return { opened: parts.reduce((s, p) => s + p.opened, 0), attempted: parts.reduce((s, p) => s + p.attempted, 0) };
  }

  drain() {
    return Promise.all(this.workers.map((w) => w.call('drain')));
  }

  probe(args) {
    return this.prober.call('probe', args);
  }

  join(args) {
    return this.prober.call('join', args);
  }

  stop() {
    for (const w of [...this.workers, this.prober]) w.stop();
  }
}

/** Kills every load worker still running; used on interruption and as a last-resort cleanup. */
export function killFleets() {
  for (const worker of live) worker.stop();
}
