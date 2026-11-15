// The latency probe's clock: warm-up discarded, timeouts counted at the timeout, join burst settled.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { probeRounds, settle } from '../src/probe.js';

/** A fake clock: sleep advances it, nothing else does. */
function clock() {
  let t = 0;
  return { now: () => t, sleep: async (ms) => { t += ms; }, advance: (ms) => { t += ms; } };
}

describe('probeRounds', () => {
  it('should discard the warm-up rounds and keep the samples', async () => {
    const c = clock();
    let i = 0;
    const round = async () => { c.advance(i++ < 3 ? 500 : 5); };
    const r = await probeRounds({ round, samples: 4, warmup: 3, intervalMs: 100, timeoutMs: 1000, sleep: c.sleep, now: c.now });
    assert.equal(r.latencies.length, 4);
    assert.equal(r.warmup, 3);
    assert.ok(r.latencies.every((l) => l <= 5), `early stalls must not reach the counted rounds: ${r.latencies}`);
  });

  it('should count a failed or timed-out round at the timeout instead of dropping it', async () => {
    const c = clock();
    let i = 0;
    const round = async () => { if (i++ === 1) throw new Error('timeout'); c.advance(2); };
    const r = await probeRounds({ round, samples: 3, warmup: 0, intervalMs: 50, timeoutMs: 800, sleep: c.sleep, now: c.now });
    assert.deepEqual(r.latencies, [2, 800, 2]);
    assert.equal(r.timeouts, 1);
  });

  it('should measure from the intended send time, not the actual one', async () => {
    const c = clock();
    let i = 0;
    // The first round stalls for three intervals; the next rounds start late through no fault of
    // their own and the stall is charged to them (coordinated-omission guard), capped at the timeout.
    const round = async () => { c.advance(i++ === 0 ? 300 : 1); };
    const r = await probeRounds({ round, samples: 3, warmup: 0, intervalMs: 100, timeoutMs: 250, sleep: c.sleep, now: c.now });
    assert.deepEqual(r.latencies, [250, 201, 102]);
    assert.equal(r.timeouts, 0);
  });
});

describe('settle', () => {
  it('should resolve true once the counter has been quiet long enough', async () => {
    const c = clock();
    let chunks = 0;
    const read = () => (c.now() < 1000 ? ++chunks : chunks);
    assert.equal(await settle(read, { quietMs: 500, maxMs: 10_000, sleep: c.sleep, now: c.now }), true);
    assert.ok(c.now() >= 1400 && c.now() < 2000, `settled at ${c.now()}`);
  });

  it('should give up at the deadline while the counter keeps moving', async () => {
    const c = clock();
    let chunks = 0;
    assert.equal(await settle(() => ++chunks, { quietMs: 500, maxMs: 3000, sleep: c.sleep, now: c.now }), false);
    assert.equal(c.now(), 3000);
  });
});
