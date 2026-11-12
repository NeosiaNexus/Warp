// The report generator and the statistics it rests on, from fixed inputs.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { percentile, median } from '../src/stats.js';
import { buildReport } from '../src/report.js';
import { cpuPlan } from '../src/env.js';
import { toCsv, COLUMNS } from '../src/sample.js';

describe('statistics', () => {
  it('should compute percentiles and median', () => {
    const xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    assert.equal(median(xs), 5);
    assert.equal(percentile(xs, 99), 10);
    assert.equal(percentile([], 50), null);
  });
});

describe('cpuPlan', () => {
  it('should not pin when there are too few cores, and still plan load workers', () => {
    const plan = cpuPlan(4, true);
    assert.equal(plan.proxy, null);
    assert.equal(plan.probe, null);
    assert.deepEqual(plan.workers, [null, null]);
    assert.match(plan.note, /too few/);
  });

  for (const cores of [6, 16, 32, 48]) {
    it(`should give every core to exactly one group on ${cores} cores`, () => {
      const plan = cpuPlan(cores, true);
      const all = [...plan.proxy, ...plan.backends, ...plan.bots];
      assert.equal(all.length, cores);
      assert.equal(new Set(all).size, cores);
      assert.ok(plan.proxy.length >= 2);
      // The backends get the largest share: they must never be the bottleneck.
      assert.ok(plan.backends.length >= plan.proxy.length);
      // The probe has a core no load worker uses.
      assert.ok(!plan.workers.includes(plan.probe));
      assert.deepEqual([...plan.workers, plan.probe], plan.bots);
    });
  }

  it('should scale with the machine instead of assuming 16 cores', () => {
    assert.equal(cpuPlan(16, true).backends.length, 8);
    assert.equal(cpuPlan(48, true).proxy.length, 12);
    assert.equal(cpuPlan(48, true).backends.length, 24);
    assert.match(cpuPlan(32, true).note, /proxy on 0-7, backends on 8-23/);
  });

  it('should not pin when disabled', () => {
    assert.equal(cpuPlan(16, false).proxy, null);
  });
});

describe('toCsv', () => {
  it('should write the header and one row per sample', () => {
    const rows = [{ t: 1, proxy: 'warp', scenario: 'idle', phase: 'measure', offered: 50, bots: 50, rssMiB: 120, threads: 40, fds: 80, cpuPct: 5, clients: 50, backendConns: 0, readBytes: 10, writeBytes: 20 }];
    const csv = toCsv(rows);
    const lines = csv.trim().split('\n');
    assert.equal(lines[0], COLUMNS.join(','));
    assert.equal(lines.length, 2);
    assert.match(lines[1], /^1,warp,idle,measure,50/);
  });
});

