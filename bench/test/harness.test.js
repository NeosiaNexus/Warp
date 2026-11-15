// The pieces around the scenarios: splitting load over workers, mover paths, profile overrides,
// reading the JVM's heap and the backend's tick health, and rebuilding a report from a run's files.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { parseTickHealth, Backend } from '../src/backend.js';
import { ephemeralConflicts } from '../src/env.js';
import { mergeStatus, split } from '../src/fleet.js';
import { parseHeapInfo, slope } from '../src/heap.js';
import { moverPath } from '../src/load.js';
import { applyOverrides, FULL, SMOKE } from '../src/profiles.js';
import { RawClient, MOVER_SPEED, MOVER_TICKS_PER_SEC } from '../src/rawclient.js';
import { toCsv } from '../src/sample.js';
import { loadRun, parseCsv, saveResult, saveRun, writeReport } from '../src/store.js';

describe('fleet', () => {
  it('should split clients evenly, each client to exactly one worker', () => {
    const shares = split(10, 3);
    assert.deepEqual(shares, [[0, 3, 6, 9], [1, 4, 7], [2, 5, 8]]);
    assert.deepEqual(split(2, 4).map((s) => s.length), [1, 1, 0, 0]);
  });

  it('should add the workers up and keep the worst event-loop lag', () => {
    const merged = mergeStatus([
      { online: 100, failed: 1, failureSamples: ['a'], bytesIn: 10, frames: 5, compressedFrames: 2, teleports: 1, kicked: 0, lagP99Ms: 11, lagMaxMs: 20 },
      { online: 99, failed: 0, failureSamples: [], bytesIn: 30, frames: 7, compressedFrames: 3, teleports: 1, kicked: 1, lagP99Ms: 80, lagMaxMs: 300 },
    ]);
    assert.equal(merged.online, 199);
    assert.equal(merged.failed, 1);
    assert.equal(merged.bytesIn, 40);
    assert.equal(merged.lagP99Ms, 80);
    assert.deepEqual(merged.failureSamples, ['a']);
  });
});

describe('movers', () => {
  it('should give consecutive movers different headings and leg lengths', () => {
    const paths = [0, 1, 2, 3].map(moverPath);
    assert.equal(new Set(paths.map((p) => p.heading.toFixed(3))).size, 4);
    assert.equal(new Set(paths.map((p) => p.max)).size, 4);
    assert.ok(paths.every((p) => p.min < p.max));
  });

  it('should walk at sprint speed along its line and turn round at both ends', () => {
    const client = new RawClient({ host: 'x', port: 1, username: 'm', path: { heading: 0, min: 2, max: 4 } });
    const sent = [];
    client.send = (id, body) => sent.push({ id, x: body.readDoubleBE(0), y: body.readDoubleBE(8), onGround: body[24] });
    client.tick();
    assert.equal(sent.length, 0, 'a mover the server has not placed does not move');
    client.state = 2; // PLAY
    client.position = { x: 100, y: -60, z: 0 };
    const step = MOVER_SPEED / MOVER_TICKS_PER_SEC;
    const xs = [];
    for (let i = 0; i < 60; i++) {
      client.tick();
      xs.push(sent.at(-1).x);
    }
    assert.ok(Math.abs(xs[0] - (100 + step)) < 1e-9);
    assert.ok(Math.max(...xs) <= 104 + step && Math.max(...xs) >= 104, 'turns at the far end');
    assert.ok(Math.min(...xs.slice(20)) >= 102 - step, 'and at the near end');
    assert.ok(sent.every((p) => p.y === -60 && p.onGround === 1));
  });
});

describe('profile overrides', () => {
  it('should override a list and a number without touching the profile itself', () => {
    const p = applyOverrides(FULL, ['idle.levels=1000,2000', 'traffic.bots=150']);
    assert.deepEqual(p.idle.levels, [1000, 2000]);
    assert.equal(p.traffic.bots, 150);
    assert.notDeepEqual(FULL.idle.levels, [1000, 2000]);
    assert.deepEqual(applyOverrides(SMOKE, ['idle.levels=20']).idle.levels, [20]);
  });

  it('should reject an unknown scenario, key or value', () => {
    assert.throws(() => applyOverrides(FULL, ['nope.bots=1']), /invalid --set/);
    assert.throws(() => applyOverrides(FULL, ['traffic.nope=1']), /invalid --set/);
    assert.throws(() => applyOverrides(FULL, ['traffic.bots=many']), /invalid --set/);
  });
});

describe('JVM heap', () => {
  it('should read the used heap from jcmd GC.heap_info (JDK 25 and older layouts)', () => {
    const jdk25 = ' garbage-first heap   total reserved 2097152K, committed 2097152K, used 34816K [0x0000000080000000, 0x0000000100000000)\n  region size 1024K, 3 young (3072K), 0 survivors (0K)\n Metaspace       used 20110K, committed 20480K, reserved 1114112K';
    assert.deepEqual(parseHeapInfo(jdk25), { usedMiB: 34, committedMiB: 2048 });
    assert.deepEqual(parseHeapInfo(' garbage-first heap   total 2097152K, used 10240K [0x...'), { usedMiB: 10, committedMiB: null });
    assert.equal(parseHeapInfo('no such process'), null);
  });

  it('should give the cost of one more connection as the slope between the extreme levels', () => {
    assert.equal(slope([{ x: 5000, y: 90 }, { x: 1000, y: 50 }, { x: 2000, y: 70 }]), 0.01);
    assert.equal(slope([{ x: 1000, y: 50 }]), null);
    assert.equal(slope([{ x: 1000, y: null }, { x: 5000, y: 80 }]), null);
  });
});

