// What makes a run a harness failure (non-zero exit) as opposed to a poor result.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { harnessFailures } from '../src/verdict.js';

const sound = {
  id: 'warp',
  errors: [],
  metrics: {
    idle: { levels: [{ level: 50, online: 50, failures: 0 }] },
    traffic: { online: 10 },
    latency: { viaProxy: { n: 20 } },
    switch: { online: 5 },
    storm: { rates: [{ rate: 500, joined: false }] },
  },
};

describe('harnessFailures', () => {
  it('should pass a sound run even when the proxy performed badly', () => {
    assert.deepEqual(harnessFailures([sound]), []);
  });

  it('should fail when a proxy did not launch or a scenario threw', () => {
    const r = { ...sound, errors: ['setup/launch: exited before listening'] };
    assert.match(harnessFailures([r])[0], /setup\/launch/);
    const t = { ...sound, errors: ['latency: probe_proxy: kicked before spawn'] };
    assert.match(harnessFailures([t])[0], /latency: probe_proxy/);
  });

  it('should fail when a scenario ran but no bot got through', () => {
    const r = {
      ...sound,
      metrics: { ...sound.metrics, idle: { levels: [{ level: 50, online: 0, failures: 50 }] }, traffic: { online: 0 }, latency: { viaProxy: { n: 0 } } },
    };
    const f = harnessFailures([r]);
    assert.equal(f.length, 3);
    assert.match(f[0], /idle @ 50/);
  });

  it('should not count a skipped switch scenario as a failure', () => {
    const r = { ...sound, metrics: { ...sound.metrics, switch: { skipped: 'no /server' } } };
    assert.deepEqual(harnessFailures([r]), []);
  });
});
