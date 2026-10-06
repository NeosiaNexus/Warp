// The merged Markdown report (src/report.js), from result.json files like the CI jobs upload.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';

const REPORT = join(import.meta.dirname, '..', 'src', 'report.js');
const dirs = [];

/** Runs the report on one result.json per version, each in its own artifact directory. */
function report(...results) {
  const dir = mkdtempSync(join(tmpdir(), 'warp-e2e-report-'));
  dirs.push(dir);
  for (const result of results) {
    mkdirSync(join(dir, `e2e-result-${result.version}`, result.version), { recursive: true });
    writeFileSync(join(dir, `e2e-result-${result.version}`, result.version, 'result.json'), JSON.stringify(result));
  }
  const { status, stdout } = spawnSync(process.execPath, [REPORT, dir, 'E2E'], { encoding: 'utf8' });
  return { status, markdown: stdout };
}

/** A result.json with one variant, whose status is the version's. */
const result = (version, protocol, scenarios, status = 'pass') => ({
  version,
  protocol,
  server: `paper ${version}`,
  knownBroken: null,
  seconds: 60,
  status,
  variants: [{ name: 'online', settings: 'online', status, scenarios, failures: [] }],
});

after(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

describe('report', () => {
  it('passes when only known-broken scenarios fail, and says why they do', () => {
    const { status, markdown } = report(
      result('1.20.1', 763, [
        { name: 'login', status: 'pass', detail: 'spawned' },
        { name: 'fallback-rejected', status: 'xfail', detail: 'socketClosed', knownBroken: 'no fallback before 1.20.2 (#44)' },
      ]),
    );

    assert.equal(status, 0);
    assert.match(markdown, /⚠️ \*\*1\*\* with known-broken scenarios/);
    assert.match(markdown, /⚠️ `fallback-rejected` \(known broken: no fallback before 1\.20\.2 \(#44\)\): socketClosed/);
  });

  it('fails when a version fails, and flags known failures that now pass', () => {
    const { status, markdown } = report(
      result('1.20.2', 764, [{ name: 'login', status: 'fail', detail: 'kicked' }], 'fail'),
      result('1.18.2', 758, [{ name: 'fallback-rejected', status: 'xpass', detail: 'landed on survival', knownBroken: 'x (#44)' }]),
    );

    assert.equal(status, 1);
    assert.match(markdown, /❌ \*\*1\*\* failed/);
    assert.match(markdown, /🎉 `fallback-rejected` passes/);
  });

  it('shows why a variant was skipped, without failing', () => {
    const passed = result('1.8.8', 47, [{ name: 'login', status: 'pass', detail: 'spawned' }]);
    passed.variants.push({ name: 'velocity', settings: 'online', status: 'skip', reason: 'Paper accepts Velocity forwarding from 1.13.1', scenarios: [], failures: [] });
    const skipped = { ...result('1.16', 735, [], 'skip'), variants: [{ ...passed.variants[1], reason: 'vanilla servers accept no forwarded player info, only Paper does' }] };

    const { status, markdown } = report(passed, skipped);

    assert.equal(status, 0);
    assert.match(markdown, /✅ \*\*1\*\* passed .* ⏭️ \*\*1\*\* skipped/);
    assert.match(markdown, /\| velocity \| .*\| ⏭️ Skipped: Paper accepts Velocity forwarding from 1\.13\.1 \|/);
    assert.doesNotMatch(markdown, /Failures and known issues/);
  });

  it('keeps log lines from breaking the table', () => {
    const { markdown } = report(
      result('1.20.2', 764, [{ name: 'login', status: 'fail', detail: 'a|b \\| <c>' }], 'fail'),
    );

    assert.ok(markdown.includes('a\\|b \\\\\\| &lt;c>'), markdown);
  });
});
