// Turns the collected results into a human-readable report: a Markdown file with one table per
// scenario, a few inline SVG charts, the exact versions and configuration used, and a fairness
// section stating what the harness did and did not control. Charts are plain inline SVG so the
// report renders on GitHub with no assets. Nothing is invented: every cell comes from a measured row
// or is left blank, and a proxy whose natives could not be confirmed is marked in the table. A
// measurement the validity gate rejected (validity.js) is printed as INVALID with its reasons, never
// as a figure, and is left out of the charts and of the slopes.
import { slope } from './heap.js';
import { percentile } from './stats.js';
import { THRESHOLDS } from './validity.js';

const FG = '#1f2933';
const GRID = '#d7dce2';
const BARS = ['#3a7bd5', '#2bb673', '#e8833a', '#9b59b6', '#d7504b'];

function num(v, digits = 0) {
  if (v === null || v === undefined || Number.isNaN(v)) return '-';
  return Number(v).toFixed(digits);
}

function bytesHuman(v) {
  if (v == null) return '-';
  if (v >= 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)} MiB/s`;
  if (v >= 1024) return `${(v / 1024).toFixed(0)} KiB/s`;
  return `${v.toFixed(0)} B/s`;
}

/** A horizontal bar chart comparing one value across proxies. Returns an inline SVG string. */
function barChart(title, series, { unit = '', digits = 1 } = {}) {
  const rows = series.filter((s) => s.value != null && !Number.isNaN(s.value));
  if (!rows.length) return '';
  const max = Math.max(...rows.map((s) => s.value), 0) || 1;
  const w = 520;
  const rowH = 28;
  const left = 150;
  const h = rows.length * rowH + 36;
  const bars = rows
    .map((s, i) => {
      const y = 24 + i * rowH;
      const len = Math.max(1, ((w - left - 70) * s.value) / max);
      const color = BARS[i % BARS.length];
      return `<text x="${left - 8}" y="${y + 14}" text-anchor="end" font-size="12" fill="${FG}">${escape(s.label)}</text>
<rect x="${left}" y="${y + 4}" width="${len.toFixed(1)}" height="16" rx="3" fill="${color}"/>
<text x="${(left + len + 6).toFixed(1)}" y="${y + 17}" font-size="12" fill="${FG}">${num(s.value, digits)}${unit}</text>`;
    })
    .join('\n');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" role="img" aria-label="${escape(title)}">
<text x="8" y="16" font-size="13" font-weight="bold" fill="${FG}">${escape(title)}</text>
<line x1="${left}" y1="20" x2="${left}" y2="${h - 8}" stroke="${GRID}"/>
${bars}
</svg>`;
}

