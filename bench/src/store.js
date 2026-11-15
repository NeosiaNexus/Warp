// What a run leaves on disk, and how to read it back. After each proxy the harness writes that
// proxy's metrics (metrics/<proxy>.json) and its per-second samples (csv/<proxy>.csv) and rebuilds
// the report from the files, so a run that is killed keeps the tables of every proxy that finished,
// and `--report-only <dir>` can rebuild the report of any run from its files alone.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { buildReport } from './report.js';

const TEXT_COLUMNS = new Set(['proxy', 'scenario', 'phase']);

/** Parses the sampler's CSV back into rows (numbers except for the text columns). */
export function parseCsv(text) {
  const [header, ...lines] = text.trim().split('\n');
  const columns = header.split(',');
  return lines.filter(Boolean).map((line) => {
    const cells = line.split(',');
    return Object.fromEntries(columns.map((c, i) => [c, TEXT_COLUMNS.has(c) ? cells[i] : Number(cells[i])]));
  });
}

/** Writes the facts about the run that the report needs besides the per-proxy results. */
export function saveRun(dir, run) {
  writeFileSync(join(dir, 'run.json'), `${JSON.stringify(run, null, 2)}\n`);
}

/** Writes one proxy's metrics and samples. */
export function saveResult(dir, result) {
  mkdirSync(join(dir, 'metrics'), { recursive: true });
  mkdirSync(join(dir, 'csv'), { recursive: true });
  const { rows, csv, ...metrics } = result;
  writeFileSync(join(dir, 'metrics', `${result.id}.json`), `${JSON.stringify(metrics, null, 2)}\n`);
  writeFileSync(join(dir, 'csv', `${result.id}.csv`), csv);
}

/**
 * Reads a results directory back: the run's facts, the host fingerprint, and the result of every
 * proxy that finished, in the order the run listed them.
 * @returns {{run: object, env: object, results: object[]}}
 */
export function loadRun(dir) {
  const read = (file) => JSON.parse(readFileSync(join(dir, file), 'utf8'));
  if (!existsSync(join(dir, 'run.json'))) throw new Error(`${dir}: no run.json (not a results directory of this harness)`);
  const run = read('run.json');
  const results = [];
  for (const id of run.proxies) {
    if (!existsSync(join(dir, 'metrics', `${id}.json`))) continue;
    const result = read(join('metrics', `${id}.json`));
    const csv = join(dir, 'csv', `${id}.csv`);
    result.rows = existsSync(csv) ? parseCsv(readFileSync(csv, 'utf8')) : [];
    results.push(result);
  }
  return { run, env: read('env.json'), results };
}

/** Rebuilds README.md in `dir` from the files of the run. Returns the results it found. */
export function writeReport(dir) {
  const { run, env, results } = loadRun(dir);
  const pending = run.proxies.filter((id) => !results.some((r) => r.id === id));
  writeFileSync(join(dir, 'README.md'), buildReport({ results, env, versions: run.versions, profileName: run.profileName, date: run.date, cpuNote: run.cpuNote, profile: run.profile, pending }));
  return results;
}
