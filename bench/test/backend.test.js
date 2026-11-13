// Booting the backends behind a proxy: a failed boot must not leave its siblings running.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { bootAll, createBackends } from '../src/backend.js';

function fake(name, { fail = false } = {}) {
  const b = { name, booted: false, stopped: 0 };
  b.boot = async () => {
    if (fail) throw new Error(`${name}: no Done line`);
    b.booted = true;
    return b;
  };
  b.stop = async () => { b.stopped++; };
  return b;
}

describe('bootAll', () => {
  it('should return every backend when all boot', async () => {
    const backends = [fake('lobby'), fake('survival'), fake('mini')];
    assert.equal(await bootAll(backends), backends);
    assert.ok(backends.every((b) => b.booted && b.stopped === 0));
  });

  it('should stop the backends that booted when one fails, then rethrow', async () => {
    const backends = [fake('lobby'), fake('survival'), fake('mini', { fail: true })];
    await assert.rejects(bootAll(backends), /mini: no Done line/);
    assert.ok(backends.every((b) => b.stopped === 1), 'every backend stopped, survivors included');
  });
});

describe('createBackends', () => {
  it('should build lobby, survival and mini on consecutive ports with the proxy forwarding', () => {
    const backends = createBackends({
      jar: '/x/paper.jar',
      java: '/x/java',
      baseDir: '/x/work',
      logDir: '/x/logs',
      basePort: 25700,
      backend: { protocol: 769 },
      forwarding: 'legacy',
      secret: 's',
      compressionThreshold: 256,
      maxHeapMb: 1024,
    });
    assert.deepEqual(backends.map((b) => b.name), ['lobby', 'survival', 'mini']);
    assert.deepEqual(backends.map((b) => b.port), [25700, 25701, 25702]);
    assert.ok(backends.every((b) => b.forwarding === 'legacy' && b.process === null));
  });
});
