// Small statistics shared by the scenarios and the report, with no other dependencies so the unit
// tests can exercise them without pulling in the bot client.

/** p-th percentile (0..100) of a numeric array, or null if empty. */
export function percentile(values, p) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

/** The median of a numeric array, or null if empty. */
export function median(values) {
  return percentile(values, 50);
}
