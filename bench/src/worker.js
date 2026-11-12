// One load-generator process of the fleet. It holds a single pool at a time (raw idle clients, raw
// movers or mineflayer switch bots) and obeys the orchestrator over the IPC channel: every message
// `{id, cmd, args}` is answered by `{id, ok, result}` or `{id, ok: false, error}`. It measures how
// late its own event loop runs, because a generator that falls behind offers less load than was
// asked and blurs every time it takes; the orchestrator reads that with each `status`.
import { monitorEventLoopDelay } from 'node:perf_hooks';

import { flood, joinOnce, probeLatency, RawPool, SwitchPool } from './load.js';

const lag = monitorEventLoopDelay({ resolution: 10 });
lag.enable();
let pool = null;

function readLag() {
  // The histogram's resolution is its own timer period: a perfectly idle loop reads about 10 ms.
  const result = { lagP99Ms: Math.round(lag.percentile(99) / 1e4) / 100, lagMaxMs: Math.round(lag.max / 1e4) / 100 };
  lag.reset();
  return result;
}

const handlers = {
  async fill({ kind, port, version, servers, names, indexes, ratePerSec }) {
    if (!pool) pool = kind === 'bot' ? new SwitchPool({ port, version, servers }) : new RawPool({ port, movers: kind === 'mover' });
    await pool.fill(names, indexes, ratePerSec);
    return pool.status();
  },
  async status() {
    const empty = { online: 0, failed: 0, failureSamples: [], bytesIn: 0, frames: 0, compressedFrames: 0, teleports: 0, kicked: 0 };
    return { ...(pool ? pool.status() : empty), ...readLag() };
  },
  async switchStorm({ durationMs, pauseMs }) {
    return pool.storm(durationMs, pauseMs);
  },
  async drain() {
    if (pool) await pool.drain();
    pool = null;
    return {};
  },
  async probe(args) {
    lag.reset();
    const result = await probeLatency(args);
    return { ...result, ...readLag() };
  },
  flood,
  join: joinOnce,
};

process.on('message', async ({ id, cmd, args }) => {
  try {
    const handler = handlers[cmd];
    if (!handler) throw new Error(`unknown command: ${cmd}`);
    process.send({ id, ok: true, result: await handler(args ?? {}) });
  } catch (e) {
    process.send({ id, ok: false, error: e.stack ?? e.message });
  }
});
// The orchestrator is gone: nothing is left to drive this process.
process.on('disconnect', () => process.exit(0));
