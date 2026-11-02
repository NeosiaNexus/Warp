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

/** Warp's `forwarding.mode` values: how it tells backends who the player is. */
export const FORWARDING_MODES = ['none', 'velocity'];

/** Resolves a variant name, or the flags of an ad-hoc run, into Warp/backend settings. */
export function resolveVariant(matrix, name, overrides = {}) {
  const preset = matrix.variants[name];
  if (!preset) throw new Error(`unknown variant "${name}". Known: ${Object.keys(matrix.variants).join(', ')}`);
  const settings = { ...matrix.variants.defaults, ...preset, ...overrides };
  if (settings.backendThreshold === undefined) settings.backendThreshold = settings.threshold;
  if (!FORWARDING_MODES.includes(settings.forwarding)) {
    throw new Error(`variant "${name}": forwarding "${settings.forwarding}" is not one of ${FORWARDING_MODES.join(', ')}`);
  }
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

/** The first protocol ViaVersion translates to without ViaRewind: 1.9. */
const V1_9 = 107;

/**
 * Which part of the via plugin translates bots of protocol `client` for backends of `backend`:
 * ViaRewind below 1.9, ViaBackwards for other older bots, ViaVersion for newer ones.
 */
export function translator(client, backend) {
  if (client < V1_9 && backend >= V1_9) return 'ViaRewind';
  return client < backend ? 'ViaBackwards' : 'ViaVersion';
}

/**
 * Expands the translation runs of a tier (`translations`) into CI jobs: bots of `client` on the
 * backends of `mc` (and `survival`, when it differs), Warp translating with the via plugin. Each
 * runs its own `variants`, else the tier's.
 */
export function translationsForTier(matrix, tierName) {
  const tier = matrix.tiers[tierName];
  if (!tier) throw new Error(`unknown tier "${tierName}". Known: ${Object.keys(matrix.tiers).join(', ')}`);
  return (tier.translations ?? []).map((run) => {
    const entry = findEntry(matrix, run.mc);
    const client = findEntry(matrix, run.client);
    const survival = run.survival ? findEntry(matrix, run.survival) : null;
    if (client.version === entry.version) throw new Error(`tier ${tierName}: translation of ${run.client} bots on ${run.mc} translates nothing`);
    return {
      id: `${client.version}-on-${entry.version}${survival ? `-and-${survival.version}` : ''}`,
      mc: entry.version,
      client: client.version,
      survival: survival?.version ?? '',
      protocol: entry.protocol,
      java: entry.java,
      variants: (run.variants ?? tier.variants).join(','),
      translator: translator(client.protocol, entry.protocol),
      known_broken: Boolean(entry.knownBroken || client.knownBroken),
    };
  });
}
