// Backends (src/backend.js): which servers accept forwarded players, how variants share backends,
// and the files a backend is configured with.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';

import { Backend, backendGroups, forwardingUnsupported, paperForwardingConfig } from '../src/backend.js';
import { findEntry, loadMatrix, resolveVariant } from '../src/versions.js';

const matrix = loadMatrix();
const entry = (version) => findEntry(matrix, version);
const variants = (...names) => names.map((name) => resolveVariant(matrix, name));
const dirs = [];

after(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

describe('Velocity forwarding', () => {
  it('is accepted by Paper from 1.13.1 on', () => {
    for (const version of ['1.13.1', '1.18.2', '1.19', '1.21.4']) assert.equal(forwardingUnsupported(entry(version), 'velocity'), null, version);
  });

  it('is refused by older Paper builds and by vanilla servers, with the reason', () => {
    assert.equal(forwardingUnsupported(entry('1.13'), 'velocity'), 'Paper accepts Velocity forwarding from 1.13.1');
    assert.equal(forwardingUnsupported(entry('1.8.8'), 'velocity'), 'Paper accepts Velocity forwarding from 1.13.1');
    assert.equal(forwardingUnsupported(entry('1.16'), 'velocity'), 'vanilla servers accept no forwarded player info, only Paper does');
  });

  it('is not needed to accept players straight', () => {
    assert.equal(forwardingUnsupported(entry('1.9'), 'none'), null);
  });

  it('is set in paper.yml up to 1.18.2, in config/paper-global.yml from 1.19', () => {
    const legacy = paperForwardingConfig(758, { secret: 's3cret', online: true });
    const modern = paperForwardingConfig(759, { secret: 's3cret', online: false });

    assert.equal(legacy.file, 'paper.yml');
    assert.equal(legacy.yaml, "settings:\n  velocity-support:\n    enabled: true\n    online-mode: true\n    secret: 's3cret'\n");
    assert.equal(modern.file, join('config', 'paper-global.yml'));
    assert.equal(modern.yaml, "proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: 's3cret'\n");
  });
});

describe('backend groups', () => {
  it('share one boot between variants with the same threshold and forwarding, in order of first use', () => {
    const groups = backendGroups(entry('1.21.4'), variants('online', 'velocity', 'backend-lower', 'offline'));

    assert.deepEqual(
      groups.map((g) => [g.threshold, g.forwarding, g.skip, g.variants.map((v) => v.name)]),
      [
        [256, null, null, ['online', 'offline']],
        [256, { mode: 'velocity', online: true }, null, ['velocity']],
        [64, null, null, ['backend-lower']],
      ],
    );
  });

  it('set aside a variant the server cannot run, with the reason', () => {
    const [online, velocity] = backendGroups(entry('1.8.8'), variants('online', 'velocity'));

    assert.equal(online.skip, null);
    assert.equal(velocity.skip, 'Paper accepts Velocity forwarding from 1.13.1');
    assert.deepEqual(velocity.variants.map((v) => v.name), ['velocity']);
  });

  it('set aside forwarding in a control run, which has no proxy', () => {
    const [online, velocity] = backendGroups(entry('1.21.4'), variants('online', 'velocity'), { direct: true });

    assert.equal(online.skip, null);
    assert.equal(velocity.skip, 'a control run has no proxy to forward player info');
  });
});

describe('backend boot', () => {
  /** A backend whose successive boots hang, exit or get ready, in the order given. */
  class ScriptedBackend extends Backend {
    constructor(...outcomes) {
      const logDir = mkdtempSync(join(tmpdir(), 'warp-e2e-boot-'));
      dirs.push(logDir);
      super({ name: 'lobby', logDir, logSuffix: 't256' });
      Object.assign(this, { outcomes, tries: 0, signals: [] });
    }

    prepare() {}

    start() {
      this.tries++;
      this.process = { alive: true, lines: [`try ${this.tries}`, 'Reloading ResourceManager: Default, bukkit'], child: { kill: (s) => this.signals.push(s) }, stop: async () => this.signals.push('stop') };
    }

    ready() {
      const outcome = this.outcomes.shift();
      if (outcome === 'ready') return Promise.resolve('Done (1.982s)! For help, type "help"');
      if (outcome === 'exit') this.process.alive = false;
      return Promise.reject(new Error(`lobby: ${outcome}`));
    }
  }

  it('dumps the threads of a server that hangs, saves what it printed, and boots it again', async () => {
    const backend = new ScriptedBackend('hang', 'ready');
    const hangs = [];

    await backend.boot(null, {}, 1, (e) => hangs.push(e.message));

    assert.equal(backend.tries, 2);
    assert.deepEqual(hangs, ['lobby: hang']);
    assert.deepEqual(backend.signals, ['SIGQUIT', 'stop']);
    assert.match(readFileSync(join(backend.logDir, 'lobby-t256-hung-boot.txt'), 'utf8'), /^try 1\nReloading ResourceManager/);
  });

  it('gives up when the second boot hangs too', async () => {
    const backend = new ScriptedBackend('hang', 'hang');

    await assert.rejects(backend.boot(null, {}, 1, () => {}), /lobby: hang/);
    assert.equal(backend.tries, 2);
  });

  it('does not boot again a server that exited: a crash is not a hang', async () => {
    const backend = new ScriptedBackend('exit', 'ready');

    await assert.rejects(backend.boot(null, {}, 1, () => assert.fail('not a hang')), /lobby: exit/);
    assert.equal(backend.tries, 1);
  });
});

describe('backend configuration', () => {
  const PACK = { url: 'http://127.0.0.1:41234/lobby.zip', sha1: 'a'.repeat(40), id: '4b4f6f52-8d1e-4a6a-9a55-3e2f6c1d2b7a' };

  /** Prepares the lobby of `version` in a fresh directory; returns a reader of its files. */
  function prepare(version, forwarding = null) {
    const dir = mkdtempSync(join(tmpdir(), 'warp-e2e-backend-'));
    dirs.push(dir);
    const { server, protocol } = entry(version);
    new Backend({ name: 'lobby', dir, server, protocol, port: 41000, compressionThreshold: 256, gameMode: 1, resourcePack: PACK, forwarding }).prepare(null);
    return (file) => readFileSync(join(dir, file), 'utf8');
  }

  const properties = (text) => Object.fromEntries(text.trim().split('\n').map((line) => line.split(/=(.*)/s).slice(0, 2)));

  it('offers the resource pack with the keys each version reads', () => {
    const legacy = properties(prepare('1.8.8')('server.properties'));
    const modern = properties(prepare('1.21.4')('server.properties'));

    assert.equal(legacy['resource-pack'], PACK.url);
    assert.equal(legacy['resource-pack-hash'], PACK.sha1);
    assert.equal(legacy['resource-pack-sha1'], undefined);
    assert.equal(modern['resource-pack-sha1'], PACK.sha1);
    assert.equal(modern['resource-pack-hash'], undefined);
    assert.equal(modern['resource-pack-id'], PACK.id);
    assert.equal(modern['require-resource-pack'], 'false');
  });

  it('accepts players forwarded with the shared secret', () => {
    const read = prepare('1.16.5', { mode: 'velocity', online: true, secret: 'f0rwarded' });

    assert.match(read('paper.yml'), /velocity-support:\n {4}enabled: true\n {4}online-mode: true\n {4}secret: 'f0rwarded'\n/);
  });

  it('accepts players straight without forwarding', () => {
    const read = prepare('1.21.4');

    assert.throws(() => read(join('config', 'paper-global.yml')), { code: 'ENOENT' });
  });
});
