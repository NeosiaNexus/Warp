// Statuses and notes (src/status.js), shared by the summary of a run and the merged report of CI.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { QUIRKS } from '../src/scenarios.js';
import { variantStatus, versionNotes, versionStatus } from '../src/status.js';

describe('version status', () => {
  it('passes or fails', () => {
    assert.equal(versionStatus(true, false), 'pass');
    assert.equal(versionStatus(false, false), 'fail');
  });

  it('fails as expected or passes unexpectedly when known broken', () => {
    assert.equal(versionStatus(false, true), 'xfail');
    assert.equal(versionStatus(true, true), 'xpass');
  });
});

describe('variant status', () => {
  const version = (knownBroken) => ({ knownBroken });

  it('is the variant’s own on a version that works', () => {
    for (const status of ['pass', 'fail', 'skip']) assert.equal(variantStatus(version(null), { status }), status);
  });

  it('is xfail or xpass on a known-broken version, unless skipped', () => {
    const broken = version('packet ids not audited yet (#48)');

    assert.equal(variantStatus(broken, { status: 'fail' }), 'xfail');
    assert.equal(variantStatus(broken, { status: 'pass' }), 'xpass');
    assert.equal(variantStatus(broken, { status: 'skip' }), 'skip');
  });
});

describe('version notes', () => {
  it('list every backend booted again, and every server quirk worked around', () => {
    const notes = versionNotes({
      bootRetries: [{ backend: 'survival', reason: 'no ready line within 240 s', log: 'logs/survival-t256.log' }],
      quirks: { concurrentLogins: 'shared random source (#103)' },
    });

    assert.deepEqual(notes, [
      'survival hung while booting (no ready line within 240 s), so it was booted again; its threads are in `logs/survival-t256.log`',
      `${QUIRKS.concurrentLogins}, for the server quirk \`concurrentLogins\`: shared random source (#103)`,
    ]);
  });

  it('are empty for a plain run, and for a result.json written before they existed', () => {
    assert.deepEqual(versionNotes({ bootRetries: [], quirks: {} }), []);
    assert.deepEqual(versionNotes({}), []);
  });
});
