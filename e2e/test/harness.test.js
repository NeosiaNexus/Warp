// Tests of the harness itself (`npm test` in e2e/): the matrix is well-formed, and the log
// patterns that fail a run match what Warp, Paper and Netty actually print.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { BACKEND_PROTOCOL_ERRORS } from '../src/backend.js';
import { connectClient } from '../src/clients/index.js';
import { announcedProtocol } from '../src/clients/mineflayer.js';
import { SCENARIOS, checkStatus, features } from '../src/scenarios.js';
import { findEntry, jobsForTier, loadMatrix, resolveVariant } from '../src/versions.js';
import { WARP_FAILURES } from '../src/warp.js';

const matrix = loadMatrix();
const matches = (patterns, line) => patterns.some((p) => p.test(line));
/** First protocol with the configuration phase (1.20.2): switching and fallbacks work differently before. */
const CONFIGURATION_PHASE = 764;

describe('versions.json', () => {
  it('has exactly one entry per protocol version, in ascending order', () => {
    const protocols = matrix.versions.map((v) => v.protocol);
    assert.deepEqual(protocols, [...new Set(protocols)].sort((a, b) => a - b));
  });

  it('pins every server download to a URL and a checksum', () => {
    for (const v of matrix.versions) {
      assert.match(v.server.url, /^https:\/\//, v.version);
      assert.ok(v.server.sha256 || v.server.sha1, `${v.version}: no checksum`);
      assert.ok(['paper', 'vanilla'].includes(v.server.type), `${v.version}: server type`);
      assert.ok(Number.isInteger(v.java) && v.java >= 8, `${v.version}: java`);
    }
  });

  it('only lists versions mineflayer speaks natively, or bridges them through ViaProxy', () => {
    for (const v of matrix.versions) {
      if (v.via) {
        assert.notEqual(announcedProtocol(v.via), null, `${v.version}: mineflayer cannot speak ${v.via}`);
      } else {
        assert.equal(announcedProtocol(v.client ?? v.version), v.protocol, `${v.version}: mineflayer would announce another protocol`);
      }
    }
  });

  it('keeps every tier, variant and extra variant resolvable', () => {
    for (const [name, tier] of Object.entries(matrix.tiers)) {
      const jobs = jobsForTier(matrix, name);
      assert.ok(jobs.length > 0, `tier ${name} is empty`);
      for (const job of jobs) {
        for (const variant of job.variants.split(',')) resolveVariant(matrix, variant);
      }
      for (const key of Object.keys(tier.extraVariants ?? {})) findEntry(matrix, key);
    }
  });

  it('never requires a known-broken version on pull requests', () => {
    for (const job of jobsForTier(matrix, 'pr')) assert.equal(job.known_broken, false, job.mc);
  });

  it('tests both sides of the configuration phase on pull requests', () => {
    const protocols = jobsForTier(matrix, 'pr').map((job) => job.protocol);
    assert.ok(protocols.some((p) => p < CONFIGURATION_PHASE), 'no version before 1.20.2');
    assert.ok(protocols.some((p) => p >= CONFIGURATION_PHASE), 'no version from 1.20.2');
  });

  it('says why each known-broken version or scenario fails, with an issue', () => {
    const scenarios = new Map(SCENARIOS.map((s) => [s.name, s]));
    for (const v of matrix.versions) {
      if (v.knownBroken !== undefined) assert.match(v.knownBroken, /\(#\d+\)/, `${v.version}: knownBroken links no issue`);
      if (v.knownBrokenScenarios === undefined) continue;
      assert.equal(v.knownBroken, undefined, `${v.version}: knownBroken already covers every scenario`);
      for (const [name, reason] of Object.entries(v.knownBrokenScenarios)) {
        // The keep-alive bot runs in the background, across the others: it cannot be excused alone.
        assert.ok(scenarios.has(name) && !scenarios.get(name).background, `${v.version}: no scenario "${name}" to mark known broken`);
        assert.match(reason, /\(#\d+\)/, `${v.version} ${name}: reason links no issue`);
      }
    }
  });
});

describe('variants', () => {
  it('defaults the backend threshold to Warp’s', () => {
    assert.equal(resolveVariant(matrix, 'online').backendThreshold, 256);
    assert.equal(resolveVariant(matrix, 'backend-lower').backendThreshold, 64);
  });

  it('applies ad-hoc overrides on top of a preset', () => {
    const v = resolveVariant(matrix, 'offline', { passthrough: false });
    assert.equal(v.online, false);
    assert.equal(v.passthrough, false);
  });
});

describe('scenario features', () => {
  it('switches servers on every version', () => {
    for (const protocol of [47, 762, 763, 764, 769]) assert.equal(features(protocol).switching, true, protocol);
  });

  it('signs chat from 1.20, whose servers take the keys that verify chat sessions from the mock', () => {
    assert.equal(features(47).signedChat, false);
    assert.equal(features(762).signedChat, false); // 1.19.4: Mojang's key is bundled in authlib
    assert.equal(features(763).signedChat, true);
    assert.equal(features(769).signedChat, true);
  });
});

describe('status scenario', () => {
  const answer = (name, protocol) => ({ version: { name, protocol }, latency: 1 });

  it('passes when Warp advertises the protocol the bot speaks', () => {
    const detail = checkStatus(answer('Warp 1.7.2-26.1.1', 769), { direct: false, protocol: 769 });
    assert.equal(detail, 'answered as "Warp 1.7.2-26.1.1" (protocol 769) in 1 ms');
  });

  it('fails when Warp advertises another protocol, which lists it as incompatible (#49)', () => {
    assert.throws(() => checkStatus(answer('Warp 26.1.1', 775), { direct: false, protocol: 769 }), /advertised protocol 775 to a client of protocol 769/);
  });

  it('fails on an answer that is not Warp’s, except in a control run', () => {
    assert.throws(() => checkStatus(answer('Paper 1.21.4', 769), { direct: false, protocol: 769 }), /unexpected status response/);
    assert.doesNotThrow(() => checkStatus(answer('Paper 1.21.4', 769), { direct: true, protocol: 769 }));
  });

  it('expects the protocol the bots speak, the bridged one through ViaProxy', () => {
    assert.equal(connectClient(findEntry(matrix, '1.21.4'), {}).protocol, 769);
    const bridged = findEntry(matrix, '1.9.1');
    assert.equal(connectClient(bridged, { ports: {}, targets: [] }).protocol, announcedProtocol(bridged.via));
  });
});

describe('failure patterns', () => {
  it('fail a run on Warp errors, buffer leaks and JVM crashes', () => {
    for (const line of [
      '2026-10-06 01:21:01.123 [epollEventLoopGroup-3-1] ERROR dev.warp.proxy.connection.MinecraftConnection - Exception in pipeline for /127.0.0.1:5000',
      '2026-10-06 01:21:01.123 [main] FATAL dev.warp.proxy.WarpBootstrap - boom',
      '2026-10-06 01:21:01.123 [epollEventLoopGroup-3-1] ERROR io.netty.util.ResourceLeakDetector - LEAK: ByteBuf.release() was not called before it\'s garbage-collected.',
      'Exception in thread "main" java.lang.NoClassDefFoundError: dev/warp/Foo',
      '# A fatal error has been detected by the Java Runtime Environment:',
      '2026-10-06 03:12:27.004 [multiThreadIoEventLoopGroup-3-2] WARN  dev.warp.proxy.connection.ClientPlaySessionHandler - Invalid KeepAlive ID from player e2e_keepalive',
      '2026-10-06 03:13:12.001 [multiThreadIoEventLoopGroup-3-2] INFO  dev.warp.proxy.connection.ConnectedPlayer - Player e2e_keepalive timed out (no KeepAlive response in 44999ms)',
    ]) {
      assert.ok(matches(WARP_FAILURES, line), line);
    }
  });

  it('ignore normal Warp output, including warnings', () => {
    for (const line of [
      '2026-10-06 01:21:01.123 [main] INFO  dev.warp.proxy.WarpServer - Warp is listening on 127.0.0.1:26102',
      '2026-10-06 01:21:01.123 [epollEventLoopGroup-3-1] WARN  dev.warp.proxy.connection.BackendLoginSessionHandler - Backend 127.0.0.1:26100 compresses packets from 64 bytes but players use 256',
      '2026-10-06 01:21:01.123 [epollEventLoopGroup-3-1] INFO  dev.warp.proxy.connection.ConnectedPlayer - Player e2e_login disconnected (ERROR handling is fine)',
    ]) {
      assert.ok(!matches(WARP_FAILURES, line), line);
    }
  });

  it('flag backend disconnects caused by malformed packets, not ordinary ones', () => {
    assert.ok(matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 INFO]: e2e_login lost connection: Internal Exception: io.netty.handler.codec.DecoderException: Badly compressed packet - size of 2 is below server threshold of 256'));
    assert.ok(matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 ERROR]: Error receiving packet 42'));
    assert.ok(!matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 INFO]: e2e_login lost connection: Disconnected'));
    assert.ok(!matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 INFO]: e2e_login lost connection: Timed out'));
  });
});
