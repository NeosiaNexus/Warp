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

/**
 * Throughput and efficiency over the sampler rows of one measure window. Bytes forwarded are the
 * proxy's `wchar` (bytes written) alone: every byte a proxy forwards is written exactly once, so
 * writes count the same thing for every proxy. `rchar` is not comparable across runtimes (a JVM's
 * Netty socket reads are not accounted in it while Go's are), so adding read bytes would compare
 * read+write for one proxy against write-only for another. Returns nulls for an empty window.
 */
export function trafficWindow(rows) {
  const forwarded = rows.map((r) => r.writeBytes ?? 0);
  const mbForwarded = forwarded.reduce((s, v) => s + v, 0) / 1024 / 1024;
  const cpuSeconds = rows.reduce((s, r) => s + (r.cpuPct ?? 0) / 100, 0);
  return {
    bytesPerSec: median(forwarded),
    mbForwarded,
    cpuSeconds,
    cpuPerMb: mbForwarded > 0 ? cpuSeconds / mbForwarded : null,
  };
}
