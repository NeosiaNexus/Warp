// Log failure tracking of managed processes (src/proc.js): what fails a run, and what a
// known-broken scenario excuses.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { ManagedProcess } from '../src/proc.js';

/** A process that is never started: lines are fed as if it had printed them. */
function printed(...lines) {
  const process = new ManagedProcess('warp', 'java', [], { cwd: '.', logFile: '/dev/null', failures: [/ ERROR /] });
  for (const line of lines) process.onLine(line);
  return process;
}

describe('ManagedProcess failures', () => {
  it('reports the lines matching a failure pattern, from a mark on', () => {
    const p = printed('INFO ready', 'x ERROR one', 'INFO more', 'x ERROR two');

    assert.deepEqual(p.failures(), ['x ERROR one', 'x ERROR two']);
    assert.deepEqual(p.failures(2), ['x ERROR two']);
  });

  it('excuses what was printed during a known-broken scenario, and only that', () => {
    const p = printed('x ERROR before');
    const from = p.mark();
    p.onLine('x ERROR during');
    p.onLine('INFO during');

    const excused = p.excuse(from);
    p.onLine('x ERROR after');

    assert.deepEqual(excused, ['x ERROR during']);
    assert.deepEqual(p.failures(), ['x ERROR before', 'x ERROR after']);
  });
});