function escape(s) {
  return String(s).replace(
    /[<>&"']/g,
    (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&#39;' })[c],
  );
}

/** A time series of one sampler column, one line per proxy. Used for the traffic CPU/RSS trace. */
function lineChart(title, serieses, key, { unit = '' } = {}) {
  const all = serieses.flatMap((s) => s.rows.map((r) => r[key]).filter((v) => typeof v === 'number'));
  if (!all.length) return '';
  const maxT = Math.max(...serieses.flatMap((s) => s.rows.map((r) => r.t)), 1);
  const maxV = Math.max(...all, 1);
  const w = 560;
  const h = 200;
  const pad = 36;
  const x = (t) => pad + (t / maxT) * (w - pad - 10);
  const y = (v) => h - pad - (v / maxV) * (h - pad - 16);
  const lines = serieses
    .map((s, i) => {
      const pts = s.rows.filter((r) => typeof r[key] === 'number').map((r) => `${x(r.t).toFixed(1)},${y(r[key]).toFixed(1)}`);
      if (!pts.length) return '';
      return `<polyline fill="none" stroke="${BARS[i % BARS.length]}" stroke-width="1.5" points="${pts.join(' ')}"/>
<text x="${w - 120}" y="${20 + i * 14}" font-size="11" fill="${BARS[i % BARS.length]}">${escape(s.label)}</text>`;
    })
    .join('\n');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" role="img" aria-label="${escape(title)}">
<text x="8" y="14" font-size="13" font-weight="bold" fill="${FG}">${escape(title)} (${escape(unit)})</text>
<line x1="${pad}" y1="${h - pad}" x2="${w - 10}" y2="${h - pad}" stroke="${GRID}"/>
<line x1="${pad}" y1="16" x2="${pad}" y2="${h - pad}" stroke="${GRID}"/>
<text x="${pad}" y="${h - pad + 14}" font-size="10" fill="${FG}">0</text>
<text x="${w - 20}" y="${h - pad + 14}" font-size="10" fill="${FG}">${maxT.toFixed(0)}s</text>
<text x="4" y="24" font-size="10" fill="${FG}">${maxV.toFixed(0)}</text>
${lines}
</svg>`;
}

function fairnessRow(r) {
  if (!r.natives) return `${r.label}: not launched`;
  let flag;
  if (r.natives.checked === false) flag = 'none to check (no native compression or transport to load; standard library)';
  else flag = r.natives.ok ? 'confirmed' : 'NOT CONFIRMED (run marked unfair)';
  return `**${r.label}** (forwarding: ${r.forwarding}) - natives ${flag}\n` + r.natives.notes.map((n) => `  - ${n}`).join('\n');
}

const ok = (m) => Boolean(m) && m.validity?.valid !== false;
const mib = (v) => (v === null || v === undefined ? '-' : `${num(v, 0)} MiB`);
const ms = (v, digits = 3) => (v === null || v === undefined ? '-' : `${num(v, digits)} ms`);
const minTps = (backends = []) => {
  const values = backends.map((b) => b.tps).filter((v) => typeof v === 'number');
  return values.length ? num(Math.min(...values), 1) : '-';
};

/** A table row for a measurement the gate rejected: INVALID, then empty cells. */
function invalidRow(lead, columns) {
  return `| ${lead.join(' | ')} | **INVALID** |${' - |'.repeat(columns - lead.length - 1)}`;
}

/** The list of rejected measurements of one section, with their reasons. */
function invalidNotes(out, entries) {
  const bad = entries.filter((e) => e.validity && !e.validity.valid);
  if (!bad.length) return;
  out.push('Rejected by the validity gate (not comparable, figures withheld):');
  out.push('');
  for (const e of bad) out.push(`- **${e.what}**: ${e.validity.reasons.join('; ')}`);
  out.push('');
}

/**
 * Builds the Markdown report.
 * @param {object} input
 * @param {object[]} input.results per-proxy results from runProxy
 * @param {object} input.env fingerprint
 * @param {object} input.versions parsed versions.json
 * @param {string} input.profileName 'full' or 'smoke'
 * @param {string} input.date folder date
 * @param {string} input.cpuNote the CPU pinning plan, in words
 * @param {object} [input.profile] the scenario sizes the run used
 * @param {string[]} [input.pending] proxies of the run that have not finished (a partial report)
 * @returns {string} Markdown
 */
export function buildReport({ results, env, versions, profileName, date, cpuNote, profile = null, pending = [] }) {
  const out = [];
  out.push(`# Warp macro benchmark - ${date}`);
  out.push('');
  out.push(`Profile: **${profileName}**. Proxies: ${results.map((r) => r.label).join(', ')}.`);
  out.push('');
  if (pending.length) {
    out.push(`> Partial report: ${pending.join(', ')} had not finished when this was written.`);
    out.push('');
  }
  if (profileName === 'smoke') {
    out.push('> Smoke profile: a handful of bots and windows of a few seconds. It proves that the harness runs end to end against each proxy; its figures are not benchmark results.');
    out.push('');
  }
  if (env.isWsl) {
    out.push('> This run was on WSL2. Timing numbers are not credible here (no CPU isolation, hypervisor scheduling noise). Treat this as a functional run only. See docs/benchmarks/README.md, hardware tiers.');
    out.push('');
  }
  out.push('## How to read this');
  out.push('');
  out.push('Every number is measured from outside the proxy (the kernel\'s view in /proc, the JVM\'s own heap accounting through jcmd, and client-side timing), never from the proxy\'s own metrics. Each scenario warmed up before measuring and was repeated; tables show the median of the repetitions. Raw per-second samples are in the CSV files next to this report, the full per-repetition records in `metrics/`, and the exact configs under `configs/`.');
  out.push('');
  out.push(`A figure is printed only when the measurement passed the validity gate: at least ${THRESHOLDS.minAchievedRatio * 100}% of the offered connections established and kept, every Paper backend at ${THRESHOLDS.minBackendTps} TPS or more with no overload warning and no error, the stub backend and the load generators not saturated (event loop at most ${THRESHOLDS.maxLoadgenLagP99Ms} ms late at p99), and no proxy protection engaged. Otherwise the cell says INVALID and the reasons are listed under the table.`);
  out.push('');

  // --- Fairness / natives ---
  out.push('## Fairness and natives');
  out.push('');
  out.push(results.map(fairnessRow).join('\n\n'));
  out.push('');
  out.push('Every proxy runs with equivalent protections, which means none: per-address rate limits and quotas are off for all of them and Warp\'s anti-bot (verification and attack detection, which no other proxy here has) is off. Each scenario starts a fresh proxy process.');
  out.push('');
  out.push(`CPU pinning: ${cpuNote}`);
  out.push('');
  if (profile) {
    out.push(`Sizes: \`${JSON.stringify(profile)}\``);
    out.push('');
  }

  // --- Idle ---
  if (results.some((r) => r.metrics.idle)) {
    out.push('## Idle connections');
    out.push('');
    out.push('N connections held in the PLAY state against the stub backend, which sends each of them one keep-alive every 15 seconds and nothing else: the connections are really idle, so this isolates what a connection at rest costs the proxy. "Connected" is the lowest count still connected at the end of a window, over the repetitions.');
    out.push('');
    out.push('The JVM proxies run with `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` (the identical headline flags), so their resident memory sits near 2.3 GiB from start-up whatever they hold: for them, read the live heap (heap in use after a forced full collection, taken after each window). A Go proxy has no fixed heap: its resident memory is the figure to read.');
    out.push('');
    out.push('| Proxy | offered | connected | RSS | live heap | FDs | CPU % |');
    out.push('|---|--:|--:|--:|--:|--:|--:|');
    for (const r of results) {
      for (const l of r.metrics.idle?.levels ?? []) {
        if (!ok(l)) out.push(invalidRow([r.label, l.level, l.online], 7));
        else out.push(`| ${r.label} | ${l.level} | ${l.online} | ${mib(l.rssMiB)} | ${mib(l.heapMiB)} | ${num(l.fds, 0)} | ${num(l.cpuPct, 0)} |`);
      }
    }
    out.push('');
    invalidNotes(out, results.flatMap((r) => (r.metrics.idle?.levels ?? []).map((l) => ({ what: `${r.label} @ ${l.level}`, validity: l.validity }))));
    out.push('Memory per connection, as the slope between the lowest and the highest valid level (one absolute figure would mostly show the fixed cost of the runtime):');
    out.push('');
    out.push('| Proxy | levels | RSS per connection | live heap per connection |');
    out.push('|---|--:|--:|--:|');
    const kib = (v) => (v === null ? '-' : `${num(v * 1024, 1)} KiB`);
    const slopes = results.map((r) => {
      const valid = (r.metrics.idle?.levels ?? []).filter(ok);
      return {
        label: r.label,
        levels: valid.length >= 2 ? `${valid[0].level} to ${valid.at(-1).level}` : '-',
        rss: slope(valid.map((l) => ({ x: l.level, y: l.rssMiB }))),
        heap: slope(valid.map((l) => ({ x: l.level, y: l.heapMiB }))),
      };
    });
    for (const s of slopes) out.push(`| ${s.label} | ${s.levels} | ${kib(s.rss)} | ${kib(s.heap)} |`);
    out.push('');
    out.push(barChart('Memory per idle connection (KiB): live heap for a JVM, RSS for a Go proxy', slopes.map((s) => ({ label: s.label, value: s.heap !== null ? s.heap * 1024 : s.rss !== null ? s.rss * 1024 : null })), { unit: ' KiB', digits: 1 }));
    out.push('');
  }

  // --- Traffic ---
  if (results.some((r) => r.metrics.traffic)) {
    out.push('## Heavy traffic (movers streaming chunks)');
    out.push('');
    out.push('Raw clients walk back and forth at sprint speed on one Paper backend, so the backend streams chunks through the proxy for the whole window. Throughput is the proxy\'s bytes written per second at the syscall boundary (writes alone: every forwarded byte is written exactly once by every proxy, while read accounting differs between a JVM and a Go runtime); "delivered" is what the clients received, counted on their sockets; CPU per MB is proxy CPU-seconds divided by MB written in the window. The comparison only holds because every proxy carried the same number of bots: a proxy that did not is INVALID.');
    out.push('');
    out.push('| Proxy | offered | connected | throughput | delivered | CPU % | CPU-s/MB | RSS | live heap | backend TPS |');
    out.push('|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|');
    for (const r of results) {
      const m = r.metrics.traffic;
      if (!m) out.push(`| ${r.label} | - | - | - | - | - | - | - | - | - |`);
      else if (!ok(m)) out.push(invalidRow([r.label, m.bots, m.online], 10));
      else out.push(`| ${r.label} | ${m.bots} | ${m.online} | ${bytesHuman(m.bytesPerSec)} | ${bytesHuman(m.deliveredBytesPerSec)} | ${num(m.cpuPct, 0)} | ${num(m.cpuPerMb, 3)} | ${mib(m.rssMiB)} | ${mib(m.heapMiB)} | ${num(m.backendTps, 1)} |`);
    }
    out.push('');
    invalidNotes(out, results.map((r) => ({ what: r.label, validity: r.metrics.traffic?.validity })));
    out.push(barChart('CPU seconds per MB forwarded (lower is better)', results.map((r) => ({ label: r.label, value: ok(r.metrics.traffic) ? r.metrics.traffic.cpuPerMb : null })), { digits: 3 }));
    out.push('');
    out.push(lineChart('Proxy CPU during traffic', results.filter((r) => r.rows?.length && ok(r.metrics.traffic)).map((r) => ({ label: r.label, rows: (r.rows ?? []).filter((x) => x.scenario === 'traffic') })), 'cpuPct', { unit: '%' }));
    out.push('');
  }

  // --- Latency ---
  if (results.some((r) => r.metrics.latency)) {
    out.push('## Added latency at low load');
    out.push('');
    const lat = results.map((r) => r.metrics.latency).find(Boolean);
    out.push(`Round trip of the PLAY ping request (which the server answers from its network thread, without waiting for a game tick), timed by a raw client with the monotonic nanosecond clock, through the proxy versus straight to a forwarding-free clone backend, with the same ${lat?.backgroundBots ?? 0} background movers on both. The probe runs alone in its own process on its own core. Added latency is the difference of the percentiles; it is the proxy's own cost on the path. The probe waits for its join burst to end, discards ${lat?.warmup ?? 0} warm-up rounds, and counts a round slower than ${lat?.timeoutMs ?? 2000} ms at that value (the timeouts column, through the proxy / direct). A direct median above ${THRESHOLDS.maxDirectP50Ms} ms means the path itself was loaded and the result is INVALID.`);
    out.push('');
    out.push('| Proxy | via proxy p50 | via proxy p99 | direct p50 | direct p99 | added p50 | added p99 | n | timeouts |');
    out.push('|---|--:|--:|--:|--:|--:|--:|--:|--:|');
    for (const r of results) {
      const m = r.metrics.latency;
      if (!m) out.push(`| ${r.label} | - | - | - | - | - | - | - | - |`);
      else if (!ok(m)) out.push(invalidRow([r.label], 9));
      else out.push(`| ${r.label} | ${ms(m.viaProxy.p50)} | ${ms(m.viaProxy.p99)} | ${ms(m.direct.p50)} | ${ms(m.direct.p99)} | ${ms(m.added.p50)} | ${ms(m.added.p99)} | ${m.viaProxy.n} | ${m.viaProxy.timeouts} / ${m.direct.timeouts} |`);
    }
    out.push('');
    invalidNotes(out, results.map((r) => ({ what: r.label, validity: r.metrics.latency?.validity })));
    out.push(barChart('Added p50 latency (ms, lower is better)', results.map((r) => ({ label: r.label, value: ok(r.metrics.latency) ? r.metrics.latency.added.p50 : null })), { unit: ' ms', digits: 3 }));
    out.push('');
  }

  // --- Switch ---
  if (results.some((r) => r.metrics.switch)) {
    out.push('## Switch storm');
    out.push('');
    out.push('Bots repeatedly run /server to change backend, resting between two switches so that the backends (a switch is a full login and a view distance of chunks for one of them) are not the limit. Reports sustained switches per second, failures, and how long a switch took: from the command to the first chunk of the new backend (proxy hand-off plus backend login), and to six chunks (world arriving). "Backend TPS" is the lowest of the three backends.');
    out.push('');
    out.push('| Proxy | offered | connected | switches | /s | failures | first chunk p50 | first chunk p99 | 6 chunks p50 | 6 chunks p99 | backend TPS |');
    out.push('|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|');
    for (const r of results) {
      const m = r.metrics.switch;
      if (!m) out.push(`| ${r.label} | - | - | - | - | - | - | - | - | - | - |`);
      else if (m.skipped) out.push(`| ${r.label} | - | - | - | - | - | - | - | - | - | ${m.skipped} |`);
      else if (!ok(m)) out.push(invalidRow([r.label, m.bots, m.online], 11));
      else out.push(`| ${r.label} | ${m.bots} | ${m.online} | ${m.switches} | ${num(m.switchesPerSec, 1)} | ${m.failures} | ${ms(m.firstChunkP50Ms, 0)} | ${ms(m.firstChunkP99Ms, 0)} | ${ms(m.p50Ms, 0)} | ${ms(m.p99Ms, 0)} | ${minTps(m.backends)} |`);
    }
    out.push('');
    invalidNotes(out, results.map((r) => ({ what: r.label, validity: r.metrics.switch?.validity })));
  }

  // --- Storm ---
  if (results.some((r) => r.metrics.storm)) {
    out.push('## Connection / status storm');
    out.push('');
    out.push('Hostile connections (a handshake and a status request each, held open for three seconds) at a rising rate while a genuine player tries to join once, halfway through. The key question is whether the real player still gets in, and at what CPU cost. The flood and the player come from the same loopback address, so the per-address protections of every proxy are off here as in the other scenarios (with them on, each proxy would throttle the genuine player together with the flood). A protection that is not per address stays on and may engage: it is listed under the table with the share of the flood the proxy accepted.');
    out.push('');
    const rates = [...new Set(results.flatMap((r) => (r.metrics.storm?.rates ?? []).map((x) => x.rate)))].sort((a, b) => a - b);
    out.push('Median CPU is the highest per-rate median over the flood; peak CPU is the highest one-second sample in any flood window.');
    out.push('');
    out.push(`| Proxy | ${rates.map((x) => `joined @ ${x}/s`).join(' | ')} | median CPU % | peak CPU % |`);
    out.push(`|---|${rates.map(() => ':--:').join('|')}|--:|--:|`);
    for (const r of results) {
      const stormRates = r.metrics.storm?.rates ?? [];
      const byRate = Object.fromEntries(stormRates.map((x) => [x.rate, x]));
      const cells = rates.map((x) => (byRate[x] ? (ok(byRate[x]) ? (byRate[x].joined ? 'yes' : 'NO') : '**INVALID**') : '-'));
      const maxOf = (key) => {
        const vals = stormRates.filter(ok).map((x) => x[key]).filter((v) => typeof v === 'number');
        return vals.length ? Math.max(...vals) : null;
      };
      out.push(`| ${r.label} | ${cells.join(' | ')} | ${num(maxOf('cpuPct'), 0)} | ${num(maxOf('cpuPeakPct'), 0)} |`);
    }
    out.push('');
    invalidNotes(out, results.flatMap((r) => (r.metrics.storm?.rates ?? []).map((x) => ({ what: `${r.label} @ ${x.rate}/s`, validity: x.validity }))));
    const reactions = results.flatMap((r) =>
      (r.metrics.storm?.rates ?? [])
        .filter((x) => ok(x) && x.attempted > 0 && (x.protections > 0 || x.opened / x.attempted < 0.99 || (!x.joined && x.joinError)))
        .map((x) => {
          const parts = [`accepted ${Math.round((x.opened / x.attempted) * 100)}% of the ${x.attempted} hostile connections`];
          if (x.protections > 0) parts.push(`a protection engaged (${x.protectionSample})`);
          if (!x.joined && x.joinError) parts.push(`the genuine player was kept out: ${String(x.joinError).split('\n')[0]}`);
          return `- **${r.label} @ ${x.rate}/s**: ${parts.join('; ')}`;
        }),
    );
    if (reactions.length) {
      out.push('How the proxies reacted to the flood:');
      out.push('');
      out.push(...reactions);
      out.push('');
    }
  }

  // --- Errors ---
  const withErrors = results.filter((r) => r.errors.length || r.proxyFailures?.length);
  if (withErrors.length) {
    out.push('## Run notes and errors');
    out.push('');
    for (const r of withErrors) {
      out.push(`- **${r.label}**: ${[...r.errors, ...(r.proxyFailures ?? [])].slice(0, 8).join('; ')}`);
    }
    out.push('');
  }

  // --- Versions ---
  out.push('## Exact versions');
  out.push('');
  out.push('| Component | Version | SHA-256 |');
  out.push('|---|---|---|');
  out.push(`| Backend | Paper ${versions.backend.version} build ${versions.backend.build} | \`${versions.backend.sha256.slice(0, 16)}...\` |`);
  for (const p of Object.values(versions.proxies)) {
    out.push(`| ${p.label} | ${p.version}${p.build ? ` build ${p.build}` : ''} | \`${p.sha256.slice(0, 16)}...\` |`);
  }
  out.push('');

  // --- Environment ---
  out.push('## Environment');
  out.push('');
  out.push('```json');
  out.push(JSON.stringify(env, null, 2));
  out.push('```');
  out.push('');
  return out.join('\n');
}

export { percentile };
