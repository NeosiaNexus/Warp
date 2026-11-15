// The validity gate: when a measurement is rejected, and what it says.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { checkBackends, checkLatency, checkLoad, checkLoadgen, checkProxy, checkStorm, checkSwitches, checkWindow, THRESHOLDS, verdict } from '../src/validity.js';
import { invalidResults } from '../src/verdict.js';

const paper = (over = {}) => ({ name: 'lobby', kind: 'paper', tps: 20, msptAvg: 4, overloads: 0, errors: 0, errorSample: null, ...over });
const stub = (over = {}) => ({ name: 'stub', kind: 'stub', players: 5000, refused: 0, keepAliveTimeouts: 0, lagP99Ms: 11, ...over });

describe('offered against achieved load', () => {
  it('should accept a load that was fully established and kept', () => {
    assert.deepEqual(checkLoad({ offered: 5000, onlineStart: 5000, onlineEnd: 5000 }), []);
  });

  it('should tolerate a loss below the threshold and nothing more', () => {
    assert.deepEqual(checkLoad({ offered: 5000, onlineStart: 4980, onlineEnd: 4975 }), []);
    assert.equal(checkLoad({ offered: 5000, onlineStart: 4974, onlineEnd: 4974 }).length, 1);
    assert.equal(THRESHOLDS.minAchievedRatio, 0.995);
  });

  it('should say what share of the offered connections were established', () => {
    assert.deepEqual(checkLoad({ offered: 2000, onlineStart: 860, onlineEnd: 860 }), ['only 43% of the 2000 offered connections were established (860)']);
  });

  it('should reject connections that were lost during the window', () => {
    assert.match(checkLoad({ offered: 300, onlineStart: 300, onlineEnd: 151 })[0], /only 151 of the 300 connections were still there/);
  });
});

describe('backend health', () => {
  it('should accept healthy backends of both kinds', () => {
    assert.deepEqual(checkBackends([paper(), stub()]), []);
  });

  it('should reject a Paper backend below the tick-rate threshold', () => {
    assert.deepEqual(checkBackends([paper({ tps: 17.4 })]), ['backend lobby below 19 TPS (17.4)']);
    assert.deepEqual(checkBackends([paper({ tps: 19 })]), []);
  });

  it('should reject a backend that could not keep up, logged errors, or did not answer', () => {
    assert.match(checkBackends([paper({ overloads: 3 })])[0], /could not keep up \(3 overload warnings\)/);
    assert.match(checkBackends([paper({ errors: 12, errorSample: 'Failed to save player data for x' })])[0], /logged 12 errors \(Failed to save player data for x\)/);
    assert.match(checkBackends([paper({ tps: null })])[0], /did not report its tick rate/);
    assert.match(checkBackends([{ name: 'mini', kind: 'paper', dead: true }])[0], /mini was not running/);
  });

  it('should reject a stub that refused logins, timed players out or was saturated', () => {
    assert.match(checkBackends([stub({ refused: 7 })])[0], /refused 7 logins/);
    assert.match(checkBackends([stub({ keepAliveTimeouts: 2 })])[0], /timed out 2 players/);
    assert.match(checkBackends([stub({ lagP99Ms: 180 })])[0], /saturated/);
  });
});

describe('load generator and proxy', () => {
  it('should reject a saturated load generator', () => {
    assert.deepEqual(checkLoadgen({ lagP99Ms: 12 }), []);
    assert.match(checkLoadgen({ lagP99Ms: 240 })[0], /load generator saturated/);
  });

  it('should reject a measurement during which the proxy engaged a protection', () => {
    assert.deepEqual(checkProxy({ protections: 0 }), []);
    const reasons = checkProxy({ protections: 516, protectionSample: "Server 'lobby' is skipped by the balancers" });
    assert.match(reasons[0], /engaged a protection 516 times \(Server 'lobby' is skipped by the balancers\)/);
    assert.match(checkProxy({ dead: true })[0], /not running/);
  });

  it('should collect every reason of a window', () => {
    const reasons = checkWindow({
      load: { offered: 100, onlineStart: 50, onlineEnd: 50 },
      backends: [paper({ tps: 10 })],
      loadgen: { lagP99Ms: 500 },
      proxy: { protections: 1, protectionSample: 'Bot attack detected' },
    });
    assert.equal(reasons.length, 4);
  });
});

describe('latency, switches and storm', () => {
  const leg = (over = {}) => ({ p50: 0.2, p99: 0.6, n: 100, timeouts: 0, settled: true, ...over });

  it('should accept an unloaded probe', () => {
    assert.deepEqual(checkLatency({ samples: 100, viaProxy: leg({ p50: 0.3 }), direct: leg() }), []);
  });

  it('should reject a direct path slower than a few milliseconds: it measures the box, not the proxy', () => {
    assert.match(checkLatency({ samples: 100, viaProxy: leg({ p50: 113 }), direct: leg({ p50: 57 }) })[0], /direct round trip of 57\.00 ms at p50 \(limit 5 ms\)/);
  });

  it('should reject timeouts and missing rounds', () => {
    const reasons = checkLatency({ samples: 100, viaProxy: leg({ n: 60, timeouts: 3 }), direct: leg() });
    assert.match(reasons.join('; '), /only 60 of 100 rounds ran through the proxy; 3 rounds timed out through the proxy/);
  });

  it('should reject a switch storm with too many failures, or none attempted', () => {
    assert.deepEqual(checkSwitches({ switches: 990, failures: 10 }), []);
    assert.match(checkSwitches({ switches: 1688, failures: 175 })[0], /175 of 1863 switches failed \(9\.3%\)/);
    assert.deepEqual(checkSwitches({ switches: 0, failures: 0 }), ['no switch was attempted']);
  });

  it('should reject a flood the generator could not deliver', () => {
    assert.deepEqual(checkStorm({ rate: 1000, durationMs: 10_000, attempted: 9990, opened: 9000 }), []);
    assert.match(checkStorm({ rate: 10_000, durationMs: 10_000, attempted: 31_000, opened: 31_000 })[0], /offered only 31% of 10000 connections\/s/);
  });
});

describe('verdict', () => {
  it('should be valid with no reason and list each reason once', () => {
    assert.deepEqual(verdict([]), { valid: true, reasons: [] });
    assert.deepEqual(verdict(['a', 'b', 'a']), { valid: false, reasons: ['a', 'b'] });
  });

  it('should list the rejected measurements of a run', () => {
    const lines = invalidResults([
      {
        id: 'warp',
        metrics: {
          idle: { levels: [{ level: 1000, validity: verdict([]) }, { level: 5000, validity: verdict(['backend stub refused 3 logins']) }] },
          traffic: { validity: verdict([]) },
          switch: { skipped: 'no command' },
        },
      },
    ]);
    assert.deepEqual(lines, ['warp: idle @ 5000: backend stub refused 3 logins']);
  });
});
