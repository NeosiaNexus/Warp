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
  it('should not pin when there are too few cores', () => {
    const plan = cpuPlan(4, true);
    assert.equal(plan.proxy, null);
    assert.match(plan.note, /too few/);
  });

  it('should split cores into proxy, backends and bots when enabled', () => {
    const plan = cpuPlan(16, true);
    assert.ok(plan.proxy.length >= 2);
    assert.ok(plan.backends.length >= 2);
    assert.ok(plan.bots.length >= 1);
    // No core appears in two groups.
    const all = [...plan.proxy, ...plan.backends, ...plan.bots];
    assert.equal(new Set(all).size, all.length);
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
        idle: { levels: [{ level: 50, rssMiB: 120, fds: 80, cpuPct: 2, failures: 0 }] },
        traffic: { online: 10, bytesPerSec: 2_000_000, cpuPct: 30, cpuPerMb: 0.012, rssMiB: 200, chunks: 400 },
        latency: { viaProxy: { p50: 3, p99: 8, n: 20, timeouts: 1 }, direct: { p50: 2, p99: 5, n: 20, timeouts: 0 }, added: { p50: 1, p99: 3 }, warmup: 5, timeoutMs: 8000 },
        switch: { online: 5, switches: 40, switchesPerSec: 2.6, failures: 0, p50Ms: 120, p99Ms: 300 },
        storm: { rates: [{ rate: 500, opened: 2000, joined: true, cpuPct: 26, cpuPeakPct: 100, rssMiB: 210 }] },
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

  it('should render every scenario section and flag a WSL run', () => {
    const md = buildReport({ results, env, versions, profileName: 'smoke', date: '2026-01-01', cpuNote: 'pinning off' });
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
    const md = buildReport({ results, env, versions, profileName: 'smoke', date: '2026-01-01', cpuNote: 'pinning off' });
    assert.match(md, /\*\*Gate\*\*.*natives none to check/);
    assert.doesNotMatch(md, /\*\*Gate\*\*.*natives confirmed/);
  });

  it('should describe the probe as a tab-complete round-trip and show n and timeouts', () => {
    const md = buildReport({ results, env, versions, profileName: 'smoke', date: '2026-01-01', cpuNote: 'pinning off' });
    assert.match(md, /Tab-complete round-trip/);
    assert.doesNotMatch(md, /Chat round-trip/);
    assert.match(md, /discards 5 warm-up rounds/);
    assert.match(md, /\| Warp \| 3\.0 ms \| 8\.0 ms \| 2\.0 ms \| 1\.0 ms \| 3\.0 ms \| 20 \| 1 \/ 0 \|/);
  });

  it('should report the storm peak from the per-second maximum, next to the median', () => {
    const md = buildReport({ results, env, versions, profileName: 'smoke', date: '2026-01-01', cpuNote: 'pinning off' });
    assert.match(md, /\| median CPU % \| peak CPU % \|/);
    assert.match(md, /\| Warp \| yes \| 26 \| 100 \|/);
  });

  it('should describe throughput as bytes written, not read\+write', () => {
    const md = buildReport({ results, env, versions, profileName: 'smoke', date: '2026-01-01', cpuNote: 'pinning off' });
    assert.match(md, /bytes written per second/);
    assert.doesNotMatch(md, /read\+write/);
  });
});
