// Statuses of a run's versions and variants: pass, fail and skip, and for a known-broken version
// xfail (fails, as expected) and xpass (passes: fixed, remove it from the list). And what else the
// reports say about a version: the backends booted again, the server quirks worked around.
import { QUIRKS } from './scenarios.js';

/** A version's status, from whether the variants it ran passed and whether it is known broken. */
export function versionStatus(passed, knownBroken) {
  if (knownBroken) return passed ? 'xpass' : 'xfail';
  return passed ? 'pass' : 'fail';
}

/**
 * How a variant of a version (an entry of `result.json`) is reported: the variants of a
 * known-broken version are xfail or xpass, except the ones it skipped.
 */
export function variantStatus(result, variant) {
  if (!result.knownBroken || variant.status === 'skip') return variant.status;
  return variant.status === 'pass' ? 'xpass' : 'xfail';
}

/**
 * What a version's result (`result.json`) says besides statuses, one line each: every backend that
 * hung while booting and was booted again, and every quirk of its server the harness works around.
 */
export function versionNotes(result) {
  return [
    ...(result.bootRetries ?? []).map((r) => `${r.backend} hung while booting (${r.reason}), so it was booted again; its threads are in \`${r.log}\``),
    ...Object.entries(result.quirks ?? {}).map(([name, reason]) => `${QUIRKS[name]}, for the server quirk \`${name}\`: ${reason}`),
  ];
}
