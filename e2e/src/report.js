// Merges the result.json of every E2E job into one Markdown report (the run summary on GitHub).
//
//   node e2e/src/report.js DIR [TITLE]   # prints Markdown; exits 1 if any version failed
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { SCENARIOS } from './scenarios.js';
import { variantStatus } from './status.js';

const [dir, title = 'End-to-end tests'] = process.argv.slice(2);

/** Every result.json under `dir` (one directory per downloaded artifact). */
function collect(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return collect(path);
    return entry.name === 'result.json' ? [JSON.parse(readFileSync(path, 'utf8'))] : [];
  });
}

const results = collect(dir).sort((a, b) => a.protocol - b.protocol);
const count = (status) => results.filter((r) => r.status === status).length;
const STATUS = { pass: '✅ Passed', fail: '❌ Failed', xfail: '⚠️ Known broken', xpass: '🎉 Passes, remove from known broken', skip: '⏭️ Skipped' };
const SCENARIO = { pass: '✅', fail: '❌', skip: '⏭️', xfail: '⚠️', xpass: '🎉' };
const scenarios = (r, status) => r.variants.flatMap((v) => v.scenarios.filter((s) => s.status === status));

const lines = [`## ${title}`, ''];
const totals = [`✅ **${count('pass')}** passed`, `❌ **${count('fail')}** failed`, `⚠️ **${count('xfail')}** known broken`];
const partly = results.filter((r) => scenarios(r, 'xfail').length).length;
if (partly) totals.push(`⚠️ **${partly}** with known-broken scenarios`);
const fixed = count('xpass') + results.filter((r) => scenarios(r, 'xpass').length).length;
if (fixed) totals.push(`🎉 **${fixed}** fixed`);
if (count('skip')) totals.push(`⏭️ **${count('skip')}** skipped`);
const minutes = Math.round(results.reduce((sum, r) => sum + (r.seconds ?? 0), 0) / 60);
lines.push(`${totals.join(' · ')} · ${results.length} versions, ${minutes} min of test time`, '');

if (!results.length) {
  lines.push('_No result was produced: see the job logs._');
} else {
  // One column per scenario that ran somewhere, in execution order.
  const ran = new Set(results.flatMap((r) => r.variants.flatMap((v) => v.scenarios.map((s) => s.name))));
  const columns = SCENARIOS.map((s) => s.name).filter((name) => ran.has(name));
  lines.push(`| Version | Protocol | Server | Variant | ${columns.join(' | ')} | Result |`);
  lines.push(`|---|--:|---|---|${columns.map(() => ':-:').join('|')}|---|`);
  for (const r of results) {
    r.variants.forEach((v, i) => {
      const cells = columns.map((name) => SCENARIO[v.scenarios.find((s) => s.name === name)?.status] ?? '');
      const status = variantStatus(r, v);
      const result = status === 'skip' ? `${STATUS.skip}: ${escape(v.reason)}` : STATUS[status];
      const first = i === 0;
      lines.push(`| ${first ? `**${r.version}**` : ''} | ${first ? r.protocol : ''} | ${first ? r.server : ''} | ${v.name} | ${cells.join(' | ')} | ${result} |`);
    });
  }

  // Failures, then what is known broken: whole versions, or single scenarios of a version.
  const known = (s) => s.status === 'xfail' || s.status === 'xpass';
  const notable = results.filter((r) => !['pass', 'skip'].includes(r.status) || r.variants.some((v) => v.scenarios.some(known)));
  if (notable.length) {
    lines.push('', '### Failures and known issues', '');
    for (const r of notable) {
      const why = r.knownBroken ? ` (known broken: ${escape(r.knownBroken)})` : '';
      lines.push(`<details><summary><b>${r.version}</b> (protocol ${r.protocol})${why}</summary>`, '');
      if (r.status === 'xpass') lines.push('🎉 Every variant passes: remove `knownBroken` from its entry in `e2e/versions.json`.', '');
      for (const v of r.variants.filter((x) => x.status === 'fail' || x.scenarios.some(known))) {
        lines.push(`**${v.name}** (${v.settings ?? 'setup'})`, '');
        for (const s of v.scenarios) {
          if (s.status === 'fail') lines.push(`- \`${s.name}\`: ${escape(s.detail)}`);
          if (s.status === 'xfail') lines.push(`- ⚠️ \`${s.name}\` (known broken: ${escape(s.knownBroken)}): ${escape(s.detail)}`);
          if (s.status === 'xpass') lines.push(`- 🎉 \`${s.name}\` passes: remove it from \`knownBrokenScenarios\``);
        }
        for (const f of v.failures ?? []) lines.push(`- ${escape(f.split('\n')[0])}`);
        lines.push('');
      }
      lines.push('</details>', '');
    }
  }
}

process.stdout.write(`${lines.join('\n')}\n`);
process.exit(count('fail') ? 1 : 0);

/** Makes text safe in a Markdown table cell: backslashes first, then pipes and tags. */
function escape(text) {
  return String(text).replace(/\\/g, '\\\\').replace(/\|/g, '\\|').replace(/</g, '&lt;').slice(0, 400);
}
