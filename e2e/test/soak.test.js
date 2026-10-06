// The soak's verdict (src/soak.js): what counts as a leak, and how it is reported. The degraded
// network's specification (src/netem.js) is checked here too.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { parseNetemSpec } from '../src/netem.js';
import { RESOURCES, checkGrowth, csv, retainedObjects, soakMarkdown, summarize, warmUpMs } from '../src/soak.js';

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
    // 40 MiB between the thirds of about 1 GiB: under 5 % of it, though over 32 MiB.
    const samples = series('rss', 1200, (t) => 1000 + (60 * t) / 1200);
    const check = checkGrowth(samples, rss, 0, 1200);

    assert.ok(check.growth > 32 && check.limit > check.growth, `growth ${check.growth}, limit ${check.limit}`);
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

describe('objects left once every player has left', () => {
  const histogram = (entries) => new Map(Object.entries(entries));
  const before = histogram({ 'dev.warp.proxy.WarpServer': 1, 'io.netty.channel.epoll.EpollServerSocketChannel': 1, '[B': 9000 });

  it('passes when Warp is back to what it held before the first player, give or take a cache per player', () => {
    const after = histogram({ 'dev.warp.proxy.WarpServer': 1, 'dev.warp.proxy.server.Registry': 1, 'dev.warp.proxy.auth.Profile': 20, '[B': 12000 });
    const retained = retainedObjects(before, after, 20);

    assert.equal(retained.status, 'pass');
    assert.deepEqual(retained.classes.map((c) => `${c.name} +${c.growth}`), ['dev.warp.proxy.auth.Profile +20', 'dev.warp.proxy.server.Registry +1']);
  });

  it('fails on objects kept per connection: players, channels', () => {
    const after = histogram({
      'dev.warp.proxy.WarpServer': 1,
      'dev.warp.proxy.connection.ConnectedPlayer': 65,
      'io.netty.channel.epoll.EpollSocketChannel': 130,
      'io.netty.channel.epoll.EpollServerSocketChannel': 1,
    });
    const retained = retainedObjects(before, after, 20);

    assert.equal(retained.status, 'fail');
    assert.equal(retained.leaked, 2);
    assert.deepEqual(retained.classes.map((c) => c.name), ['io.netty.channel.epoll.EpollSocketChannel', 'dev.warp.proxy.connection.ConnectedPlayer']);
  });

  it('only tracks Warp and Netty channels, not the libraries Warp relocates', () => {
    const after = histogram({ ...Object.fromEntries(before), 'dev.warp.libs.caffeine.cache.Node': 500, 'java.lang.String': 90000 });

    assert.deepEqual(retainedObjects(before, after, 20), { status: 'pass', limit: 20, before: 1, after: 1, leaked: 0, classes: [] });
  });
});

describe('soak verdict', () => {
  const ctx = { entry: { version: '1.21.4' }, variant: { name: 'online' }, bots: 20, soak: { minutes: 10 } };
  const crowd = { ops: [], failures: [] };
  const samples = series('heapLive', 600, () => 16).map((s) => ({ ...s, phase: s.t < 120 ? 'warm-up' : 'steady' }));
  const idle = new Map([['dev.warp.proxy.WarpServer', 1]]);
  const released = { clients: 0, backends: 0, seconds: 0.1 };
  const verdict = (overrides) =>
    summarize({
      ctx,
      samples,
      crowd,
      timeline: { steadyFrom: 120, drainFrom: 600 },
      drains: [released, released],
      objects: { before: idle, after: idle },
      network: null,
      error: null,
      ...overrides,
    });

  it('passes a Warp that released everything', () => {
    const report = verdict({});

    assert.equal(report.status, 'pass');
    assert.deepEqual(report.problems, []);
    assert.deepEqual(report.phases.map((p) => `${p.name} ${p.from}-${p.to}`), ['warm-up 0-115', 'steady 120-600']);
    assert.equal(report.resources.find((r) => r.key === 'heapLive').status, 'pass');
  });

  it('names the classes Warp kept, the connections it held and the bots that failed', () => {
    const after = new Map([...idle, ['dev.warp.proxy.connection.MinecraftConnection', 220], ['io.netty.channel.epoll.EpollSocketChannel', 220]]);
    const report = verdict({
      objects: { before: idle, after },
      drains: [released, { clients: 0, backends: 3, seconds: null }],
      crowd: { ops: [], failures: [{ t: 42, phase: 'steady', op: 'switch', bot: 'soak_03', error: 'no packet for 31 s' }] },
    });

    assert.equal(report.status, 'fail');
    assert.deepEqual(report.problems, [
      'Warp kept objects of 2 class(es) through the steady phase, more than one per player: dev.warp.proxy.connection.MinecraftConnection +220, io.netty.channel.epoll.EpollSocketChannel +220',
      'Warp still held 0 client and 3 backend connection(s) 30 s after every player left, at the end',
      '1 bot failure(s), first: switch soak_03: no packet for 31 s',
    ]);
  });

  it('skips the trends of a soak that never reached its steady phase', () => {
    const report = verdict({ timeline: { steadyFrom: null, drainFrom: 60 }, drains: [], objects: { before: null, after: null }, error: new Error('Warp exited during the soak') });

    assert.deepEqual(report.problems, ['Warp exited during the soak']);
    assert.ok(report.resources.every((r) => r.status === 'skip'));
    assert.equal(report.objects, null);
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
    drains: [
      { clients: 0, backends: 0, seconds: 0.02 },
      { clients: 0, backends: 0, seconds: 0.4 },
    ],
    objects: { status: 'pass', limit: 20, before: 1669, after: 1672, leaked: 0, classes: [{ name: 'dev.warp.protocol.compress.FrameDecompressor$1', before: 6, after: 8, growth: 2 }] },
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
    assert.match(markdown, /Warp closed every connection as soon as every player left, logged no error or buffer leak and shut down in 1\.1 s\./);
  });

  it('shows what Warp held each time every player had left', () => {
    const markdown = soakMarkdown({ version: '1.21.4' }, variant);

    assert.match(markdown, /^\| Connections Warp still held \| 0 \| 0 after 0\.4 s \| none \| ✅ \|$/m);
    assert.match(markdown, /^\| Objects of Warp and Netty channels \| 1,669 \| 1,672 \| \+20 per class \| ✅ \|$/m);
    assert.match(markdown, /^\| `dev\.warp\.protocol\.compress\.FrameDecompressor\$1` \| 6 \| 8 \| ✅ \|$/m);
  });

  it('tabulates each resource, each phase and the network', () => {
    const markdown = soakMarkdown({ version: '1.21.4' }, variant);

    assert.match(markdown, /\| Resident memory \| 590 MiB \| 612 MiB → 615 MiB \(\+3 MiB\) \| 640 MiB \| 600 MiB \| \+64 MiB \| ✅ \|/);
    assert.match(markdown, /^\| \| \*\*warm-up\*\* \(5:00\) \|$/m);
    assert.match(markdown, /^\| Joins \(p50 \/ p95\) \| 40 \(900 \/ 1500 ms\) \|$/m);
    assert.match(markdown, /^\| Server switches \(p50 \/ p95\) \| 120 \(180 \/ 420 ms\) \|$/m);
    assert.match(markdown, /tc netem `delay 50ms 20ms loss 1%` on Warp's ports, both ways: 990,000 packets \(1907 MiB\), 10,000 dropped \(1\.00 %\)/);
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
