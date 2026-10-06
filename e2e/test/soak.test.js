// The soak's verdict (src/soak.js): what counts as a leak, and how it is reported. The degraded
// network's specification (src/netem.js) is checked here too.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { parseNetemSpec } from '../src/netem.js';
import { RESOURCES, checkGrowth, csv, soakMarkdown, warmUpMs } from '../src/soak.js';

const rss = RESOURCES.find((r) => r.key === 'rss');
const heapLive = RESOURCES.find((r) => r.key === 'heapLive');

/** One sample every 5 s over [0, seconds], the resource at `value(t)`. */
const series = (key, seconds, value) => Array.from({ length: seconds / 5 + 1 }, (_, i) => ({ t: i * 5, [key]: value(i * 5) }));
/** Deterministic noise in [-amplitude, amplitude]. */
const noise = (t, amplitude) => amplitude * Math.sin(t * 12.9898);

describe('leak check', () => {
  it('passes a resource that only fluctuates', () => {
    const samples = series('rss', 1200, (t) => 600 + noise(t, 20));

    assert.equal(checkGrowth(samples, rss, 0, 1200).status, 'pass');
  });

  it('fails a resource that keeps growing past its limit', () => {
    // 20 MiB of live heap over 20 minutes: 13 MiB between the first and last thirds, limit 8.
    const samples = series('heapLive', 1200, (t) => 20 + (20 * t) / 1200 + noise(t, 2));
    const check = checkGrowth(samples, heapLive, 0, 1200);

    assert.equal(check.status, 'fail');
    assert.ok(check.growth > 10 && check.growth < 16, `growth ${check.growth}`);
    assert.equal(check.limit, 8);
  });

  it('passes a pool that grew early and then stayed full', () => {
    const samples = series('heapLive', 1200, (t) => (t < 300 ? 20 : 40));

    assert.equal(checkGrowth(samples, heapLive, 0, 1200).status, 'pass');
  });

  it('scales the limit with the level of the resource', () => {
    const samples = series('rss', 1200, (t) => 1000 + (110 * t) / 1200);
    const check = checkGrowth(samples, rss, 0, 1200);

    assert.ok(check.limit > 100, `limit ${check.limit}`); // 10 % of about 1 GiB, more than 64 MiB
    assert.equal(check.status, 'pass');
  });

  it('only looks at the steady phase, and skips a resource it has too few samples of', () => {
    const samples = series('heapLive', 600, (t) => (t < 120 ? 10 * t : 30));

    assert.equal(checkGrowth(samples, heapLive, 120, 600).status, 'pass');
    assert.equal(checkGrowth(samples.filter((s) => s.t < 400), heapLive, 120, 600).status, 'skip');
  });

  it('warms up for a fifth of the soak, from 1 to 5 minutes, and at most half of it', () => {
    assert.equal(warmUpMs(30 * 60_000), 300_000);
    assert.equal(warmUpMs(10 * 60_000), 120_000);
    assert.equal(warmUpMs(3 * 60_000), 60_000);
    assert.equal(warmUpMs(60_000), 30_000);
  });
});

