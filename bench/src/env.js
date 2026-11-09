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
    },
    ulimitNofile: tryExec('bash', ['-c', 'ulimit -n']),
    node: process.version,
    java: tryExec('java', ['-version']) ?? tryExec('bash', ['-c', 'java -version 2>&1']),
    isWsl: (release().toLowerCase().includes('microsoft') || release().toLowerCase().includes('wsl')),
    harnessSha,
    proxies,
  };
}

/**
 * Plans CPU pinning for single-machine runs: the proxy on its own cores, the backends on others, the
 * bots on the rest. With too few cores (or on WSL, where taskset does not truly isolate), returns no
 * pinning and the report notes that proxy and load shared the machine.
 * @param {number} cores total cores
 * @param {boolean} enable whether pinning was requested
 * @returns {{proxy: string[]|null, backends: string[]|null, bots: string[]|null, note: string}}
 */
export function cpuPlan(cores, enable) {
  if (!enable || cores < 6) {
    return {
      proxy: null,
      backends: null,
      bots: null,
      note:
        cores < 6
          ? `CPU pinning off: ${cores} cores is too few to isolate proxy, backends and bots (need >= 6).`
          : 'CPU pinning off (not requested).',
    };
  }
  // Proxy: a quarter of the cores (at least 2). Backends: the next quarter. Bots: the rest.
  const proxyN = Math.max(2, Math.floor(cores / 4));
  const backendN = Math.max(2, Math.floor(cores / 4));
  const ids = Array.from({ length: cores }, (_, i) => String(i));
  return {
    proxy: ids.slice(0, proxyN),
    backends: ids.slice(proxyN, proxyN + backendN),
    bots: ids.slice(proxyN + backendN),
    note: `CPU pinning on: proxy on cores ${ids.slice(0, proxyN).join(',')}, backends on ${ids.slice(proxyN, proxyN + backendN).join(',')}, bots on the rest.`,
  };
}
