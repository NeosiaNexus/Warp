// Prints the GitHub Actions matrix (one job per Minecraft version) for a tier of versions.json:
//
//   node e2e/src/matrix.js --tier pr
//   node e2e/src/matrix.js --tier full
//   node e2e/src/matrix.js --tier full --versions 1.8.8,26.3 --variants online,offline
import { parseArgs } from 'node:util';

import { serverId } from './backend.js';
import { findEntry, jobsForTier, loadMatrix } from './versions.js';

const { values: opts } = parseArgs({
  options: {
    tier: { type: 'string', default: 'pr' },
    versions: { type: 'string', default: '' },
    variants: { type: 'string', default: '' },
  },
});

const matrix = loadMatrix();
let jobs = jobsForTier(matrix, opts.tier);
if (opts.versions) {
  const wanted = opts.versions.split(',').map((key) => findEntry(matrix, key.trim()).version);
  jobs = jobs.filter((job) => wanted.includes(job.mc));
}
if (opts.variants) jobs = jobs.map((job) => ({ ...job, variants: opts.variants }));

const include = jobs.map((job) => {
  const entry = findEntry(matrix, job.mc);
  const variants = job.variants.split(',').length;
  const brokenScenarios = Object.keys(entry.knownBrokenScenarios ?? {}).length;
  const notes = [
    variants > 1 ? `${variants} variants` : null,
    entry.via ? 'via ViaProxy' : null,
    job.known_broken ? 'known broken' : null,
    brokenScenarios ? `${brokenScenarios} known-broken scenario${brokenScenarios > 1 ? 's' : ''}` : null,
  ];
  return {
    id: job.mc,
    label: [`${job.mc} (${job.protocol})`, ...notes.filter(Boolean)].join(' · '),
    runs: `${job.mc}=${job.variants}`,
    // The server's Java, plus Java 25 for Warp (and ViaProxy).
    java: [...new Set([entry.java, 25])].join('\n'),
    server: serverId(entry.server),
    via: Boolean(entry.via),
  };
});
process.stdout.write(JSON.stringify({ include }));