describe('Paper backend', () => {
  it('should read ticks per second and tick times from the console answers', () => {
    const health = parseTickHealth([
      '[21:10:01 INFO]: TPS from last 1m, 5m, 15m: *20.0, 19.97, 19.9',
      '[21:10:01 INFO]: Server tick times (avg/min/max) from last 5s, 10s, 1m:',
      '[21:10:01 INFO]: ◴ 3.1/1.2/9.0, 4.5/1.1/31.2, 4.0/1.0/48.9',
    ]);
    assert.deepEqual(health, { tps: 20, msptAvg: 4.5, msptMax: 31.2 });
    assert.deepEqual(parseTickHealth(['nothing']), { tps: null, msptAvg: null, msptMax: null });
  });

  it('should not save player data, bound the tracking range, and forward only when asked', () => {
    const yml = (forwarding) => new Backend({ forwarding }).spigotYml();
    assert.match(yml('legacy'), /bungeecord: true/);
    assert.match(yml('modern'), /bungeecord: false/);
    assert.match(yml('none'), /disable-saving: true/);
    assert.match(yml('none'), /entity-tracking-range:\n\s+players: 32/);
  });

  it('should never let a backend look offline players up at Mojang', () => {
    const yml = (forwarding) => new Backend({ forwarding, secret: 'S' }).paperGlobalYml();
    assert.match(yml('legacy'), /bungee-cord:\n\s+online-mode: false/);
    assert.match(yml('modern'), /velocity:\n\s+enabled: true\n\s+online-mode: false\n\s+secret: 'S'/);
    assert.equal(yml('none'), null);
  });
});

describe('results on disk', () => {
  const run = {
    date: '2026-01-01',
    profileName: 'smoke',
    profile: SMOKE,
    proxies: ['warp', 'velocity'],
    cpuNote: 'pinning off',
    versions: { backend: { version: '1.21.4', build: 232, sha256: 'a'.repeat(64) }, proxies: { warp: { label: 'Warp', version: '1', sha256: 'b'.repeat(64) } } },
  };
  const rows = [{ t: 1.5, proxy: 'warp', scenario: 'traffic', phase: 'measure', offered: 10, bots: 10, rssMiB: 200.5, threads: 30, fds: 50, cpuPct: 12, clients: 10, backendConns: 10, readBytes: 0, writeBytes: 4096 }];
  const result = {
    id: 'warp',
    label: 'Warp',
    forwarding: 'modern',
    natives: { ok: true, checked: true, notes: [] },
    errors: [],
    proxyFailures: [],
    metrics: { traffic: { bots: 10, online: 10, bytesPerSec: 4096, deliveredBytesPerSec: 4000, cpuPct: 12, cpuPerMb: 0.03, rssMiB: 200, heapMiB: 30, backendTps: 20, validity: { valid: true, reasons: [] } } },
    rows,
    csv: toCsv(rows),
  };

  it('should read the sampler CSV back as the rows it was written from', () => {
    assert.deepEqual(parseCsv(toCsv(rows)), rows);
  });

  it('should rebuild the report from the files alone, for the proxies that finished', () => {
    const dir = mkdtempSync(join(tmpdir(), 'bench-store-'));
    try {
      saveRun(dir, run);
      writeFileSync(join(dir, 'env.json'), JSON.stringify({ isWsl: false, cpuCount: 16 }));
      saveResult(dir, result);
      const loaded = loadRun(dir);
      assert.equal(loaded.results.length, 1);
      assert.deepEqual(loaded.results[0].rows, rows);
      assert.equal(loaded.results[0].csv, undefined, 'the CSV text is not duplicated in the metrics file');
      assert.equal(writeReport(dir).length, 1);
      const md = readFileSync(join(dir, 'README.md'), 'utf8');
      assert.match(md, /\| Warp \| 10 \| 10 \| 4 KiB\/s/);
      assert.match(md, /Partial report: velocity had not finished/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it('should refuse a directory that is not a run of this harness', () => {
    assert.throws(() => loadRun(tmpdir()), /no run\.json/);
  });
});

describe('listening ports and the ephemeral range', () => {
  const ports = [25565, 25700, 25720];

  it('should find nothing wrong with the default range', () => {
    assert.deepEqual(ephemeralConflicts(ports, '32768\t60999', ''), []);
  });

  it('should name the ports a widened range can hand out to an outgoing connection', () => {
    assert.deepEqual(ephemeralConflicts(ports, '1024\t65535', ''), ports);
    assert.deepEqual(ephemeralConflicts(ports, '1024\t65535', '25565,25700-25710'), [25720]);
    assert.deepEqual(ephemeralConflicts(ports, '1024\t65535', '25565,25700-25720\n'), []);
  });

  it('should not guess when the range cannot be read', () => {
    assert.deepEqual(ephemeralConflicts(ports, null, null), []);
  });
});
