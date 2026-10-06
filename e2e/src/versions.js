// The version matrix (e2e/versions.json): one entry per protocol version Warp supports, with the
// client version to announce, the server to run behind Warp, and the Java it needs.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const E2E_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');

export function loadMatrix(path = process.env.WARP_E2E_VERSIONS ?? join(E2E_DIR, 'versions.json')) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** Finds an entry by client version ("1.21.4"), server version, or protocol number ("769"). */
export function findEntry(matrix, key) {
  const entry = matrix.versions.find(
    (v) => v.client === key || v.server.version === key || String(v.protocol) === key || (v.aliases ?? []).includes(key),
  );
  if (!entry) {
    const known = matrix.versions.map((v) => v.client).join(', ');
    throw new Error(`unknown Minecraft version "${key}". Known: ${known}`);
  }
  return entry;
}

/** Resolves a variant name, or the flags of an ad-hoc run, into Warp/backend settings. */
export function resolveVariant(matrix, name, overrides = {}) {
  const preset = matrix.variants[name];
  if (!preset) throw new Error(`unknown variant "${name}". Known: ${Object.keys(matrix.variants).join(', ')}`);
  const settings = { ...matrix.variants.defaults, ...preset, ...overrides };
  if (settings.backendThreshold === undefined) settings.backendThreshold = settings.threshold;
  return { name, ...settings };
}

/**
 * Expands a tier into CI jobs. Each job boots one version's backends once and runs one or more
 * variants against them; known-broken entries are flagged so the job reports instead of failing.
 */
export function jobsForTier(matrix, tierName) {
  const tier = matrix.tiers[tierName];
  if (!tier) throw new Error(`unknown tier "${tierName}". Known: ${Object.keys(matrix.tiers).join(', ')}`);
  const selected = tier.versions === 'all' ? matrix.versions : tier.versions.map((key) => findEntry(matrix, key));
  return selected.map((entry) => {
    const extra = tier.extraVariants?.[entry.version] ?? [];
    const variants = [...tier.variants, ...extra.filter((v) => !tier.variants.includes(v))];
    return {
      id: entry.version,
      mc: entry.version,
      protocol: entry.protocol,
      java: entry.java,
      variants: variants.join(','),
      known_broken: Boolean(entry.knownBroken),
    };
  });
}