describe('soak report', () => {
  const soak = {
    bots: 20,
    minutes: 30,
    seconds: 1832,
    status: 'pass',
    problems: [],
    resources: [
      { key: 'rss', label: 'Resident memory', unit: 'MiB', start: 590, end: 600, peak: 640, status: 'pass', first: 612, middle: 613, last: 615, growth: 3, limit: 64 },
      { key: 'threads', label: 'Threads', unit: '', start: 30, end: 50, peak: 60, status: 'pass', first: 52, middle: 52, last: 52, growth: 0, limit: 5.2 },
    ],
    heap: { peak: 210, max: 512 },
    drain: { clients: 0, backends: 0, seconds: 0.4 },
    phases: [
      { name: 'warm-up', from: 0, to: 300, bots: 20, joins: 40, switches: 120, pings: 30, failures: 0, joinMs: { p50: 900, p95: 1500 }, switchMs: { p50: 180, p95: 420 }, rss: { avg: 600, max: 640 }, heapLive: 41, heapUsed: 210, direct: 34, fds: 120, threads: 60, cpu: 35, sendQueue: 96, receiveQueue: 0 },
    ],
    network: { spec: 'delay 50ms 20ms loss 1%', packets: 990_000, bytes: 2_000_000_000, drops: 10_000 },
    failures: [],
  };
  const variant = { name: 'online', status: 'pass', failures: [], shutdown: [{ name: 'warp-online', seconds: 1.1, forced: false }], soak };

  it('opens with the verdict and what the crowd did', () => {
    const markdown = soakMarkdown({ version: '1.21.4' }, variant);

    assert.match(markdown, /^## Soak: Minecraft 1\.21\.4, online, 30 min, degraded network$/m);
    assert.match(markdown, /✅ \*\*Passed\.\*\* 20 bots for 30:32: 120 server switches, 40 joins, 30 pings, none failed\./);
    assert.match(markdown, /closed every connection 0\.4 s after the last player left/);
    assert.match(markdown, /shut down in 1\.1 s/);
  });

  it('tabulates each resource, each phase and the network', () => {
    const markdown = soakMarkdown({ version: '1.21.4' }, variant);

    assert.match(markdown, /\| Resident memory \| 590 MiB \| 612 MiB → 615 MiB \(\+3 MiB\) \| 640 MiB \| 600 MiB \| \+64 MiB \| ✅ \|/);
    assert.match(markdown, /^\| \| \*\*warm-up\*\* \(0:00 to 5:00\) \|$/m);
    assert.match(markdown, /^\| Joins \(p50 \/ p95\) \| 40 \(900 \/ 1500 ms\) \|$/m);
    assert.match(markdown, /^\| Server switches \(p50 \/ p95\) \| 120 \(180 \/ 420 ms\) \|$/m);
    assert.match(markdown, /tc netem `delay 50ms 20ms loss 1%` on Warp's port, both ways: 990,000 packets \(1907 MiB\), 10,000 dropped \(1\.00 %\)/);
  });

  it('lists what failed', () => {
    const failed = {
      ...variant,
      status: 'fail',
      failures: ['warp-online: 2026-10-06 03:00:00.000 [loop] ERROR io.netty.util.ResourceLeakDetector - LEAK: ByteBuf.release() was not called'],
      soak: { ...soak, status: 'fail', problems: ['Live heap (after full GC) grew by 13 MiB over the steady phase (limit 8 MiB)'] },
    };
    const markdown = soakMarkdown({ version: '1.21.4' }, failed);

    assert.match(markdown, /❌ \*\*Failed\.\*\*/);
    assert.match(markdown, /logged 1 error\(s\) or buffer leak\(s\)/);
    assert.match(markdown, /- Live heap \(after full GC\) grew by 13 MiB/);
    assert.match(markdown, /- warp-online: .* LEAK: ByteBuf\.release\(\) was not called/);
  });

  it('writes the time series as CSV, empty where a sample has no value', () => {
    const text = csv([{ t: 0, phase: 'warm-up', bots: 0, rss: 590, heapLive: 12, fds: 45 }, { t: 5, phase: 'warm-up', bots: 3, rss: 595, heapLive: null, fds: 51 }]);
    const [header, first, second] = text.trim().split('\n');

    assert.ok(header.startsWith('time_s,phase,bots,rss_mib,heap_used_mib,heap_committed_mib,heap_live_mib,direct_mib'), header);
    assert.equal(first, '0,warm-up,0,590,,,12,,,45,,,,,,,');
    assert.equal(second, '5,warm-up,3,595,,,,,,51,,,,,,,');
  });
});

describe('netem specification', () => {
  it('splits a specification into tc arguments', () => {
    assert.deepEqual(parseNetemSpec(' delay 50ms 20ms distribution normal  loss 1% '), ['delay', '50ms', '20ms', 'distribution', 'normal', 'loss', '1%']);
  });

  it('refuses anything but plain words, and corruption', () => {
    assert.throws(() => parseNetemSpec(''), /needs a specification/);
    assert.throws(() => parseNetemSpec('delay 50ms; reboot'), /unexpected "50ms;"/);
    assert.throws(() => parseNetemSpec('corrupt 1%'), /corrupt is not supported/);
  });
});
