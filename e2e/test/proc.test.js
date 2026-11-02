// Managed processes (src/proc.js): log failure tracking (what fails a run, and what a known-broken
// scenario excuses), and the output a process leaves when it exits.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
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

/** A real process: `sh -c script`, logging to a file of its own. */
function shell(script) {
  const logFile = join(mkdtempSync(join(tmpdir(), 'warp-e2e-proc-')), 'sh.log');
  return new ManagedProcess('sh', 'sh', ['-c', script], { cwd: '.', logFile }).start();
}

describe('ManagedProcess exit', () => {
  // The subshell keeps the output pipe open after sh exits, so the last line arrives after the exit
  // event, as a line printed just before System.exit can (warp-drain's "Exiting with code 0").
  const LATE = '(sleep 0.3; echo "last line") & echo "first line"';

  it('settles with every line printed, those read after the exit event included', async () => {
    const p = shell(LATE);

    const exit = await p.exited;

    assert.equal(exit.code, 0);
    assert.deepEqual(p.lines, ['first line', 'last line']);
    assert.equal(readFileSync(p.logFile, 'utf8'), 'first line\nlast line\n');
  });

  it('resolves a wait for a line printed as the process exits', async () => {
    const p = shell(LATE);

    const line = await p.waitFor(/last line/, 5_000);

    assert.equal(line, 'last line');
  });

  it('settles after a grace period when a process it started keeps the output open', async () => {
    const p = shell('(sleep 4) & echo "only line"');
    const started = Date.now();

    await p.exited;

    assert.ok(Date.now() - started < 3_500, `settled after ${Date.now() - started} ms`);
    assert.deepEqual(p.lines, ['only line']);
  });
});
