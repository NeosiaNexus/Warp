// Merges the result.json of every E2E job into one Markdown report (the run summary on GitHub).
//
//   node e2e/src/report.js DIR [TITLE]   # prints Markdown; exits 1 if any version failed
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { SCENARIOS } from './scenarios.js';

const [dir, title = 'End-to-end tests'] = process.argv.slice(2);

function collect(path) {
  if (statSync(path).isDirectory()) return readdirSync(path).flatMap((name) => collect(join(path, name)));
  return path.endsWith('result.json') ? [JSON.parse(readFileSync(path, 'utf8'))] : [];
}

const results = collect(dir).sort((a, b) => a.protocol - b.protocol);
const count = (status) => results.filter((r) => r.status === status).length;
const STATUS = { pass: '✅ Passed', fail: '❌ Failed', xfail: '⚠️ Known broken', xpass: '🎉 Passes, remove from known broken' };
const SCENARIO = { pass: '✅', fail: '❌', skip: '⏭️' };

const lines = [`## ${title}`, ''];
const totals = [`✅ **${count('pass')}** passed`, `❌ **${count('fail')}** failed`, `⚠️ **${count('xfail')}** known broken`];
if (count('xpass')) totals.push(`🎉 **${count('xpass')}** fixed`);
const minutes = Math.round(results.reduce((sum, r) => sum + (r.seconds ?? 0), 0) / 60);
lines.push(`${totals.join(' · ')} · ${results.length} versions, ${minutes} min of test time`, '');

if (!results.length) {
  lines.push('_No result was produced: see the job logs._');
} else {
  // One column per scenario that ran somewhere, in execution order.
  const ran = new Set(results.flatMap((r) => r.variants.flatMap((v) => v.scenarios.map((s) => s.name))));
  const scenarios = SCENARIOS.map((s) => s.name).filter((name) => ran.has(name));
  lines.push(`| Version | Protocol | Server | Variant | ${scenarios.join(' | ')} | Result |`);
  lines.push(`|---|--:|---|---|${scenarios.map(() => ':-:').join('|')}|---|`);
  for (const r of results) {
    r.variants.forEach((v, i) => {
      const cells = scenarios.map((name) => SCENARIO[v.scenarios.find((s) => s.name === name)?.status] ?? '');
      const status = r.knownBroken ? (v.status === 'pass' ? 'xpass' : 'xfail') : v.status;
      const first = i === 0;
      lines.push(`| ${first ? `**${r.version}**` : ''} | ${first ? r.protocol : ''} | ${first ? r.server : ''} | ${v.name} | ${cells.join(' | ')} | ${STATUS[status]} |`);
    });
  }

  const failures = results.filter((r) => r.status === 'fail' || r.status === 'xfail');
  if (failures.length) {
    lines.push('', '### Failures', '');
    for (const r of failures) {
      const why = r.knownBroken ? ` (known broken: ${r.knownBroken})` : '';
      lines.push(`<details><summary><b>${r.version}</b> (protocol ${r.protocol})${why}</summary>`, '');
      for (const v of r.variants.filter((x) => x.status === 'fail')) {
        lines.push(`**${v.name}** (${v.settings ?? 'setup'})`, '');
        for (const s of v.scenarios.filter((x) => x.status === 'fail')) lines.push(`- \`${s.name}\`: ${escape(s.detail)}`);
        for (const f of v.failures ?? []) lines.push(`- ${escape(f.split('\n')[0])}`);
        lines.push('');
      }
      lines.push('</details>', '');
    }
  }
}

process.stdout.write(`${lines.join('\n')}\n`);
process.exit(count('fail') ? 1 : 0);

function escape(text) {
  return String(text).replace(/\|/g, '\\|').replace(/</g, '&lt;').slice(0, 400);
}
