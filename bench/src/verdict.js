// Decides whether a run proved the harness works. The report is written regardless; this is the
// exit status, so CI fails when a proxy did not launch, a scenario threw, or a scenario ran but no
// bot ever got through (a forwarding mismatch kicks every bot, and the pools record that as failures
// and empty metrics rather than throwing). A proxy that merely performed badly (a player kept out by
// the storm, slow switches) is a result, not a failure.

/**
 * @param {object[]} results per-proxy results from runProxy
 * @returns {string[]} one line per failure, empty when the run is sound
 */
export function harnessFailures(results) {
  const out = [];
  for (const r of results) {
    for (const e of r.errors ?? []) out.push(`${r.id}: ${e}`);
    const m = r.metrics ?? {};
    for (const level of m.idle?.levels ?? []) {
      if (level.online === 0) out.push(`${r.id}: idle @ ${level.level}: no connection stayed online (${level.failures} failures)`);
    }
    if (m.traffic && m.traffic.online === 0) out.push(`${r.id}: traffic: no bot stayed online`);
    if (m.switch && !m.switch.skipped && m.switch.online === 0) out.push(`${r.id}: switch: no bot stayed online`);
    if (m.latency && m.latency.viaProxy?.n === 0) out.push(`${r.id}: latency: no round completed through the proxy`);
  }
  return out;
}
