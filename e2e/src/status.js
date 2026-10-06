// Statuses of a run's versions and variants: pass, fail and skip, and for a known-broken version
// xfail (fails, as expected) and xpass (passes: fixed, remove it from the list).

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
