// Turns the collected results into a human-readable report: a Markdown file with one table per
// scenario, a few inline SVG charts, the exact versions and configuration used, and a fairness
// section stating what the harness did and did not control. Charts are plain inline SVG so the
// report renders on GitHub with no assets. Nothing is invented: every cell comes from a measured row
// or is left blank, and a proxy whose natives could not be confirmed is marked in the table.
import { percentile } from './stats.js';

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
  return String(s).replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' })[c]);
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
  const flag = r.natives.ok ? 'confirmed' : 'NOT CONFIRMED (run marked unfair)';
  return `**${r.label}** (forwarding: ${r.forwarding}) - natives ${flag}\n` + r.natives.notes.map((n) => `  - ${n}`).join('\n');
}

/**
 * Builds the Markdown report.
 * @param {object} input
 * @param {object[]} input.results per-proxy results from runProxy
 * @param {object} input.env fingerprint
 * @param {object} input.versions parsed versions.json
 * @param {string} input.profileName 'full' or 'smoke'
 * @param {string} input.date folder date
 * @returns {string} Markdown
 */
export function buildReport({ results, env, versions, profileName, date, cpuNote }) {
  const out = [];
  out.push(`# Warp macro benchmark - ${date}`);
  out.push('');
  out.push(`Profile: **${profileName}**. Proxies: ${results.map((r) => r.label).join(', ')}.`);
  out.push('');
  if (env.isWsl) {
    out.push('> This run was on WSL2. Timing numbers are not credible here (no CPU isolation, hypervisor scheduling noise). Treat this as a functional run only. See docs/benchmarks/README.md, hardware tiers.');
    out.push('');
  }
  out.push('## How to read this');
  out.push('');
  out.push('Every number is measured from outside the proxy (the kernel\'s view in /proc and bot-side timing), never from the proxy\'s own metrics. Latency is measured from the intended send time to guard against coordinated omission. Each scenario warmed up before measuring and was repeated; tables show the median. Raw per-second samples are in the CSV files next to this report, and the exact configs under `configs/`.');
  out.push('');

  // --- Fairness / natives ---
  out.push('## Fairness and natives');
  out.push('');
  out.push(results.map(fairnessRow).join('\n\n'));
  out.push('');
  out.push(`CPU pinning: ${cpuNote}`);
  out.push('');

  // --- Idle ---
  if (results.some((r) => r.metrics.idle)) {
    out.push('## Idle connections');
    out.push('');
    out.push('Resident memory, open file descriptors and CPU with N idle connections held in the PLAY state. This isolates the per-connection cost.');
    out.push('');
    out.push('> Note: the JVM proxies run with `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` (the identical headline flags), so their resident memory is pinned near 2 GiB from start-up regardless of load. For the JVM proxies, read the growth between levels, not the absolute RSS; the absolute figure is meaningful only for the Go proxy (Gate), which sizes its heap to the live set. A later revision measures heap-live for the JVM proxies.');
    out.push('');
    const levels = [...new Set(results.flatMap((r) => (r.metrics.idle?.levels ?? []).map((l) => l.level)))].sort((a, b) => a - b);
    out.push(`| Proxy | ${levels.map((l) => `RSS @ ${l}`).join(' | ')} | FDs @ ${levels.at(-1)} | fail |`);
    out.push(`|---|${levels.map(() => '--:').join('|')}|--:|--:|`);
    for (const r of results) {
      const byLevel = Object.fromEntries((r.metrics.idle?.levels ?? []).map((l) => [l.level, l]));
      const cells = levels.map((l) => (byLevel[l] ? `${num(byLevel[l].rssMiB, 0)} MiB` : '-'));
      const top = byLevel[levels.at(-1)];
      out.push(`| ${r.label} | ${cells.join(' | ')} | ${top ? num(top.fds, 0) : '-'} | ${top ? top.failures : '-'} |`);
    }
    out.push('');
    const topLevel = levels.at(-1);
    out.push(barChart(`RSS at ${topLevel} idle connections (MiB)`, results.map((r) => ({ label: r.label, value: (r.metrics.idle?.levels ?? []).find((l) => l.level === topLevel)?.rssMiB })), { unit: ' MiB', digits: 0 }));
    out.push('');
  }

  // --- Traffic ---
  if (results.some((r) => r.metrics.traffic)) {
    out.push('## Heavy traffic (moving bots, loading chunks)');
    out.push('');
    out.push('Bots walk and load chunks so the proxy forwards a real clientbound stream. Throughput is the proxy\'s read+write bytes per second at the syscall boundary; CPU per MB is proxy CPU-seconds divided by MB forwarded in the window.');
    out.push('');
    out.push('| Proxy | bots | throughput | CPU % | CPU-s/MB | RSS |');
    out.push('|---|--:|--:|--:|--:|--:|');
    for (const r of results) {
      const m = r.metrics.traffic;
      if (!m) {
        out.push(`| ${r.label} | - | - | - | - | - |`);
        continue;
      }
      out.push(`| ${r.label} | ${m.online} | ${bytesHuman(m.bytesPerSec)} | ${num(m.cpuPct, 0)} | ${num(m.cpuPerMb, 3)} | ${num(m.rssMiB, 0)} MiB |`);
    }
    out.push('');
    out.push(barChart('CPU seconds per MB forwarded (lower is better)', results.map((r) => ({ label: r.label, value: r.metrics.traffic?.cpuPerMb })), { digits: 3 }));
    out.push('');
    out.push(lineChart('Proxy CPU during traffic', results.filter((r) => r.rows?.length).map((r) => ({ label: r.label, rows: (r.rows ?? []).filter((x) => x.scenario === 'traffic') })), 'cpuPct', { unit: '%' }));
    out.push('');
  }

  // --- Latency ---
  if (results.some((r) => r.metrics.latency)) {
    out.push('## Added latency at low load');
    out.push('');
    out.push('Chat round-trip through the proxy versus straight to the backend. Added latency is the difference; it is the proxy\'s own cost on the path.');
    out.push('');
    out.push('| Proxy | via proxy p50 | via proxy p99 | direct p50 | added p50 | added p99 |');
    out.push('|---|--:|--:|--:|--:|--:|');
    for (const r of results) {
      const m = r.metrics.latency;
      if (!m) {
        out.push(`| ${r.label} | - | - | - | - | - |`);
        continue;
      }
      out.push(`| ${r.label} | ${num(m.viaProxy.p50, 1)} ms | ${num(m.viaProxy.p99, 1)} ms | ${num(m.direct.p50, 1)} ms | ${num(m.added.p50, 1)} ms | ${num(m.added.p99, 1)} ms |`);
    }
    out.push('');
    out.push(barChart('Added p99 latency (ms, lower is better)', results.map((r) => ({ label: r.label, value: r.metrics.latency?.added.p99 })), { unit: ' ms', digits: 1 }));
    out.push('');
  }

  // --- Switch ---
  if (results.some((r) => r.metrics.switch)) {
    out.push('## Switch storm');
    out.push('');
    out.push('Bots repeatedly run /server to change backend. Reports sustained switches per second, failures, and how long a switch took: from the command to the first chunk of the new backend (proxy hand-off plus backend login), and to five chunks (world arriving).');
    out.push('');
    out.push('| Proxy | bots | switches | /s | failures | first chunk p50 | 5 chunks p50 | 5 chunks p99 |');
    out.push('|---|--:|--:|--:|--:|--:|--:|--:|');
    for (const r of results) {
      const m = r.metrics.switch;
      if (!m) {
        out.push(`| ${r.label} | - | - | - | - | - | - | - |`);
        continue;
      }
      if (m.skipped) {
        out.push(`| ${r.label} | - | - | - | - | - | - | ${m.skipped} |`);
        continue;
      }
      out.push(`| ${r.label} | ${m.online} | ${m.switches} | ${num(m.switchesPerSec, 1)} | ${m.failures} | ${num(m.firstChunkP50Ms, 0)} ms | ${num(m.p50Ms, 0)} ms | ${num(m.p99Ms, 0)} ms |`);
    }
    out.push('');
  }

  // --- Storm ---
  if (results.some((r) => r.metrics.storm)) {
    out.push('## Connection / status storm');
    out.push('');
    out.push('Hostile connections at a rising rate while a genuine player tries to join. The key question is whether the real player still gets in, and at what CPU and memory cost.');
    out.push('');
    const rates = [...new Set(results.flatMap((r) => (r.metrics.storm?.rates ?? []).map((x) => x.rate)))].sort((a, b) => a - b);
    out.push(`| Proxy | ${rates.map((x) => `joined @ ${x}/s`).join(' | ')} | peak CPU % |`);
    out.push(`|---|${rates.map(() => ':--:').join('|')}|--:|`);
    for (const r of results) {
      const byRate = Object.fromEntries((r.metrics.storm?.rates ?? []).map((x) => [x.rate, x]));
      const cells = rates.map((x) => (byRate[x] ? (byRate[x].joined ? 'yes' : 'NO') : '-'));
      const cpu = Math.max(0, ...(r.metrics.storm?.rates ?? []).map((x) => x.cpuPct ?? 0));
      out.push(`| ${r.label} | ${cells.join(' | ')} | ${num(cpu, 0)} |`);
    }
    out.push('');
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
  for (const [id, p] of Object.entries(versions.proxies)) {
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
