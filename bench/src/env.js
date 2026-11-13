// Records the machine the benchmark ran on, so a reader can judge the numbers and reproduce them,
// and plans how to pin the proxy, the backends and the bots to different CPUs on a single machine.
// The fingerprint is written next to the results as env.json. None of this gates a run; a run on an
// unsuitable host still produces numbers, with the caveats stated in the report.
import { execFileSync } from 'node:child_process';
import { cpus, totalmem, hostname, release, arch, platform } from 'node:os';
import { readFileSync } from 'node:fs';

function tryRead(path) {
  try {
    return readFileSync(path, 'utf8').trim();
  } catch {
    return null;
  }
}

function tryExec(cmd, args) {
  try {
    return execFileSync(cmd, args, { encoding: 'utf8' }).trim();
  } catch {
    return null;
  }
}

/** A snapshot of the host: CPU, memory, kernel, governor, relevant sysctls, and tool versions. */
export function fingerprint({ harnessSha, proxies }) {
  const list = cpus();
  const governor = tryRead('/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor');
  const thp = tryRead('/sys/kernel/mm/transparent_hugepage/enabled');
  const sysctl = (key) => tryExec('sysctl', ['-n', key]);
  return {
    date: new Date().toISOString(),
    host: hostname(),
    platform: platform(),
    arch: arch(),
    kernel: release(),
    cpuModel: list[0]?.model ?? 'unknown',
    cpuCount: list.length,
    memTotalGiB: Math.round((totalmem() / 1024 ** 3) * 10) / 10,
    governor,
    transparentHugepage: thp,
    sysctl: {
      'net.ipv4.ip_local_port_range': sysctl('net.ipv4.ip_local_port_range'),
      'net.core.somaxconn': sysctl('net.core.somaxconn'),
      'fs.file-max': sysctl('fs.file-max'),
      'net.ipv4.ip_local_reserved_ports': sysctl('net.ipv4.ip_local_reserved_ports'),
    },
    ulimitNofile: tryExec('bash', ['-c', 'ulimit -n']),
    node: process.version,
    java: tryExec('java', ['-version']) ?? tryExec('bash', ['-c', 'java -version 2>&1']),
    isWsl: (release().toLowerCase().includes('microsoft') || release().toLowerCase().includes('wsl')),
    harnessSha,
    proxies,
  };
}

/** Fewer cores than this cannot give the proxy, the backends and the load their own. */
export const MIN_CORES_TO_PIN = 6;

/**
 * Plans CPU pinning for single-machine runs, from the core count alone so the same harness fits a
 * 6-core and a 48-core machine: a quarter of the cores for the proxy (at least 2), a quarter for the
 * load generators (at least 2: one core per load worker, and the last one for the latency probe
 * alone), and the rest, about half the machine, for the backends, which are the heaviest part and
 * must never be the bottleneck. No core is in two groups, so nothing shares a core with the proxy.
 * With too few cores, or when pinning is off, nothing is pinned, a few unpinned load workers are
 * used, and the report says that proxy and load shared the machine.
 * @param {number} cores total cores
 * @param {boolean} enable whether pinning was requested
 * @returns {{proxy: string[]|null, backends: string[]|null, bots: string[]|null, workers: (string|null)[], probe: string|null, note: string}}
 */
export function cpuPlan(cores, enable) {
  if (!enable || cores < MIN_CORES_TO_PIN) {
    return {
      proxy: null,
      backends: null,
      bots: null,
      workers: Array.from({ length: Math.min(4, Math.max(1, Math.floor(cores / 2))) }, () => null),
      probe: null,
      note:
        cores < MIN_CORES_TO_PIN
          ? `CPU pinning off: ${cores} cores is too few to isolate proxy, backends and bots (need >= ${MIN_CORES_TO_PIN}).`
          : 'CPU pinning off (not requested).',
    };
  }
  const proxyN = Math.max(2, Math.floor(cores / 4));
  const botN = Math.max(2, Math.floor(cores / 4));
  const ids = Array.from({ length: cores }, (_, i) => String(i));
  const proxy = ids.slice(0, proxyN);
  const backends = ids.slice(proxyN, cores - botN);
  const bots = ids.slice(cores - botN);
  const range = (list) => (list.length > 1 ? `${list[0]}-${list.at(-1)}` : list[0]);
  return {
    proxy,
    backends,
    bots,
    workers: bots.slice(0, -1),
    probe: bots.at(-1),
    note: `CPU pinning on (${cores} cores): proxy on ${range(proxy)}, backends on ${range(backends)}, ${bots.length - 1} load worker(s) on ${range(bots.slice(0, -1))}, latency probe on ${bots.at(-1)}; the harness itself runs on the load cores.`,
  };
}

/**
 * The listening ports of the harness that the kernel may also hand out as the source port of an
 * outgoing connection. On a host tuned for many connections (`ip_local_port_range` widened to
 * 1024-65535) a client socket of the previous proxy's run can sit on the port a backend or the proxy
 * is about to listen on, and the bind then fails in the middle of a run. Ports listed in
 * `ip_local_reserved_ports` are never handed out, so they are safe.
 * @param {number[]} ports the ports the harness listens on
 * @param {string|null} range contents of /proc/sys/net/ipv4/ip_local_port_range ("low high")
 * @param {string|null} reserved contents of /proc/sys/net/ipv4/ip_local_reserved_ports ("a,b-c")
 * @returns {number[]} the ports at risk (empty when the range is unknown)
 */
export function ephemeralConflicts(ports, range, reserved) {
  const [low, high] = (range ?? '').trim().split(/\s+/).map(Number);
  if (!Number.isFinite(low) || !Number.isFinite(high)) return [];
  const spans = (reserved ?? '')
    .trim()
    .split(',')
    .filter(Boolean)
    .map((item) => item.split('-').map(Number))
    .map(([from, to = from]) => [from, to]);
  const isReserved = (port) => spans.some(([from, to]) => port >= from && port <= to);
  return ports.filter((port) => port >= low && port <= high && !isReserved(port));
}

/** The ports at risk on this host (see {@link ephemeralConflicts}). */
export function ephemeralConflictsHere(ports) {
  return ephemeralConflicts(ports, tryRead('/proc/sys/net/ipv4/ip_local_port_range'), tryRead('/proc/sys/net/ipv4/ip_local_reserved_ports'));
}
