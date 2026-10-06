// Tests of the harness itself (`npm test` in e2e/): the matrix is well-formed, and the log
// patterns that fail a run match what Warp, Paper and Netty actually print.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { BACKEND_PROTOCOL_ERRORS, backendGroups } from '../src/backend.js';
import { connectClient } from '../src/clients/index.js';
import { announcedProtocol } from '../src/clients/mineflayer.js';
import { LOBBY_MODE, QUIRKS, SCENARIOS, SURVIVAL_MODE, checkStatus, checkStayed, checkTabLists, features, fromSurvival } from '../src/scenarios.js';
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

  it('only lists versions the bots speak natively, or bridges them through ViaProxy', () => {
    for (const v of matrix.versions) {
      if (v.via) {
        assert.notEqual(announcedProtocol(v.via), null, `${v.version}: the bots cannot speak ${v.via}`);
      } else {
        assert.equal(announcedProtocol(v.client ?? v.version), v.protocol, `${v.version}: the bots would announce another protocol`);
      }
    }
  });

  it('drives 1.7 with minecraft-protocol alone, as mineflayer starts at 1.8.8', () => {
    assert.match(connectClient(findEntry(matrix, '1.7.10'), {}).description, /^minecraft-protocol 1\.7\.10$/);
    assert.match(connectClient(findEntry(matrix, '1.7.2'), { ports: {}, targets: [] }).description, /^minecraft-protocol 1\.7\.10 → ViaProxy → 1\.7\.2$/);
    assert.match(connectClient(findEntry(matrix, '1.8.8'), {}).description, /^mineflayer 1\.8\.8$/);
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

  it('tests modern forwarding on pull requests', () => {
    const variants = jobsForTier(matrix, 'pr').flatMap((job) => job.variants.split(','));
    assert.ok(variants.some((name) => resolveVariant(matrix, name).forwarding !== 'none'));
  });

  it('only schedules variants that each version can run', () => {
    for (const tier of Object.keys(matrix.tiers)) {
      for (const job of jobsForTier(matrix, tier)) {
        const variants = job.variants.split(',').map((name) => resolveVariant(matrix, name));
        for (const group of backendGroups(findEntry(matrix, job.mc), variants)) assert.equal(group.skip, null, `${tier} ${job.mc}: ${group.skip}`);
      }
    }
  });

  it('tests the profile key login of 1.19 to 1.19.2 on pull requests', () => {
    const protocols = jobsForTier(matrix, 'pr').map((job) => job.protocol);
    assert.ok(protocols.some((p) => features(p).profileKeys === true), 'no version from 1.19 to 1.19.2');
  });

  it('requires the newest version, and every variant on the newest the bots speak natively, on pull requests', () => {
    const passing = matrix.versions.filter((v) => !v.knownBroken);
    const newest = passing.at(-1);
    const newestNative = passing.findLast((v) => !v.via);
    const jobs = new Map(jobsForTier(matrix, 'pr').map((job) => [job.mc, job]));
    assert.ok(jobs.has(newest.version), `${newest.version}, the newest version that passes, is not in the pr tier`);
    const every = Object.keys(matrix.variants).filter((name) => name !== 'defaults').sort();
    const variants = jobs.get(newestNative.version)?.variants.split(',').sort();
    assert.deepEqual(variants, every, `${newestNative.version}, the newest version the bots speak natively, must run every variant on pull requests`);
  });

  it('runs on main every version and variant a pull request runs', () => {
    const full = new Map(jobsForTier(matrix, 'full').map((job) => [job.mc, new Set(job.variants.split(','))]));
    for (const job of jobsForTier(matrix, 'pr')) {
      for (const variant of job.variants.split(',')) {
        assert.ok(full.get(job.mc)?.has(variant), `${job.mc} ${variant} runs on pull requests but not in the full tier`);
      }
    }
  });

  it('works around only the server quirks the scenarios know, each with its issue', () => {
    const quirky = matrix.versions.filter((v) => v.server.quirks);
    assert.ok(quirky.length, 'no server quirk left: drop QUIRKS and what reads them');
    for (const v of quirky) {
      for (const [name, reason] of Object.entries(v.server.quirks)) {
        assert.ok(Object.hasOwn(QUIRKS, name), `${v.version}: unknown server quirk "${name}"`);
        assert.match(reason, /\(#\d+\)/, `${v.version} ${name}: reason links no issue`);
      }
    }
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

  it('forward nothing unless they say so', () => {
    assert.equal(resolveVariant(matrix, 'online').forwarding, 'none');
    assert.equal(resolveVariant(matrix, 'velocity').forwarding, 'velocity');
  });

  it('refuse a forwarding mode Warp does not have', () => {
    assert.throws(() => resolveVariant(matrix, 'online', { forwarding: 'bungeecord' }), /forwarding "bungeecord" is not one of none, velocity/);
  });
});

describe('scenario features', () => {
  it('switches servers on every version, through Warp', () => {
    for (const protocol of [47, 762, 763, 764, 769, 777]) assert.equal(features(protocol).switching, true, String(protocol));
    assert.equal(features(769, { direct: true }).switching, 'no proxy in a control run');
  });

  it('checks forwarded identities where they differ from what a backend derives itself: online', () => {
    assert.equal(features(769, { forwarding: 'velocity', online: true }).forwarding, true);
    assert.equal(features(769, { forwarding: 'velocity' }).forwarding, 'offline, Warp forwards the UUID a backend derives itself');
    assert.equal(features(769, { online: true }).forwarding, 'the variant forwards no player info');
    assert.equal(features(769, { direct: true, forwarding: 'velocity', online: true }).forwarding, 'no proxy in a control run');
  });

  it('tells survival’s brand from a late one of the lobby, by when it arrives', () => {
    assert.equal(fromSurvival({ state: 'configuration', gameMode: LOBBY_MODE }), true, 'configuration phase of the switch (1.20.2+)');
    assert.equal(fromSurvival({ state: 'play', gameMode: SURVIVAL_MODE }), true, 'after survival’s Join Game (before 1.20.2)');
    assert.equal(fromSurvival({ state: 'play', gameMode: LOBBY_MODE }), false, 'still on the lobby');
  });

  it('offers resource packs from 1.8, where they became a packet of their own', () => {
    assert.equal(features(47).resourcePack, true);
    assert.equal(features(5).resourcePack, 'no Resource Pack Send packet before 1.8');
  });

  it('logs in with a profile key on 1.19 to 1.19.2 only, the versions whose Login Start carries one', () => {
    assert.deepEqual(
      [758, 759, 760, 761].map((protocol) => features(protocol).profileKeys === true),
      [false, true, true, false],
    );
    assert.equal(features(758).profileKeys, 'only 1.19 to 1.19.2 clients send a profile key in Login Start');
  });

  it('signs chat from 1.20, whose servers take the keys that verify chat sessions from the mock', () => {
    assert.equal(features(47).signedChat, "before 1.20 a server trusts only Mojang's own key to verify chat");
    assert.notEqual(features(762).signedChat, true); // 1.19.4: Mojang's key is bundled in authlib
    assert.equal(features(763).signedChat, true);
    assert.equal(features(769).signedChat, true);
  });

  it('knows every feature a scenario requires', () => {
    const known = Object.keys(features(769));
    for (const s of SCENARIOS.filter((x) => x.requires)) assert.ok(known.includes(s.requires), `${s.name} requires "${s.requires}"`);
  });
});

describe('tab lists after a switch', () => {
  const bot = (username, ...listed) => ({ username, listed: () => listed });

  it('fails when a bot that did not switch left the lobby, which would pass for one left behind', () => {
    const stayer = (username, gameMode) => ({ username, gameMode: () => gameMode });
    assert.doesNotThrow(() => checkStayed([stayer('c', LOBBY_MODE), stayer('d', LOBBY_MODE)]));
    assert.throws(() => checkStayed([stayer('c', LOBBY_MODE), stayer('d', SURVIVAL_MODE)]), /: d \(adventure\)$/);
  });

  it('passes when no switcher lists a player left behind', () => {
    const detail = checkTabLists([bot('a', 'a', 'b'), bot('b', 'a', 'b')], ['c', 'd']);
    assert.equal(detail, 'no player left behind in 2 tab lists');
  });

  it('fails when a switcher still lists a player of the previous server', () => {
    assert.throws(() => checkTabLists([bot('a', 'a', 'c'), bot('b', 'b')], ['c', 'd']), /a lists c$/);
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
      '2026-10-06 12:13:13.939 [multiThreadIoEventLoopGroup-3-6] INFO  dev.warp.proxy.connection.MinecraftConnection - Connection /127.0.0.1:38044 timed out in PLAY: the client sent nothing for 30 s (reads on, 0 bytes queued to send)',
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

  it('flag backend disconnects caused by malformed packets or chat acknowledgements, not ordinary ones', () => {
    assert.ok(matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 INFO]: e2e_login lost connection: Internal Exception: io.netty.handler.codec.DecoderException: Badly compressed packet - size of 2 is below server threshold of 256'));
    assert.ok(matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 ERROR]: Error receiving packet 42'));
    // Paper 1.20.4, when a /server Warp kept from it took the client's acknowledgements along (#81).
    assert.ok(matches(BACKEND_PROTOCOL_ERRORS, '[13:42:37 WARN]: Failed to validate message acknowledgements from e2e_chat'));
    assert.ok(matches(BACKEND_PROTOCOL_ERRORS, '[13:42:37 INFO]: e2e_chat lost connection: Chat message validation failure'));
    assert.ok(!matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 INFO]: e2e_login lost connection: Disconnected'));
    assert.ok(!matches(BACKEND_PROTOCOL_ERRORS, '[12:00:00 INFO]: e2e_login lost connection: Timed out'));
  });
});