describe('buildReport', () => {
  const env = { isWsl: true, date: 'x', cpuModel: 'test', cpuCount: 10 };
  const versions = {
    backend: { version: '1.21.4', build: 232, sha256: 'a'.repeat(64) },
    proxies: {
      warp: { label: 'Warp', version: '0.1.0', sha256: 'b'.repeat(64) },
      velocity: { label: 'Velocity', version: '3.4.0', build: 566, sha256: 'c'.repeat(64) },
    },
  };
  const valid = { valid: true, reasons: [] };
  const results = [
    {
      id: 'warp',
      label: 'Warp',
      forwarding: 'modern',
      natives: { ok: true, notes: ['compression: libdeflate'] },
      errors: [],
      proxyFailures: [],
      rows: [{ t: 1, scenario: 'traffic', cpuPct: 10 }],
      metrics: {
        idle: {
          levels: [
            { level: 1000, online: 1000, rssMiB: 2300, heapMiB: 40, fds: 2100, cpuPct: 2, failures: 0, validity: valid },
            { level: 5000, online: 5000, rssMiB: 2310, heapMiB: 80, fds: 10100, cpuPct: 3, failures: 0, validity: valid },
            { level: 10000, online: 4300, rssMiB: 2320, heapMiB: 90, fds: 9000, cpuPct: 3, failures: 5700, validity: { valid: false, reasons: ['only 43% of the 10000 offered connections were established (4300)'] } },
          ],
        },
        traffic: { bots: 10, online: 10, bytesPerSec: 2_000_000, deliveredBytesPerSec: 1_900_000, cpuPct: 30, cpuPerMb: 0.012, rssMiB: 200, heapMiB: 50, backendTps: 20, validity: valid },
        latency: {
          viaProxy: { p50: 0.31, p99: 0.82, n: 20, timeouts: 1 },
          direct: { p50: 0.2, p99: 0.5, n: 20, timeouts: 0 },
          added: { p50: 0.11, p99: 0.32 },
          warmup: 5,
          timeoutMs: 2000,
          backgroundBots: 5,
          validity: valid,
        },
        switch: { bots: 5, online: 5, switches: 40, switchesPerSec: 2.6, failures: 0, firstChunkP50Ms: 80, firstChunkP99Ms: 150, p50Ms: 120, p99Ms: 300, backends: [{ tps: 20 }, { tps: 19.8 }], validity: valid },
        storm: { rates: [{ rate: 500, opened: 2000, joined: true, cpuPct: 26, cpuPeakPct: 100, rssMiB: 210, validity: valid }] },
      },
    },
    {
      id: 'velocity',
      label: 'Velocity',
      forwarding: 'modern',
      natives: { ok: true, notes: [] },
      errors: [],
      proxyFailures: [],
      rows: [],
      metrics: {
        traffic: { bots: 10, online: 4, bytesPerSec: 9_000_000, cpuPct: 99, cpuPerMb: 0.777, rssMiB: 200, validity: { valid: false, reasons: ['only 40% of the 10 offered connections were established (4)', 'backend lobby below 19 TPS (12.3)'] } },
      },
    },
    {
      id: 'gate',
      label: 'Gate',
      forwarding: 'modern',
      natives: { ok: true, checked: false, notes: ['listening: listening on :25565'] },
      errors: [],
      proxyFailures: [],
      rows: [],
      metrics: {},
    },
  ];
  const report = (extra = {}) => buildReport({ results, env, versions, profileName: 'smoke', date: '2026-01-01', cpuNote: 'pinning off', ...extra });

  it('should render every scenario section and flag a WSL run', () => {
    const md = report();
    assert.match(md, /# Warp macro benchmark/);
    assert.match(md, /WSL2/);
    assert.match(md, /Idle connections/);
    assert.match(md, /Heavy traffic/);
    assert.match(md, /Added latency/);
    assert.match(md, /Switch storm/);
    assert.match(md, /Connection \/ status storm/);
    assert.match(md, /\*\*Warp\*\*.*natives confirmed/);
    assert.match(md, /Paper 1\.21\.4/);
    assert.match(md, /<svg/); // at least one chart
  });

  it('should say a Go proxy has no natives to check rather than "confirmed"', () => {
    const md = report();
    assert.match(md, /\*\*Gate\*\*.*natives none to check/);
    assert.doesNotMatch(md, /\*\*Gate\*\*.*natives confirmed/);
  });

  it('should print INVALID with the reason instead of the figures of a rejected measurement', () => {
    const md = report();
    assert.match(md, /\| Warp \| 10000 \| 4300 \| \*\*INVALID\*\* \|/);
    assert.match(md, /\*\*Warp @ 10000\*\*: only 43% of the 10000 offered connections were established/);
    assert.match(md, /\| Velocity \| 10 \| 4 \| \*\*INVALID\*\* \|/);
    assert.match(md, /\*\*Velocity\*\*: only 40% of the 10 offered connections were established \(4\); backend lobby below 19 TPS \(12\.3\)/);
    // The rejected run's CPU-per-MB and throughput must not appear anywhere as a figure.
    assert.doesNotMatch(md, /0\.777/);
    assert.doesNotMatch(md, /8\.6 MiB\/s/);
  });

  it('should show the offered and the connected count of every valid row', () => {
    const md = report();
    assert.match(md, /\| Warp \| 5000 \| 5000 \| 2310 MiB \| 80 MiB \| 10100 \| 3 \|/);
    assert.match(md, /\| Warp \| 10 \| 10 \| 1\.9 MiB\/s \| 1\.8 MiB\/s \| 30 \| 0\.012 \| 200 MiB \| 50 MiB \| 20\.0 \|/);
  });

  it('should give memory per connection as the slope between the valid levels only', () => {
    const md = report();
    // RSS: (2310 - 2300) MiB over 4000 connections = 2.6 KiB; heap: 40 MiB over 4000 = 10.2 KiB.
    assert.match(md, /\| Warp \| 1000 to 5000 \| 2\.6 KiB \| 10\.2 KiB \|/);
  });

  it('should describe the probe as a ping round trip in fractional milliseconds', () => {
    const md = report();
    assert.match(md, /PLAY ping request/);
    assert.match(md, /discards 5 warm-up rounds/);
    assert.match(md, /\| Warp \| 0\.310 ms \| 0\.820 ms \| 0\.200 ms \| 0\.500 ms \| 0\.110 ms \| 0\.320 ms \| 20 \| 1 \/ 0 \|/);
  });

  it('should report the storm peak from the per-second maximum, next to the median', () => {
    const md = report();
    assert.match(md, /\| median CPU % \| peak CPU % \|/);
    assert.match(md, /\| Warp \| yes \| 26 \| 100 \|/);
  });

  it('should describe throughput as bytes written, not read\+write', () => {
    const md = report();
    assert.match(md, /bytes written per second/);
    assert.doesNotMatch(md, /read\+write/);
  });

  it('should say that a smoke run makes no performance claim', () => {
    assert.match(report(), /Smoke profile: .*not benchmark results/);
    assert.doesNotMatch(report({ profileName: 'full' }), /Smoke profile/);
  });

  it('should say which proxies are missing from a partial report', () => {
    assert.match(report({ pending: ['bungeecord'] }), /Partial report: bungeecord had not finished/);
    assert.doesNotMatch(report(), /Partial report/);
  });
});
